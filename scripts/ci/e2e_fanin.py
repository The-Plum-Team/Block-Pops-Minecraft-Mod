#!/usr/bin/env python3
"""Fail-closed fan-in for isolated packaged-E2E lane artifacts.

The matrix jobs deliberately upload one artifact per runtime lane.  This module
never overlays those hostile trees.  It validates each lane against the
branch-local release matrix, scenario contract, and verified production bundle,
then creates one fresh, content-inventoried aggregate for downstream curation.
The aggregate carries a canonical non-self-referential receipt so a protected
consumer can bind every payload byte to the authenticated source run identity.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import stat
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.packaged_runtime import RuntimeFailure, validate_packaged_result  # noqa: E402
from e2e.scenario_contract import (  # noqa: E402
    ScenarioContract,
    ScenarioContractError,
    load_contract,
)
from scripts.lib import atomic_directory  # noqa: E402
from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    canonical_json,
    loads as secure_loads,
    read as read_secure_json,
)
from scripts.release.artifact_manifest import (  # noqa: E402
    ArtifactError,
    MAX_MANIFEST_BYTES as MAX_ARTIFACT_MANIFEST_BYTES,
    SCHEMA_VERSION as ARTIFACT_SCHEMA_VERSION,
    lane_build_identity,
    verify_staged,
)
from scripts.release.matrix import (  # noqa: E402
    MatrixError,
    MAX_MATRIX_BYTES,
    MatrixDocument,
    gha_matrix,
    load_matrix,
    matrix_sha256,
    normalize_matrix_inventory,
    valid_branch_name,
)

SCHEMA_VERSION = 1
KIND = "packaged-e2e-aggregate"
WORKFLOW = ".github/workflows/on-demand-e2e.yml"
AGGREGATE_ARTIFACT = "packaged-e2e-aggregate"
ARTIFACT_PREFIX = "packaged-e2e-"
AGGREGATE_RECEIPT = "aggregate.json"

SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SAFE_ARTIFACT = re.compile(r"^[A-Za-z0-9_.-]{1,240}$")

MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
MAX_FILES = 4096
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_METRIC = (1 << 63) - 1
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class FanInError(ValueError):
    """Raised when lane evidence is incomplete, stale, unsafe, or ambiguous."""


def run_artifact_prefix(commit: str, run_attempt: int) -> str:
    """Return the immutable prefix shared by artifacts from one exact attempt."""

    _digest(commit, "artifact commit", SHA1)
    _positive_int(run_attempt, "artifact run attempt")
    return f"{ARTIFACT_PREFIX}{commit}-{run_attempt}-"


def aggregate_artifact_name(commit: str, run_attempt: int) -> str:
    """Name the aggregate so a prior attempt can never satisfy a newer run."""

    return run_artifact_prefix(commit, run_attempt) + "aggregate"


@dataclass(frozen=True)
class SourceIdentity:
    repository: str
    source_branch: str
    commit: str
    tree: str
    run_id: int
    run_attempt: int
    projection: str

    def validate(self) -> None:
        if REPOSITORY.fullmatch(self.repository) is None:
            raise FanInError("repository must use owner/name form")
        if not valid_branch_name(self.source_branch):
            raise FanInError("source branch is not a safe exact Git ref name")
        _digest(self.commit, "source commit", SHA1)
        _digest(self.tree, "source tree", SHA1)
        _positive_int(self.run_id, "source run id")
        _positive_int(self.run_attempt, "source run attempt")
        if self.projection not in {"pr-anchors", "scheduled-anchors"}:
            raise FanInError("projection must be pr-anchors or scheduled-anchors")


@dataclass(frozen=True)
class ExpectedLane:
    artifact_name: str
    job_id: str
    artifact_node: str
    minecraft: str
    loader: str
    java: int
    scenarios: tuple[str, ...]


def _object(value: Any, label: str, keys: Iterable[str]) -> dict[str, Any]:
    expected = set(keys)
    if not isinstance(value, dict) or set(value) != expected:
        actual = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise FanInError(f"{label} fields are invalid: expected {sorted(expected)}, found {actual}")
    return value


def _text(value: Any, label: str, *, maximum: int = 1024) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or "\x00" in value
        or len(value.encode("utf-8")) > maximum
    ):
        raise FanInError(f"{label} must be a bounded non-empty trimmed string")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise FanInError(f"{label} must be a positive integer")
    return value


def _metric_int(value: Any, label: str) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not 0 <= value <= MAX_METRIC
    ):
        raise FanInError(f"{label} must be a bounded non-negative integer")
    return value


def _number(value: Any, label: str, *, minimum: float = 0.0, maximum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise FanInError(f"{label} must be a finite number")
    number = float(value)
    if not math.isfinite(number) or number < minimum or (maximum is not None and number > maximum):
        raise FanInError(f"{label} is outside its numeric bound")
    return number


def _digest(value: Any, label: str, pattern: re.Pattern[str] = SHA256) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise FanInError(f"{label} must be an exact lowercase digest")
    return value


def _canonical_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value or ":" in value:
        raise FanInError(f"{label} is not a canonical relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or value != path.as_posix() or any(part in {"", ".", ".."} for part in path.parts):
        raise FanInError(f"{label} is not a canonical relative path")
    return value


def _real_directory(path: Path, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as exc:
        raise FanInError(f"cannot inspect {label}: {exc}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise FanInError(f"{label} must be a real directory")
    return info


def _inventory(root: Path) -> tuple[set[str], dict[str, tuple[int, int, int]]]:
    _real_directory(root, "evidence root")
    directories: set[str] = set()
    files: dict[str, tuple[int, int, int]] = {}
    file_identities: set[tuple[int, int]] = set()
    total = 0
    try:
        for current, names, filenames in os.walk(root, followlinks=False):
            parent = Path(current)
            for name in names:
                path = parent / name
                info = path.lstat()
                relative = path.relative_to(root).as_posix()
                _canonical_path(relative, "evidence directory")
                if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
                    raise FanInError(f"evidence contains an unsafe directory: {relative}")
                if relative in directories:
                    raise FanInError(f"evidence repeats directory {relative}")
                directories.add(relative)
            for name in filenames:
                path = parent / name
                info = path.lstat()
                relative = path.relative_to(root).as_posix()
                _canonical_path(relative, "evidence file")
                if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                    raise FanInError(f"evidence contains an unsafe file: {relative}")
                if relative in files:
                    raise FanInError(f"evidence repeats file {relative}")
                identity = (info.st_dev, info.st_ino)
                if identity in file_identities:
                    raise FanInError(f"evidence aliases one file inode at {relative}")
                file_identities.add(identity)
                files[relative] = (info.st_dev, info.st_ino, info.st_size)
                total += info.st_size
                if len(files) > MAX_FILES or total > MAX_TOTAL_BYTES:
                    raise FanInError("evidence exceeds its file-count or byte bound")
    except FanInError:
        raise
    except OSError as exc:
        raise FanInError(f"cannot inventory evidence: {exc}") from exc
    return directories, files


def _maximum_for(relative: str) -> int:
    if relative.endswith(".png"):
        return MAX_SCREENSHOT_BYTES
    if "/logs/" in f"/{relative}":
        return MAX_LOG_BYTES
    return MAX_JSON_BYTES


def _read_file(root: Path, relative: str, *, allow_empty: bool = False) -> bytes:
    relative = _canonical_path(relative, "evidence file")
    current = root
    for component in PurePosixPath(relative).parts[:-1]:
        current /= component
        _real_directory(current, f"parent of {relative}")
    path = root.joinpath(*PurePosixPath(relative).parts)
    try:
        before = path.lstat()
    except OSError as exc:
        raise FanInError(f"cannot stat evidence file {relative}: {exc}") from exc
    maximum = _maximum_for(relative)
    if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
        raise FanInError(f"evidence file is not regular: {relative}")
    if before.st_size > maximum or (before.st_size == 0 and not allow_empty):
        raise FanInError(f"evidence file size is outside its bound: {relative}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise FanInError(f"cannot open evidence file {relative}: {exc}") from exc
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino, opened.st_size)
            != (before.st_dev, before.st_ino, before.st_size)
        ):
            raise FanInError(f"evidence file changed while opening: {relative}")
        chunks: list[bytes] = []
        remaining = maximum + 1
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            (after.st_dev, after.st_ino, after.st_size)
            != (opened.st_dev, opened.st_ino, opened.st_size)
            or len(data) != opened.st_size
        ):
            raise FanInError(f"evidence file changed while reading: {relative}")
    finally:
        os.close(descriptor)
    return data


def _json(root: Path, relative: str, label: str) -> dict[str, Any]:
    try:
        value = secure_loads(_read_file(root, relative), label=label, max_bytes=MAX_JSON_BYTES)
    except SecureJsonError as exc:
        raise FanInError(str(exc)) from exc
    if not isinstance(value, dict):
        raise FanInError(f"{label} must be an object")
    return value


def _json_bytes(value: Any) -> bytes:
    encoded = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
    if not encoded or len(encoded) > MAX_MANIFEST_BYTES:
        raise FanInError("generated JSON exceeds its byte bound")
    return encoded


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def expected_lanes(
    matrix: dict[str, Any],
    contract: ScenarioContract,
    projection: str,
    *,
    artifact_prefix: str = ARTIFACT_PREFIX,
    scope: str | None = None,
    artifact_node: str | None = None,
) -> tuple[ExpectedLane, ...]:
    if projection not in {"pr-anchors", "scheduled-anchors"}:
        raise FanInError("projection must be pr-anchors or scheduled-anchors")
    document = _selected_document(matrix, scope=scope, artifact_node=artifact_node)
    projected = document.projection(projection, contract=contract, scope=scope,
                                    artifact_node=artifact_node).get("include")
    if not isinstance(projected, list) or not projected:
        raise FanInError("authoritative E2E projection is empty")
    lanes: list[ExpectedLane] = []
    names: set[str] = set()
    nodes: set[str] = set()
    for index, row in enumerate(projected):
        if not isinstance(row, dict):
            raise FanInError(f"projected lane {index} is not an object")
        scenarios_value = row.get("scenarios")
        if not isinstance(scenarios_value, str):
            raise FanInError(f"projected lane {index} has no scenario projection")
        scenarios = tuple(scenarios_value.split(","))
        if not scenarios or any(not item for item in scenarios) or len(scenarios) != len(set(scenarios)):
            raise FanInError(f"projected lane {index} scenarios are empty/duplicated")
        job_id = _text(row.get("id"), f"projected lane {index}.id", maximum=200)
        artifact_name = artifact_prefix + job_id
        node = _text(row.get("artifact_node"), f"projected lane {index}.artifact_node")
        if SAFE_ARTIFACT.fullmatch(artifact_name) is None or artifact_name in names or node in nodes:
            raise FanInError("projected lane artifact identities are unsafe or duplicated")
        lanes.append(
            ExpectedLane(
                artifact_name=artifact_name,
                job_id=job_id,
                artifact_node=node,
                minecraft=_text(row.get("minecraft"), f"projected lane {index}.minecraft"),
                loader=_text(row.get("loader"), f"projected lane {index}.loader"),
                java=_positive_int(row.get("java"), f"projected lane {index}.java"),
                scenarios=scenarios,
            )
        )
        names.add(artifact_name)
        nodes.add(node)
    return tuple(lanes)


def _selected_document(matrix, *, scope=None, artifact_node=None):
    document = MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))
    if document.inventory.schema_version == 2:
        if scope not in {"lane", "legacy", "full"}:
            raise FanInError("schema2 fan-in requires an explicit external scope")
    elif scope is not None or artifact_node is not None:
        raise FanInError("scoped fan-in requires a schema2 matrix")
    document.select_lanes(scope=scope, artifact_node=artifact_node)
    return document


def _scoped_lane_inputs(document, manifest, *, matrix_digest, contract, scope, artifact_node):
    """Bind an already verified bundle to the exact loaded matrix/contract and scope."""
    _object(manifest, "scoped artifact manifest", {"schema_version", "matrix", "scenario_contract",
        "git_commit", "git_tree", "release_branch", "scope", "artifacts"})
    matrix_record = _object(manifest["matrix"], "scoped matrix", {"path", "sha256"})
    contract_record = _object(manifest["scenario_contract"], "scoped contract", {"path", "sha256"})
    selected = sorted(document.select_lanes(scope=scope, artifact_node=artifact_node),
                      key=lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader))
    nodes = [lane.identity.artifact_node for lane in selected]
    expected_scope = {"kind": scope, "selected_nodes": nodes,
                      "target_nodes": list(document.inventory.target_nodes),
                      "migration_mode": document.inventory.migration_mode,
                      "partial": set(nodes) != set(document.inventory.target_nodes)}
    if (type(manifest.get("schema_version")) is not int or manifest["schema_version"] != 3
            or _json_bytes(manifest["scope"]) != _json_bytes(expected_scope)
            or manifest.get("release_branch") != document.branch_name
            or matrix_record["sha256"] != matrix_digest or contract_record["sha256"] != contract.sha256):
        raise FanInError("scoped fan-in bundle disagrees with loaded inputs or external scope")
    commit = _digest(manifest.get("git_commit"), "scoped bundle commit", SHA1)
    tree = _digest(manifest.get("git_tree"), "scoped bundle tree", SHA1)
    rows = manifest.get("artifacts")
    if not isinstance(rows, list) or len(rows) != len(selected):
        raise FanInError("scoped fan-in bundle has incomplete lanes")
    hashes = {}
    for row, lane in zip(rows, selected, strict=True):
        expected = {key: lane.artifact[key] for key in ("artifact_node", "minecraft", "loader", "java")}
        expected.update(mod_version=lane.mod_version, gradle_java=lane.gradle_java,
                        build_identity=lane_build_identity(document, lane.identity.artifact_node,
                            matrix_digest=matrix_digest, contract_digest=contract.sha256, commit=commit, tree=tree))
        _object(row, "scoped artifact row", set(expected) | {"production", "harness"})
        if _json_bytes({key: row[key] for key in expected}) != _json_bytes(expected):
            raise FanInError("scoped fan-in lane metadata or embedded identity is stale")
        hashes[lane.identity.artifact_node] = tuple(
            _digest(row[kind].get("sha256") if isinstance(row[kind], dict) else None,
                    f"scoped {kind} hash") for kind in ("production", "harness"))
    return hashes, expected_scope


def _execution_coverage(lane, artifact_scope):
    return {"execution_scope": {"kind": "lane", "selected_nodes": [lane.artifact_node],
        "scenarios": list(lane.scenarios), "target_nodes": artifact_scope["target_nodes"],
        "partial": True, "artifact_scope": artifact_scope}}


def _aggregate_coverage(lanes, artifact_scope, projection):
    return {"aggregate_scope": {**artifact_scope, "projection": projection,
                                "scenarios": list(lanes[0].scenarios)}}


def _profile_name(lane: ExpectedLane, scenario: str) -> str:
    value = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
    if SAFE_ARTIFACT.fullmatch(value) is None:
        raise FanInError(f"profile identity is unsafe: {value!r}")
    return value


# The orchestrator leaves its workspace ownership marker at the root of every
# evidence directory it promotes. It is not evidence and is never copied into the
# aggregate, but a real run produces it, so it is accepted the way the visual
# evidence reader accepts it: optional, and only in its exact form.
WORKSPACE_MARKER = ".blockpops-run-workspace.json"


def _validate_workspace_marker(root: Path) -> None:
    raw = _read_file(root, WORKSPACE_MARKER)
    try:
        marker = secure_loads(raw, label="packaged workspace ownership marker", max_bytes=4096)
    except SecureJsonError as exc:
        raise FanInError(str(exc)) from exc
    generation = marker.get("generation") if isinstance(marker, dict) else None
    expected = {"schema": 1, "kind": "blockpops-run-workspace-snapshot", "generation": generation}
    if (not isinstance(generation, str) or re.fullmatch(r"[0-9a-f]{32}", generation) is None
            or raw != canonical_json(expected) + b"\n"):
        raise FanInError("packaged workspace ownership marker is invalid")


def _profile_layout(
    lane: ExpectedLane, contract: ScenarioContract
) -> tuple[set[str], set[str]]:
    directories = {"profiles"}
    files = {"summary.json", "resolved-matrix.json", "runtime-store.json"}
    for scenario in lane.scenarios:
        profile = f"profiles/{_profile_name(lane, scenario)}"
        roles = contract.expected_roles(scenario)
        directories.update({profile, f"{profile}/logs"})
        files.update(
            {
                f"{profile}/result.json",
                f"{profile}/logs/server-install.log",
                f"{profile}/logs/server.log",
                *(f"{profile}/logs/{role}.log" for role in roles),
            }
        )
        for role in roles:
            report = f"{profile}/{role}/e2e-report"
            screenshots = f"{profile}/{role}/screenshots"
            directories.update({f"{profile}/{role}", report, screenshots})
            files.update({f"{report}/report.json", f"{report}/done.marker"})
            for step in contract.role(scenario, role).steps:
                if step.capture is not None:
                    files.add(f"{screenshots}/{step.capture.capture_id}.png")
    return directories, files


def _runtime_metrics(value: Any, label: str) -> dict[str, int]:
    metrics = _object(
        value,
        label,
        {"hits", "misses", "pruned_entries", "pruned_bytes", "total_bytes"},
    )
    return {key: _metric_int(item, f"{label}.{key}") for key, item in metrics.items()}


def _png_dimensions(data: bytes, label: str) -> tuple[int, int]:
    if len(data) < 45 or data[:8] != PNG_SIGNATURE:
        raise FanInError(f"{label} is not a bounded PNG")
    length = struct.unpack(">I", data[8:12])[0]
    if length != 13 or data[12:16] != b"IHDR":
        raise FanInError(f"{label} has no canonical PNG IHDR")
    ihdr = data[16:29]
    if zlib.crc32(b"IHDR" + ihdr) & 0xFFFFFFFF != struct.unpack(">I", data[29:33])[0]:
        raise FanInError(f"{label} has an invalid PNG IHDR checksum")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", ihdr)
    if (
        width <= 0
        or height <= 0
        or depth not in {8, 16}
        or color not in {0, 2, 4, 6}
        or compression != 0
        or filtering != 0
        or interlace not in {0, 1}
    ):
        raise FanInError(f"{label} has an unsupported PNG header")
    if data[-12:-8] != b"\0\0\0\0" or data[-8:-4] != b"IEND":
        raise FanInError(f"{label} has no final PNG IEND")
    if zlib.crc32(b"IEND") & 0xFFFFFFFF != struct.unpack(">I", data[-4:])[0]:
        raise FanInError(f"{label} has an invalid PNG IEND checksum")
    return width, height


def _decode_screenshot_metrics(
    screenshot: bytes,
    *,
    label: str,
    contract: ScenarioContract,
) -> tuple[dict[str, Any], Any]:
    """Fully decode one PNG and independently reproduce the runtime metrics.

    Lane reports are untrusted input.  Header/CRC checks alone do not prove that
    the IDAT stream is decodable, and report-provided pixel hashes or blank-frame
    metrics are not evidence.  Keep this calculation in the protected fan-in so
    every deterministic gate consumes pixels rather than self-asserted claims.
    """

    dimensions = _png_dimensions(screenshot, label)
    if dimensions != contract.gui_text_reference_size:
        raise FanInError(f"{label} dimensions disagree with the scenario contract")
    try:
        from PIL import Image, ImageStat, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - locked CI requirements install Pillow
        raise FanInError("Pillow is required for packaged evidence validation") from exc

    try:
        Image.MAX_IMAGE_PIXELS = 20_000_000
        with Image.open(io.BytesIO(screenshot)) as image:
            if image.format != "PNG" or getattr(image, "n_frames", 1) != 1:
                raise FanInError(f"{label} is not one PNG frame")
            if image.size != dimensions:
                raise FanInError(f"{label} decoded dimensions disagree with its PNG header")
            image.load()
            rgb = image.convert("RGB")
        sample = rgb.resize((160, 90), Image.Resampling.BILINEAR)
        luma = sample.convert("L")
        entropy = float(luma.entropy())
        channel_stddev = [float(item) for item in ImageStat.Stat(sample).stddev]
        palette_counts = sample.quantize(colors=32).getcolors() or []
        sample_pixels = sample.width * sample.height
        meaningful_colors = sum(
            count >= max(2, sample_pixels // 1000) for count, _ in palette_counts
        )
        histogram = luma.histogram()
        dark_fraction = sum(histogram[:8]) / sample_pixels
        light_fraction = sum(histogram[248:]) / sample_pixels
    except FanInError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise FanInError(f"{label} cannot be fully decoded: {exc}") from exc

    if (
        entropy < 0.75
        or max(channel_stddev) < 2.0
        or meaningful_colors < 4
        or dark_fraction > 0.98
        or light_fraction > 0.995
    ):
        raise FanInError(f"{label} is effectively blank")
    return (
        {
            "width": dimensions[0],
            "height": dimensions[1],
            "file_sha256": _sha256(screenshot),
            "pixel_sha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
            "luma_entropy": round(entropy, 3),
            "meaningful_colors": meaningful_colors,
            "dark_fraction": round(dark_fraction, 4),
            "light_fraction": round(light_fraction, 4),
        },
        rgb,
    )


def _recompute_comparison(
    first: Any,
    second: Any,
    *,
    minimum_changed_fraction: float,
    region: tuple[float, float, float, float] | None,
    label: str,
) -> dict[str, Any]:
    try:
        from PIL import ImageChops
    except ImportError as exc:  # pragma: no cover - locked CI requirements install Pillow
        raise FanInError("Pillow is required for packaged evidence validation") from exc

    if first.size != second.size:
        raise FanInError(f"{label} screenshots have incompatible dimensions")
    first_pixels = first
    second_pixels = second
    if region is not None:
        width, height = first.size
        left, top, right, bottom = region
        box = (
            int(left * width),
            int(top * height),
            int(right * width),
            int(bottom * height),
        )
        if box[0] >= box[2] or box[1] >= box[3]:
            raise FanInError(f"{label} comparison region is empty")
        first_pixels = first.crop(box)
        second_pixels = second.crop(box)
    difference = ImageChops.difference(first_pixels, second_pixels).convert("L")
    histogram = difference.histogram()
    pixels = difference.width * difference.height
    changed_fraction = sum(histogram[8:]) / pixels
    rms_difference = (
        sum(value * value * count for value, count in enumerate(histogram)) / pixels
    ) ** 0.5
    result: dict[str, Any] = {
        "changed_fraction": round(changed_fraction, 7),
        "rms_difference": round(rms_difference, 3),
        "required_changed_fraction": minimum_changed_fraction,
    }
    if region is not None:
        result["region"] = list(region)
    return result


def _validate_screenshot_metrics(
    value: Any,
    *,
    label: str,
    screenshot: bytes,
    contract: ScenarioContract,
) -> Any:
    metrics = _object(
        value,
        label,
        {
            "width",
            "height",
            "file_sha256",
            "pixel_sha256",
            "luma_entropy",
            "meaningful_colors",
            "dark_fraction",
            "light_fraction",
        },
    )
    # Validate primitive types first so equality cannot exploit bool/int or NaN quirks.
    _metric_int(metrics["width"], f"{label}.width")
    _metric_int(metrics["height"], f"{label}.height")
    _digest(metrics["file_sha256"], f"{label}.file_sha256")
    _digest(metrics["pixel_sha256"], f"{label}.pixel_sha256")
    _number(metrics["luma_entropy"], f"{label}.luma_entropy", maximum=8.0)
    _metric_int(metrics["meaningful_colors"], f"{label}.meaningful_colors")
    _number(metrics["dark_fraction"], f"{label}.dark_fraction", maximum=1.0)
    _number(metrics["light_fraction"], f"{label}.light_fraction", maximum=1.0)
    actual, rgb = _decode_screenshot_metrics(
        screenshot,
        label=label,
        contract=contract,
    )
    if metrics != actual:
        raise FanInError(f"{label} recorded pixel metrics are stale or fabricated")
    return rgb


def _validate_report(
    report: Any,
    *,
    lane: ExpectedLane,
    scenario: str,
    role: str,
    contract: ScenarioContract,
    profile_files: dict[str, bytes],
) -> dict[str, Any]:
    label = f"report {lane.artifact_node}/{scenario}/{role}"
    value = _object(
        report,
        label,
        {
            "schema_version",
            "minecraft",
            "role",
            "scenario",
            "contract_sha256",
            "status",
            "steps",
            "pixel_validation",
        },
    )
    if (
        value["schema_version"] != 1
        or value["minecraft"] != lane.minecraft
        or value["role"] != role
        or value["scenario"] != scenario
        or value["contract_sha256"] != contract.sha256
        or value["status"] != "pass"
    ):
        raise FanInError(f"{label} identity/status is stale")
    role_contract = contract.role(scenario, role)
    steps = value["steps"]
    if not isinstance(steps, list) or len(steps) != len(role_contract.steps):
        raise FanInError(f"{label} step inventory is incomplete")
    capture_steps: set[str] = set()
    for index, (actual, expected) in enumerate(zip(steps, role_contract.steps, strict=True)):
        step = _object(
            actual,
            f"{label}.steps[{index}]",
            {"id", "status", "message", "capture_id", "screenshot"},
        )
        capture = expected.capture
        capture_id = capture.capture_id if capture is not None else None
        screenshot = f"{capture_id}.png" if capture is not None else None
        if (
            step["id"] != expected.id
            or step["status"] != "pass"
            or step["capture_id"] != capture_id
            or step["screenshot"] != screenshot
            or not isinstance(step["message"], str)
            or len(step["message"].encode("utf-8")) > 1024
            or "\x00" in step["message"]
        ):
            raise FanInError(f"{label} step {expected.id!r} is stale")
        if capture is not None:
            capture_steps.add(expected.id)
    pixels = _object(value["pixel_validation"], f"{label}.pixel_validation", {"screenshots", "comparisons"})
    screenshots = pixels["screenshots"]
    if not isinstance(screenshots, dict) or set(screenshots) != capture_steps:
        raise FanInError(f"{label} screenshot metric inventory is stale")
    profile = _profile_name(lane, scenario)
    decoded: dict[str, Any] = {}
    for step in role_contract.steps:
        if step.capture is None:
            continue
        path = f"profiles/{profile}/{role}/screenshots/{step.capture.capture_id}.png"
        decoded[step.id] = _validate_screenshot_metrics(
            screenshots[step.id],
            label=f"{label}/{step.id}",
            screenshot=profile_files[path],
            contract=contract,
        )
    comparisons = pixels["comparisons"]
    expected_comparisons = {
        f"{item.first_step}->{item.second_step}": item for item in role_contract.comparisons
    }
    if not isinstance(comparisons, dict) or set(comparisons) != set(expected_comparisons):
        raise FanInError(f"{label} comparison inventory is stale")
    for key, expected in expected_comparisons.items():
        required = {"changed_fraction", "rms_difference", "required_changed_fraction"}
        if expected.region is not None:
            required.add("region")
        comparison = _object(comparisons[key], f"{label}.comparisons[{key!r}]", required)
        changed = _number(comparison["changed_fraction"], f"{label}.{key}.changed_fraction", maximum=1.0)
        _number(comparison["rms_difference"], f"{label}.{key}.rms_difference", maximum=255.0)
        if comparison["required_changed_fraction"] != expected.minimum_changed_fraction or changed < expected.minimum_changed_fraction:
            raise FanInError(f"{label} comparison {key!r} does not satisfy its contract")
        if expected.region is not None and comparison["region"] != list(expected.region):
            raise FanInError(f"{label} comparison {key!r} region is stale")
        actual = _recompute_comparison(
            decoded[expected.first_step],
            decoded[expected.second_step],
            minimum_changed_fraction=expected.minimum_changed_fraction,
            region=expected.region,
            label=f"{label}.comparisons[{key!r}]",
        )
        if comparison != actual:
            raise FanInError(f"{label} comparison {key!r} is stale or fabricated")
        if actual["changed_fraction"] < expected.minimum_changed_fraction:
            raise FanInError(f"{label} comparison {key!r} does not satisfy its contract")
    return value


def _validate_result(
    result: dict[str, Any],
    *,
    lane: ExpectedLane,
    scenario: str,
    contract: ScenarioContract,
    hashes: tuple[str, str],
    profile_files: dict[str, bytes],
) -> None:
    try:
        validate_packaged_result(result)
    except RuntimeFailure as exc:
        raise FanInError(str(exc)) from exc
    profile = _profile_name(lane, scenario)
    production_sha, harness_sha = hashes
    if (
        result["status"] != "pass"
        or result["artifact_node"] != lane.artifact_node
        or result["minecraft"] != lane.minecraft
        or result["loader"] != lane.loader
        or result["scenario"] != scenario
        or result["contract_sha256"] != contract.sha256
        or result["profile"] != f"profiles/{profile}"
        or result["production_jar_sha256"] != production_sha
        or result["harness_jar_sha256"] != harness_sha
        or production_sha == harness_sha
        or result["error"] is not None
    ):
        raise FanInError(f"packaged result identity is stale for {lane.artifact_node}/{scenario}")
    roles = contract.expected_roles(scenario)
    installed = result["installed_blockpops"]
    owners: set[str] = set()
    for item in installed:
        path = _canonical_path(item["path"], "installed production path")
        parts = PurePosixPath(path).parts
        if len(parts) < 3 or parts[1] != "mods" or item["sha256"] != production_sha:
            raise FanInError("installed production inventory is stale")
        owners.add(parts[0])
    if len(installed) != len(owners) or owners != {"server", *roles}:
        raise FanInError("installed production inventory does not cover server and exact roles")
    reports = result["reports"]
    if set(reports) != set(roles):
        raise FanInError("packaged report role inventory is stale")
    for role in roles:
        enriched = _validate_report(
            reports[role], lane=lane, scenario=scenario, role=role,
            contract=contract, profile_files=profile_files,
        )
        raw_path = f"profiles/{profile}/{role}/e2e-report/report.json"
        try:
            raw_report = secure_loads(profile_files[raw_path], label=raw_path, max_bytes=MAX_JSON_BYTES)
        except SecureJsonError as exc:
            raise FanInError(str(exc)) from exc
        expected_raw = {key: value for key, value in enriched.items() if key != "pixel_validation"}
        if raw_report != expected_raw:
            raise FanInError(f"raw and summarized reports disagree for {lane.artifact_node}/{scenario}/{role}")
        marker = f"profiles/{profile}/{role}/e2e-report/done.marker"
        if profile_files[marker] != b"pass":
            raise FanInError(f"completion marker is stale for {lane.artifact_node}/{scenario}/{role}")


def _validate_lane_payload(
    root: Path,
    *,
    lane: ExpectedLane,
    contract: ScenarioContract,
    hashes: tuple[str, str],
    coverage: dict[str, Any] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, int], dict[str, bytes]]:
    expected_directories, expected_files = _profile_layout(lane, contract)
    before = _inventory(root)
    observed_files = set(before[1])
    if WORKSPACE_MARKER in observed_files:
        _validate_workspace_marker(root)
        observed_files.discard(WORKSPACE_MARKER)
    if before[0] != expected_directories or observed_files != expected_files:
        raise FanInError(
            f"lane {lane.artifact_name} inventory mismatch: "
            f"missing files={sorted(expected_files - observed_files)}, "
            f"unknown files={sorted(observed_files - expected_files)}, "
            f"missing directories={sorted(expected_directories - before[0])}, "
            f"unknown directories={sorted(before[0] - expected_directories)}"
        )
    profile_files = {
        path: _read_file(root, path, allow_empty="/logs/" in f"/{path}")
        for path in sorted(expected_files)
        if path.startswith("profiles/")
    }
    results_by_scenario: dict[str, dict[str, Any]] = {}
    for scenario in lane.scenarios:
        relative = f"profiles/{_profile_name(lane, scenario)}/result.json"
        try:
            result = secure_loads(profile_files[relative], label=relative, max_bytes=MAX_JSON_BYTES)
        except SecureJsonError as exc:
            raise FanInError(str(exc)) from exc
        if not isinstance(result, dict) or scenario in results_by_scenario:
            raise FanInError("lane result is not a unique object")
        _validate_result(
            result, lane=lane, scenario=scenario, contract=contract,
            hashes=hashes, profile_files=profile_files,
        )
        results_by_scenario[scenario] = result

    summary = _object(
        _json(root, "summary.json", f"{lane.artifact_name} summary"),
        f"{lane.artifact_name} summary",
        {"schema_version", "contract_sha256", "results", "runtime_store"} | set(coverage or {}),
    )
    if type(summary["schema_version"]) is not int or summary["schema_version"] != 1 or summary["contract_sha256"] != contract.sha256:
        raise FanInError(f"{lane.artifact_name} summary identity is stale")
    if coverage and _json_bytes({key: summary[key] for key in coverage}) != _json_bytes(coverage):
        raise FanInError(f"{lane.artifact_name} summary execution scope is stale")
    summary_results = summary["results"]
    if not isinstance(summary_results, list) or len(summary_results) != len(lane.scenarios):
        raise FanInError(f"{lane.artifact_name} summary result inventory is incomplete")
    if summary_results != [results_by_scenario[item] for item in lane.scenarios]:
        raise FanInError(f"{lane.artifact_name} summary results disagree with profile results")
    metrics = _runtime_metrics(summary["runtime_store"], f"{lane.artifact_name}.runtime_store")

    resolved = _object(
        _json(root, "resolved-matrix.json", f"{lane.artifact_name} resolved matrix"),
        f"{lane.artifact_name} resolved matrix",
        {"schema_version", "rows"} | set(coverage or {}),
    )
    expected_rows = [
        {
            "artifact_node": lane.artifact_node,
            "minecraft": lane.minecraft,
            "loader": lane.loader,
            "scenario": scenario,
            "production_jar_sha256": hashes[0],
        }
        for scenario in lane.scenarios
    ]
    if _json_bytes(resolved) != _json_bytes({"schema_version": 1, "rows": expected_rows, **(coverage or {})}):
        raise FanInError(f"{lane.artifact_name} resolved matrix is stale")
    runtime_store = _object(
        _json(root, "runtime-store.json", f"{lane.artifact_name} runtime store"),
        f"{lane.artifact_name} runtime store",
        {"schema_version", "metrics"},
    )
    if type(runtime_store["schema_version"]) is not int or runtime_store["schema_version"] != 1 or _runtime_metrics(
        runtime_store["metrics"], f"{lane.artifact_name}.runtime-store.metrics"
    ) != metrics:
        raise FanInError(f"{lane.artifact_name} runtime-store records disagree")
    if _inventory(root) != before:
        raise FanInError(f"lane {lane.artifact_name} changed during validation")
    return [results_by_scenario[item] for item in lane.scenarios], metrics, profile_files


def _aggregate_metrics(items: Iterable[dict[str, int]]) -> dict[str, int]:
    total = {key: 0 for key in ("hits", "misses", "pruned_entries", "pruned_bytes", "total_bytes")}
    for metrics in items:
        for key in total:
            total[key] += metrics[key]
            if total[key] > MAX_METRIC:
                raise FanInError("aggregate runtime telemetry overflows its bound")
    return total


def _manifest_lane(lane: ExpectedLane, hashes: tuple[str, str]) -> dict[str, Any]:
    return {
        "artifact_name": lane.artifact_name,
        "job_id": lane.job_id,
        "artifact_node": lane.artifact_node,
        "minecraft": lane.minecraft,
        "loader": lane.loader,
        "java": lane.java,
        "scenarios": list(lane.scenarios),
        "production_jar_sha256": hashes[0],
        "harness_jar_sha256": hashes[1],
    }


def _artifact_hashes(
    artifact_manifest: dict[str, Any],
    *,
    matrix: dict[str, Any],
    expected_nodes: set[str],
    commit: str | None = None,
    tree: str | None = None,
) -> dict[str, tuple[str, str]]:
    """Project verified production/harness hashes for an exact matrix inventory."""

    if (
        artifact_manifest.get("schema_version") != ARTIFACT_SCHEMA_VERSION
        or artifact_manifest.get("release_branch") != matrix["branch"]["name"]
        or artifact_manifest.get("lane_count") != matrix["lane_count"]
        or (commit is not None and artifact_manifest.get("git_commit") != commit)
        or (tree is not None and artifact_manifest.get("git_tree") != tree)
    ):
        raise FanInError("verified artifact manifest schema/commit/tree/matrix identity is stale")
    rows = artifact_manifest.get("artifacts")
    if not isinstance(rows, list):
        raise FanInError("verified artifact manifest has no artifact rows")
    by_node: dict[str, tuple[str, str]] = {}
    for raw in rows:
        if (
            not isinstance(raw, dict)
            or not isinstance(raw.get("production"), dict)
            or not isinstance(raw.get("harness"), dict)
        ):
            raise FanInError("verified artifact manifest row is malformed")
        node = raw.get("artifact_node")
        if not isinstance(node, str) or node in by_node:
            raise FanInError("verified artifact manifest repeats an artifact node")
        by_node[node] = (
            _digest(raw["production"].get("sha256"), f"{node} production sha256"),
            _digest(raw["harness"].get("sha256"), f"{node} harness sha256"),
        )
    if set(by_node) != expected_nodes:
        raise FanInError("verified artifact manifest disagrees with projected lanes")
    return by_node


def validate_lane(
    *,
    root: Path,
    matrix_path: Path,
    contract_path: Path,
    projection: str,
    row: dict[str, Any],
    artifact_manifest: dict[str, Any],
    scope: str | None = None,
    artifact_node: str | None = None,
) -> dict[str, Any]:
    """Revalidate one sealed runtime lane before its same-run artifact upload."""

    if projection not in {"pr-anchors", "scheduled-anchors"}:
        raise FanInError("projection must be pr-anchors or scheduled-anchors")
    matrix, matrix_bytes = read_secure_json(matrix_path, label="fan-in matrix", max_bytes=MAX_MATRIX_BYTES)
    document = _selected_document(matrix, scope=scope, artifact_node=artifact_node)
    contract = load_contract(contract_path)
    projected = document.projection(projection, contract=contract, scope=scope,
                                    artifact_node=artifact_node).get("include")
    if not isinstance(projected, list):
        raise FanInError("authoritative E2E projection is malformed")
    matches = [index for index, expected in enumerate(projected) if _json_bytes(expected) == _json_bytes(row)]
    if len(matches) != 1:
        raise FanInError("runtime row is not one exact authoritative projected lane")
    lanes = expected_lanes(matrix, contract, projection, scope=scope, artifact_node=artifact_node)
    if len(lanes) != len(projected):
        raise FanInError("runtime lane projection is internally inconsistent")
    lane = lanes[matches[0]]
    coverage = {}
    if document.inventory.schema_version == 2:
        hashes, artifact_scope = _scoped_lane_inputs(document, artifact_manifest,
            matrix_digest=_sha256(matrix_bytes), contract=contract, scope=scope, artifact_node=artifact_node)
        coverage = _execution_coverage(lane, artifact_scope)
    else:
        hashes = _artifact_hashes(artifact_manifest, matrix=matrix,
                                 expected_nodes={item.artifact_node for item in lanes})
    results, metrics, _files = _validate_lane_payload(
        root.absolute(), lane=lane, contract=contract, hashes=hashes[lane.artifact_node], coverage=coverage,
    )
    if (read_secure_json(matrix_path, label="final fan-in matrix", max_bytes=MAX_MATRIX_BYTES)[1] != matrix_bytes
            or load_contract(contract_path).sha256 != contract.sha256):
        raise FanInError("loaded fan-in matrix or contract changed during validation")
    return {
        "schema_version": 1,
        "artifact_node": lane.artifact_node,
        "job_id": lane.job_id,
        "scenario_count": len(results),
        "runtime_store": metrics,
        **coverage,
    }


def _provenance(
    identity: SourceIdentity,
    *,
    matrix: dict[str, Any],
    matrix_path: Path,
    contract: ScenarioContract,
) -> dict[str, Any]:
    return {
        "repository": identity.repository,
        "source_branch": identity.source_branch,
        "commit": identity.commit,
        "tree": identity.tree,
        "workflow": WORKFLOW,
        "run_id": identity.run_id,
        "run_attempt": identity.run_attempt,
        "projection": identity.projection,
        "matrix_branch": matrix["branch"]["name"],
        "matrix_sha256": matrix_sha256(matrix_path),
        "contract_sha256": contract.sha256,
    }


def _optional_expected_identity(
    *,
    repository: str | None,
    source_branch: str | None,
    commit: str | None,
    tree: str | None,
    run_id: int | None,
    run_attempt: int | None,
    projection: str,
) -> SourceIdentity | None:
    """Return an externally authenticated source identity, or require none.

    A partial identity is more dangerous than no external identity: it can make
    a caller believe an aggregate is bound while leaving one mutable dimension
    unchecked.  Structural callers may omit the complete group, while workflow
    boundaries pass every field from their protected job-graph authentication.
    """

    values = (repository, source_branch, commit, tree, run_id, run_attempt)
    if all(value is None for value in values):
        return None
    if any(value is None for value in values):
        raise FanInError("expected source identity must be supplied all-or-none")
    identity = SourceIdentity(
        repository=repository,
        source_branch=source_branch,
        commit=commit,
        tree=tree,
        run_id=run_id,
        run_attempt=run_attempt,
        projection=projection,
    )
    identity.validate()
    return identity


def _validate_receipt_shape(value: Any, *, scoped: bool = False) -> tuple[SourceIdentity, str]:
    """Validate every primitive in the non-self-referential aggregate receipt."""

    receipt = _object(
        value,
        "aggregate receipt",
        {"schema_version", "kind", "provenance", "artifact_manifest", "lanes", "files"}
        | ({"aggregate_scope"} if scoped else set()),
    )
    if type(receipt["schema_version"]) is not int or receipt["schema_version"] != SCHEMA_VERSION:
        raise FanInError("aggregate receipt schema version is unsupported")
    if receipt["kind"] != KIND:
        raise FanInError("aggregate receipt kind is stale")

    provenance = _object(
        receipt["provenance"],
        "aggregate receipt provenance",
        {
            "repository",
            "source_branch",
            "commit",
            "tree",
            "workflow",
            "run_id",
            "run_attempt",
            "projection",
            "matrix_branch",
            "matrix_sha256",
            "contract_sha256",
        },
    )
    identity = SourceIdentity(
        repository=_text(provenance["repository"], "aggregate receipt provenance.repository"),
        source_branch=_text(
            provenance["source_branch"], "aggregate receipt provenance.source_branch"
        ),
        commit=_digest(
            provenance["commit"], "aggregate receipt provenance.commit", SHA1
        ),
        tree=_digest(provenance["tree"], "aggregate receipt provenance.tree", SHA1),
        run_id=_positive_int(provenance["run_id"], "aggregate receipt provenance.run_id"),
        run_attempt=_positive_int(
            provenance["run_attempt"], "aggregate receipt provenance.run_attempt"
        ),
        projection=_text(
            provenance["projection"], "aggregate receipt provenance.projection"
        ),
    )
    identity.validate()
    _text(provenance["workflow"], "aggregate receipt provenance.workflow")
    _text(provenance["matrix_branch"], "aggregate receipt provenance.matrix_branch")
    _digest(provenance["matrix_sha256"], "aggregate receipt provenance.matrix_sha256")
    _digest(provenance["contract_sha256"], "aggregate receipt provenance.contract_sha256")

    artifact = _object(
        receipt["artifact_manifest"],
        "aggregate receipt artifact manifest",
        {"path", "schema_version", "sha256", "commit", "tree"},
    )
    _canonical_path(artifact["path"], "aggregate receipt artifact manifest path")
    if (
        type(artifact["schema_version"]) is not int
        or artifact["schema_version"] != (3 if scoped else ARTIFACT_SCHEMA_VERSION)
    ):
        raise FanInError("aggregate receipt artifact manifest schema is stale")
    manifest_sha = _digest(
        artifact["sha256"], "aggregate receipt artifact manifest sha256"
    )
    _digest(artifact["commit"], "aggregate receipt artifact manifest commit", SHA1)
    _digest(artifact["tree"], "aggregate receipt artifact manifest tree", SHA1)

    lanes = receipt["lanes"]
    if not isinstance(lanes, list) or not lanes:
        raise FanInError("aggregate receipt lane inventory is empty")
    for index, raw in enumerate(lanes):
        lane = _object(
            raw,
            f"aggregate receipt lanes[{index}]",
            {
                "artifact_name",
                "job_id",
                "artifact_node",
                "minecraft",
                "loader",
                "java",
                "scenarios",
                "production_jar_sha256",
                "harness_jar_sha256",
            },
        )
        artifact_name = _text(
            lane["artifact_name"], f"aggregate receipt lanes[{index}].artifact_name"
        )
        if SAFE_ARTIFACT.fullmatch(artifact_name) is None:
            raise FanInError("aggregate receipt contains an unsafe lane artifact name")
        for key in ("job_id", "artifact_node", "minecraft", "loader"):
            _text(lane[key], f"aggregate receipt lanes[{index}].{key}")
        _positive_int(lane["java"], f"aggregate receipt lanes[{index}].java")
        scenarios = lane["scenarios"]
        if not isinstance(scenarios, list) or not scenarios:
            raise FanInError("aggregate receipt lane scenarios are empty or duplicated")
        observed_scenarios: set[str] = set()
        for scenario_index, scenario in enumerate(scenarios):
            scenario = _text(
                scenario,
                f"aggregate receipt lanes[{index}].scenarios[{scenario_index}]",
            )
            if scenario in observed_scenarios:
                raise FanInError("aggregate receipt lane scenarios are empty or duplicated")
            observed_scenarios.add(scenario)
        _digest(
            lane["production_jar_sha256"],
            f"aggregate receipt lanes[{index}].production_jar_sha256",
        )
        _digest(
            lane["harness_jar_sha256"],
            f"aggregate receipt lanes[{index}].harness_jar_sha256",
        )

    files = receipt["files"]
    if not isinstance(files, list) or not files:
        raise FanInError("aggregate receipt file inventory is empty")
    paths: set[str] = set()
    for index, raw in enumerate(files):
        record = _object(
            raw,
            f"aggregate receipt files[{index}]",
            {"path", "size", "sha256"},
        )
        path = _canonical_path(record["path"], f"aggregate receipt files[{index}].path")
        if path == AGGREGATE_RECEIPT or path in paths:
            raise FanInError("aggregate receipt file inventory is self-referential or duplicated")
        paths.add(path)
        _metric_int(record["size"], f"aggregate receipt files[{index}].size")
        _digest(record["sha256"], f"aggregate receipt files[{index}].sha256")
    return identity, manifest_sha


def _atomic_directory(output: Path, writer: Any) -> Any:
    try:
        return atomic_directory.atomic_directory(output, writer)
    except atomic_directory.AtomicDirectoryError as exc:
        raise FanInError(str(exc)) from exc


def _write_new(stage: int, relative: str, data: bytes) -> None:
    try:
        atomic_directory.write_new(stage, relative, data)
    except atomic_directory.AtomicDirectoryError as exc:
        raise FanInError(str(exc)) from exc


def create_aggregate(
    *,
    input_root: Path,
    output: Path,
    matrix_path: Path,
    contract_path: Path,
    identity: SourceIdentity,
    artifact_manifest: dict[str, Any],
    artifact_manifest_sha256: str,
    scope: str | None = None,
    artifact_node: str | None = None,
) -> dict[str, Any]:
    """Publish validated lane evidence using caller scope and a preverified artifact bundle.

    SourceIdentity and the bundle's raw digest must come from the authenticated caller;
    producing an aggregate does not authenticate a remote run or qualify its target set.
    """
    identity.validate()
    matrix, matrix_bytes = read_secure_json(matrix_path, label="fan-in matrix", max_bytes=MAX_MATRIX_BYTES)
    document = _selected_document(matrix, scope=scope, artifact_node=artifact_node)
    contract = load_contract(contract_path)
    lanes = expected_lanes(
        matrix,
        contract,
        identity.projection,
        artifact_prefix=run_artifact_prefix(identity.commit, identity.run_attempt),
        scope=scope,
        artifact_node=artifact_node,
    )
    _digest(artifact_manifest_sha256, "artifact manifest sha256")
    scoped = document.inventory.schema_version == 2
    coverage = {}
    if scoped:
        by_node, artifact_scope = _scoped_lane_inputs(document, artifact_manifest,
            matrix_digest=_sha256(matrix_bytes), contract=contract, scope=scope, artifact_node=artifact_node)
        if artifact_manifest["git_commit"] != identity.commit or artifact_manifest["git_tree"] != identity.tree:
            raise FanInError("scoped fan-in bundle commit/tree disagrees with source identity")
        coverage = _aggregate_coverage(lanes, artifact_scope, identity.projection)
    else:
        by_node = _artifact_hashes(
            artifact_manifest,
            matrix=matrix,
            expected_nodes={lane.artifact_node for lane in lanes},
            commit=identity.commit,
            tree=identity.tree,
        )
    root = input_root.absolute()
    root_snapshot = _inventory(root)
    root_directories, root_files = root_snapshot
    expected_artifacts = {lane.artifact_name for lane in lanes}
    top_directories = {path for path in root_directories if "/" not in path}
    top_files = {path for path in root_files if "/" not in path}
    if top_files or top_directories != expected_artifacts:
        raise FanInError(
            "download root must contain exactly one isolated directory per projected lane"
        )

    def writer(stage: Path, stage_fd: int) -> dict[str, Any]:
        all_results: list[dict[str, Any]] = []
        all_metrics: list[dict[str, int]] = []
        copied: dict[str, bytes] = {}
        lane_records: list[dict[str, Any]] = []
        for lane in lanes:
            hashes = by_node[lane.artifact_node]
            results, metrics, profile_files = _validate_lane_payload(
                root / lane.artifact_name,
                lane=lane,
                contract=contract,
                hashes=hashes,
                coverage=_execution_coverage(lane, artifact_scope) if scoped else None,
            )
            all_results.extend(results)
            all_metrics.append(metrics)
            lane_records.append(_manifest_lane(lane, hashes))
            for relative, data in profile_files.items():
                if relative in copied:
                    raise FanInError(f"lane fan-in collides at {relative}")
                copied[relative] = data
        metrics = _aggregate_metrics(all_metrics)
        resolved_rows = [
            {
                "artifact_node": result["artifact_node"],
                "minecraft": result["minecraft"],
                "loader": result["loader"],
                "scenario": result["scenario"],
                "production_jar_sha256": result["production_jar_sha256"],
            }
            for result in all_results
        ]
        copied.update(
            {
                "summary.json": _json_bytes(
                    {
                        "schema_version": 1,
                        "contract_sha256": contract.sha256,
                        "results": all_results,
                        "runtime_store": metrics,
                        **coverage,
                    }
                ),
                "resolved-matrix.json": _json_bytes({"schema_version": 1, "rows": resolved_rows, **coverage}),
                "runtime-store.json": _json_bytes({"schema_version": 1, "metrics": metrics}),
            }
        )
        records: list[dict[str, Any]] = []
        for relative, data in sorted(copied.items()):
            _write_new(stage_fd, relative, data)
            records.append({"path": relative, "size": len(data), "sha256": _sha256(data)})
        receipt = {
            "schema_version": SCHEMA_VERSION,
            "kind": KIND,
            "provenance": _provenance(
                identity, matrix=matrix, matrix_path=matrix_path, contract=contract
            ),
            "artifact_manifest": {
                "path": "build/release/artifacts.json",
                "schema_version": 3 if scoped else ARTIFACT_SCHEMA_VERSION,
                "sha256": artifact_manifest_sha256,
                "commit": identity.commit,
                "tree": identity.tree,
            },
            "lanes": lane_records,
            "files": records,
            **coverage,
        }
        _write_new(stage_fd, AGGREGATE_RECEIPT, _json_bytes(receipt))
        validate_aggregate(
            root=stage,
            matrix_path=matrix_path,
            contract_path=contract_path,
            projection=identity.projection,
            expected_hashes=by_node,
            expected_repository=identity.repository,
            expected_source_branch=identity.source_branch,
            expected_commit=identity.commit,
            expected_tree=identity.tree,
            expected_run_id=identity.run_id,
            expected_run_attempt=identity.run_attempt,
            scope=scope,
            artifact_node=artifact_node,
            artifact_manifest=artifact_manifest if scoped else None,
            artifact_manifest_sha256=artifact_manifest_sha256 if scoped else None,
        )
        if _inventory(root) != root_snapshot:
            raise FanInError("download root changed during fan-in")
        if (read_secure_json(matrix_path, label="final fan-in matrix", max_bytes=MAX_MATRIX_BYTES)[1] != matrix_bytes
                or load_contract(contract_path).sha256 != contract.sha256):
            raise FanInError("loaded fan-in matrix or contract changed before publication")
        return receipt

    return _atomic_directory(output, writer)


def validate_aggregate(
    *,
    root: Path,
    matrix_path: Path,
    contract_path: Path,
    projection: str,
    expected_hashes: dict[str, tuple[str, str]] | None = None,
    expected_repository: str | None = None,
    expected_source_branch: str | None = None,
    expected_commit: str | None = None,
    expected_tree: str | None = None,
    expected_run_id: int | None = None,
    expected_run_attempt: int | None = None,
    scope: str | None = None,
    artifact_node: str | None = None,
    artifact_manifest: dict[str, Any] | None = None,
    artifact_manifest_sha256: str | None = None,
) -> dict[str, Any]:
    """Validate exact aggregate coverage, independently of each lane's execution coverage.

    Schema2 requires caller-selected scope and an already verified bundle plus its raw digest.
    aggregate_scope covers every selected bundle lane and the exact projected scenarios; a
    per-lane execution_scope cannot substitute for it. Source authentication still requires
    the complete external expected-identity group; structural validation alone grants none.
    """
    if projection not in {"pr-anchors", "scheduled-anchors"}:
        raise FanInError("projection must be pr-anchors or scheduled-anchors")
    matrix, matrix_bytes = read_secure_json(matrix_path, label="aggregate matrix", max_bytes=MAX_MATRIX_BYTES)
    document = _selected_document(matrix, scope=scope, artifact_node=artifact_node)
    contract = load_contract(contract_path)
    externally_expected = _optional_expected_identity(
        repository=expected_repository,
        source_branch=expected_source_branch,
        commit=expected_commit,
        tree=expected_tree,
        run_id=expected_run_id,
        run_attempt=expected_run_attempt,
        projection=projection,
    )
    structural_lanes = expected_lanes(matrix, contract, projection, scope=scope, artifact_node=artifact_node)
    scoped = document.inventory.schema_version == 2
    coverage = {}
    if scoped:
        expected_manifest_sha = _digest(artifact_manifest_sha256, "expected scoped manifest sha256")
        bound_hashes, artifact_scope = _scoped_lane_inputs(document, artifact_manifest,
            matrix_digest=_sha256(matrix_bytes), contract=contract, scope=scope, artifact_node=artifact_node)
        if expected_hashes is not None and expected_hashes != bound_hashes:
            raise FanInError("external hashes disagree with the verified scoped bundle")
        expected_hashes = bound_hashes
        coverage = _aggregate_coverage(structural_lanes, artifact_scope, projection)
    elif artifact_manifest is not None or artifact_manifest_sha256 is not None:
        raise FanInError("external scoped bundle inputs require a schema2 matrix")
    root = root.absolute()
    if expected_hashes is not None and set(expected_hashes) != {
        lane.artifact_node for lane in structural_lanes
    }:
        raise FanInError("expected production hashes disagree with projected lanes")

    expected_directories: set[str] = set()
    expected_files = {
        AGGREGATE_RECEIPT,
        "summary.json",
        "resolved-matrix.json",
        "runtime-store.json",
    }
    for lane in structural_lanes:
        lane_directories, lane_files = _profile_layout(lane, contract)
        expected_directories.update(lane_directories)
        expected_files.update(path for path in lane_files if path.startswith("profiles/"))
    directories, actual = _inventory(root)
    if directories != expected_directories or set(actual) != expected_files:
        raise FanInError("aggregate filesystem inventory is incomplete or contains unknown entries")

    receipt_bytes = _read_file(root, AGGREGATE_RECEIPT)
    try:
        receipt = secure_loads(
            receipt_bytes,
            label="aggregate receipt",
            max_bytes=MAX_MANIFEST_BYTES,
        )
    except SecureJsonError as exc:
        raise FanInError(str(exc)) from exc
    if not isinstance(receipt, dict):
        raise FanInError("aggregate receipt must be an object")
    if receipt_bytes != _json_bytes(receipt):
        raise FanInError("aggregate receipt JSON is not canonical")
    source_identity, observed_manifest_sha = _validate_receipt_shape(receipt, scoped=scoped)
    if source_identity.projection != projection:
        raise FanInError("aggregate receipt projection is stale")
    if externally_expected is not None and source_identity != externally_expected:
        raise FanInError("aggregate receipt source identity is stale")
    if scoped and (observed_manifest_sha != expected_manifest_sha
            or source_identity.commit != artifact_manifest["git_commit"]
            or source_identity.tree != artifact_manifest["git_tree"]):
        raise FanInError("aggregate source or manifest digest differs from the verified scoped bundle")
    lanes = expected_lanes(
        matrix,
        contract,
        projection,
        artifact_prefix=run_artifact_prefix(
            source_identity.commit, source_identity.run_attempt
        ),
        scope=scope, artifact_node=artifact_node,
    )

    all_results: list[dict[str, Any]] = []
    observed_hashes: dict[str, tuple[str, str]] = {}
    for lane in lanes:
        profile_files = {
            relative: _read_file(root, relative, allow_empty="/logs/" in f"/{relative}")
            for relative in sorted(expected_files)
            if relative.startswith("profiles/")
            and any(relative.startswith(f"profiles/{_profile_name(lane, scenario)}/") for scenario in lane.scenarios)
        }
        for scenario in lane.scenarios:
            relative = f"profiles/{_profile_name(lane, scenario)}/result.json"
            try:
                result = secure_loads(profile_files[relative], label=relative, max_bytes=MAX_JSON_BYTES)
            except SecureJsonError as exc:
                raise FanInError(str(exc)) from exc
            if not isinstance(result, dict):
                raise FanInError(f"aggregate result must be an object: {relative}")
            pair = (
                _digest(result.get("production_jar_sha256"), f"{lane.artifact_node} production sha256"),
                _digest(result.get("harness_jar_sha256"), f"{lane.artifact_node} harness sha256"),
            )
            if pair[0] == pair[1]:
                raise FanInError("aggregate production and harness identities are not separate")
            previous = observed_hashes.setdefault(lane.artifact_node, pair)
            if previous != pair or (
                expected_hashes is not None
                and expected_hashes[lane.artifact_node] != pair
            ):
                raise FanInError(f"aggregate jar hashes are stale/mixed for {lane.artifact_node}")
            _validate_result(
                result, lane=lane, scenario=scenario, contract=contract,
                hashes=pair, profile_files=profile_files,
            )
            all_results.append(result)

    summary = _object(
        _json(root, "summary.json", "aggregate summary"),
        "aggregate summary",
        {"schema_version", "contract_sha256", "results", "runtime_store"} | set(coverage),
    )
    if type(summary["schema_version"]) is not int or summary["schema_version"] != 1 or summary["contract_sha256"] != contract.sha256 or summary["results"] != all_results:
        raise FanInError("aggregate summary identity/results are stale")
    if coverage and _json_bytes({key: summary[key] for key in coverage}) != _json_bytes(coverage):
        raise FanInError("aggregate summary coverage is stale")
    aggregate_metrics = _runtime_metrics(summary["runtime_store"], "aggregate summary.runtime_store")
    runtime_store = _object(
        _json(root, "runtime-store.json", "aggregate runtime store"),
        "aggregate runtime store",
        {"schema_version", "metrics"},
    )
    if type(runtime_store["schema_version"]) is not int or runtime_store["schema_version"] != 1 or _runtime_metrics(
        runtime_store["metrics"], "aggregate runtime-store.metrics"
    ) != aggregate_metrics:
        raise FanInError("aggregate runtime telemetry disagrees")
    resolved = _object(
        _json(root, "resolved-matrix.json", "aggregate resolved matrix"),
        "aggregate resolved matrix",
        {"schema_version", "rows"} | set(coverage),
    )
    expected_rows = [
        {
            "artifact_node": result["artifact_node"],
            "minecraft": result["minecraft"],
            "loader": result["loader"],
            "scenario": result["scenario"],
            "production_jar_sha256": result["production_jar_sha256"],
        }
        for result in all_results
    ]
    if _json_bytes(resolved) != _json_bytes({"schema_version": 1, "rows": expected_rows, **coverage}):
        raise FanInError("aggregate resolved matrix is stale")

    non_self_records = [
        {
            "path": path,
            "size": actual[path][2],
            "sha256": _sha256(
                _read_file(root, path, allow_empty="/logs/" in f"/{path}")
            ),
        }
        for path in sorted(actual)
        if path != AGGREGATE_RECEIPT
    ]
    expected_receipt = {
        "schema_version": SCHEMA_VERSION,
        "kind": KIND,
        "provenance": _provenance(
            source_identity,
            matrix=matrix,
            matrix_path=matrix_path,
            contract=contract,
        ),
        "artifact_manifest": {
            "path": "build/release/artifacts.json",
            "schema_version": 3 if scoped else ARTIFACT_SCHEMA_VERSION,
            "sha256": observed_manifest_sha,
            "commit": source_identity.commit,
            "tree": source_identity.tree,
        },
        "lanes": [
            _manifest_lane(lane, observed_hashes[lane.artifact_node]) for lane in lanes
        ],
        "files": non_self_records,
        **coverage,
    }
    if _json_bytes(receipt) != _json_bytes(expected_receipt):
        raise FanInError("aggregate receipt identity or non-self inventory is stale")
    if _read_file(root, AGGREGATE_RECEIPT) != receipt_bytes:
        raise FanInError("aggregate receipt changed during validation")
    if _inventory(root) != (directories, actual):
        raise FanInError("aggregate changed during validation")
    if (read_secure_json(matrix_path, label="final aggregate matrix", max_bytes=MAX_MATRIX_BYTES)[1] != matrix_bytes
            or load_contract(contract_path).sha256 != contract.sha256):
        raise FanInError("aggregate matrix or contract changed during validation")
    return receipt


def _identity_from_args(args: argparse.Namespace) -> SourceIdentity:
    return SourceIdentity(
        repository=args.repository,
        source_branch=args.source_branch,
        commit=args.commit,
        tree=args.tree,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
        projection=args.projection,
    )


def _verified_manifest(
    *,
    matrix_path: Path,
    stage: Path,
    manifest_path: Path,
    repository: Path = REPO,
    scope: str | None = None,
    artifact_node: str | None = None,
) -> tuple[dict[str, Any], str]:
    manifest = verify_staged(
        repository=repository,
        matrix_path=matrix_path,
        manifest_path=manifest_path,
        stage=stage,
        scope=scope,
        artifact_node=artifact_node,
    )
    try:
        reread, raw = read_secure_json(
            manifest_path,
            label="verified artifact manifest",
            max_bytes=MAX_ARTIFACT_MANIFEST_BYTES,
        )
    except SecureJsonError as exc:
        raise FanInError(str(exc)) from exc
    if _json_bytes(reread) != _json_bytes(manifest):
        raise FanInError("verified artifact manifest changed after verification")
    return manifest, _sha256(raw)


def _common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--contract", type=Path, default=Path("e2e/scenario-contract.json"))
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("legacy", "full"), help="external schema3 bundle scope")
    selection.add_argument("--artifact-node", help="select exactly one configured schema2 lane")


def _source(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-branch", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tree", required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--run-attempt", type=int, required=True)
    parser.add_argument("--projection", choices=("pr-anchors", "scheduled-anchors"), required=True)


def _optional_source(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--repository")
    parser.add_argument("--source-branch")
    parser.add_argument("--commit")
    parser.add_argument("--tree")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--run-attempt", type=int)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create", help="validate isolated lanes and create a fresh aggregate")
    _common(create)
    _source(create)
    create.add_argument("--input", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--stage", type=Path, default=Path("build/release"))
    create.add_argument("--artifact-manifest", type=Path, default=Path("build/release/artifacts.json"))
    create.add_argument("--artifact-repository", type=Path,
                        help="local bundle checkout, distinct from the source owner/repository")
    validate = commands.add_parser(
        "validate", help="revalidate one immutable aggregate's exact internal inventory"
    )
    _common(validate)
    validate.add_argument("--input", type=Path, required=True)
    validate.add_argument("--projection", choices=("pr-anchors", "scheduled-anchors"), required=True)
    _optional_source(validate)
    validate.add_argument("--stage", type=Path, help="scoped bundle stage (default: build/release)")
    validate.add_argument("--artifact-manifest", type=Path,
                          help="scoped bundle manifest (default: build/release/artifacts.json)")
    validate.add_argument("--artifact-repository", type=Path,
                          help="local scoped bundle checkout, distinct from the source owner/repository")
    validate_lane_parser = commands.add_parser(
        "validate-lane", help="revalidate one sealed packaged-runtime lane before upload"
    )
    _common(validate_lane_parser)
    validate_lane_parser.add_argument("--input", type=Path, required=True)
    validate_lane_parser.add_argument(
        "--projection", choices=("pr-anchors", "scheduled-anchors"), required=True
    )
    validate_lane_parser.add_argument("--row-json", required=True)
    validate_lane_parser.add_argument("--repository", type=Path, required=True)
    validate_lane_parser.add_argument("--stage", type=Path, default=Path("build/release"))
    validate_lane_parser.add_argument(
        "--artifact-manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    args = parser.parse_args(argv)
    try:
        selection = {"scope": "lane" if args.artifact_node is not None else args.scope,
                     "artifact_node": args.artifact_node}
        bundle_inputs = {}
        if args.command != "validate" or selection["scope"] is not None:
            repository = (args.repository if args.command == "validate-lane" else args.artifact_repository or REPO).resolve()
            defaults = {"stage": Path("build/release"), "artifact_manifest": Path("build/release/artifacts.json")}
            for name in ("matrix", "contract", "stage", "artifact_manifest"):
                path = getattr(args, name) or defaults[name]
                setattr(args, name, path if path.is_absolute() else repository / path)
            bundle_inputs = {"repository": repository, **selection}
        elif any(value is not None for value in (args.stage, args.artifact_manifest, args.artifact_repository)):
            raise FanInError("explicit aggregate bundle options require an external scope")
        if args.command == "create":
            identity = _identity_from_args(args)
            artifact_manifest, artifact_manifest_sha = _verified_manifest(
                matrix_path=args.matrix,
                stage=args.stage,
                manifest_path=args.artifact_manifest,
                **bundle_inputs,
            )
            result = create_aggregate(
                input_root=args.input,
                output=args.output,
                matrix_path=args.matrix,
                contract_path=args.contract,
                identity=identity,
                artifact_manifest=artifact_manifest,
                artifact_manifest_sha256=artifact_manifest_sha,
                **selection,
            )
        elif args.command == "validate":
            artifact_manifest, artifact_manifest_sha = (None, None)
            if bundle_inputs:
                artifact_manifest, artifact_manifest_sha = _verified_manifest(
                    matrix_path=args.matrix, stage=args.stage, manifest_path=args.artifact_manifest,
                    **bundle_inputs,
                )
            result = validate_aggregate(
                root=args.input,
                matrix_path=args.matrix,
                contract_path=args.contract,
                projection=args.projection,
                expected_repository=args.repository,
                expected_source_branch=args.source_branch,
                expected_commit=args.commit,
                expected_tree=args.tree,
                expected_run_id=args.run_id,
                expected_run_attempt=args.run_attempt,
                artifact_manifest=artifact_manifest,
                artifact_manifest_sha256=artifact_manifest_sha,
                **selection,
            )
        else:
            row = secure_loads(
                args.row_json.encode("utf-8"),
                label="runtime row",
                max_bytes=MAX_JSON_BYTES,
            )
            if not isinstance(row, dict):
                raise FanInError("runtime row must be an object")
            artifact_manifest, _artifact_manifest_sha = _verified_manifest(
                matrix_path=args.matrix,
                stage=args.stage,
                manifest_path=args.artifact_manifest,
                **bundle_inputs,
            )
            result = validate_lane(
                root=args.input,
                matrix_path=args.matrix,
                contract_path=args.contract,
                projection=args.projection,
                row=row,
                artifact_manifest=artifact_manifest,
                **selection,
            )
    except (
        ArtifactError,
        FanInError,
        MatrixError,
        OSError,
        RuntimeFailure,
        ScenarioContractError,
        SecureJsonError,
    ) as exc:
        print(f"packaged E2E fan-in error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
