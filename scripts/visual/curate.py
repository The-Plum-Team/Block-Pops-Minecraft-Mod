#!/usr/bin/env python3
"""Authenticate exact Actions evidence and create one data-only visual-review queue entry.

Only protected default-branch code executes here. Candidate commits are fetched as inert file
bytes through the GitHub API; neither candidate nor reference repository code is checked out or
executed. Every run, attempt, tree, workflow, job graph, aggregate artifact, matrix, and scenario
contract is rebound immediately before semantic pairing.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import ssl
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.scenario_contract import ScenarioContract, ScenarioContractError, load_contract  # noqa: E402
from e2e.visual_capsule import write_capsule  # noqa: E402
from e2e.visual_evidence import (  # noqa: E402
    EvidenceBundle,
    SourceExpectation,
    VisualFrame,
    VisualEvidenceError,
    canonicalize_png,
    canonical_reference_identity,
    collect_evidence,
    extract_authenticated_artifact,
    validate_attestation,
)
from scripts.ci.e2e_fanin import (  # noqa: E402
    FanInError,
    aggregate_artifact_name,
    validate_aggregate,
)
from scripts.ci.e2e_job_graph import JobGraphError, expected_jobs, validate_jobs  # noqa: E402
from scripts.lib.secure_json import canonical_json  # noqa: E402
from scripts.pages.visual_anchor import (  # noqa: E402
    VisualAnchorError,
    validate_anchor,
    visual_anchor_artifact_name,
)
from scripts.release.matrix import (  # noqa: E402
    MatrixError,
    gha_matrix,
    load_matrix,
    matrix_sha256,
    valid_branch_name,
)
from scripts.visual.handoff import HandoffError, build_queue  # noqa: E402


WORKFLOW_PATH = ".github/workflows/on-demand-e2e.yml"
DEFAULT_BRANCH = "master"
REFERENCE_EVENTS = frozenset({"schedule", "workflow_dispatch"})
SOURCE_EVENTS = frozenset({"pull_request_target", "schedule", "workflow_dispatch"})
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256_PREFIX = re.compile(r"^sha256:([0-9a-f]{64})$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
MAX_API_JSON = 32 * 1024 * 1024
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_ARTIFACT_BYTES = 512 * 1024 * 1024
MAX_RUNS = 500
MAX_JOBS = 1000
MAX_ARTIFACTS = 1000
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


class CurationError(ValueError):
    """Authenticated source evidence is missing, stale, mixed, or malformed."""


def _fail(message: str) -> None:
    raise CurationError(message)


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value}")


def _strict_json(payload: bytes, label: str) -> Any:
    if not 1 <= len(payload) <= MAX_API_JSON:
        _fail(f"{label} response is empty or oversized")
    try:
        return json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicates,
            parse_constant=_reject_nonfinite,
        )
    except (UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise CurationError(f"{label} response is not strict JSON: {exc}") from exc


def _positive(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        _fail(f"{label} must be a positive integer")
    return value


def _sha1(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        _fail(f"{label} must be a lowercase Git SHA-1")
    return value


def _repository(value: Any, label: str = "repository") -> str:
    if not isinstance(value, str) or REPOSITORY.fullmatch(value) is None:
        _fail(f"{label} must use safe owner/name form")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        _fail(f"{label} must be a UTC RFC3339 timestamp")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise CurationError(f"{label} is invalid") from exc


class _SafeRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward the GitHub bearer credential to artifact object storage."""

    def redirect_request(
        self,
        request: Any,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> Any:
        previous = urllib.parse.urlsplit(request.full_url)
        redirected_url = urllib.parse.urlsplit(newurl)
        try:
            previous_port = previous.port or 443
            redirected_port = redirected_url.port or 443
        except ValueError:
            return None
        if (
            redirected_url.scheme != "https"
            or not redirected_url.hostname
            or redirected_url.username is not None
            or redirected_url.password is not None
            or redirected_url.fragment
        ):
            return None
        redirected = super().redirect_request(request, fp, code, msg, headers, newurl)
        same_origin = (
            previous.scheme,
            previous.hostname,
            previous_port,
        ) == (
            redirected_url.scheme,
            redirected_url.hostname,
            redirected_port,
        )
        if redirected is not None and not same_origin:
            redirected.remove_header("Authorization")
        return redirected


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = _repository(repository)
        if not token or len(token) > 4096:
            _fail("GITHUB_TOKEN is absent or invalid")
        parsed = urllib.parse.urlsplit(api_url)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            _fail("GitHub API URL must be an HTTPS origin")
        self.api_url = api_url.rstrip("/")
        self.token = token
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _SafeRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )

    def _request(self, route: str, *, maximum: int, accept: str = "application/vnd.github+json") -> bytes:
        if not route.startswith("/") or "\x00" in route:
            _fail("GitHub API route is unsafe")
        request = urllib.request.Request(
            self.api_url + route,
            headers={
                "Accept": accept,
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-secretless-visual-curator/1",
            },
        )
        try:
            with self.opener.open(request, timeout=60) as response:
                if response.status != 200:
                    _fail(f"GitHub API returned HTTP {response.status}")
                payload = response.read(maximum + 1)
        except urllib.error.HTTPError as exc:
            raise CurationError(f"GitHub API returned HTTP {exc.code} for an authenticated route") from exc
        except urllib.error.URLError as exc:
            raise CurationError(f"GitHub API transport failed: {exc.reason}") from exc
        if not 1 <= len(payload) <= maximum:
            _fail("GitHub API response is empty or exceeds its byte bound")
        return payload

    def json(self, route: str, *, label: str) -> Any:
        return _strict_json(self._request(route, maximum=MAX_API_JSON), label)

    def pages(self, route: str, *, array_key: str, label: str, maximum: int) -> list[dict[str, Any]]:
        separator = "&" if "?" in route else "?"
        records: list[dict[str, Any]] = []
        for page in range(1, 11):
            document = self.json(
                f"{route}{separator}per_page=100&page={page}", label=f"{label} page {page}"
            )
            if not isinstance(document, dict) or not isinstance(document.get(array_key), list):
                _fail(f"{label} API page shape is invalid")
            page_records = document[array_key]
            if any(not isinstance(item, dict) for item in page_records):
                _fail(f"{label} API page contains a non-object record")
            records.extend(page_records)
            if len(records) > maximum:
                _fail(f"{label} API result exceeds its record bound")
            if len(page_records) < 100:
                return records
        _fail(f"{label} API pagination exceeds ten pages")

    def repo_route(self, repository: str, suffix: str) -> str:
        repository = _repository(repository)
        owner, name = repository.split("/", 1)
        return f"/repos/{urllib.parse.quote(owner, safe='')}/{urllib.parse.quote(name, safe='')}{suffix}"

    def repository_record(self) -> dict[str, Any]:
        value = self.json(self.repo_route(self.repository, ""), label="repository")
        if not isinstance(value, dict):
            _fail("repository API record is not an object")
        return value

    def run(self, run_id: int) -> dict[str, Any]:
        value = self.json(
            self.repo_route(self.repository, f"/actions/runs/{_positive(run_id, 'run id')}"),
            label="workflow run",
        )
        if not isinstance(value, dict):
            _fail("workflow run API record is not an object")
        return value

    def run_attempt(self, run_id: int, run_attempt: int) -> dict[str, Any]:
        value = self.json(
            self.repo_route(
                self.repository,
                f"/actions/runs/{_positive(run_id, 'run id')}/attempts/"
                f"{_positive(run_attempt, 'run attempt')}",
            ),
            label="workflow run attempt",
        )
        if not isinstance(value, dict):
            _fail("workflow run-attempt API record is not an object")
        return value

    def jobs(self, run_id: int) -> list[dict[str, Any]]:
        return self.pages(
            self.repo_route(self.repository, f"/actions/runs/{run_id}/jobs?filter=all"),
            array_key="jobs",
            label="workflow jobs",
            maximum=MAX_JOBS,
        )

    def artifacts(self, run_id: int) -> list[dict[str, Any]]:
        return self.pages(
            self.repo_route(self.repository, f"/actions/runs/{run_id}/artifacts"),
            array_key="artifacts",
            label="workflow artifacts",
            maximum=MAX_ARTIFACTS,
        )

    def artifact(self, artifact_id: int) -> dict[str, Any]:
        value = self.json(
            self.repo_route(
                self.repository,
                f"/actions/artifacts/{_positive(artifact_id, 'artifact id')}",
            ),
            label="workflow artifact",
        )
        if not isinstance(value, dict):
            _fail("workflow artifact API record is not an object")
        return value

    def compare(self, repository: str, base: str, head: str) -> dict[str, Any]:
        value = self.json(
            self.repo_route(
                repository,
                "/compare/"
                + urllib.parse.quote(_sha1(base, "compare base"), safe="")
                + "..."
                + urllib.parse.quote(_sha1(head, "compare head"), safe=""),
            ),
            label="commit comparison",
        )
        if not isinstance(value, dict):
            _fail("commit comparison API record is not an object")
        return value

    def branch_head(self, repository: str, branch: str) -> str:
        if not valid_branch_name(branch):
            _fail("branch name is unsafe")
        value = self.json(
            self.repo_route(repository, f"/branches/{urllib.parse.quote(branch, safe='')}"),
            label="branch head",
        )
        try:
            return _sha1(value["commit"]["sha"], "branch head SHA")
        except (KeyError, TypeError) as exc:
            raise CurationError("branch head API record is malformed") from exc

    def commit_identity(self, repository: str, commit: str) -> tuple[str, tuple[str, ...]]:
        value = self.json(
            self.repo_route(repository, f"/git/commits/{_sha1(commit, 'commit SHA')}"),
            label="Git commit",
        )
        try:
            if value["sha"] != commit:
                _fail("Git commit API returned a different commit")
            parents = value["parents"]
            if not isinstance(parents, list) or len(parents) > 16:
                _fail("Git commit parent inventory is invalid")
            parent_shas = tuple(
                _sha1(parent["sha"], "commit parent SHA")
                for parent in parents
                if isinstance(parent, dict)
            )
            if len(parent_shas) != len(parents) or len(set(parent_shas)) != len(parent_shas):
                _fail("Git commit parent inventory is malformed or duplicated")
            return _sha1(value["tree"]["sha"], "commit tree SHA"), parent_shas
        except (KeyError, TypeError) as exc:
            raise CurationError("Git commit API record is malformed") from exc

    def commit_tree(self, repository: str, commit: str) -> str:
        return self.commit_identity(repository, commit)[0]

    def file(self, repository: str, path: str, commit: str) -> bytes:
        if path.startswith("/") or ".." in Path(path).parts or "\x00" in path:
            _fail("requested repository file path is unsafe")
        route = self.repo_route(
            repository,
            "/contents/"
            + urllib.parse.quote(path, safe="/")
            + "?ref="
            + urllib.parse.quote(_sha1(commit, "file commit"), safe=""),
        )
        value = self.json(route, label=f"repository file {path}")
        if not isinstance(value, dict) or value.get("type") != "file" or value.get("encoding") != "base64":
            _fail(f"repository file API record is invalid for {path}")
        encoded = value.get("content")
        if not isinstance(encoded, str) or len(encoded) > MAX_FILE_BYTES * 2:
            _fail(f"repository file base64 is invalid for {path}")
        if any(character.isspace() and character not in "\r\n" for character in encoded):
            _fail(f"repository file base64 contains unsupported whitespace for {path}")
        encoded = encoded.replace("\r", "").replace("\n", "")
        try:
            payload = base64.b64decode(encoded, validate=True)
        except ValueError as exc:
            raise CurationError(f"repository file is not canonical base64: {path}") from exc
        if not 1 <= len(payload) <= MAX_FILE_BYTES or value.get("size") != len(payload):
            _fail(f"repository file size is invalid for {path}")
        git_blob = hashlib.sha1(f"blob {len(payload)}\0".encode("ascii") + payload).hexdigest()
        if value.get("sha") != git_blob:
            _fail(f"repository file Git blob identity is stale for {path}")
        return payload

    def workflow_runs(self, branch: str, head_sha: str) -> list[dict[str, Any]]:
        workflow = urllib.parse.quote(WORKFLOW_PATH.rsplit("/", 1)[1], safe="")
        return self.pages(
            self.repo_route(
                self.repository,
                f"/actions/workflows/{workflow}/runs?branch={urllib.parse.quote(branch, safe='')}"
                f"&head_sha={urllib.parse.quote(_sha1(head_sha, 'workflow head SHA'), safe='')}",
            ),
            array_key="workflow_runs",
            label="workflow runs",
            maximum=MAX_RUNS,
        )

    def pull_request(self, number: int) -> dict[str, Any]:
        value = self.json(
            self.repo_route(self.repository, f"/pulls/{_positive(number, 'pull request number')}"),
            label="pull request",
        )
        if not isinstance(value, dict):
            _fail("pull request API record is not an object")
        return value

    def pull_requests_for_commit(self, commit: str) -> list[dict[str, Any]]:
        value = self.json(
            self.repo_route(
                self.repository,
                f"/commits/{_sha1(commit, 'associated commit SHA')}/pulls?per_page=100",
            ),
            label="commit pull requests",
        )
        if (
            not isinstance(value, list)
            or len(value) >= 100
            or any(not isinstance(item, dict) for item in value)
        ):
            _fail("commit pull-request association is malformed or excessive")
        return value

    def download_artifact(self, artifact_id: int, destination: Path, expected_size: int) -> None:
        artifact_id = _positive(artifact_id, "artifact id")
        if not 1 <= expected_size <= MAX_ARTIFACT_BYTES:
            _fail("artifact declared size is outside its bound")
        payload = self._request(
            self.repo_route(self.repository, f"/actions/artifacts/{artifact_id}/zip"),
            maximum=MAX_ARTIFACT_BYTES,
            accept="application/vnd.github+json",
        )
        if len(payload) != expected_size:
            _fail("downloaded artifact size disagrees with authenticated metadata")
        if destination.exists() or destination.is_symlink():
            _fail("artifact archive destination must be fresh")
        with destination.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())


