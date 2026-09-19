#!/usr/bin/env python3
"""Plan serial, isolated lane builds without starting Gradle or claiming success."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import SecureJsonError, read as read_secure_json  # noqa: E402
from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES, MatrixDocument, MatrixError, normalize_matrix_inventory,
)


def numeric_version(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def plan_build(
    matrix_path: Path, *, scope: str = "full", artifact_node: str | None = None,
    clean: bool = False, windows: bool | None = None,
) -> dict[str, Any]:
    if scope not in {"full", "legacy"}:
        raise MatrixError("build scope must be full or legacy")
    matrix_path = matrix_path.parent.resolve() / matrix_path.name
    repository = matrix_path.parents[1]
    data, payload = read_secure_json(matrix_path, label="release matrix", max_bytes=MAX_MATRIX_BYTES)
    document = MatrixDocument(
        normalize_matrix_inventory(data, repository=repository), json.dumps(data),
    )
    if artifact_node is not None and scope != "full":
        raise MatrixError("artifact_node cannot be combined with an explicit scope")
    selected_scope = "lane" if artifact_node is not None else scope
    lanes = sorted(document.select_lanes(scope=selected_scope, artifact_node=artifact_node),
                   key=lambda lane: (numeric_version(lane.identity.minecraft), lane.identity.loader))
    if any(lane.gradle_java != 21 for lane in lanes):
        raise MatrixError("the serial runner requires Gradle Java 21")
    wrapper = "gradlew.bat" if (os.name == "nt" if windows is None else windows) else "./gradlew"
    planned = []
    for lane in lanes:
        artifact, runtime = lane.artifact, lane.runtime
        if artifact["java"] not in {17, 21} or runtime["java"] not in {17, 21}:
            raise MatrixError("the serial runner requires artifact/runtime Java 17 or 21")
        home = repository / "build/gradle-home" / lane.identity.artifact_node
        command = [wrapper, "--no-daemon", "--no-parallel", "--max-workers=1",
                   "--dependency-verification", "strict", "--gradle-user-home", str(home),
                   f"-PblockpopsLane={lane.identity.artifact_node}"]
        if clean:
            command.append(artifact["gradle_task"].rsplit(":", 1)[0] + ":clean")
        command.extend(["validateReleaseMatrix", artifact["gradle_task"], artifact["harness_task"]])
        planned.append({
            "artifact_node": lane.identity.artifact_node,
            "minecraft": lane.identity.minecraft, "loader": lane.identity.loader,
            "build_layout": lane.build_layout, "gradle_user_home": str(home),
            "required_java": {"gradle": 21, "artifact": artifact["java"], "runtime": runtime["java"]},
            "cwd": str(repository), "command": command,
            "outputs": {"production": str(repository / lane.production_jar),
                        "harness": str(repository / lane.harness_jar)},
        })
    selected_nodes = [lane["artifact_node"] for lane in planned]
    return {
        "schema_version": 1, "kind": "blockpops-build-plan", "status": "planned",
        "scope": selected_scope, "selected_nodes": selected_nodes,
        "target_nodes": list(document.inventory.target_nodes),
        "partial_scope": set(selected_nodes) != set(document.inventory.target_nodes),
        "matrix": {"path": str(matrix_path), "sha256": hashlib.sha256(payload).hexdigest(),
                   "schema_version": document.inventory.schema_version,
                   "migration_mode": document.inventory.migration_mode},
        "source": {"repository": str(repository), "status": "unverified"},
        "toolchains": {"status": "unverified"}, "lanes": planned,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true", required=True,
                        help="emit a plan only; execution is not implemented")
    parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("full", "legacy"))
    selection.add_argument("--artifact-node")
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args(argv)
    try:
        plan = plan_build(args.matrix, scope=args.scope or "full", artifact_node=args.artifact_node, clean=args.clean)
    except (MatrixError, SecureJsonError, OSError) as exc:
        print(f"build matrix plan failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(plan, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
