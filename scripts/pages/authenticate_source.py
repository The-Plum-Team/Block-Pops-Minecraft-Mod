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
    cache_artifact_name,
    raw_artifact_name,
)
from scripts.pages.select_artifact import (  # noqa: E402
    ARTIFACT_DIGEST_PATTERN,
    GitHubApi,
    MAX_ARTIFACT_BYTES,
    PAGES_EVENTS,
    PAGES_WORKFLOW,
    REPOSITORY_PATTERN,
    SelectionError,
    _validate_run,
)
from scripts.release.matrix import MatrixError  # noqa: E402

MAX_MANIFEST_BYTES = 2 * 1024 * 1024


class SourceAuthenticationError(RuntimeError):
    """Raised when a public bundle lacks an exact deterministic source run."""


def _positive(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise SourceAuthenticationError(f"{label} must be a positive integer")
    return value


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


def _historical_run(
    api: GitHubApi, run_id: int, run_attempt: int, label: str
) -> dict[str, Any]:
    run = api.run_attempt(run_id, run_attempt)
    if run.get("id") != run_id or run.get("run_attempt") != run_attempt:
        raise SourceAuthenticationError(f"{label} historical run attempt is stale")
    return run


def _selected_artifact(
    api: GitHubApi,
    *,
    run_id: int,
    artifact_id: int,
    artifact_name: str,
    artifact_digest: str,
    controller_branch: str,
    controller_sha: str,
) -> None:
    matches = [
        artifact
        for artifact in api.artifacts_for_run(run_id)
        if artifact.id == artifact_id
    ]
    if len(matches) != 1:
        raise SourceAuthenticationError(
            "selected artifact ID is absent or ambiguous at its exact owner"
        )
    artifact = matches[0]
    if (
        artifact.name != artifact_name
        or artifact.digest != artifact_digest
        or artifact.expired
        or artifact.run_id != run_id
        or artifact.head_branch != controller_branch
        or artifact.head_sha != controller_sha
        or artifact.size <= 0
        or artifact.size > MAX_ARTIFACT_BYTES
    ):
        raise SourceAuthenticationError(
            "selected artifact digest/owner/controller provenance is stale"
        )


def authenticate(
    api: GitHubApi,
    *,
    repository: str,
    canonical_branch: str,
    matrix_path: Path,
    evidence_root: Path,
    selected_kind: str,
    selected_artifact_id: int,
    selected_artifact_name: str,
    selected_artifact_digest: str,
    selected_run_id: int,
    selected_run_attempt: int,
    expected_handoff_run_id: int,
    expected_handoff_run_attempt: int,
) -> dict[str, Any]:
    if selected_kind not in {"raw", "compact"}:
        raise SourceAuthenticationError("selected artifact kind is invalid")
    if REPOSITORY_PATTERN.fullmatch(repository) is None:
        raise SourceAuthenticationError("repository must use owner/name form")
    if (
        not isinstance(canonical_branch, str)
        or not canonical_branch
        or canonical_branch != canonical_branch.strip()
        or len(canonical_branch.encode("utf-8")) > 240
    ):
        raise SourceAuthenticationError("canonical branch identity is invalid")
    for value, item_label in (
        (selected_artifact_id, "selected artifact id"),
        (selected_run_id, "selected run id"),
        (selected_run_attempt, "selected run attempt"),
        (expected_handoff_run_id, "expected handoff run id"),
        (expected_handoff_run_attempt, "expected handoff run attempt"),
    ):
        _positive(value, item_label)
    if (
        not isinstance(selected_artifact_name, str)
        or not selected_artifact_name
        or len(selected_artifact_name.encode("utf-8")) > 240
        or not isinstance(selected_artifact_digest, str)
        or ARTIFACT_DIGEST_PATTERN.fullmatch(selected_artifact_digest) is None
    ):
        raise SourceAuthenticationError("selected artifact name/digest is invalid")
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
    if handoff["controller_branch"] != canonical_branch:
        raise SourceAuthenticationError(
            "handoff run is not owned by the protected default controller branch"
        )
    if packaged["controller_branch"] != canonical_branch:
        raise SourceAuthenticationError(
            "packaged run is not owned by the protected default controller branch"
        )
    if api.branch_head(provenance["branch"]) != (
        provenance["commit"],
        provenance["tree"],
    ):
        raise SourceAuthenticationError("published branch/commit/tree identity is stale")
    workflow = api.workflow("on-demand-e2e.yml")

    if selected_kind == "raw" and (
        handoff["run_id"] != selected_run_id
        or handoff["run_attempt"] != selected_run_attempt
    ):
        raise SourceAuthenticationError("raw artifact owner differs from handoff provenance")

    handoff_run = _historical_run(
        api, handoff["run_id"], handoff["run_attempt"], "handoff"
    )
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
            branch=handoff["controller_branch"],
            sha=handoff["controller_sha"],
            events=handoff_events,
            require_success=True,
            display_title=f"Packaged E2E / {provenance['commit']}",
        )
    except SelectionError as exc:
        raise SourceAuthenticationError(str(exc)) from exc

    packaged_run = _historical_run(
        api, packaged["run_id"], packaged["run_attempt"], "packaged source"
    )
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
            branch=packaged["controller_branch"],
            sha=packaged["controller_sha"],
            events=packaged_events,
            require_success=True,
            display_title=f"Packaged E2E / {packaged['commit']}",
        )
    except SelectionError as exc:
        raise SourceAuthenticationError(str(exc)) from exc
    if api.commit_tree(provenance["commit"]) != provenance["tree"]:
        raise SourceAuthenticationError("published commit/tree identity is stale")
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
    if not attested and (
        packaged["branch"] != provenance["branch"]
        or packaged["commit"] != provenance["commit"]
        or packaged["tree"] != provenance["tree"]
        or packaged["controller_branch"] != handoff["controller_branch"]
        or packaged["controller_sha"] != handoff["controller_sha"]
    ):
        raise SourceAuthenticationError(
            "non-attested handoff does not describe its own exact packaged source"
        )
    if attested:
        handoff_jobs = api.jobs_for_attempt(handoff["run_id"], handoff["run_attempt"])
        attestation_jobs = [
            job
            for job in handoff_jobs
            if isinstance(job.get("name"), str)
            and job["name"].endswith(" / Verify exact tested tree")
            and job.get("run_attempt") == handoff["run_attempt"]
        ]
        if len(attestation_jobs) != 1 or (
            attestation_jobs[0].get("status") != "completed"
            or attestation_jobs[0].get("conclusion") != "success"
        ):
            raise SourceAuthenticationError("handoff run lacks one successful exact-tree attestation job")

    expected_selected_name = (
        raw_artifact_name(provenance["branch"], handoff["run_attempt"])
        if selected_kind == "raw"
        else cache_artifact_name(provenance["branch"], provenance["commit"])
    )
    if selected_artifact_name != expected_selected_name:
        raise SourceAuthenticationError("selected artifact name is not exact")
    if selected_kind == "raw":
        selected_owner = handoff_run
        if (
            selected_run_id != handoff["run_id"]
            or selected_run_attempt != handoff["run_attempt"]
        ):
            raise SourceAuthenticationError("raw artifact owner differs from handoff provenance")
    else:
        selected_owner = _historical_run(
            api, selected_run_id, selected_run_attempt, "compact cache owner"
        )
        pages_workflow = api.workflow("pages.yml")
        try:
            _validate_run(
                selected_owner,
                workflow_id=pages_workflow["id"],
                workflow_path=PAGES_WORKFLOW,
                repository=repository,
                branch=canonical_branch,
                sha=str(selected_owner.get("head_sha", "")),
                events=PAGES_EVENTS,
                require_success=True,
            )
        except SelectionError as exc:
            raise SourceAuthenticationError(str(exc)) from exc
    _selected_artifact(
        api,
        run_id=selected_run_id,
        artifact_id=selected_artifact_id,
        artifact_name=selected_artifact_name,
        artifact_digest=selected_artifact_digest,
        controller_branch=str(selected_owner["head_branch"]),
        controller_sha=str(selected_owner["head_sha"]),
    )
    if api.branch_head(provenance["branch"]) != (
        provenance["commit"],
        provenance["tree"],
    ):
        raise SourceAuthenticationError("published branch advanced during authentication")
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
    parser.add_argument("--selected-artifact-id", type=int, required=True)
    parser.add_argument("--selected-artifact-name", required=True)
    parser.add_argument("--selected-artifact-digest", required=True)
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
            selected_artifact_id=args.selected_artifact_id,
            selected_artifact_name=args.selected_artifact_name,
            selected_artifact_digest=args.selected_artifact_digest,
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
