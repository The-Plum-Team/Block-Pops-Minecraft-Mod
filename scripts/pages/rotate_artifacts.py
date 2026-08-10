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
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.pages.build_site import SiteError, _inventory  # noqa: E402
from scripts.pages.evidence import (  # noqa: E402
    EvidenceError,
    cache_artifact_name,
    collection_artifact_name,
    raw_artifact_name,
    validate_compact,
)
from scripts.pages.select_artifact import (  # noqa: E402
    Artifact,
    GitHubApi,
    PAGES_EVENTS,
    PAGES_WORKFLOW,
    SelectionError,
    _validate_run,
)


class RotationError(RuntimeError):
    """Raised before deletion when a replacement generation is not exact."""


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
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status != 204:
                    raise RotationError(f"artifact deletion returned HTTP {response.status}")
        except (OSError, urllib.error.HTTPError) as exc:
            raise RotationError(f"cannot delete exact artifact {artifact_id}: {exc}") from exc


def plan_rotation(
    *, api: RotationApi, repository: str, pages_run_id: int, pages_run_sha: str,
    inventory: list[dict[str, Any]], caches_root: Path, canonical_branch: str,
) -> list[int]:
    pages_workflow = api.workflow("pages.yml")
    owner = api.run(pages_run_id)
    try:
        _validate_run(
            owner, workflow_id=pages_workflow["id"], workflow_path=PAGES_WORKFLOW,
            repository=repository, branch=canonical_branch, sha=pages_run_sha,
            events=PAGES_EVENTS, require_success=True,
        )
    except SelectionError as exc:
        raise RotationError(str(exc)) from exc
    owner_artifacts = api.artifacts_for_run(pages_run_id)
    repository_artifacts = api.all_artifacts()
    deletions: set[int] = set()
    keep_ids: set[int] = set()
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
        manifest = validate_compact(
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
            if source.name != source_name or source.head_branch != branch or source.head_sha != row["commit"]:
                raise RotationError(f"source artifact {source_id} provenance is stale")
            source_workflow = api.workflow("on-demand-e2e.yml")
            source_run = api.run(source.run_id)
            try:
                _validate_run(
                    source_run,
                    workflow_id=source_workflow["id"],
                    workflow_path=".github/workflows/on-demand-e2e.yml",
                    repository=repository,
                    branch=branch,
                    sha=row["commit"],
                    events=(
                        frozenset({"workflow_dispatch", "schedule"})
                        if branch == canonical_branch
                        else frozenset({"workflow_dispatch"})
                    ),
                    require_success=True,
                )
            except SelectionError as exc:
                raise RotationError(str(exc)) from exc
            if source.run_id != manifest["provenance"]["handoff"]["run_id"]:
                raise RotationError("source artifact owner differs from compact provenance")
            if source_run.get("run_attempt") != manifest["provenance"]["handoff"]["run_attempt"]:
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--pages-run-id", type=int, required=True)
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
        inventory = _inventory(args.inventory)
        deletions = plan_rotation(
            api=api,
            repository=args.repository,
            pages_run_id=args.pages_run_id,
            pages_run_sha=args.pages_run_sha,
            inventory=inventory,
            caches_root=args.caches_root,
            canonical_branch=args.canonical_branch,
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
