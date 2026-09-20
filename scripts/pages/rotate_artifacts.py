#!/usr/bin/env python3
"""Rotate exact Pages artifacts only after their owning deployment succeeds."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from collections.abc import Mapping
from contextlib import ExitStack, contextmanager
from datetime import datetime, timedelta, timezone
from dataclasses import asdict
from pathlib import Path
from typing import Any
from types import SimpleNamespace

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.pages.build_site import SiteError, _inventory  # noqa: E402
from scripts.pages import build_site as site, refresh_cache  # noqa: E402
from scripts.pages.evidence import (  # noqa: E402
    E2E_WORKFLOW,
    EvidenceError,
    cache_artifact_name,
    collection_artifact_name,
    raw_artifact_name,
    validate_compact,
)
from scripts.pages.visual_anchor import (  # noqa: E402
    visual_anchor_artifact_name,
    visual_anchor_artifact_prefix,
)
from scripts.pages.select_artifact import (  # noqa: E402
    Artifact,
    GitHubApi,
    PAGES_EVENTS,
    PAGES_WORKFLOW,
    SelectionError,
    _validate_run,
    newest_exact_source,
)


class RotationError(RuntimeError):
    """Raised before deletion when a replacement generation is not exact."""


ANCHOR_QUEUE_GRACE = timedelta(days=8)


class RotationApi(GitHubApi):
    def delete_artifact(self, artifact_id: int) -> None:
        request = urllib.request.Request(
            f"{self.api_url}/repos/{self.repository}/actions/artifacts/{artifact_id}",
            method="DELETE",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "BlockPops-pages-rotation",
            },
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                if response.status != 204:
                    raise RotationError(f"artifact deletion returned HTTP {response.status}")
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return
            raise RotationError(f"cannot delete exact artifact {artifact_id}: {exc}") from exc
        except OSError as exc:
            raise RotationError(f"cannot delete exact artifact {artifact_id}: {exc}") from exc


@contextmanager
def current_rotation_inputs(api, *, repository, pages_run_id, pages_run_attempt,
                            implementation_sha, canonical_branch, inventory_path, caches_root):
    """Hold authenticated current-attempt inputs; no plan or deletion is authorized here.

    The caller must keep this context open and recheck before planning/each action.
    Cache transport remains the caller's same-run digest-checked download.
    """
    if not REPO == site.REPO == refresh_cache.REPO:
        raise RotationError("rotation and cache validators must share their protected checkout")
    args = SimpleNamespace(repository=repository, pages_run_id=pages_run_id,
        pages_run_attempt=pages_run_attempt, implementation_sha=implementation_sha,
        canonical_branch=canonical_branch, inventory=inventory_path,
        canonical_matrix=REPO / "release/release-matrix.json")
    with site._current_pages_inputs(api, args, phase="rotate-current") as context, ExitStack() as handles:
        root = caches_root.absolute()
        site.atomic._real_directory(root.parent)
        root = root.parent.resolve(strict=True) / root.name
        parent = site.atomic._directory_fd(root.parent); handles.callback(os.close, parent)
        descriptor = site.atomic._directory_fd(Path(root.name), root_fd=parent); handles.callback(os.close, descriptor)
        names = {cache_artifact_name(row["name"], row["commit"]) for row in context["rows"]}
        def stamp():
            info = os.fstat(descriptor)
            return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        initial = stamp()
        def inventory():
            if set(os.listdir(descriptor)) != names or stamp() != initial:
                raise RotationError("rotation cache inventory changed or differs")
            site.atomic._bound_output_directory(root, parent, root.name, descriptor)
        inventory()
        manifests, cached, size, count = {}, [], 0, 0
        for row in context["rows"]:
            manifest, files, total, unchanged = handles.enter_context(refresh_cache._cache_input(
                context, root=root / cache_artifact_name(row["name"], row["commit"]),
                branch=row["name"], repository=repository, byte_budget=site.MAX_SITE_BYTES-size))
            size += total; count += files
            if count > site.MAX_SITE_FILES:
                raise RotationError("rotation caches exceed the aggregate file-count bound")
            manifests[row["name"]] = manifest
            cached.append(unchanged)
        live = True
        def recheck(*, _after_api=None):
            if not live:
                raise RotationError("rotation input lease is closed")
            context["recheck"](_after_api=_after_api)
            for unchanged in cached: unchanged()
            inventory()
        try:
            recheck()
            yield context["rows"], manifests, recheck
        finally:
            live = False


def validate_current_invocation(
    environment: Mapping[str, str],
    *,
    repository: str,
    canonical_branch: str,
    pages_run_id: int,
    pages_run_attempt: int,
    pages_run_sha: str,
) -> None:
    """Bind the in-progress exception to this exact protected Pages attempt."""

    expected_workflow_ref = (
        f"{repository}/.github/workflows/pages.yml@refs/heads/{canonical_branch}"
    )
    expected = {
        "GITHUB_REPOSITORY": repository,
        "GITHUB_REF": f"refs/heads/{canonical_branch}",
        "GITHUB_SHA": pages_run_sha,
        "GITHUB_RUN_ID": str(pages_run_id),
        "GITHUB_RUN_ATTEMPT": str(pages_run_attempt),
        "GITHUB_WORKFLOW_REF": expected_workflow_ref,
    }
    if pages_run_id <= 0 or pages_run_attempt <= 0:
        raise RotationError("current Pages run id/attempt must be positive")
    for key, value in expected.items():
        if environment.get(key) != value:
            raise RotationError(f"current Pages invocation has stale {key}")


def _plan_anchor_rotation(
    *,
    api: RotationApi,
    repository: str,
    branch: str,
    commit: str,
    artifacts: list[Artifact],
    now: datetime,
) -> tuple[int, set[int]]:
    """Keep each authenticated anchor for one queue window after supersession."""

    if now.tzinfo is None or now.utcoffset() is None:
        raise RotationError("visual anchor rotation time must be timezone-aware")
    now = now.astimezone(timezone.utc)

    workflow = api.workflow("on-demand-e2e.yml")
    prefix = visual_anchor_artifact_prefix(branch)
    pattern = re.compile(
        re.escape(prefix)
        + r"(?P<commit>[0-9a-f]{40})-(?P<run_id>[1-9][0-9]*)-"
        + r"(?P<run_attempt>[1-9][0-9]*)"
    )
    authenticated: list[tuple[Artifact, str]] = []
    for artifact in artifacts:
        match = pattern.fullmatch(artifact.name)
        if match is None or artifact.expired:
            continue
        artifact_commit = match.group("commit")
        producer_run_id = int(match.group("run_id"))
        producer_run_attempt = int(match.group("run_attempt"))
        if (
            artifact.name
            != visual_anchor_artifact_name(
                branch, artifact_commit, producer_run_id, producer_run_attempt
            )
            or artifact.run_id != producer_run_id
            or artifact.head_branch != branch
            or artifact.head_sha != artifact_commit
        ):
            continue
        try:
            owner = api.run_attempt(producer_run_id, producer_run_attempt)
            _validate_run(
                owner,
                workflow_id=workflow["id"],
                workflow_path=E2E_WORKFLOW,
                repository=repository,
                branch=branch,
                sha=artifact_commit,
                events=frozenset({"schedule", "workflow_dispatch"}),
                require_success=True,
                display_title=f"Packaged E2E / {artifact_commit}",
            )
            if (
                owner.get("id") != producer_run_id
                or owner.get("run_attempt") != producer_run_attempt
                or artifact.head_branch != owner.get("head_branch")
                or artifact.head_sha != owner.get("head_sha")
            ):
                raise SelectionError("anchor owner attempt identity is stale")
        except (OSError, SelectionError):
            # An artifact is never a deletion candidate unless its owner can be
            # authenticated positively.  Current-head absence still fails below.
            continue
        authenticated.append((artifact, artifact_commit))
    replacements = [
        artifact
        for artifact, published_commit in authenticated
        if published_commit == commit
    ]
    if not replacements:
        raise RotationError("no authenticated current-head visual anchor replacement exists")
    replacement = max(replacements, key=lambda artifact: artifact.order)
    if replacement.order[0] > now:
        raise RotationError("current visual anchor replacement is future-dated")
    ordered = sorted(
        (artifact for artifact, _commit in authenticated), key=lambda item: item.order
    )
    deletions: set[int] = set()
    for index, candidate in enumerate(ordered):
        if candidate.id == replacement.id:
            continue
        if candidate.order >= replacement.order:
            raise RotationError(
                f"a concurrent visual anchor is not older than replacement {replacement.id}"
            )
        # The first authenticated successor is the earliest time at which this
        # exact anchor could have become obsolete.  Queue artifacts live for
        # seven days.  One additional day avoids a boundary race between queue
        # expiry/cleanup and a review that still names the historical artifact.
        successor = ordered[index + 1]
        if now - successor.order[0] >= ANCHOR_QUEUE_GRACE:
            deletions.add(candidate.id)
    return replacement.id, deletions


def _plan_rotation(
    *, api: RotationApi, repository: str, pages_run_id: int, pages_run_sha: str,
    inventory: list[dict[str, Any]], caches_root: Path, canonical_branch: str,
    now: datetime | None = None, current_run_attempt: int | None = None,
    manifests: dict | None = None,
) -> list[int]:
    rotation_time = datetime.now(timezone.utc) if now is None else now
    pages_workflow = api.workflow("pages.yml")
    owner = api.run(pages_run_id)
    try:
        _validate_run(
            owner, workflow_id=pages_workflow["id"], workflow_path=PAGES_WORKFLOW,
            repository=repository, branch=canonical_branch, sha=pages_run_sha,
            events=PAGES_EVENTS, require_success=current_run_attempt is None,
        )
    except SelectionError as exc:
        raise RotationError(str(exc)) from exc
    if owner.get("id") != pages_run_id:
        raise RotationError("Pages owner API returned another run id")
    if current_run_attempt is not None:
        if current_run_attempt <= 0:
            raise RotationError("current Pages run attempt must be positive")
        if (
            owner.get("run_attempt") != current_run_attempt
            or owner.get("status") != "in_progress"
            or owner.get("conclusion") is not None
        ):
            raise RotationError("current Pages owner attempt is not exact and in progress")
    owner_artifacts = api.artifacts_for_run(pages_run_id)
    repository_artifacts = api.all_artifacts()
    deletions: set[int] = set()
    keep_ids: set[int] = set()
    canonical_rows = [row for row in inventory if row["name"] == canonical_branch]
    if len(canonical_rows) != 1:
        raise RotationError("Pages inventory must contain exactly one canonical branch")
    canonical_row = canonical_rows[0]
    canonical_head = api.branch_head(canonical_branch)
    if canonical_head != (canonical_row["commit"], canonical_row["tree"]):
        raise RotationError("canonical branch advanced before visual anchor rotation")
    anchor_keep, anchor_deletions = _plan_anchor_rotation(
        api=api,
        repository=repository,
        branch=canonical_branch,
        commit=canonical_row["commit"],
        artifacts=repository_artifacts,
        now=rotation_time,
    )
    keep_ids.add(anchor_keep)
    deletions.update(anchor_deletions)
    for row in inventory:
        branch = row["name"]
        current = api.branch_head(branch)
        if current != (row["commit"], row["tree"]):
            raise RotationError(f"branch {branch!r} advanced before rotation")
        cache_name = cache_artifact_name(branch, row["commit"])
        keep = [
            artifact for artifact in owner_artifacts
            if artifact.name == cache_name and not artifact.expired
            and artifact.run_id == pages_run_id and artifact.head_branch == canonical_branch
            and artifact.head_sha == pages_run_sha
        ]
        if len(keep) != 1:
            raise RotationError(f"successful Pages run does not own exactly one cache for {branch!r}")
        replacement = keep[0]
        keep_ids.add(replacement.id)
        collection_name = collection_artifact_name(branch, row["commit"])
        collected = [
            artifact
            for artifact in owner_artifacts
            if artifact.name == collection_name
            and not artifact.expired
            and artifact.run_id == pages_run_id
            and artifact.head_branch == canonical_branch
            and artifact.head_sha == pages_run_sha
        ]
        if len(collected) != 1:
            raise RotationError(
                f"successful Pages run does not own exactly one fan-in bundle for {branch!r}"
            )
        deletions.add(collected[0].id)
        bundle = caches_root / cache_name
        manifest = manifests[branch] if manifests is not None else validate_compact(
            bundle,
            matrix_path=bundle / "release-matrix.json",
            expected={
                "repository": repository,
                "branch": branch,
                "commit": row["commit"],
                "tree": row["tree"],
                "matrix_sha256": row["matrix_sha256"],
            },
        )
        source_id = manifest["source_artifact"]["id"]
        source_name = raw_artifact_name(
            branch, manifest["provenance"]["handoff"]["run_attempt"]
        )
        raw_matches = [artifact for artifact in api.artifacts_named(source_name) if artifact.id == source_id]
        if len(raw_matches) > 1:
            raise RotationError(f"source artifact ID {source_id} is ambiguous")
        if raw_matches:
            source = raw_matches[0]
            handoff = manifest["provenance"]["handoff"]
            if (
                source.name != source_name
                or source.digest != manifest["source_artifact"]["digest"]
                or source.run_id != handoff["run_id"]
                or source.head_branch != handoff["controller_branch"]
                or source.head_sha != handoff["controller_sha"]
            ):
                raise RotationError(f"source artifact {source_id} provenance is stale")
            source_workflow = api.workflow("on-demand-e2e.yml")
            source_run = api.run_attempt(source.run_id, handoff["run_attempt"])
            try:
                _validate_run(
                    source_run,
                    workflow_id=source_workflow["id"],
                    workflow_path=E2E_WORKFLOW,
                    repository=repository,
                    branch=handoff["controller_branch"],
                    sha=handoff["controller_sha"],
                    events=(
                        frozenset({"workflow_dispatch", "schedule"})
                        if branch == canonical_branch
                        else frozenset({"workflow_dispatch"})
                    ),
                    require_success=True,
                    display_title=f"Packaged E2E / {row['commit']}",
                )
            except SelectionError as exc:
                raise RotationError(str(exc)) from exc
            if (
                source_run.get("id") != handoff["run_id"]
                or source_run.get("run_attempt") != handoff["run_attempt"]
            ):
                raise RotationError("source artifact run attempt differs from compact provenance")
            deletions.add(source.id)
        cache_prefix = cache_name.rsplit("--", 1)[0] + "--"
        cache_pattern = re.compile(re.escape(cache_prefix) + r"[0-9a-f]{40}")
        caches = [artifact for artifact in repository_artifacts if cache_pattern.fullmatch(artifact.name)]
        for candidate in caches:
            if candidate.id == replacement.id or candidate.expired:
                continue
            if candidate.head_branch != canonical_branch:
                raise RotationError(f"cache artifact {candidate.id} has an unexpected owner branch")
            candidate_owner = api.run(candidate.run_id)
            try:
                _validate_run(
                    candidate_owner,
                    workflow_id=pages_workflow["id"],
                    workflow_path=PAGES_WORKFLOW,
                    repository=repository,
                    branch=canonical_branch,
                    sha=candidate.head_sha,
                    events=PAGES_EVENTS,
                    require_success=True,
                )
            except SelectionError as exc:
                raise RotationError(str(exc)) from exc
            if candidate.order >= replacement.order:
                raise RotationError(f"a concurrent cache is not older than replacement {replacement.id}")
            deletions.add(candidate.id)
    # The immutable deploy payload is redundant after GitHub Pages reports success.
    pages_payloads = [artifact for artifact in owner_artifacts if artifact.name == "github-pages" and not artifact.expired]
    if len(pages_payloads) != 1:
        raise RotationError("successful Pages run does not own exactly one deploy artifact")
    deletions.add(pages_payloads[0].id)
    promotions = [artifact for artifact in owner_artifacts if artifact.name == "pages-promotion" and not artifact.expired]
    if len(promotions) != 1:
        raise RotationError("successful Pages run does not own exactly one promotion inventory")
    deletions.add(promotions[0].id)
    if deletions & keep_ids:
        raise RotationError("rotation plan attempts to delete a retained cache")
    return sorted(deletions)


def plan_rotation(
    *, api: RotationApi, repository: str, pages_run_id: int, pages_run_sha: str,
    inventory: list[dict[str, Any]], caches_root: Path, canonical_branch: str,
    now: datetime | None = None, current_run_attempt: int | None = None,
) -> list[int]:
    return _plan_rotation(api=api, repository=repository, pages_run_id=pages_run_id,
        pages_run_sha=pages_run_sha, inventory=inventory, caches_root=caches_root,
        canonical_branch=canonical_branch, now=now, current_run_attempt=current_run_attempt)


class _RotationReads:
    """Pin policy reads; only this invocation's successful deletions may disappear."""
    methods = frozenset({"workflow", "run", "run_attempt", "runs", "branch_head",
                         "artifacts_for_run", "all_artifacts", "artifacts_named"})
    run_fields = ("id", "run_attempt", "workflow_id", "path", "head_branch", "head_sha", "event",
                  "display_title", "created_at", "status", "conclusion", "head_repository")

    def __init__(self, api):
        self.api, self.records, self.artifacts = api, {}, {}

    def stable(self, name, value, deleted=()):
        if name in {"artifacts_for_run", "all_artifacts", "artifacts_named"}:
            if not isinstance(value, list) or not all(isinstance(item, Artifact) for item in value):
                raise RotationError("rotation artifact response is malformed")
            if len({item.id for item in value}) != len(value):
                raise RotationError("rotation artifact IDs are duplicated")
            for item in value:
                raw = site.canonical_json(asdict(item))
                if item.id in self.artifacts and self.artifacts[item.id] != raw:
                    raise RotationError("rotation artifact metadata changed")
                self.artifacts.setdefault(item.id, raw)
            return [asdict(item) for item in sorted(value, key=lambda item: item.id) if item.id not in deleted]
        if name == "branch_head": return value
        fields = ("id", "path", "state") if name == "workflow" else self.run_fields
        records = value if name == "runs" else [value]
        projected = [{key: row.get(key) for key in fields} for row in records]
        return sorted(site.canonical_json(row) for row in projected)

    def __getattr__(self, name):
        if name not in self.methods: raise AttributeError(name)
        def read(*args):
            value = getattr(self.api, name)(*args)
            key = (name, args); encoded = site.canonical_json(self.stable(name, value))
            if key in self.records and self.records[key][1] != encoded:
                raise RotationError("rotation policy input changed while planning")
            self.records[key] = (value, encoded)
            return value
        return read

    def pin_current_attempts(self):
        for (name, args), (historical, _) in list(self.records.items()):
            if name == "run_attempt":
                current = self.run(args[0])
                if site.canonical_json(self.stable("run", current)) != site.canonical_json(self.stable("run", historical)):
                    raise RotationError("rotation historical owner is no longer the current attempt")

    def recheck(self, deleted):
        for (name, args), (original, _) in self.records.items():
            current = getattr(self.api, name)(*args)
            if site.canonical_json(self.stable(name, current, deleted)) != site.canonical_json(self.stable(name, original, deleted)):
                raise RotationError("rotation inventory or owner changed")


