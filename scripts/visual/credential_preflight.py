#!/usr/bin/env python3
"""Stdlib-only final identity check for the fresh Claude credential runner."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import ssl
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^(?:sha256:)?([0-9a-f]{64})$")
SAFE_BRANCH = re.compile(r"^(?!/)(?!.*(?:\.\.|//))[A-Za-z0-9._/-]{1,200}(?<!/)$")
MAX_JSON = 16 * 1024 * 1024
MAX_HANDOFF_BYTES = 96 * 1024 * 1024
MAX_CAPSULE_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_REFERENCE_ARTIFACT_BYTES = 512 * 1024 * 1024
WORKFLOW = ".github/workflows/visual-review-drain.yml"
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
SOURCE_EVENTS = frozenset({"pull_request_target", "workflow_dispatch"})
MASTER_BRANCH_TOKEN = hashlib.sha256(b"master").hexdigest()[:24]
HANDOFF_KEYS = frozenset(
    {
        "schema_version",
        "purpose",
        "reviewer_implementation_sha",
        "queue_manifest_sha256",
        "client_sha256",
        "preflight_sha256",
        "sonnet_prompt_sha256",
        "fable_prompt_sha256",
        "inventory",
        "total_bytes",
    }
)


class PreflightError(RuntimeError):
    pass


class StaleError(PreflightError):
    pass


class TransientError(PreflightError):
    pass


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PreflightError("duplicate JSON key")
        result[key] = value
    return result


def _loads(payload: bytes, label: str) -> Any:
    if not 1 <= len(payload) <= MAX_JSON:
        raise PreflightError(f"{label} size is invalid")
    try:
        return json.loads(
            payload,
            object_pairs_hook=_strict_object,
            parse_constant=lambda value: (_ for _ in ()).throw(
                PreflightError(f"{label} contains {value}")
            ),
        )
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise PreflightError(f"{label} is not strict JSON") from exc


def _read(path: Path, maximum: int, label: str) -> bytes:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise PreflightError(f"{label} is not a regular file")
    if not 1 <= metadata.st_size <= maximum:
        raise PreflightError(f"{label} size is invalid")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        payload = b""
        while len(payload) <= maximum:
            chunk = os.read(descriptor, min(1024 * 1024, maximum + 1 - len(payload)))
            if not chunk:
                break
            payload += chunk
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    if (
        not stat.S_ISREG(opened.st_mode)
        or len(payload) != opened.st_size
        or (opened.st_dev, opened.st_ino, opened.st_size)
        != (after.st_dev, after.st_ino, after.st_size)
        or (opened.st_dev, opened.st_ino, opened.st_size)
        != (metadata.st_dev, metadata.st_ino, metadata.st_size)
    ):
        raise PreflightError(f"{label} changed while reading")
    return payload


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        raise PreflightError(f"{label} is not a commit SHA")
    return value


def _positive(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise PreflightError(f"{label} is not positive")
    return value


def _digest(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise PreflightError(f"{label} is not a SHA-256 digest")
    match = SHA256.fullmatch(value)
    if match is None:
        raise PreflightError(f"{label} is not a SHA-256 digest")
    return match.group(1)


class Api:
    def __init__(self, repository: str, token: str) -> None:
        if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
            raise PreflightError("repository identity is invalid")
        if not token or any(ord(character) < 33 for character in token):
            raise PreflightError("GitHub API token is missing")
        self.repository = repository
        self.token = token
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
            _NoRedirect(),
        )

    def get(self, route: str) -> Any:
        route_path = route.split("?", 1)[0]
        if not route.startswith("/") or any(part == ".." for part in route_path.split("/")):
            raise PreflightError("GitHub API route is unsafe")
        request = urllib.request.Request(
            f"https://api.github.com{route}",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-credential-preflight/1",
            },
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise PreflightError("GitHub API returned a non-success status")
                payload = response.read(MAX_JSON + 1)
        except urllib.error.HTTPError as exc:
            if exc.code == 429 or exc.code in {408, 425} or 500 <= exc.code <= 599:
                raise TransientError("GitHub API is temporarily unavailable") from exc
            raise PreflightError("GitHub API request failed") from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError("GitHub API is temporarily unavailable") from exc
        return _loads(payload, "GitHub API response")

    def repo(self, suffix: str) -> Any:
        encoded = "/".join(urllib.parse.quote(part, safe="") for part in self.repository.split("/"))
        return self.get(f"/repos/{encoded}{suffix}")

    def branch(self, name: str) -> str:
        value = self.repo(f"/branches/{urllib.parse.quote(name, safe='')}")
        try:
            return _sha(value["commit"]["sha"], "branch head")
        except (KeyError, TypeError) as exc:
            raise PreflightError("branch response is malformed") from exc

    def commit(self, commit: str) -> tuple[str, tuple[str, ...]]:
        value = self.repo(f"/git/commits/{_sha(commit, 'commit')}")
        try:
            tree = _sha(value["tree"]["sha"], "commit tree")
            parents = tuple(_sha(parent["sha"], "commit parent") for parent in value["parents"])
        except (KeyError, TypeError) as exc:
            raise PreflightError("commit response is malformed") from exc
        return tree, parents

    def compare(self, base: str, head: str) -> dict[str, Any]:
        value = self.repo(
            "/compare/"
            + urllib.parse.quote(_sha(base, "compare base"), safe="")
            + "..."
            + urllib.parse.quote(_sha(head, "compare head"), safe="")
        )
        if not isinstance(value, dict):
            raise PreflightError("commit comparison is malformed")
        return value

    def pull_requests_for_commit(self, commit: str) -> list[dict[str, Any]]:
        value = self.repo(
            f"/commits/{_sha(commit, 'associated commit')}/pulls?per_page=100"
        )
        if (
            not isinstance(value, list)
            or len(value) >= 100
            or any(not isinstance(item, dict) for item in value)
        ):
            raise PreflightError("commit pull-request association is malformed or excessive")
        return value


def _source_identity(
    api: Api, queue: dict[str, Any], candidate: dict[str, Any]
) -> tuple[str, str] | None:
    run_id = _positive(queue.get("source_run_id"), "source run id")
    attempt = _positive(queue.get("source_run_attempt"), "source run attempt")
    source_head = _sha(queue.get("source_head_sha"), "source head")
    tested = _sha(queue.get("tested_sha"), "tested commit")
    tested_tree = _sha(queue.get("tested_tree"), "tested tree")
    run = api.repo(f"/actions/runs/{run_id}/attempts/{attempt}")
    current_run = api.repo(f"/actions/runs/{run_id}")
    queue_implementation = _sha(queue.get("implementation_sha"), "queue implementation")
    if not isinstance(run, dict) or not isinstance(current_run, dict):
        raise PreflightError("source run response is malformed")
    run_repository = run.get("repository")
    run_head_repository = run.get("head_repository")
    current_repository = current_run.get("repository")
    current_head_repository = current_run.get("head_repository")
    if (
        not isinstance(run_repository, dict)
        or not isinstance(run_head_repository, dict)
        or not isinstance(current_repository, dict)
        or not isinstance(current_head_repository, dict)
    ):
        raise PreflightError("source run response is malformed")
    try:
        if (
            run["id"] != run_id
            or run["run_attempt"] != attempt
            or run["path"] != SOURCE_WORKFLOW
            or run_repository.get("full_name") != api.repository
            or run_head_repository.get("full_name") != api.repository
            or run["head_branch"] != "master"
            or SHA1.fullmatch(run["head_sha"]) is None
            or run["status"] != "completed"
            or run["conclusion"] != "success"
        ):
            raise StaleError("source run identity changed")
        if (
            current_run["id"] != run_id
            or current_run["run_attempt"] != attempt
            or any(
                current_run.get(field) != run.get(field)
                for field in (
                    "path",
                    "event",
                    "head_branch",
                    "head_sha",
                    "status",
                    "conclusion",
                )
            )
            or current_repository.get("full_name") != api.repository
            or current_head_repository.get("full_name") != api.repository
        ):
            raise StaleError("source workflow was re-run or changed after curation")
        event = run["event"]
        controller_head = _sha(run["head_sha"], "source controller head")
    except (KeyError, TypeError) as exc:
        raise PreflightError("source run response is malformed") from exc
    if controller_head != queue_implementation:
        comparison = api.compare(controller_head, queue_implementation)
        try:
            if (
                comparison["base_commit"]["sha"] != controller_head
                or comparison["merge_base_commit"]["sha"] != controller_head
                or comparison["status"] != "ahead"
            ):
                raise StaleError(
                    "source controller is not an ancestor of the queue implementation"
                )
        except (KeyError, TypeError) as exc:
            raise PreflightError("source controller comparison is malformed") from exc
    if event not in SOURCE_EVENTS or not isinstance(candidate, dict):
        raise StaleError("source event or capsule identity is no longer eligible")
    try:
        if (
            candidate["event"] != event
            or candidate["run_id"] != run_id
            or candidate["run_attempt"] != attempt
            or candidate["repository"] != api.repository
            or candidate["source_head_repository"] != api.repository
            or candidate["source_head_commit"] != source_head
            or candidate["tested_commit"] != tested
            or candidate["tested_tree"] != tested_tree
        ):
            raise StaleError("capsule source disagrees with its queue or run")
        source_branch = candidate["source_head_branch"]
        base_branch = candidate["base_branch"]
    except (KeyError, TypeError) as exc:
        raise PreflightError("capsule source response is malformed") from exc
    if (
        not isinstance(source_branch, str)
        or SAFE_BRANCH.fullmatch(source_branch) is None
        or not isinstance(base_branch, str)
        or SAFE_BRANCH.fullmatch(base_branch) is None
    ):
        raise PreflightError("capsule source branch identity is unsafe")
    if event == "pull_request_target":
        pulls = run.get("pull_requests")
        if not isinstance(pulls, list) or len(pulls) != 1:
            raise StaleError("source run no longer identifies one pull request")
        pull = api.repo(f"/pulls/{_positive(pulls[0].get('number'), 'pull number')}")
        try:
            if (
                pull["head"]["sha"] != source_head
                or pull["head"]["ref"] != source_branch
                or pull["head"]["repo"]["full_name"] != api.repository
                or pull["base"]["repo"]["full_name"] != api.repository
                or pull["base"]["ref"] != base_branch
            ):
                raise StaleError("pull request head/base identity changed")
            base_sha = _sha(pull["base"]["sha"], "pull base")
            delivered = _sha(pull["merge_commit_sha"], "pull merge")
            state = pull["state"]
            merged = pull.get("merged")
        except (KeyError, TypeError) as exc:
            raise PreflightError("pull response is malformed") from exc
        tree, parents = api.commit(tested)
        if tree != tested_tree:
            raise StaleError("tested tree changed")
        if state == "open":
            if (
                delivered != tested
                or api.branch(base_branch) != base_sha
                or parents != (base_sha, source_head)
            ):
                raise StaleError("open pull request tested identity changed")
            return None
        elif state == "closed" and merged is True:
            delivered_tree, delivered_parents = api.commit(delivered)
            if (
                api.branch(base_branch) != delivered
                or delivered_tree != tested_tree
                or delivered_parents != parents
                or len(parents) != 2
                or parents[1] != source_head
            ):
                raise StaleError("merged pull request no longer delivers the tested tree")
            return (parents[0], delivered) if base_branch == "master" else None
        else:
            raise StaleError("pull request closed without delivering the tested tree")
    if tested == controller_head:
        if source_head != controller_head or source_branch != "master":
            raise StaleError("default dispatch source identity changed")
        if api.branch(source_branch) != source_head:
            raise StaleError("branch source is no longer current")
        tree, _parents = api.commit(tested)
        if tree != tested_tree:
            raise StaleError("branch tested tree changed")
        return None
    if source_head != tested or not source_branch.startswith("automation/release-sync/"):
        raise StaleError("release synchronization source identity changed")
    matches: list[dict[str, Any]] = []
    for summary in api.pull_requests_for_commit(tested):
        try:
            if (
                summary["head"]["sha"] == tested
                and summary["head"]["ref"] == source_branch
                and summary["head"]["repo"]["full_name"] == api.repository
                and summary["base"]["repo"]["full_name"] == api.repository
            ):
                matches.append(
                    api.repo(f"/pulls/{_positive(summary['number'], 'pull number')}")
                )
        except (KeyError, TypeError) as exc:
            raise PreflightError("commit pull-request association is malformed") from exc
    if len(matches) != 1:
        raise StaleError("release synchronization no longer identifies one pull request")
    pull = matches[0]
    try:
        if (
            pull["head"]["sha"] != tested
            or pull["head"]["ref"] != source_branch
            or pull["head"]["repo"]["full_name"] != api.repository
            or pull["base"]["repo"]["full_name"] != api.repository
            or pull["base"]["ref"] != base_branch
        ):
            raise StaleError("release synchronization pull request changed")
        base_sha = _sha(pull["base"]["sha"], "pull base")
        delivered = _sha(pull["merge_commit_sha"], "pull merge")
        state = pull["state"]
        merged = pull.get("merged")
    except (KeyError, TypeError) as exc:
        raise PreflightError("release synchronization pull response is malformed") from exc
    tree, parents = api.commit(tested)
    if tree != tested_tree:
        raise StaleError("release synchronization tested tree changed")
    if state == "open":
        if (
            api.branch(source_branch) != tested
            or api.branch(base_branch) != base_sha
            or len(parents) != 2
            or parents[0] != base_sha
        ):
            raise StaleError("open release synchronization topology changed")
    elif state == "closed" and merged is True:
        delivered_tree, delivered_parents = api.commit(delivered)
        if (
            api.branch(base_branch) != delivered
            or delivered_tree != tested_tree
            or len(delivered_parents) != 2
            or delivered_parents[1] != tested
        ):
            raise StaleError("merged release synchronization no longer delivers tested tree")
    else:
        raise StaleError("release synchronization closed without delivery")
    return None


def _is_attestation(api: Api, run: dict[str, Any]) -> bool:
    if run.get("event") != "workflow_dispatch":
        return False
    run_id = _positive(run.get("id"), "reference run id")
    attempt = _positive(run.get("run_attempt"), "reference run attempt")
    jobs = api.repo(f"/actions/runs/{run_id}/jobs?filter=all&per_page=100")
    values = jobs.get("jobs") if isinstance(jobs, dict) else None
    if not isinstance(values, list) or len(values) > 100:
        raise PreflightError("reference job graph is malformed")
    names = [
        job.get("name")
        for job in values
        if isinstance(job, dict) and job.get("run_attempt") == attempt
    ]
    return sum(isinstance(name, str) and name.endswith(" / Verify exact tested tree") for name in names) == 1


def _reference_identity(
    api: Api,
    queue: dict[str, Any],
    source: dict[str, Any],
    *,
    historical_binding: tuple[str, str] | None,
) -> None:
    reference = _sha(queue.get("reference_sha"), "reference commit")
    run_id = _positive(queue.get("reference_run_id"), "reference run id")
    attempt = _positive(queue.get("reference_run_attempt"), "reference run attempt")
    current_reference = api.branch("master")
    if historical_binding is None:
        if current_reference != reference:
            raise StaleError("canonical branch advanced")
    else:
        if not isinstance(historical_binding, tuple) or len(historical_binding) != 2:
            raise PreflightError("historical reference binding is malformed")
        historical_reference = _sha(
            historical_binding[0], "historical reference commit"
        )
        delivered = _sha(historical_binding[1], "delivered merge commit")
        if current_reference != delivered:
            raise StaleError("delivered merge is no longer the current canonical head")
        if reference != historical_reference:
            raise StaleError("historical reference is not the delivered merge parent")
    if not isinstance(source, dict):
        raise PreflightError("reference capsule source is malformed")
    try:
        artifact_id = _positive(source.get("artifact_id"), "reference artifact id")
        artifact_name = source["artifact_name"]
        artifact_digest = _digest(
            source.get("artifact_sha256"), "reference artifact digest"
        )
        if (
            source["repository"] != api.repository
            or source["source_head_repository"] != api.repository
            or source["source_head_branch"] != "master"
            or source["source_head_commit"] != reference
            or source["base_branch"] != "master"
            or source["tested_commit"] != reference
            or source["workflow_path"] != SOURCE_WORKFLOW
            or source["event"] not in {"schedule", "workflow_dispatch"}
            or source["run_id"] != run_id
            or source["run_attempt"] != attempt
            or artifact_name
            != (
                f"visual-anchor-v1-{MASTER_BRANCH_TOKEN}--"
                f"{reference}-{run_id}-{attempt}"
            )
        ):
            raise StaleError("reference capsule identity changed")
        source_tree = _sha(source["tested_tree"], "reference tree")
    except (KeyError, TypeError) as exc:
        raise PreflightError("reference capsule source is malformed") from exc
    reference_tree, _parents = api.commit(reference)
    if reference_tree != source_tree:
        raise StaleError("reference commit tree changed")
    workflow = urllib.parse.quote(SOURCE_WORKFLOW.rsplit("/", 1)[1], safe="")
    values = api.repo(
        f"/actions/workflows/{workflow}/runs?branch=master&head_sha={reference}&per_page=100"
    )
    runs = values.get("workflow_runs") if isinstance(values, dict) else None
    if not isinstance(runs, list) or len(runs) > 100 or values.get("total_count", 0) > 100:
        raise PreflightError("reference run inventory is malformed or excessive")
    candidates = []
    for run in runs:
        if not isinstance(run, dict):
            raise PreflightError("reference run inventory contains a non-object")
        try:
            if (
                run["path"] != SOURCE_WORKFLOW
                or run["repository"]["full_name"] != api.repository
                or run["head_repository"]["full_name"] != api.repository
                or run["head_branch"] != "master"
                or run["head_sha"] != reference
                or run["display_title"] != f"Packaged E2E / {reference}"
                or run["event"] not in {"schedule", "workflow_dispatch"}
            ):
                continue
            candidates.append(run)
        except (KeyError, TypeError) as exc:
            raise PreflightError("reference run record is malformed") from exc
    candidates.sort(key=lambda item: (item.get("created_at", ""), item.get("id", 0)), reverse=True)
    for run in candidates:
        if _is_attestation(api, run):
            continue
        if (
            run.get("id") != run_id
            or run.get("run_attempt") != attempt
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"
        ):
            raise StaleError("newest canonical full run changed")
        artifact = api.repo(f"/actions/artifacts/{artifact_id}")
        try:
            owner = artifact["workflow_run"]
            size = _positive(
                artifact.get("size_in_bytes"), "reference artifact size"
            )
            if (
                artifact["id"] != artifact_id
                or artifact["name"] != artifact_name
                or artifact["expired"] is not False
                or size > MAX_REFERENCE_ARTIFACT_BYTES
                or _digest(artifact.get("digest"), "reference artifact API digest")
                != artifact_digest
                or owner["id"] != run_id
                or owner["head_branch"] != "master"
                or owner["head_sha"] != reference
            ):
                raise StaleError("reference anchor artifact identity changed")
        except (KeyError, TypeError) as exc:
            raise PreflightError("reference anchor artifact response is malformed") from exc
        return
    raise StaleError("canonical full run disappeared")


def preflight(args: argparse.Namespace) -> None:
    handoff = args.handoff.resolve(strict=True)
    if stat.S_ISLNK(handoff.lstat().st_mode) or not handoff.is_dir():
        raise PreflightError("handoff root is unsafe")
    manifest_bytes = _read(handoff / "handoff.json", 256 * 1024, "handoff manifest")
    manifest_match = SHA256.fullmatch(args.manifest_sha256)
    if manifest_match is None or hashlib.sha256(manifest_bytes).hexdigest() != manifest_match.group(1):
        raise PreflightError("handoff manifest digest mismatch")
    manifest = _loads(manifest_bytes, "handoff manifest")
    queue = _loads(_read(handoff / "queue" / "queue.json", 256 * 1024, "queue manifest"), "queue manifest")
    if (
        not isinstance(manifest, dict)
        or set(manifest) != HANDOFF_KEYS
        or manifest.get("schema_version") != 1
        or manifest.get("purpose") != "claude-advisory-visual-tool-handoff"
        or not isinstance(queue, dict)
    ):
        raise PreflightError("handoff manifest schema is invalid")
    capsule_bytes = _read(
        handoff / "queue" / "capsule" / "visual-capsule.json",
        MAX_CAPSULE_MANIFEST_BYTES,
        "visual capsule manifest",
    )
    if hashlib.sha256(capsule_bytes).hexdigest() != queue.get("capsule_manifest_sha256"):
        raise PreflightError("visual capsule digest disagrees with its queue")
    capsule = _loads(capsule_bytes, "visual capsule manifest")
    if (
        not isinstance(capsule, dict)
        or not isinstance(capsule.get("candidate_source"), dict)
        or not isinstance(capsule.get("reference_source"), dict)
    ):
        raise PreflightError("visual capsule source schema is invalid")
    implementation = _sha(
        manifest.get("reviewer_implementation_sha"), "reviewer implementation commit"
    )
    if (
        os.environ.get("GITHUB_REF") != "refs/heads/master"
        or _sha(os.environ.get("GITHUB_SHA"), "workflow commit") != implementation
        or _sha(args.workflow_sha, "expected workflow commit") != implementation
    ):
        raise StaleError("executing protected workflow is no longer current")
    api = Api(args.repository, os.environ.get("PREFLIGHT_GITHUB_TOKEN", ""))
    if api.branch("master") != implementation:
        raise StaleError("protected controller advanced")
    queue_implementation = _sha(queue.get("implementation_sha"), "queue implementation")
    if queue_implementation != implementation:
        comparison = api.compare(queue_implementation, implementation)
        try:
            if (
                comparison["base_commit"]["sha"] != queue_implementation
                or comparison["merge_base_commit"]["sha"] != queue_implementation
                or comparison["status"] != "ahead"
            ):
                raise StaleError(
                    "queue implementation is not an ancestor of the current controller"
                )
        except (KeyError, TypeError) as exc:
            raise PreflightError("queue implementation comparison is malformed") from exc
    artifact = api.repo(f"/actions/artifacts/{_positive(args.artifact_id, 'artifact id')}")
    digest = SHA256.fullmatch(args.artifact_digest)
    try:
        if (
            artifact["id"] != args.artifact_id
            or artifact["name"] != args.artifact_name
            or artifact["expired"] is not False
            or artifact["workflow_run"]["id"] != _positive(args.owner_run_id, "owner run id")
            or isinstance(artifact.get("size_in_bytes"), bool)
            or not isinstance(artifact.get("size_in_bytes"), int)
            or not 1 <= artifact["size_in_bytes"] <= MAX_HANDOFF_BYTES
            or SHA256.fullmatch(artifact["digest"]).group(1) != digest.group(1)
        ):
            raise StaleError("admitted handoff artifact identity changed")
    except (KeyError, TypeError, AttributeError) as exc:
        raise PreflightError("admitted artifact response is malformed") from exc
    owner = api.repo(f"/actions/runs/{args.owner_run_id}")
    if (
        owner.get("run_attempt") != _positive(args.owner_run_attempt, "owner run attempt")
        or owner.get("path") != WORKFLOW
        or owner.get("head_branch") != "master"
        or owner.get("head_sha") != implementation
        or owner.get("repository", {}).get("full_name") != args.repository
        or owner.get("event") not in {"schedule", "repository_dispatch", "workflow_dispatch"}
        or owner.get("status") not in {"in_progress", "completed"}
    ):
        raise StaleError("admitted handoff owner identity changed")
    historical_binding = _source_identity(api, queue, capsule["candidate_source"])
    _reference_identity(
        api,
        queue,
        capsule["reference_source"],
        historical_binding=historical_binding,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--handoff", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--artifact-id", type=int, required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--artifact-digest", required=True)
    parser.add_argument("--owner-run-id", type=int, required=True)
    parser.add_argument("--owner-run-attempt", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        preflight(args)
    except StaleError:
        return 3
    except TransientError:
        return 4
    except (OSError, PreflightError, ValueError):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
