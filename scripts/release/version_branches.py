#!/usr/bin/env python3
"""Discover release branches from self-identifying branch-local matrices."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES,
    MatrixDocument,
    MatrixError,
    load_matrix,
    load_matrix_bytes,
    normalize_matrix_inventory,
    valid_branch_name,
)
from scripts.lib.secure_json import SecureJsonError, loads as secure_loads  # noqa: E402


class BranchDiscoveryError(ValueError):
    """Raised when a release-looking branch cannot be authenticated."""


@dataclass(frozen=True)
class ReleaseBranch:
    name: str
    commit: str
    tree: str
    matrix_blob: str
    matrix_sha256: str
    minecraft: str
    loaders: tuple[str, ...]
    java: tuple[int, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "commit": self.commit,
            "tree": self.tree,
            "matrix_blob": self.matrix_blob,
            "matrix_sha256": self.matrix_sha256,
            "minecraft": self.minecraft,
            "loaders": list(self.loaders),
            "java": list(self.java),
        }


def _pages_git(repository: Path, *args: str) -> bytes:
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(GIT_NO_REPLACE_OBJECTS="1", GIT_GRAFT_FILE=os.devnull,
        GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
    result = subprocess.run(["git", "-C", str(repository), *args], check=False,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=environment)
    if result.returncode:
        raise BranchDiscoveryError("Pages Git inspection failed: " + result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def inspect_pages_branch(repository: Path, *, branch: str, ref: str,
                         canonical_branch: str, scope: str) -> dict[str, object]:
    """Inspect immutable matrix bytes under an explicit Pages selection.

    Callers authenticate the advertised ref/head separately. This opt-in API does
    not select a run, infer a projection, inspect worktree sources or qualify any
    lane. Existing sync/default discovery interfaces remain schema1-only.
    """
    if scope not in ("unscoped", "legacy", "full"):
        raise BranchDiscoveryError("Pages requires explicit unscoped/legacy/full scope")
    return _inspect_pages_branch(repository, branch=branch, ref=ref,
                                 canonical_branch=canonical_branch, scope=scope)


def _inspect_pages_branch(repository: Path, *, branch: str, ref: str,
                          canonical_branch: str, scope: str | None = None,
                          enrolled_only: bool = False) -> dict[str, object] | None:
    if not valid_branch_name(branch) or not valid_branch_name(canonical_branch):
        raise BranchDiscoveryError("Pages branch identity is unsafe")
    if not isinstance(ref, str) or not ref:
        raise BranchDiscoveryError("Pages requires an exact ref")

    def oid(raw: bytes, label: str) -> str:
        value = raw.strip().decode("ascii", "strict")
        if len(value) != 40 or any(char not in "0123456789abcdef" for char in value):
            raise BranchDiscoveryError(f"Pages {label} is not one exact Git identity")
        return value

    try:
        head_command = ("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
        commit = oid(_pages_git(repository, *head_command), "commit")
        tree = oid(_pages_git(repository, "rev-parse", f"{commit}^{{tree}}"), "tree")
        path = "release/release-matrix.json"
        record = _pages_git(repository, "ls-tree", "-z", commit, "--", path)
        if not record and enrolled_only and branch != canonical_branch:
            return None
        metadata, separator, filename = record.rstrip(b"\0").partition(b"\t")
        fields = metadata.split()
        if (separator != b"\t" or filename != path.encode() or len(fields) != 3
                or fields[:2] != [b"100644", b"blob"]):
            raise BranchDiscoveryError("Pages matrix must be one regular non-executable Git blob")
        blob = oid(fields[2], "matrix blob")
        size = int(_pages_git(repository, "cat-file", "-s", blob))
        if not 0 < size <= MAX_MATRIX_BYTES:
            raise BranchDiscoveryError("Pages matrix blob exceeds its bounded size")
        raw = _pages_git(repository, "cat-file", "blob", blob)
        if (len(raw) != size
                or hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() != blob):
            raise BranchDiscoveryError("Pages matrix bytes differ from their Git blob identity")
        try:
            matrix = secure_loads(raw, label="Pages branch matrix", max_bytes=MAX_MATRIX_BYTES)
        except SecureJsonError:
            if enrolled_only and branch != canonical_branch:
                return None
            raise
        if enrolled_only and branch != canonical_branch:
            claim = matrix.get("branch") if isinstance(matrix, dict) else None
            if not isinstance(claim, dict) or claim.get("name") != branch or claim.get("role") != "release":
                return None
        inventory = normalize_matrix_inventory(matrix)
        document = MatrixDocument(inventory, json.dumps(matrix))
        if scope is None:
            scope = "unscoped" if inventory.schema_version == 1 else document.default_scope
        identity = matrix["branch"]
        role = "integration" if branch == canonical_branch else "release"
        if (identity["name"] != branch or identity["canonical"] != canonical_branch or identity["role"] != role):
            raise BranchDiscoveryError("Pages matrix branch/canonical/role identity is inconsistent")
        if (inventory.schema_version == 1) != (scope == "unscoped"):
            raise BranchDiscoveryError("Pages scope is incompatible with its matrix schema")
        key = lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader)
        lanes = sorted(document.select_lanes(scope="full" if scope == "unscoped" else scope), key=key)
        selected = [lane.identity.artifact_node for lane in lanes]
        result = {"name": branch, "commit": commit, "tree": tree, "matrix_blob": blob,
            "matrix_sha256": hashlib.sha256(raw).hexdigest(), "matrix_schema_version": inventory.schema_version,
            "minecraft_versions": sorted({lane.identity.minecraft for lane in lanes}, key=lambda version: tuple(map(int, version.split(".")))),
            "loaders": sorted({lane.identity.loader for lane in lanes}), "java": sorted({lane.artifact["java"] for lane in lanes}),
            "configured_nodes": [lane.identity.artifact_node for lane in sorted(inventory.lanes, key=key)],
            "scope": {"kind": scope, "selected_nodes": selected, "target_nodes": list(inventory.target_nodes),
                "migration_mode": inventory.migration_mode, "partial": set(selected) != set(inventory.target_nodes)}}
        if oid(_pages_git(repository, *head_command), "final commit") != commit:
            raise BranchDiscoveryError("Pages branch ref changed during inspection")
        return result
    except (MatrixError, SecureJsonError, OSError, UnicodeError, ValueError) as exc:
        if isinstance(exc, BranchDiscoveryError):
            raise
        raise BranchDiscoveryError(f"invalid Pages branch matrix: {exc}") from exc


def _pages_refs(repository: Path, remote: str) -> dict[str, str]:
    if not valid_branch_name(remote) or "/" in remote:
        raise BranchDiscoveryError("Pages remote name is unsafe")
    prefix = f"refs/remotes/{remote}/"
    raw = _pages_git(repository, "for-each-ref", "--count=1002",
        "--format=%(refname)%00%(objectname)%00%(objecttype)%00%(symref)", prefix)
    refs = {}
    for record in raw.decode("utf-8", "strict").splitlines():
        fields = record.split("\0")
        if len(fields) != 4 or not fields[0].startswith(prefix):
            raise BranchDiscoveryError("Pages remote ref inventory is malformed")
        ref, commit, kind, symbolic = fields
        if ref == prefix + "HEAD":
            continue
        branch = ref[len(prefix):]
        if (not valid_branch_name(branch) or branch in refs or kind != "commit" or symbolic
                or len(commit) != 40 or any(char not in "0123456789abcdef" for char in commit)):
            raise BranchDiscoveryError("Pages remote ref identity is invalid")
        refs[branch] = commit
    if len(refs) > 1000:
        raise BranchDiscoveryError("Pages remote ref inventory exceeds 1000 branches")
    return refs


def discover_pages_repository(repository: Path, *, remote: str, canonical_branch: str,
                              include_integration: bool = False) -> list[dict[str, object]]:
    """Inspect each enrolled head using that immutable matrix's default dispatch.

    The caller authenticates remote advertisements separately. No run/projection,
    published authority or game qualification follows from this local inventory.
    """
    if not valid_branch_name(canonical_branch) or type(include_integration) is not bool:
        raise BranchDiscoveryError("Pages canonical branch or integration selector is invalid")
    try:
        refs = _pages_refs(repository, remote)
        if canonical_branch not in refs:
            raise BranchDiscoveryError("Pages canonical branch is absent from the remote inventory")
        results = []
        for branch, commit in sorted(refs.items()):
            row = _inspect_pages_branch(repository, branch=branch, ref=commit,
                canonical_branch=canonical_branch, enrolled_only=True)
            if row is not None and (branch != canonical_branch or include_integration):
                results.append(row)
        if _pages_refs(repository, remote) != refs:
            raise BranchDiscoveryError("Pages remote inventory changed during discovery")
        return results
    except (OSError, UnicodeError, ValueError) as exc:
        if isinstance(exc, BranchDiscoveryError):
            raise
        raise BranchDiscoveryError(f"invalid Pages remote inventory: {exc}") from exc


def _git(repository: Path, *args: str, check: bool = True) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repository), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise BranchDiscoveryError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout


def _has_matrix(repository: Path, ref: str) -> bool:
    result = subprocess.run(
        [
            "git",
            "-C",
            str(repository),
            "cat-file",
            "-e",
            f"{ref}:release/release-matrix.json",
        ],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def _claims_enrollment(raw: bytes, *, branch: str, canonical_branch: str) -> bool:
    """Return whether inert matrix bytes claim this exact branch as protected.

    Feature branches commonly carry an unchanged integration/release matrix.  An
    identity mismatch therefore excludes them.  Once a branch self-identifies as
    the canonical or an enrolled release branch, full schema validation is
    mandatory and fail-closed in ``inspect_branch``.
    """

    try:
        candidate = secure_loads(
            raw, label=f"branch {branch!r} release-matrix claim", max_bytes=MAX_MATRIX_BYTES
        )
    except SecureJsonError as exc:
        if branch == canonical_branch:
            raise BranchDiscoveryError(
                f"canonical branch {branch!r} has an unreadable release matrix: {exc}"
            ) from exc
        return False
    if not isinstance(candidate, dict) or not isinstance(candidate.get("branch"), dict):
        if branch == canonical_branch:
            raise BranchDiscoveryError(
                f"canonical branch {branch!r} has no matrix branch identity"
            )
        return False
    identity = candidate["branch"]
    return branch == canonical_branch or (
        identity.get("name") == branch and identity.get("role") == "release"
    )


def _matrix_from_git(
    repository: Path, ref: str, *, branch: str
) -> tuple[dict[str, object], bytes, str, str, str]:
    if not valid_branch_name(branch):
        raise BranchDiscoveryError(f"unsafe branch name {branch!r}")
    object_name = f"{ref}:release/release-matrix.json"
    tree_record = _git(
        repository,
        "ls-tree",
        "-z",
        ref,
        "--",
        "release/release-matrix.json",
        check=False,
    )
    fields = tree_record.rstrip(b"\0").partition(b"\t")
    metadata = fields[0].split()
    if (
        fields[1] != b"\t"
        or fields[2] != b"release/release-matrix.json"
        or len(metadata) != 3
        or metadata[0] != b"100644"
        or metadata[1] != b"blob"
    ):
        raise BranchDiscoveryError(
            f"release branch {branch!r} has no regular release matrix blob"
        )
    matrix_blob = metadata[2].decode("ascii", "strict")
    size_raw = _git(repository, "cat-file", "-s", object_name).strip()
    try:
        size = int(size_raw)
    except ValueError as exc:
        raise BranchDiscoveryError(
            f"release branch {branch!r} matrix has an invalid blob size"
        ) from exc
    if size <= 0 or size > MAX_MATRIX_BYTES:
        raise BranchDiscoveryError(
            f"release branch {branch!r} matrix size is outside 1..{MAX_MATRIX_BYTES}"
        )
    raw = _git(repository, "show", object_name)
    if len(raw) != size:
        raise BranchDiscoveryError(
            f"release branch {branch!r} matrix changed during inspection"
        )
    try:
        matrix = load_matrix_bytes(raw)
    except MatrixError as exc:
        raise BranchDiscoveryError(
            f"release branch {branch!r} has an invalid matrix: {exc}"
        ) from exc
    if matrix["branch"]["name"] != branch:
        raise BranchDiscoveryError(
            f"release branch {branch!r} matrix self-identifies as "
            f"{matrix['branch']['name']!r}"
        )
    commit = _git(repository, "rev-parse", f"{ref}^{{commit}}").decode().strip()
    tree = _git(repository, "rev-parse", f"{ref}^{{tree}}").decode().strip()
    if len(commit) != 40 or any(char not in "0123456789abcdef" for char in commit):
        raise BranchDiscoveryError(f"release branch {branch!r} has an invalid commit")
    if len(tree) != 40 or any(char not in "0123456789abcdef" for char in tree):
        raise BranchDiscoveryError(f"release branch {branch!r} has an invalid tree")
    return matrix, raw, commit, tree, matrix_blob


def inspect_branch(
    repository: Path,
    *,
    branch: str,
    ref: str,
    canonical_branch: str,
) -> ReleaseBranch:
    matrix, raw, commit, tree, matrix_blob = _matrix_from_git(
        repository, ref, branch=branch
    )
    if matrix["branch"]["canonical"] != canonical_branch:
        raise BranchDiscoveryError(
            f"release branch {branch!r} names a different canonical branch"
        )
    expected_role = "integration" if branch == canonical_branch else "release"
    if matrix["branch"]["role"] != expected_role:
        raise BranchDiscoveryError(
            f"release branch {branch!r} has role {matrix['branch']['role']!r}, "
            f"expected {expected_role!r}"
        )
    artifacts = matrix["artifacts"]
    return ReleaseBranch(
        name=branch,
        commit=commit,
        tree=tree,
        matrix_blob=matrix_blob,
        matrix_sha256=hashlib.sha256(raw).hexdigest(),
        minecraft=artifacts[0]["minecraft"],
        loaders=tuple(sorted(row["loader"] for row in artifacts)),
        java=tuple(sorted({row["java"] for row in artifacts})),
    )


def discover_from_snapshots(
    snapshots: Mapping[str, bytes],
    *,
    integration_branch: str,
    commits: Mapping[str, str] | None = None,
    include_integration: bool = False,
) -> list[ReleaseBranch]:
    """Pure helper used by portability tests and protected controllers."""

    results: list[ReleaseBranch] = []
    commit_map = commits or {}
    for branch in sorted(snapshots):
        if not _claims_enrollment(
            snapshots[branch], branch=branch, canonical_branch=integration_branch
        ):
            continue
        try:
            matrix = load_matrix_bytes(snapshots[branch])
        except MatrixError as exc:
            raise BranchDiscoveryError(
                f"release branch {branch!r} has an invalid matrix: {exc}"
            ) from exc
        if matrix["branch"]["name"] != branch:
            raise BranchDiscoveryError(f"release branch {branch!r} matrix self-identity mismatch")
        if matrix["branch"]["canonical"] != integration_branch:
            raise BranchDiscoveryError(
                f"release branch {branch!r} integration identity mismatch"
            )
        if branch == integration_branch and not include_integration:
            continue
        expected_role = "integration" if branch == integration_branch else "release"
        if matrix["branch"]["role"] != expected_role:
            raise BranchDiscoveryError(
                f"release branch {branch!r} role mismatch"
            )
        if expected_role == "release" and not matrix["branch"]["sync"]["enabled"]:
            raise BranchDiscoveryError(
                f"release branch {branch!r} is not enrolled for synchronization"
            )
        commit = commit_map.get(branch, "0" * 40)
        artifacts = matrix["artifacts"]
        results.append(
            ReleaseBranch(
                name=branch,
                commit=commit,
                tree="0" * 40,
                matrix_blob=hashlib.sha1(
                    b"blob " + str(len(snapshots[branch])).encode() + b"\0"
                    + snapshots[branch]
                ).hexdigest(),
                matrix_sha256=hashlib.sha256(snapshots[branch]).hexdigest(),
                minecraft=artifacts[0]["minecraft"],
                loaders=tuple(sorted(row["loader"] for row in artifacts)),
                java=tuple(sorted({row["java"] for row in artifacts})),
            )
        )
    return results


def remote_branch_names(repository: Path, remote: str) -> list[str]:
    prefix = f"refs/remotes/{remote}/"
    raw = _git(
        repository,
        "for-each-ref",
        "--format=%(refname)",
        f"refs/remotes/{remote}",
    ).decode("utf-8", "strict")
    names: list[str] = []
    for ref in raw.splitlines():
        if ref == f"{prefix}HEAD" or not ref.startswith(prefix):
            continue
        names.append(ref[len(prefix) :])
    return names


def discover_repository(
    repository: Path,
    *,
    remote: str,
    integration_branch: str,
    include_integration: bool = False,
    names: Iterable[str] | None = None,
) -> list[ReleaseBranch]:
    branch_names = list(names) if names is not None else remote_branch_names(repository, remote)
    results: list[ReleaseBranch] = []
    saw_integration = False
    for branch in sorted(set(branch_names)):
        if not valid_branch_name(branch):
            raise BranchDiscoveryError(f"remote advertised unsafe branch name {branch!r}")
        ref = f"refs/remotes/{remote}/{branch}"
        matrix_path = f"{ref}:release/release-matrix.json"
        if not _has_matrix(repository, ref):
            if branch == integration_branch:
                raise BranchDiscoveryError(
                    f"canonical branch {branch!r} has no release matrix"
                )
            continue
        raw = _git(repository, "show", matrix_path)
        if not _claims_enrollment(
            raw, branch=branch, canonical_branch=integration_branch
        ):
            continue
        inspected = inspect_branch(
            repository,
            branch=branch,
            ref=ref,
            canonical_branch=integration_branch,
        )
        saw_integration = saw_integration or branch == integration_branch
        if branch != integration_branch or include_integration:
            results.append(inspected)
    if integration_branch in set(branch_names) and not saw_integration:
        raise BranchDiscoveryError(
            f"canonical branch {integration_branch!r} was not authenticated"
        )
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--target")
    parser.add_argument("--include-integration", action="store_true")
    parser.add_argument("--objects", action="store_true")
    parser.add_argument("--pages", action="store_true")
    parser.add_argument("--canonical-branch")
    args = parser.parse_args(argv)
    try:
        if args.pages:
            branches = discover_pages_repository(args.repository, remote=args.remote,
                canonical_branch=args.canonical_branch,
                include_integration=args.include_integration or args.target == args.canonical_branch)
            if args.target is not None:
                if not valid_branch_name(args.target):
                    raise BranchDiscoveryError("Pages target branch is unsafe")
                branches = [row for row in branches if row["name"] == args.target]
                if not branches:
                    raise BranchDiscoveryError("Pages target is not an enrolled branch")
            output = branches if args.objects else [row["name"] for row in branches]
            print(json.dumps(output, sort_keys=True, separators=(",", ":")))
            return 0
        if args.canonical_branch is not None:
            raise BranchDiscoveryError("--canonical-branch requires the opt-in --pages mode")
        local = load_matrix(args.matrix)
        integration = local["branch"]["canonical"]
        if args.target:
            branches = [
                inspect_branch(
                    args.repository,
                    branch=args.target,
                    ref=f"refs/remotes/{args.remote}/{args.target}",
                    canonical_branch=integration,
                )
            ]
        else:
            branches = discover_repository(
                args.repository,
                remote=args.remote,
                integration_branch=integration,
                include_integration=args.include_integration,
            )
    except (MatrixError, BranchDiscoveryError) as exc:
        print(f"release branch discovery error: {exc}", file=sys.stderr)
        return 2
    output: object = (
        [branch.as_dict() for branch in branches]
        if args.objects
        else [branch.name for branch in branches]
    )
    print(json.dumps(output, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
