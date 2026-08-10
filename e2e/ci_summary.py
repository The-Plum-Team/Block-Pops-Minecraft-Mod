#!/usr/bin/env python3
"""Render bounded packaged-E2E timing and RuntimeStore telemetry for Actions."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any


class SummaryError(ValueError):
    pass


SAFE_ID = re.compile(r"^[A-Za-z0-9_.-]+$")


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\n" in value or "\r" in value:
        raise SummaryError(f"{label} must be a non-empty single-line string")
    return value


def render(data: Any) -> str:
    if not isinstance(data, dict):
        raise SummaryError("summary must be an object")
    if set(data) != {"results", "runtime_store"}:
        raise SummaryError("summary must contain exactly results and runtime_store")
    results = data.get("results")
    if not isinstance(results, list) or not results or len(results) > 100:
        raise SummaryError("summary.results must contain 1..100 rows")
    lines = [
        "### Packaged E2E telemetry",
        "",
        "| Artifact | Scenario | Result | Runtime |",
        "|---|---|---:|---:|",
    ]
    total = 0.0
    passed = 0
    for index, result in enumerate(results):
        if not isinstance(result, dict):
            raise SummaryError(f"summary.results[{index}] must be an object")
        artifact = _text(result.get("artifact_node"), f"results[{index}].artifact_node")
        scenario = _text(result.get("scenario"), f"results[{index}].scenario")
        status = _text(result.get("status"), f"results[{index}].status")
        if SAFE_ID.fullmatch(artifact) is None or SAFE_ID.fullmatch(scenario) is None:
            raise SummaryError(f"summary.results[{index}] contains an unsafe identity")
        if status not in {"pass", "fail"}:
            raise SummaryError(f"summary.results[{index}].status is invalid")
        elapsed = result.get("elapsed_s")
        if (
            isinstance(elapsed, bool)
            or not isinstance(elapsed, (int, float))
            or not math.isfinite(elapsed)
            or elapsed < 0
        ):
            raise SummaryError(f"results[{index}].elapsed_s is invalid")
        if status == "pass":
            passed += 1
        total += float(elapsed)
        lines.append(f"| `{artifact}` | `{scenario}` | {status} | {elapsed:.1f}s |")
    lines.extend(("", f"**Scenarios:** {passed}/{len(results)} passed · **runtime:** {total:.1f}s"))

    store = data["runtime_store"]
    if not isinstance(store, dict):
        raise SummaryError("summary.runtime_store must be an object")
    required = {"hits", "misses", "pruned_entries", "pruned_bytes", "total_bytes"}
    if set(store) != required:
        raise SummaryError("summary.runtime_store fields are invalid")
    values: dict[str, int] = {}
    for key in required:
        value = store[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise SummaryError(f"summary.runtime_store.{key} must be a non-negative integer")
        values[key] = value
    lines.extend(
        (
            "",
            "**RuntimeStore:** "
            f"{values['hits']} hit(s), {values['misses']} miss(es), "
            f"{values['pruned_entries']} pruned ({values['pruned_bytes']} bytes), "
            f"{values['total_bytes']} bytes retained.",
        )
    )
    return "\n".join(lines) + "\n"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--github-step-summary", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        data = json.loads(args.summary.read_text(encoding="utf-8"))
        markdown = render(data)
        with args.github_step_summary.open("a", encoding="utf-8") as output:
            output.write(markdown)
    except (OSError, json.JSONDecodeError, SummaryError) as exc:
        print(f"Cannot summarize packaged E2E: {exc}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
