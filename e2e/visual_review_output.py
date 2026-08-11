#!/usr/bin/env python3
"""Fail-closed normalization for schema-2 advisory Claude visual-review output.

Defect verdicts remain advisory data: malformed or over-broad output is rejected, while a valid
semantic regression never changes the deterministic Build or Packaged E2E result.
"""

from __future__ import annotations

import os
import re
import stat
import tempfile
import unicodedata
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


MAX_REVIEW_OUTPUT_BYTES = 1024 * 1024
MAX_VISIBLE_CHARS = 2048
MAX_FINDINGS = 16
MAX_FINDING_CHARS = 1024
MAX_PAIRS = 10
MAX_MODEL_CALLS = 5
MAX_MODEL_ATTEMPTS = 10
MAX_DURATION_MS = 35 * 60 * 1000
MAX_USAGE_TOKENS = 100_000_000

SONNET_MODEL = "claude-sonnet-5"
FABLE_MODEL = "claude-fable-5"
IDENTICAL_VISIBLE = "Candidate and canonical reference are byte-identical."
SONNET_INPUT_MICRO_USD = 3
SONNET_OUTPUT_MICRO_USD = 15
FABLE_INPUT_MICRO_USD = 10
FABLE_OUTPUT_MICRO_USD = 50

SHA256 = re.compile(r"^[0-9a-f]{64}$")
REQUEST_ID = re.compile(r"^req_[A-Za-z0-9_-]{1,160}$")
OUTPUT_KEYS = frozenset({"schema_version", "advisory", "telemetry", "verdicts"})
VERDICT_KEYS = frozenset(
    {
        "label",
        "capture_id",
        "route",
        "matches_expectation",
        "semantic_regression",
        "visible",
        "findings",
    }
)
TELEMETRY_KEYS = frozenset(
    {
        "provider",
        "auth_mode",
        "triage_model",
        "verification_model",
        "client_sha256",
        "sonnet_prompt_sha256",
        "fable_prompt_sha256",
        "pair_count",
        "identical_pairs",
        "triaged_pairs",
        "escalated_pairs",
        "sonnet_calls",
        "fable_calls",
        "provider_attempts",
        "retries",
        "sonnet_usage",
        "fable_usage",
        "reported_cost_upper_bound_micro_usd",
        "duration_ms",
        "request_ids",
    }
)
USAGE_KEYS = frozenset(
    {
        "input_tokens",
        "output_tokens",
        "cache_creation_input_tokens",
        "cache_read_input_tokens",
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
ROUTES = frozenset({"identical", "sonnet", "fable"})


class VisualReviewOutputError(ValueError):
    """The report was not bounded output for the exact authenticated capsule."""


def _fail(message: str) -> None:
    raise VisualReviewOutputError(message)


def _text(value: Any, label: str, *, maximum: int) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum
        or any(
            ord(character) < 32
            or ord(character) == 127
            or 0xD800 <= ord(character) <= 0xDFFF
            or unicodedata.category(character) == "Cf"
            for character in value
        )
    ):
        _fail(f"{label} must be printable non-empty text of at most {maximum} characters")
    return value


def _integer(value: Any, label: str, *, minimum: int = 0, maximum: int) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not minimum <= value <= maximum
    ):
        _fail(f"{label} must be an integer in {minimum}..{maximum}")
    return value


def _validate_findings(value: Any, label: str) -> list[dict[str, str]]:
    if not isinstance(value, list) or len(value) > MAX_FINDINGS:
        _fail(f"{label} must contain at most {MAX_FINDINGS} entries")
    normalized: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    for index, raw in enumerate(value):
        try:
            finding = require_object(
                raw, label=f"{label}[{index}]", required=FINDING_KEYS
            )
        except SecureJsonError as exc:
            raise VisualReviewOutputError(str(exc)) from exc
        category = finding["category"]
        severity = finding["severity"]
        if category not in FINDING_CATEGORIES:
            _fail(f"{label}[{index}] category is unknown")
        if severity not in FINDING_SEVERITIES:
            _fail(f"{label}[{index}] severity is unknown")
        detail = _text(
            finding["detail"], f"{label}[{index}].detail", maximum=MAX_FINDING_CHARS
        )
        identity = (category, severity, detail)
        if identity in seen:
            _fail(f"{label} repeats an identical finding")
        seen.add(identity)
        normalized.append(
            {"category": category, "severity": severity, "detail": detail}
        )
    return normalized


