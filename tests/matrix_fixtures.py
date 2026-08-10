"""Branch-portable matrix fixtures for shared controller tests."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any


def canonical_integration_matrix(source: dict[str, Any]) -> dict[str, Any]:
    """Derive a valid canonical policy fixture from any enrolled branch matrix.

    The production matrix remains authoritative.  Tests need a canonical Git
    parent as well as the current branch, so this projects only the stable
    visual-reference lane instead of copying a default-branch version list.
    """

    matrix = copy.deepcopy(source)
    reference = matrix["visual_reference"]
    node = reference["artifact_node"]
    loader, separator, minecraft = node.partition("-")
    if separator != "-" or loader != "fabric" or not minecraft:
        raise ValueError("the canonical visual reference must be a Fabric lane")
    artifact = copy.deepcopy(
        next(row for row in matrix["artifacts"] if row["loader"] == loader)
    )
    runtime = copy.deepcopy(
        next(row for row in matrix["runtimes"] if row["loader"] == loader)
    )
    artifact.update(
        artifact_node=node,
        minecraft=minecraft,
        jar=f"fabric/build/libs/BlockPops - Fabric - {minecraft}-{{mod_version}}.jar",
        harness_jar=(
            f"fabric/build/libs/BlockPops E2E - Fabric - {minecraft}-0.0.0.jar"
        ),
    )
    artifact["metadata"]["minecraft"] = f"~{minecraft}"
    runtime.update(artifact_node=node, minecraft=minecraft)
    canonical = reference["release_branch"]
    matrix["branch"] = {
        "role": "integration",
        "name": canonical,
        "canonical": canonical,
        "sync": {"enabled": False, "source": canonical},
    }
    matrix["lane_count"] = 1
    matrix["unit_test_lane"] = node
    matrix["artifacts"] = [artifact]
    matrix["runtimes"] = [runtime]
    matrix["source_routing"] = {
        key: value
        for key, value in matrix["source_routing"].items()
        if key in {"common", loader}
    }
    matrix["installers"] = {
        runtime["installer"]: matrix["installers"][runtime["installer"]]
    }
    return matrix


def reference_runtime(matrix: dict[str, Any]) -> dict[str, Any]:
    """Return the semantic identity of the matrix's protected visual lane."""

    node = matrix["visual_reference"]["artifact_node"]
    loader, separator, minecraft = node.partition("-")
    if separator != "-" or not loader or not minecraft:
        raise ValueError("visual reference artifact_node is malformed")
    return {
        "artifact_node": node,
        "loader": loader,
        "minecraft": minecraft,
    }


def write_matrix_fixture(root: Path, matrix: dict[str, Any]) -> Path:
    """Write a matrix below a minimal repository-shaped source tree."""

    repository = root / "matrix-repository"
    for route in matrix["source_routing"].values():
        (repository / route["canonical"]).mkdir(parents=True, exist_ok=True)
        for overlay in route["overlays"].values():
            (repository / overlay).mkdir(parents=True, exist_ok=True)
    path = repository / "release" / "release-matrix.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(matrix, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return path
