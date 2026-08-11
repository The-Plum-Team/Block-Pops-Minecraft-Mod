#!/usr/bin/env python3
"""Normalize bounded model output and render an advisory report for one exact capsule."""

from __future__ import annotations

import argparse
import hashlib
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
from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    canonical_json,
    read as read_secure_json,
    require_object,
)
from scripts.visual.handoff import HANDOFF_KEYS, HANDOFF_MANIFEST, HANDOFF_PURPOSE  # noqa: E402
from scripts.visual.review_client import ReviewClientError, validate_handoff  # noqa: E402


class NormalizeError(ValueError):
    """The advisory report is not bound to the expected source and baseline."""


def _fail(message: str) -> None:
    raise NormalizeError(message)


PROVENANCE_PURPOSE = "authenticated-advisory-visual-review-provenance"
PROVENANCE_KEYS = frozenset(
    {
        "schema_version",
        "purpose",
        "advisory",
        "handoff_manifest_sha256",
        "queue_manifest_sha256",
        "capsule_manifest_sha256",
        "normalized_review_sha256",
        "reviewer_implementation_sha",
        "contract_sha256",
        "canonical_reference",
        "candidate_source",
        "reference_source",
        "pairs",
    }
)


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


def _handoff_identity(root: Path, expected_sha256: str) -> dict[str, Any]:
    """Read the already-validated manifest again and bind its exact protected tools."""

    try:
        value, raw = read_secure_json(
            root / HANDOFF_MANIFEST,
            label="visual review handoff manifest",
            max_bytes=4 * 1024 * 1024,
        )
        record = require_object(
            value,
            label="visual review handoff manifest",
            required=HANDOFF_KEYS,
        )
    except SecureJsonError as exc:
        raise NormalizeError(str(exc)) from exc
    if (
        hashlib.sha256(raw).hexdigest() != expected_sha256
        or raw != canonical_json(record) + b"\n"
        or record["schema_version"] != 1
        or record["purpose"] != HANDOFF_PURPOSE
    ):
        _fail("visual review handoff manifest identity changed before publication")
    return record


def _provenance_projection(
    *,
    capsule_manifest: dict[str, Any],
    handoff_identity: dict[str, Any],
    handoff_manifest_sha256: str,
    normalized_report: dict[str, Any],
) -> dict[str, Any]:
    """Project authenticated data needed to explain a review after images expire."""

    pair_records: list[dict[str, Any]] = []
    for pair in capsule_manifest["pairs"]:
        frames: dict[str, dict[str, Any]] = {}
        for role in ("candidate", "reference"):
            frame = pair[role]
            frames[role] = {
                field: frame[field]
                for field in (
                    "artifact_node",
                    "minecraft",
                    "loader",
                    "scenario",
                    "role",
                    "step",
                    "capture_id",
                    "label",
                    "source_file_sha256",
                    "pixel_sha256",
                    "file_sha256",
                    "width",
                    "height",
                    "source_artifact_id",
                )
            }
        pair_records.append(
            {
                "label": pair["label"],
                "capture_id": pair["capture_id"],
                "title": pair["title"],
                "expectation": pair["expectation"],
                "review_tier": pair["review_tier"],
                "candidate": frames["candidate"],
                "reference": frames["reference"],
                "triage": pair["triage"],
            }
        )
    capsule_payload = canonical_json(capsule_manifest) + b"\n"
    review_payload = canonical_json(normalized_report) + b"\n"
    return {
        "schema_version": 1,
        "purpose": PROVENANCE_PURPOSE,
        "advisory": True,
        "handoff_manifest_sha256": handoff_manifest_sha256,
        "queue_manifest_sha256": handoff_identity["queue_manifest_sha256"],
        "capsule_manifest_sha256": hashlib.sha256(capsule_payload).hexdigest(),
        "normalized_review_sha256": hashlib.sha256(review_payload).hexdigest(),
        "reviewer_implementation_sha": handoff_identity[
            "reviewer_implementation_sha"
        ],
        "contract_sha256": capsule_manifest["contract_sha256"],
        "canonical_reference": capsule_manifest["canonical_reference"],
        "candidate_source": capsule_manifest["candidate_source"],
        "reference_source": capsule_manifest["reference_source"],
        "pairs": pair_records,
    }


def validate_review_provenance(
    *,
    handoff: Path,
    expected_handoff_sha256: str,
    normalized_output: Path,
    provenance_output: Path,
) -> dict[str, Any]:
    """Validate the bounded durable projection against its original authenticated inputs."""

    validate_handoff(handoff, expected_handoff_sha256)
    handoff_identity = _handoff_identity(handoff, expected_handoff_sha256)
    capsule = handoff / "queue" / "capsule"
    capsule_manifest, _pairs = validate_capsule(capsule)
    normalized_report = read_and_validate_review(capsule, normalized_output)
    expected = _provenance_projection(
        capsule_manifest=capsule_manifest,
        handoff_identity=handoff_identity,
        handoff_manifest_sha256=expected_handoff_sha256,
        normalized_report=normalized_report,
    )
    try:
        value, raw = read_secure_json(
            provenance_output,
            label="visual review provenance",
            max_bytes=4 * 1024 * 1024,
        )
        record = require_object(
            value,
            label="visual review provenance",
            required=PROVENANCE_KEYS,
        )
    except SecureJsonError as exc:
        raise NormalizeError(str(exc)) from exc
    if raw != canonical_json(record) + b"\n" or record != expected:
        _fail("durable visual review provenance disagrees with authenticated evidence")
    return record


