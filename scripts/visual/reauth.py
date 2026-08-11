#!/usr/bin/env python3
"""Reauthenticate one queued visual capsule immediately before AI or publication.

The queue is data only.  This script comes from the current protected default
branch and binds that immutable data back to the current PR/branch, exact tested
tree, complete Packaged E2E job graph, and eligible lossless canonical anchor.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.scenario_contract import ScenarioContractError, load_contract  # noqa: E402
from e2e.visual_capsule import validate_capsule  # noqa: E402
from e2e.visual_evidence import VisualEvidenceError, canonical_reference_identity  # noqa: E402
from scripts.lib.secure_json import canonical_json  # noqa: E402
from scripts.release.matrix import MatrixError, load_matrix  # noqa: E402
from scripts.visual.curate import (  # noqa: E402
    DEFAULT_BRANCH,
    SOURCE_EVENTS,
    WORKFLOW_PATH,
    ArtifactIdentity,
    CurationError,
    GitHubApi,
    RunIdentity,
    TestedIdentity,
    _attestation,
    _bind_matrix_branch,
    _job_graph,
    _positive,
    _projected_identity,
    _projection,
    _sha1,
    _source_files,
    authenticate_run,
    select_reference_run,
)
from scripts.visual.handoff import HandoffError, validate_queue  # noqa: E402


PREPARE_WORKFLOW = ".github/workflows/visual-review.yml"
PREPARE_EVENTS = frozenset({"repository_dispatch", "workflow_run"})
SHA256 = re.compile(r"^(?:sha256:)?([0-9a-f]{64})$")
MAX_QUEUE_ARTIFACT_BYTES = 96 * 1024 * 1024


class ReauthenticationError(ValueError):
    """The queue could not be rebound safely."""


class StaleSourceError(ReauthenticationError):
    """A positively identified mutable source advanced after curation."""


def _fail(message: str) -> None:
    raise ReauthenticationError(message)


def _stale(message: str) -> None:
    raise StaleSourceError(message)


def _artifact_digest(value: Any, label: str) -> str:
    if not isinstance(value, str):
        _fail(f"{label} must be a SHA-256 digest")
    match = SHA256.fullmatch(value)
    if match is None:
        _fail(f"{label} must be a SHA-256 digest")
    return match.group(1)


def _write_result(path: Path, value: dict[str, Any]) -> None:
    payload = canonical_json(value) + b"\n"
    if len(payload) > 64 * 1024:
        _fail("reauthentication result exceeds its byte bound")
    if path.exists() or path.is_symlink():
        _fail("reauthentication result destination must be fresh")
    try:
        parent = path.parent.resolve(strict=True)
        metadata = parent.lstat()
    except OSError as exc:
        raise ReauthenticationError(f"cannot inspect result parent: {exc}") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail("reauthentication result parent must be a real directory")
    with path.open("xb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, 0o600)


def _queue_owner(
    *,
    api: GitHubApi,
    queue: dict[str, Any],
    artifact_id: int,
    artifact_name: str,
    artifact_digest: str,
    owner_run_id: int,
    owner_run_attempt: int,
) -> None:
    metadata = api.artifact(artifact_id)
    owner = metadata.get("workflow_run")
    digest = _artifact_digest(metadata.get("digest"), "queue artifact API digest")
    size = _positive(metadata.get("size_in_bytes"), "queue artifact size")
    if (
        queue["producer_run_id"] != owner_run_id
        or queue["producer_run_attempt"] != owner_run_attempt
        or metadata.get("id") != artifact_id
        or metadata.get("name") != artifact_name
        or digest != artifact_digest
        or size > MAX_QUEUE_ARTIFACT_BYTES
        or metadata.get("expired") is not False
        or not isinstance(owner, dict)
        or owner.get("id") != owner_run_id
        or owner.get("head_sha") != queue["implementation_sha"]
    ):
        _fail("queue artifact API identity is stale or ambiguous")
    run = api.run_attempt(owner_run_id, owner_run_attempt)
    repository = run.get("repository")
    head_repository = run.get("head_repository")
    if (
        run.get("id") != owner_run_id
        or run.get("run_attempt") != owner_run_attempt
        or run.get("path") != PREPARE_WORKFLOW
        or run.get("event") not in PREPARE_EVENTS
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("head_branch") != DEFAULT_BRANCH
        or run.get("head_sha") != queue["implementation_sha"]
        or not isinstance(repository, dict)
        or repository.get("full_name") != api.repository
        or not isinstance(head_repository, dict)
        or head_repository.get("full_name") != api.repository
    ):
        _fail("queue owner is not an authenticated protected curation run")


def _protected_implementation(
    api: GitHubApi, *, queue_implementation: str, current_implementation: str
) -> None:
    _sha1(current_implementation, "current implementation SHA")
    if api.branch_head(api.repository, DEFAULT_BRANCH) != current_implementation:
        _fail("current reviewer checkout is not the exact default-branch head")
    if queue_implementation == current_implementation:
        return
    comparison = api.compare(api.repository, queue_implementation, current_implementation)
    try:
        base = comparison["base_commit"]["sha"]
        merge_base = comparison["merge_base_commit"]["sha"]
        status_value = comparison["status"]
    except (KeyError, TypeError) as exc:
        raise ReauthenticationError("implementation comparison is malformed") from exc
    if (
        base != queue_implementation
        or merge_base != queue_implementation
        or status_value != "ahead"
    ):
        _fail("queue implementation is not an ancestor of the current protected head")


def _source_controller_ancestry(
    api: GitHubApi, *, source_controller: str, queue_implementation: str
) -> None:
    """Require the historical packaged controller to belong to protected history."""

    _sha1(source_controller, "source controller SHA")
    _sha1(queue_implementation, "queue implementation SHA")
    if source_controller == queue_implementation:
        return
    comparison = api.compare(api.repository, source_controller, queue_implementation)
    try:
        base = comparison["base_commit"]["sha"]
        merge_base = comparison["merge_base_commit"]["sha"]
        status_value = comparison["status"]
    except (KeyError, TypeError) as exc:
        raise ReauthenticationError("source controller comparison is malformed") from exc
    if (
        base != source_controller
        or merge_base != source_controller
        or status_value != "ahead"
    ):
        _stale("source controller is not an ancestor of the queue implementation")


def _current_source_run(api: GitHubApi, historical: RunIdentity) -> RunIdentity:
    """Reject an already-queued attempt as soon as GitHub advances its mutable run."""

    current_record = api.run(historical.run_id)
    try:
        current_attempt = _positive(
            current_record.get("run_attempt"), "current source run attempt"
        )
    except (AttributeError, CurationError) as exc:
        raise ReauthenticationError("current source run response is malformed") from exc
    if current_attempt != historical.run_attempt:
        _stale("source workflow was re-run after this queue item was curated")
    try:
        current = authenticate_run(
            current_record,
            repository=api.repository,
            expected_id=historical.run_id,
        )
    except CurationError as exc:
        raise StaleSourceError("current source run is no longer the queued success") from exc
    if current != historical:
        _stale("mutable source run identity differs from its historical attempt")
    return current


def _run_pull_number(run_record: dict[str, Any]) -> int:
    pulls = run_record.get("pull_requests")
    if not isinstance(pulls, list) or len(pulls) != 1 or not isinstance(pulls[0], dict):
        _fail("pull_request_target run does not identify exactly one pull request")
    return _positive(pulls[0].get("number"), "pull request number")


def _current_pr_tested(
    api: GitHubApi,
    *,
    run: RunIdentity,
    run_record: dict[str, Any],
    expected_tested: str,
    expected_tree: str,
    expected_base_branch: str,
    expected_source_repository: str,
    expected_source_branch: str,
    expected_source_head: str,
) -> TestedIdentity:
    pull = api.pull_request(_run_pull_number(run_record))
    try:
        head = pull["head"]
        base = pull["base"]
        state = pull["state"]
        merged = pull.get("merged")
        if (
            head["sha"] != expected_source_head
            or head["repo"]["full_name"] != expected_source_repository
            or head["ref"] != expected_source_branch
            or expected_source_repository != run.repository
            or base["repo"]["full_name"] != run.repository
            or base["ref"] != expected_base_branch
        ):
            _stale("pull request head/base identity changed after curation")
        base_sha = _sha1(base["sha"], "pull request base SHA")
        merge_sha = _sha1(pull["merge_commit_sha"], "pull request merge SHA")
    except (KeyError, TypeError) as exc:
        raise ReauthenticationError("pull request API record is malformed") from exc
    if state == "open":
        if api.branch_head(run.repository, expected_base_branch) != base_sha:
            _stale("pull request base branch advanced after curation")
        if merge_sha != expected_tested:
            _stale("pull request synthetic merge changed after curation")
        tree, parents = api.commit_identity(run.repository, merge_sha)
        if tree != expected_tree or parents != (base_sha, expected_source_head):
            _stale("pull request tested merge tree or parents changed")
    elif state == "closed" and merged is True:
        if api.branch_head(run.repository, expected_base_branch) != merge_sha:
            _stale("merged pull request is no longer the exact release-branch head")
        merge_tree, merge_parents = api.commit_identity(run.repository, merge_sha)
        tested_tree, tested_parents = api.commit_identity(
            run.repository, expected_tested
        )
        if (
            merge_tree != expected_tree
            or tested_tree != expected_tree
            or len(merge_parents) != 2
            or merge_parents[1] != expected_source_head
            or tested_parents != merge_parents
        ):
            _stale("merged pull request no longer attests the exact tested tree")
    else:
        _stale("pull request closed without delivering its tested tree")
    return TestedIdentity(
        repository=run.repository,
        source_head_repository=expected_source_repository,
        source_head_branch=expected_source_branch,
        source_head_commit=expected_source_head,
        tested_commit=expected_tested,
        tested_tree=expected_tree,
        base_branch_hint=expected_base_branch,
    )


def _current_sync_tested(
    api: GitHubApi,
    *,
    run: RunIdentity,
    expected_tested: str,
    expected_tree: str,
    expected_base_branch: str,
    expected_source_repository: str,
    expected_source_branch: str,
    expected_source_head: str,
) -> TestedIdentity:
    if (
        expected_source_repository != run.repository
        or expected_source_head != expected_tested
        or not expected_source_branch.startswith("automation/release-sync/")
    ):
        _stale("release synchronization source identity changed after curation")
    candidates: list[dict[str, Any]] = []
    for summary in api.pull_requests_for_commit(expected_tested):
        try:
            if (
                summary["head"]["sha"] == expected_tested
                and summary["head"]["ref"] == expected_source_branch
                and summary["head"]["repo"]["full_name"] == run.repository
                and summary["base"]["repo"]["full_name"] == run.repository
            ):
                candidates.append(api.pull_request(_positive(summary["number"], "pull number")))
        except (KeyError, TypeError) as exc:
            raise ReauthenticationError("commit pull-request association is malformed") from exc
    if len(candidates) != 1:
        _stale("release synchronization no longer identifies one exact pull request")
    pull = candidates[0]
    try:
        head = pull["head"]
        base = pull["base"]
        state = pull["state"]
        merged = pull.get("merged")
        delivered = _sha1(pull["merge_commit_sha"], "pull request merge SHA")
        base_sha = _sha1(base["sha"], "pull request base SHA")
        if (
            head["sha"] != expected_tested
            or head["ref"] != expected_source_branch
            or head["repo"]["full_name"] != run.repository
            or base["ref"] != expected_base_branch
            or base["repo"]["full_name"] != run.repository
        ):
            _stale("release synchronization pull request changed after curation")
    except (KeyError, TypeError) as exc:
        raise ReauthenticationError("release synchronization pull request is malformed") from exc
    tree, parents = api.commit_identity(run.repository, expected_tested)
    if tree != expected_tree:
        _stale("release synchronization tested tree changed")
    if state == "open":
        if (
            api.branch_head(run.repository, expected_source_branch) != expected_tested
            or api.branch_head(run.repository, expected_base_branch) != base_sha
            or len(parents) != 2
            or parents[0] != base_sha
        ):
            _stale("open release synchronization topology changed")
    elif state == "closed" and merged is True:
        delivered_tree, delivered_parents = api.commit_identity(run.repository, delivered)
        if (
            api.branch_head(run.repository, expected_base_branch) != delivered
            or delivered_tree != expected_tree
            or len(delivered_parents) != 2
            or delivered_parents[1] != expected_tested
        ):
            _stale("merged release synchronization no longer delivers the tested tree")
    else:
        _stale("release synchronization pull request closed without delivery")
    return TestedIdentity(
        repository=run.repository,
        source_head_repository=run.repository,
        source_head_branch=expected_source_branch,
        source_head_commit=expected_source_head,
        tested_commit=expected_tested,
        tested_tree=expected_tree,
        base_branch_hint=expected_base_branch,
    )


def _current_tested(
    api: GitHubApi,
    *,
    run: RunIdentity,
    run_record: dict[str, Any],
    expected_tested: str,
    expected_tree: str,
    source: dict[str, Any],
) -> TestedIdentity:
    expected_base_branch = source["base_branch"]
    expected_source_repository = source["source_head_repository"]
    expected_source_branch = source["source_head_branch"]
    expected_source_head = source["source_head_commit"]
    if run.event == "pull_request_target":
        return _current_pr_tested(
            api,
            run=run,
            run_record=run_record,
            expected_tested=expected_tested,
            expected_tree=expected_tree,
            expected_base_branch=expected_base_branch,
            expected_source_repository=expected_source_repository,
            expected_source_branch=expected_source_branch,
            expected_source_head=expected_source_head,
        )
    if run.event != "workflow_dispatch":
        _fail("scheduled baseline runs must never enter the AI queue")
    if expected_tested != run.head_sha:
        return _current_sync_tested(
            api,
            run=run,
            expected_tested=expected_tested,
            expected_tree=expected_tree,
            expected_base_branch=expected_base_branch,
            expected_source_repository=expected_source_repository,
            expected_source_branch=expected_source_branch,
            expected_source_head=expected_source_head,
        )
    if (
        expected_source_repository != run.repository
        or expected_source_branch != run.head_branch
        or expected_source_head != run.head_sha
    ):
        _stale("workflow_dispatch source identity changed after curation")
    if api.branch_head(run.repository, run.head_branch) != run.head_sha:
        _stale("workflow_dispatch source branch advanced after curation")
    tree, _parents = api.commit_identity(run.repository, run.head_sha)
    if tree != expected_tree:
        _stale("workflow_dispatch tested tree changed after curation")
    return TestedIdentity(
        repository=run.repository,
        source_head_repository=run.repository,
        source_head_branch=run.head_branch,
        source_head_commit=run.head_sha,
        tested_commit=run.head_sha,
        tested_tree=tree,
        base_branch_hint=None,
    )


def _candidate_reference_binding(
    api: GitHubApi,
    *,
    run: RunIdentity,
    run_record: dict[str, Any],
    tested: TestedIdentity,
) -> tuple[str, bool]:
    """Reprove current ``master`` or the exact parent-zero merge baseline."""

    current_reference = api.branch_head(api.repository, DEFAULT_BRANCH)
    if run.event != "pull_request_target":
        return current_reference, False
    pull = api.pull_request(_run_pull_number(run_record))
    try:
        head = pull["head"]
        base = pull["base"]
        state = pull["state"]
        merged = pull.get("merged")
        delivered = _sha1(pull["merge_commit_sha"], "pull request merge SHA")
        if (
            head["repo"]["full_name"] != tested.source_head_repository
            or head["sha"] != tested.source_head_commit
            or head["ref"] != tested.source_head_branch
            or base["repo"]["full_name"] != run.repository
            or base["ref"] != tested.base_branch_hint
        ):
            _stale("pull request identity changed while binding its reference")
        base_branch = base["ref"]
    except (KeyError, TypeError) as exc:
        raise ReauthenticationError("pull request reference record is malformed") from exc
    if state == "open":
        return current_reference, False
    if state != "closed" or merged is not True:
        _stale("pull request closed without delivering its tested tree")
    if base_branch != DEFAULT_BRANCH:
        return current_reference, False
    tested_tree, tested_parents = api.commit_identity(
        run.repository, tested.tested_commit
    )
    delivered_tree, delivered_parents = api.commit_identity(run.repository, delivered)
    if (
        current_reference != delivered
        or api.branch_head(run.repository, base_branch) != delivered
        or tested_tree != tested.tested_tree
        or delivered_tree != tested.tested_tree
        or len(tested_parents) != 2
        or tested_parents != delivered_parents
        or tested_parents[1] != tested.source_head_commit
    ):
        _stale("merged pull request cannot authenticate its historical baseline")
    return tested_parents[0], True


def _recomputed_attestation(
    *,
    api: GitHubApi,
    run: RunIdentity,
    tested: TestedIdentity,
    source: dict[str, Any],
    work: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    matrix_path, contract_path, workflow = _source_files(
        api,
        repository=tested.repository,
        tested_commit=tested.tested_commit,
        root=work,
    )
    matrix = load_matrix(matrix_path, validate_sources=False)
    contract = load_contract(contract_path)
    _bind_matrix_branch(run, tested, matrix["branch"]["name"])
    nodes, scenarios = _projected_identity(matrix, contract, _projection(run.event))
    graph = _job_graph(
        api.jobs(run.run_id), run=run, matrix_path=matrix_path, tested=tested
    )
    artifact = ArtifactIdentity(
        artifact_id=_positive(source.get("artifact_id"), "source artifact id"),
        name=str(source.get("artifact_name", "")),
        digest=_artifact_digest(source.get("artifact_sha256"), "source artifact digest"),
        size=1,
    )
    if not artifact.name:
        _fail("source artifact name is absent")
    attestation, _expectation = _attestation(
        run=run,
        tested=tested,
        workflow=workflow,
        graph=graph,
        artifact=artifact,
        matrix_path=matrix_path,
        contract=contract,
        matrix_branch=matrix["branch"]["name"],
        nodes=(tuple(source["artifact_nodes"]) if source is not None else nodes),
        scenarios=scenarios,
    )
    if attestation != source:
        _stale("queued source provenance no longer matches its exact job graph")
    return matrix, {
        "matrix_path": matrix_path,
        "contract_path": contract_path,
        "workflow": workflow,
        "contract": contract,
        "graph": graph,
        "scenarios": scenarios,
    }


def reauthenticate(
    *,
    api: GitHubApi,
    queue_root: Path,
    artifact_id: int,
    artifact_name: str,
    artifact_digest: str,
    owner_run_id: int,
    owner_run_attempt: int,
    implementation_sha: str,
) -> dict[str, Any]:
    queue = validate_queue(queue_root)
    normalized_digest = _artifact_digest(artifact_digest, "selected queue digest")
    expected_name = (
        f"visual-review-input-{queue['source_run_id']}-"
        f"{queue['source_run_attempt']}-{queue['tested_sha']}-"
        f"{queue['producer_run_attempt']}"
    )
    if artifact_name != expected_name:
        _fail("selected queue artifact name disagrees with its embedded identity")
    _queue_owner(
        api=api,
        queue=queue,
        artifact_id=artifact_id,
        artifact_name=artifact_name,
        artifact_digest=normalized_digest,
        owner_run_id=owner_run_id,
        owner_run_attempt=owner_run_attempt,
    )
    _protected_implementation(
        api,
        queue_implementation=queue["implementation_sha"],
        current_implementation=implementation_sha,
    )
    capsule, _pairs = validate_capsule(queue_root / "capsule")
    candidate_source = capsule["candidate_source"]
    reference_source = capsule["reference_source"]
    source_record = api.run_attempt(
        queue["source_run_id"], queue["source_run_attempt"]
    )
    source_run = authenticate_run(
        source_record,
        repository=api.repository,
        expected_id=queue["source_run_id"],
    )
    _current_source_run(api, source_run)
    _source_controller_ancestry(
        api,
        source_controller=source_run.head_sha,
        queue_implementation=queue["implementation_sha"],
    )
    if (
        source_run.run_attempt != queue["source_run_attempt"]
        or source_run.head_repository != api.repository
        or source_run.head_branch != DEFAULT_BRANCH
        or source_run.event not in SOURCE_EVENTS
        or candidate_source["source_head_commit"] != queue["source_head_sha"]
        or candidate_source["event"] != source_run.event
    ):
        _stale("source controller/run/candidate identity changed after curation")
    tested = _current_tested(
        api,
        run=source_run,
        run_record=source_record,
        expected_tested=queue["tested_sha"],
        expected_tree=queue["tested_tree"],
        source=candidate_source,
    )
    with tempfile.TemporaryDirectory(prefix="blockpops-visual-reauth-") as temporary:
        work = Path(temporary)
        candidate_matrix, _candidate = _recomputed_attestation(
            api=api,
            run=source_run,
            tested=tested,
            source=candidate_source,
            work=work / "candidate",
        )
        reference_sha, historical_reference = _candidate_reference_binding(
            api,
            run=source_run,
            run_record=source_record,
            tested=tested,
        )
        if reference_sha != queue["reference_sha"]:
            _stale("canonical reference no longer matches the candidate delivery")
        reference_files = work / "reference"
        reference_matrix_path, reference_contract_path, reference_workflow = _source_files(
            api,
            repository=api.repository,
            tested_commit=reference_sha,
            root=reference_files,
        )
        reference_matrix = load_matrix(reference_matrix_path, validate_sources=False)
        if canonical_reference_identity(candidate_matrix) != canonical_reference_identity(
            reference_matrix
        ):
            _stale("candidate and authenticated reference matrices disagree on the anchor")
        reference_run, reference_artifact = select_reference_run(
            api,
            api.workflow_runs(DEFAULT_BRANCH, reference_sha),
            repository=api.repository,
            branch=DEFAULT_BRANCH,
            head_sha=reference_sha,
        )
        reference_tree, _parents = api.commit_identity(
            api.repository, reference_sha
        )
        reference_tested = TestedIdentity(
            repository=api.repository,
            source_head_repository=api.repository,
            source_head_branch=DEFAULT_BRANCH,
            source_head_commit=reference_sha,
            tested_commit=reference_sha,
            tested_tree=reference_tree,
            base_branch_hint=None,
        )
        reference_contract = load_contract(reference_contract_path)
        reference_graph = _job_graph(
            api.jobs(reference_run.run_id),
            run=reference_run,
            matrix_path=reference_matrix_path,
            tested=reference_tested,
        )
        _nodes, reference_scenarios = _projected_identity(
            reference_matrix,
            reference_contract,
            _projection(reference_run.event),
        )
        reference_attestation, _expectation = _attestation(
            run=reference_run,
            tested=reference_tested,
            workflow=reference_workflow,
            graph=reference_graph,
            artifact=reference_artifact,
            matrix_path=reference_matrix_path,
            contract=reference_contract,
            matrix_branch=reference_matrix["branch"]["name"],
            nodes=(canonical_reference_identity(reference_matrix)["artifact_node"],),
            scenarios=reference_scenarios,
        )
        if reference_attestation != reference_source:
            qualifier = "historical" if historical_reference else "current-head"
            _stale(
                f"queued baseline is not the newest authenticated {qualifier} anchor"
            )
    return {
        "schema_version": 1,
        "status": "authenticated",
        "category": "",
        "implementation_sha": implementation_sha,
        "queue_artifact_id": artifact_id,
        "queue_artifact_digest": normalized_digest,
        "source_run_id": queue["source_run_id"],
        "source_run_attempt": queue["source_run_attempt"],
        "source_head_sha": queue["source_head_sha"],
        "tested_sha": queue["tested_sha"],
        "tested_tree": queue["tested_tree"],
        "reference_sha": queue["reference_sha"],
        "reference_run_id": reference_run.run_id,
        "reference_run_attempt": reference_run.run_attempt,
        "reference_artifact_id": reference_artifact.artifact_id,
        "reference_artifact_digest": reference_artifact.digest,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--queue-artifact-id", type=int, required=True)
    parser.add_argument("--queue-artifact-name", required=True)
    parser.add_argument("--queue-artifact-digest", required=True)
    parser.add_argument("--queue-owner-run-id", type=int, required=True)
    parser.add_argument("--queue-owner-run-attempt", type=int, required=True)
    parser.add_argument("--implementation-sha", required=True)
    parser.add_argument("--result", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        api = GitHubApi(
            repository=args.repository,
            token=os.environ.get("GITHUB_TOKEN", ""),
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        result = reauthenticate(
            api=api,
            queue_root=args.queue,
            artifact_id=args.queue_artifact_id,
            artifact_name=args.queue_artifact_name,
            artifact_digest=args.queue_artifact_digest,
            owner_run_id=args.queue_owner_run_id,
            owner_run_attempt=args.queue_owner_run_attempt,
            implementation_sha=args.implementation_sha,
        )
        _write_result(args.result, result)
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except StaleSourceError as exc:
        result = {
            "schema_version": 1,
            "status": "stale_source",
            "category": "stale_source",
        }
        try:
            _write_result(args.result, result)
        except ReauthenticationError:
            pass
        print(f"visual reauthentication stale: {exc}", file=sys.stderr)
        return 3
    except (
        CurationError,
        HandoffError,
        MatrixError,
        ScenarioContractError,
        VisualEvidenceError,
        ReauthenticationError,
        OSError,
        ValueError,
    ) as exc:
        result = {
            "schema_version": 1,
            "status": "error",
            "category": "authentication",
        }
        try:
            _write_result(args.result, result)
        except ReauthenticationError:
            pass
        print(f"visual reauthentication failed closed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
