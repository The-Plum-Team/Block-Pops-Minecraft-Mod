"""Acquire current lane evidence; release planning and publication are separate."""

import hashlib
import os
import re
import stat
import subprocess
import uuid
from contextlib import ExitStack, contextmanager
from pathlib import Path

from scripts.ci.e2e_fanin import SourceIdentity
from scripts.ci.loader_bootstrap import validate_commit as validate_loader_bootstrap
from scripts.lib import atomic_directory as atomic
from scripts.lib.secure_json import canonical_json, read
from scripts.release.artifact_manifest import scoped_manifest_context
from scripts.release.build_matrix import source_snapshot
from scripts.release.content_evidence import read_lane_content_evidence
from scripts.release.evidence_archive import _stamp, _verify_stage, download_evidence_archive
from scripts.release.run_evidence import read_current_evidence
from scripts.release.matrix import MAX_MATRIX_BYTES, normalize_matrix_inventory, valid_branch_name


class ReleaseEvidenceError(ValueError):
    """Current transport, source or content bindings did not remain consistent."""


def _check(condition, message):
    if not condition:
        raise ReleaseEvidenceError(message)


def authenticate_canonical_checkout(api):
    """Observe this implementation's exact bytes on the current GitHub default.

    No path, expected SHA or local receipt chooses the controller. This proves
    current default-branch content, not AUTH-1 admission, governance completeness,
    an active restricted-transition generation, qualification or publication.
    The later caller must repeat this observation inside its final evidence lease.
    """
    try:
        implementation = Path(__file__).resolve(strict=True)
        repository = implementation.parents[2]
        _check(implementation == repository / "scripts/release/plan_release.py",
               "implementation must occupy its protected repository path")
        _check(api.api_url == "https://api.github.com" and isinstance(api.repository, str)
               and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", api.repository),
               "canonical authentication requires the exact GitHub API origin/repository")

        def environment():
            _check(not any(name.startswith("GIT_") for name in os.environ),
                   "canonical authentication rejects inherited Git controls")

        def remote():
            environment()
            record = api.get(f"/repos/{api.repository}")
            _check(isinstance(record, dict) and record.get("full_name") == api.repository,
                   "repository API identity differs")
            branch = record.get("default_branch")
            _check(valid_branch_name(branch), "repository API default branch is invalid")
            head = api.branch_head(branch)
            _check(isinstance(head, tuple) and len(head) == 2 and all(isinstance(value, str)
                   and re.fullmatch("[0-9a-f]{40}", value) for value in head),
                   "default branch API commit/tree is invalid")
            environment()
            return {"repository": api.repository, "default_branch": branch,
                    "commit": head[0], "tree": head[1]}

        def local(expected):
            environment()
            snapshot = source_snapshot(repository)
            _check(snapshot["dirty"] is False and (snapshot["commit"], snapshot["tree"])
                   == (expected["commit"], expected["tree"]),
                   "implementation is not the exact clean API default checkout")
            matrix, raw = read(repository / "release/release-matrix.json",
                               label="canonical matrix", max_bytes=MAX_MATRIX_BYTES)
            inventory = normalize_matrix_inventory(matrix)
            branch = matrix["branch"]
            _check(inventory.schema_version == 2 and branch["name"] == branch["canonical"]
                   == expected["default_branch"] and branch["role"] == "integration"
                   and matrix["project"]["sources"].rstrip("/") == "https://github.com/" + api.repository,
                   "matrix does not identify the canonical repository/default")
            files = {row["path"]: row for row in snapshot["files"]}
            _check(files["release/release-matrix.json"]["sha256"] == hashlib.sha256(raw).hexdigest(),
                   "matrix bytes differ from the clean source snapshot")
            bootstrap = validate_loader_bootstrap(repository, head_sha=expected["commit"],
                                                 contract_sha=expected["commit"])
            _check(bootstrap["matrix_sha256"] == hashlib.sha256(raw).hexdigest()
                   and bootstrap["contract_sha256"] == files["e2e/loader-bootstrap-contract.json"]["sha256"],
                   "bootstrap differs from the observed source inputs")
            environment()
            _check(canonical_json(source_snapshot(repository)) == canonical_json(snapshot),
                   "implementation source changed during authentication")
            return {"checkout": str(repository), "source_fingerprint": snapshot["fingerprint"],
                "index_sha256": snapshot["index_sha256"], "bootstrap": bootstrap,
                **{label: files[path] for label, path in (
                    ("implementation", "scripts/release/plan_release.py"),
                    ("matrix", "release/release-matrix.json"),
                    ("scenario_contract", "e2e/scenario-contract.json"))}}

        observed = remote()
        inputs = local(observed)
        _check(canonical_json(remote()) == canonical_json(observed),
               "repository default identity changed during authentication")
        _check(canonical_json(local(observed)) == canonical_json(inputs),
               "canonical source inputs changed during authentication")
        return {"schema_version": 1, "kind": "blockpops-canonical-checkout-observation",
                **observed, **inputs}
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError,
            subprocess.SubprocessError) as exc:
        raise ReleaseEvidenceError(f"canonical checkout authentication failed: {exc}") from exc


