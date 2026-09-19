#!/usr/bin/env python3
"""Create and verify the immutable BlockPops production/E2E artifact bundle."""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
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
    MAX_MATRIX_BYTES,
    MatrixError,
    MatrixDocument,
    load_matrix_document,
    matrix_sha256,
    mod_version,
    normalize_matrix_inventory,
)

SCHEMA_VERSION = 2
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_JAR_BYTES = 256 * 1024 * 1024
MAX_ZIP_ENTRIES = 8192
MAX_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_NESTED_ARCHIVES = 32
MAX_ARCHIVE_DEPTH = 4
ROOT_KEYS = frozenset(
    {
        "schema_version",
        "matrix",
        "lane_count",
        "mod_version",
        "git_commit",
        "git_tree",
        "release_branch",
        "artifacts",
    }
)
ARTIFACT_KEYS = frozenset(
    {"artifact_node", "minecraft", "loader", "java", "production", "harness"}
)
FILE_KEYS = frozenset({"filename", "path", "bytes", "sha256"})
BUILD_IDENTITY_PATH = "META-INF/blockpops-build.json"


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
    try:
        names = _validate_zip_entries(entries, label=str(path))
    except BaseException:
        archive.close()
        raise
    return archive, entries, names


def _validate_zip_entries(entries: list[zipfile.ZipInfo], *, label: str) -> set[str]:
    if not entries or len(entries) > MAX_ZIP_ENTRIES:
        raise ArtifactError(f"JAR entry count is outside 1..{MAX_ZIP_ENTRIES}: {label}")
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        raise ArtifactError(f"JAR contains duplicate ZIP entries: {label}")
    total = 0
    for entry in entries:
        _safe_zip_name(entry.filename)
        total += entry.file_size
        if total > MAX_UNCOMPRESSED_BYTES:
            raise ArtifactError(f"JAR uncompressed size exceeds limit: {label}")
        unix_type = (entry.external_attr >> 16) & 0o170000
        if unix_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
            raise ArtifactError(f"JAR contains a special/symlink entry: {entry.filename}")
    return set(names)


def _verify_archive_boundary(archive: zipfile.ZipFile, *, production: bool) -> None:
    """Inspect bounded nested archives as well as top-level classes/resources."""
    kind = "production" if production else "E2E harness"
    forbidden_ids = ("blockpops-e2e", "blockpops_e2e") if production else ("blockpops",)
    budget = {"archives": 0, "entries": 0, "bytes": 0}
    def inspect(current, depth):
        entries = current.infolist()
        _validate_zip_entries(entries, label=f"{kind} archive content")
        budget["archives"] += 1
        budget["entries"] += len(entries)
        budget["bytes"] += sum(entry.file_size for entry in entries)
        if (depth > MAX_ARCHIVE_DEPTH or budget["archives"] > MAX_NESTED_ARCHIVES
                or budget["entries"] > MAX_ZIP_ENTRIES or budget["bytes"] > MAX_UNCOMPRESSED_BYTES):
            raise ArtifactError(f"{kind} nested archive inspection exceeds its bounded budget")
        for entry in entries:
            name = entry.filename
            parts = name.split("/", 3)
            if (len(parts) == 4 and parts[:2] == ["META-INF", "versions"]
                    and parts[2].isascii() and parts[2].isdigit()):
                name = parts[3]  # Enforce the effective path even without Multi-Release: true.
            if production and (name.startswith("com/theplumteam/e2e/") or name.rsplit("/", 1)[-1] == "blockpops-e2e.properties"):
                raise ArtifactError("production JAR leaks the packaged E2E harness")
            if not production and ((name.endswith(".class") and not name.startswith("com/theplumteam/e2e/"))
                                   or name.rsplit("/", 1)[-1] == "blockpops.mixins.json"):
                raise ArtifactError("E2E harness contains non-harness classes or production resources")
            if entry.is_dir():
                continue
            if entry.file_size > MAX_JAR_BYTES:
                raise ArtifactError(f"{kind} archive entry exceeds its inspection size limit")
            metadata = name in {"fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"}
            if metadata and entry.file_size > 1024 * 1024:
                raise ArtifactError(f"{kind} loader metadata is oversized")
            with current.open(entry) as stream:
                payload = stream.read(MAX_JAR_BYTES + 1)
            if len(payload) > MAX_JAR_BYTES:
                raise ArtifactError(f"{kind} archive entry exceeds its inspection size limit")
            if name == "fabric.mod.json":
                if _decode_zip_json(payload, entry.filename).get("id") in forbidden_ids:
                    raise ArtifactError(f"{kind} contains the opposite artifact's loader metadata (E2E boundary)")
            elif name in {"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}:
                import tomllib
                try:
                    mods = tomllib.loads(payload.decode("utf-8", "strict")).get("mods", [])
                    if any(mod.get("modId") in forbidden_ids for mod in mods):
                        raise ArtifactError(f"{kind} contains the opposite artifact's loader metadata (E2E boundary)")
                except (UnicodeError, tomllib.TOMLDecodeError, AttributeError, TypeError) as exc:
                    raise ArtifactError(f"{kind} contains invalid FML metadata") from exc
            buffer = io.BytesIO(payload)
            if not (zipfile.is_zipfile(buffer) or name.lower().endswith((".jar", ".zip"))
                    or payload.startswith(b"PK\x03\x04")):
                continue
            try:
                with zipfile.ZipFile(buffer) as nested:
                    inspect(nested, depth + 1)
            except zipfile.BadZipFile as exc:
                raise ArtifactError(f"{kind} contains an invalid nested archive") from exc
    try:
        inspect(archive, 0)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError) as exc:
        raise ArtifactError(f"{kind} contains unreadable archive data") from exc


