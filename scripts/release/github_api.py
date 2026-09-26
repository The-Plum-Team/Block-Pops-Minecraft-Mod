"""Read-only GitHub Actions API client shared by the release tooling.

``GitHubApi``, ``Artifact``, ``SelectionError``, ``_validate_run`` and ``_run_order`` moved here
verbatim from the retired ``scripts/pages/select_artifact.py`` when public evidence was delegated
to mod-base; Pages selection itself now belongs to the pinned kit. Release planning keeps using
this client to read exact run, job and artifact metadata; it never downloads, qualifies or
publishes anything by itself.
"""

from __future__ import annotations

import base64
import hashlib
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from scripts.lib.secure_json import SecureJsonError, loads as secure_loads

MAX_API_BYTES = 32 * 1024 * 1024
REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA1_PATTERN = re.compile(r"^[0-9a-f]{40}$")
ARTIFACT_DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
MAX_WORKFLOW_RUNS = 1000


class SelectionError(RuntimeError):
    """Raised when no artifact can prove the exact current head."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep the GitHub bearer credential pinned to the configured API origin."""

    def redirect_request(
        self, request: Any, fp: Any, code: int, message: str,
        headers: Any, new_url: str,
    ) -> None:
        return None


@dataclass(frozen=True)
class Artifact:
    id: int
    name: str
    size: int
    expired: bool
    created_at: str
    digest: str
    run_id: int
    head_branch: str
    head_sha: str

    @property
    def order(self) -> tuple[datetime, int]:
        return datetime.fromisoformat(self.created_at.replace("Z", "+00:00")), self.id

    @classmethod
    def parse(cls, raw: Any) -> "Artifact":
        if not isinstance(raw, dict) or not isinstance(raw.get("workflow_run"), dict):
            raise SelectionError("artifact API record is malformed")
        workflow = raw["workflow_run"]
        digest = raw.get("digest")
        if not isinstance(digest, str) or ARTIFACT_DIGEST_PATTERN.fullmatch(digest) is None:
            raise SelectionError("artifact API record has no immutable SHA-256 digest")
        try:
            created = datetime.fromisoformat(str(raw["created_at"]).replace("Z", "+00:00"))
        except (KeyError, ValueError) as exc:
            raise SelectionError("artifact API timestamp is invalid") from exc
        if created.tzinfo is None:
            raise SelectionError("artifact API timestamp has no timezone")
        values = (raw.get("id"), raw.get("size_in_bytes"), workflow.get("id"))
        if any(isinstance(value, bool) or not isinstance(value, int) or value <= 0 for value in values):
            raise SelectionError("artifact API numeric identity is invalid")
        if (
            not isinstance(raw.get("name"), str)
            or not raw["name"]
            or len(raw["name"].encode("utf-8")) > 240
            or not isinstance(raw.get("expired"), bool)
            or not isinstance(workflow.get("head_branch"), str)
            or not workflow["head_branch"]
            or SHA1_PATTERN.fullmatch(str(workflow.get("head_sha", ""))) is None
        ):
            raise SelectionError("artifact API textual/controller identity is invalid")
        return cls(
            id=raw["id"], name=str(raw.get("name", "")), size=raw["size_in_bytes"],
            expired=raw.get("expired") is True, created_at=raw["created_at"], digest=digest,
            run_id=workflow["id"], head_branch=str(workflow.get("head_branch", "")),
            head_sha=str(workflow.get("head_sha", "")),
        )


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        if REPOSITORY_PATTERN.fullmatch(repository) is None:
            raise SelectionError("repository must use owner/name form")
        if not isinstance(token, str) or not token or len(token) > 4096:
            raise SelectionError("GitHub token is absent or invalid")
        parsed = urllib.parse.urlsplit(api_url)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise SelectionError("GitHub API URL must be an HTTPS origin")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )

    def get(self, path: str) -> Any:
        if not isinstance(path, str) or not path.startswith("/") or "\x00" in path:
            raise SelectionError("GitHub API path is unsafe")
        url = self.api_url + path
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-pages-evidence",
            },
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise SelectionError(
                        f"GitHub API request returned HTTP {response.status} for {path}"
                    )
                data = response.read(MAX_API_BYTES + 1)
        except (OSError, urllib.error.HTTPError) as exc:
            raise SelectionError(f"GitHub API request failed for {path}: {exc}") from exc
        if len(data) > MAX_API_BYTES:
            raise SelectionError("GitHub API response exceeds its byte bound")
        try:
            return secure_loads(data, label=f"GitHub API {path}", max_bytes=MAX_API_BYTES)
        except SecureJsonError as exc:
            raise SelectionError(str(exc)) from exc

    def workflow(self, filename: str) -> dict[str, Any]:
        value = self.get(f"/repos/{self.repository}/actions/workflows/{urllib.parse.quote(filename, safe='')}")
        if (
            not isinstance(value, dict)
            or isinstance(value.get("id"), bool)
            or not isinstance(value.get("id"), int)
            or value["id"] <= 0
        ):
            raise SelectionError(f"workflow {filename} is unavailable")
        return value

    def branch_head(self, branch: str) -> tuple[str, str]:
        value = self.get(f"/repos/{self.repository}/branches/{urllib.parse.quote(branch, safe='')}")
        try:
            commit = value["commit"]["sha"]
        except (KeyError, TypeError) as exc:
            raise SelectionError("branch API response has no commit") from exc
        git_commit = self.get(f"/repos/{self.repository}/git/commits/{commit}")
        try:
            tree = git_commit["tree"]["sha"]
        except (KeyError, TypeError) as exc:
            raise SelectionError("commit API response has no tree") from exc
        return commit, tree

    def commit_tree(self, commit: str) -> str:
        value = self.get(f"/repos/{self.repository}/git/commits/{commit}")
        try:
            tree = value["tree"]["sha"]
        except (KeyError, TypeError) as exc:
            raise SelectionError("commit API response has no tree") from exc
        if SHA1_PATTERN.fullmatch(str(tree)) is None:
            raise SelectionError("commit API response has an invalid tree")
        return tree

    def runs(self, workflow_id: int, branch: str) -> list[dict[str, Any]]:
        runs: list[dict[str, Any]] = []
        for page in range(1, 11):
            query = urllib.parse.urlencode(
                {"branch": branch, "per_page": 100, "page": page}
            )
            value = self.get(
                f"/repos/{self.repository}/actions/workflows/{workflow_id}/runs?{query}"
            )
            rows = value.get("workflow_runs") if isinstance(value, dict) else None
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise SelectionError("workflow-runs response is malformed")
            runs.extend(rows)
            if len(rows) < 100:
                return runs
        if len(runs) >= MAX_WORKFLOW_RUNS:
            raise SelectionError("workflow-run pagination exceeds 1,000 records")
        return runs

    def run(self, run_id: int) -> dict[str, Any]:
        value = self.get(f"/repos/{self.repository}/actions/runs/{run_id}")
        if not isinstance(value, dict):
            raise SelectionError("workflow-run response is malformed")
        return value

    def run_attempt(self, run_id: int, run_attempt: int) -> dict[str, Any]:
        value = self.get(
            f"/repos/{self.repository}/actions/runs/{run_id}/attempts/{run_attempt}"
        )
        if not isinstance(value, dict):
            raise SelectionError("workflow run-attempt response is malformed")
        return value

    def artifacts_for_run(self, run_id: int) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 11):
            value = self.get(
                f"/repos/{self.repository}/actions/runs/{run_id}/artifacts"
                f"?per_page=100&page={page}"
            )
            rows = value.get("artifacts") if isinstance(value, dict) else None
            if not isinstance(rows, list):
                raise SelectionError("run-artifacts response is malformed")
            artifacts.extend(Artifact.parse(row) for row in rows)
            if len(rows) < 100:
                return artifacts
        raise SelectionError("run artifact pagination exceeds 1,000 records")

    def jobs_for_attempt(self, run_id: int, run_attempt: int) -> list[dict[str, Any]]:
        jobs: list[dict[str, Any]] = []
        for page in range(1, 11):
            value = self.get(
                f"/repos/{self.repository}/actions/runs/{run_id}/attempts/"
                f"{run_attempt}/jobs?per_page=100&page={page}"
            )
            rows = value.get("jobs") if isinstance(value, dict) else None
            if not isinstance(rows, list):
                raise SelectionError("run-jobs response is malformed")
            if any(not isinstance(row, dict) for row in rows):
                raise SelectionError("run-jobs response contains a malformed job")
            jobs.extend(rows)
            if len(rows) < 100:
                if not jobs:
                    raise SelectionError("run has no jobs for its exact attempt")
                return jobs
        raise SelectionError("run job pagination exceeds 1,000 records")

    def artifacts_named(self, name: str) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 11):
            query = urllib.parse.urlencode(
                {"name": name, "per_page": 100, "page": page}
            )
            value = self.get(f"/repos/{self.repository}/actions/artifacts?{query}")
            rows = value.get("artifacts") if isinstance(value, dict) else None
            if not isinstance(rows, list):
                raise SelectionError("repository-artifacts response is malformed")
            artifacts.extend(Artifact.parse(row) for row in rows)
            if len(rows) < 100:
                return artifacts
        raise SelectionError("named artifact pagination exceeds 1,000 records")

    def all_artifacts(self) -> list[Artifact]:
        artifacts: list[Artifact] = []
        for page in range(1, 101):
            value = self.get(
                f"/repos/{self.repository}/actions/artifacts?per_page=100&page={page}"
            )
            rows = value.get("artifacts") if isinstance(value, dict) else None
            if not isinstance(rows, list):
                raise SelectionError("repository-artifacts response is malformed")
            artifacts.extend(Artifact.parse(row) for row in rows)
            if len(rows) < 100:
                return artifacts
        raise SelectionError("repository artifact pagination exceeds 10,000 records")

    def blob(self, oid: str) -> bytes:
        value = self.get(f"/repos/{self.repository}/git/blobs/{oid}")
        if not isinstance(value, dict) or value.get("encoding") != "base64" or not isinstance(value.get("content"), str):
            raise SelectionError("matrix blob response is malformed")
        encoded = value["content"]
        if any(character.isspace() and character not in "\r\n" for character in encoded):
            raise SelectionError("matrix blob base64 contains unexpected whitespace")
        try:
            data = base64.b64decode("".join(encoded.splitlines()), validate=True)
        except ValueError as exc:
            raise SelectionError("matrix blob is not canonical base64") from exc
        if value.get("size") != len(data) or len(data) > 256 * 1024:
            raise SelectionError("matrix blob size is invalid")
        git_oid = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if git_oid != oid or value.get("sha") != oid:
            raise SelectionError("matrix blob object identity is stale")
        return data


