#!/usr/bin/env python3
"""Authenticate active loader build scripts and packaged-E2E entrypoints.

The release matrix selects loaders; this protected contract pins the executable
bootstrap for every supported loader without duplicating a branch/version list.
Validation reads immutable Git objects at one exact commit, never the mutable
working tree.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    loads as secure_loads,
    require_object,
)
from scripts.release.matrix import (  # noqa: E402
    KNOWN_LOADERS,
    MAX_MATRIX_BYTES,
    MatrixError,
    load_matrix_bytes,
)

SCHEMA_VERSION = 1
MATRIX_PATH = "release/release-matrix.json"
CONTRACT_PATH = "e2e/loader-bootstrap-contract.json"
MAX_CONTRACT_BYTES = 256 * 1024
MAX_BOOTSTRAP_FILES = 64
MAX_BOOTSTRAP_FILE_BYTES = 4 * 1024 * 1024
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_OID = re.compile(r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
HARNESS_BINDING = "apply from: rootProject.file('gradle/e2e-harness-conventions.gradle')"


class LoaderBootstrapError(ValueError):
    """Raised when executable loader bootstrap does not match protected policy."""


@dataclass(frozen=True)
class LoaderBootstrap:
    build_sha256: str
    files: dict[str, str]


@dataclass(frozen=True)
class LoaderBootstrapContract:
    loaders: dict[str, LoaderBootstrap]
    sha256: str


def _git(repository: Path, *arguments: str, accepted: Iterable[int] = (0,)) -> bytes:
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
        raise LoaderBootstrapError(detail or f"git {' '.join(arguments)} failed")
    return result.stdout


def _exact_commit(repository: Path, value: str, label: str) -> str:
    if not isinstance(value, str) or GIT_OID.fullmatch(value) is None:
        raise LoaderBootstrapError(f"{label} must be one exact lowercase Git object id")
    resolved = _git(repository, "rev-parse", "--verify", f"{value}^{{commit}}").decode().strip()
    if resolved != value:
        raise LoaderBootstrapError(f"{label} did not resolve to itself")
    return value


def _blob(repository: Path, commit: str, path: str, *, maximum: int) -> bytes:
    raw = _git(repository, "ls-tree", "-z", commit, "--", path)
    metadata, separator, raw_path = raw.rstrip(b"\0").partition(b"\t")
    fields = metadata.split()
    if (
        separator != b"\t"
        or raw_path != path.encode("utf-8")
        or len(fields) != 3
        or fields[:2] != [b"100644", b"blob"]
    ):
        raise LoaderBootstrapError(f"{commit} has no regular non-executable {path} blob")
    size_raw = _git(repository, "cat-file", "-s", fields[2].decode()).decode().strip()
    try:
        size = int(size_raw)
    except ValueError as exc:
        raise LoaderBootstrapError(f"{path} has an invalid Git blob size") from exc
    if size <= 0 or size > maximum:
        raise LoaderBootstrapError(f"{path} size is outside 1..{maximum}")
    payload = _git(repository, "cat-file", "blob", fields[2].decode())
    if len(payload) != size:
        raise LoaderBootstrapError(f"{path} changed while reading its immutable blob")
    return payload


def _object_blob(repository: Path, oid: str, path: str) -> bytes:
    size_raw = _git(repository, "cat-file", "-s", oid).decode().strip()
    try:
        size = int(size_raw)
    except ValueError as exc:
        raise LoaderBootstrapError(f"{path} has an invalid Git blob size") from exc
    if size <= 0 or size > MAX_BOOTSTRAP_FILE_BYTES:
        raise LoaderBootstrapError(
            f"{path} size is outside 1..{MAX_BOOTSTRAP_FILE_BYTES}"
        )
    payload = _git(repository, "cat-file", "blob", oid)
    if len(payload) != size:
        raise LoaderBootstrapError(f"{path} changed while reading its immutable blob")
    return payload


def _bootstrap_path(value: Any, loader: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise LoaderBootstrapError(f"{loader} bootstrap path is invalid")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or value != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
        or not value.startswith(f"{loader}/src/e2e/")
    ):
        raise LoaderBootstrapError(f"{loader} bootstrap path is not canonical: {value!r}")
    return value


def load_contract_bytes(payload: bytes) -> LoaderBootstrapContract:
    try:
        document = require_object(
            secure_loads(
                payload,
                label="loader bootstrap contract",
                max_bytes=MAX_CONTRACT_BYTES,
            ),
            label="loader bootstrap contract",
            required={"schema_version", "loaders"},
        )
    except SecureJsonError as exc:
        raise LoaderBootstrapError(str(exc)) from exc
    if document["schema_version"] != SCHEMA_VERSION:
        raise LoaderBootstrapError(
            f"loader bootstrap contract schema_version must be {SCHEMA_VERSION}"
        )
    raw_loaders = document["loaders"]
    if not isinstance(raw_loaders, dict) or set(raw_loaders) != set(KNOWN_LOADERS):
        raise LoaderBootstrapError("loader bootstrap contract must cover every known loader")
    loaders: dict[str, LoaderBootstrap] = {}
    for loader in sorted(KNOWN_LOADERS):
        try:
            record = require_object(
                raw_loaders[loader],
                label=f"{loader} bootstrap",
                required={"build_sha256", "files"},
            )
        except SecureJsonError as exc:
            raise LoaderBootstrapError(str(exc)) from exc
        build_sha = record["build_sha256"]
        if not isinstance(build_sha, str) or SHA256.fullmatch(build_sha) is None:
            raise LoaderBootstrapError(f"{loader} build_sha256 is invalid")
        raw_files = record["files"]
        if (
            not isinstance(raw_files, dict)
            or not 2 <= len(raw_files) <= MAX_BOOTSTRAP_FILES
        ):
            raise LoaderBootstrapError(f"{loader} bootstrap must bind 2..{MAX_BOOTSTRAP_FILES} files")
        files: dict[str, str] = {}
        for raw_path, raw_digest in raw_files.items():
            path = _bootstrap_path(raw_path, loader)
            if not isinstance(raw_digest, str) or SHA256.fullmatch(raw_digest) is None:
                raise LoaderBootstrapError(f"{loader} bootstrap digest is invalid for {path}")
            files[path] = raw_digest
        if not any("/java/" in path and path.endswith(".java") for path in files) or not any(
            "/resources/" in path for path in files
        ):
            raise LoaderBootstrapError(
                f"{loader} bootstrap must bind Java entrypoint and loader metadata resources"
            )
        loaders[loader] = LoaderBootstrap(build_sha, files)
    return LoaderBootstrapContract(loaders, hashlib.sha256(payload).hexdigest())


def _tree_entries(repository: Path, commit: str, loader: str) -> dict[str, str]:
    raw = _git(
        repository,
        "ls-tree",
        "-r",
        "-z",
        "--full-tree",
        commit,
        "--",
        f"{loader}/src/e2e",
    )
    result: dict[str, str] = {}
    for entry in (item for item in raw.split(b"\0") if item):
        try:
            metadata, path_raw = entry.split(b"\t", 1)
            mode, kind, oid = metadata.decode("ascii").split(" ")
            path = path_raw.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise LoaderBootstrapError(f"{loader} bootstrap tree is malformed") from exc
        _bootstrap_path(path, loader)
        if path in result:
            raise LoaderBootstrapError(f"{loader} bootstrap tree repeats {path}")
        if mode != "100644" or kind != "blob" or GIT_OID.fullmatch(oid) is None:
            raise LoaderBootstrapError(
                f"{loader} bootstrap must contain only non-executable regular blobs: {path}"
            )
        result[path] = oid
        if len(result) > MAX_BOOTSTRAP_FILES:
            raise LoaderBootstrapError(
                f"{loader} bootstrap tree exceeds {MAX_BOOTSTRAP_FILES} files"
            )
    return result


def validate_commit(
    repository: Path,
    *,
    head_sha: str,
    contract_sha: str | None = None,
) -> dict[str, Any]:
    repository = repository.resolve()
    head_sha = _exact_commit(repository, head_sha, "loader bootstrap head")
    contract_sha = _exact_commit(
        repository,
        head_sha if contract_sha is None else contract_sha,
        "loader bootstrap contract commit",
    )
    matrix_bytes = _blob(
        repository,
        head_sha,
        MATRIX_PATH,
        maximum=MAX_MATRIX_BYTES,
    )
    contract_bytes = _blob(
        repository,
        contract_sha,
        CONTRACT_PATH,
        maximum=MAX_CONTRACT_BYTES,
    )
    try:
        matrix = load_matrix_bytes(matrix_bytes)
    except MatrixError as exc:
        raise LoaderBootstrapError(f"release matrix is invalid: {exc}") from exc
    contract = load_contract_bytes(contract_bytes)
    active = tuple(sorted({row["loader"] for row in matrix["artifacts"]}))
    if not active or any(loader not in contract.loaders for loader in active):
        raise LoaderBootstrapError(f"matrix selected an uncontracted loader: {active!r}")
    verified: dict[str, Any] = {}
    for loader in active:
        expected = contract.loaders[loader]
        observed = _tree_entries(repository, head_sha, loader)
        if set(observed) != set(expected.files):
            raise LoaderBootstrapError(
                f"{loader} bootstrap inventory differs from protected contract: "
                f"missing={sorted(set(expected.files) - set(observed))}, "
                f"extra={sorted(set(observed) - set(expected.files))}"
            )
        for path, digest in expected.files.items():
            payload = _object_blob(repository, observed[path], path)
            if hashlib.sha256(payload).hexdigest() != digest:
                raise LoaderBootstrapError(
                    f"{loader} bootstrap differs from protected contract: {path}"
                )
        build_path = f"{loader}/build.gradle"
        build = _blob(repository, head_sha, build_path, maximum=1024 * 1024)
        build_digest = hashlib.sha256(build).hexdigest()
        if build_digest != expected.build_sha256:
            raise LoaderBootstrapError(
                f"{loader} build script differs from protected loader contract"
            )
        try:
            text = build.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise LoaderBootstrapError(f"{loader} build script is not UTF-8") from exc
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        if (
            not lines
            or lines[-1] != HARNESS_BINDING
            or lines.count(HARNESS_BINDING) != 1
        ):
            raise LoaderBootstrapError(
                f"{loader} build script must end in one protected harness binding"
            )
        verified[loader] = {
            "build_sha256": build_digest,
            "files_sha256": hashlib.sha256(
                json.dumps(
                    expected.files,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            ).hexdigest(),
        }
    return {
        "schema_version": SCHEMA_VERSION,
        "head_sha": head_sha,
        "contract_sha": contract_sha,
        "matrix_sha256": hashlib.sha256(matrix_bytes).hexdigest(),
        "contract_sha256": contract.sha256,
        "active_loaders": list(active),
        "verified": verified,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=REPO)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--contract-sha")
    args = parser.parse_args(argv)
    try:
        result = validate_commit(
            args.repository,
            head_sha=args.head_sha,
            contract_sha=args.contract_sha,
        )
    except (LoaderBootstrapError, OSError) as exc:
        print(f"loader bootstrap error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
