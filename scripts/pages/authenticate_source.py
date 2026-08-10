#!/usr/bin/env python3
"""Authenticate the original packaged run behind a raw or compact Pages bundle."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.ci.e2e_job_graph import (  # noqa: E402
    ExpectedJob,
    JobGraphError,
    expected_jobs,
    validate_jobs,
)
from scripts.lib.secure_json import SecureJsonError, read as read_secure_json  # noqa: E402
from scripts.pages.evidence import (  # noqa: E402
    E2E_WORKFLOW,
    EvidenceError,
    _validate_provenance,
)
from scripts.pages.select_artifact import (  # noqa: E402
    GitHubApi,
    REPOSITORY_PATTERN,
    SelectionError,
    _validate_run,
)
from scripts.release.matrix import MatrixError  # noqa: E402

MAX_MANIFEST_BYTES = 2 * 1024 * 1024


class SourceAuthenticationError(RuntimeError):
    """Raised when a public bundle lacks an exact deterministic source run."""


def _manifest(root: Path) -> tuple[dict[str, Any], str]:
    candidates = ((root / "pages-evidence.json", "raw"), (root / "manifest.json", "compact"))
    found = [(path, kind) for path, kind in candidates if path.exists() or path.is_symlink()]
    if len(found) != 1:
        raise SourceAuthenticationError("evidence must contain exactly one raw or compact manifest")
    path, kind = found[0]
    try:
        value, _ = read_secure_json(path, label="Pages provenance manifest", max_bytes=MAX_MANIFEST_BYTES)
    except SecureJsonError as exc:
        raise SourceAuthenticationError(str(exc)) from exc
    if not isinstance(value, dict) or value.get("kind") != (
        "raw-packaged-e2e" if kind == "raw" else "compact-pages-evidence"
    ):
        raise SourceAuthenticationError("Pages provenance manifest kind is invalid")
    try:
        _validate_provenance(value.get("provenance"))
    except EvidenceError as exc:
        raise SourceAuthenticationError(str(exc)) from exc
    return value, kind


def _expected_jobs(
    matrix_path: Path, event: str, source_branch: str | None = None
) -> tuple[ExpectedJob, ...]:
    try:
        return expected_jobs(
            matrix_path,
            "on-demand-e2e.yml",
            event=event,
            source_branch=source_branch,
        )
    except JobGraphError as exc:
        raise SourceAuthenticationError(str(exc)) from exc


def _run_attempt(run: dict[str, Any], expected: int, label: str) -> None:
    if run.get("run_attempt") != expected:
        raise SourceAuthenticationError(f"{label} run attempt is stale")


def authenticate(
    api: GitHubApi,
    *,
    repository: str,
    canonical_branch: str,
    matrix_path: Path,
    evidence_root: Path,
    selected_kind: str,
    selected_run_id: int,
    selected_run_attempt: int,
    expected_handoff_run_id: int,
    expected_handoff_run_attempt: int,
) -> dict[str, Any]:
    manifest, manifest_kind = _manifest(evidence_root)
    if manifest_kind != selected_kind:
        raise SourceAuthenticationError("selected artifact kind disagrees with its manifest")
    provenance = manifest["provenance"]
    handoff = provenance["handoff"]
    packaged = provenance["packaged"]
    if (
        handoff["run_id"] != expected_handoff_run_id
        or handoff["run_attempt"] != expected_handoff_run_attempt
    ):
        raise SourceAuthenticationError(
            "evidence does not derive from the newest exact-head E2E run attempt"
        )
    workflow = api.workflow("on-demand-e2e.yml")

    if selected_kind == "raw" and (
        handoff["run_id"] != selected_run_id
        or handoff["run_attempt"] != selected_run_attempt
    ):
        raise SourceAuthenticationError("raw artifact owner differs from handoff provenance")

    handoff_run = api.run(handoff["run_id"])
    handoff_events = (
        frozenset({"schedule", "workflow_dispatch"})
        if provenance["branch"] == canonical_branch
        else frozenset({"workflow_dispatch"})
    )
    try:
        _validate_run(
            handoff_run,
            workflow_id=workflow["id"],
            workflow_path=E2E_WORKFLOW,
            repository=repository,
            branch=provenance["branch"],
            sha=provenance["commit"],
            events=handoff_events,
            require_success=True,
        )
    except SelectionError as exc:
        raise SourceAuthenticationError(str(exc)) from exc
    _run_attempt(handoff_run, handoff["run_attempt"], "handoff")

    packaged_run = api.run(packaged["run_id"])
    packaged_events = (
        frozenset({"schedule", "workflow_dispatch"})
        if packaged["branch"] == canonical_branch
        else frozenset({"workflow_dispatch"})
    )
    try:
        _validate_run(
            packaged_run,
            workflow_id=workflow["id"],
            workflow_path=E2E_WORKFLOW,
            repository=repository,
            branch=packaged["branch"],
            sha=packaged["commit"],
            events=packaged_events,
            require_success=True,
        )
    except SelectionError as exc:
        raise SourceAuthenticationError(str(exc)) from exc
    _run_attempt(packaged_run, packaged["run_attempt"], "packaged source")
    if api.commit_tree(packaged["commit"]) != packaged["tree"]:
        raise SourceAuthenticationError("packaged source commit/tree identity is stale")
    jobs = api.jobs_for_attempt(packaged["run_id"], packaged["run_attempt"])
    try:
        graph = validate_jobs(
            jobs,
            expected=_expected_jobs(
                matrix_path,
                str(packaged_run.get("event")),
                source_branch=packaged["branch"],
            ),
            run_attempt=packaged["run_attempt"],
        )
    except (JobGraphError, MatrixError) as exc:
        raise SourceAuthenticationError(str(exc)) from exc

    attested = (handoff["run_id"], handoff["run_attempt"]) != (
        packaged["run_id"],
        packaged["run_attempt"],
    )
    if attested:
        handoff_jobs = api.jobs_for_attempt(handoff["run_id"], handoff["run_attempt"])
        attestation_jobs = [
            job
            for job in handoff_jobs
            if isinstance(job.get("name"), str)
            and job["name"].endswith(" / Verify exact tested tree")
            and job.get("run_attempt") == handoff["run_attempt"]
            and job.get("status") == "completed"
            and job.get("conclusion") == "success"
        ]
        if len(attestation_jobs) != 1:
            raise SourceAuthenticationError("handoff run lacks one successful exact-tree attestation job")
    return {
        "schema_version": 1,
        "attested": attested,
        "handoff_run_id": handoff["run_id"],
        "packaged_run_id": packaged["run_id"],
        "packaged_job_graph_sha256": graph["sha256"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--canonical-branch", required=True)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--selected-kind", choices=("raw", "compact"), required=True)
    parser.add_argument("--selected-run-id", type=int, required=True)
    parser.add_argument("--selected-run-attempt", type=int, required=True)
    parser.add_argument("--expected-handoff-run-id", type=int, required=True)
    parser.add_argument("--expected-handoff-run-attempt", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        if REPOSITORY_PATTERN.fullmatch(args.repository) is None:
            raise SourceAuthenticationError("repository must use owner/name form")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise SourceAuthenticationError("GH_TOKEN is required")
        api = GitHubApi(
            repository=args.repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        result = authenticate(
            api,
            repository=args.repository,
            canonical_branch=args.canonical_branch,
            matrix_path=args.matrix,
            evidence_root=args.evidence,
            selected_kind=args.selected_kind,
            selected_run_id=args.selected_run_id,
            selected_run_attempt=args.selected_run_attempt,
            expected_handoff_run_id=args.expected_handoff_run_id,
            expected_handoff_run_attempt=args.expected_handoff_run_attempt,
        )
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except (
        EvidenceError,
        JobGraphError,
        MatrixError,
        OSError,
        SelectionError,
        SourceAuthenticationError,
        ValueError,
    ) as exc:
        print(f"Pages source authentication error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