@dataclass(frozen=True)
class RunIdentity:
    repository: str
    head_repository: str
    head_branch: str
    head_sha: str
    run_id: int
    run_attempt: int
    event: str
    created_at: datetime


@dataclass(frozen=True)
class ArtifactIdentity:
    artifact_id: int
    name: str
    digest: str
    size: int


@dataclass(frozen=True)
class TestedIdentity:
    repository: str
    source_head_repository: str
    source_head_branch: str
    source_head_commit: str
    tested_commit: str
    tested_tree: str
    base_branch_hint: str | None


@dataclass(frozen=True)
class ReferenceCandidate:
    identity: RunIdentity
    status: str
    conclusion: str | None


def _run_identity(value: Any, *, repository: str, expected_id: int | None = None) -> RunIdentity:
    if not isinstance(value, dict):
        _fail("workflow run must be an object")
    run_id = _positive(value.get("id"), "workflow run id")
    if expected_id is not None and run_id != expected_id:
        _fail("workflow run id disagrees with the trigger")
    run_attempt = _positive(value.get("run_attempt"), "workflow run attempt")
    if value.get("path") != WORKFLOW_PATH:
        _fail("workflow run path is not the protected packaged workflow")
    event = value.get("event")
    if event not in SOURCE_EVENTS:
        _fail("workflow run event is not an approved packaged trigger")
    record_repository = value.get("repository")
    if not isinstance(record_repository, dict) or record_repository.get("full_name") != repository:
        _fail("workflow run belongs to a different repository")
    head_repository = value.get("head_repository")
    if not isinstance(head_repository, dict):
        _fail("workflow run has no head repository")
    head_repository_name = _repository(head_repository.get("full_name"), "head repository")
    head_branch = value.get("head_branch")
    if not isinstance(head_branch, str) or not valid_branch_name(head_branch):
        _fail("workflow run head branch is unsafe")
    return RunIdentity(
        repository=repository,
        head_repository=head_repository_name,
        head_branch=head_branch,
        head_sha=_sha1(value.get("head_sha"), "workflow run head SHA"),
        run_id=run_id,
        run_attempt=run_attempt,
        event=event,
        created_at=_timestamp(value.get("created_at"), "workflow run created_at"),
    )


