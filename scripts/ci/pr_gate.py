#!/usr/bin/env python3
"""Evaluate ordinary PR gates from protected default-branch policy.

Candidate Build/E2E workflows are deliberately treated only as evidence producers.  This
controller runs from the protected default branch, authenticates the current pull request and its
synthetic merge, requires branch-portable controller parity, and selects the newest exact run and
attempt for both deterministic gates.  It never writes to GitHub; a separate App-only job consumes
its bounded outputs and publishes the two trusted commit-status contexts.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.ci.e2e_fanin import aggregate_artifact_name  # noqa: E402
from scripts.ci.e2e_job_graph import (  # noqa: E402
    JobGraphError,
    expected_jobs,
    validate_jobs,
)
from scripts.ci.gate_controller import (  # noqa: E402
    GateControllerError,
    VERSION_SPECIFIC_PATHS,
    validate_controller_parity,
)
from scripts.release.matrix import (  # noqa: E402
    MatrixError,
    load_matrix_bytes,
    valid_branch_name,
)

SCHEMA_VERSION = 1
WORKFLOWS = {
    "build": "build-gate.yml",
    "e2e": "on-demand-e2e.yml",
}
CONTEXTS = {
    "build": "Trusted PR / Build and verify",
    "e2e": "Trusted PR / Packaged E2E gate",
}
MATRIX_PATH = "release/release-matrix.json"
VERIFICATION_PATH = "gradle/verification-metadata.xml"
EXACT_BASE_OWNED_PATHS = (
    MATRIX_PATH,
    VERIFICATION_PATH,
    *sorted(VERSION_SPECIFIC_PATHS),
)
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256_DIGEST = re.compile(r"^sha256:([0-9a-f]{64})$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
MAX_API_BYTES = 32 * 1024 * 1024
MAX_RECORDS = 1000
MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024
RUN_STATUSES = frozenset(
    {"queued", "in_progress", "completed", "pending", "waiting", "requested"}
)
RUN_CONCLUSIONS = frozenset(
    {
        "success",
        "failure",
        "cancelled",
        "timed_out",
        "action_required",
        "neutral",
        "skipped",
        "stale",
        "startup_failure",
    }
)


class PrGateError(ValueError):
    """Trusted PR evidence is malformed, stale, mixed, or incomplete."""


class NotEligible(PrGateError):
    """The wake belongs to no still-current ordinary same-repository PR."""


def _fail(message: str) -> None:
    raise PrGateError(message)


def _positive(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        _fail(f"{label} must be a positive integer")
    return value


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        _fail(f"{label} must be one lowercase SHA-1")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        _fail(f"{label} must be a UTC RFC3339 timestamp")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise PrGateError(f"{label} is invalid") from exc


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self, request: Any, fp: Any, code: int, message: str, headers: Any, newurl: str
    ) -> None:
        return None


class GitHubApi:
    """Small read-only GitHub API surface used by the secretless evaluator."""

    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        if not isinstance(repository, str) or REPOSITORY.fullmatch(repository) is None:
            _fail("repository must use safe owner/name form")
        if not token or len(token) > 4096:
            _fail("GITHUB_TOKEN is absent or invalid")
        parsed = urllib.parse.urlsplit(api_url)
        if (
            parsed.scheme != "https"
            or not parsed.netloc
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            _fail("GitHub API URL must be an HTTPS origin")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self.opener = urllib.request.build_opener(
            _NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context())
        )

    def _route(self, suffix: str) -> str:
        owner, name = self.repository.split("/", 1)
        return (
            f"/repos/{urllib.parse.quote(owner, safe='')}/"
            f"{urllib.parse.quote(name, safe='')}{suffix}"
        )

    def json(self, suffix: str, *, label: str) -> Any:
        request = urllib.request.Request(
            self.api_url + self._route(suffix),
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-protected-pr-gate/1",
            },
        )
        try:
            with self.opener.open(request, timeout=60) as response:
                if response.status != 200:
                    _fail(f"{label} API returned HTTP {response.status}")
                payload = response.read(MAX_API_BYTES + 1)
        except urllib.error.HTTPError as exc:
            raise PrGateError(f"{label} API returned HTTP {exc.code}") from exc
        except urllib.error.URLError as exc:
            raise PrGateError(f"{label} API transport failed: {exc.reason}") from exc
        if not 1 <= len(payload) <= MAX_API_BYTES:
            _fail(f"{label} API response is empty or oversized")
        try:
            return json.loads(
                payload.decode("utf-8"),
                object_pairs_hook=_reject_duplicates,
                parse_constant=_reject_nonfinite,
            )
        except (UnicodeError, json.JSONDecodeError, ValueError) as exc:
            raise PrGateError(f"{label} API response is not strict JSON: {exc}") from exc

    def pages(self, suffix: str, *, key: str, label: str) -> list[dict[str, Any]]:
        separator = "&" if "?" in suffix else "?"
        records: list[dict[str, Any]] = []
        for page in range(1, 11):
            value = self.json(
                f"{suffix}{separator}per_page=100&page={page}", label=f"{label} page {page}"
            )
            if not isinstance(value, dict) or not isinstance(value.get(key), list):
                _fail(f"{label} API page has an invalid shape")
            batch = value[key]
            if any(not isinstance(item, dict) for item in batch):
                _fail(f"{label} API page contains a non-object record")
            records.extend(batch)
            if len(records) > MAX_RECORDS:
                _fail(f"{label} API response exceeds {MAX_RECORDS} records")
            if len(batch) < 100:
                return records
        _fail(f"{label} API pagination exceeds ten pages")

    def repository_record(self) -> dict[str, Any]:
        value = self.json("", label="repository")
        if not isinstance(value, dict):
            _fail("repository API record is not an object")
        return value

    def run(self, run_id: int) -> dict[str, Any]:
        value = self.json(f"/actions/runs/{_positive(run_id, 'run id')}", label="run")
        if not isinstance(value, dict):
            _fail("workflow run API record is not an object")
        return value

    def pull(self, number: int) -> dict[str, Any]:
        value = self.json(f"/pulls/{_positive(number, 'pull request number')}", label="pull")
        if not isinstance(value, dict):
            _fail("pull request API record is not an object")
        return value

    def branch_sha(self, branch: str) -> str:
        if not valid_branch_name(branch):
            _fail("branch identity is unsafe")
        value = self.json(
            f"/branches/{urllib.parse.quote(branch, safe='')}", label="branch"
        )
        try:
            return _sha(value["commit"]["sha"], "branch SHA")
        except (KeyError, TypeError) as exc:
            raise PrGateError("branch API record is malformed") from exc

    def commit_identity(self, commit: str) -> tuple[str, tuple[str, ...]]:
        value = self.json(f"/git/commits/{_sha(commit, 'commit')}", label="commit")
        try:
            if value["sha"] != commit:
                _fail("commit API returned a different object")
            parents = value["parents"]
            if not isinstance(parents, list) or len(parents) > 16:
                _fail("commit parent inventory is invalid")
            parent_shas = tuple(
                _sha(item["sha"], "commit parent") for item in parents if isinstance(item, dict)
            )
            if len(parent_shas) != len(parents) or len(set(parent_shas)) != len(parent_shas):
                _fail("commit parent inventory is malformed or duplicated")
            return _sha(value["tree"]["sha"], "commit tree"), parent_shas
        except (KeyError, TypeError) as exc:
            raise PrGateError("commit API record is malformed") from exc

    def workflow_runs(self, workflow: str, head_sha: str) -> list[dict[str, Any]]:
        encoded = urllib.parse.quote(workflow, safe="")
        return self.pages(
            f"/actions/workflows/{encoded}/runs?event=pull_request&head_sha={_sha(head_sha, 'head SHA')}",
            key="workflow_runs",
            label=f"{workflow} runs",
        )

    def jobs(self, run_id: int) -> list[dict[str, Any]]:
        return self.pages(
            f"/actions/runs/{_positive(run_id, 'run id')}/jobs?filter=all",
            key="jobs",
            label="workflow jobs",
        )

    def artifacts(self, run_id: int) -> list[dict[str, Any]]:
        return self.pages(
            f"/actions/runs/{_positive(run_id, 'run id')}/artifacts",
            key="artifacts",
            label="workflow artifacts",
        )


@dataclass(frozen=True)
class PullIdentity:
    number: int
    default_branch: str
    default_sha: str
    base_branch: str
    base_sha: str
    head_branch: str
    head_sha: str
    merge_sha: str
    merge_tree: str


@dataclass(frozen=True)
class SelectedRun:
    run_id: int
    run_attempt: int
    status: str
    conclusion: str | None
    created_at: str


@dataclass(frozen=True)
class GateResult:
    state: str
    description: str
    run_id: int
    run_attempt: int


def _run_pull_number(value: Any, label: str) -> int:
    pulls = value.get("pull_requests") if isinstance(value, dict) else None
    if not isinstance(pulls, list) or len(pulls) != 1 or not isinstance(pulls[0], dict):
        _fail(f"{label} must identify exactly one pull request")
    return _positive(pulls[0].get("number"), f"{label} pull request number")


def _authenticate_trigger(api: GitHubApi, trigger_run_id: int) -> tuple[dict[str, Any], int]:
    run = api.run(trigger_run_id)
    if run.get("id") != trigger_run_id:
        _fail("trigger run id disagrees with the API record")
    if run.get("event") != "pull_request":
        raise NotEligible("source run is not an ordinary pull_request run")
    if run.get("path") not in {f".github/workflows/{item}" for item in WORKFLOWS.values()}:
        raise NotEligible("source run is not a deterministic PR gate")
    repository = run.get("repository")
    head_repository = run.get("head_repository")
    if (
        not isinstance(repository, dict)
        or repository.get("full_name") != api.repository
        or not isinstance(head_repository, dict)
        or head_repository.get("full_name") != api.repository
    ):
        raise NotEligible("source run is not a same-repository PR gate")
    _sha(run.get("head_sha"), "trigger source head")
    if not valid_branch_name(run.get("head_branch")):
        _fail("trigger source branch is unsafe")
    if run["head_branch"].startswith("automation/release-sync/"):
        raise NotEligible("release synchronization uses its dedicated protected handler")
    return run, _run_pull_number(run, "trigger run")


def resolve_pull_identity(
    api: GitHubApi, *, trigger_run_id: int, implementation_sha: str
) -> PullIdentity:
    implementation_sha = _sha(implementation_sha, "protected implementation SHA")
    trigger, number = _authenticate_trigger(api, trigger_run_id)
    repository = api.repository_record()
    if repository.get("full_name") != api.repository:
        _fail("repository API identity changed")
    default_branch = repository.get("default_branch")
    if not valid_branch_name(default_branch):
        _fail("repository default branch is unsafe")
    default_sha = api.branch_sha(default_branch)
    if default_sha != implementation_sha:
        raise NotEligible("protected default branch advanced during PR evaluation")

    pull = api.pull(number)
    try:
        if pull["number"] != number or pull["state"] != "open":
            raise NotEligible("pull request is no longer open")
        if (
            pull["head"]["repo"]["full_name"] != api.repository
            or pull["base"]["repo"]["full_name"] != api.repository
        ):
            raise NotEligible("pull request is not wholly same-repository")
        head_branch = pull["head"]["ref"]
        head_sha = _sha(pull["head"]["sha"], "pull request head SHA")
        base_branch = pull["base"]["ref"]
        base_sha = _sha(pull["base"]["sha"], "pull request base SHA")
        merge_sha = _sha(pull["merge_commit_sha"], "pull request merge SHA")
    except (KeyError, TypeError) as exc:
        raise PrGateError("pull request API record is malformed") from exc
    if not valid_branch_name(head_branch) or not valid_branch_name(base_branch):
        _fail("pull request branch identity is unsafe")
    if head_branch.startswith("automation/release-sync/"):
        raise NotEligible("release synchronization head is not an ordinary PR")
    if head_branch != trigger["head_branch"] or head_sha != trigger["head_sha"]:
        raise NotEligible("trigger run is stale for the current pull request head")
    if api.branch_sha(head_branch) != head_sha or api.branch_sha(base_branch) != base_sha:
        raise NotEligible("pull request branch heads changed during evaluation")
    merge_tree, parents = api.commit_identity(merge_sha)
    if parents != (base_sha, head_sha):
        _fail("synthetic pull request merge lacks exact current base/head parents")
    return PullIdentity(
        number=number,
        default_branch=default_branch,
        default_sha=default_sha,
        base_branch=base_branch,
        base_sha=base_sha,
        head_branch=head_branch,
        head_sha=head_sha,
        merge_sha=merge_sha,
        merge_tree=merge_tree,
    )


def _git(repository: Path, *arguments: str, accepted: Iterable[int] = (0,)) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        env={
            **os.environ,
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_PAGER": "cat",
            "LC_ALL": "C",
        },
    )
    if result.returncode not in set(accepted):
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise PrGateError(detail or f"git {' '.join(arguments)} failed")
    return result.stdout


def _exact_commit(repository: Path, commit: str, label: str) -> str:
    commit = _sha(commit, label)
    resolved = _git(repository, "rev-parse", "--verify", f"{commit}^{{commit}}").decode().strip()
    if resolved != commit:
        _fail(f"{label} did not resolve to itself")
    return commit


def _tree_entry(repository: Path, commit: str, path: str) -> tuple[str, str, str] | None:
    raw = _git(repository, "ls-tree", "-z", commit, "--", path)
    if not raw:
        return None
    metadata, separator, raw_path = raw.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if separator != b"\t" or raw_path != path.encode() or len(fields) != 3:
        _fail(f"{commit} has a malformed tree entry for {path}")
    try:
        return tuple(item.decode("ascii") for item in fields)  # type: ignore[return-value]
    except UnicodeDecodeError as exc:
        raise PrGateError(f"{commit} has a non-ASCII tree entry for {path}") from exc


def _blob(repository: Path, commit: str, path: str, *, maximum: int) -> bytes:
    entry = _tree_entry(repository, commit, path)
    if entry is None or entry[:2] != ("100644", "blob"):
        _fail(f"{commit} has no regular non-executable {path} blob")
    size_raw = _git(repository, "cat-file", "-s", entry[2]).decode().strip()
    try:
        size = int(size_raw)
    except ValueError as exc:
        raise PrGateError(f"{path} has invalid Git blob size") from exc
    if not 1 <= size <= maximum:
        _fail(f"{path} size is outside 1..{maximum}")
    value = _git(repository, "cat-file", "blob", entry[2])
    if len(value) != size:
        _fail(f"{path} changed while reading its immutable Git blob")
    return value


def validate_pr_tree(repository: Path, identity: PullIdentity) -> bytes:
    """Authenticate default->base portability and exact base->merge control-plane parity."""

    repository = repository.resolve()
    for value, label in (
        (identity.default_sha, "default SHA"),
        (identity.base_sha, "base SHA"),
        (identity.head_sha, "head SHA"),
        (identity.merge_sha, "merge SHA"),
    ):
        _exact_commit(repository, value, label)
    if _git(repository, "rev-parse", "HEAD").decode().strip() != identity.merge_sha:
        _fail("candidate checkout is not the exact current synthetic merge")
    parents = tuple(_git(repository, "show", "-s", "--format=%P", identity.merge_sha).decode().split())
    if parents != (identity.base_sha, identity.head_sha):
        _fail("local synthetic merge has stale or reordered parents")
    tree = _git(repository, "rev-parse", f"{identity.merge_sha}^{{tree}}").decode().strip()
    if tree != identity.merge_tree:
        _fail("local synthetic merge tree disagrees with GitHub API")

    # A release branch is accepted as policy only after proving its controllers are a valid,
    # branch-portable projection of the current protected default branch.
    validate_controller_parity(
        repository,
        protected_sha=identity.default_sha,
        candidate_sha=identity.base_sha,
    )
    validate_controller_parity(
        repository,
        protected_sha=identity.base_sha,
        candidate_sha=identity.merge_sha,
    )
    for path in EXACT_BASE_OWNED_PATHS:
        base = _tree_entry(repository, identity.base_sha, path)
        merge = _tree_entry(repository, identity.merge_sha, path)
        if base is None or base[:2] != ("100644", "blob") or merge != base:
            _fail(f"ordinary PR changed exact base-owned path {path!r}")

    matrix_bytes = _blob(
        repository, identity.merge_sha, MATRIX_PATH, maximum=256 * 1024
    )
    matrix = load_matrix_bytes(matrix_bytes)
    branch = matrix["branch"]
    if branch["name"] != identity.base_branch or branch["canonical"] != identity.default_branch:
        _fail("base branch release matrix does not authenticate this PR topology")
    if identity.base_branch == identity.default_branch:
        if branch["role"] != "integration":
            _fail("default branch matrix is not integration policy")
    elif (
        branch["role"] != "release"
        or branch["sync"] != {"enabled": True, "source": identity.default_branch}
    ):
        _fail("release base matrix is not enrolled in protected synchronization")
    return matrix_bytes


def select_newest_pull_run(
    records: Iterable[Any],
    *,
    workflow: str,
    repository: str,
    identity: PullIdentity,
) -> SelectedRun | None:
    if workflow not in WORKFLOWS.values():
        _fail("unsupported PR gate workflow")
    path = f".github/workflows/{workflow}"
    candidates: list[tuple[datetime, int, SelectedRun]] = []
    seen: set[tuple[int, int]] = set()
    for value in records:
        if not isinstance(value, dict):
            _fail("workflow runs response contains a non-object")
        run_repository = value.get("repository")
        head_repository = value.get("head_repository")
        if (
            value.get("event") != "pull_request"
            or value.get("path") != path
            or value.get("head_branch") != identity.head_branch
            or value.get("head_sha") != identity.head_sha
            or not isinstance(run_repository, dict)
            or run_repository.get("full_name") != repository
            or not isinstance(head_repository, dict)
            or head_repository.get("full_name") != repository
        ):
            continue
        if _run_pull_number(value, f"{workflow} run") != identity.number:
            continue
        run_id = _positive(value.get("id"), "workflow run id")
        attempt = _positive(value.get("run_attempt"), "workflow run attempt")
        if (run_id, attempt) in seen:
            _fail("workflow runs response repeats a run/attempt")
        seen.add((run_id, attempt))
        status = value.get("status")
        conclusion = value.get("conclusion")
        if status not in RUN_STATUSES:
            _fail("matching workflow run has an invalid status")
        if status == "completed":
            if conclusion not in RUN_CONCLUSIONS:
                _fail("completed workflow run has an invalid conclusion")
        elif conclusion is not None:
            _fail("incomplete workflow run unexpectedly has a conclusion")
        created_at = value.get("created_at")
        selected = SelectedRun(run_id, attempt, status, conclusion, created_at)
        candidates.append((_timestamp(created_at, "workflow run created_at"), run_id, selected))
    if not candidates:
        return None
    return max(candidates, key=lambda item: (item[0], item[1]))[2]


def _expected_artifact_name(kind: str, identity: PullIdentity, attempt: int) -> str:
    if kind == "build":
        return f"staged-release-bundle-{identity.merge_sha}-{attempt}"
    if kind == "e2e":
        return aggregate_artifact_name(identity.merge_sha, attempt)
    _fail("unsupported gate artifact kind")


def validate_exact_artifact(
    records: Iterable[Any], *, expected_name: str, run_id: int
) -> dict[str, Any]:
    ids: set[int] = set()
    names: set[str] = set()
    matched: list[dict[str, Any]] = []
    for value in records:
        if not isinstance(value, dict):
            _fail("artifact API response contains a non-object")
        artifact_id = _positive(value.get("id"), "artifact id")
        name = value.get("name")
        if not isinstance(name, str) or not name or len(name) > 256:
            _fail("artifact has an invalid name")
        if artifact_id in ids or name in names:
            _fail("artifact API response repeats an id or name")
        ids.add(artifact_id)
        names.add(name)
        if name != expected_name:
            continue
        owner = value.get("workflow_run")
        size = value.get("size_in_bytes")
        digest = value.get("digest")
        if (
            value.get("expired") is not False
            or not isinstance(owner, dict)
            or owner.get("id") != run_id
            or isinstance(size, bool)
            or not isinstance(size, int)
            or not 1 <= size <= MAX_ARTIFACT_BYTES
            or not isinstance(digest, str)
            or SHA256_DIGEST.fullmatch(digest) is None
        ):
            _fail("exact gate artifact metadata is stale or unsafe")
        matched.append(value)
    if len(matched) != 1:
        _fail(f"expected exactly one immutable artifact named {expected_name!r}")
    return matched[0]


def _gate_result(
    api: GitHubApi,
    *,
    kind: str,
    identity: PullIdentity,
    matrix_path: Path,
    selected: SelectedRun | None,
) -> GateResult:
    label = "Build" if kind == "build" else "Packaged E2E"
    if selected is None:
        return GateResult("pending", f"No exact current-head {label} run exists", 0, 0)
    if selected.status != "completed":
        return GateResult(
            "pending",
            f"Newest exact {label} run attempt is still in progress",
            selected.run_id,
            selected.run_attempt,
        )
    if selected.conclusion != "success":
        return GateResult(
            "failure",
            f"Newest exact {label} run attempt did not succeed",
            selected.run_id,
            selected.run_attempt,
        )
    try:
        graph = expected_jobs(
            matrix_path,
            WORKFLOWS[kind],
            event="pull_request",
            source_branch=identity.head_branch,
        )
        validate_jobs(
            api.jobs(selected.run_id),
            expected=graph,
            run_attempt=selected.run_attempt,
        )
        validate_exact_artifact(
            api.artifacts(selected.run_id),
            expected_name=_expected_artifact_name(kind, identity, selected.run_attempt),
            run_id=selected.run_id,
        )
    except (JobGraphError, MatrixError, PrGateError):
        return GateResult(
            "failure",
            f"Newest exact {label} evidence failed protected validation",
            selected.run_id,
            selected.run_attempt,
        )
    return GateResult(
        "success",
        f"Protected evaluator accepted newest exact {label} attempt",
        selected.run_id,
        selected.run_attempt,
    )


def _snapshot(
    api: GitHubApi, *, identity: PullIdentity, matrix_path: Path
) -> tuple[dict[str, GateResult], dict[str, SelectedRun | None]]:
    selected = {
        kind: select_newest_pull_run(
            api.workflow_runs(workflow, identity.head_sha),
            workflow=workflow,
            repository=api.repository,
            identity=identity,
        )
        for kind, workflow in WORKFLOWS.items()
    }
    results = {
        kind: _gate_result(
            api,
            kind=kind,
            identity=identity,
            matrix_path=matrix_path,
            selected=selected[kind],
        )
        for kind in WORKFLOWS
    }
    return results, selected


def evaluate(
    api: GitHubApi,
    *,
    repository: Path,
    trigger_run_id: int,
    implementation_sha: str,
    expected_pr_number: int,
    expected_merge_sha: str,
) -> dict[str, Any]:
    identity = resolve_pull_identity(
        api, trigger_run_id=trigger_run_id, implementation_sha=implementation_sha
    )
    if identity.number != expected_pr_number or identity.merge_sha != expected_merge_sha:
        raise NotEligible("pull request identity changed after candidate checkout")
    try:
        matrix_bytes = validate_pr_tree(repository, identity)
    except (GateControllerError, MatrixError, PrGateError) as exc:
        # The target head/base were authenticated before policy evaluation, so a protected-policy
        # mismatch is safe to report as failure on that exact current head.
        current = resolve_pull_identity(
            api, trigger_run_id=trigger_run_id, implementation_sha=implementation_sha
        )
        if current != identity:
            raise NotEligible("pull request changed while reporting a policy failure") from exc
        failure = GateResult("failure", "Ordinary PR changed protected branch policy", 0, 0)
        return _result(identity, {"build": failure, "e2e": failure})

    with tempfile.TemporaryDirectory(prefix="blockpops-pr-gate-") as temporary:
        matrix_path = Path(temporary) / "release-matrix.json"
        matrix_path.write_bytes(matrix_bytes)
        try:
            first_results, first_selected = _snapshot(
                api, identity=identity, matrix_path=matrix_path
            )
        except (JobGraphError, MatrixError, PrGateError) as exc:
            current = resolve_pull_identity(
                api,
                trigger_run_id=trigger_run_id,
                implementation_sha=implementation_sha,
            )
            if current != identity:
                raise NotEligible(
                    "pull request changed while gate evidence was unavailable"
                ) from exc
            failure = GateResult(
                "failure",
                "Protected evaluator could not authenticate newest exact gate evidence",
                0,
                0,
            )
            return _result(identity, {"build": failure, "e2e": failure})
        current = resolve_pull_identity(
            api, trigger_run_id=trigger_run_id, implementation_sha=implementation_sha
        )
        if current != identity:
            raise NotEligible("pull request identity changed during protected gate evaluation")
        try:
            second_results, second_selected = _snapshot(
                api, identity=identity, matrix_path=matrix_path
            )
        except (JobGraphError, MatrixError, PrGateError) as exc:
            current = resolve_pull_identity(
                api,
                trigger_run_id=trigger_run_id,
                implementation_sha=implementation_sha,
            )
            if current != identity:
                raise NotEligible(
                    "pull request changed during exact gate reselection"
                ) from exc
            failure = GateResult(
                "failure",
                "Protected evaluator could not reselect newest exact gate evidence",
                0,
                0,
            )
            return _result(identity, {"build": failure, "e2e": failure})
        final_identity = resolve_pull_identity(
            api, trigger_run_id=trigger_run_id, implementation_sha=implementation_sha
        )
        if final_identity != identity:
            raise NotEligible("pull request identity changed before protected outputs")
        if first_selected != second_selected or first_results != second_results:
            pending = {
                kind: GateResult(
                    "pending",
                    "Exact gate evidence changed during protected reselection",
                    0 if second_selected[kind] is None else second_selected[kind].run_id,
                    0 if second_selected[kind] is None else second_selected[kind].run_attempt,
                )
                for kind in WORKFLOWS
            }
            return _result(identity, pending)
        return _result(identity, second_results)


def _result(identity: PullIdentity, gates: dict[str, GateResult]) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "eligible": True,
        "pull_request": identity.number,
        "head_sha": identity.head_sha,
        "merge_sha": identity.merge_sha,
        "base_sha": identity.base_sha,
        "default_sha": identity.default_sha,
        "gates": {
            kind: {**asdict(gates[kind]), "context": CONTEXTS[kind]}
            for kind in ("build", "e2e")
        },
    }


def _append_outputs(path: Path, values: dict[str, Any]) -> None:
    try:
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            _fail("GITHUB_OUTPUT must be a regular non-symlink file")
    except FileNotFoundError:
        path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    for key, raw in values.items():
        value = str(raw).lower() if isinstance(raw, bool) else str(raw)
        if not re.fullmatch(r"[A-Za-z0-9._:/ -]{0,200}", value) or "\n" in value:
            _fail(f"unsafe workflow output {key}")
        lines.append(f"{key}={value}\n")
    with path.open("a", encoding="utf-8") as stream:
        stream.writelines(lines)


def _locate_outputs(identity: PullIdentity) -> dict[str, Any]:
    return {
        "eligible": True,
        "pr_number": identity.number,
        "merge_sha": identity.merge_sha,
        "head_sha": identity.head_sha,
    }


def _evaluation_outputs(value: dict[str, Any]) -> dict[str, Any]:
    outputs: dict[str, Any] = {
        "eligible": value["eligible"],
        "pr_number": value["pull_request"],
        "head_sha": value["head_sha"],
        "merge_sha": value["merge_sha"],
    }
    for kind in ("build", "e2e"):
        gate = value["gates"][kind]
        for field in ("state", "description", "run_id", "run_attempt"):
            outputs[f"{kind}_{field}"] = gate[field]
    return outputs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    locate = commands.add_parser("locate")
    locate.add_argument("--repository-name", required=True)
    locate.add_argument("--trigger-run-id", type=int, required=True)
    locate.add_argument("--implementation-sha", required=True)
    locate.add_argument("--github-output", type=Path)
    assess = commands.add_parser("evaluate")
    assess.add_argument("--repository-name", required=True)
    assess.add_argument("--repository", type=Path, required=True)
    assess.add_argument("--trigger-run-id", type=int, required=True)
    assess.add_argument("--implementation-sha", required=True)
    assess.add_argument("--expected-pr-number", type=int, required=True)
    assess.add_argument("--expected-merge-sha", required=True)
    assess.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        api = GitHubApi(
            repository=args.repository_name,
            token=os.environ.get("GITHUB_TOKEN", ""),
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        if args.command == "locate":
            identity = resolve_pull_identity(
                api,
                trigger_run_id=args.trigger_run_id,
                implementation_sha=args.implementation_sha,
            )
            value: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                **_locate_outputs(identity),
            }
            outputs = _locate_outputs(identity)
        else:
            value = evaluate(
                api,
                repository=args.repository,
                trigger_run_id=args.trigger_run_id,
                implementation_sha=args.implementation_sha,
                expected_pr_number=args.expected_pr_number,
                expected_merge_sha=args.expected_merge_sha,
            )
            outputs = _evaluation_outputs(value)
        if args.github_output is not None:
            _append_outputs(args.github_output, outputs)
        print(json.dumps(value, sort_keys=True, separators=(",", ":")))
        return 0
    except NotEligible as exc:
        if args.github_output is not None:
            _append_outputs(args.github_output, {"eligible": False})
        print(f"trusted PR gate is ineligible: {exc}", file=sys.stderr)
        return 0
    except (GateControllerError, JobGraphError, MatrixError, OSError, PrGateError) as exc:
        print(f"trusted PR gate error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
