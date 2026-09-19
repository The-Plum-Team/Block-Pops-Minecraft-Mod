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
        "--repository",
        type=Path,
        default=REPO,
        help="Repository whose production outputs and Git identity are being verified",
    )
    parser.add_argument(
        "--matrix", type=Path, default=Path("release/release-matrix.json")
    )
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    parser.add_argument(
        "--manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    parser.add_argument("--verify-staged", action="store_true")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("legacy", "full"), help="expected schema3 bundle scope")
    selection.add_argument("--artifact-node", help="stage or verify exactly one configured lane")
    args = parser.parse_args(argv)
    repository = args.repository.resolve()
    paths = {name: value if value.is_absolute() else repository / value
             for name, value in (("matrix_path", args.matrix), ("manifest_path", args.manifest), ("stage", args.stage))}
    selection = {"scope": "lane" if args.artifact_node else args.scope, "artifact_node": args.artifact_node}
    try:
        if args.verify_staged:
            verify_staged(
                repository=repository,
                **paths, **selection,
            )
        else:
            stage_release(
                repository=repository,
                **paths, **selection,
            )
    except (ArtifactError, MatrixError) as exc:
        print(f"release verification error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