def authenticate_run(value: Any, *, repository: str, expected_id: int | None = None) -> RunIdentity:
    if not isinstance(value, dict):
        _fail("workflow run must be an object")
    if value.get("status") != "completed" or value.get("conclusion") != "success":
        _fail("workflow run is not a completed success")
    return _run_identity(value, repository=repository, expected_id=expected_id)


def reference_candidates(
    values: Iterable[Any], *, repository: str, branch: str, head_sha: str
) -> tuple[ReferenceCandidate, ...]:
    """Return every exact current-head run newest-first, including failures and pending runs."""

    selected: list[ReferenceCandidate] = []
    identities: set[tuple[int, int]] = set()
    for value in values:
        if not isinstance(value, dict):
            _fail("workflow runs response contains a non-object")
        if (
            value.get("path") != WORKFLOW_PATH
            or value.get("event") not in REFERENCE_EVENTS
            or value.get("head_branch") != branch
            or value.get("head_sha") != head_sha
            or value.get("display_title") != f"Packaged E2E / {head_sha}"
        ):
            continue
        identity = _run_identity(value, repository=repository)
        if identity.head_repository != repository:
            _fail("reference run head repository is not the protected repository")
        status = value.get("status")
        conclusion = value.get("conclusion")
        if status not in RUN_STATUSES:
            _fail("matching reference run has an invalid status")
        if (status == "completed" and conclusion not in RUN_CONCLUSIONS) or (
            status != "completed" and conclusion is not None
        ):
            _fail("matching reference run has an invalid conclusion for its status")
        key = (identity.run_id, identity.run_attempt)
        if key in identities:
            _fail("workflow runs response repeats a run/attempt")
        identities.add(key)
        selected.append(
            ReferenceCandidate(identity=identity, status=status, conclusion=conclusion)
        )
    selected.sort(
        key=lambda item: (
            item.identity.created_at,
            item.identity.run_id,
            item.identity.run_attempt,
        ),
        reverse=True,
    )
    if not selected:
        _fail("no packaged run exists for the exact current baseline head")
    return tuple(selected)


def is_attestation_mode(jobs: Iterable[Any], *, run_attempt: int) -> bool:
    """Identify only the protected reusable attestation job; never infer mode from artifacts."""

    ids: set[int] = set()
    names: set[str] = set()
    attestation_jobs = 0
    current_jobs = 0
    for value in jobs:
        if not isinstance(value, dict):
            _fail("job API response contains a non-object")
        job_id = _positive(value.get("id"), "job id")
        name = value.get("name")
        attempt = _positive(value.get("run_attempt"), "job run attempt")
        if job_id in ids or not isinstance(name, str) or not name:
            _fail("job API response repeats an id or has an invalid name")
        ids.add(job_id)
        if attempt != run_attempt:
            continue
        if name in names:
            _fail("job API response repeats a name in the current attempt")
        names.add(name)
        current_jobs += 1
        if name.endswith(" / Verify exact tested tree"):
            attestation_jobs += 1
    if current_jobs == 0:
        _fail("matching reference run has no jobs for its current attempt")
    if attestation_jobs > 1:
        _fail("matching reference run repeats the protected attestation job")
    return attestation_jobs == 1


def select_reference_run(
    api: GitHubApi,
    values: Iterable[Any],
    *,
    repository: str,
    branch: str,
    head_sha: str,
) -> tuple[RunIdentity, ArtifactIdentity]:
    """Select the newest exact full run, excluding only authenticated attestation-mode runs."""

    for candidate in reference_candidates(
        values, repository=repository, branch=branch, head_sha=head_sha
    ):
        run = candidate.identity
        artifacts = api.artifacts(run.run_id)
        anchor = exact_visual_anchor_artifact(
            artifacts,
            run_id=run.run_id,
            run_attempt=run.run_attempt,
            branch=branch,
            tested_commit=run.head_sha,
        )
        jobs = api.jobs(run.run_id)
        if run.event == "workflow_dispatch" and is_attestation_mode(
            jobs, run_attempt=run.run_attempt
        ):
            continue
        if candidate.status != "completed" or candidate.conclusion != "success":
            _fail("newest exact current-head full packaged run is not a completed success")
        if anchor is None:
            _fail("newest exact current-head full packaged run has no lossless visual anchor")
        return run, anchor
    _fail("current default head has no full packaged baseline run")


