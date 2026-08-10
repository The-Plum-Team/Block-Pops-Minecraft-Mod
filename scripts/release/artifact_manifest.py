#!/usr/bin/env python3
"""Create and verify the immutable BlockPops production/E2E artifact bundle."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    read as read_secure_json,
    require_object,
)
from scripts.release.matrix import (  # noqa: E402
    MatrixError,
    load_matrix,
    matrix_sha256,
    mod_version,
)

SCHEMA_VERSION = 1
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_JAR_BYTES = 256 * 1024 * 1024
MAX_ZIP_ENTRIES = 8192
MAX_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
ROOT_KEYS = frozenset(
    {
        "schema_version",
        "matrix",
        "lane_count",
        "mod_version",
        "git_commit",
        "release_branch",
        "artifacts",
    }
)
ARTIFACT_KEYS = frozenset(
    {"artifact_node", "minecraft", "loader", "java", "production", "harness"}
)
FILE_KEYS = frozenset({"filename", "path", "bytes", "sha256"})


class ArtifactError(ValueError):
    """Raised when production bytes or their provenance are invalid."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _regular_file(path: Path, *, label: str, maximum: int) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as exc:
        raise ArtifactError(f"cannot stat {label} {path}: {exc}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ArtifactError(f"{label} must be a regular non-symlink file: {path}")
    if info.st_size <= 0 or info.st_size > maximum:
        raise ArtifactError(f"{label} size is outside 1..{maximum}: {path}")
    return info


def _safe_zip_name(name: str) -> None:
    normalized = name.replace("\\", "/")
    candidate = normalized[:-1] if normalized.endswith("/") else normalized
    path = PurePosixPath(candidate)
    if (
        name != normalized
        or not candidate
        or normalized.startswith("/")
        or candidate != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
        or ":" in normalized
        or "\x00" in normalized
    ):
        raise ArtifactError(f"JAR contains unsafe entry {name!r}")


def inspect_zip(path: Path) -> tuple[zipfile.ZipFile, list[zipfile.ZipInfo], set[str]]:
    _regular_file(path, label="JAR", maximum=MAX_JAR_BYTES)
    try:
        archive = zipfile.ZipFile(path)
        entries = archive.infolist()
    except (OSError, zipfile.BadZipFile) as exc:
        raise ArtifactError(f"invalid JAR {path}: {exc}") from exc
    if not entries or len(entries) > MAX_ZIP_ENTRIES:
        archive.close()
        raise ArtifactError(f"JAR entry count is outside 1..{MAX_ZIP_ENTRIES}: {path}")
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        archive.close()
        raise ArtifactError(f"JAR contains duplicate ZIP entries: {path}")
    total = 0
    for entry in entries:
        _safe_zip_name(entry.filename)
        total += entry.file_size
        if total > MAX_UNCOMPRESSED_BYTES:
            archive.close()
            raise ArtifactError(f"JAR uncompressed size exceeds limit: {path}")
        unix_type = (entry.external_attr >> 16) & 0o170000
        if unix_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
            archive.close()
            raise ArtifactError(f"JAR contains a special/symlink entry: {entry.filename}")
    return archive, entries, set(names)


def _read_zip_json(
    archive: zipfile.ZipFile, name: str, *, maximum: int = 1024 * 1024
) -> dict[str, Any]:
    try:
        info = archive.getinfo(name)
    except KeyError as exc:
        raise ArtifactError(f"JAR is missing {name}") from exc
    if info.file_size <= 0 or info.file_size > maximum:
        raise ArtifactError(f"JAR metadata {name} has an invalid size")
    raw = archive.read(info)
    try:
        from scripts.lib.secure_json import loads

        value = loads(raw, label=f"JAR {name}", max_bytes=maximum)
    except SecureJsonError as exc:
        raise ArtifactError(str(exc)) from exc
    if not isinstance(value, dict):
        raise ArtifactError(f"JAR {name} must contain a JSON object")
    return value


def verify_production_jar(path: Path, artifact: dict[str, Any]) -> None:
    if any(marker in path.name for marker in ("dev-shadow", "-sources", "-javadoc")):
        raise ArtifactError(f"production path selects a development artifact: {path.name}")
    archive, _, names = inspect_zip(path)
    try:
        required = {
            "com/theplumteam/BlockPopsMod.class",
            "blockpops.mixins.json",
            artifact["metadata"]["file"],
        }
        missing = required - names
        if missing:
            raise ArtifactError(f"production JAR is missing {sorted(missing)}")
        if any(name.startswith("com/theplumteam/e2e/") for name in names):
            raise ArtifactError("production JAR leaks the packaged E2E harness")
        loader = artifact["loader"]
        if loader == "fabric":
            metadata = _read_zip_json(archive, "fabric.mod.json")
            if metadata.get("id") != "blockpops":
                raise ArtifactError("Fabric production mod id is not blockpops")
            dependencies = metadata.get("depends")
            if not isinstance(dependencies, dict):
                raise ArtifactError("Fabric production metadata has no dependency object")
            expected = artifact["metadata"]
            for key, matrix_key in (
                ("minecraft", "minecraft"),
                ("fabricloader", "loader"),
                ("architectury", "architectury"),
                ("geckolib", "geckolib"),
            ):
                if dependencies.get(key) != expected[matrix_key]:
                    raise ArtifactError(
                        f"Fabric metadata {key} disagrees with the release matrix"
                    )
            if "com/theplumteam/fabric/BlockPopsFabric.class" not in names:
                raise ArtifactError("Fabric production entrypoint class is missing")
        else:
            metadata_name = artifact["metadata"]["file"]
            text = archive.read(metadata_name).decode("utf-8", "strict")
            required_fragments = (
                'modId = "blockpops"',
                f'versionRange = "{artifact["metadata"]["minecraft"]}"',
                f'versionRange = "{artifact["metadata"]["architectury"]}"',
                f'versionRange = "{artifact["metadata"]["geckolib"]}"',
            )
            if not all(fragment in text for fragment in required_fragments):
                raise ArtifactError(
                    f"{loader} production metadata disagrees with the release matrix"
                )
            entrypoint = {
                "forge": "com/theplumteam/forge/BlockPopsModForge.class",
                "neoforge": "com/theplumteam/neoforge/BlockPopsModForge.class",
            }[loader]
            if entrypoint not in names:
                raise ArtifactError(f"{loader} production entrypoint class is missing")
    finally:
        archive.close()


def verify_harness_jar(path: Path, artifact: dict[str, Any]) -> None:
    archive, _, names = inspect_zip(path)
    try:
        required = {
            "com/theplumteam/e2e/E2EHarness.class",
            "com/theplumteam/e2e/generated/ScenarioContract.class",
            artifact["metadata"]["file"],
        }
        loader_entrypoint = {
            "fabric": "com/theplumteam/e2e/fabric/BlockPopsE2EFabric.class",
            "forge": "com/theplumteam/e2e/forge/BlockPopsE2EForge.class",
            "neoforge": "com/theplumteam/e2e/neoforge/BlockPopsE2ENeoForge.class",
        }[artifact["loader"]]
        required.add(loader_entrypoint)
        if artifact["loader"] in {"forge", "neoforge"}:
            required.add("pack.mcmeta")
        missing = required - names
        if missing:
            raise ArtifactError(f"E2E harness is missing {sorted(missing)}")
        if "com/theplumteam/BlockPopsMod.class" in names:
            raise ArtifactError("E2E harness contains production classes")
        illegal_classes = sorted(
            name
            for name in names
            if name.endswith(".class") and not name.startswith("com/theplumteam/e2e/")
        )
        if illegal_classes:
            raise ArtifactError(
                f"E2E harness contains non-harness classes: {illegal_classes[:8]}"
            )
        if artifact["loader"] == "fabric":
            metadata = _read_zip_json(archive, "fabric.mod.json")
            dependencies = metadata.get("depends")
            if (
                metadata.get("id") != "blockpops-e2e"
                or metadata.get("version") != "0.0.0"
                or metadata.get("environment") != "client"
                or not isinstance(dependencies, dict)
                or dependencies.get("blockpops") != "*"
                or dependencies.get("fabricloader") != artifact["metadata"]["loader"]
                or dependencies.get("minecraft") != artifact["metadata"]["minecraft"]
            ):
                raise ArtifactError("Fabric E2E metadata identity is invalid")
        else:
            text = archive.read(artifact["metadata"]["file"]).decode("utf-8", "strict")
            if (
                'modId = "blockpops_e2e"' not in text
                or 'version = "0.0.0"' not in text
                or 'displayTest = "IGNORE_ALL_VERSION"' not in text
                or 'modId = "blockpops"' not in text
                or f'loaderVersion = "{artifact["metadata"]["loader"]}"' not in text
                or f'versionRange = "{artifact["metadata"]["minecraft"]}"' not in text
            ):
                raise ArtifactError("FML E2E metadata identity is invalid")
    finally:
        archive.close()


def git_commit(repository: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    commit = result.stdout.strip()
    if result.returncode or len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise ArtifactError(f"cannot resolve exact git commit: {result.stderr.strip()}")
    expected = os.environ.get("GITHUB_SHA")
    if expected and expected != commit:
        raise ArtifactError(f"GITHUB_SHA {expected} does not equal checkout HEAD {commit}")
    cleanliness = subprocess.run(
        ["git", "-C", str(repository), "diff-index", "--quiet", "HEAD", "--"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    if cleanliness.returncode not in {0, 1}:
        raise ArtifactError(
            "cannot authenticate tracked worktree cleanliness: "
            + cleanliness.stderr.decode("utf-8", "replace").strip()
        )
    if cleanliness.returncode:
        raise ArtifactError(
            "refusing to bind release artifacts to a commit with tracked changes"
        )
    return commit


def _clean_directory(directory: Path) -> None:
    if directory.exists():
        if directory.is_symlink() or not directory.is_dir():
            raise ArtifactError(f"stage child is not an owned directory: {directory}")
        shutil.rmtree(directory)
    directory.mkdir(parents=True, exist_ok=False)


def _file_record(path: Path, *, relative: str) -> dict[str, Any]:
    info = _regular_file(path, label="staged artifact", maximum=MAX_JAR_BYTES)
    return {
        "filename": path.name,
        "path": relative,
        "bytes": info.st_size,
        "sha256": sha256_file(path),
    }


def stage_release(
    *,
    repository: Path,
    matrix_path: Path,
    manifest_path: Path,
    stage: Path,
) -> dict[str, Any]:
    repo = repository.resolve()
    matrix_file = matrix_path.resolve()
    stage_root = stage.resolve()
    if repo not in stage_root.parents or stage_root.parent != repo / "build":
        raise ArtifactError("release stage must be a direct child of repository build/")
    if manifest_path.resolve().parent != stage_root:
        raise ArtifactError("artifact manifest must be a direct child of the release stage")
    matrix = load_matrix(matrix_file)
    version = mod_version(matrix_file, matrix)
    commit = git_commit(repo)
    stage_root.mkdir(parents=True, exist_ok=True)
    files_directory = stage_root / "files"
    harness_directory = stage_root / "harness"
    _clean_directory(files_directory)
    _clean_directory(harness_directory)
    rows: list[dict[str, Any]] = []
    for artifact in matrix["artifacts"]:
        production_source = repo / artifact["jar"].replace("{mod_version}", version)
        harness_source = repo / artifact["harness_jar"]
        verify_production_jar(production_source, artifact)
        verify_harness_jar(harness_source, artifact)
        production_target = files_directory / production_source.name
        harness_target = harness_directory / harness_source.name
        if production_target.exists() or harness_target.exists():
            raise ArtifactError("release matrix produces duplicate staged filenames")
        shutil.copyfile(production_source, production_target)
        shutil.copyfile(harness_source, harness_target)
        verify_production_jar(production_target, artifact)
        verify_harness_jar(harness_target, artifact)
        rows.append(
            {
                "artifact_node": artifact["artifact_node"],
                "minecraft": artifact["minecraft"],
                "loader": artifact["loader"],
                "java": artifact["java"],
                "production": _file_record(
                    production_target, relative=f"files/{production_target.name}"
                ),
                "harness": _file_record(
                    harness_target, relative=f"harness/{harness_target.name}"
                ),
            }
        )
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "matrix": {
            "path": matrix_file.relative_to(repo).as_posix(),
            "sha256": matrix_sha256(matrix_file),
        },
        "lane_count": matrix["lane_count"],
        "mod_version": version,
        "git_commit": commit,
        "release_branch": matrix["branch"]["name"],
        "artifacts": rows,
    }
    encoded = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(encoded) > MAX_MANIFEST_BYTES:
        raise ArtifactError("generated artifact manifest exceeds its size limit")
    with tempfile.NamedTemporaryFile(
        dir=stage_root, prefix=".artifacts.", suffix=".tmp", delete=False
    ) as handle:
        temporary = Path(handle.name)
        handle.write(encoded)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, manifest_path)
    return verify_staged(
        repository=repo,
        matrix_path=matrix_file,
        manifest_path=manifest_path,
        stage=stage_root,
    )


def _manifest_file(
    stage: Path, record: dict[str, Any], *, label: str
) -> Path:
    try:
        row = require_object(record, label=label, required=FILE_KEYS)
    except SecureJsonError as exc:
        raise ArtifactError(str(exc)) from exc
    relative = row["path"]
    if not isinstance(relative, str):
        raise ArtifactError(f"{label}.path must be a string")
    path = PurePosixPath(relative)
    if path.is_absolute() or relative != path.as_posix() or ".." in path.parts:
        raise ArtifactError(f"{label}.path is unsafe")
    resolved = (stage / relative).resolve()
    if stage not in resolved.parents or resolved.name != row["filename"]:
        raise ArtifactError(f"{label}.path escapes or disagrees with filename")
    info = _regular_file(resolved, label=label, maximum=MAX_JAR_BYTES)
    if isinstance(row["bytes"], bool) or row["bytes"] != info.st_size:
        raise ArtifactError(f"{label}.bytes disagrees with staged bytes")
    if row["sha256"] != sha256_file(resolved):
        raise ArtifactError(f"{label}.sha256 disagrees with staged bytes")
    return resolved


def verify_staged(
    *,
    repository: Path,
    matrix_path: Path,
    manifest_path: Path,
    stage: Path,
) -> dict[str, Any]:
    repo = repository.resolve()
    stage_root = stage.resolve()
    if stage_root.parent != repo / "build":
        raise ArtifactError("release stage must be a direct child of repository build/")
    if manifest_path.resolve().parent != stage_root:
        raise ArtifactError("artifact manifest must be a direct child of the release stage")
    try:
        raw_manifest, _ = read_secure_json(
            manifest_path,
            label="artifact manifest",
            max_bytes=MAX_MANIFEST_BYTES,
        )
        manifest = require_object(
            raw_manifest, label="artifact manifest", required=ROOT_KEYS
        )
        matrix_record = require_object(
            manifest["matrix"],
            label="artifact manifest.matrix",
            required={"path", "sha256"},
        )
    except SecureJsonError as exc:
        raise ArtifactError(str(exc)) from exc
    if manifest["schema_version"] != SCHEMA_VERSION:
        raise ArtifactError("artifact manifest schema_version is unsupported")
    matrix = load_matrix(matrix_path)
    if matrix_record != {
        "path": matrix_path.resolve().relative_to(repo).as_posix(),
        "sha256": matrix_sha256(matrix_path),
    }:
        raise ArtifactError("artifact manifest matrix identity is stale")
    if manifest["lane_count"] != matrix["lane_count"]:
        raise ArtifactError("artifact manifest lane_count is stale")
    if manifest["mod_version"] != mod_version(matrix_path, matrix):
        raise ArtifactError("artifact manifest mod_version is stale")
    if manifest["git_commit"] != git_commit(repo):
        raise ArtifactError("artifact manifest commit is stale")
    if manifest["release_branch"] != matrix["branch"]["name"]:
        raise ArtifactError("artifact manifest release branch is stale")
    rows = manifest["artifacts"]
    if not isinstance(rows, list) or len(rows) != matrix["lane_count"]:
        raise ArtifactError("artifact manifest has the wrong row count")
    matrix_by_node = {row["artifact_node"]: row for row in matrix["artifacts"]}
    seen: set[str] = set()
    expected_paths: set[str] = set()
    for index, raw_row in enumerate(rows):
        try:
            row = require_object(
                raw_row,
                label=f"artifact manifest.artifacts[{index}]",
                required=ARTIFACT_KEYS,
            )
        except SecureJsonError as exc:
            raise ArtifactError(str(exc)) from exc
        node = row["artifact_node"]
        artifact = matrix_by_node.get(node)
        if artifact is None or node in seen:
            raise ArtifactError(f"manifest has an unknown/duplicate artifact node {node!r}")
        for key in ("minecraft", "loader", "java"):
            if row[key] != artifact[key]:
                raise ArtifactError(f"manifest artifact {node}.{key} is stale")
        production = _manifest_file(
            stage_root, row["production"], label=f"artifact {node}.production"
        )
        harness = _manifest_file(
            stage_root, row["harness"], label=f"artifact {node}.harness"
        )
        verify_production_jar(production, artifact)
        verify_harness_jar(harness, artifact)
        expected_paths.update(
            {row["production"]["path"], row["harness"]["path"]}
        )
        seen.add(node)
    if seen != set(matrix_by_node):
        raise ArtifactError("manifest artifact inventory is incomplete")
    actual_paths: set[str] = set()
    for directory_name in ("files", "harness"):
        directory = stage_root / directory_name
        if directory.is_symlink() or not directory.is_dir():
            raise ArtifactError(f"staged {directory_name} directory is invalid")
        for item in directory.iterdir():
            if item.is_symlink() or not item.is_file():
                raise ArtifactError(f"unexpected staged object {item}")
            actual_paths.add(item.relative_to(stage_root).as_posix())
    if actual_paths != expected_paths:
        raise ArtifactError(
            f"staged file inventory mismatch: expected {sorted(expected_paths)}, "
            f"found {sorted(actual_paths)}"
        )
    return manifest
