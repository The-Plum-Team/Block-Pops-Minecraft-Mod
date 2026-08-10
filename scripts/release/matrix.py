#!/usr/bin/env python3
"""Validate and project BlockPops' branch-local release matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
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


class MatrixError(ValueError):
    """Raised when the authoritative branch matrix is inconsistent."""


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


def validate_matrix(
    data: Any,
    *,
    contract: ScenarioContract | None = None,
    repository: Path | None = None,
) -> dict[str, Any]:
    try:
        root = require_object(data, label="release matrix", required=ROOT_KEYS)
    except SecureJsonError as exc:
        raise MatrixError(str(exc)) from exc
    if root["schema_version"] != 1:
        _fail("release matrix schema_version must be 1")
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
                raw, label=f"artifacts[{index}]", required=ARTIFACT_KEYS
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
        no_remap = _boolean(artifact["no_remap"], f"artifact {node}.no_remap")
        expected_task = f":{loader}:" + ("shadowJar" if no_remap else "remapJar")
        expected_harness = f":{loader}:" + (
            "e2eHarnessJar" if no_remap else "remapE2EHarnessJar"
        )
        if artifact["gradle_task"] != expected_task:
            _fail(f"artifact {node} gradle_task must be {expected_task}")
        if artifact["harness_task"] != expected_harness:
            _fail(f"artifact {node} harness_task must be {expected_harness}")
        for key, marker in (("jar", "BlockPops - "), ("harness_jar", "BlockPops E2E - ")):
            path = _safe_path(artifact[key], f"artifact {node}.{key}")
            if not path.startswith(f"{loader}/build/libs/") or minecraft not in Path(path).name:
                _fail(f"artifact {node}.{key} disagrees with the target Gradle layout")
            if marker not in Path(path).name:
                _fail(f"artifact {node}.{key} has the wrong archive identity")
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
    if len(forge_family_loaders) > 1:
        _fail("a release branch cannot activate Forge and NeoForge together")

    if gradle_java < max(artifact_java_versions):
        _fail("gradle_java cannot be lower than an artifact Java toolchain")

    if len(versions) != 1:
        _fail("a release branch must contain exactly one Minecraft version")
    only_version = next(iter(versions))
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
                required={"canonical", "overlays"},
            )
        except SecureJsonError as exc:
            raise MatrixError(str(exc)) from exc
        if _safe_path(route["canonical"], f"source_routing.{module}.canonical") != f"{module}/src/main":
            _fail(f"source_routing.{module}.canonical must be {module}/src/main")
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
        validate_source_tree(Path(repository), routing)
    return root


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
    args = parser.parse_args(argv)
    try:
        matrix = load_matrix(
            args.matrix, validate_sources=not args.no_source_check
        )
        output: Any = gha_matrix(matrix, args.kind) if args.kind else matrix
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
