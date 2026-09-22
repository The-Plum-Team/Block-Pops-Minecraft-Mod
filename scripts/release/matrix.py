#!/usr/bin/env python3
"""Validate and project BlockPops' branch-local release matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field, replace
from pathlib import Path, PurePosixPath
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
sys.path.insert(0, str(REPO / "e2e"))

from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    read as read_secure_json,
    require_object,
)
from scenario_contract import ScenarioContract, default_contract  # noqa: E402

MAX_MATRIX_BYTES = 256 * 1024
KNOWN_LOADERS = frozenset({"fabric", "forge", "neoforge"})
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COORDINATE = re.compile(
    r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\-]+$"
)

ROOT_KEYS = frozenset(
    {
        "schema_version",
        "gradle_java",
        "lane_count",
        "unit_test_lane",
        "branch",
        "project",
        "visual_reference",
        "source_routing",
        "installers",
        "artifacts",
        "runtimes",
    }
)
PROJECT_KEYS = frozenset(
    {
        "name",
        "mod_id",
        "description",
        "mod_version",
        "sources",
        "issues",
        "license",
    }
)
ARTIFACT_KEYS = frozenset(
    {
        "artifact_node",
        "minecraft",
        "loader",
        "java",
        "no_remap",
        "gradle_task",
        "harness_task",
        "jar",
        "harness_jar",
        "metadata",
    }
)
RUNTIME_KEYS = frozenset(
    {
        "artifact_node",
        "minecraft",
        "loader",
        "java",
        "loader_version",
        "installer",
        "pr_anchor",
        "scheduled_anchor",
        "runtime_dependencies",
    }
)
SCHEMA2_ROOT_KEYS = ROOT_KEYS | {"targets", "migration"}
SCHEMA2_ARTIFACT_KEYS = ARTIFACT_KEYS | {
    "mod_version", "build_layout", "gradle_java", "repository_family", "source_routes",
}
TARGET_KEYS = frozenset({"artifact_node", "minecraft", "loader"})
EXPECTED_TARGETS = frozenset(
    {
        ("fabric", "1.20.1"),
        ("forge", "1.20.1"),
        ("fabric", "1.21.1"),
        ("neoforge", "1.21.1"),
        ("fabric", "1.21.4"),
        ("neoforge", "1.21.4"),
        ("fabric", "1.21.5"),
        ("neoforge", "1.21.5"),
        ("fabric", "1.21.6"),
        ("neoforge", "1.21.6"),
        ("fabric", "1.21.7"),
        ("neoforge", "1.21.7"),
        ("fabric", "1.21.8"),
        ("neoforge", "1.21.8"),
        ("fabric", "1.21.10"),
        ("neoforge", "1.21.10"),
        ("fabric", "1.21.11"),
        ("neoforge", "1.21.11"),
        ("fabric", "26.1.2"),
        ("neoforge", "26.1.2"),
        ("fabric", "26.2"),
        ("neoforge", "26.2"),
        ("fabric", "26.3"),
        ("neoforge", "26.3"),
    }
)
LEGACY_TARGET_NODES = frozenset({"fabric-1.20.1", "forge-1.20.1"})


@dataclass(frozen=True)
class LaneIdentity:
    """A validated loader/Minecraft identity from the authoritative matrix."""

    artifact_node: str
    minecraft: str
    loader: str


@dataclass(frozen=True)
class LaneConfiguration:
    """Validated configuration snapshots; neither builds nor qualification evidence."""

    identity: LaneIdentity
    mod_version: str
    build_layout: str
    gradle_java: int
    repository_family: str
    source_routes: tuple[str, ...]
    _artifact_json: str = field(repr=False)
    _runtime_json: str = field(repr=False)

    @property
    def artifact(self) -> dict[str, Any]:
        return json.loads(self._artifact_json)

    @property
    def runtime(self) -> dict[str, Any]:
        return json.loads(self._runtime_json)

    @property
    def production_jar(self) -> str:
        return self.artifact["jar"].replace("{mod_version}", self.mod_version)

    @property
    def harness_jar(self) -> str:
        return self.artifact["harness_jar"]


@dataclass(frozen=True)
class MatrixInventory:
    """Schema-neutral inventory; execution support remains an explicit boundary."""

    schema_version: int
    targets: tuple[LaneIdentity, ...]
    migration_mode: str | None
    legacy_nodes: tuple[str, ...]
    execution_supported: bool
    lanes: tuple[LaneConfiguration, ...] = ()
    sources_checked: bool = False

    @property
    def target_nodes(self) -> tuple[str, ...]:
        return tuple(target.artifact_node for target in self.targets)

    @property
    def configured_nodes(self) -> tuple[str, ...]:
        return tuple(lane.identity.artifact_node for lane in self.lanes)

    def lane(self, node: str) -> LaneConfiguration:
        for lane in self.lanes:
            if lane.identity.artifact_node == node:
                return lane
        if node in self.target_nodes:
            _fail(f"unresolved target {node}: missing artifact and runtime configuration")
        _fail(f"unknown target {node!r}")

    def require_complete(self) -> tuple[LaneConfiguration, ...]:
        """Require complete configuration, without claiming source/build qualification."""
        missing = sorted(set(self.target_nodes) - set(self.configured_nodes))
        if missing:
            _fail(f"unresolved targets (missing artifact and runtime configuration): {', '.join(missing)}")
        return self.lanes

    def require_configured(self) -> tuple[LaneConfiguration, ...]:
        """Select every configured lane, leaving unresolved targets explicitly out.

        Unlike require_complete this does not demand that every declared target is
        configured, so it never presents a partial migration as a complete one. It
        also claims no build, harness or gameplay qualification for the lanes it
        returns; callers that need that must check their own evidence.
        """
        if not self.lanes:
            _fail("no configured lane is available for the configured scope")
        unknown = sorted(set(self.configured_nodes) - set(self.target_nodes))
        if unknown:
            _fail(f"configured lanes are not declared targets: {', '.join(unknown)}")
        return self.lanes

    def report(self) -> dict[str, Any]:
        configured = set(self.configured_nodes)
        return {
            "schema_version": self.schema_version,
            "migration_mode": self.migration_mode,
            "target_count": len(self.targets),
            "lane_count": len(self.lanes),
            "configuration_complete": configured == set(self.target_nodes),
            "sources_checked": self.sources_checked,
            "execution_supported": self.execution_supported,
            "targets": [
                {
                    "artifact_node": target.artifact_node,
                    "minecraft": target.minecraft,
                    "loader": target.loader,
                    "configuration": "configured" if target.artifact_node in configured else "unresolved",
                    "missing_inputs": [] if target.artifact_node in configured else ["artifact", "runtime"],
                }
                for target in self.targets
            ],
        }


class MatrixError(ValueError):
    """Raised when the authoritative branch matrix is inconsistent."""


@dataclass(frozen=True)
class MatrixDocument:
    """Validated input for individually adapted consumers; raw schema stays intact."""

    inventory: MatrixInventory
    _matrix_json: str = field(repr=False)

    @property
    def data(self) -> dict[str, Any]:
        return json.loads(self._matrix_json)

    @property
    def branch_name(self) -> str:
        return self.data["branch"]["name"]

    @property
    def default_scope(self) -> str:
        return "legacy" if self.inventory.migration_mode == "preparing" else "full"

    def select_lanes(
        self, *, scope: str | None = None, artifact_node: str | None = None,
    ) -> tuple[LaneConfiguration, ...]:
        selected_scope = self.default_scope if scope is None else scope
        if selected_scope == "lane":
            if artifact_node is None:
                _fail("lane scope requires one explicit artifact_node")
            return (self.inventory.lane(artifact_node),)
        if artifact_node is not None:
            _fail("artifact_node is only valid with lane scope")
        if selected_scope == "full":
            return self.inventory.require_complete()
        if selected_scope == "configured":
            return self.inventory.require_configured()
        if selected_scope == "legacy" and self.inventory.migration_mode == "preparing":
            legacy = set(self.inventory.legacy_nodes)
            return tuple(lane for lane in self.inventory.lanes if lane.identity.artifact_node in legacy)
        _fail(f"unsupported matrix scope {selected_scope!r}")

    def projection(
        self, kind: str, *, scope: str | None = None, artifact_node: str | None = None,
        contract: ScenarioContract | None = None,
    ) -> dict[str, Any]:
        lanes = self.select_lanes(scope=scope, artifact_node=artifact_node)
        return gha_matrix({
            "artifacts": [lane.artifact for lane in lanes],
            "runtimes": [lane.runtime for lane in lanes],
            "gradle_java": self.data["gradle_java"],
        }, kind, contract=contract)

    def gradle_context(self, *, artifact_node: str | None = None) -> dict[str, Any]:
        """Describe one isolated build context, or the unchanged legacy aggregate."""
        if artifact_node is None and self.inventory.migration_mode == "shared":
            _fail("shared Gradle configuration requires one explicit artifact_node; use build_matrix.py")
        scope = "lane" if artifact_node is not None else self.default_scope
        lanes = self.select_lanes(scope=scope, artifact_node=artifact_node)
        if artifact_node is None and any(lane.build_layout != "legacy" for lane in lanes):
            _fail("Stonecutter configuration requires one explicit artifact_node")
        eras = {(lane.identity.minecraft, lane.artifact["java"], lane.mod_version) for lane in lanes}
        if len(eras) != 1:
            _fail("one Gradle process requires one Minecraft/Java/mod-version context")
        families = {lane.repository_family for lane in lanes}
        if {"forge", "neoforge"} <= families:
            _fail("one Gradle process cannot mix Forge and NeoForge repository families")
        minecraft, _, _ = next(iter(eras))
        annotation_node = f"fabric-{minecraft}"
        try:
            annotation_lane = self.inventory.lane(annotation_node)
        except MatrixError as exc:
            raise MatrixError(f"common annotations require configured {annotation_node}: {exc}") from exc
        return {
            "matrix": self.data,
            "scope": scope,
            "artifact_node": artifact_node,
            "common_annotation_dependency": {
                "artifact_node": annotation_node,
                "coordinate": f"net.fabricmc:fabric-loader:{annotation_lane.runtime['loader_version']}",
            },
            "lanes": [
                {
                    "artifact": lane.artifact,
                    "runtime": lane.runtime,
                    "mod_version": lane.mod_version,
                    "build_layout": lane.build_layout,
                    "gradle_java": lane.gradle_java,
                    "repository_family": lane.repository_family,
                    "source_routes": list(lane.source_routes),
                }
                for lane in lanes
            ],
        }


def _fail(message: str) -> None:
    raise MatrixError(message)


def _text(value: Any, label: str, *, pattern: re.Pattern[str] | None = None) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        _fail(f"{label} must be a non-empty trimmed string")
    if pattern is not None and pattern.fullmatch(value) is None:
        _fail(f"{label} has an invalid value {value!r}")
    return value


def _integer(value: Any, label: str, *, minimum: int = 1) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        _fail(f"{label} must be an integer of at least {minimum}")
    return value


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        _fail(f"{label} must be a boolean")
    return value


def _safe_path(value: Any, label: str) -> str:
    text = _text(value, label).replace("\\", "/")
    path = PurePosixPath(text)
    if (
        path.is_absolute()
        or text != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
        or ":" in text
        or "\x00" in text
    ):
        _fail(f"{label} is not a canonical repository-relative path")
    return text


def valid_branch_name(name: Any) -> bool:
    if not isinstance(name, str) or not 1 <= len(name.encode("utf-8")) <= 240:
        return False
    if (
        name != name.strip()
        or name.startswith(("/", "."))
        or name.endswith(("/", ".", ".lock"))
        or "//" in name
        or ".." in name
        or "@{" in name
        or "\\" in name
        or name == "@"
        or any(character in name for character in " ~^:?*[")
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in name)
        or any(component.startswith(".") for component in name.split("/"))
    ):
        return False
    return True


def _validate_configuration(
    data: Any,
    *,
    contract: ScenarioContract | None = None,
    repository: Path | None = None,
    inventory: MatrixInventory | None = None,
) -> dict[str, Any]:
    schema2 = inventory is not None
    try:
        root = require_object(
            data, label="release matrix",
            required=SCHEMA2_ROOT_KEYS if schema2 else ROOT_KEYS,
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc
    if root["schema_version"] != (2 if schema2 else 1):
        _fail("release matrix schema_version disagrees with its inventory")
    gradle_java = _integer(root["gradle_java"], "gradle_java", minimum=21)
    lane_count = _integer(root["lane_count"], "lane_count")

    try:
        project = require_object(root["project"], label="project", required=PROJECT_KEYS)
        branch_record = require_object(
            root["branch"],
            label="branch",
            required={"role", "name", "canonical", "sync"},
        )
        branch_sync = require_object(
            branch_record["sync"],
            label="branch.sync",
            required={"enabled", "source"},
        )
        visual = require_object(
            root["visual_reference"],
            label="visual_reference",
            required={"release_branch", "artifact_node", "scenario_contract"},
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc
    for key in PROJECT_KEYS:
        _text(project[key], f"project.{key}")
    if not re.fullmatch(r"[a-z][a-z0-9_-]{1,63}", project["mod_id"]):
        _fail("project.mod_id is unsafe")
    if re.fullmatch(
        r"[0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?",
        project["mod_version"],
    ) is None:
        _fail("project.mod_version must be a bounded semantic release version")
    branch = _text(branch_record["name"], "branch.name")
    canonical = _text(branch_record["canonical"], "branch.canonical")
    source_branch = _text(branch_sync["source"], "branch.sync.source")
    if not all(valid_branch_name(value) for value in (branch, canonical, source_branch)):
        _fail("branch identity contains an unsafe Git ref name")
    role = branch_record["role"]
    sync_enabled = _boolean(branch_sync["enabled"], "branch.sync.enabled")
    if role == "integration":
        if branch != canonical or sync_enabled or source_branch != canonical:
            _fail("integration branch identity/sync policy is inconsistent")
    elif role == "release":
        if branch == canonical or not sync_enabled or source_branch != canonical:
            _fail("release branch identity/sync policy is inconsistent")
    else:
        _fail("branch.role must be integration or release")
    if not project["sources"].startswith("https://") or not project["issues"].startswith("https://"):
        _fail("project source and issue URLs must use HTTPS")

    artifacts = root["artifacts"]
    runtimes = root["runtimes"]
    if not isinstance(artifacts, list) or len(artifacts) != lane_count:
        _fail("artifacts must contain exactly lane_count rows")
    if not isinstance(runtimes, list) or len(runtimes) != lane_count:
        _fail("runtimes must contain exactly lane_count rows")

    by_node: dict[str, dict[str, Any]] = {}
    versions: set[str] = set()
    active_loaders: set[str] = set()
    artifact_java_versions: set[int] = set()
    for index, raw in enumerate(artifacts):
        try:
            artifact = require_object(
                raw, label=f"artifacts[{index}]",
                required=SCHEMA2_ARTIFACT_KEYS if schema2 else ARTIFACT_KEYS,
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        node = _text(artifact["artifact_node"], f"artifacts[{index}].artifact_node")
        minecraft = _text(artifact["minecraft"], f"artifacts[{index}].minecraft")
        if re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", minecraft) is None:
            _fail(f"artifact {node} has an invalid Minecraft version")
        loader = _text(artifact["loader"], f"artifacts[{index}].loader")
        if loader not in KNOWN_LOADERS:
            _fail(f"artifact {node} uses unsupported loader {loader!r}")
        if node != f"{loader}-{minecraft}":
            _fail(f"artifact_node {node!r} must equal {loader}-{minecraft}")
        if node in by_node:
            _fail(f"duplicate artifact_node {node}")
        java = _integer(artifact["java"], f"artifact {node}.java", minimum=17)
        if schema2:
            _validate_schema2_artifact(artifact, root, inventory)
        no_remap = _boolean(artifact["no_remap"], f"artifact {node}.no_remap")
        stonecutter = schema2 and artifact["build_layout"] == "stonecutter"
        prefix = f":{loader}:{minecraft}:" if stonecutter else f":{loader}:"
        output_root = f"{loader}/versions/{minecraft}" if stonecutter else loader
        expected_task = prefix + ("shadowJar" if no_remap else "remapJar")
        expected_harness = prefix + (
            "e2eHarnessJar" if no_remap else "remapE2EHarnessJar"
        )
        if artifact["gradle_task"] != expected_task:
            _fail(f"artifact {node} gradle_task must be {expected_task}")
        if artifact["harness_task"] != expected_harness:
            _fail(f"artifact {node} harness_task must be {expected_harness}")
        for key, marker in (("jar", "BlockPops - "), ("harness_jar", "BlockPops E2E - ")):
            path = _safe_path(artifact[key], f"artifact {node}.{key}")
            if not path.startswith(f"{output_root}/build/libs/") or minecraft not in Path(path).name:
                _fail(f"artifact {node}.{key} disagrees with the target Gradle layout")
            if marker not in Path(path).name:
                _fail(f"artifact {node}.{key} has the wrong archive identity")
            if schema2:
                display = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}[loader]
                version = "{mod_version}" if key == "jar" else "0.0.0"
                expected = f"{output_root}/build/libs/{marker}{display} - {minecraft}-{version}.jar"
                if artifact[key] != expected:
                    _fail(f"artifact {node}.{key} must be {expected}")
        metadata_required = (
            {"file", "minecraft", "loader", "architectury", "geckolib"}
        )
        try:
            metadata = require_object(
                artifact["metadata"],
                label=f"artifact {node}.metadata",
                required=metadata_required,
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        expected_metadata = {
            "fabric": "fabric.mod.json",
            "forge": "META-INF/mods.toml",
            "neoforge": "META-INF/neoforge.mods.toml",
        }[loader]
        if metadata["file"] != expected_metadata:
            _fail(f"artifact {node} names the wrong metadata file")
        for key in metadata_required - {"file"}:
            _text(metadata[key], f"artifact {node}.metadata.{key}")
        by_node[node] = artifact
        versions.add(minecraft)
        active_loaders.add(loader)
        artifact_java_versions.add(java)

    forge_family_loaders = active_loaders & {"forge", "neoforge"}
    if not schema2 and len(forge_family_loaders) > 1:
        _fail("a release branch cannot activate Forge and NeoForge together")

    # Loom refuses to set up Minecraft when the Gradle runtime is older than the
    # game's Java release, so one Gradle runtime serves every lane and it is at
    # least the highest any of them needs. 26.1 raised that floor from 21 to 25.
    if gradle_java < max(artifact_java_versions):
        _fail("gradle_java cannot be lower than an artifact Java toolchain")

    if not schema2 and len(versions) != 1:
        _fail("a release branch must contain exactly one Minecraft version")
    if schema2:
        if not set(inventory.legacy_nodes) <= set(by_node):
            _fail("preparing migration must configure both legacy nodes")
        if inventory.migration_mode == "shared" and set(by_node) != set(inventory.target_nodes):
            _fail("shared migration must configure every target")
    if schema2:
        _text(root["unit_test_lane"], "unit_test_lane")
    if root["unit_test_lane"] not in by_node:
        _fail("unit_test_lane must select an active artifact")

    try:
        routing = require_object(
            root["source_routing"],
            label="source_routing",
            required={"common", *active_loaders},
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc
    for module, raw_route in routing.items():
        try:
            route = require_object(
                raw_route,
                label=f"source_routing.{module}",
                required={"canonical", "e2e", "overlays"} if schema2 else {"canonical", "overlays"},
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        if _safe_path(route["canonical"], f"source_routing.{module}.canonical") != f"{module}/src/main":
            _fail(f"source_routing.{module}.canonical must be {module}/src/main")
        if schema2:
            _validate_schema2_route(module, route, by_node)
            continue
        if not isinstance(route["overlays"], dict):
            _fail(f"source_routing.{module}.overlays must be an object")
        allowed_versions = versions if module == "common" else {
            row["minecraft"] for row in artifacts if row["loader"] == module
        }
        for version, overlay in route["overlays"].items():
            if version not in allowed_versions:
                _fail(f"source_routing.{module} has an unsupported overlay version")
            path = _safe_path(overlay, f"source_routing.{module}.overlays[{version!r}]")
            if not path.startswith(f"{module}/src/legacy"):
                _fail(f"source_routing.{module} overlay must live under {module}/src/legacy*")

    installers = root["installers"]
    if not isinstance(installers, dict) or not installers:
        _fail("installers must be a non-empty object")
    for installer_id, raw_installer in installers.items():
        _text(installer_id, "installer id")
        try:
            installer = require_object(
                raw_installer,
                label=f"installer {installer_id}",
                required={"url", "sha256"},
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        if not _text(installer["url"], f"installer {installer_id}.url").startswith("https://"):
            _fail(f"installer {installer_id} URL must use HTTPS")
        _text(installer["sha256"], f"installer {installer_id}.sha256", pattern=SHA256)

    runtime_nodes: set[str] = set()
    used_installers: set[str] = set()
    for index, raw in enumerate(runtimes):
        try:
            runtime = require_object(
                raw, label=f"runtimes[{index}]", required=RUNTIME_KEYS
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        node = _text(runtime["artifact_node"], f"runtimes[{index}].artifact_node")
        artifact = by_node.get(node)
        if artifact is None or node in runtime_nodes:
            _fail(f"runtime row has unknown or duplicate artifact_node {node!r}")
        if schema2:
            _integer(runtime["java"], f"runtime {node}.java", minimum=17)
        for key in ("minecraft", "loader", "java"):
            if runtime[key] != artifact[key]:
                _fail(f"runtime {node}.{key} disagrees with its artifact")
        _text(runtime["loader_version"], f"runtime {node}.loader_version")
        installer = _text(runtime["installer"], f"runtime {node}.installer")
        if installer not in installers:
            _fail(f"runtime {node} refers to an unknown installer")
        _boolean(runtime["pr_anchor"], f"runtime {node}.pr_anchor")
        _boolean(runtime["scheduled_anchor"], f"runtime {node}.scheduled_anchor")
        if not runtime["pr_anchor"] or not runtime["scheduled_anchor"]:
            _fail(f"runtime {node} must remain a PR and scheduled anchor")
        dependencies = runtime["runtime_dependencies"]
        if not isinstance(dependencies, list) or not dependencies:
            _fail(f"runtime {node} must declare runtime_dependencies")
        dependency_ids: set[str] = set()
        coordinates: set[str] = set()
        for dep_index, raw_dependency in enumerate(dependencies):
            try:
                dependency = require_object(
                    raw_dependency,
                    label=f"runtime {node}.runtime_dependencies[{dep_index}]",
                    required={"id", "coordinate", "repository", "side"},
                )
            except SecureJsonError as exc:
                raise MatrixError(str(exc)) from exc
            dep_id = _text(dependency["id"], f"runtime {node} dependency id")
            coordinate = _text(
                dependency["coordinate"],
                f"runtime {node} dependency coordinate",
                pattern=COORDINATE,
            )
            repository_url = _text(
                dependency["repository"], f"runtime {node} dependency repository"
            )
            if not repository_url.startswith("https://") or not repository_url.endswith("/"):
                _fail(f"runtime {node} dependency repositories must be canonical HTTPS base URLs")
            if schema2:
                _text(dependency["side"], f"runtime {node} dependency side")
            if dependency["side"] not in {"both", "client", "server"}:
                _fail(f"runtime {node} dependency side is unsupported")
            if dep_id in dependency_ids or coordinate in coordinates:
                _fail(f"runtime {node} repeats a runtime dependency")
            dependency_ids.add(dep_id)
            coordinates.add(coordinate)
        required_dependencies = {"architectury", "geckolib"}
        if runtime["loader"] == "fabric":
            required_dependencies.add("fabric-api")
        if runtime["loader"] == "forge" and runtime["minecraft"] == "1.20.1":
            required_dependencies.add("mclib")
        if dependency_ids != required_dependencies:
            _fail(
                f"runtime {node} dependencies must be exactly "
                f"{sorted(required_dependencies)}, found {sorted(dependency_ids)}"
            )
        if schema2:
            _validate_schema2_runtime_context(artifact, runtime, installers[installer])
        runtime_nodes.add(node)
        used_installers.add(installer)
    if runtime_nodes != set(by_node):
        _fail("every artifact must have exactly one runtime row")
    if used_installers != set(installers):
        _fail("every locked installer must be used exactly by the active inventory")

    try:
        visual_branch = _text(
            visual["release_branch"], "visual_reference.release_branch"
        )
        visual_node = _text(visual["artifact_node"], "visual_reference.artifact_node")
        visual_contract = _safe_path(
            visual["scenario_contract"], "visual_reference.scenario_contract"
        )
    except MatrixError:
        raise
    if not valid_branch_name(visual_branch):
        _fail("visual_reference.release_branch is not a safe Git branch name")
    if (visual_branch, visual_node, visual_contract) != (
        canonical,
        "fabric-1.20.1",
        "e2e/scenario-contract.json",
    ):
        _fail(
            "BlockPops visual_reference must remain the stable master/fabric-1.20.1 "
            "lane and canonical scenario contract"
        )
    if branch == visual_branch and visual_node not in by_node:
        _fail("the visual reference branch must contain its canonical artifact node")

    selected_contract = contract or default_contract()
    if not selected_contract.scenarios_for_profile("runtime-default"):
        _fail("scenario contract must provide a runtime-default profile")
    if set(selected_contract.scenarios_for_profile("pr")) != set(
        selected_contract.scenarios_for_profile("release")
    ):
        _fail("PR and release profiles must cover the same protected scenarios")

    if repository is not None:
        if schema2:
            _validate_schema2_source_tree(Path(repository), routing, by_node)
        else:
            validate_source_tree(Path(repository), routing)
    return root


def _validate_schema2_artifact(
    artifact: dict[str, Any], root: dict[str, Any], inventory: MatrixInventory,
) -> None:
    node, loader, minecraft = (
        artifact["artifact_node"], artifact["loader"], artifact["minecraft"],
    )
    if node not in inventory.target_nodes:
        _fail(f"artifact {node} is not a migration target")
    version = _text(artifact["mod_version"], f"artifact {node}.mod_version")
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?", version) is None:
        _fail(f"artifact {node}.mod_version must be a bounded semantic release version")
    legacy = node in inventory.legacy_nodes
    if artifact["build_layout"] != ("legacy" if legacy else "stonecutter"):
        _fail(f"artifact {node}.build_layout disagrees with migration legacy_nodes")
    if legacy and version != root["project"]["mod_version"]:
        _fail(f"artifact {node}.mod_version disagrees with the legacy project version")
    if artifact["java"] != _era_java(minecraft):
        _fail(f"artifact {node}.java disagrees with its Minecraft era")
    if _integer(artifact["gradle_java"], f"artifact {node}.gradle_java") != root["gradle_java"]:
        _fail(f"artifact {node}.gradle_java disagrees with the branch Gradle runtime")
    if artifact["repository_family"] != loader:
        _fail(f"artifact {node}.repository_family must be {loader}")
    if artifact["source_routes"] != ["common", loader]:
        _fail(f"artifact {node}.source_routes must select common and {loader}")


def _validate_schema2_runtime_context(
    artifact: dict[str, Any], runtime: dict[str, Any], installer: dict[str, Any],
) -> None:
    node, loader, minecraft = (
        artifact["artifact_node"], artifact["loader"], artifact["minecraft"],
    )
    version = runtime["loader_version"]
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9.-]+)?", version) is None:
        _fail(f"runtime {node}.loader_version is not a bounded version")
    installer_id = runtime["installer"]
    if loader == "fabric":
        match = re.fullmatch(r"fabric-([0-9]+(?:\.[0-9]+)+)", installer_id)
        if match is None:
            _fail(f"runtime {node} must use a Fabric installer")
        installer_version = match[1]
        expected_url = (
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
            f"{installer_version}/fabric-installer-{installer_version}.jar"
        )
    else:
        if installer_id != f"{loader}-{version}":
            _fail(f"runtime {node} installer identity disagrees with loader_version")
        if loader == "forge":
            prefix = f"{minecraft}-"
            origin = "https://maven.minecraftforge.net/net/minecraftforge/forge"
        else:
            # NeoForge mirrors the game version it targets. Up to 1.21.x it drops the
            # leading "1."; from 26.1 the game version has no such prefix to drop.
            if _numeric_version(minecraft)[0] >= 26:
                prefix = f"{minecraft}."
            else:
                prefix = ".".join(minecraft.split(".")[1:]) + "."
            origin = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
        if not version.startswith(prefix):
            _fail(f"runtime {node}.loader_version disagrees with its Minecraft era")
        expected_url = f"{origin}/{version}/{loader}-{version}-installer.jar"
    if installer["url"] != expected_url:
        _fail(f"runtime {node} installer URL disagrees with its loader identity")

    dependency_contexts = {
        "architectury": (f"dev.architectury:architectury-{loader}", "https://maven.architectury.dev/"),
        # GeckoLib renamed its Maven group at the 26.1 boundary; the same host serves both.
        "geckolib": (f"{'com.geckolib' if _numeric_version(minecraft)[0] >= 26 else 'software.bernie.geckolib'}"
                     f":geckolib-{loader}-{minecraft}",
                    "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/"),
        "fabric-api": ("net.fabricmc.fabric-api:fabric-api", "https://maven.fabricmc.net/"),
        "mclib": ("com.eliotlash.mclib:mclib", "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/"),
    }
    for dependency in runtime["runtime_dependencies"]:
        coordinate, repository = dependency_contexts[dependency["id"]]
        if dependency["coordinate"].rsplit(":", 1)[0] != coordinate or dependency["repository"] != repository:
            _fail(f"runtime {node} dependency {dependency['id']} disagrees with its loader context")
        if dependency["id"] == "fabric-api" and not dependency["coordinate"].endswith(f"+{minecraft}"):
            _fail(f"runtime {node} Fabric API dependency disagrees with its Minecraft era")

    constraint = artifact["metadata"]["minecraft"]
    if loader == "fabric":
        valid = constraint == f"~{minecraft}"
    else:
        upper = re.fullmatch(r"\[" + re.escape(minecraft) + r",([0-9]+(?:\.[0-9]+)+)\)", constraint)
        valid = constraint == f"[{minecraft}]" or (
            upper is not None
            and _numeric_version(upper[1]) > _numeric_version(minecraft)
        )
    if not valid:
        _fail(f"artifact {node} metadata Minecraft constraint disagrees with its era")


def _strict_source_path(value: Any, label: str) -> str:
    path = _safe_path(value, label)
    if path != value or path == "." or any(ord(character) < 32 or ord(character) == 127 for character in path):
        _fail(f"{label} is not a canonical source path")
    return path


def _validate_schema2_route(module: str, route: dict, artifacts: dict) -> None:
    for key, source_set in (("canonical", "main"), ("e2e", "e2e")):
        if _strict_source_path(route[key], f"{module}.{key}") != f"{module}/src/{source_set}":
            _fail(f"{module}.{key} must select its canonical {source_set} root")
    if not isinstance(route["overlays"], list):
        _fail(f"{module}.overlays must be an array")
    paths, scopes = set(), set()
    allowed = {node for node, row in artifacts.items() if module in row["source_routes"]}
    for raw in route["overlays"]:
        try:
            overlay = require_object(raw, label=f"{module} overlay", required={
                "lanes", "source_set", "path", "adds", "replaces", "reason", "historical_source", "acceptance",
            })
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        source_set = _text(overlay["source_set"], "overlay.source_set")
        if source_set not in {"main", "e2e"}:
            _fail("overlay.source_set must be main or e2e")
        path = _strict_source_path(overlay["path"], "overlay.path")
        if re.fullmatch(rf"{module}/src/legacy[A-Za-z0-9_]+/{source_set}", path) is None or path in paths:
            _fail(f"{module} overlay path must be unique and lane-local under src/legacy*")
        paths.add(path)
        lanes = overlay["lanes"]
        if not isinstance(lanes, list) or not lanes or any(not isinstance(node, str) for node in lanes):
            _fail("overlay.lanes must be a non-empty array of lane identities")
        if len(set(lanes)) != len(lanes) or not set(lanes) <= allowed:
            _fail(f"{module} overlay lanes must be unique configured lanes of this module")
        for node in lanes:
            if (node, source_set) in scopes:
                _fail(f"{module} overlays overlap for {node}/{source_set}")
            scopes.add((node, source_set))
        declared = set()
        for key in ("adds", "replaces"):
            if not isinstance(overlay[key], list):
                _fail(f"overlay.{key} must be an array")
            for value in overlay[key]:
                relative = _strict_source_path(value, f"overlay.{key}")
                if not relative.startswith(("java/", "resources/")) or relative in declared:
                    _fail("overlay files must be unique java/resources source-relative paths")
                declared.add(relative)
        if not declared:
            _fail("overlay must declare added or replaced files")
        for key in ("reason", "acceptance"):
            _text(overlay[key], f"overlay.{key}")
        historical = _text(overlay["historical_source"], "overlay.historical_source")
        sha, separator, historical_path = historical.partition(":")
        if not separator or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
            _fail("overlay.historical_source must bind an exact commit and source path")
        _strict_source_path(historical_path, "overlay.historical_source path")


def _source_files(repository: Path, relative: str) -> set[str]:
    path = repository
    for part in PurePosixPath(relative).parts:
        path = path / part
        if path.is_symlink():
            _fail(f"source route contains a symlink: {relative}")
    if not path.is_dir():
        _fail(f"source route is missing: {relative}")
    files = set()
    for item in path.rglob("*"):
        if item.is_symlink() or not (item.is_dir() or item.is_file()):
            _fail(f"source route contains a symlink or special file: {item}")
        if item.is_file():
            files.add(item.relative_to(path).as_posix())
    return files


def _validate_schema2_source_tree(repository: Path, routing: dict, artifacts: dict) -> None:
    root = repository.resolve()
    canonical, overlays = {}, {}
    for module, route in routing.items():
        # Stonecutter's node-local src directory is an override input, not generated
        # output. Only canonical roots and explicitly declared legacy overlays own sources.
        versions = root / module / "versions"
        if versions.is_symlink():
            _fail(f"source inventory contains a linked version root: {versions}")
        if versions.is_dir():
            for node in versions.iterdir():
                if node.is_symlink():
                    _fail(f"source inventory contains a linked version node: {node}")
                override = node / "src"
                if override.exists() or override.is_symlink():
                    if _source_files(root, override.relative_to(root).as_posix()):
                        _fail(f"source inventory contains undeclared node overrides: {override}")
        for source_set, key in (("main", "canonical"), ("e2e", "e2e")):
            canonical[module, source_set] = _source_files(root, route[key])
        for overlay in route["overlays"]:
            files = _source_files(root, overlay["path"])
            base = canonical[module, overlay["source_set"]]
            replacements, additions = set(overlay["replaces"]), set(overlay["adds"])
            if files != replacements | additions or not replacements <= base or additions & base:
                _fail(f"overlay {overlay['path']} has missing files or undeclared shadowing/replacements")
            for node in overlay["lanes"]:
                overlays[module, node, overlay["source_set"]] = files
        expected = {str(PurePosixPath(overlay["path"]).parent) for overlay in route["overlays"]}
        live = set()
        for path in (root / module / "src").iterdir():
            if path.name.startswith(("legacy", "v")):
                if path.is_symlink():
                    _fail(f"source inventory contains a symlink: {path}")
                if path.is_dir():
                    relative = path.relative_to(root).as_posix()
                    files = _source_files(root, relative)
                    if files:
                        live.add(relative)
                    declared = {
                        f"{overlay['source_set']}/{item}"
                        for overlay in route["overlays"]
                        if str(PurePosixPath(overlay["path"]).parent) == relative
                        for item in overlay["adds"] + overlay["replaces"]
                    }
                    if files != declared:
                        _fail(f"{module} live overlay inventory has undeclared files: {relative}")
        if live != expected:
            _fail(f"{module} live overlay inventory differs from the matrix")
    for node, artifact in artifacts.items():
        for source_set in ("main", "e2e"):
            selected = set()
            for module in artifact["source_routes"]:
                files = canonical[module, source_set] | overlays.get((module, node, source_set), set())
                if selected & files:
                    _fail(f"source routes for {node}/{source_set} contain duplicate files")
                selected.update(files)


def _era_java(minecraft: str) -> int:
    """The Java release a Minecraft era compiles against.

    1.20.1 is the last Java 17 era; 26.1 moved the game to Java 25.
    """
    if minecraft == "1.20.1":
        return 17
    return 25 if _numeric_version(minecraft)[0] >= 26 else 21


def _numeric_version(value: str) -> tuple[int, ...]:
    parts = list(map(int, value.split(".")))
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def _schema_version(data: Any) -> int:
    if not isinstance(data, dict):
        _fail("release matrix must be an object")
    if "schema_version" not in data:
        _fail("release matrix is missing schema_version")
    schema = data["schema_version"]
    if isinstance(schema, bool) or not isinstance(schema, int):
        _fail("release matrix schema_version must be an integer")
    if schema not in {1, 2}:
        _fail(f"unsupported release matrix schema_version {schema}")
    return schema


def _normalize_schema2_inventory(data: Any) -> MatrixInventory:
    try:
        root = require_object(
            data, label="schema-2 release matrix", required=SCHEMA2_ROOT_KEYS
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc

    raw_targets = root["targets"]
    if not isinstance(raw_targets, list):
        _fail("schema-2 targets must be an array")
    targets: list[LaneIdentity] = []
    nodes: set[str] = set()
    pairs: set[tuple[str, str]] = set()
    for index, raw_target in enumerate(raw_targets):
        try:
            target = require_object(
                raw_target, label=f"targets[{index}]", required=TARGET_KEYS
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        node = _text(target["artifact_node"], f"targets[{index}].artifact_node")
        minecraft = _text(target["minecraft"], f"targets[{index}].minecraft")
        loader = _text(target["loader"], f"targets[{index}].loader")
        if loader not in KNOWN_LOADERS:
            _fail(f"target {node} uses unsupported loader {loader!r}")
        if node != f"{loader}-{minecraft}":
            _fail(f"target artifact_node {node!r} must equal {loader}-{minecraft}")
        if node in nodes or (loader, minecraft) in pairs:
            _fail(f"duplicate schema-2 target {node}")
        nodes.add(node)
        pairs.add((loader, minecraft))
        targets.append(LaneIdentity(node, minecraft, loader))

    if pairs != EXPECTED_TARGETS:
        missing = sorted(EXPECTED_TARGETS - pairs)
        extra = sorted(pairs - EXPECTED_TARGETS)
        _fail(f"schema-2 targets diverge: missing {missing}, extra {extra}")

    try:
        migration = require_object(
            root["migration"],
            label="migration",
            required={"mode", "legacy_nodes"},
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc
    mode = _text(migration["mode"], "migration.mode")
    if mode not in {"preparing", "shared"}:
        _fail("migration.mode must be preparing or shared")
    raw_legacy_nodes = migration["legacy_nodes"]
    if not isinstance(raw_legacy_nodes, list):
        _fail("migration.legacy_nodes must be an array")
    legacy_nodes = tuple(
        _text(node, f"migration.legacy_nodes[{index}]")
        for index, node in enumerate(raw_legacy_nodes)
    )
    if len(set(legacy_nodes)) != len(legacy_nodes):
        _fail("migration.legacy_nodes must not contain duplicates")
    if not set(legacy_nodes) <= nodes:
        _fail("migration.legacy_nodes must reference declared targets")
    if mode == "preparing" and set(legacy_nodes) != LEGACY_TARGET_NODES:
        _fail("preparing migration must retain both 1.20.1 legacy nodes")
    if mode == "shared" and legacy_nodes:
        _fail("shared migration must not retain legacy nodes")

    return MatrixInventory(2, tuple(targets), mode, legacy_nodes, False)


def normalize_matrix_inventory(
    data: Any,
    *,
    contract: ScenarioContract | None = None,
    repository: Path | None = None,
) -> MatrixInventory:
    """Validate normalized lane identity without activating partial schema 2.

    Schema 1 remains fully executable. Schema 2 validates target membership and
    configured lane inputs without enabling execution consumers. Missing pairs
    remain explicitly unresolved; malformed or orphan rows fail validation.
    """

    schema = _schema_version(data)
    if schema == 2:
        inventory = _normalize_schema2_inventory(data)
        _validate_configuration(
            data, contract=contract, repository=repository, inventory=inventory,
        )
        return replace(
            inventory, lanes=_normalized_lanes(data), sources_checked=repository is not None,
        )
    root = _validate_configuration(
        data, contract=contract, repository=repository
    )
    targets = tuple(
        LaneIdentity(row["artifact_node"], row["minecraft"], row["loader"])
        for row in root["artifacts"]
    )
    return MatrixInventory(1, targets, None, (), True, _normalized_lanes(root), repository is not None)


def _normalized_lanes(matrix: dict[str, Any]) -> tuple[LaneConfiguration, ...]:
    runtimes = {row["artifact_node"]: row for row in matrix["runtimes"]}
    schema2 = matrix["schema_version"] == 2
    return tuple(
        LaneConfiguration(
            LaneIdentity(row["artifact_node"], row["minecraft"], row["loader"]),
            row["mod_version"] if schema2 else matrix["project"]["mod_version"],
            row["build_layout"] if schema2 else "legacy",
            row["gradle_java"] if schema2 else matrix["gradle_java"],
            row["repository_family"] if schema2 else row["loader"],
            tuple(row["source_routes"]) if schema2 else ("common", row["loader"]),
            json.dumps(row), json.dumps(runtimes[row["artifact_node"]]),
        )
        for row in matrix["artifacts"]
    )


def validate_matrix(
    data: Any,
    *,
    contract: ScenarioContract | None = None,
    repository: Path | None = None,
) -> dict[str, Any]:
    """Validate a matrix for existing execution consumers."""

    if _schema_version(data) == 1:
        return _validate_configuration(
            data, contract=contract, repository=repository
        )
    _normalize_schema2_inventory(data)
    _fail("schema 2 is inventory only; execution consumers are not supported yet")


def validate_source_tree(repository: Path, routing: dict[str, Any]) -> None:
    root = repository.resolve()
    for module, route in routing.items():
        expected = {route["canonical"], *route["overlays"].values()}
        for relative in expected:
            path = (root / relative).resolve()
            if root not in path.parents or not path.is_dir():
                _fail(f"matrix source route is missing or escapes the repository: {relative}")
        source_root = root / module / "src"
        live_overlays = {
            path.relative_to(root).as_posix()
            for path in source_root.glob("legacy*")
            if path.is_dir() and any(item.is_file() for item in path.rglob("*"))
        }
        expected_overlays = set(route["overlays"].values())
        if live_overlays != expected_overlays:
            _fail(
                f"{module} live overlay inventory differs from the matrix: "
                f"expected {sorted(expected_overlays)}, found {sorted(live_overlays)}"
            )
        retired = [
            path.relative_to(root).as_posix()
            for path in source_root.glob("v*")
            if path.is_dir() and any(item.is_file() for item in path.rglob("*"))
        ]
        if retired:
            _fail(f"{module} contains retired version snapshots: {retired}")


def load_matrix(path: Path, *, validate_sources: bool = True) -> dict[str, Any]:
    try:
        data, _ = read_secure_json(
            path, label="release matrix", max_bytes=MAX_MATRIX_BYTES
        )
        return validate_matrix(
            data,
            repository=path.resolve().parents[1] if validate_sources else None,
        )
    except (SecureJsonError, MatrixError) as exc:
        if isinstance(exc, MatrixError):
            raise
        raise MatrixError(str(exc)) from exc


def load_matrix_bytes(data: bytes) -> dict[str, Any]:
    from scripts.lib.secure_json import loads

    try:
        return validate_matrix(
            loads(data, label="release matrix snapshot", max_bytes=MAX_MATRIX_BYTES)
        )
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc


def load_matrix_inventory(path: Path, *, validate_sources: bool = True) -> MatrixInventory:
    """Read either schema for configuration inspection, without activating consumers."""
    try:
        data, _ = read_secure_json(path, label="release matrix", max_bytes=MAX_MATRIX_BYTES)
        return normalize_matrix_inventory(
            data, repository=path.resolve().parents[1] if validate_sources else None,
        )
    except (SecureJsonError, OSError) as exc:
        raise MatrixError(str(exc)) from exc


def load_matrix_document(path: Path, *, validate_sources: bool = True) -> MatrixDocument:
    """Opt in one adapted consumer without enabling the legacy schema-1 reader."""
    try:
        data, _ = read_secure_json(path, label="release matrix", max_bytes=MAX_MATRIX_BYTES)
        inventory = normalize_matrix_inventory(
            data, repository=path.resolve().parents[1] if validate_sources else None,
        )
        return MatrixDocument(inventory, json.dumps(data))
    except (SecureJsonError, OSError) as exc:
        raise MatrixError(str(exc)) from exc


def mod_version(matrix_path: Path, matrix: dict[str, Any]) -> str:
    del matrix_path
    return _text(matrix["project"]["mod_version"], "project.mod_version")


def matrix_sha256(path: Path) -> str:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise MatrixError(f"cannot hash release matrix: {exc}") from exc
    return hashlib.sha256(raw).hexdigest()


def gha_matrix(
    matrix: dict[str, Any],
    kind: str,
    *,
    contract: ScenarioContract | None = None,
) -> dict[str, Any]:
    selected_contract = contract or default_contract()
    if kind == "artifacts":
        return {"include": [dict(row) for row in matrix["artifacts"]]}
    if kind == "java":
        return {
            "include": [
                {"java": major}
                for major in sorted({row["java"] for row in matrix["artifacts"]})
            ]
        }
    if kind == "gradle-java":
        return {"java": matrix["gradle_java"]}
    if kind not in {"runtime", "pr-anchors", "scheduled-anchors"}:
        _fail(f"unsupported matrix projection {kind!r}")
    profile = "pr" if kind == "pr-anchors" else "release"
    rows = matrix["runtimes"]
    if kind == "pr-anchors":
        rows = [row for row in rows if row["pr_anchor"]]
    elif kind == "scheduled-anchors":
        rows = [row for row in rows if row["scheduled_anchor"]]
    scenarios = ",".join(selected_contract.scenarios_for_profile(profile))
    include: list[dict[str, Any]] = []
    for row in rows:
        expanded = dict(row)
        expanded["id"] = (
            f"{row['artifact_node']}--{profile}-behavior".replace(".", "_")
        )
        expanded["scenarios"] = scenarios
        include.append(expanded)
    return {"include": include}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix", type=Path, default=Path("release/release-matrix.json")
    )
    parser.add_argument(
        "--kind",
        choices=(
            "inventory",
            "gradle-context",
            "artifacts",
            "java",
            "gradle-java",
            "runtime",
            "pr-anchors",
            "scheduled-anchors",
        ),
    )
    parser.add_argument("--no-source-check", action="store_true")
    parser.add_argument("--pretty", action="store_true")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--artifact-node")
    selection.add_argument("--scope", choices=("legacy", "full"))
    args = parser.parse_args(argv)
    try:
        if args.kind in {None, "inventory"} and (args.artifact_node is not None or args.scope is not None):
            _fail("matrix and inventory output cannot narrow their authoritative input")
        if args.kind == "gradle-context" and args.scope is not None:
            _fail("Gradle context accepts only an explicit --artifact-node, not --scope")
        if args.kind == "gradle-context":
            output: Any = load_matrix_document(
                args.matrix, validate_sources=not args.no_source_check,
            ).gradle_context(artifact_node=args.artifact_node)
        elif args.kind == "inventory":
            output: Any = load_matrix_inventory(
                args.matrix, validate_sources=not args.no_source_check,
            ).report()
        else:
            document = load_matrix_document(
                args.matrix, validate_sources=not args.no_source_check
            )
            output = document.projection(
                args.kind, scope="lane" if args.artifact_node else args.scope,
                artifact_node=args.artifact_node,
            ) if args.kind else document.data
    except MatrixError as exc:
        print(f"release matrix error: {exc}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            output,
            indent=2 if args.pretty else None,
            separators=None if args.pretty else (",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
