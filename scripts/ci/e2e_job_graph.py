#!/usr/bin/env python3
"""Validate the exact authoritative job graph of a protected gate run."""

from __future__ import annotations

import argparse
import hashlib
import json
import stat
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.matrix import MatrixError, load_matrix_document  # noqa: E402

MAX_API_BYTES = 32 * 1024 * 1024
MAX_JOBS = 1000
SCENARIO_SUFFIX = " - contract scenarios"
BUILD_IDENTITY = "Resolve authoritative build matrix"
BUILD_BUNDLE = "Build immutable release bundle"
BUILD_GATE = "Build and verify"
BUILD_ATTEST = "Attest exact tested build tree"
E2E_IDENTITY = "Resolve authoritative packaged matrix"
E2E_BUNDLE = "Build immutable E2E input bundle"
E2E_AGGREGATE = "Validate and aggregate packaged evidence"
E2E_GATE = "Packaged E2E gate"
E2E_ATTEST = "Attest exact tested packaged tree"
E2E_PUBLIC = "Curate current-head public evidence (advisory)"
SOURCE_EVENTS = frozenset({"pull_request_target", "schedule", "workflow_dispatch"})


@dataclass(frozen=True)
class ExpectedJob:
    name: str
    conclusion: str


class JobGraphError(ValueError):
    """Raised when a successful run did not execute its exact protected graph."""


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def read_jobs(path: Path) -> list[dict[str, Any]]:
    try:
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or path.is_symlink():
            raise JobGraphError("jobs response must be a regular non-symlink file")
        if metadata.st_size <= 0 or metadata.st_size > MAX_API_BYTES:
            raise JobGraphError("jobs response is empty or oversized")
        document = json.loads(
            path.read_text("utf-8"),
            object_pairs_hook=_reject_duplicates,
            parse_constant=_reject_nonfinite,
        )
    except JobGraphError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise JobGraphError(f"cannot read jobs response: {exc}") from exc
    pages = document if isinstance(document, list) else [document]
    jobs: list[dict[str, Any]] = []
    for page in pages:
        if not isinstance(page, dict) or not isinstance(page.get("jobs"), list):
            raise JobGraphError("jobs response has an invalid page shape")
        for job in page["jobs"]:
            if not isinstance(job, dict):
                raise JobGraphError("job record must be an object")
            jobs.append(job)
    if not jobs or len(jobs) > MAX_JOBS:
        raise JobGraphError(f"job graph must contain 1..{MAX_JOBS} jobs")
    return jobs


def expected_jobs(
    matrix_path: Path,
    workflow: str,
    *,
    event: str = "workflow_dispatch",
    source_branch: str | None = None,
    scope: str | None = None,
    artifact_node: str | None = None,
) -> tuple[ExpectedJob, ...]:
    """Derive jobs from caller-owned selection; observed jobs never select scope.

    Explicit selection is schema2-only. Individual lanes require a dispatch;
    existing scheduled/PR gates cannot be reduced to one lane. Build job names
    alone do not prove lane coverage, which requires verified bundle/report data.
    """
    if event not in SOURCE_EVENTS:
        raise JobGraphError(f"unsupported protected source event {event!r}")
    if workflow not in {"build-gate.yml", "on-demand-e2e.yml"}:
        raise JobGraphError("unsupported protected workflow")
    matrix = None
    selection = {}
    if scope is not None or artifact_node is not None:
        if scope is None or (scope == "lane" and event != "workflow_dispatch"):
            raise JobGraphError("explicit lane selection requires scope and workflow_dispatch")
        matrix = load_matrix_document(matrix_path, validate_sources=False)
        if matrix.inventory.schema_version != 2:
            raise JobGraphError("explicit job scope requires a schema2 matrix")
        selection = {"scope": scope, "artifact_node": artifact_node}
        matrix.select_lanes(**selection)
    if workflow == "build-gate.yml":
        return (
            ExpectedJob(BUILD_IDENTITY, "success"),
            ExpectedJob(BUILD_BUNDLE, "success"),
            ExpectedJob(BUILD_GATE, "success"),
            ExpectedJob(BUILD_ATTEST, "skipped"),
        )
    if matrix is None:
        matrix = load_matrix_document(matrix_path, validate_sources=False)
    projection = "scheduled-anchors" if event == "schedule" else "pr-anchors"
    rows = matrix.projection(projection, **selection)["include"]
    if not rows:
        raise JobGraphError("authoritative E2E matrix is empty")
    scenario = tuple(sorted(row["id"] + SCENARIO_SUFFIX for row in rows))
    if len(scenario) != len(set(scenario)):
        raise JobGraphError("authoritative E2E job names are duplicated")
    selected_branch = matrix.branch_name if source_branch is None else source_branch
    public_conclusion = (
        "success"
        if event in {"schedule", "workflow_dispatch"}
        and selected_branch == matrix.branch_name
        else "skipped"
    )
    return (
        ExpectedJob(E2E_IDENTITY, "success"),
        ExpectedJob(E2E_BUNDLE, "success"),
        *(ExpectedJob(name, "success") for name in scenario),
        ExpectedJob(E2E_AGGREGATE, "success"),
        ExpectedJob(E2E_GATE, "success"),
        ExpectedJob(E2E_ATTEST, "skipped"),
        ExpectedJob(E2E_PUBLIC, public_conclusion),
    )


