#!/usr/bin/env python3
"""Fail-closed packaged-runtime impact classification.

The classifier is intentionally advisory: release synchronization always runs
Packaged E2E.  Its manifest explains why a change was considered runtime-facing
without ever weakening the authoritative gate.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


POLICY_VERSION = 1
ROOT_DOCUMENTS = frozenset(
    {
        "README.md",
        "CONTRIBUTING.md",
        "RELEASING.md",
        "SECURITY.md",
        "VERSION-BRANCHES.md",
    }
)
EXACT_NON_RUNTIME_WORKFLOWS = frozenset(
    {
        ".github/workflows/handle-release-sync-result.yml",
        ".github/workflows/reconcile-release-sync.yml",
        ".github/workflows/sync-release-branches.yml",
    }
)
EXACT_NON_RUNTIME_PATHS = frozenset(
    {
        ".github/pull_request_template.md",
        "scripts/release/version_branches.py",
    }
)


class ImpactError(ValueError):
    """Raised when an exact diff cannot be safely classified."""


@dataclass(frozen=True)
class Classification:
    runtime_required: bool
    paths: tuple[str, ...]
    runtime_paths: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": POLICY_VERSION,
            "runtime_required": self.runtime_required,
            "gate_policy": "always-full",
            "paths": list(self.paths),
            "runtime_paths": list(self.runtime_paths),
        }


def normalize_path(raw: str) -> str:
    if (
        not isinstance(raw, str)
        or not raw
        or any(character in raw for character in ("\0", "\n", "\r", "\\"))
    ):
        raise ImpactError("diff paths must be non-empty canonical UTF-8 lines")
    path = PurePosixPath(raw)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ImpactError(f"diff path is not repository-relative: {raw!r}")
    if path.as_posix() != raw:
        raise ImpactError(f"diff path is not canonical: {raw!r}")
    return raw


def is_non_runtime_path(path: str) -> bool:
    if path in ROOT_DOCUMENTS or path in EXACT_NON_RUNTIME_PATHS:
        return True
    if path in EXACT_NON_RUNTIME_WORKFLOWS:
        return True
    if path.startswith("docs/"):
        return path.endswith(".md") or path.startswith("docs/assets/")
    return False


def classify(paths: Iterable[str]) -> Classification:
    normalized = tuple(sorted({normalize_path(path) for path in paths}))
    if not normalized:
        # An ancestry-only synchronization still receives the full gate. It is
        # never treated as evidence that Packaged E2E is not applicable.
        return Classification(True, (), ())
    runtime = tuple(path for path in normalized if not is_non_runtime_path(path))
    return Classification(bool(runtime), normalized, runtime)


def git_diff_paths(repository: Path, base: str, head: str) -> list[str]:
    if not base or not head:
        raise ImpactError("both exact base and head are required")
    result = subprocess.run(
        [
            "git",
            "-C",
            str(repository),
            "diff",
            "--no-renames",
            "--name-only",
            "-z",
            "--diff-filter=ACDMRTUXB",
            base,
            head,
            "--",
        ],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ImpactError(f"cannot resolve exact diff: {detail}")
    records = result.stdout.split(b"\0")
    if records and not records[-1]:
        records.pop()
    try:
        return [record.decode("utf-8", "strict") for record in records]
    except UnicodeDecodeError as exc:
        raise ImpactError("diff contains a non-UTF-8 path") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        manifest = classify(
            git_diff_paths(args.repository.resolve(), args.base, args.head)
        ).manifest()
    except (OSError, ImpactError) as exc:
        print(f"E2E impact classification failed: {exc}")
        return 2
    encoded = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
    print(encoded)
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"runtime_required={'true' if manifest['runtime_required'] else 'false'}\n")
            output.write(f"manifest={encoded}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
