#!/usr/bin/env python3
"""Run exact staged BlockPops jars in isolated production Minecraft runtimes."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))

from e2e.packaged_runtime import (  # noqa: E402
    PackagedRuntimeSession,
    RuntimeFailure,
    run_packaged_row,
)
from e2e.runtime_store import RunWorkspace, RuntimeStoreError, WorkspacePromotion  # noqa: E402
from e2e.scenario_contract import default_contract  # noqa: E402
from scripts.lib.secure_json import SecureJsonError, canonical_json, loads, read as read_secure_json  # noqa: E402
from scripts.release.artifact_manifest import (  # noqa: E402
    ArtifactError,
    verify_staged,
)
from scripts.release.matrix import MAX_MATRIX_BYTES, MatrixDocument, MatrixError, normalize_matrix_inventory  # noqa: E402

CONTRACT = default_contract()
MAX_ROW_JSON_BYTES = 64 * 1024


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument(
        "--artifacts-manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    parser.add_argument("--row-json")
    parser.add_argument("--projection", choices=("pr-anchors", "scheduled-anchors"), default="pr-anchors",
                        help="authoritative projection used to validate --row-json")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--artifact-node")
    selection.add_argument("--scope", choices=("legacy", "full"))
    parser.add_argument("--minecraft")
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"))
    parser.add_argument("--scenarios")
    parser.add_argument("--output-root", type=Path, default=Path("e2e-out"))
    parser.add_argument("--packaged", action="store_true")
    parser.add_argument("--list", action="store_true")
    return parser.parse_args(argv)


def absolute(path: Path) -> Path:
    # Preserve parent links for the scoped verifier instead of resolving them away.
    return path if path.is_absolute() else REPO / path


def _selection(args: argparse.Namespace) -> dict[str, Any]:
    return {"scope": "lane" if args.artifact_node else args.scope,
            "artifact_node": args.artifact_node}


def select_rows(document: MatrixDocument, args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.projection not in {"pr-anchors", "scheduled-anchors"}:
        raise ValueError("runtime row projection must be pr-anchors or scheduled-anchors")
    if document.inventory.schema_version == 2 and (args.minecraft or args.loader) and not args.artifact_node:
        raise ValueError("schema-2 version/loader filters require an explicit --artifact-node")
    selection = _selection(args)
    rows = [lane.runtime for lane in document.select_lanes(**selection)]
    if args.row_json:
        try:
            requested = loads(
                args.row_json.encode("utf-8"),
                label="--row-json",
                max_bytes=MAX_ROW_JSON_BYTES,
            )
        except (UnicodeError, SecureJsonError) as exc:
            raise ValueError(f"invalid --row-json: {exc}") from exc
        matches = [
            projected for projected in document.projection(
                args.projection, contract=CONTRACT, **selection,
            )["include"] if canonical_json(projected) == canonical_json(requested)
        ]
        if len(matches) != 1:
            raise ValueError(f"--row-json is not one exact authoritative {args.projection} row")
        identity = matches[0]["artifact_node"]
        rows = [row for row in rows if row["artifact_node"] == identity]
    if args.artifact_node:
        rows = [row for row in rows if row["artifact_node"] == args.artifact_node]
    if args.minecraft:
        rows = [row for row in rows if row["minecraft"] == args.minecraft]
    if args.loader:
        rows = [row for row in rows if row["loader"] == args.loader]
    if not rows:
        raise ValueError("runtime selection is empty")
    return rows


def scenarios_for(args: argparse.Namespace) -> list[str]:
    scenarios = (
        [value.strip() for value in args.scenarios.split(",") if value.strip()]
        if args.scenarios
        else list(CONTRACT.scenarios_for_profile("runtime-default"))
    )
    if not scenarios or len(scenarios) != len(set(scenarios)):
        raise ValueError("scenario selection must be non-empty and duplicate-free")
    known = set(CONTRACT.scenario_ids)
    unknown = set(scenarios) - known
    if unknown:
        raise ValueError(f"unknown E2E scenarios: {sorted(unknown)}")
    return scenarios


def manifest_hash(manifest: dict[str, Any] | None, node: str) -> str:
    if manifest is None:
        return "from:artifact-manifest"
    records = [
        record for record in manifest["artifacts"] if record["artifact_node"] == node
    ]
    if len(records) != 1:
        raise ValueError(f"artifact manifest has {len(records)} rows for {node}")
    return records[0]["production"]["sha256"]


def execution_scope(document: MatrixDocument, rows: list[dict[str, Any]],
                    scenarios: list[str], manifest: dict[str, Any] | None,
                    args: argparse.Namespace) -> dict[str, Any]:
    if document.inventory.schema_version == 1:
        return {}
    nodes = [row["artifact_node"] for row in rows]
    return {"execution_scope": {
        "kind": "lane" if args.row_json else _selection(args)["scope"] or document.default_scope,
        "selected_nodes": nodes, "scenarios": scenarios,
        "target_nodes": list(document.inventory.target_nodes),
        "partial": set(nodes) != set(document.inventory.target_nodes),
        "artifact_scope": None if manifest is None else manifest["scope"],
    }}


def print_rows(
    rows: list[dict[str, Any]],
    scenarios: list[str],
    manifest: dict[str, Any] | None,
    *, document: MatrixDocument | None = None, scope: str | None = None,
    coverage: dict[str, Any] | None = None,
) -> None:
    resolved = [
        {
            "artifact_node": row["artifact_node"],
            "minecraft": row["minecraft"],
            "loader": row["loader"],
            "scenario": scenario,
            "production_jar_sha256": manifest_hash(manifest, row["artifact_node"]),
        }
        for row in rows
        for scenario in scenarios
    ]
    output = {"schema_version": 1, "rows": resolved, **(coverage or {})}
    if document is not None and document.inventory.schema_version == 2:
        output.update(scope=scope or document.default_scope,
                      migration_mode=document.inventory.migration_mode,
                      target_count=len(document.inventory.targets),
                      configured_lane_count=len(document.inventory.lanes))
    print(json.dumps(output, indent=2))


def _write_json(path: Path, value: Any) -> None:
    encoded = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(encoded) > 16 * 1024 * 1024:
        raise RuntimeFailure(f"orchestrator output is oversized: {path.name}")
    path.write_bytes(encoded)


def execute_packaged_rows(
    data: dict[str, Any],
    rows: list[dict[str, Any]],
    scenarios: list[str],
    manifest: dict[str, Any],
    manifest_path: Path,
    output_root: Path,
    *, coverage: dict[str, Any] | None = None,
    verify_inputs: Callable[[], None] | None = None,
) -> tuple[list[dict[str, Any]], WorkspacePromotion]:
    results: list[dict[str, Any]] = []
    with RunWorkspace.create(output_root, prefix=".evidence-run-") as evidence:
        with tempfile.TemporaryDirectory(
            prefix="blockpops-e2e-scratch-parent-"
        ) as scratch_parent, RunWorkspace.create(
            Path(scratch_parent), prefix=".runtime-run-"
        ) as scratch:
            runtime_session = PackagedRuntimeSession.from_environment(scratch.path)
            for row in rows:
                for scenario in scenarios:
                    print(
                        f">>> {row['artifact_node']} / {row['loader']} / {scenario}",
                        flush=True,
                    )
                    result = run_packaged_row(
                        REPO,
                        data,
                        row,
                        scenario,
                        manifest,
                        manifest_path,
                        evidence.path,
                        runtime_session,
                    )
                    results.append(result)
                    print(
                        f"<<< {result['status'].upper()} ({result['elapsed_s']}s)"
                        + (f": {result['error']}" if result.get("error") else ""),
                        flush=True,
                    )
            store_metrics = runtime_session.gc()

        resolved = [
            {
                "artifact_node": result["artifact_node"],
                "minecraft": result["minecraft"],
                "loader": result["loader"],
                "scenario": result["scenario"],
                "production_jar_sha256": result["production_jar_sha256"],
            }
            for result in results
        ]
        _write_json(
            evidence.path / "resolved-matrix.json",
            {"schema_version": 1, "rows": resolved, **(coverage or {})},
        )
        _write_json(
            evidence.path / "summary.json",
            {
                "schema_version": 1,
                "contract_sha256": CONTRACT.sha256,
                "results": results,
                "runtime_store": store_metrics,
                **(coverage or {}),
            },
        )
        _write_json(
            evidence.path / "runtime-store.json",
            {"schema_version": 1, "metrics": store_metrics},
        )
        if verify_inputs is not None:
            verify_inputs()
        promotion = evidence.promote_to(output_root / "current")
    return results, promotion


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    matrix_path = absolute(args.matrix)
    manifest_path = absolute(args.artifacts_manifest)
    output_root = absolute(args.output_root)
    try:
        data, matrix_bytes = read_secure_json(matrix_path, label="release matrix", max_bytes=MAX_MATRIX_BYTES)
        document = MatrixDocument(normalize_matrix_inventory(data, repository=matrix_path.resolve().parents[1]),
                                  json.dumps(data))
        matrix_digest = hashlib.sha256(matrix_bytes).hexdigest()
        rows = select_rows(document, args)
        scenarios = scenarios_for(args)
        scoped = document.inventory.schema_version == 2
        if scoped and manifest_path.exists() and not (args.artifact_node or args.scope):
            raise ValueError("schema-2 staged execution/listing requires --artifact-node or --scope")

        def verify_manifest():
            verified = verify_staged(
                repository=REPO,
                matrix_path=matrix_path,
                manifest_path=manifest_path,
                stage=manifest_path.parent,
                **(_selection(args) if scoped else {}),
            )
            if scoped and (verified["matrix"]["sha256"] != matrix_digest
                           or verified["scenario_contract"]["sha256"] != CONTRACT.sha256):
                raise ArtifactError("staged matrix/contract differs from the loaded execution inputs")
            return verified
        manifest = verify_manifest() if manifest_path.exists() else None
        coverage = execution_scope(document, rows, scenarios, manifest, args)
        if args.list:
            print_rows(rows, scenarios, manifest, document=document,
                       scope=coverage.get("execution_scope", {}).get("kind"), coverage=coverage)
            return 0
        if not args.packaged:
            raise ValueError("pass --packaged to run staged production jars")
        if manifest is None:
            raise ValueError(f"packaged execution requires {manifest_path}")
        def recheck():
            if verify_manifest() != manifest:
                raise ArtifactError("staged inputs changed during packaged execution")
        options = {"coverage": coverage, "verify_inputs": recheck} if scoped else {}
        results, promotion = execute_packaged_rows(
            data, rows, scenarios, manifest, manifest_path, output_root, **options)
        passed = sum(result["status"] == "pass" for result in results)
        action = "replaced" if promotion.replaced else "created"
        print(f"{passed}/{len(results)} packaged rows passed; {action} {promotion.current}")
        return 0 if passed == len(results) else 1
    except (
        ArtifactError,
        MatrixError,
        RuntimeFailure,
        RuntimeStoreError,
        SecureJsonError,
        ValueError,
        OSError,
    ) as exc:
        print(f"E2E configuration failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
