#!/usr/bin/env python3
"""Build a data-only visual queue entry and a fresh protected Claude tool handoff."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
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
from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    canonical_json,
    read as read_secure_json,
    require_object,
)


QUEUE_MANIFEST = "queue.json"
QUEUE_PURPOSE = "claude-advisory-visual-queue"
HANDOFF_MANIFEST = "handoff.json"
HANDOFF_PURPOSE = "claude-advisory-visual-tool-handoff"
MAX_FILES = 1100
MAX_FILE_BYTES = 7 * 1024 * 1024
MAX_TOTAL_BYTES = 96 * 1024 * 1024
MAX_CLIENT_BYTES = 256 * 1024
MAX_PREFLIGHT_BYTES = 256 * 1024
MAX_PROMPT_BYTES = 64 * 1024
MAX_PAIRS = 48
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
QUEUE_KEYS = frozenset(
    {
        "schema_version",
        "purpose",
        "implementation_sha",
        "producer_run_id",
        "producer_run_attempt",
        "source_run_id",
        "source_run_attempt",
        "source_head_sha",
        "tested_sha",
        "tested_tree",
        "reference_run_id",
        "reference_run_attempt",
        "reference_sha",
        "capsule_manifest_sha256",
        "pair_count",
        "inventory",
        "total_bytes",
    }
)
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


def _copy_tree(
    *, source_root: Path, output_root: Path, inventory_prefix: str
) -> tuple[list[dict[str, Any]], int]:
    inventory: list[dict[str, Any]] = []
    total = 0
    try:
        root_metadata = source_root.lstat()
    except OSError as exc:
        raise HandoffError(f"cannot inspect visual handoff root: {exc}") from exc
    if stat.S_ISLNK(root_metadata.st_mode) or not stat.S_ISDIR(root_metadata.st_mode):
        _fail("visual handoff source root must be a real directory")
    source_files: list[Path] = []
    for path in sorted(source_root.rglob("*")):
        try:
            metadata = path.lstat()
        except OSError as exc:
            raise HandoffError(f"cannot inspect visual handoff entry: {exc}") from exc
        if stat.S_ISLNK(metadata.st_mode):
            _fail(f"visual handoff contains a symbolic link: {path}")
        if stat.S_ISDIR(metadata.st_mode):
            continue
        if not stat.S_ISREG(metadata.st_mode):
            _fail(f"visual handoff contains a special entry: {path}")
        source_files.append(path)
    if not source_files or len(source_files) > MAX_FILES:
        _fail("visual handoff file count is empty or excessive")
    for source in source_files:
        relative = _safe_relative(source_root, source)
        payload = _read_regular(source, maximum=MAX_FILE_BYTES)
        output = output_root / PurePosixPath(relative)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(output, 0o444)
        total += len(payload)
        inventory.append(
            {
                "path": f"{inventory_prefix}{relative}",
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
            }
        )
    return inventory, total


def build_queue(
    destination: Path,
    *,
    capsule: Path,
    implementation_sha: str,
    producer_run_id: int,
    producer_run_attempt: int,
) -> dict[str, Any]:
    """Create a strictly data-only queue entry; it deliberately contains no executable code."""

    try:
        capsule_manifest, capsule_pairs = validate_capsule(capsule)
    except VisualEvidenceError as exc:
        raise HandoffError(str(exc)) from exc
    candidate = capsule_manifest["candidate_source"]
    reference = capsule_manifest["reference_source"]
    if SHA1.fullmatch(implementation_sha) is None:
        _fail("protected implementation SHA must be a lowercase commit SHA")
    for value, label in (
        (producer_run_id, "queue producer run id"),
        (producer_run_attempt, "queue producer run attempt"),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            _fail(f"{label} must be a positive integer")
    capsule_manifest_sha256 = hashlib.sha256(
        _read_regular(
            capsule / "visual-capsule.json",
            maximum=4 * 1024 * 1024,
        )
    ).hexdigest()
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        _fail(f"queue destination must be fresh: {destination}")
    try:
        parent = destination.parent.resolve(strict=True)
        parent_metadata = parent.lstat()
    except OSError as exc:
        raise HandoffError(f"cannot inspect queue parent: {exc}") from exc
    if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(parent_metadata.st_mode):
        _fail("queue parent must be a real directory")
    staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}.building-", dir=parent))
    try:
        output_capsule = staging / "capsule"
        output_capsule.mkdir(mode=0o700)
        inventory, total = _copy_tree(
            source_root=capsule,
            output_root=output_capsule,
            inventory_prefix="capsule/",
        )
        if total > MAX_TOTAL_BYTES:
            _fail("visual queue exceeds its total byte bound")
        inventory.sort(key=lambda item: item["path"])
        manifest = {
            "schema_version": 1,
            "purpose": QUEUE_PURPOSE,
            "implementation_sha": implementation_sha,
            "producer_run_id": producer_run_id,
            "producer_run_attempt": producer_run_attempt,
            "source_run_id": candidate["run_id"],
            "source_run_attempt": candidate["run_attempt"],
            "source_head_sha": candidate["source_head_commit"],
            "tested_sha": candidate["tested_commit"],
            "tested_tree": candidate["tested_tree"],
            "reference_run_id": reference["run_id"],
            "reference_run_attempt": reference["run_attempt"],
            "reference_sha": reference["tested_commit"],
            "capsule_manifest_sha256": capsule_manifest_sha256,
            "pair_count": len(capsule_pairs),
            "inventory": inventory,
            "total_bytes": total,
        }
        payload = canonical_json(manifest) + b"\n"
        with (staging / QUEUE_MANIFEST).open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(staging / QUEUE_MANIFEST, 0o444)
        staging.rename(destination)
        return {
            "schema_version": 1,
            "manifest_sha256": hashlib.sha256(payload).hexdigest(),
            "files": len(inventory),
            "total_bytes": total,
            "producer_run_id": producer_run_id,
            "producer_run_attempt": producer_run_attempt,
            "source_run_id": candidate["run_id"],
            "source_run_attempt": candidate["run_attempt"],
            "source_head_sha": candidate["source_head_commit"],
            "tested_sha": candidate["tested_commit"],
            "tested_tree": candidate["tested_tree"],
            "reference_sha": reference["tested_commit"],
            "pair_count": len(capsule_pairs),
        }
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def validate_queue(root: Path, expected_manifest_sha256: str | None = None) -> dict[str, Any]:
    """Revalidate one data-only queue entry and its complete capsule inventory."""

    try:
        root_metadata = root.lstat()
    except OSError as exc:
        raise HandoffError(f"cannot inspect visual queue root: {exc}") from exc
    if stat.S_ISLNK(root_metadata.st_mode) or not stat.S_ISDIR(root_metadata.st_mode):
        _fail("visual queue root must be a real directory")
    top_level = list(root.iterdir())
    if {path.name for path in top_level} != {QUEUE_MANIFEST, "capsule"}:
        _fail("visual queue contains an unexpected top-level entry")
    if any(stat.S_ISLNK(path.lstat().st_mode) for path in top_level):
        _fail("visual queue top-level entries must not be symbolic links")
    try:
        manifest, raw = read_secure_json(
            root / QUEUE_MANIFEST,
            label="visual queue manifest",
            max_bytes=4 * 1024 * 1024,
        )
        record = require_object(manifest, label="visual queue", required=QUEUE_KEYS)
    except (OSError, SecureJsonError) as exc:
        raise HandoffError(str(exc)) from exc
    digest = hashlib.sha256(raw).hexdigest()
    if expected_manifest_sha256 is not None and digest != expected_manifest_sha256:
        _fail("visual queue manifest digest is stale")
    if (
        raw != canonical_json(record) + b"\n"
        or type(record["schema_version"]) is not int
        or record["schema_version"] != 1
    ):
        _fail("visual queue manifest is not canonical schema 1")
    if record["purpose"] != QUEUE_PURPOSE:
        _fail("visual queue purpose is invalid")
    for field in (
        "producer_run_id",
        "producer_run_attempt",
        "source_run_id",
        "source_run_attempt",
        "reference_run_id",
        "reference_run_attempt",
    ):
        value = record[field]
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            _fail(f"visual queue {field} must be a positive integer")
    for field in (
        "implementation_sha",
        "source_head_sha",
        "tested_sha",
        "tested_tree",
        "reference_sha",
    ):
        value = record[field]
        if not isinstance(value, str) or SHA1.fullmatch(value) is None:
            _fail(f"visual queue {field} must be a lowercase commit SHA")
    value = record["capsule_manifest_sha256"]
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        _fail("visual queue capsule_manifest_sha256 must be a lowercase SHA-256")
    pair_count = record["pair_count"]
    if isinstance(pair_count, bool) or not isinstance(pair_count, int) or not 1 <= pair_count <= MAX_PAIRS:
        _fail(f"visual queue pair_count must be in 1..{MAX_PAIRS}")
    inventory = record["inventory"]
    if not isinstance(inventory, list) or not inventory or len(inventory) > MAX_FILES:
        _fail("visual queue inventory is empty or excessive")
    observed: list[dict[str, Any]] = []
    total = 0
    capsule = root / "capsule"
    sources: list[Path] = []
    for path in sorted(capsule.rglob("*")):
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            _fail("visual queue capsule contains a symbolic link")
        if stat.S_ISDIR(metadata.st_mode):
            continue
        if not stat.S_ISREG(metadata.st_mode):
            _fail("visual queue capsule contains a special file")
        sources.append(path)
    for source in sources:
        relative = _safe_relative(capsule, source)
        payload = _read_regular(source, maximum=MAX_FILE_BYTES)
        total += len(payload)
        observed.append(
            {
                "path": f"capsule/{relative}",
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
            }
        )
    if inventory != observed or total != record["total_bytes"] or total > MAX_TOTAL_BYTES:
        _fail("visual queue inventory or total byte identity is stale")
    try:
        capsule_manifest, capsule_pairs = validate_capsule(capsule)
    except VisualEvidenceError as exc:
        raise HandoffError(str(exc)) from exc
    candidate = capsule_manifest["candidate_source"]
    reference = capsule_manifest["reference_source"]
    expected_identity = {
        "source_run_id": candidate["run_id"],
        "source_run_attempt": candidate["run_attempt"],
        "source_head_sha": candidate["source_head_commit"],
        "tested_sha": candidate["tested_commit"],
        "tested_tree": candidate["tested_tree"],
        "reference_run_id": reference["run_id"],
        "reference_run_attempt": reference["run_attempt"],
        "reference_sha": reference["tested_commit"],
        "capsule_manifest_sha256": hashlib.sha256(
            _read_regular(
                capsule / "visual-capsule.json",
                maximum=4 * 1024 * 1024,
            )
        ).hexdigest(),
        "pair_count": len(capsule_pairs),
    }
    if any(record[field] != value for field, value in expected_identity.items()):
        _fail("visual queue provenance disagrees with its authenticated capsule")
    return record


def build_handoff(
    destination: Path,
    *,
    queue: Path,
    reviewer_implementation_sha: str,
    client: Path,
    preflight: Path,
    sonnet_prompt: Path,
    fable_prompt: Path,
) -> dict[str, Any]:
    """Combine validated queue data with tools copied from the current protected head."""

    queue_record = validate_queue(queue)
    if SHA1.fullmatch(reviewer_implementation_sha) is None:
        _fail("reviewer implementation must be a lowercase commit SHA")
    queue_manifest_payload = _read_regular(
        queue / QUEUE_MANIFEST,
        maximum=4 * 1024 * 1024,
    )
    client_payload = _read_regular(client, maximum=MAX_CLIENT_BYTES)
    preflight_payload = _read_regular(preflight, maximum=MAX_PREFLIGHT_BYTES)
    sonnet_payload = _read_regular(sonnet_prompt, maximum=MAX_PROMPT_BYTES)
    fable_payload = _read_regular(fable_prompt, maximum=MAX_PROMPT_BYTES)
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
        output_queue = staging / "queue"
        output_queue.mkdir(mode=0o700)
        inventory, total = _copy_tree(
            source_root=queue,
            output_root=output_queue,
            inventory_prefix="queue/",
        )
        fixed_files = {
            "review_client.py": client_payload,
            "credential_preflight.py": preflight_payload,
            "prompts/sonnet.md": sonnet_payload,
            "prompts/fable.md": fable_payload,
        }
        for relative, payload in fixed_files.items():
            output = staging / PurePosixPath(relative)
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("xb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(output, 0o444)
            total += len(payload)
            inventory.append(
                {
                    "path": relative,
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "size": len(payload),
                }
            )
        if total > MAX_TOTAL_BYTES:
            _fail("visual handoff exceeds its total byte bound")
        inventory.sort(key=lambda item: item["path"])
        manifest = {
            "schema_version": 1,
            "purpose": HANDOFF_PURPOSE,
            "reviewer_implementation_sha": reviewer_implementation_sha,
            "queue_manifest_sha256": hashlib.sha256(queue_manifest_payload).hexdigest(),
            "client_sha256": hashlib.sha256(client_payload).hexdigest(),
            "preflight_sha256": hashlib.sha256(preflight_payload).hexdigest(),
            "sonnet_prompt_sha256": hashlib.sha256(sonnet_payload).hexdigest(),
            "fable_prompt_sha256": hashlib.sha256(fable_payload).hexdigest(),
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
            "preflight_sha256": hashlib.sha256(preflight_payload).hexdigest(),
            "queue_manifest_sha256": hashlib.sha256(queue_manifest_payload).hexdigest(),
            "sonnet_prompt_sha256": hashlib.sha256(sonnet_payload).hexdigest(),
            "fable_prompt_sha256": hashlib.sha256(fable_payload).hexdigest(),
            "files": len(inventory),
            "total_bytes": total,
            "implementation_sha": queue_record["implementation_sha"],
            "reviewer_implementation_sha": reviewer_implementation_sha,
            "source_run_id": queue_record["source_run_id"],
            "source_run_attempt": queue_record["source_run_attempt"],
            "source_head_sha": queue_record["source_head_sha"],
            "tested_sha": queue_record["tested_sha"],
            "tested_tree": queue_record["tested_tree"],
            "reference_sha": queue_record["reference_sha"],
            "pair_count": queue_record["pair_count"],
        }
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", type=Path)
    parser.add_argument("--capsule", type=Path)
    parser.add_argument("--queue-only", action="store_true")
    parser.add_argument("--implementation-sha")
    parser.add_argument("--producer-run-id", type=int)
    parser.add_argument("--producer-run-attempt", type=int)
    parser.add_argument("--reviewer-implementation-sha")
    parser.add_argument("--client", type=Path)
    parser.add_argument("--preflight", type=Path)
    parser.add_argument("--sonnet-prompt", type=Path)
    parser.add_argument("--fable-prompt", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.queue_only:
            if (
                args.capsule is None
                or args.queue is not None
                or args.implementation_sha is None
                or args.producer_run_id is None
                or args.producer_run_attempt is None
                or args.reviewer_implementation_sha is not None
                or args.client is not None
                or args.preflight is not None
                or args.sonnet_prompt is not None
                or args.fable_prompt is not None
            ):
                _fail(
                    "--queue-only requires --capsule, --implementation-sha, "
                    "--producer-run-id, and --producer-run-attempt and forbids "
                    "tool-handoff inputs"
                )
            result = build_queue(
                args.output,
                capsule=args.capsule,
                implementation_sha=args.implementation_sha,
                producer_run_id=args.producer_run_id,
                producer_run_attempt=args.producer_run_attempt,
            )
        else:
            if (
                args.queue is None
                or args.capsule is not None
                or args.implementation_sha is not None
                or args.producer_run_id is not None
                or args.producer_run_attempt is not None
                or args.reviewer_implementation_sha is None
                or args.client is None
                or args.preflight is None
                or args.sonnet_prompt is None
                or args.fable_prompt is None
            ):
                _fail(
                    "tool handoff requires --queue, --reviewer-implementation-sha, --client, "
                    "--preflight, --sonnet-prompt, and --fable-prompt, and forbids --capsule and "
                    "--implementation-sha, --producer-run-id, and --producer-run-attempt"
                )
            result = build_handoff(
                args.output,
                queue=args.queue,
                reviewer_implementation_sha=args.reviewer_implementation_sha,
                client=args.client,
                preflight=args.preflight,
                sonnet_prompt=args.sonnet_prompt,
                fable_prompt=args.fable_prompt,
            )
    except (HandoffError, OSError) as exc:
        print(f"visual handoff error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