def expected_names(
    matrix_path: Path,
    workflow: str,
    *,
    event: str = "workflow_dispatch",
    source_branch: str | None = None,
    scope: str | None = None,
    artifact_node: str | None = None,
) -> tuple[str, ...]:
    """Compatibility projection for callers that only display the exact names."""

    return tuple(
        job.name
        for job in expected_jobs(
            matrix_path,
            workflow,
            event=event,
            source_branch=source_branch,
            scope=scope,
            artifact_node=artifact_node,
        )
    )


def validate_jobs(
    jobs: list[dict[str, Any]],
    *,
    expected: tuple[ExpectedJob | str, ...],
    run_attempt: int,
) -> dict[str, Any]:
    if isinstance(run_attempt, bool) or not isinstance(run_attempt, int) or run_attempt <= 0:
        raise JobGraphError("run_attempt must be a positive integer")
    by_name: dict[str, list[dict[str, Any]]] = {}
    ids: set[int] = set()
    for job in jobs:
        job_id = job.get("id")
        name = job.get("name")
        attempt = job.get("run_attempt")
        if (
            isinstance(job_id, bool)
            or not isinstance(job_id, int)
            or job_id <= 0
            or not isinstance(name, str)
            or not name
            or isinstance(attempt, bool)
            or not isinstance(attempt, int)
            or attempt <= 0
        ):
            raise JobGraphError("job has an invalid id/name/run_attempt")
        if job_id in ids:
            raise JobGraphError("job API response repeats a job id")
        ids.add(job_id)
        if attempt == run_attempt:
            by_name.setdefault(name, []).append(job)
    normalized = tuple(
        item if isinstance(item, ExpectedJob) else ExpectedJob(item, "success")
        for item in expected
    )
    expected_by_name = {item.name: item for item in normalized}
    if len(expected_by_name) != len(normalized):
        raise JobGraphError("expected job graph contains duplicate names")
    observed_names = set(by_name)
    expected_names_set = set(expected_by_name)
    if observed_names != expected_names_set:
        missing = sorted(expected_names_set - observed_names)
        unexpected = sorted(observed_names - expected_names_set)
        raise JobGraphError(
            f"exact job inventory mismatch: missing={missing}, unexpected={unexpected}"
        )
    for name, expectation in expected_by_name.items():
        records = by_name.get(name, [])
        if len(records) != 1:
            raise JobGraphError(f"expected job {name!r} appears {len(records)} times")
        record = records[0]
        if (
            record.get("status") != "completed"
            or record.get("conclusion") != expectation.conclusion
        ):
            raise JobGraphError(
                f"expected job {name!r} did not complete as {expectation.conclusion!r}"
            )
    canonical = {
        "schema_version": 1,
        "run_attempt": run_attempt,
        "required_jobs": sorted(expected_by_name),
        "expected_conclusions": {
            name: expected_by_name[name].conclusion for name in sorted(expected_by_name)
        },
        "job_ids": sorted(by_name[name][0]["id"] for name in expected_by_name),
    }
    encoded = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    canonical["sha256"] = hashlib.sha256(encoded).hexdigest()
    return canonical


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--jobs", type=Path, required=True)
    parser.add_argument(
        "--workflow", choices=("build-gate.yml", "on-demand-e2e.yml"), required=True
    )
    parser.add_argument(
        "--event",
        choices=("pull_request_target", "schedule", "workflow_dispatch"),
        default="workflow_dispatch",
    )
    parser.add_argument("--source-branch")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("legacy", "full"))
    selection.add_argument("--artifact-node")
    parser.add_argument("--run-attempt", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        result = validate_jobs(
            read_jobs(args.jobs),
            expected=expected_jobs(
                args.matrix,
                args.workflow,
                event=args.event,
                source_branch=args.source_branch,
                scope="lane" if args.artifact_node is not None else args.scope,
                artifact_node=args.artifact_node,
            ),
            run_attempt=args.run_attempt,
        )
    except (JobGraphError, MatrixError, OSError) as exc:
        print(f"job graph error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