def exact_aggregate_artifact(
    values: Iterable[Any], *, run_id: int, tested_commit: str, run_attempt: int
) -> ArtifactIdentity | None:
    expected_name = aggregate_artifact_name(
        _sha1(tested_commit, "tested artifact commit"),
        _positive(run_attempt, "artifact run attempt"),
    )
    matches: list[ArtifactIdentity] = []
    ids: set[int] = set()
    names: set[str] = set()
    for value in values:
        if not isinstance(value, dict):
            _fail("artifact API response contains a non-object")
        artifact_id = _positive(value.get("id"), "artifact id")
        name = value.get("name")
        if artifact_id in ids or not isinstance(name, str) or name in names:
            _fail("artifact API response repeats an id or name")
        ids.add(artifact_id)
        names.add(name)
        if name != expected_name:
            continue
        owner = value.get("workflow_run")
        match = SHA256_PREFIX.fullmatch(value.get("digest", ""))
        size = _positive(value.get("size_in_bytes"), "artifact size")
        if (
            value.get("expired") is not False
            or not isinstance(owner, dict)
            or owner.get("id") != run_id
            or match is None
            or size > MAX_ARTIFACT_BYTES
        ):
            _fail("aggregate artifact metadata is stale or unsafe")
        matches.append(
            ArtifactIdentity(
                artifact_id=artifact_id,
                name=name,
                digest=match.group(1),
                size=size,
            )
        )
    if len(matches) > 1:
        _fail("source run publishes more than one aggregate artifact")
    return matches[0] if matches else None


def aggregate_tested_commit(
    values: Iterable[Any], *, run_id: int, run_attempt: int
) -> str:
    """Recover the immutable tested commit from this exact attempt's aggregate name."""

    run_id = _positive(run_id, "aggregate owner run id")
    run_attempt = _positive(run_attempt, "aggregate run attempt")
    pattern = re.compile(
        rf"^packaged-e2e-([0-9a-f]{{40}})-{run_attempt}-aggregate$"
    )
    matches: list[str] = []
    ids: set[int] = set()
    names: set[str] = set()
    for value in values:
        if not isinstance(value, dict):
            _fail("artifact API response contains a non-object")
        artifact_id = _positive(value.get("id"), "artifact id")
        name = value.get("name")
        if artifact_id in ids or not isinstance(name, str) or name in names:
            _fail("artifact API response repeats an id or name")
        ids.add(artifact_id)
        names.add(name)
        match = pattern.fullmatch(name)
        if match is None:
            continue
        owner = value.get("workflow_run")
        digest = SHA256_PREFIX.fullmatch(value.get("digest", ""))
        size = _positive(value.get("size_in_bytes"), "artifact size")
        if (
            value.get("expired") is not False
            or not isinstance(owner, dict)
            or owner.get("id") != run_id
            or digest is None
            or size > MAX_ARTIFACT_BYTES
        ):
            _fail("aggregate artifact metadata is stale or unsafe")
        matches.append(match.group(1))
    if len(matches) != 1:
        _fail("source run must publish one exact aggregate for its current attempt")
    return matches[0]


def exact_visual_anchor_artifact(
    values: Iterable[Any],
    *,
    run_id: int,
    run_attempt: int,
    branch: str,
    tested_commit: str,
) -> ArtifactIdentity | None:
    """Authenticate one lossless anchor by its branch token and exact commit."""

    expected_name = visual_anchor_artifact_name(
        branch,
        _sha1(tested_commit, "visual anchor commit"),
        _positive(run_id, "visual anchor owner run id"),
        _positive(run_attempt, "visual anchor owner run attempt"),
    )
    matches: list[ArtifactIdentity] = []
    ids: set[int] = set()
    names: set[str] = set()
    for value in values:
        if not isinstance(value, dict):
            _fail("artifact API response contains a non-object")
        artifact_id = _positive(value.get("id"), "artifact id")
        name = value.get("name")
        if artifact_id in ids or not isinstance(name, str) or name in names:
            _fail("artifact API response repeats an id or name")
        ids.add(artifact_id)
        names.add(name)
        if name != expected_name:
            continue
        owner = value.get("workflow_run")
        match = SHA256_PREFIX.fullmatch(value.get("digest", ""))
        size = _positive(value.get("size_in_bytes"), "artifact size")
        if (
            value.get("expired") is not False
            or not isinstance(owner, dict)
            or owner.get("id") != run_id
            or owner.get("head_sha") != tested_commit
            or match is None
            or size > MAX_ARTIFACT_BYTES
        ):
            _fail("visual anchor artifact metadata is stale or unsafe")
        matches.append(
            ArtifactIdentity(
                artifact_id=artifact_id,
                name=name,
                digest=match.group(1),
                size=size,
            )
        )
    if len(matches) > 1:
        _fail("source run publishes more than one exact visual anchor")
    return matches[0] if matches else None


