#!/usr/bin/env python3
"""Conservative visual impact classifier for authenticated sync pull requests.

The only skip is a complete bounded inventory from an exact same-repository
automation sync whose controller identity has already been authenticated.  The
authoritative packaged E2E policy remains ``always-full`` in every result.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


MAX_CHANGED_FILES = 100
MAX_JSON_BYTES = 4 * 1024 * 1024
SHA_LENGTH = 40
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
FILE_STATUSES = frozenset({"added", "modified", "removed", "renamed"})
SYNC_PREFIX = "automation/release-sync/"


class ImpactInputError(ValueError):
    """Raised internally for malformed inputs; callers convert it to review."""


@dataclass(frozen=True)
class ImpactDecision:
    visual_review: str
    reason: str
    paths: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": 1,
            "visual_review": self.visual_review,
            "reason": self.reason,
            "paths": list(self.paths),
            "gate_policy": "always-full",
        }


def _canonical_path(value: Any) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    if "\\" in value or any(
        ord(character) < 32 or ord(character) == 127 for character in value
    ):
        return None
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        return None
    if path.as_posix() != value:
        return None
    return value


def _safe_path(value: Any) -> bool:
    path = _canonical_path(value)
    if path is None:
        return False
    if path.startswith("docs/"):
        return path.endswith(".md")
    if path.startswith("scripts/ci/tests/"):
        return path.endswith(".py")
    return False


def _exact_ref(value: Any) -> str | None:
    if not isinstance(value, str) or not 1 <= len(value) <= 200:
        return None
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        return None
    if (
        value.startswith(("/", "."))
        or value.endswith(("/", ".", ".lock"))
        or "//" in value
        or ".." in value
        or "@{" in value
        or "\\" in value
    ):
        return None
    return value


def _exact_sha(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) != SHA_LENGTH:
        return None
    return value if all(character in "0123456789abcdef" for character in value) else None


def _same_repository_ref(
    value: Any, *, repository: str, expected_ref: str, expected_sha: str
) -> bool:
    return bool(
        isinstance(value, dict)
        and value.get("ref") == expected_ref
        and value.get("sha") == expected_sha
        and isinstance(value.get("repo"), dict)
        and value["repo"].get("full_name") == repository
    )


def _candidate_topology(
    candidate: Any,
    *,
    expected_candidate_sha: str,
    expected_candidate_tree: str,
    expected_base_sha: str,
    expected_head_sha: str,
) -> bool:
    if not isinstance(candidate, dict):
        return False
    parents = candidate.get("parents")
    return bool(
        candidate.get("sha") == expected_candidate_sha
        and isinstance(candidate.get("tree"), dict)
        and candidate["tree"].get("sha") == expected_candidate_tree
        and isinstance(parents, list)
        and len(parents) == 2
        and all(isinstance(parent, dict) for parent in parents)
        and [parent.get("sha") for parent in parents]
        == [expected_base_sha, expected_head_sha]
    )


def _automation_actor_and_label(
    pull: dict[str, Any], *, expected_actor: str, expected_label: str | None
) -> bool:
    user = pull.get("user")
    if not isinstance(user, dict) or user.get("login") != expected_actor:
        return False
    if user.get("type") != "Bot":
        return False
    labels = pull.get("labels")
    if not isinstance(labels, list) or len(labels) > 20:
        return False
    names: list[str] = []
    for label in labels:
        if not isinstance(label, dict) or not isinstance(label.get("name"), str):
            return False
        names.append(label["name"])
    if len(names) != len(set(names)):
        return False
    return expected_label is None or expected_label in names


def _authenticated_sync(
    pull: Any,
    candidate: Any,
    *,
    repository: str,
    expected_head_ref: str,
    expected_head_sha: str,
    expected_base_ref: str,
    expected_base_sha: str,
    expected_candidate_sha: str,
    expected_candidate_tree: str,
    expected_actor: str,
    expected_label: str | None,
    controller_authenticated: bool,
) -> bool:
    if controller_authenticated is not True or not isinstance(pull, dict):
        return False
    if REPOSITORY.fullmatch(repository) is None:
        return False
    if not isinstance(expected_actor, str) or not expected_actor:
        return False
    number = pull.get("number")
    if isinstance(number, bool) or not isinstance(number, int) or number <= 0:
        return False
    if pull.get("state") not in {"open", "closed"}:
        return False
    if pull.get("state") == "closed" and (
        pull.get("merged") is not True or not isinstance(pull.get("merged_at"), str)
    ):
        return False
    if not expected_head_ref.startswith(SYNC_PREFIX):
        return False
    pull_changed_files = pull.get("changed_files")
    if type(pull_changed_files) is not int or not (
        1 <= pull_changed_files <= MAX_CHANGED_FILES
    ):
        return False
    if (
        _exact_ref(expected_head_ref) is None
        or _exact_ref(expected_base_ref) is None
        or _exact_sha(expected_head_sha) is None
        or _exact_sha(expected_base_sha) is None
        or _exact_sha(expected_candidate_sha) is None
        or _exact_sha(expected_candidate_tree) is None
    ):
        return False
    return bool(
        pull.get("merge_commit_sha") == expected_candidate_sha
        and _automation_actor_and_label(
            pull, expected_actor=expected_actor, expected_label=expected_label
        )
        and _same_repository_ref(
            pull.get("head"),
            repository=repository,
            expected_ref=expected_head_ref,
            expected_sha=expected_head_sha,
        )
        and _same_repository_ref(
            pull.get("base"),
            repository=repository,
            expected_ref=expected_base_ref,
            expected_sha=expected_base_sha,
        )
        and _candidate_topology(
            candidate,
            expected_candidate_sha=expected_candidate_sha,
            expected_candidate_tree=expected_candidate_tree,
            expected_base_sha=expected_base_sha,
            expected_head_sha=expected_head_sha,
        )
    )


def _complete_nonvisual_inventory(
    payload: Any, *, changed_files: int
) -> tuple[str, ...] | None:
    if type(changed_files) is not int or not 1 <= changed_files <= MAX_CHANGED_FILES:
        return None
    if not isinstance(payload, list) or len(payload) != changed_files:
        return None
    seen: set[str] = set()
    paths: set[str] = set()
    for item in payload:
        if not isinstance(item, dict) or item.get("status") not in FILE_STATUSES:
            return None
        filename = _canonical_path(item.get("filename"))
        if filename is None or filename in seen or not _safe_path(filename):
            return None
        seen.add(filename)
        paths.add(filename)
        previous = item.get("previous_filename")
        if item["status"] == "renamed" and previous is None:
            return None
        if previous is not None:
            canonical_previous = _canonical_path(previous)
            if canonical_previous is None or not _safe_path(canonical_previous):
                return None
            paths.add(canonical_previous)
    return tuple(sorted(paths))


def classify_sync_impact(
    *,
    pull: Any,
    candidate: Any,
    files: Any,
    changed_files: int,
    repository: str,
    expected_head_ref: str,
    expected_head_sha: str,
    expected_base_ref: str,
    expected_base_sha: str,
    expected_candidate_sha: str,
    expected_candidate_tree: str,
    expected_actor: str,
    expected_label: str | None,
    controller_authenticated: bool,
) -> ImpactDecision:
    """Return ``skip`` only when both provenance and path inventory are exact."""

    if not _authenticated_sync(
        pull,
        candidate,
        repository=repository,
        expected_head_ref=expected_head_ref,
        expected_head_sha=expected_head_sha,
        expected_base_ref=expected_base_ref,
        expected_base_sha=expected_base_sha,
        expected_candidate_sha=expected_candidate_sha,
        expected_candidate_tree=expected_candidate_tree,
        expected_actor=expected_actor,
        expected_label=expected_label,
        controller_authenticated=controller_authenticated,
    ):
        return ImpactDecision("review", "sync-provenance-uncertain", ())
    if pull.get("changed_files") != changed_files:
        return ImpactDecision("review", "changed-file-count-skew", ())
    paths = _complete_nonvisual_inventory(files, changed_files=changed_files)
    if paths is None:
        return ImpactDecision("review", "changed-files-uncertain-or-visual", ())
    return ImpactDecision("skip", "authenticated-nonvisual-sync", paths)


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ImpactInputError(f"JSON repeats key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ImpactInputError(f"JSON contains non-finite number {value!r}")


def read_bounded_json(path: Path) -> Any:
    if not hasattr(os, "O_NOFOLLOW"):
        raise ImpactInputError("this platform cannot safely reject JSON symlinks")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ImpactInputError("JSON input must be a regular non-symlink file")
        if metadata.st_size <= 0 or metadata.st_size > MAX_JSON_BYTES:
            raise ImpactInputError("JSON input is empty or oversized")
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            payload = stream.read(MAX_JSON_BYTES + 1)
        if len(payload) != metadata.st_size or len(payload) > MAX_JSON_BYTES:
            raise ImpactInputError("JSON input changed while it was being read")
    finally:
        os.close(descriptor)
    try:
        text = payload.decode("utf-8", "strict")
    except UnicodeDecodeError as exc:
        raise ImpactInputError("JSON input is not strict UTF-8") from exc
    return json.loads(
        text,
        object_pairs_hook=_reject_duplicate_keys,
        parse_constant=_reject_nonfinite,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pull", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--files", type=Path, required=True)
    parser.add_argument("--changed-files", type=int, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--head-ref", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--candidate-tree", required=True)
    parser.add_argument("--expected-actor", required=True)
    parser.add_argument("--expected-label")
    parser.add_argument("--controller-authenticated", action="store_true")
    args = parser.parse_args(argv)
    try:
        pull = read_bounded_json(args.pull)
        candidate = read_bounded_json(args.candidate)
        files = read_bounded_json(args.files)
    except (
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        ImpactInputError,
        RecursionError,
    ):
        pull = None
        candidate = None
        files = None
    decision = classify_sync_impact(
        pull=pull,
        candidate=candidate,
        files=files,
        changed_files=args.changed_files,
        repository=args.repository,
        expected_head_ref=args.head_ref,
        expected_head_sha=args.head_sha,
        expected_base_ref=args.base_ref,
        expected_base_sha=args.base_sha,
        expected_candidate_sha=args.candidate_sha,
        expected_candidate_tree=args.candidate_tree,
        expected_actor=args.expected_actor,
        expected_label=args.expected_label,
        controller_authenticated=args.controller_authenticated,
    )
    print(json.dumps(decision.manifest(), sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
