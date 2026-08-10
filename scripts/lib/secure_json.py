"""Bounded, race-aware, fail-closed JSON readers used at trust boundaries."""

from __future__ import annotations

import json
import math
import os
import stat
from pathlib import Path
from typing import Any, NoReturn


class SecureJsonError(ValueError):
    """Raised when JSON bytes or the file containing them are not trustworthy."""


def _reject_constant(value: str) -> NoReturn:
    raise SecureJsonError(f"non-finite JSON number {value!r} is forbidden")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SecureJsonError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def loads(data: bytes, *, label: str, max_bytes: int) -> Any:
    if not isinstance(data, bytes):
        raise SecureJsonError(f"{label} must be bytes")
    if not data:
        raise SecureJsonError(f"{label} is empty")
    if len(data) > max_bytes:
        raise SecureJsonError(
            f"{label} exceeds the {max_bytes}-byte input limit"
        )
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SecureJsonError(f"{label} is not valid UTF-8") from exc
    try:
        value = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except SecureJsonError:
        raise
    except json.JSONDecodeError as exc:
        raise SecureJsonError(f"{label} is not valid JSON: {exc}") from exc
    _reject_non_finite(value, label)
    return value


def _reject_non_finite(value: Any, label: str) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise SecureJsonError(f"{label} contains a non-finite number")
    if isinstance(value, list):
        for item in value:
            _reject_non_finite(item, label)
    elif isinstance(value, dict):
        for item in value.values():
            _reject_non_finite(item, label)


def read(path: Path, *, label: str, max_bytes: int) -> tuple[Any, bytes]:
    """Read one stable regular file without following a final-component symlink."""

    candidate = Path(path)
    try:
        before = candidate.lstat()
    except OSError as exc:
        raise SecureJsonError(f"cannot stat {label} {candidate}: {exc}") from exc
    if stat.S_ISLNK(before.st_mode):
        raise SecureJsonError(f"{label} path must not be a symlink: {candidate}")
    if not stat.S_ISREG(before.st_mode):
        raise SecureJsonError(f"{label} path must be a regular file: {candidate}")
    if before.st_size <= 0 or before.st_size > max_bytes:
        raise SecureJsonError(
            f"{label} size must be between 1 and {max_bytes} bytes"
        )

    flags = os.O_RDONLY
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(candidate, flags)
    except OSError as exc:
        raise SecureJsonError(f"cannot open {label} {candidate}: {exc}") from exc
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode):
            raise SecureJsonError(f"{label} changed to a non-regular file")
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise SecureJsonError(f"{label} changed while it was opened")
        if opened.st_size != before.st_size:
            raise SecureJsonError(f"{label} size changed while it was opened")
        chunks: list[bytes] = []
        remaining = max_bytes + 1
        while remaining:
            chunk = os.read(descriptor, min(65536, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        raw = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            (after.st_dev, after.st_ino, after.st_size)
            != (opened.st_dev, opened.st_ino, opened.st_size)
        ):
            raise SecureJsonError(f"{label} changed while it was read")
    finally:
        os.close(descriptor)
    return loads(raw, label=label, max_bytes=max_bytes), raw


def require_object(
    value: Any,
    *,
    label: str,
    required: set[str] | frozenset[str],
    optional: set[str] | frozenset[str] = frozenset(),
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SecureJsonError(f"{label} must be an object")
    keys = set(value)
    missing = set(required) - keys
    unknown = keys - set(required) - set(optional)
    if missing or unknown:
        details: list[str] = []
        if missing:
            details.append(f"missing {sorted(missing)}")
        if unknown:
            details.append(f"unknown {sorted(unknown)}")
        raise SecureJsonError(f"{label} has " + " and ".join(details))
    return value


def canonical_json(value: Any) -> bytes:
    return (
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        .encode("utf-8")
    )