def normalize(
    *,
    handoff: Path,
    expected_handoff_sha256: str,
    raw_output: Path,
    normalized_output: Path,
    markdown_output: Path,
    provenance_output: Path,
    repository: str,
    source_run_id: int,
    source_run_attempt: int,
    source_head_sha: str,
    tested_sha: str,
    reference_sha: str,
) -> dict[str, Any]:
    _manifest, handoff_pairs = validate_handoff(handoff, expected_handoff_sha256)
    handoff_identity = _handoff_identity(handoff, expected_handoff_sha256)
    capsule = handoff / "queue" / "capsule"
    capsule_manifest, capsule_pairs = validate_capsule(capsule)
    if len(handoff_pairs) != len(capsule_pairs):
        _fail("handoff and protected capsule validators disagree on pair coverage")
    candidate = capsule_manifest["candidate_source"]
    reference = capsule_manifest["reference_source"]
    if (
        candidate["repository"] != repository
        or candidate["run_id"] != source_run_id
        or candidate["run_attempt"] != source_run_attempt
        or candidate["source_head_commit"] != source_head_sha
        or candidate["tested_commit"] != tested_sha
        or reference["repository"] != repository
        or reference["source_head_commit"] != reference_sha
        or reference["tested_commit"] != reference_sha
        or reference["source_head_branch"] != "master"
    ):
        _fail("normalized advisory output is bound to a different source or baseline")
    report = read_and_validate_review(capsule, raw_output)
    telemetry = report["telemetry"]
    expected_tools = {
        "client_sha256": handoff_identity["client_sha256"],
        "sonnet_prompt_sha256": handoff_identity["sonnet_prompt_sha256"],
        "fable_prompt_sha256": handoff_identity["fable_prompt_sha256"],
    }
    if any(telemetry[field] != digest for field, digest in expected_tools.items()):
        _fail("visual review telemetry is not bound to the authenticated client and prompts")
    # Narrow the read/validation-to-publication window without trusting a prior
    # filesystem observation. A changed handoff fails before normalized output.
    validate_handoff(handoff, expected_handoff_sha256)
    if _handoff_identity(handoff, expected_handoff_sha256) != handoff_identity:
        _fail("visual review handoff changed during protected normalization")
    write_normalized_review(normalized_output, report, pairs=capsule_pairs)
    provenance = _provenance_projection(
        capsule_manifest=capsule_manifest,
        handoff_identity=handoff_identity,
        handoff_manifest_sha256=expected_handoff_sha256,
        normalized_report=report,
    )
    provenance_payload = canonical_json(provenance) + b"\n"
    if len(provenance_payload) > 4 * 1024 * 1024:
        _fail("durable visual review provenance exceeds its byte bound")
    _write_new(provenance_output, provenance_payload)
    validate_review_provenance(
        handoff=handoff,
        expected_handoff_sha256=expected_handoff_sha256,
        normalized_output=normalized_output,
        provenance_output=provenance_output,
    )
    markdown = advisory_markdown(report) + "\n"
    if len(markdown.encode("utf-8")) > 512 * 1024:
        _fail("advisory Markdown exceeds its byte bound")
    _write_new(markdown_output, markdown.encode("utf-8"))
    defects = sum(item["semantic_regression"] is True for item in report["verdicts"])
    return {
        "schema_version": 1,
        "advisory": True,
        "source_run_id": source_run_id,
        "source_run_attempt": source_run_attempt,
        "source_head_sha": source_head_sha,
        "tested_sha": tested_sha,
        "reference_sha": reference_sha,
        "verdicts": len(report["verdicts"]),
        "semantic_regressions": defects,
        "provenance_sha256": hashlib.sha256(provenance_payload).hexdigest(),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--handoff", type=Path, required=True)
    parser.add_argument("--expected-handoff-sha256", required=True)
    parser.add_argument("--raw-output", type=Path, required=True)
    parser.add_argument("--normalized-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--provenance-output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--source-run-attempt", type=int, required=True)
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
            provenance_output=args.provenance_output,
            repository=args.repository,
            source_run_id=args.source_run_id,
            source_run_attempt=args.source_run_attempt,
            source_head_sha=args.source_head_sha,
            tested_sha=args.tested_sha,
            reference_sha=args.reference_sha,
        )
    except (
        NormalizeError,
        OSError,
        ReviewClientError,
        SecureJsonError,
        VisualEvidenceError,
        VisualReviewOutputError,
    ) as exc:
        print(f"visual review normalization error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