def _read_zip_json(
    archive: zipfile.ZipFile, name: str, *, maximum: int = 1024 * 1024
) -> dict[str, Any]:
    try:
        info = archive.getinfo(name)
    except KeyError as exc:
        raise ArtifactError(f"JAR is missing {name}") from exc
    if info.file_size <= 0 or info.file_size > maximum:
        raise ArtifactError(f"JAR metadata {name} has an invalid size")
    return _decode_zip_json(archive.read(info), name, maximum=maximum)


def _decode_zip_json(raw: bytes, name: str, *, maximum: int = 1024 * 1024) -> dict[str, Any]:
    try:
        from scripts.lib.secure_json import loads

        value = loads(raw, label=f"JAR {name}", max_bytes=maximum)
    except SecureJsonError as exc:
        raise ArtifactError(str(exc)) from exc
    if not isinstance(value, dict):
        raise ArtifactError(f"JAR {name} must contain a JSON object")
    return value


def lane_build_identity(
    document: MatrixDocument, artifact_node: str, *, matrix_digest: str,
    contract_digest: str, commit: str, tree: str,
) -> dict[str, Any]:
    """Expected embedded identity from trusted caller inputs, never archive claims."""
    for value, size, label in ((matrix_digest, 64, "matrix"), (contract_digest, 64, "contract"),
                               (commit, 40, "commit"), (tree, 40, "tree")):
        if not isinstance(value, str) or re.fullmatch(f"[0-9a-f]{{{size}}}", value) is None:
            raise ArtifactError(f"build identity {label} digest is invalid")
    lane = document.inventory.lane(artifact_node)
    context = document.gradle_context(artifact_node=artifact_node)
    context.pop("matrix")  # The authoritative matrix bytes have their own digest.
    context_digest = hashlib.sha256(json.dumps(context, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {
        "schema_version": 1, "artifact_node": artifact_node,
        "minecraft": lane.identity.minecraft, "loader": lane.identity.loader,
        "mod_version": lane.mod_version, "java": lane.artifact["java"], "gradle_java": lane.gradle_java,
        "matrix_sha256": matrix_digest, "scenario_contract_sha256": contract_digest,
        "git_commit": commit, "git_tree": tree, "build_context_sha256": context_digest,
    }


def _verify_build_identity(
    archive: zipfile.ZipFile, expected: dict[str, Any] | None, artifact: dict[str, Any],
) -> None:
    if expected is None:
        return  # Historical schema-2 manifests did not embed a build identity.
    keys = ("artifact_node", "minecraft", "loader", "java", "mod_version", "gradle_java")
    for key in keys:
        if key in artifact and (type(expected.get(key)) is not type(artifact[key]) or expected[key] != artifact[key]):
            raise ArtifactError("expected build identity is paired with a different artifact lane")
    observed = _read_zip_json(archive, BUILD_IDENTITY_PATH, maximum=16 * 1024)
    if json.dumps(observed, sort_keys=True) != json.dumps(expected, sort_keys=True):
        raise ArtifactError("embedded build identity differs from the expected lane inputs")


def verify_production_jar(
    path: Path, artifact: dict[str, Any], *, build_identity: dict[str, Any] | None = None,
) -> None:
    if any(marker in path.name for marker in ("dev-shadow", "-sources", "-javadoc")):
        raise ArtifactError(f"production path selects a development artifact: {path.name}")
    archive, _, names = inspect_zip(path)
    try:
        _verify_build_identity(archive, build_identity, artifact)
        required = {
            "com/theplumteam/BlockPopsMod.class",
            "blockpops.mixins.json",
            artifact["metadata"]["file"],
        }
        missing = required - names
        if missing:
            raise ArtifactError(f"production JAR is missing {sorted(missing)}")
        _verify_archive_boundary(archive, production=True)
        loader = artifact["loader"]
        if loader == "fabric":
            metadata = _read_zip_json(archive, "fabric.mod.json")
            if metadata.get("id") != "blockpops":
                raise ArtifactError("Fabric production mod id is not blockpops")
            if build_identity is not None and metadata.get("version") != build_identity["mod_version"]:
                raise ArtifactError("Fabric production mod version differs from its lane")
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
            if build_identity is not None:
                import tomllib
                try:
                    mods = tomllib.loads(text).get("mods", [])
                    versions = [mod.get("version") for mod in mods if mod.get("modId") == "blockpops"]
                except (tomllib.TOMLDecodeError, AttributeError, TypeError) as exc:
                    raise ArtifactError("invalid FML production metadata") from exc
                if versions != [build_identity["mod_version"]]:
                    raise ArtifactError("FML production mod version differs from its lane")
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


def verify_harness_jar(
    path: Path, artifact: dict[str, Any], *, build_identity: dict[str, Any] | None = None,
) -> None:
    archive, _, names = inspect_zip(path)
    try:
        _verify_build_identity(archive, build_identity, artifact)
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
        _verify_archive_boundary(archive, production=False)
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


def _git_identity_environment() -> dict[str, str]:
    return {**os.environ, "GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": os.devnull}


def git_commit(repository: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=False,
        env=_git_identity_environment(),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    commit = result.stdout.strip()
    if result.returncode or len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise ArtifactError(f"cannot resolve exact git commit: {result.stderr.strip()}")
    expected = os.environ.get("BLOCKPOPS_TESTED_SHA")
    if expected and expected != commit:
        raise ArtifactError(
            f"BLOCKPOPS_TESTED_SHA {expected} does not equal checkout HEAD {commit}"
        )
    cleanliness = subprocess.run(
        ["git", "-C", str(repository), "diff-index", "--quiet", "HEAD", "--"],
        check=False,
        env=_git_identity_environment(),
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


def git_tree(repository: Path, commit: str) -> str:
    if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise ArtifactError("cannot resolve tree for an invalid exact git commit")
    result = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", f"{commit}^{{tree}}"],
        check=False,
        env=_git_identity_environment(),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    tree = result.stdout.strip()
    if result.returncode or len(tree) != 40 or any(c not in "0123456789abcdef" for c in tree):
        raise ArtifactError(f"cannot resolve exact git tree: {result.stderr.strip()}")
    return tree


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


def _manifest_document(matrix_path: Path) -> MatrixDocument:
    document = load_matrix_document(matrix_path)
    if document.inventory.schema_version != 1:
        raise ArtifactError("schema-2 matrices require scoped schema-3 artifact evidence before staging")
    return document


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
    document = _manifest_document(matrix_file)
    matrix = document.data
    version = mod_version(matrix_file, matrix)
    commit = git_commit(repo)
    tree = git_tree(repo, commit)
    stage_root.mkdir(parents=True, exist_ok=True)
    files_directory = stage_root / "files"
    harness_directory = stage_root / "harness"
    _clean_directory(files_directory)
    _clean_directory(harness_directory)
    rows: list[dict[str, Any]] = []
    for lane in document.select_lanes(scope="full"):
        artifact = lane.artifact
        production_source = repo / lane.production_jar
        harness_source = repo / lane.harness_jar
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
        "git_tree": tree,
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



def _scoped_path(repository: Path, path: Path) -> Path:
    """Reject linked parents before any resolution could hide their presence."""
    candidate = path if path.is_absolute() else repository / path
    try:
        relative = candidate.relative_to(repository)
    except ValueError as exc:
        raise ArtifactError("scoped artifact path is outside the repository") from exc
    if ".." in relative.parts:
        raise ArtifactError("scoped artifact path is not canonical")
    current = repository
    for part in relative.parts:
        current = current / part
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode) or (stat.S_ISREG(info.st_mode) and info.st_nlink != 1):
            raise ArtifactError("scoped artifact paths must not contain symlinks or hardlinks")
    return candidate


def scoped_manifest_context(repository: Path, matrix_path: Path, *, scope: str,
                            artifact_node: str | None = None):
    """Derive schema3 expectations from caller-selected scope and current inputs."""
    repo = repository.resolve()
    records, values = {}, {}
    for label, path, maximum in (("matrix", matrix_path, MAX_MATRIX_BYTES),
                                 ("scenario_contract", Path("e2e/scenario-contract.json"), MAX_MANIFEST_BYTES)):
        path = _scoped_path(repo, path)
        value, payload = read_secure_json(path, label=label, max_bytes=maximum)
        values[label] = value
        records[label] = {"path": path.relative_to(repo).as_posix(), "sha256": hashlib.sha256(payload).hexdigest()}
    document = MatrixDocument(normalize_matrix_inventory(values["matrix"], repository=repo),
                              json.dumps(values["matrix"]))
    if document.inventory.schema_version != 2:
        raise ArtifactError("schema3 artifact manifests require a schema2 matrix")
    if scope not in {"legacy", "lane", "full"}:
        raise ArtifactError("schema3 verification requires an explicit trusted scope")
    lanes = sorted(document.select_lanes(scope=scope, artifact_node=artifact_node),
                   key=lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader))
    commit = git_commit(repo)
    tree = git_tree(repo, commit)
    nodes = [lane.identity.artifact_node for lane in lanes]
    header = {"schema_version": 3, **records, "git_commit": commit, "git_tree": tree,
              "release_branch": document.branch_name,
              "scope": {"kind": scope, "selected_nodes": nodes,
                        "target_nodes": list(document.inventory.target_nodes),
                        "migration_mode": document.inventory.migration_mode,
                        "partial": set(nodes) != set(document.inventory.target_nodes)}}
    rows = []
    for lane in lanes:
        row = {key: lane.artifact[key] for key in ("artifact_node", "minecraft", "loader", "java")}
        row.update(mod_version=lane.mod_version, gradle_java=lane.gradle_java,
                   build_identity=lane_build_identity(document, lane.identity.artifact_node,
                       matrix_digest=records["matrix"]["sha256"], contract_digest=records["scenario_contract"]["sha256"],
                       commit=commit, tree=tree))
        rows.append(row)
    return document, header, rows


def _scoped_inventory(repo: Path, stage_root: Path, manifest_file: Path) -> set[str]:
    actual_paths = set()
    for item in stage_root.iterdir():
        _scoped_path(repo, item)
        if item.name in {"files", "harness"} and item.is_dir():
            for child in item.iterdir():
                _scoped_path(repo, child)
                _regular_file(child, label="scoped artifact", maximum=MAX_JAR_BYTES)
                actual_paths.add(child.relative_to(stage_root).as_posix())
        elif item == manifest_file:
            actual_paths.add(item.name)
        else:
            raise ArtifactError("scoped stage contains an unlisted object")
    return actual_paths


def verify_scoped_staged(*, repository: Path, matrix_path: Path, manifest_path: Path,
                         stage: Path, scope: str, artifact_node: str | None = None) -> dict[str, Any]:
    """Verify a schema3 bundle against an external scope, never its own claim."""
    repo = repository.resolve()
    try:
        stage_root = _scoped_path(repo, stage)
        manifest_file = _scoped_path(repo, manifest_path)
        if stage_root.parent != repo / "build" or manifest_file.parent != stage_root:
            raise ArtifactError("scoped stage must be one build child with a direct manifest")
        manifest, manifest_payload = read_secure_json(manifest_file, label="artifact manifest", max_bytes=MAX_MANIFEST_BYTES)
        document, header, expected_rows = scoped_manifest_context(repo, matrix_path, scope=scope, artifact_node=artifact_node)
        require_object(manifest, label="scoped artifact manifest", required=set(header) | {"artifacts"})
        canonical = lambda value: json.dumps(value, sort_keys=True, allow_nan=False)
        if canonical({key: manifest[key] for key in header}) != canonical(header):
            raise ArtifactError("scoped manifest source, matrix, contract or scope is stale")
        rows = manifest["artifacts"]
        if not isinstance(rows, list) or len(rows) != len(expected_rows):
            raise ArtifactError("scoped manifest has an incomplete artifact inventory")
        expected_paths = {manifest_file.relative_to(stage_root).as_posix()}
        for row, expected in zip(rows, expected_rows, strict=True):
            require_object(row, label="scoped artifact row", required=set(expected) | {"production", "harness"})
            if canonical({key: row[key] for key in expected}) != canonical(expected):
                raise ArtifactError("scoped manifest has a stale, duplicate or reordered lane identity")
            lane = document.inventory.lane(expected["artifact_node"])
            for kind, directory, source, verify in (
                ("production", "files", lane.production_jar, verify_production_jar),
                ("harness", "harness", lane.harness_jar, verify_harness_jar),
            ):
                record = require_object(row[kind], label=f"scoped {kind}", required=FILE_KEYS)
                relative = f"{directory}/{Path(source).name}"
                if (record["path"] != relative or record["filename"] != Path(source).name
                        or type(record["bytes"]) is not int or relative in expected_paths):
                    raise ArtifactError("scoped artifact path, size type or filename is invalid")
                path = _scoped_path(repo, stage_root / relative)
                _manifest_file(stage_root, record, label=f"scoped {kind}")
                verify(path, lane.artifact, build_identity=expected["build_identity"])
                expected_paths.add(relative)
        actual_paths = _scoped_inventory(repo, stage_root, manifest_file)
        if actual_paths != expected_paths:
            raise ArtifactError("scoped stage file inventory differs from the manifest")
        _, final_header, final_rows = scoped_manifest_context(repo, matrix_path, scope=scope, artifact_node=artifact_node)
        if canonical((header, expected_rows)) != canonical((final_header, final_rows)):
            raise ArtifactError("scoped inputs changed during artifact verification")
        for row in rows:
            for kind in ("production", "harness"):
                _scoped_path(repo, stage_root / row[kind]["path"])
                _manifest_file(stage_root, row[kind], label=f"final scoped {kind}")
        _scoped_path(repo, manifest_file)
        _, final_payload = read_secure_json(manifest_file, label="final artifact manifest", max_bytes=MAX_MANIFEST_BYTES)
        if (final_payload != manifest_payload
                or _scoped_inventory(repo, stage_root, manifest_file) != expected_paths):
            raise ArtifactError("scoped staged inventory or manifest changed during verification")
        return manifest
    except (SecureJsonError, OSError) as exc:
        raise ArtifactError(str(exc)) from exc


def verify_staged(
    *,
    repository: Path,
    matrix_path: Path,
    manifest_path: Path,
    stage: Path,
    scope: str | None = None,
    artifact_node: str | None = None,
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
        if isinstance(raw_manifest, dict) and type(raw_manifest.get("schema_version")) is int and raw_manifest["schema_version"] == 3:
            return verify_scoped_staged(repository=repo, matrix_path=matrix_path, manifest_path=manifest_path,
                                        stage=stage, scope=scope, artifact_node=artifact_node)
        if scope is not None or artifact_node is not None:
            raise ArtifactError("explicit scoped verification requires a schema3 artifact manifest")
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
    document = _manifest_document(matrix_path)
    matrix = document.data
    if matrix_record != {
        "path": matrix_path.resolve().relative_to(repo).as_posix(),
        "sha256": matrix_sha256(matrix_path),
    }:
        raise ArtifactError("artifact manifest matrix identity is stale")
    if manifest["lane_count"] != matrix["lane_count"]:
        raise ArtifactError("artifact manifest lane_count is stale")
    if manifest["mod_version"] != mod_version(matrix_path, matrix):
        raise ArtifactError("artifact manifest mod_version is stale")
    current_commit = git_commit(repo)
    if manifest["git_commit"] != current_commit:
        raise ArtifactError("artifact manifest commit is stale")
    if manifest["git_tree"] != git_tree(repo, current_commit):
        raise ArtifactError("artifact manifest tree is stale")
    if manifest["release_branch"] != matrix["branch"]["name"]:
        raise ArtifactError("artifact manifest release branch is stale")
    rows = manifest["artifacts"]
    if not isinstance(rows, list) or len(rows) != matrix["lane_count"]:
        raise ArtifactError("artifact manifest has the wrong row count")
    matrix_by_node = {lane.identity.artifact_node: lane for lane in document.select_lanes(scope="full")}
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
        lane = matrix_by_node.get(node)
        if lane is None or node in seen:
            raise ArtifactError(f"manifest has an unknown/duplicate artifact node {node!r}")
        artifact = lane.artifact
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