def _validate_usage(value: Any, label: str) -> dict[str, int]:
    try:
        usage = require_object(value, label=label, required=USAGE_KEYS)
    except SecureJsonError as exc:
        raise VisualReviewOutputError(str(exc)) from exc
    return {
        field: _integer(
            usage[field], f"{label}.{field}", maximum=MAX_USAGE_TOKENS
        )
        for field in sorted(USAGE_KEYS)
    }


def _usage_total(usage: dict[str, int]) -> int:
    return sum(usage.values())


def _reported_cost(sonnet: dict[str, int], fable: dict[str, int]) -> int:
    return (
        sonnet["input_tokens"] * SONNET_INPUT_MICRO_USD
        + sonnet["cache_creation_input_tokens"] * SONNET_INPUT_MICRO_USD * 2
        + sonnet["cache_read_input_tokens"] * SONNET_INPUT_MICRO_USD
        + sonnet["output_tokens"] * SONNET_OUTPUT_MICRO_USD
        + fable["input_tokens"] * FABLE_INPUT_MICRO_USD
        + fable["cache_creation_input_tokens"] * FABLE_INPUT_MICRO_USD * 2
        + fable["cache_read_input_tokens"] * FABLE_INPUT_MICRO_USD
        + fable["output_tokens"] * FABLE_OUTPUT_MICRO_USD
    )


