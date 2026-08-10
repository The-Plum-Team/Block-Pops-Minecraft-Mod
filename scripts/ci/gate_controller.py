#!/usr/bin/env python3
"""Authenticate release-sync topology and select exact GitHub Actions runs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES,
    MatrixError,
    load_matrix_bytes,
    valid_branch_name,
)
from scripts.ci.loader_bootstrap import (  # noqa: E402
    LoaderBootstrapError,
    validate_commit as validate_loader_bootstrap_commit,
)

MATRIX_PATH = "release/release-matrix.json"
VERIFICATION_PATH = "gradle/verification-metadata.xml"
KNOWN_LOADER_ROOTS = frozenset({"fabric", "forge", "neoforge"})
MAX_API_BYTES = 16 * 1024 * 1024
MAX_RUNS = 1000
WORKFLOWS = frozenset({"build-gate.yml", "on-demand-e2e.yml"})
PROTECTED_PATHS = (
    ".github/actions",
    ".github/workflows",
    "build.gradle",
    "common/build.gradle",
    "e2e",
    "gradle.properties",
    "gradle/e2e-harness-conventions.gradle",
    "gradle/wrapper",
    "gradlew",
    "scripts/ci",
    "scripts/lib",
    "scripts/pages",
    "scripts/release",
    "scripts/visual",
    "settings.gradle",
    "site",
    "common/src/e2e",
)
VERSION_SPECIFIC_PATHS = frozenset(
    {
        "common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java",
        "e2e/server-template/datapack/pack.mcmeta",
    }
)


class GateControllerError(ValueError):
    """Raised when protected gate evidence cannot be authenticated."""


def _git(
    repository: Path,
    *arguments: str,
    accepted: Iterable[int] = (0,),
) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
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
        raise GateControllerError(detail or f"git {' '.join(arguments)} failed")
    return result.stdout


def _oid_length(repository: Path) -> int:
    value = _git(repository, "rev-parse", "--show-object-format").decode().strip()
    lengths = {"sha1": 40, "sha256": 64}
    if value not in lengths:
        raise GateControllerError(f"unsupported Git object format {value!r}")
    return lengths[value]


def _oid(value: Any, length: int, label: str) -> str:
    if not isinstance(value, str) or re.fullmatch(rf"[0-9a-f]{{{length}}}", value) is None:
        raise GateControllerError(f"{label} is not an exact lowercase object id")
    return value


def _blob(repository: Path, commit: str) -> tuple[str, bytes]:
    raw = _git(repository, "ls-tree", "-z", commit, "--", MATRIX_PATH)
    metadata, separator, path = raw.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if (
        separator != b"\t"
        or path != MATRIX_PATH.encode()
        or len(fields) != 3
        or fields[:2] != [b"100644", b"blob"]
    ):
        raise GateControllerError(f"{commit} has no regular release matrix blob")
    object_name = f"{commit}:{MATRIX_PATH}"
    raw_size = _git(repository, "cat-file", "-s", object_name).decode().strip()
    try:
        size = int(raw_size)
    except ValueError as exc:
        raise GateControllerError(f"{commit} has an invalid matrix blob size") from exc
    if size <= 0 or size > MAX_MATRIX_BYTES:
        raise GateControllerError(
            f"{commit} matrix size is outside 1..{MAX_MATRIX_BYTES}"
        )
    payload = _git(repository, "show", object_name)
    if len(payload) != size:
        raise GateControllerError(f"{commit} matrix changed during inspection")
    return fields[2].decode(), payload


def _tree_entry(repository: Path, commit: str, path: str) -> tuple[str, str, str] | None:
    raw = _git(repository, "ls-tree", "-z", commit, "--", path)
    if not raw:
        return None
    metadata, separator, raw_path = raw.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if separator != b"\t" or raw_path != path.encode() or len(fields) != 3:
        raise GateControllerError(f"{commit} has a malformed tree entry for {path}")
    mode, kind, oid = (field.decode("ascii", "strict") for field in fields)
    if (mode, kind) not in {("100644", "blob"), ("040000", "tree")}:
        raise GateControllerError(f"{commit} has an unsafe tree entry for {path}")
    return mode, kind, oid


def validate_topology(
    repository: Path,
    *,
    protected_sha: str,
    target_sha: str,
    head_sha: str,
    source_branch: str,
    target_branch: str,
) -> dict[str, str]:
    repository = repository.resolve()
    length = _oid_length(repository)
    protected_sha = _oid(protected_sha, length, "protected SHA")
    target_sha = _oid(target_sha, length, "target SHA")
    head_sha = _oid(head_sha, length, "candidate SHA")
    if not valid_branch_name(source_branch) or not valid_branch_name(target_branch):
        raise GateControllerError("sync topology has an unsafe branch identity")
    for commit, label in (
        (protected_sha, "protected SHA"),
        (target_sha, "target SHA"),
        (head_sha, "candidate SHA"),
    ):
        resolved = _git(repository, "rev-parse", "--verify", f"{commit}^{{commit}}").decode().strip()
        if resolved != commit:
            raise GateControllerError(f"{label} did not resolve to itself")
    parents = _git(repository, "show", "-s", "--format=%P", head_sha).decode().split()
    if parents != [target_sha, protected_sha]:
        raise GateControllerError("candidate must be one two-parent merge over exact target/protected heads")
    target_blob, target_bytes = _blob(repository, target_sha)
    head_blob, head_bytes = _blob(repository, head_sha)
    if target_blob != head_blob or target_bytes != head_bytes:
        raise GateControllerError("candidate did not retain the exact target matrix blob")
    try:
        matrix = load_matrix_bytes(head_bytes)
    except MatrixError as exc:
        raise GateControllerError(f"candidate target matrix is invalid: {exc}") from exc
    branch = matrix["branch"]
    if (
        branch["role"] != "release"
        or branch["name"] != target_branch
        or branch["canonical"] != source_branch
        or branch["sync"] != {"enabled": True, "source": source_branch}
    ):
        raise GateControllerError("candidate matrix does not authenticate this exact sync pair")
    _, protected_matrix_bytes = _blob(repository, protected_sha)
    try:
        protected_matrix = load_matrix_bytes(protected_matrix_bytes)
    except MatrixError as exc:
        raise GateControllerError(f"protected matrix is invalid: {exc}") from exc
    protected_branch = protected_matrix["branch"]
    if (
        protected_branch["role"] != "integration"
        or protected_branch["name"] != source_branch
        or protected_branch["canonical"] != source_branch
    ):
        raise GateControllerError("protected matrix does not authenticate the canonical branch")
    active_loaders = {row["loader"] for row in matrix["artifacts"]}
    protected_loaders = {row["loader"] for row in protected_matrix["artifacts"]}
    branch_specific_loaders = sorted(
        KNOWN_LOADER_ROOTS - (active_loaders & protected_loaders)
    )
    target_owned = (
        VERIFICATION_PATH,
        *sorted(VERSION_SPECIFIC_PATHS),
        *branch_specific_loaders,
    )
    retained: dict[str, str | None] = {}
    for path in target_owned:
        expected_entry = _tree_entry(repository, target_sha, path)
        candidate_entry = _tree_entry(repository, head_sha, path)
        if path == VERIFICATION_PATH and (
            expected_entry is None or expected_entry[:2] != ("100644", "blob")
        ):
            raise GateControllerError("target has no regular dependency-verification metadata")
        if candidate_entry != expected_entry:
            raise GateControllerError(f"candidate changed target-owned path {path!r}")
        retained[path] = None if expected_entry is None else expected_entry[2]
    return {
        "protected_sha": protected_sha,
        "target_sha": target_sha,
        "head_sha": head_sha,
        "tree": _git(repository, "rev-parse", f"{head_sha}^{{tree}}").decode().strip(),
        "matrix_blob": head_blob,
        "matrix_sha256": hashlib.sha256(head_bytes).hexdigest(),
        "target_owned_sha256": hashlib.sha256(
            json.dumps(retained, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
    }


def validate_controller_parity(
    repository: Path, *, protected_sha: str, candidate_sha: str
) -> tuple[str, ...]:
    repository = repository.resolve()
    length = _oid_length(repository)
    protected_sha = _oid(protected_sha, length, "protected SHA")
    candidate_sha = _oid(candidate_sha, length, "candidate SHA")
    _, protected_matrix_bytes = _blob(repository, protected_sha)
    _, candidate_matrix_bytes = _blob(repository, candidate_sha)
    try:
        protected_matrix = load_matrix_bytes(protected_matrix_bytes)
        candidate_matrix = load_matrix_bytes(candidate_matrix_bytes)
    except MatrixError as exc:
        raise GateControllerError(f"cannot derive loader controller parity: {exc}") from exc
    protected_loaders = {row["loader"] for row in protected_matrix["artifacts"]}
    candidate_loaders = {row["loader"] for row in candidate_matrix["artifacts"]}
    loader_paths = tuple(
        path
        for loader in sorted(protected_loaders & candidate_loaders)
        for path in (f"{loader}/build.gradle", f"{loader}/src/e2e")
    )
    paths = (*PROTECTED_PATHS, *loader_paths)
    for path in paths:
        _git(repository, "cat-file", "-e", f"{protected_sha}:{path}")
        _git(repository, "cat-file", "-e", f"{candidate_sha}:{path}")
    raw = _git(
        repository,
        "diff",
        "--no-ext-diff",
        "--name-only",
        "-z",
        protected_sha,
        candidate_sha,
        "--",
        *paths,
    )
    try:
        changed = tuple(
            sorted(item.decode("utf-8", "strict") for item in raw.split(b"\0") if item)
        )
    except UnicodeDecodeError as exc:
        raise GateControllerError("protected controller diff contains non-UTF-8 paths") from exc
    unexpected = tuple(path for path in changed if path not in VERSION_SPECIFIC_PATHS)
    if unexpected:
        raise GateControllerError(
            "candidate protected controllers differ from canonical: "
            + ", ".join(unexpected)
        )
    try:
        validate_loader_bootstrap_commit(
            repository,
            head_sha=candidate_sha,
            contract_sha=protected_sha,
        )
    except LoaderBootstrapError as exc:
        raise GateControllerError(
            f"candidate loader bootstrap is not authenticated: {exc}"
        ) from exc
    return paths


def _read_json(path: Path, label: str) -> Any:
    try:
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or path.is_symlink():
            raise GateControllerError(f"{label} must be a regular non-symlink file")
        if metadata.st_size <= 0 or metadata.st_size > MAX_API_BYTES:
            raise GateControllerError(f"{label} size is outside 1..{MAX_API_BYTES}")
        raw = path.read_bytes()
        if len(raw) != metadata.st_size:
            raise GateControllerError(f"{label} changed while being read")
        return json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicates,
            parse_constant=_reject_nonfinite,
        )
    except GateControllerError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise GateControllerError(f"cannot read {label}: {exc}") from exc


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _runs(document: Any) -> list[dict[str, Any]]:
    pages = document if isinstance(document, list) else [document]
    result: list[dict[str, Any]] = []
    for page in pages:
        if not isinstance(page, dict) or not isinstance(page.get("workflow_runs"), list):
            raise GateControllerError("Actions runs response has an invalid shape")
        for run in page["workflow_runs"]:
            if not isinstance(run, dict):
                raise GateControllerError("Actions run must be an object")
            result.append(run)
    if not result or len(result) > MAX_RUNS:
        raise GateControllerError(f"Actions response must contain 1..{MAX_RUNS} runs")
    return result


def _timestamp(value: Any) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise GateControllerError("Actions run created_at must be UTC RFC3339")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise GateControllerError("Actions run created_at is invalid") from exc


def select_newest_exact_run(
    document: Any,
    *,
    workflow: str,
    branch: str,
    sha: str,
    repository: str,
) -> dict[str, Any]:
    if workflow not in WORKFLOWS:
        raise GateControllerError("unsupported protected workflow")
    if not valid_branch_name(branch) or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
        raise GateControllerError("exact run selector has an unsafe branch/SHA")
    if not isinstance(repository, str) or repository.count("/") != 1:
        raise GateControllerError("exact run selector has an invalid repository")
    path = f".github/workflows/{workflow}"
    candidates: list[tuple[datetime, int, dict[str, Any]]] = []
    identities: set[tuple[int, int]] = set()
    for run in _runs(document):
        head_repository = run.get("head_repository")
        if (
            run.get("path") != path
            or run.get("event") != "workflow_dispatch"
            or run.get("head_branch") != branch
            or run.get("head_sha") != sha
            or not isinstance(head_repository, dict)
            or head_repository.get("full_name") != repository
        ):
            continue
        run_id = run.get("id")
        attempt = run.get("run_attempt")
        if (
            isinstance(run_id, bool)
            or not isinstance(run_id, int)
            or run_id <= 0
            or isinstance(attempt, bool)
            or not isinstance(attempt, int)
            or attempt <= 0
        ):
            raise GateControllerError("matching Actions run has an invalid id/attempt")
        identity = (run_id, attempt)
        if identity in identities:
            raise GateControllerError("Actions response repeats a matching run/attempt")
        identities.add(identity)
        status = run.get("status")
        conclusion = run.get("conclusion")
        if status not in {"queued", "in_progress", "completed", "pending", "waiting", "requested"}:
            raise GateControllerError("matching Actions run has an invalid status")
        if status == "completed" and conclusion not in {
            "success",
            "failure",
            "cancelled",
            "timed_out",
            "action_required",
            "neutral",
            "skipped",
            "stale",
            "startup_failure",
        }:
            raise GateControllerError("completed Actions run has an invalid conclusion")
        candidates.append((_timestamp(run.get("created_at")), run_id, run))
    if not candidates:
        raise GateControllerError("no exact workflow_dispatch run exists for this head")
    selected = max(candidates, key=lambda item: (item[0], item[1]))[2]
    return {
        "id": selected["id"],
        "run_attempt": selected["run_attempt"],
        "created_at": selected["created_at"],
        "status": selected["status"],
        "conclusion": selected.get("conclusion"),
        "workflow": workflow,
        "branch": branch,
        "sha": sha,
        "repository": repository,
    }


def branch_token(branch: str) -> str:
    if not valid_branch_name(branch):
        raise GateControllerError("cannot encode an unsafe branch identity")
    return hashlib.sha256(branch.encode("utf-8")).hexdigest()[:24]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    topology = commands.add_parser("topology")
    topology.add_argument("--repository", type=Path, default=Path.cwd())
    topology.add_argument("--protected-sha", required=True)
    topology.add_argument("--target-sha", required=True)
    topology.add_argument("--head-sha", required=True)
    topology.add_argument("--source-branch", required=True)
    topology.add_argument("--target-branch", required=True)
    parity = commands.add_parser("parity")
    parity.add_argument("--repository", type=Path, default=Path.cwd())
    parity.add_argument("--protected-sha", required=True)
    parity.add_argument("--candidate-sha", required=True)
    select = commands.add_parser("select-run")
    select.add_argument("--runs", type=Path, required=True)
    select.add_argument("--workflow", choices=sorted(WORKFLOWS), required=True)
    select.add_argument("--branch", required=True)
    select.add_argument("--sha", required=True)
    select.add_argument("--repository-name", required=True)
    token = commands.add_parser("branch-token")
    token.add_argument("--branch", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "topology":
            output: Any = validate_topology(
                args.repository,
                protected_sha=args.protected_sha,
                target_sha=args.target_sha,
                head_sha=args.head_sha,
                source_branch=args.source_branch,
                target_branch=args.target_branch,
            )
        elif args.command == "parity":
            output = {
                "paths": list(
                    validate_controller_parity(
                        args.repository,
                        protected_sha=args.protected_sha,
                        candidate_sha=args.candidate_sha,
                    )
                )
            }
        elif args.command == "select-run":
            output = select_newest_exact_run(
                _read_json(args.runs, "Actions runs response"),
                workflow=args.workflow,
                branch=args.branch,
                sha=args.sha,
                repository=args.repository_name,
            )
        else:
            output = branch_token(args.branch)
    except (GateControllerError, MatrixError, OSError) as exc:
        print(f"gate controller error: {exc}", file=sys.stderr)
        return 2
    if isinstance(output, str):
        print(output)
    else:
        print(json.dumps(output, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
