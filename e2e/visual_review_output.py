#!/usr/bin/env python3
"""Fail-closed normalization for advisory semantic visual-review output.

Defect verdicts remain data: they never turn this validator into a deterministic gate.
Malformed, missing, duplicated, or over-broad model output is rejected before it can be
published or consumed by later automation.
"""

from __future__ import annotations

import os
import stat
import tempfile
from pathlib import Path
from typing import Any

from e2e.visual_capsule import validate_capsule
from e2e.visual_evidence import VisualEvidenceError
from scripts.lib.secure_json import (
    SecureJsonError,
    canonical_json,
    read as read_secure_json,
    require_object,
)


MAX_REVIEW_OUTPUT_BYTES = 4 * 1024 * 1024
MAX_VISIBLE_CHARS = 2048
MAX_FINDINGS = 16
MAX_FINDING_CHARS = 1024
OUTPUT_KEYS = frozenset({"schema_version", "advisory", "verdicts"})
VERDICT_KEYS = frozenset(
    {
        "label",
        "capture_id",
        "matches_expectation",
        "semantic_regression",
        "visible",
        "findings",
    }
)
FINDING_KEYS = frozenset({"category", "severity", "detail"})
FINDING_CATEGORIES = frozenset(
    {
        "blur",
        "clipping",
        "layout",
        "missing-widget",
        "rendering",
        "state",
        "text",
        "transparency",
        "unexpected-widget",
        "other",
    }
)
FINDING_SEVERITIES = frozenset({"note", "defect"})


class VisualReviewOutputError(ValueError):
    """The model report was not bounded output for the exact capsule."""


def _fail(message: str) -> None:
    raise VisualReviewOutputError(message)


def _text(value: Any, label: str, *, maximum: int) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > maximum
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        _fail(f"{label} must be printable non-empty text of at most {maximum} characters")
    return value.strip()


def validate_review_output(
    pairs: tuple[dict[str, Any], ...] | list[dict[str, Any]],
    value: Any,
) -> dict[str, Any]:
    """Return a canonical-order report only when it covers every capsule pair exactly once."""

    try:
        report = require_object(value, label="visual review output", required=OUTPUT_KEYS)
    except SecureJsonError as exc:
        raise VisualReviewOutputError(str(exc)) from exc
    if report["schema_version"] != 1 or report["advisory"] is not True:
        _fail("visual review output must be schema 1 and explicitly advisory")
    verdicts = report["verdicts"]
    if not isinstance(verdicts, list) or len(verdicts) != len(pairs) or not verdicts:
        _fail("visual review verdict count must exactly equal the capsule pair count")
    expected = {pair["label"]: pair["capture_id"] for pair in pairs}
    if len(expected) != len(pairs):
        _fail("capsule pairs contain duplicate labels")
    normalized_by_label: dict[str, dict[str, Any]] = {}
    for index, raw_verdict in enumerate(verdicts):
        try:
            verdict = require_object(
                raw_verdict, label=f"verdicts[{index}]", required=VERDICT_KEYS
            )
        except SecureJsonError as exc:
            raise VisualReviewOutputError(str(exc)) from exc
        label = _text(verdict["label"], f"verdicts[{index}].label", maximum=512)
        capture_id = _text(
            verdict["capture_id"], f"verdicts[{index}].capture_id", maximum=160
        )
        if label not in expected or expected[label] != capture_id:
            _fail(f"verdicts[{index}] is not an exact capsule semantic identity")
        if label in normalized_by_label:
            _fail(f"visual review output duplicates label {label!r}")
        matches = verdict["matches_expectation"]
        regression = verdict["semantic_regression"]
        if not isinstance(matches, bool) or not isinstance(regression, bool):
            _fail(f"verdicts[{index}] match/regression fields must be booleans")
        if matches == regression:
            _fail(
                f"verdicts[{index}] must set exactly one of matches_expectation "
                "and semantic_regression"
            )
        visible = _text(
            verdict["visible"], f"verdicts[{index}].visible", maximum=MAX_VISIBLE_CHARS
        )
        findings = verdict["findings"]
        if not isinstance(findings, list) or len(findings) > MAX_FINDINGS:
            _fail(f"verdicts[{index}].findings must contain at most {MAX_FINDINGS} entries")
        normalized_findings: list[dict[str, str]] = []
        seen_findings: set[tuple[str, str, str]] = set()
        for finding_index, raw_finding in enumerate(findings):
            try:
                finding = require_object(
                    raw_finding,
                    label=f"verdicts[{index}].findings[{finding_index}]",
                    required=FINDING_KEYS,
                )
            except SecureJsonError as exc:
                raise VisualReviewOutputError(str(exc)) from exc
            category = finding["category"]
            severity = finding["severity"]
            if category not in FINDING_CATEGORIES:
                _fail(f"verdicts[{index}] finding category is unknown")
            if severity not in FINDING_SEVERITIES:
                _fail(f"verdicts[{index}] finding severity is unknown")
            detail = _text(
                finding["detail"],
                f"verdicts[{index}].findings[{finding_index}].detail",
                maximum=MAX_FINDING_CHARS,
            )
            identity = (category, severity, detail)
            if identity in seen_findings:
                _fail(f"verdicts[{index}] repeats an identical finding")
            seen_findings.add(identity)
            normalized_findings.append(
                {"category": category, "severity": severity, "detail": detail}
            )
        has_defect = any(item["severity"] == "defect" for item in normalized_findings)
        if regression != has_defect:
            _fail(
                f"verdicts[{index}] semantic_regression must exactly reflect defect findings"
            )
        normalized_by_label[label] = {
            "label": label,
            "capture_id": capture_id,
            "matches_expectation": matches,
            "semantic_regression": regression,
            "visible": visible,
            "findings": normalized_findings,
        }
    missing = set(expected) - set(normalized_by_label)
    if missing:
        _fail(f"visual review output is missing capsule labels: {sorted(missing)}")
    return {
        "schema_version": 1,
        "advisory": True,
        "verdicts": [normalized_by_label[pair["label"]] for pair in pairs],
    }