def _validate_telemetry(
    value: Any,
    *,
    pairs: tuple[dict[str, Any], ...] | list[dict[str, Any]],
    verdicts: list[dict[str, Any]],
) -> dict[str, Any]:
    try:
        telemetry = require_object(
            value, label="visual review telemetry", required=TELEMETRY_KEYS
        )
    except SecureJsonError as exc:
        raise VisualReviewOutputError(str(exc)) from exc
    if (
        telemetry["provider"] != "anthropic"
        or telemetry["auth_mode"] != "github-oidc-wif"
        or telemetry["triage_model"] != SONNET_MODEL
        or telemetry["verification_model"] != FABLE_MODEL
    ):
        _fail("visual review telemetry provider/model identity is invalid")
    for field in ("client_sha256", "sonnet_prompt_sha256", "fable_prompt_sha256"):
        if not isinstance(telemetry[field], str) or SHA256.fullmatch(telemetry[field]) is None:
            _fail(f"visual review telemetry {field} is invalid")

    pair_count = len(pairs)
    identical_expected = sum(
        pair.get("triage", {}).get("byte_identical") is True for pair in pairs
    )
    if any(
        not isinstance(pair.get("triage"), dict)
        or not isinstance(pair["triage"].get("byte_identical"), bool)
        for pair in pairs
    ):
        _fail("capsule pair triage identity is unavailable")
    triaged_expected = pair_count - identical_expected
    escalated_expected = sum(verdict["route"] == "fable" for verdict in verdicts)
    exact_counts = {
        "pair_count": pair_count,
        "identical_pairs": identical_expected,
        "triaged_pairs": triaged_expected,
        "escalated_pairs": escalated_expected,
    }
    for field, expected in exact_counts.items():
        if telemetry[field] != expected:
            _fail(f"visual review telemetry {field} disagrees with verdict routing")
    sonnet_calls = _integer(
        telemetry["sonnet_calls"], "visual review telemetry sonnet_calls", maximum=MAX_MODEL_CALLS
    )
    fable_calls = _integer(
        telemetry["fable_calls"], "visual review telemetry fable_calls", maximum=MAX_MODEL_CALLS
    )
    if triaged_expected == 0:
        if sonnet_calls != 0:
            _fail("identical-only review must not call Sonnet")
    elif not (triaged_expected + 4) // 5 <= sonnet_calls <= triaged_expected:
        _fail("Sonnet call count cannot cover the triaged pairs within chunk bounds")
    if escalated_expected == 0:
        if fable_calls != 0:
            _fail("review without escalation must not call the strongest tier")
    elif not (escalated_expected + 3) // 4 <= fable_calls <= escalated_expected:
        _fail("strong-tier call count cannot cover the escalated pairs within chunk bounds")
    calls = sonnet_calls + fable_calls
    if calls > MAX_MODEL_CALLS:
        _fail("visual review telemetry exceeds the five-call budget")
    attempts = _integer(
        telemetry["provider_attempts"],
        "visual review telemetry provider_attempts",
        maximum=MAX_MODEL_ATTEMPTS,
    )
    retries = _integer(
        telemetry["retries"], "visual review telemetry retries", maximum=MAX_MODEL_CALLS
    )
    if attempts < calls or attempts > calls * 2 or retries != attempts - calls:
        _fail("visual review attempt/retry telemetry is inconsistent")

    sonnet_usage = _validate_usage(
        telemetry["sonnet_usage"], "visual review telemetry sonnet_usage"
    )
    fable_usage = _validate_usage(
        telemetry["fable_usage"], "visual review telemetry fable_usage"
    )
    if sonnet_calls == 0 and _usage_total(sonnet_usage) != 0:
        _fail("visual review reports Sonnet usage without a Sonnet call")
    if fable_calls == 0 and _usage_total(fable_usage) != 0:
        _fail("visual review reports Fable usage without a Fable call")
    expected_cost = _reported_cost(sonnet_usage, fable_usage)
    cost = _integer(
        telemetry["reported_cost_upper_bound_micro_usd"],
        "visual review telemetry reported_cost_upper_bound_micro_usd",
        maximum=2**63 - 1,
    )
    if cost != expected_cost:
        _fail("visual review reported cost does not match normalized usage")
    duration = _integer(
        telemetry["duration_ms"],
        "visual review telemetry duration_ms",
        maximum=MAX_DURATION_MS,
    )
    request_ids = telemetry["request_ids"]
    if (
        not isinstance(request_ids, list)
        or len(request_ids) != calls
        or request_ids != list(dict.fromkeys(request_ids))
        or any(
            not isinstance(item, str) or REQUEST_ID.fullmatch(item) is None
            for item in request_ids
        )
    ):
        _fail("visual review request-id telemetry is invalid")
    return {
        "provider": "anthropic",
        "auth_mode": "github-oidc-wif",
        "triage_model": SONNET_MODEL,
        "verification_model": FABLE_MODEL,
        "client_sha256": telemetry["client_sha256"],
        "sonnet_prompt_sha256": telemetry["sonnet_prompt_sha256"],
        "fable_prompt_sha256": telemetry["fable_prompt_sha256"],
        **exact_counts,
        "sonnet_calls": sonnet_calls,
        "fable_calls": fable_calls,
        "provider_attempts": attempts,
        "retries": retries,
        "sonnet_usage": sonnet_usage,
        "fable_usage": fable_usage,
        "reported_cost_upper_bound_micro_usd": cost,
        "duration_ms": duration,
        "request_ids": list(request_ids),
    }


