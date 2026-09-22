"""Protected branch-portable matrix fixtures for shared controller tests.

The frozen schema1 JSON contains Git blob 14ccfa09c5fd292a79926c3e30309eb55dbb3b6d
from before preparing enrollment. Hash its checkout bytes, not the historical blob;
this test data is never production authority or release qualification.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

from scripts.release.matrix import EXPECTED_TARGETS, _era_java, _numeric_version

SCHEMA1_MATRIX_PATH = Path(__file__).resolve().parents[3] / "tests/fixtures/release-matrix-schema1.json"

# The migration scope is declared once, by the validator. Tests that count targets
# read it from here, so a growing scope never needs a literal updated per file.
TARGET_COUNT = len(EXPECTED_TARGETS)


def schema1_matrix() -> dict[str, Any]:
    """Return independent frozen input without importing candidate-owned test code."""
    return json.loads(SCHEMA1_MATRIX_PATH.read_bytes())


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


def schema2_configuration(*, shared: bool = False) -> dict[str, Any]:
    """Synthetic controller test inputs; versions/hashes are not qualification pins."""
    matrix = schema1_matrix()
    artifact_template, runtime_template = copy.deepcopy(matrix["artifacts"][0]), copy.deepcopy(matrix["runtimes"][0])
    matrix["schema_version"] = 2
    # The migration scope is declared once, by the validator. Deriving the fixture from
    # it keeps these controller tests honest when the scope grows.
    matrix["targets"] = [
        {"artifact_node": f"{loader}-{minecraft}", "loader": loader, "minecraft": minecraft}
        for minecraft, loader in sorted(
            ((minecraft, loader) for loader, minecraft in EXPECTED_TARGETS),
            key=lambda pair: (_numeric_version(pair[0]), pair[1]),
        )
    ]
    legacy = ["fabric-1.20.1", "forge-1.20.1"]
    # One Gradle runtime serves every generated lane, exactly as the validator
    # requires, so the era that needs the newest Java decides it.
    selected = [target["minecraft"] for target in matrix["targets"]
                if shared or target["minecraft"] in {"1.20.1", "1.21.1"}]
    matrix["gradle_java"] = max(_era_java(minecraft) for minecraft in selected)
    matrix["migration"] = {"mode": "shared" if shared else "preparing", "legacy_nodes": [] if shared else legacy}
    matrix["artifacts"], matrix["runtimes"], matrix["installers"] = [], [], {}
    matrix["source_routing"] = {
        module: {"canonical": f"{module}/src/main", "e2e": f"{module}/src/e2e", "overlays": []}
        for module in ("common", "fabric", "forge", "neoforge")
    }
    for target in matrix["targets"]:
        node, loader, minecraft = (target[key] for key in ("artifact_node", "loader", "minecraft"))
        if not shared and minecraft not in {"1.20.1", "1.21.1"}:
            continue
        artifact, runtime = copy.deepcopy(artifact_template), copy.deepcopy(runtime_template)
        is_legacy = not shared and node in legacy
        prefix = f":{loader}:" if is_legacy else f":{loader}:{minecraft}:"
        output = loader if is_legacy else f"{loader}/versions/{minecraft}"
        display = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}[loader]
        # 26.1 ships unobfuscated, so its lanes build a shadow jar instead of remapping.
        no_remap = _numeric_version(minecraft)[0] >= 26
        artifact.update(target, java=_era_java(minecraft), no_remap=no_remap,
                        mod_version=matrix["project"]["mod_version"] if is_legacy else "2.3.4",
                        build_layout="legacy" if is_legacy else "stonecutter",
                        gradle_java=matrix["gradle_java"],
                        repository_family=loader, source_routes=["common", loader],
                        gradle_task=prefix + ("shadowJar" if no_remap else "remapJar"),
                        harness_task=prefix + ("e2eHarnessJar" if no_remap else "remapE2EHarnessJar"),
                        jar=f"{output}/build/libs/BlockPops - {display} - {minecraft}-{{mod_version}}.jar",
                        harness_jar=f"{output}/build/libs/BlockPops E2E - {display} - {minecraft}-0.0.0.jar")
        artifact["metadata"]["file"] = {"fabric": "fabric.mod.json", "forge": "META-INF/mods.toml",
                                          "neoforge": "META-INF/neoforge.mods.toml"}[loader]
        artifact["metadata"]["minecraft"] = f"~{minecraft}" if loader == "fabric" else f"[{minecraft}]"
        # NeoForge mirrors the game version: it drops the leading "1." up to 1.21.x,
        # and from 26.1 there is no such prefix to drop.
        neoforge = (f"{minecraft}.1" if _numeric_version(minecraft)[0] >= 26
                    else f"21.{minecraft.split('.')[-1]}.1")
        version = "0.17.3" if loader == "fabric" else (f"{minecraft}-47.4.9" if loader == "forge" else neoforge)
        installer = "fabric-1.1.0" if loader == "fabric" else f"{loader}-{version}"
        origin = {"forge": "https://maven.minecraftforge.net/net/minecraftforge/forge",
                  "neoforge": "https://maven.neoforged.net/releases/net/neoforged/neoforge"}
        url = ("https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.0/fabric-installer-1.1.0.jar"
               if loader == "fabric" else f"{origin[loader]}/{version}/{loader}-{version}-installer.jar")
        matrix["installers"][installer] = {"url": url, "sha256": "a" * 64}
        dependencies = [
            ("architectury", f"dev.architectury:architectury-{loader}:13.0.8", "https://maven.architectury.dev/"),
            ("geckolib", f"{'com.geckolib' if _numeric_version(minecraft)[0] >= 26 else 'software.bernie.geckolib'}"
                          f":geckolib-{loader}-{minecraft}:4.8",
             "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/"),
        ]
        if loader == "fabric":
            dependencies.append(("fabric-api", f"net.fabricmc.fabric-api:fabric-api:0.110.0+{minecraft}", "https://maven.fabricmc.net/"))
        if loader == "forge":
            dependencies.append(("mclib", "com.eliotlash.mclib:mclib:20", "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/"))
        runtime.update(target, java=artifact["java"], loader_version=version, installer=installer,
                       runtime_dependencies=[{"id": name, "coordinate": coordinate, "repository": repository, "side": "both"}
                                             for name, coordinate, repository in dependencies])
        matrix["artifacts"].append(artifact)
        matrix["runtimes"].append(runtime)
    matrix["lane_count"] = len(matrix["artifacts"])
    matrix["unit_test_lane"] = "fabric-1.20.1"
    return matrix
