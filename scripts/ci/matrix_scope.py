#!/usr/bin/env python3
"""Select the default dispatch scope from validated matrix inputs, never a bundle."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.matrix import MatrixError, load_matrix_document  # noqa: E402


def default_dispatch_scope(matrix_path: Path, *, validate_sources: bool = True) -> str:
    """Return an artifact selector token, without authenticating or qualifying execution.

    `unscoped` means omit the artifact scope argument for schema1. Preparing selects
    its configured legacy lanes even while other targets remain unresolved; shared
    requires every target configured. Disabling source checks only skips tree inspection.
    """
    document = load_matrix_document(matrix_path, validate_sources=validate_sources)
    document.select_lanes()
    return "unscoped" if document.inventory.schema_version == 1 else document.default_scope


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--no-source-check", action="store_true",
                        help="validate configuration without inspecting repository source paths")
    args = parser.parse_args(argv)
    try:
        token = default_dispatch_scope(args.matrix, validate_sources=not args.no_source_check)
    except MatrixError as exc:
        print(f"matrix dispatch scope error: {exc}", file=sys.stderr)
        return 2
    print(token)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