def validate_review_output(
    pairs: tuple[dict[str, Any], ...] | list[dict[str, Any]],
    value: Any,
) -> dict[str, Any]:
    """Return canonical-order schema 2 only when every capsule pair is covered once."""

    try:
        report = require_object(value, label="visual review output", required=OUTPUT_KEYS)
    except SecureJsonError as exc:
        raise VisualReviewOutputError(str(exc)) from exc
    if report["schema_version"] != 2 or report["advisory"] is not True:
        _fail("visual review output must be schema 2 and explicitly advisory")
    if not isinstance(pairs, (tuple, list)) or not 1 <= len(pairs) <= MAX_PAIRS:
        _fail("capsule pair inventory is empty or excessive")
    verdicts = report["verdicts"]
    if not isinstance(verdicts, list) or len(verdicts) != len(pairs):
        _fail("visual review verdict count must exactly equal the capsule pair count")
    expected = {pair["label"]: pair for pair in pairs}
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
        pair = expected.get(label)
        if pair is None or pair["capture_id"] != capture_id:
            _fail(f"verdicts[{index}] is not an exact capsule semantic identity")
        if label in normalized_by_label:
            _fail(f"visual review output duplicates label {label!r}")
        route = verdict["route"]
        if route not in ROUTES:
            _fail(f"verdicts[{index}].route is unknown")
        matches = verdict["matches_expectation"]
        regression = verdict["semantic_regression"]
        if (
            not isinstance(matches, bool)
            or not isinstance(regression, bool)
            or matches == regression
        ):
            _fail(f"verdicts[{index}] match/regression booleans are inconsistent")
        visible = _text(
            verdict["visible"], f"verdicts[{index}].visible", maximum=MAX_VISIBLE_CHARS
        )
        findings = _validate_findings(verdict["findings"], f"verdicts[{index}].findings")
        has_defect = any(item["severity"] == "defect" for item in findings)
        if regression != has_defect:
            _fail(f"verdicts[{index}] regression must exactly reflect defect findings")
        identical = pair.get("triage", {}).get("byte_identical")
        if not isinstance(identical, bool):
            _fail(f"verdicts[{index}] pair lacks authenticated byte identity")
        if identical:
            if (
                route != "identical"
                or matches is not True
                or regression is not False
                or visible != IDENTICAL_VISIBLE
                or findings
            ):
                _fail(f"verdicts[{index}] byte-identical route is not deterministic")
        elif route == "identical":
            _fail(f"verdicts[{index}] changed pair cannot use the identical route")
        elif route == "sonnet" and (regression or has_defect):
            _fail(f"verdicts[{index}] Sonnet anomalies must reach the strongest tier")
        normalized_by_label[label] = {
            "label": label,
            "capture_id": capture_id,
            "route": route,
            "matches_expectation": matches,
            "semantic_regression": regression,
            "visible": visible,
            "findings": findings,
        }
    if set(normalized_by_label) != set(expected):
        _fail("visual review output is missing capsule labels")
    normalized_verdicts = [normalized_by_label[pair["label"]] for pair in pairs]
    telemetry = _validate_telemetry(
        report["telemetry"], pairs=pairs, verdicts=normalized_verdicts
    )
    return {
        "schema_version": 2,
        "advisory": True,
        "telemetry": telemetry,
        "verdicts": normalized_verdicts,
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
    """Atomically publish only a report already reduced to normalized schema 2."""

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
    # Neutralize pre-encoded entities before introducing our own safe entities.
    # HTML entity decoding is not recursive, so an attacker-controlled
    # ``&commat;``/``&#64;``/``&#58;`` remains literal text in GitHub Markdown.
    text = text.replace("&", "&amp;")
    for character in ("\\", "`", "*", "_", "[", "]", "<", ">"):
        text = text.replace(character, "\\" + character)
    for character, replacement in (("@", "&#64;"), ("#", "&#35;"), (":", "&#58;")):
        text = text.replace(character, replacement)
    return text


def advisory_markdown(report: dict[str, Any]) -> str:
    """Render bounded normalized output without converting advisory defects into a gate."""

    verdicts = report.get("verdicts", []) if isinstance(report, dict) else []
    telemetry = report.get("telemetry", {}) if isinstance(report, dict) else {}
    defects = [item for item in verdicts if item.get("semantic_regression") is True]
    routes = {
        route: sum(item.get("route") == route for item in verdicts)
        for route in ("identical", "sonnet", "fable")
    }
    lines = [
        "## Advisory visual review: regressions reported"
        if defects
        else "## Advisory visual review: clean",
        "",
        f"Reviewed {len(verdicts)} semantic frame pairs; {len(defects)} advisory regression(s).",
        (
            "Routes: "
            f"{routes['identical']} byte-identical, {routes['sonnet']} Sonnet, "
            f"{routes['fable']} Fable."
        ),
    ]
    if isinstance(telemetry, dict):
        calls = telemetry.get("sonnet_calls", 0) + telemetry.get("fable_calls", 0)
        cost = telemetry.get("reported_cost_upper_bound_micro_usd", 0)
        if isinstance(calls, int) and isinstance(cost, int):
            lines.append(
                "Provider calls: "
                f"{calls}; reported-usage cost upper bound: ${cost / 1_000_000:.6f}."
            )
    for verdict in defects:
        lines.extend(
            (
                "",
                f"**{_markdown(verdict['label'])}** (route: {_markdown(verdict['route'])})",
                f"- Seen: {_markdown(verdict['visible'])}",
            )
        )
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