def _write_exact(path: Path, payload: bytes) -> None:
    if path.exists() or path.is_symlink():
        _fail(f"refusing to replace temporary identity file: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())


def _source_files(
    api: GitHubApi, *, repository: str, tested_commit: str, root: Path
) -> tuple[Path, Path, bytes]:
    matrix = api.file(repository, "release/release-matrix.json", tested_commit)
    contract = api.file(repository, "e2e/scenario-contract.json", tested_commit)
    workflow = api.file(repository, WORKFLOW_PATH, tested_commit)
    matrix_path = root / "release-matrix.json"
    contract_path = root / "scenario-contract.json"
    _write_exact(matrix_path, matrix)
    _write_exact(contract_path, contract)
    return matrix_path, contract_path, workflow


def _projection(event: str) -> str:
    return "scheduled-anchors" if event == "schedule" else "pr-anchors"


def _projected_identity(
    matrix: dict[str, Any], contract: ScenarioContract, projection: str
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    rows = gha_matrix(matrix, projection, contract=contract)["include"]
    if not rows:
        _fail("branch matrix projects no packaged runtime lanes")
    nodes = tuple(sorted(row["artifact_node"] for row in rows))
    scenarios = tuple(
        sorted({scenario for row in rows for scenario in row["scenarios"].split(",") if scenario})
    )
    if len(nodes) != len(rows) or not scenarios:
        _fail("branch matrix projection contains ambiguous lanes or scenarios")
    return nodes, scenarios


def _job_graph(
    jobs: list[dict[str, Any]],
    *,
    run: RunIdentity,
    matrix_path: Path,
    tested: TestedIdentity | None = None,
) -> dict[str, Any]:
    try:
        return validate_jobs(
            jobs,
            expected=expected_jobs(
                matrix_path,
                "on-demand-e2e.yml",
                event=run.event,
                source_branch=(
                    run.head_branch if tested is None else tested.source_head_branch
                ),
            ),
            run_attempt=run.run_attempt,
        )
    except JobGraphError as exc:
        raise CurationError(str(exc)) from exc


def _attestation(
    *,
    run: RunIdentity,
    tested: TestedIdentity,
    workflow: bytes,
    graph: dict[str, Any],
    artifact: ArtifactIdentity,
    matrix_path: Path,
    contract: ScenarioContract,
    matrix_branch: str,
    nodes: tuple[str, ...],
    scenarios: tuple[str, ...],
) -> tuple[dict[str, Any], SourceExpectation]:
    if (
        tested.repository != run.repository
        or tested.source_head_repository != run.repository
    ):
        _fail("tested/source repository disagrees with the protected workflow run")
    expectation = SourceExpectation(
        repository=run.repository,
        source_head_repository=tested.source_head_repository,
        source_head_branch=tested.source_head_branch,
        base_branch=matrix_branch,
        source_head_commit=tested.source_head_commit,
        tested_commit=tested.tested_commit,
        tested_tree=tested.tested_tree,
        workflow_path=WORKFLOW_PATH,
        workflow_sha256=hashlib.sha256(workflow).hexdigest(),
        job_graph_sha256=graph["sha256"],
        event=run.event,
        run_id=run.run_id,
        run_attempt=run.run_attempt,
        artifact_id=artifact.artifact_id,
        artifact_name=artifact.name,
        artifact_sha256=artifact.digest,
    )
    value = {
        "schema_version": 1,
        "repository": expectation.repository,
        "source_head_repository": expectation.source_head_repository,
        "source_head_branch": expectation.source_head_branch,
        "base_branch": expectation.base_branch,
        "source_head_commit": expectation.source_head_commit,
        "tested_commit": expectation.tested_commit,
        "tested_tree": expectation.tested_tree,
        "workflow_path": expectation.workflow_path,
        "workflow_sha256": expectation.workflow_sha256,
        "job_graph_sha256": expectation.job_graph_sha256,
        "event": expectation.event,
        "run_id": expectation.run_id,
        "run_attempt": expectation.run_attempt,
        "status": "completed",
        "conclusion": "success",
        "artifact_id": expectation.artifact_id,
        "artifact_name": expectation.artifact_name,
        "artifact_sha256": expectation.artifact_sha256,
        "matrix_sha256": matrix_sha256(matrix_path),
        "contract_sha256": contract.sha256,
        "artifact_nodes": list(nodes),
        "scenarios": list(scenarios),
    }
    try:
        validated = validate_attestation(
            value,
            expectation,
            expected_matrix_sha256=value["matrix_sha256"],
            expected_contract_sha256=contract.sha256,
        )
    except VisualEvidenceError as exc:
        raise CurationError(str(exc)) from exc
    return validated, expectation


def _resolve_tested_identity(
    api: GitHubApi,
    run_record: dict[str, Any] | None,
    run: RunIdentity,
    *,
    expected_tested: str | None = None,
    expected_source_branch: str | None = None,
    expected_source_head: str | None = None,
) -> TestedIdentity:
    """Bind a protected controller run to the separate source bytes that executed."""

    if run.event == "pull_request_target":
        if run_record is None:
            _fail("pull_request_target identity requires its authenticated run record")
        if (
            run.head_repository != run.repository
            or run.head_branch != DEFAULT_BRANCH
        ):
            _fail("pull_request_target run is not anchored to the default controller")
        pulls = run_record.get("pull_requests")
        if not isinstance(pulls, list) or len(pulls) != 1 or not isinstance(pulls[0], dict):
            _fail("pull_request_target run does not identify exactly one pull request")
        pull = api.pull_request(_positive(pulls[0].get("number"), "pull request number"))
        try:
            head = pull["head"]
            base = pull["base"]
            state = pull["state"]
            merged = pull.get("merged")
            if (
                head["repo"]["full_name"] != run.repository
                or base["repo"]["full_name"] != run.repository
            ):
                _fail("pull request is not a current same-repository association")
            source_head = _sha1(head["sha"], "pull request head SHA")
            source_branch = head["ref"]
            base_branch = base["ref"]
            base_commit = _sha1(base["sha"], "pull request base SHA")
            delivered_commit = _sha1(
                pull["merge_commit_sha"], "pull request merge SHA"
            )
        except (KeyError, TypeError) as exc:
            raise CurationError("pull request API record is malformed") from exc
        if not valid_branch_name(base_branch) or not valid_branch_name(source_branch):
            _fail("pull request source/base branch is unsafe")
        if (
            expected_source_branch is not None
            and source_branch != expected_source_branch
        ) or (
            expected_source_head is not None
            and source_head != _sha1(expected_source_head, "expected source head SHA")
        ):
            _fail("pull request source identity disagrees with the authenticated locator")
        tested_commit = _sha1(expected_tested, "aggregate tested merge SHA")
        if tested_commit == source_head:
            _fail("pull request tested merge must differ from its source head")
        tested_tree, parents = api.commit_identity(run.repository, tested_commit)
        if state == "open":
            if delivered_commit != tested_commit:
                _fail("open pull request synthetic merge changed after the tested run")
            if api.branch_head(run.repository, base_branch) != base_commit:
                _fail("pull request base branch advanced after merge synthesis")
            if parents != (base_commit, source_head):
                _fail("tested pull request merge does not have exact current base/head parents")
        elif state == "closed" and merged is True:
            if api.branch_head(run.repository, base_branch) != delivered_commit:
                _fail("merged pull request is no longer the exact release-branch head")
            delivered_tree, delivered_parents = api.commit_identity(
                run.repository, delivered_commit
            )
            if (
                delivered_tree != tested_tree
                or len(delivered_parents) != 2
                or delivered_parents[1] != source_head
                or parents != delivered_parents
            ):
                _fail("merged pull request no longer delivers the exact tested tree")
        else:
            _fail("pull request closed without delivering its tested tree")
        return TestedIdentity(
            repository=run.repository,
            source_head_repository=run.repository,
            source_head_branch=source_branch,
            source_head_commit=source_head,
            tested_commit=tested_commit,
            tested_tree=tested_tree,
            base_branch_hint=base_branch,
        )
    if run.head_repository != run.repository:
        _fail("non-PR packaged controller repository is not protected")
    tested_commit = (
        run.head_sha
        if expected_tested is None
        else _sha1(expected_tested, "aggregate tested commit")
    )
    source_head = (
        run.head_sha
        if expected_source_head is None
        else _sha1(expected_source_head, "expected source head SHA")
    )
    source_branch = run.head_branch if expected_source_branch is None else expected_source_branch
    if not valid_branch_name(source_branch):
        _fail("packaged source branch is unsafe")
    if tested_commit == run.head_sha:
        if source_head != run.head_sha or source_branch != run.head_branch:
            _fail("branch source locator disagrees with its protected run")
        if api.branch_head(run.repository, run.head_branch) != run.head_sha:
            _fail("packaged run is no longer the exact current head of its source branch")
        tested_tree, _parents = api.commit_identity(run.repository, run.head_sha)
        return TestedIdentity(
            repository=run.repository,
            source_head_repository=run.repository,
            source_head_branch=run.head_branch,
            source_head_commit=run.head_sha,
            tested_commit=run.head_sha,
            tested_tree=tested_tree,
            base_branch_hint=None,
        )
    if run.event != "workflow_dispatch" or run.head_branch != DEFAULT_BRANCH:
        _fail("non-default dispatch cannot name separate candidate bytes")
    if source_head != tested_commit or not source_branch.startswith("automation/release-sync/"):
        _fail("release synchronization source identity is not exact")
    associated = []
    for summary in api.pull_requests_for_commit(tested_commit):
        try:
            if (
                summary["head"]["sha"] == tested_commit
                and summary["head"]["ref"] == source_branch
                and summary["head"]["repo"]["full_name"] == run.repository
                and summary["base"]["repo"]["full_name"] == run.repository
            ):
                associated.append(
                    api.pull_request(_positive(summary["number"], "pull request number"))
                )
        except (KeyError, TypeError) as exc:
            raise CurationError("commit pull-request association is malformed") from exc
    if len(associated) != 1:
        _fail("release synchronization commit does not identify exactly one current pull request")
    pull = associated[0]
    try:
        head = pull["head"]
        base = pull["base"]
        state = pull["state"]
        merged = pull.get("merged")
        delivered_commit = _sha1(pull["merge_commit_sha"], "pull request merge SHA")
        base_branch = base["ref"]
        base_commit = _sha1(base["sha"], "pull request base SHA")
        if (
            head["sha"] != tested_commit
            or head["ref"] != source_branch
            or head["repo"]["full_name"] != run.repository
            or base["repo"]["full_name"] != run.repository
        ):
            _fail("release synchronization pull request changed after association")
    except (KeyError, TypeError) as exc:
        raise CurationError("release synchronization pull request is malformed") from exc
    if not valid_branch_name(base_branch):
        _fail("release synchronization base branch is unsafe")
    tested_tree, tested_parents = api.commit_identity(run.repository, tested_commit)
    if state == "open":
        if (
            api.branch_head(run.repository, source_branch) != tested_commit
            or api.branch_head(run.repository, base_branch) != base_commit
            or len(tested_parents) != 2
            or tested_parents[0] != base_commit
        ):
            _fail("open release synchronization source/base topology changed")
    elif state == "closed" and merged is True:
        if api.branch_head(run.repository, base_branch) != delivered_commit:
            _fail("merged release synchronization is no longer the exact release head")
        delivered_tree, delivered_parents = api.commit_identity(
            run.repository, delivered_commit
        )
        if (
            delivered_tree != tested_tree
            or len(delivered_parents) != 2
            or delivered_parents[1] != tested_commit
        ):
            _fail("merged release synchronization no longer delivers the tested tree")
    else:
        _fail("release synchronization pull request closed without delivery")
    return TestedIdentity(
        repository=run.repository,
        source_head_repository=run.repository,
        source_head_branch=source_branch,
        source_head_commit=source_head,
        tested_commit=tested_commit,
        tested_tree=tested_tree,
        base_branch_hint=base_branch,
    )


def _candidate_reference_binding(
    api: GitHubApi,
    run_record: dict[str, Any] | None,
    run: RunIdentity,
    tested: TestedIdentity,
) -> tuple[str, bool]:
    """Choose current ``master`` or one exact pre-merge PR parent.

    A pull request may finish curation just before or just after GitHub delivers
    its already-tested two-parent merge to ``master``.  In that one state, the
    meaningful baseline remains parent zero of the delivered merge.  Every
    other source keeps the current-head baseline rule.
    """

    current_reference = api.branch_head(api.repository, DEFAULT_BRANCH)
    if run.event != "pull_request_target":
        return current_reference, False
    if run_record is None:
        _fail("pull_request_target reference binding requires its run record")
    pulls = run_record.get("pull_requests")
    if not isinstance(pulls, list) or len(pulls) != 1 or not isinstance(pulls[0], dict):
        _fail("pull_request_target run does not identify exactly one pull request")
    pull = api.pull_request(_positive(pulls[0].get("number"), "pull request number"))
    try:
        head = pull["head"]
        base = pull["base"]
        state = pull["state"]
        merged = pull.get("merged")
        delivered = _sha1(pull["merge_commit_sha"], "pull request merge SHA")
        if (
            head["repo"]["full_name"] != run.repository
            or head["sha"] != tested.source_head_commit
            or head["ref"] != tested.source_head_branch
            or base["repo"]["full_name"] != run.repository
            or base["ref"] != tested.base_branch_hint
        ):
            _fail("pull request identity changed while binding its reference")
        base_branch = base["ref"]
    except (KeyError, TypeError) as exc:
        raise CurationError("pull request reference record is malformed") from exc
    if state == "open":
        return current_reference, False
    if state != "closed" or merged is not True:
        _fail("pull request closed without delivering its tested tree")
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
        _fail("merged pull request cannot authenticate its historical baseline")
    return tested_parents[0], True


def _reference_tested_identity(
    api: GitHubApi, run: RunIdentity, reference_sha: str
) -> TestedIdentity:
    """Bind an authenticated reference run to immutable commit bytes.

    Current-vs-historical eligibility is proved separately by
    :func:`_candidate_reference_binding`; the run and anchor still have to bind
    to this exact commit, tree, branch, repository, event, and attempt.
    """

    reference_sha = _sha1(reference_sha, "reference commit")
    if (
        run.repository != api.repository
        or run.head_repository != api.repository
        or run.head_branch != DEFAULT_BRANCH
        or run.head_sha != reference_sha
        or run.event not in REFERENCE_EVENTS
    ):
        _fail("reference run does not own the authenticated baseline commit")
    tree, _parents = api.commit_identity(api.repository, reference_sha)
    return TestedIdentity(
        repository=api.repository,
        source_head_repository=api.repository,
        source_head_branch=DEFAULT_BRANCH,
        source_head_commit=reference_sha,
        tested_commit=reference_sha,
        tested_tree=tree,
        base_branch_hint=None,
    )


def _bind_matrix_branch(
    run: RunIdentity, tested: TestedIdentity, matrix_branch: str
) -> None:
    if tested.base_branch_hint is not None and tested.base_branch_hint != matrix_branch:
        _fail("pull request base branch disagrees with the tested release matrix")
    if run.event == "schedule" and run.head_branch != matrix_branch:
        _fail("scheduled baseline run is not on its matrix-owned branch")


def _bundle(
    *,
    api: GitHubApi,
    run: RunIdentity,
    tested: TestedIdentity,
    artifact: ArtifactIdentity,
    matrix_path: Path,
    contract_path: Path,
    workflow: bytes,
    work: Path,
) -> EvidenceBundle:
    work.mkdir(parents=True, exist_ok=False)
    matrix = load_matrix(matrix_path, validate_sources=False)
    contract = load_contract(contract_path)
    projection = _projection(run.event)
    nodes, scenarios = _projected_identity(matrix, contract, projection)
    graph = _job_graph(
        api.jobs(run.run_id), run=run, matrix_path=matrix_path, tested=tested
    )
    provenance, _expectation = _attestation(
        run=run,
        tested=tested,
        workflow=workflow,
        graph=graph,
        artifact=artifact,
        matrix_path=matrix_path,
        contract=contract,
        matrix_branch=matrix["branch"]["name"],
        nodes=nodes,
        scenarios=scenarios,
    )
    archive = work / f"artifact-{artifact.artifact_id}.zip"
    api.download_artifact(artifact.artifact_id, archive, artifact.size)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != artifact.digest:
        _fail("downloaded aggregate digest disagrees with authenticated artifact metadata")
    extracted = extract_authenticated_artifact(
        archive,
        work / f"artifact-{artifact.artifact_id}",
        expected_sha256=artifact.digest,
    )
    try:
        validate_aggregate(
            root=extracted,
            matrix_path=matrix_path,
            contract_path=contract_path,
            projection=projection,
            expected_repository=run.repository,
            expected_source_branch=tested.source_head_branch,
            expected_commit=tested.tested_commit,
            expected_tree=tested.tested_tree,
            expected_run_id=run.run_id,
            expected_run_attempt=run.run_attempt,
        )
    except FanInError as exc:
        raise CurationError(str(exc)) from exc
    try:
        frames = collect_evidence(
            extracted, matrix=matrix, contract=contract, provenance=provenance
        )
    except VisualEvidenceError as exc:
        raise CurationError(str(exc)) from exc
    return EvidenceBundle(
        provenance=provenance,
        matrix=matrix,
        matrix_sha256=matrix_sha256(matrix_path),
        contract=contract,
        frames=frames,
    )


def _anchor_bundle(
    *,
    api: GitHubApi,
    run: RunIdentity,
    tested: TestedIdentity,
    artifact: ArtifactIdentity,
    matrix_path: Path,
    contract_path: Path,
    workflow: bytes,
    work: Path,
) -> EvidenceBundle:
    """Import only the authenticated eligible lossless anchor as reference frames."""

    work.mkdir(parents=True, exist_ok=False)
    matrix = load_matrix(matrix_path, validate_sources=False)
    contract = load_contract(contract_path)
    projection = _projection(run.event)
    nodes, scenarios = _projected_identity(matrix, contract, projection)
    reference = canonical_reference_identity(matrix)
    if nodes != tuple(sorted(set(nodes))) or reference["artifact_node"] not in nodes:
        _fail("canonical anchor lane is absent from the protected matrix projection")
    graph = _job_graph(
        api.jobs(run.run_id), run=run, matrix_path=matrix_path, tested=tested
    )
    provenance, _expectation = _attestation(
        run=run,
        tested=tested,
        workflow=workflow,
        graph=graph,
        artifact=artifact,
        matrix_path=matrix_path,
        contract=contract,
        matrix_branch=matrix["branch"]["name"],
        nodes=(reference["artifact_node"],),
        scenarios=scenarios,
    )
    archive = work / f"anchor-{artifact.artifact_id}.zip"
    api.download_artifact(artifact.artifact_id, archive, artifact.size)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != artifact.digest:
        _fail("downloaded visual anchor digest disagrees with authenticated metadata")
    extracted = extract_authenticated_artifact(
        archive,
        work / f"anchor-{artifact.artifact_id}",
        expected_sha256=artifact.digest,
    )
    try:
        anchor = validate_anchor(
            extracted,
            matrix_path=matrix_path,
            expected={
                "repository": run.repository,
                "branch": run.head_branch,
                "commit": tested.tested_commit,
                "tree": tested.tested_tree,
                "matrix_sha256": matrix_sha256(matrix_path),
                "contract_sha256": contract.sha256,
                "handoff": {
                    "path": WORKFLOW_PATH,
                    "run_id": run.run_id,
                    "run_attempt": run.run_attempt,
                    "controller_branch": run.head_branch,
                    "controller_sha": run.head_sha,
                },
            },
        )
    except VisualAnchorError as exc:
        raise CurationError(str(exc)) from exc
    frames: list[VisualFrame] = []
    for record in anchor["frames"]:
        source = record["source"]
        image_path = extracted / PurePosixPath(source["path"])
        try:
            (
                width,
                height,
                source_file_sha256,
                pixel_sha256,
                canonical_file_sha256,
                canonical_png,
                _metrics,
            ) = canonicalize_png(
                image_path,
                expected_size=contract.gui_text_reference_size,
            )
        except VisualEvidenceError as exc:
            raise CurationError(str(exc)) from exc
        if (
            source_file_sha256 != source["sha256"]
            or pixel_sha256 != source["pixel_sha256"]
            or record["scenario"] not in scenarios
        ):
            _fail("lossless visual anchor frame identity is stale")
        frames.append(
            VisualFrame(
                artifact_node=record["artifact_node"],
                minecraft=record["minecraft"],
                loader=record["loader"],
                scenario=record["scenario"],
                role=record["role"],
                step=record["step"],
                capture_id=record["capture_id"],
                title=record["title"],
                expectation=record["expectation"],
                review_tier=record["review_tier"],
                width=width,
                height=height,
                source_file_sha256=source_file_sha256,
                pixel_sha256=pixel_sha256,
                canonical_file_sha256=canonical_file_sha256,
                canonical_png=canonical_png,
                source_artifact_id=artifact.artifact_id,
            )
        )
    expected_captures = {
        capture.capture_id
        for scenario in scenarios
        for role in contract.scenario(scenario).roles
        for step in role.steps
        if step.capture is not None
        for capture in (step.capture,)
    }
    if {frame.capture_id for frame in frames} != expected_captures:
        _fail("lossless visual anchor does not cover the projected semantic captures")
    frames.sort(key=lambda frame: frame.label)
    return EvidenceBundle(
        provenance=provenance,
        matrix=matrix,
        matrix_sha256=matrix_sha256(matrix_path),
        contract=contract,
        frames=tuple(frames),
    )


def curate(
    *,
    api: GitHubApi,
    source_run_id: int,
    source_run_attempt: int,
    source_sha: str,
    source_branch: str,
    implementation_sha: str,
    producer_run_id: int,
    producer_run_attempt: int,
    output: Path,
) -> dict[str, Any]:
    _positive(producer_run_id, "queue producer run id")
    _positive(producer_run_attempt, "queue producer run attempt")
    source_run_attempt = _positive(source_run_attempt, "source run attempt")
    source_run_record = api.run_attempt(source_run_id, source_run_attempt)
    source_run = authenticate_run(
        source_run_record, repository=api.repository, expected_id=source_run_id
    )
    if source_run.run_attempt != source_run_attempt:
        _fail("historical source run attempt disagrees with its authenticated locator")
    _sha1(source_sha, "authenticated source SHA")
    if not valid_branch_name(source_branch):
        _fail("authenticated source branch is unsafe")
    implementation_sha = _sha1(implementation_sha, "protected implementation SHA")
    repository_record = api.repository_record()
    if repository_record.get("full_name") != api.repository or repository_record.get("default_branch") != DEFAULT_BRANCH:
        _fail("repository default branch does not match the protected visual anchor")
    if (
        source_run.head_repository != api.repository
        or source_run.head_branch != DEFAULT_BRANCH
        or api.branch_head(api.repository, DEFAULT_BRANCH) != implementation_sha
    ):
        _fail("source run is not anchored to a protected default controller")
    comparison = api.compare(api.repository, source_run.head_sha, implementation_sha)
    try:
        source_base = comparison["base_commit"]["sha"]
        merge_base = comparison["merge_base_commit"]["sha"]
        comparison_status = comparison["status"]
    except (KeyError, TypeError) as exc:
        raise VisualReviewError("source controller comparison is malformed") from exc
    if (
        source_base != source_run.head_sha
        or merge_base != source_run.head_sha
        or comparison_status
        != ("identical" if source_run.head_sha == implementation_sha else "ahead")
    ):
        _fail("source controller is not an ancestor of the current protected head")

    output = output.absolute()
    if output.exists() or output.is_symlink():
        _fail("visual queue output must be fresh")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="blockpops-visual-curation-") as temporary:
        work = Path(temporary)
        source_artifacts = api.artifacts(source_run.run_id)
        aggregate_commit = aggregate_tested_commit(
            source_artifacts,
            run_id=source_run.run_id,
            run_attempt=source_run.run_attempt,
        )
        candidate_tested = _resolve_tested_identity(
            api,
            source_run_record,
            source_run,
            expected_tested=aggregate_commit,
            expected_source_branch=source_branch,
            expected_source_head=source_sha,
        )
        candidate_files = work / "candidate-files"
        candidate_files.mkdir()
        candidate_matrix_path, candidate_contract_path, candidate_workflow = _source_files(
            api,
            repository=candidate_tested.repository,
            tested_commit=candidate_tested.tested_commit,
            root=candidate_files,
        )
        candidate_matrix = load_matrix(candidate_matrix_path, validate_sources=False)
        _bind_matrix_branch(
            source_run, candidate_tested, candidate_matrix["branch"]["name"]
        )
        candidate_artifact = exact_aggregate_artifact(
            source_artifacts,
            run_id=source_run.run_id,
            tested_commit=candidate_tested.tested_commit,
            run_attempt=source_run.run_attempt,
        )
        if candidate_artifact is None:
            _fail("source run has no aggregate for its exact tested commit and attempt")

        anchor = canonical_reference_identity(candidate_matrix)
        if anchor["release_branch"] != DEFAULT_BRANCH:
            _fail("candidate matrix does not use the protected default visual branch")
        reference_sha, historical_reference = _candidate_reference_binding(
            api, source_run_record, source_run, candidate_tested
        )
        reference_files = work / "reference-files"
        reference_files.mkdir()
        reference_matrix_path, reference_contract_path, reference_workflow = _source_files(
            api,
            repository=api.repository,
            tested_commit=reference_sha,
            root=reference_files,
        )
        reference_matrix = load_matrix(reference_matrix_path, validate_sources=False)
        if canonical_reference_identity(reference_matrix) != anchor:
            _fail("authenticated reference disagrees on the canonical visual anchor")

        reference_run, reference_artifact = select_reference_run(
            api,
            api.workflow_runs(DEFAULT_BRANCH, reference_sha),
            repository=api.repository,
            branch=DEFAULT_BRANCH,
            head_sha=reference_sha,
        )
        reference_tested = _reference_tested_identity(
            api, reference_run, reference_sha
        )
        _bind_matrix_branch(
            reference_run, reference_tested, reference_matrix["branch"]["name"]
        )
        if reference_tested.tested_commit != reference_sha:
            _fail("selected baseline did not execute the exact reference commit")

        candidate_bundle = _bundle(
            api=api,
            run=source_run,
            tested=candidate_tested,
            artifact=candidate_artifact,
            matrix_path=candidate_matrix_path,
            contract_path=candidate_contract_path,
            workflow=candidate_workflow,
            work=work / "candidate",
        )
        reference_bundle = _anchor_bundle(
            api=api,
            run=reference_run,
            tested=reference_tested,
            artifact=reference_artifact,
            matrix_path=reference_matrix_path,
            contract_path=reference_contract_path,
            workflow=reference_workflow,
            work=work / "reference",
        )
        if _resolve_tested_identity(
            api,
            source_run_record,
            source_run,
            expected_tested=aggregate_commit,
            expected_source_branch=source_branch,
            expected_source_head=source_sha,
        ) != candidate_tested:
            _fail("candidate source/base/tested merge identity changed during curation")
        if _candidate_reference_binding(
            api, source_run_record, source_run, candidate_tested
        ) != (reference_sha, historical_reference):
            _fail("candidate/reference binding changed during curation")
        newest_reference = select_reference_run(
            api,
            api.workflow_runs(DEFAULT_BRANCH, reference_sha),
            repository=api.repository,
            branch=DEFAULT_BRANCH,
            head_sha=reference_sha,
        )
        if (
            newest_reference != (reference_run, reference_artifact)
            or _reference_tested_identity(api, reference_run, reference_sha)
            != reference_tested
        ):
            _fail("authenticated reference run or anchor changed during curation")
        capsule = work / "visual-capsule"
        capsule_digest = write_capsule(capsule, candidate_bundle, reference_bundle)
        queue = build_queue(
            output,
            capsule=capsule,
            implementation_sha=implementation_sha,
            producer_run_id=producer_run_id,
            producer_run_attempt=producer_run_attempt,
        )
        result = {
            "schema_version": 1,
            "advisory": True,
            "implementation_sha": implementation_sha,
            "producer_run_id": producer_run_id,
            "producer_run_attempt": producer_run_attempt,
            "source_run_id": source_run.run_id,
            "source_run_attempt": source_run.run_attempt,
            "source_head_sha": candidate_tested.source_head_commit,
            "tested_sha": candidate_tested.tested_commit,
            "candidate_artifact_id": candidate_artifact.artifact_id,
            "candidate_artifact_sha256": candidate_artifact.digest,
            "reference_run_id": reference_run.run_id,
            "reference_run_attempt": reference_run.run_attempt,
            "reference_sha": reference_sha,
            "reference_artifact_id": reference_artifact.artifact_id,
            "reference_artifact_sha256": reference_artifact.digest,
            "capsule_manifest_sha256": capsule_digest,
            "queue_manifest_sha256": queue["manifest_sha256"],
            "pair_count": len(candidate_bundle.frames),
        }
        return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--source-run-attempt", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--source-branch", required=True)
    parser.add_argument("--implementation-sha", required=True)
    parser.add_argument("--producer-run-id", type=int, required=True)
    parser.add_argument("--producer-run-attempt", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--result", type=Path)
    args = parser.parse_args(argv)
    try:
        api = GitHubApi(
            repository=args.repository,
            token=os.environ.get("GITHUB_TOKEN", ""),
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        result = curate(
            api=api,
            source_run_id=args.source_run_id,
            source_run_attempt=args.source_run_attempt,
            source_sha=args.source_sha,
            source_branch=args.source_branch,
            implementation_sha=args.implementation_sha,
            producer_run_id=args.producer_run_id,
            producer_run_attempt=args.producer_run_attempt,
            output=args.output,
        )
        encoded = canonical_json(result) + b"\n"
        if args.result is not None:
            _write_exact(args.result, encoded)
        print(encoded.decode("utf-8"), end="")
    except (
        CurationError,
        FanInError,
        HandoffError,
        JobGraphError,
        MatrixError,
        OSError,
        ScenarioContractError,
        VisualEvidenceError,
    ) as exc:
        print(f"visual curation error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
