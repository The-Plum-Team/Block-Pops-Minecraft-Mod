#!/usr/bin/env python3
"""Create one authenticated release-sync merge while retaining target identity.

Only ``release/release-matrix.json`` has a mechanical conflict policy.  Its
exact target blob is restored even when Git could auto-merge it.  Every other
unmerged path fails closed; this controller never delegates conflict resolution
to AI or executes files from the candidate tree.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES,
    MatrixError,
    load_matrix_bytes,
)

MATRIX_PATH = "release/release-matrix.json"
VERIFICATION_PATH = "gradle/verification-metadata.xml"
KNOWN_LOADER_ROOTS = frozenset({"fabric", "forge", "neoforge"})
VERSION_SPECIFIC_PATHS = (
    "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java",
    "e2e/server-template/datapack/pack.mcmeta",
)
SCHEMA_VERSION = 1
BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"


class SyncMergeError(ValueError):
    """Raised when an exact synchronization merge cannot be authenticated."""


def branch_specific_loader_roots(
    target_loaders: Iterable[str], source_loaders: Iterable[str]
) -> tuple[str, ...]:
    """Return loader roots that must retain the exact target tree."""

    target = frozenset(target_loaders)
    source = frozenset(source_loaders)
    if (
        not target
        or not source
        or not target.issubset(KNOWN_LOADER_ROOTS)
        or not source.issubset(KNOWN_LOADER_ROOTS)
    ):
        raise SyncMergeError("loader-root policy received an invalid loader inventory")
    return tuple(sorted(KNOWN_LOADER_ROOTS - (target & source)))


def _environment() -> dict[str, str]:
    environment = os.environ.copy()
    for name in tuple(environment):
        if name in {
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        } or name.startswith("GIT_CONFIG_"):
            environment.pop(name, None)
    environment.update(
        {
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_PAGER": "cat",
            "LC_ALL": "C",
        }
    )
    return environment


def _git(
    repository: Path,
    *arguments: str,
    accepted: Iterable[int] = (0,),
) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=_environment(),
    )
    if result.returncode not in set(accepted):
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise SyncMergeError(detail or f"git {' '.join(arguments)} failed")
    return result


def _object_length(repository: Path) -> int:
    value = _git(repository, "rev-parse", "--show-object-format").stdout.decode().strip()
    if value == "sha1":
        return 40
    if value == "sha256":
        return 64
    raise SyncMergeError(f"unsupported Git object format {value!r}")


def _oid(value: str, length: int, label: str) -> str:
    if re.fullmatch(rf"[0-9a-f]{{{length}}}", value) is None:
        raise SyncMergeError(f"{label} is not an exact lowercase object id")
    return value


def _resolve_commit(repository: Path, value: str, length: int, label: str) -> str:
    _oid(value, length, label)
    resolved = _git(repository, "rev-parse", "--verify", f"{value}^{{commit}}").stdout.decode().strip()
    if _oid(resolved, length, label) != value:
        raise SyncMergeError(f"{label} did not resolve to itself")
    return value


def _clean(repository: Path) -> bool:
    return not _git(
        repository, "status", "--porcelain=v1", "-z", "--untracked-files=all"
    ).stdout


def _blob(repository: Path, commit: str, path: str) -> tuple[str, bytes]:
    record = _git(repository, "ls-tree", "-z", commit, "--", path).stdout
    metadata, separator, raw_path = record.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if (
        separator != b"\t"
        or raw_path != path.encode()
        or len(fields) != 3
        or fields[0] != b"100644"
        or fields[1] != b"blob"
    ):
        raise SyncMergeError(f"{commit} has no regular {path} blob")
    object_name = f"{commit}:{path}"
    raw_size = _git(repository, "cat-file", "-s", object_name).stdout.decode().strip()
    try:
        size = int(raw_size)
    except ValueError as exc:
        raise SyncMergeError(f"{commit} has an invalid {path} blob size") from exc
    if size <= 0 or size > MAX_MATRIX_BYTES:
        raise SyncMergeError(
            f"{commit} {path} size is outside 1..{MAX_MATRIX_BYTES}"
        )
    payload = _git(repository, "show", object_name).stdout
    if len(payload) != size:
        raise SyncMergeError(f"{commit} {path} changed while being inspected")
    return fields[2].decode("ascii"), payload


def _tree_entry(repository: Path, commit: str, path: str) -> tuple[str, str, str] | None:
    record = _git(repository, "ls-tree", "-z", commit, "--", path).stdout
    if not record:
        return None
    metadata, separator, raw_path = record.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if separator != b"\t" or raw_path != path.encode() or len(fields) != 3:
        raise SyncMergeError(f"{commit} has a malformed tree entry for {path}")
    try:
        mode, kind, oid = (field.decode("ascii", "strict") for field in fields)
    except UnicodeDecodeError as exc:
        raise SyncMergeError(f"{commit} has a non-ASCII tree entry for {path}") from exc
    if (mode, kind) not in {("100644", "blob"), ("040000", "tree")}:
        raise SyncMergeError(f"{commit} has an unsafe tree entry for {path}")
    return mode, kind, oid


def _restore_target_path(repository: Path, target_sha: str, path: str) -> None:
    """Restore an exact target-owned path, including target absence."""

    if _tree_entry(repository, target_sha, path) is not None:
        _git(
            repository,
            "restore",
            "--source",
            target_sha,
            "--staged",
            "--worktree",
            "--",
            path,
        )
        return
    _git(
        repository,
        "rm",
        "-r",
        "--force",
        "--ignore-unmatch",
        "--",
        path,
    )
    # Delete only this exact target-absent root if a conflicted worktree copy
    # remained untracked after its index entries were removed.
    _git(repository, "clean", "-d", "--force", "--", path)


def _unmerged(repository: Path) -> tuple[str, ...]:
    raw = _git(repository, "diff", "--name-only", "--diff-filter=U", "-z").stdout
    records = [item for item in raw.split(b"\0") if item]
    try:
        paths = tuple(sorted(item.decode("utf-8", "strict") for item in records))
    except UnicodeDecodeError as exc:
        raise SyncMergeError("merge contains a non-UTF-8 conflict path") from exc
    if len(paths) != len(set(paths)):
        raise SyncMergeError("merge repeats an unmerged path")
    return paths


def _abort(repository: Path) -> None:
    _git(repository, "merge", "--abort", accepted=(0, 1, 128))


def create_sync_merge(
    repository: Path,
    *,
    target_sha: str,
    source_sha: str,
    target_branch: str,
    source_branch: str,
) -> dict[str, object]:
    repository = repository.resolve()
    length = _object_length(repository)
    target_sha = _resolve_commit(repository, target_sha, length, "target SHA")
    source_sha = _resolve_commit(repository, source_sha, length, "source SHA")
    if target_sha == source_sha:
        raise SyncMergeError("target and source SHA must differ")
    head = _git(repository, "rev-parse", "HEAD").stdout.decode().strip()
    if head != target_sha or not _clean(repository):
        raise SyncMergeError("sync worktree must be completely clean at the exact target SHA")

    matrix_blob, matrix_bytes = _blob(repository, target_sha, MATRIX_PATH)
    try:
        target_matrix = load_matrix_bytes(matrix_bytes)
    except MatrixError as exc:
        raise SyncMergeError(f"target matrix is invalid: {exc}") from exc
    identity = target_matrix["branch"]
    if (
        identity["role"] != "release"
        or identity["name"] != target_branch
        or identity["canonical"] != source_branch
        or identity["sync"] != {"enabled": True, "source": source_branch}
    ):
        raise SyncMergeError("target matrix does not enroll this exact branch/source pair")
    _, source_matrix_bytes = _blob(repository, source_sha, MATRIX_PATH)
    try:
        source_matrix = load_matrix_bytes(source_matrix_bytes)
    except MatrixError as exc:
        raise SyncMergeError(f"source matrix is invalid: {exc}") from exc
    if (
        source_matrix["branch"]["role"] != "integration"
        or source_matrix["branch"]["name"] != source_branch
        or source_matrix["branch"]["canonical"] != source_branch
    ):
        raise SyncMergeError("source matrix does not authenticate the canonical branch")
    active_loaders = {row["loader"] for row in target_matrix["artifacts"]}
    source_loaders = {row["loader"] for row in source_matrix["artifacts"]}
    branch_specific_loaders = branch_specific_loader_roots(
        active_loaders, source_loaders
    )
    target_owned_paths = (
        MATRIX_PATH,
        VERIFICATION_PATH,
        *VERSION_SPECIFIC_PATHS,
        *branch_specific_loaders,
    )
    verification_entry = _tree_entry(repository, target_sha, VERIFICATION_PATH)
    if verification_entry is None or verification_entry[:2] != ("100644", "blob"):
        raise SyncMergeError("target branch has no regular dependency-verification metadata")
    target_owned_entries = {
        path: _tree_entry(repository, target_sha, path) for path in target_owned_paths
    }

    if _git(
        repository,
        "merge-base",
        "--is-ancestor",
        source_sha,
        target_sha,
        accepted=(0, 1),
    ).returncode == 0:
        return {
            "schema_version": SCHEMA_VERSION,
            "status": "up-to-date",
            "target_branch": target_branch,
            "source_branch": source_branch,
            "target_sha": target_sha,
            "source_sha": source_sha,
            "merge_sha": target_sha,
            "tree": _git(repository, "rev-parse", f"{target_sha}^{{tree}}").stdout.decode().strip(),
            "matrix_blob": matrix_blob,
            "matrix_sha256": hashlib.sha256(matrix_bytes).hexdigest(),
        }

    with tempfile.TemporaryDirectory(prefix="blockpops-sync-hooks-") as hooks:
        merge_started = False
        try:
            result = _git(
                repository,
                "-c",
                f"core.hooksPath={hooks}",
                "-c",
                f"user.name={BOT_NAME}",
                "-c",
                f"user.email={BOT_EMAIL}",
                "-c",
                "commit.gpgSign=false",
                "merge",
                "--no-ff",
                "--no-commit",
                "--no-edit",
                source_sha,
                accepted=(0, 1),
            )
            merge_started = True
            merge_head = _git(repository, "rev-parse", "--verify", "MERGE_HEAD").stdout.decode().strip()
            if merge_head != source_sha:
                raise SyncMergeError("MERGE_HEAD does not equal the exact source SHA")
            conflicts = _unmerged(repository)
            def mechanically_owned(path: str) -> bool:
                return any(
                    path == owned or path.startswith(f"{owned}/")
                    for owned in target_owned_paths
                )

            unknown = tuple(path for path in conflicts if not mechanically_owned(path))
            if unknown:
                raise SyncMergeError(
                    "unknown release-sync conflicts fail closed: " + ", ".join(unknown)
                )
            if result.returncode == 1 and not conflicts:
                raise SyncMergeError("Git merge failed without an authenticated conflict set")

            # Retain all target-owned identities even when Git auto-merged them.
            # Dependency hashes are branch-local, and an inactive loader root from
            # canonical must never be introduced into a different loader topology.
            for target_owned in target_owned_paths:
                _restore_target_path(repository, target_sha, target_owned)
            if _unmerged(repository):
                raise SyncMergeError("mechanical matrix retention left unmerged paths")
            retained_blob, retained_bytes = _blob(repository, "HEAD", MATRIX_PATH)
            # HEAD still names target during --no-commit; authenticate index separately.
            staged_blob = _git(
                repository, "rev-parse", f":{MATRIX_PATH}"
            ).stdout.decode().strip()
            if (
                retained_blob != matrix_blob
                or retained_bytes != matrix_bytes
                or staged_blob != matrix_blob
                or (repository / MATRIX_PATH).read_bytes() != matrix_bytes
            ):
                raise SyncMergeError("target release matrix was not retained byte-for-byte")
            for target_owned, expected_entry in target_owned_entries.items():
                staged_tree = _git(repository, "write-tree").stdout.decode().strip()
                actual_entry = _tree_entry(repository, staged_tree, target_owned)
                if actual_entry != expected_entry:
                    raise SyncMergeError(
                        f"target-owned path {target_owned!r} was not retained exactly"
                    )
            load_matrix_bytes(matrix_bytes)
            _git(repository, "diff", "--check")
            _git(
                repository,
                "-c",
                f"core.hooksPath={hooks}",
                "-c",
                f"user.name={BOT_NAME}",
                "-c",
                f"user.email={BOT_EMAIL}",
                "-c",
                "commit.gpgSign=false",
                "commit",
                "--no-edit",
            )
            merge_started = False
            merge_sha = _git(repository, "rev-parse", "HEAD").stdout.decode().strip()
            parents = _git(repository, "show", "-s", "--format=%P", merge_sha).stdout.decode().split()
            if parents != [target_sha, source_sha]:
                raise SyncMergeError("sync merge does not have the exact ordered parents")
            if not _clean(repository):
                raise SyncMergeError("sync merge left a dirty worktree")
            final_blob, final_bytes = _blob(repository, merge_sha, MATRIX_PATH)
            if final_blob != matrix_blob or final_bytes != matrix_bytes:
                raise SyncMergeError("sync merge changed the target matrix identity")
            for target_owned, expected_entry in target_owned_entries.items():
                if _tree_entry(repository, merge_sha, target_owned) != expected_entry:
                    raise SyncMergeError(
                        f"sync merge changed target-owned path {target_owned!r}"
                    )
            return {
                "schema_version": SCHEMA_VERSION,
                "status": "created",
                "target_branch": target_branch,
                "source_branch": source_branch,
                "target_sha": target_sha,
                "source_sha": source_sha,
                "merge_sha": merge_sha,
                "tree": _git(repository, "rev-parse", f"{merge_sha}^{{tree}}").stdout.decode().strip(),
                "matrix_blob": matrix_blob,
                "matrix_sha256": hashlib.sha256(matrix_bytes).hexdigest(),
                "target_owned": {
                    path: (
                        None
                        if entry is None
                        else {"mode": entry[0], "kind": entry[1], "oid": entry[2]}
                    )
                    for path, entry in sorted(target_owned_entries.items())
                },
            }
        except Exception:
            if merge_started:
                _abort(repository)
            raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--target-branch", required=True)
    parser.add_argument("--source-branch", required=True)
    args = parser.parse_args(argv)
    try:
        payload = create_sync_merge(
            args.repository,
            target_sha=args.target_sha,
            source_sha=args.source_sha,
            target_branch=args.target_branch,
            source_branch=args.source_branch,
        )
    except (MatrixError, OSError, SyncMergeError) as exc:
        print(f"release-sync merge error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(payload, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
