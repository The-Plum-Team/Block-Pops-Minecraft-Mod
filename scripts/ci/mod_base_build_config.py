#!/usr/bin/env python3
"""Check or rewrite the SHA-256 of every file ``scripts/ci/mod-base-build.json`` lists.

The kit runs the protected Build hooks from a copy holding the config and exactly the files of
``adapter.files``, and refuses a config whose listed file has another hash. After editing one of
them, run ``python3 scripts/ci/mod_base_build_config.py --write`` and commit the config with the
edit; without ``--write`` the command only reports stale hashes (exit 1). Hashes are of the bytes
in the working tree, so run it in a checkout with LF line endings (``core.autocrlf=input``).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CONFIG = REPO / "scripts" / "ci" / "mod-base-build.json"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="rewrite stale hashes instead of reporting them")
    args = parser.parse_args(argv)
    config = json.loads(CONFIG.read_bytes())
    stale = []
    for entry in config["adapter"]["files"]:
        data = (REPO / entry["path"]).read_bytes()
        if b"\r\n" in data:
            print(f"{entry['path']} has CRLF line endings; hash an LF checkout", file=sys.stderr)
            return 2
        digest = hashlib.sha256(data).hexdigest()
        if digest != entry["sha256"]:
            stale.append(entry["path"])
            entry["sha256"] = digest
    if stale and args.write:
        CONFIG.write_bytes((json.dumps(config, sort_keys=True, indent=2) + "\n").encode("utf-8"))
    for path in stale:
        print(f"{'updated' if args.write else 'stale'}: {path}")
    return 0 if args.write or not stale else 1


if __name__ == "__main__":
    raise SystemExit(main())
