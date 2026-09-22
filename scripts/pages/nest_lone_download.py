"""Give a lone downloaded Pages cache the artifact-named directory rotation expects.

download-artifact extracts a pattern that matches exactly one artifact straight
into its path instead of into a directory named after the artifact. Rotation
requires one directory per enrolled branch, named exactly after that branch's
cache artifact, so a lone cache is moved into the directory it would have had
among several. The name comes from the promotion inventory, never from the
downloaded bytes; rotation still authenticates everything it then reads.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.lib.secure_json import SecureJsonError, read as read_secure_json  # noqa: E402
from scripts.pages.build_site import MAX_INVENTORY_BYTES  # noqa: E402
from scripts.pages.evidence import EvidenceError, cache_artifact_name  # noqa: E402


class NestError(RuntimeError):
    pass


def nest_lone_cache(root: Path, inventory_path: Path) -> str | None:
    """Move a flattened lone cache under its artifact name; leave anything else as is."""
    manifest = root / "manifest.json"
    try:
        info = manifest.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(info.st_mode):
        return None
    try:
        rows, _ = read_secure_json(inventory_path, label="promotion inventory", max_bytes=MAX_INVENTORY_BYTES)
    except SecureJsonError as exc:
        raise NestError(str(exc)) from exc
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        raise NestError("a lone downloaded cache needs exactly one enrolled branch")
    try:
        name = cache_artifact_name(rows[0].get("name"), rows[0].get("commit"))
    except (AttributeError, EvidenceError, TypeError, ValueError) as exc:
        raise NestError(f"promotion inventory row cannot name a cache: {exc}") from exc
    entries = sorted(os.listdir(root))
    staging = root.parent / f".{root.name}-{name}"
    staging.mkdir()
    for entry in entries:
        os.rename(root / entry, staging / entry)
    os.rename(staging, root / name)
    return name


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        nested = nest_lone_cache(args.root, args.inventory)
    except (NestError, OSError) as exc:
        print(f"Pages cache nesting error: {exc}", file=sys.stderr)
        return 2
    if nested is not None:
        print(f"nested the lone cache under {nested}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