def _validate_run(
    run: dict[str, Any], *, workflow_id: int, workflow_path: str, repository: str,
    branch: str, sha: str, events: frozenset[str], require_success: bool,
    display_title: str | None = None,
) -> None:
    if SHA1_PATTERN.fullmatch(sha) is None:
        raise SelectionError("workflow run controller SHA is invalid")
    head_repository = run.get("head_repository")
    if (
        run.get("workflow_id") != workflow_id
        or run.get("path") != workflow_path
        or run.get("head_branch") != branch
        or run.get("head_sha") != sha
        or run.get("event") not in events
        or not isinstance(head_repository, dict)
        or head_repository.get("full_name") != repository
        or (display_title is not None and run.get("display_title") != display_title)
    ):
        raise SelectionError("workflow run provenance is not exact")
    if require_success and (run.get("status") != "completed" or run.get("conclusion") != "success"):
        raise SelectionError("workflow run is not completed/success")


def _run_order(run: dict[str, Any]) -> tuple[datetime, int, int]:
    """Return one immutable Actions dispatch order after strict shape validation."""

    run_id = run.get("id")
    attempt = run.get("run_attempt")
    created_at = run.get("created_at")
    if (
        isinstance(run_id, bool)
        or not isinstance(run_id, int)
        or run_id <= 0
        or isinstance(attempt, bool)
        or not isinstance(attempt, int)
        or attempt <= 0
        or not isinstance(created_at, str)
        or not created_at.endswith("Z")
    ):
        raise SelectionError("matching workflow run has an invalid id/attempt/timestamp")
    try:
        created = datetime.fromisoformat(created_at[:-1] + "+00:00")
    except ValueError as exc:
        raise SelectionError("matching workflow run timestamp is invalid") from exc
    return created, run_id, attempt