def read_and_validate_review(capsule_root: Path, output_path: Path) -> dict[str, Any]:
    """Revalidate the immutable capsule, then normalize one bounded model JSON file."""

    try:
        _manifest, pairs = validate_capsule(capsule_root)
        raw, _ = read_secure_json(
            output_path,
            label="advisory visual review output",
            max_bytes=MAX_REVIEW_OUTPUT_BYTES,
        )
    except (SecureJsonError, VisualEvidenceError) as exc:
        raise VisualReviewOutputError(str(exc)) from exc
    return validate_review_output(pairs, raw)


def write_normalized_review(
    destination: Path,
    report: dict[str, Any],
    *,
    pairs: tuple[dict[str, Any], ...] | list[dict[str, Any]],
) -> None:
    """Atomically publish only a report already reduced to the normalized schema."""

    normalized = validate_review_output(pairs, report)
    payload = canonical_json(normalized) + b"\n"
    if len(payload) > MAX_REVIEW_OUTPUT_BYTES:
        _fail("normalized visual review output exceeds its byte limit")
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        _fail(f"normalized visual review destination must be fresh: {destination}")
    try:
        raw_parent_metadata = destination.parent.lstat()
        if stat.S_ISLNK(raw_parent_metadata.st_mode):
            _fail("normalized visual review parent must not be a symbolic link")
        parent = destination.parent.resolve(strict=True)
        metadata = parent.lstat()
    except OSError as exc:
        raise VisualReviewOutputError(f"cannot inspect normalized report parent: {exc}") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail("normalized visual review parent must be a real directory")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".tmp", dir=parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    except OSError as exc:
        temporary.unlink(missing_ok=True)
        raise VisualReviewOutputError(f"cannot publish normalized visual review: {exc}") from exc


def _markdown(value: Any) -> str:
    text = " ".join(str(value).split())
    for character in ("\\", "`", "*", "_", "[", "]", "<", ">"):
        text = text.replace(character, "\\" + character)
    return text


def advisory_markdown(report: dict[str, Any]) -> str:
    """Render bounded normalized output without converting advisory defects into a gate."""

    verdicts = report.get("verdicts", []) if isinstance(report, dict) else []
    defects = [item for item in verdicts if item.get("semantic_regression") is True]
    lines = [
        "## Advisory visual review: regressions reported" if defects else "## Advisory visual review: clean",
        "",
        f"Reviewed {len(verdicts)} semantic frame pairs; {len(defects)} advisory regression(s).",
    ]
    for verdict in defects:
        lines.extend(("", f"**{_markdown(verdict['label'])}**", f"- Seen: {_markdown(verdict['visible'])}"))
        for finding in verdict["findings"]:
            if finding["severity"] == "defect":
                lines.append(
                    f"- {_markdown(finding['category'])}: {_markdown(finding['detail'])}"
                )
    lines.extend(
        (
            "",
            "This semantic AI review is advisory. Build and packaged E2E remain authoritative.",
        )
    )
    return "\n".join(lines)
