#!/usr/bin/env python3
"""Normalize bounded model output and render an advisory report for one exact capsule."""

from __future__ import annotations

import argparse
import json
import os
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.visual_capsule import validate_capsule  # noqa: E402
from e2e.visual_evidence import VisualEvidenceError  # noqa: E402
from e2e.visual_review_output import (  # noqa: E402
    VisualReviewOutputError,
    advisory_markdown,
    read_and_validate_review,
    write_normalized_review,
)
from scripts.visual.review_client import ReviewClientError, validate_handoff  # noqa: E402


class NormalizeError(ValueError):
    """The advisory report is not bound to the expected source and baseline."""


def _fail(message: str) -> None:
    raise NormalizeError(message)


def _write_new(path: Path, payload: bytes) -> None:
    path = path.absolute()
    if path.exists() or path.is_symlink():
        _fail(f"advisory publication destination must be fresh: {path}")
    try:
        parent = path.parent.resolve(strict=True)
        metadata = parent.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            _fail("advisory publication parent must be a real directory")
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=parent
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except OSError as exc:
        if "temporary" in locals():
            temporary.unlink(missing_ok=True)
        raise NormalizeError(f"cannot publish advisory output: {exc}") from exc


def normalize(
    *,
    handoff: Path,
    expected_handoff_sha256: str,
    raw_output: Path,
    normalized_output: Path,
    markdown_output: Path,
    repository: str,
    source_run_id: int,
    source_head_sha: str,
    tested_sha: str,
    reference_sha: str,
) -> dict[str, Any]:
    _manifest, handoff_pairs = validate_handoff(handoff, expected_handoff_sha256)
    capsule = handoff / "capsule"
    capsule_manifest, capsule_pairs = validate_capsule(capsule)
    if len(handoff_pairs) != len(capsule_pairs):
        _fail("handoff and protected capsule validators disagree on pair coverage")
    candidate = capsule_manifest["candidate_source"]
    reference = capsule_manifest["reference_source"]
    if (
        candidate["repository"] != repository
        or candidate["run_id"] != source_run_id
        or candidate["source_head_commit"] != source_head_sha
        or candidate["tested_commit"] != tested_sha
        or reference["repository"] != repository
        or reference["source_head_commit"] != reference_sha
        or reference["tested_commit"] != reference_sha
        or reference["source_head_branch"] != "master"
    ):
        _fail("normalized advisory output is bound to a different source or baseline")
    report = read_and_validate_review(capsule, raw_output)
    write_normalized_review(normalized_output, report, pairs=capsule_pairs)
    markdown = advisory_markdown(report) + "\n"
    if len(markdown.encode("utf-8")) > 512 * 1024:
        _fail("advisory Markdown exceeds its byte bound")
    _write_new(markdown_output, markdown.encode("utf-8"))
    defects = sum(item["semantic_regression"] is True for item in report["verdicts"])
    return {
        "schema_version": 1,
        "advisory": True,
        "source_run_id": source_run_id,
        "source_head_sha": source_head_sha,
        "tested_sha": tested_sha,
        "reference_sha": reference_sha,
        "verdicts": len(report["verdicts"]),
        "semantic_regressions": defects,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--handoff", type=Path, required=True)
    parser.add_argument("--expected-handoff-sha256", required=True)
    parser.add_argument("--raw-output", type=Path, required=True)
    parser.add_argument("--normalized-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--source-head-sha", required=True)
    parser.add_argument("--tested-sha", required=True)
    parser.add_argument("--reference-sha", required=True)
    args = parser.parse_args(argv)
    try:
        result = normalize(
            handoff=args.handoff,
            expected_handoff_sha256=args.expected_handoff_sha256,
            raw_output=args.raw_output,
            normalized_output=args.normalized_output,
            markdown_output=args.markdown_output,
            repository=args.repository,
            source_run_id=args.source_run_id,
            source_head_sha=args.source_head_sha,
            tested_sha=args.tested_sha,
            reference_sha=args.reference_sha,
        )
    except (
        NormalizeError,
        OSError,
        ReviewClientError,
        VisualEvidenceError,
        VisualReviewOutputError,
    ) as exc:
        print(f"visual review normalization error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
