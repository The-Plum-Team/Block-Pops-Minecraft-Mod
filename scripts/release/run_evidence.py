"""Bind live producer jobs and immutable artifact locators, without qualification."""

import hashlib
import re
import tempfile
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

from scripts.ci.e2e_job_graph import expected_jobs, validate_jobs
from scripts.lib.secure_json import canonical_json, read
from scripts.pages.select_artifact import Artifact, GitHubApi, SelectionError
from scripts.release.matrix import MAX_MATRIX_BYTES, MatrixDocument, normalize_matrix_inventory
from scripts.release.run_selection import _check, select_current_run

ARTIFACT_LIMITS = {"bundle": 256 * 1024 * 1024, "report": 8 * 1024 * 1024,
                   "aggregate": 256 * 1024 * 1024}


def read_current_evidence(api: GitHubApi, *, matrix_path: Path, expected_matrix_sha256: str,
                          scope: str, artifact_node: str | None = None, **identity) -> dict:
    """Use externally authenticated matrix/source/controller identities and scope.

    This authenticates API metadata only. Download by these IDs/digests, validate
    the complete bundle/report/aggregate, then call again and compare before any
    release plan. Neither a local receipt nor this return value qualifies a lane.
    Controller deployment and the matrix's exact Git binding remain caller duties.
    """
    try:
        data, raw = read(matrix_path, label="release evidence matrix", max_bytes=MAX_MATRIX_BYTES)
        _check(isinstance(expected_matrix_sha256, str) and re.fullmatch("[0-9a-f]{64}", expected_matrix_sha256)
               and hashlib.sha256(raw).hexdigest() == expected_matrix_sha256, "matrix byte binding differs")
        document = MatrixDocument(normalize_matrix_inventory(data), canonical_json(data).decode())
        _check(document.inventory.schema_version == 2, "release evidence requires schema2")
        _check(document.branch_name == identity.get("branch")
               and data["branch"]["canonical"] == identity.get("canonical_branch"), "matrix/source/controller branch differs")
        _check(scope in {"legacy", "lane", "full"}, "explicit release evidence scope is required")
        lanes = document.select_lanes(scope=scope, artifact_node=artifact_node)
        run = select_current_run(api, **identity)
        commit, attempt, run_id = run["commit"], run["run_attempt"], run["run_id"]
        names = ({"bundle": f"staged-release-bundle-{commit}-{attempt}",
                  "report": f"build-run-report-{commit}-{attempt}"} if run["workflow"] == "build-gate.yml" else
                 {"bundle": f"e2e-input-bundle-{commit}-{attempt}",
                  "aggregate": f"packaged-e2e-{commit}-{attempt}-aggregate"})
        # The graph reader sees these exact authenticated bytes, never a second
        # mutable-path read that could select a different graph and then revert.
        with tempfile.TemporaryDirectory(prefix="blockpops-release-graph-") as temporary:
            snapshot_path = Path(temporary) / "matrix.json"
            snapshot_path.write_bytes(raw)
            expected = expected_jobs(snapshot_path, run["workflow"], event=run["event"],
                source_branch=run["branch"], scope=scope, artifact_node=artifact_node)

        def artifacts():
            rows = api.artifacts_for_run(run_id)
            _check(isinstance(rows, list) and len(rows) <= 1000
                   and all(isinstance(row, Artifact) for row in rows), "invalid artifact inventory")
            _check(len({row.id for row in rows}) == len(rows) == len({row.name for row in rows}),
                   "duplicate artifact ID or name")
            selected = {}
            for kind, name in names.items():
                matches = [row for row in rows if row.name == name]
                _check(len(matches) == 1, "missing or ambiguous exact " + kind + " artifact")
                row = matches[0]
                direct = Artifact.parse(api.get(f"/repos/{api.repository}/actions/artifacts/{row.id}"))
                _check(row == direct and canonical_json(asdict(row)) == canonical_json(asdict(direct)),
                       "artifact changed at its immutable ID")
                _check(row.run_id == run_id and not row.expired and row.head_branch == run["controller_branch"]
                       and row.head_sha == run["controller_sha"] and row.size <= ARTIFACT_LIMITS[kind]
                       and row.order[0] >= datetime.fromisoformat(run["created_at"].replace("Z", "+00:00")),
                       "artifact owner/controller/time/size differs")
                selected[kind] = asdict(direct)
            return selected

        def observe():
            jobs = api.jobs_for_attempt(run_id, attempt)
            _check(isinstance(jobs, list) and 0 < len(jobs) <= 1000 and all(isinstance(job, dict)
                   and type(job.get("run_id")) is int and job["run_id"] == run_id
                   and type(job.get("run_attempt")) is int and job["run_attempt"] == attempt for job in jobs),
                   "jobs do not belong to the exact producer attempt")
            graph = validate_jobs(jobs, expected=expected, run_attempt=attempt)
            bindings = [{key: job[key] for key in ("id", "name", "status", "conclusion", "run_id", "run_attempt")}
                        for job in sorted(jobs, key=lambda job: job["id"])]
            return {"jobs": graph, "job_identities": bindings, "artifacts": artifacts()}

        observed = observe()
        _check(canonical_json(observe()) == canonical_json(observed), "producer jobs or artifacts changed")
        _check(canonical_json(select_current_run(api, **identity)) == canonical_json(run),
               "producer identity changed while reading evidence")
        _check(read(matrix_path, label="final release matrix", max_bytes=MAX_MATRIX_BYTES)[1] == raw,
               "release matrix changed while reading evidence")
        return {"run": run, "matrix_sha256": expected_matrix_sha256,
                "selection": {"scope": scope, "artifact_node": artifact_node,
                              "artifact_nodes": [lane.identity.artifact_node for lane in lanes]}, **observed}
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        raise SelectionError(f"invalid current producer metadata: {exc}") from exc
