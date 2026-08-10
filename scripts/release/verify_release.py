#!/usr/bin/env python3
"""Stage or reverify the exact production and packaged-E2E release bytes."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.release.artifact_manifest import (  # noqa: E402
    ArtifactError,
    stage_release,
    verify_staged,
)
from scripts.release.matrix import MatrixError  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix", type=Path, default=Path("release/release-matrix.json")
    )
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    parser.add_argument(
        "--manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    parser.add_argument("--verify-staged", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.verify_staged:
            verify_staged(
                repository=REPO,
                matrix_path=args.matrix,
                manifest_path=args.manifest,
                stage=args.stage,
            )
        else:
            stage_release(
                repository=REPO,
                matrix_path=args.matrix,
                manifest_path=args.manifest,
                stage=args.stage,
            )
    except (ArtifactError, MatrixError) as exc:
        print(f"release verification error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
