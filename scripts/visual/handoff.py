#!/usr/bin/env python3
"""Build the sole immutable handoff admitted to the AI credential boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.visual_capsule import validate_capsule  # noqa: E402
from e2e.visual_evidence import VisualEvidenceError  # noqa: E402
from scripts.lib.secure_json import canonical_json  # noqa: E402


HANDOFF_MANIFEST = "handoff.json"
HANDOFF_PURPOSE = "openai-advisory-semantic-ui-review"
MAX_FILES = 1100
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 500 * 1024 * 1024
MAX_CLIENT_BYTES = 256 * 1024


class HandoffError(ValueError):
    """The credential-bearing handoff could not be constructed safely."""


def _fail(message: str) -> None:
    raise HandoffError(message)


def _read_regular(path: Path, *, maximum: int) -> bytes:
    descriptor = -1
    try:
        before = path.lstat()
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
            _fail(f"handoff input must be a regular non-symlink file: {path}")
        if not 1 <= before.st_size <= maximum:
            _fail(f"handoff input size is outside 1..{maximum}: {path}")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino, opened.st_size)
            != (before.st_dev, before.st_ino, before.st_size)
        ):
            _fail(f"handoff input changed while opening: {path}")
        payload = b""
        while len(payload) <= maximum:
            chunk = os.read(descriptor, min(1024 * 1024, maximum + 1 - len(payload)))
            if not chunk:
                break
            payload += chunk
        after = os.fstat(descriptor)
        if (
            len(payload) != opened.st_size
            or (after.st_dev, after.st_ino, after.st_size)
            != (opened.st_dev, opened.st_ino, opened.st_size)
        ):
            _fail(f"handoff input changed while reading: {path}")
        return payload
    except OSError as exc:
        raise HandoffError(f"cannot read handoff input {path}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _safe_relative(root: Path, path: Path) -> str:
    try:
        relative = path.relative_to(root).as_posix()
    except ValueError as exc:
        raise HandoffError(f"handoff path escapes its root: {path}") from exc
    parsed = PurePosixPath(relative)
    if (
        not relative
        or relative != parsed.as_posix()
        or parsed.is_absolute()
        or any(part in {"", ".", ".."} for part in parsed.parts)
    ):
        _fail(f"handoff path is not canonical: {relative!r}")
    return relative


def build_handoff(destination: Path, *, capsule: Path, client: Path) -> dict[str, Any]:
    """Copy a validated capsule and fixed client into one exact-inventory directory."""

    try:
        validate_capsule(capsule)
    except VisualEvidenceError as exc:
        raise HandoffError(str(exc)) from exc
    client_payload = _read_regular(client, maximum=MAX_CLIENT_BYTES)
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        _fail(f"handoff destination must be fresh: {destination}")
    try:
        parent = destination.parent.resolve(strict=True)
        parent_metadata = parent.lstat()
    except OSError as exc:
        raise HandoffError(f"cannot inspect handoff parent: {exc}") from exc
    if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(parent_metadata.st_mode):
        _fail("handoff parent must be a real directory")

    staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}.building-", dir=parent))
    try:
        output_capsule = staging / "capsule"
        output_capsule.mkdir(mode=0o700)
        inventory: list[dict[str, Any]] = []
        total = 0
        source_files = sorted(path for path in capsule.rglob("*") if path.is_file())
        if not source_files or len(source_files) + 1 > MAX_FILES:
            _fail("visual handoff file count is empty or excessive")
        for source in source_files:
            metadata = source.lstat()
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
                _fail(f"capsule contains a non-regular entry: {source}")
            relative = _safe_relative(capsule, source)
            payload = _read_regular(source, maximum=MAX_FILE_BYTES)
            output = output_capsule / PurePosixPath(relative)
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("xb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(output, 0o444)
            total += len(payload)
            inventory.append(
                {
                    "path": f"capsule/{relative}",
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "size": len(payload),
                }
            )
        output_client = staging / "review_client.py"
        with output_client.open("xb") as stream:
            stream.write(client_payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(output_client, 0o444)
        total += len(client_payload)
        inventory.append(
            {
                "path": "review_client.py",
                "sha256": hashlib.sha256(client_payload).hexdigest(),
                "size": len(client_payload),
            }
        )
        if total > MAX_TOTAL_BYTES:
            _fail("visual handoff exceeds its total byte bound")
        inventory.sort(key=lambda item: item["path"])
        manifest = {
            "schema_version": 1,
            "purpose": HANDOFF_PURPOSE,
            "inventory": inventory,
            "total_bytes": total,
        }
        manifest_payload = canonical_json(manifest) + b"\n"
        manifest_path = staging / HANDOFF_MANIFEST
        with manifest_path.open("xb") as stream:
            stream.write(manifest_payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(manifest_path, 0o444)
        staging.rename(destination)
        return {
            "schema_version": 1,
            "manifest_sha256": hashlib.sha256(manifest_payload).hexdigest(),
            "client_sha256": hashlib.sha256(client_payload).hexdigest(),
            "files": len(inventory),
            "total_bytes": total,
        }
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capsule", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = build_handoff(args.output, capsule=args.capsule, client=args.client)
    except (HandoffError, OSError) as exc:
        print(f"visual handoff error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