@contextmanager
def current_rotation_actions(api, *, repository, pages_run_id, pages_run_attempt,
                             implementation_sha, canonical_branch, inventory_path, caches_root, now=None):
    """Keep exact-ID actions inside live authenticated leases; no caller-supplied permits."""
    with current_rotation_inputs(api, repository=repository, pages_run_id=pages_run_id,
            pages_run_attempt=pages_run_attempt, implementation_sha=implementation_sha,
            canonical_branch=canonical_branch, inventory_path=inventory_path, caches_root=caches_root) as (rows, manifests, recheck):
        reads = _RotationReads(api)
        for row in rows:
            source = newest_exact_source(reads, repository=repository, branch=row["name"], commit=row["commit"],
                tree=row["tree"], canonical_branch=canonical_branch)
            handoff = manifests[row["name"]]["provenance"]["handoff"]
            if source != tuple(handoff[key] for key in ("run_id", "run_attempt", "controller_branch", "controller_sha")):
                raise RotationError("rotation cache does not describe the newest exact source")
        planned = tuple(_plan_rotation(api=reads, repository=repository, pages_run_id=pages_run_id,
            pages_run_sha=implementation_sha, inventory=rows, caches_root=caches_root,
            canonical_branch=canonical_branch, now=now, current_run_attempt=pages_run_attempt, manifests=manifests))
        reads.pin_current_attempts()
        permits = {identifier: reads.artifacts[identifier] for identifier in planned}
        deleted, live, failed = set(), True, False
        def delete_next():
            nonlocal failed
            if not live or failed or len(deleted) == len(planned):
                raise RotationError("rotation action lease is closed, failed or exhausted")
            identifier = planned[len(deleted)]
            def observe():
                reads.recheck(deleted)
                item = Artifact.parse(api.get(f"/repos/{repository}/actions/artifacts/{identifier}"))
                if site.canonical_json(asdict(item)) != permits[identifier]:
                    raise RotationError("rotation exact-ID permit changed")
            try:
                recheck(_after_api=observe)
                api.delete_artifact(identifier)
            except BaseException:
                failed = True
                raise
            deleted.add(identifier)
            return identifier
        try:
            recheck(_after_api=lambda: reads.recheck(deleted))
            yield planned, delete_next
        finally:
            live = False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--pages-run-id", type=int, required=True)
    parser.add_argument("--current-run-attempt", type=int)
    parser.add_argument("--pages-run-sha", required=True)
    parser.add_argument("--canonical-branch", required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--caches-root", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise RotationError("GH_TOKEN is required")
        api = RotationApi(
            repository=args.repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        if args.current_run_attempt is not None:
            validate_current_invocation(
                os.environ,
                repository=args.repository,
                canonical_branch=args.canonical_branch,
                pages_run_id=args.pages_run_id,
                pages_run_attempt=args.current_run_attempt,
                pages_run_sha=args.pages_run_sha,
            )
        inventory = _inventory(args.inventory)
        deletions = plan_rotation(
            api=api,
            repository=args.repository,
            pages_run_id=args.pages_run_id,
            pages_run_sha=args.pages_run_sha,
            inventory=inventory,
            caches_root=args.caches_root,
            canonical_branch=args.canonical_branch,
            current_run_attempt=args.current_run_attempt,
        )
        if not args.dry_run:
            for artifact_id in deletions:
                api.delete_artifact(artifact_id)
        print(json.dumps({"deleted": [] if args.dry_run else deletions, "planned": deletions}, sort_keys=True))
        return 0
    except (EvidenceError, OSError, RotationError, SelectionError, SiteError, ValueError) as exc:
        print(f"Pages rotation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
