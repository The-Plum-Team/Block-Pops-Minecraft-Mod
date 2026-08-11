#!/usr/bin/env python3
"""Classify bounded workflow failures without making an AI-repair decision."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass


CODE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
MAX_DETAIL = 2048

EXTERNAL_CAPACITY = frozenset(
    {
        "actions_quota",
        "artifact_quota",
        "billing_limit",
        "provider_overloaded",
        "provider_rate_limit",
        "runner_capacity",
        "storage_quota",
    }
)
TRANSIENT_NETWORK = frozenset(
    {
        "connection_reset",
        "dns_failure",
        "http_502",
        "http_503",
        "http_504",
        "network_timeout",
        "provider_timeout",
        "tls_failure",
    }
)
CODE_TEST = frozenset(
    {"assertion_failure", "build_failure", "compile_failure", "test_failure"}
)
SECURITY_POLICY = frozenset(
    {
        "capsule_invalid",
        "digest_mismatch",
        "path_traversal",
        "policy_violation",
        "provenance_mismatch",
        "symlink_rejected",
    }
)
AUTHENTICATION = frozenset(
    {
        "credential_missing",
        "github_authentication",
        "http_401",
        "http_403",
        "provider_authentication",
    }
)
PROVIDER_UNKNOWN = frozenset({"provider_error", "provider_output_invalid"})


@dataclass(frozen=True)
class FailureClassification:
    category: str
    code: str
    retain_queue: bool
    retryable: bool

    def manifest(self) -> dict[str, object]:
        # Deliberately contains no repair/AI-routing field.
        return {
            "schema_version": 1,
            "category": self.category,
            "code": self.code,
            "retain_queue": self.retain_queue,
            "retryable": self.retryable,
        }


def _detail_code(detail: str) -> str | None:
    """Recognize only a few operational signatures; never echo diagnostics."""

    if not isinstance(detail, str):
        return None
    try:
        if len(detail.encode("utf-8")) > MAX_DETAIL:
            return None
    except UnicodeEncodeError:
        return None
    normalized = " ".join(detail.lower().split())
    if "artifact" in normalized and any(
        token in normalized for token in ("quota", "storage limit", "storage usage")
    ):
        return "artifact_quota"
    if "http 429" in normalized or "too many requests" in normalized:
        return "provider_rate_limit"
    if any(token in normalized for token in ("http 502", "bad gateway")):
        return "http_502"
    if any(token in normalized for token in ("http 503", "service unavailable")):
        return "http_503"
    if any(token in normalized for token in ("http 504", "gateway timeout")):
        return "http_504"
    if "connection reset" in normalized:
        return "connection_reset"
    return None


def classify_failure(code: str, *, detail: str = "") -> FailureClassification:
    """Classify a trusted structured code, retaining every ambiguous failure."""

    normalized = code if isinstance(code, str) and CODE.fullmatch(code) else "unknown"
    if normalized == "unknown":
        detected = _detail_code(detail)
        normalized = detected or "unknown"
    if normalized in EXTERNAL_CAPACITY:
        return FailureClassification("external_capacity", normalized, True, True)
    if normalized in TRANSIENT_NETWORK:
        return FailureClassification("transient_network", normalized, True, True)
    if normalized in CODE_TEST:
        return FailureClassification("code_test", normalized, False, False)
    if normalized in SECURITY_POLICY:
        return FailureClassification("security_policy", normalized, False, False)
    if normalized in AUTHENTICATION:
        return FailureClassification("security_policy", normalized, True, False)
    if normalized in PROVIDER_UNKNOWN:
        return FailureClassification("unknown", normalized, True, False)
    return FailureClassification("unknown", "unknown", True, False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", default="unknown")
    parser.add_argument("--detail", default="")
    args = parser.parse_args(argv)
    print(
        json.dumps(
            classify_failure(args.code, detail=args.detail).manifest(),
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