@contextmanager
def _verify_download(path, receipt):
    """Recheck every API-bound extracted byte, including reports and empty logs."""
    parent = atomic._directory_fd(path.parent)
    root = None
    try:
        root = atomic._directory_fd(Path(path.name), root_fd=parent)
        stamps, directories = {}, set()
        for record in receipt["files"]:
            relative = Path(record["path"])
            directories.update(str(item) for item in relative.parents if str(item) != ".")
            directory = atomic._directory_fd(relative.parent, root_fd=root)
            try:
                descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                with os.fdopen(descriptor, "rb") as source:
                    before = os.fstat(source.fileno())
                    _check(stat.S_ISREG(before.st_mode) and before.st_nlink == 1
                           and before.st_size == record["bytes"], "downloaded file identity/size differs")
                    _check(hashlib.file_digest(source, "sha256").hexdigest() == record["sha256"]
                           and _stamp(os.fstat(source.fileno())) == _stamp(before), "downloaded bytes changed")
                    stamps[record["path"]] = _stamp(before)
            finally:
                os.close(directory)
        def unchanged():
            _verify_stage(root, stamps, directories)
            atomic._bound_output_directory(path, parent, path.name, root)
        unchanged()
        yield unchanged
    finally:
        if root is not None: os.close(root)
        os.close(parent)


def acquire_lane_evidence(api, *, repository, matrix_path, expected_matrix_sha256, artifact_node,
                          build_scope, e2e_scope, source_repository, canonical_branch,
                          controller_sha, branch, commit, tree):
    """Join live API metadata, authenticated ZIPs and exact clean-checkout content.

    The caller must authenticate the deployed controller generation and source
    Git identity independently. This API does not turn an arbitrary API origin,
    local JSON receipt or candidate controller into that authority. It returns no
    qualification/publication decision. Four unique build children retain acquired
    diagnostics on failure; no caller directory is replaced or recursively removed.
    """
    try:
        repository = Path(repository).resolve()
        matrix_path = Path(matrix_path).absolute()
        identity = dict(repository=source_repository, canonical_branch=canonical_branch,
                        controller_sha=controller_sha, branch=branch, commit=commit, tree=tree)
        scopes = {"build": build_scope, "e2e": e2e_scope}
        workflows = {"build": "build-gate.yml", "e2e": "on-demand-e2e.yml"}

        def contexts():
            headers = {}
            for producer, scope in scopes.items():
                _, header, rows = scoped_manifest_context(repository, matrix_path, scope=scope,
                    artifact_node=artifact_node if scope == "lane" else None)
                _check(header["matrix"]["sha256"] == expected_matrix_sha256
                       and (header["git_commit"], header["git_tree"], header["release_branch"])
                       == (commit, tree, branch) and artifact_node in {row["artifact_node"] for row in rows},
                       "clean source/matrix/scope differs from external identity")
                headers[producer] = header
            return canonical_json(headers)

        def observe():
            return {producer: read_current_evidence(api, matrix_path=matrix_path,
                expected_matrix_sha256=expected_matrix_sha256, scope=scope,
                artifact_node=artifact_node if scope == "lane" else None,
                workflow=workflows[producer], **identity) for producer, scope in scopes.items()}

        source = contexts()
        observed = observe()
        paths, receipts = {}, {}
        prefix = "release-evidence-" + uuid.uuid4().hex
        for producer, evidence in observed.items():
            for kind, artifact in evidence["artifacts"].items():
                key = producer + "-" + kind
                paths[key] = repository / "build" / (prefix + "-" + key)
                receipts[key] = download_evidence_archive(repository=source_repository, artifact_id=artifact["id"],
                    kind=kind, expected_digest=artifact["digest"], expected_size=artifact["size"],
                    output=paths[key], token=api.token, api_url=api.api_url)

        def digest(key, filename):
            return next(row["sha256"] for row in receipts[key]["files"] if row["path"] == filename)

        run = observed["e2e"]["run"]
        e2e_identity = SourceIdentity(source_repository, branch, commit, tree, run["run_id"], run["run_attempt"],
            "scheduled-anchors" if run["event"] == "schedule" else "pr-anchors")
        content = read_lane_content_evidence(repository=repository, matrix_path=matrix_path,
            expected_matrix_sha256=expected_matrix_sha256, artifact_node=artifact_node,
            build_stage=paths["build-bundle"], build_scope=build_scope,
            expected_build_manifest_sha256=digest("build-bundle", "artifacts.json"),
            build_report_path=paths["build-report"] / "build-matrix-report.json",
            expected_build_report_sha256=digest("build-report", "build-matrix-report.json"),
            e2e_stage=paths["e2e-bundle"], e2e_scope=e2e_scope,
            expected_e2e_manifest_sha256=digest("e2e-bundle", "artifacts.json"),
            aggregate_root=paths["e2e-aggregate"], expected_aggregate_sha256=digest("e2e-aggregate", "aggregate.json"),
            expected_e2e_identity=e2e_identity)
        _check(canonical_json(observe()) == canonical_json(observed), "producers changed during acquisition")
        with ExitStack() as leases:
            checks = [leases.enter_context(_verify_download(path, receipts[key])) for key, path in paths.items()]
            _check(contexts() == source, "source context changed during acquisition")
            for unchanged in checks:
                unchanged()
        return {"schema_version": 1, "kind": "blockpops-acquired-lane-evidence", "content": content,
                "producers": observed, "downloads": receipts, "paths": {key: str(path) for key, path in paths.items()}}
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError, StopIteration) as exc:
        raise ReleaseEvidenceError(f"lane acquisition failed: {exc}") from exc
