#!/usr/bin/env python3
"""Validate authenticated packaged-E2E artifacts for semantic visual review.

The GitHub API authentication which produces :class:`SourceExpectation` is outside this
module.  This secretless boundary deliberately requires every API-derived field again,
verifies the downloaded artifact ZIP by digest, extracts it without following archive
paths, and accepts frames only after the branch matrix and scenario contract agree.
"""

from __future__ import annotations

import hashlib
import io
import math
import os
import re
import shutil
import stat
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from PIL import Image, ImageChops, ImageStat, UnidentifiedImageError

from e2e.scenario_contract import (
    Capture,
    OpaqueStarsProbe,
    RequiredGuiTextProbe,
    ScenarioContract,
    ScenarioContractError,
    load_contract,
)
from scripts.lib.secure_json import (
    SecureJsonError,
    canonical_json,
    read as read_secure_json,
    require_object,
)
from scripts.release.matrix import (
    MAX_MATRIX_BYTES, MatrixDocument, MatrixError, normalize_matrix_inventory, valid_branch_name,
)


SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,159}$")
SAFE_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
SAFE_WORKFLOW = re.compile(r"^\.github/workflows/[A-Za-z0-9][A-Za-z0-9._-]*\.ya?ml$")
ALLOWED_EVENTS = frozenset(
    {"merge_group", "pull_request_target", "push", "schedule", "workflow_dispatch"}
)
RESULT_KEYS = frozenset(
    {
        "schema_version",
        "artifact_node",
        "minecraft",
        "loader",
        "scenario",
        "contract_sha256",
        "production_jar_sha256",
        "harness_jar_sha256",
        "port",
        "status",
        "profile",
        "installed_blockpops",
        "reports",
        "elapsed_s",
        "error",
    }
)
REPORT_KEYS = frozenset(
    {
        "schema_version",
        "minecraft",
        "role",
        "scenario",
        "contract_sha256",
        "status",
        "steps",
        "pixel_validation",
    }
)
RAW_REPORT_KEYS = REPORT_KEYS - {"pixel_validation"}
STEP_KEYS = frozenset({"id", "status", "message", "capture_id", "screenshot"})
PIXEL_VALIDATION_KEYS = frozenset({"screenshots", "comparisons"})
SCREENSHOT_METRIC_KEYS = frozenset(
    {
        "width",
        "height",
        "file_sha256",
        "pixel_sha256",
        "luma_entropy",
        "meaningful_colors",
        "dark_fraction",
        "light_fraction",
    }
)
COMPARISON_KEYS = frozenset(
    {"changed_fraction", "rms_difference", "required_changed_fraction"}
)
COMPARISON_REGION_KEYS = COMPARISON_KEYS | {"region"}
ATTESTATION_KEYS = frozenset(
    {
        "schema_version",
        "repository",
        "source_head_repository",
        "source_head_branch",
        "base_branch",
        "source_head_commit",
        "tested_commit",
        "tested_tree",
        "workflow_path",
        "workflow_sha256",
        "job_graph_sha256",
        "event",
        "run_id",
        "run_attempt",
        "status",
        "conclusion",
        "artifact_id",
        "artifact_name",
        "artifact_sha256",
        "matrix_sha256",
        "contract_sha256",
        "artifact_nodes",
        "scenarios",
    }
)

MAX_ATTESTATION_BYTES = 128 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_FILES = 1024
MAX_ARCHIVE_MEMBER_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 300 * 1024 * 1024
MAX_EVIDENCE_PROFILES = 256
MAX_EVIDENCE_FILES = 768
MAX_EVIDENCE_TOTAL_BYTES = 256 * 1024 * 1024
MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_MESSAGE_CHARS = 1024


class VisualEvidenceError(ValueError):
    """Untrusted visual evidence failed a closed validation boundary."""


@dataclass(frozen=True)
class SourceExpectation:
    """Fields copied from an authenticated GitHub run/artifact API response.

    Callers must not construct this object from files inside the candidate artifact.
    In particular, ``artifact_sha256`` is the normalized digest returned for the
    downloaded artifact and ``job_graph_sha256`` binds the canonical proof derived from
    authenticated jobs API responses. ``tested_commit``/``tested_tree`` identify the exact base
    repository bytes executed by Actions; ``source_head_*`` separately identify the current PR
    head (or the same protected branch commit for non-PR runs).
    """

    repository: str
    source_head_repository: str
    source_head_branch: str
    base_branch: str
    source_head_commit: str
    tested_commit: str
    tested_tree: str
    workflow_path: str
    workflow_sha256: str
    job_graph_sha256: str
    event: str
    run_id: int
    run_attempt: int
    artifact_id: int
    artifact_name: str
    artifact_sha256: str


@dataclass(frozen=True)
class VisualFrame:
    artifact_node: str
    minecraft: str
    loader: str
    scenario: str
    role: str
    step: str
    capture_id: str
    title: str
    expectation: str
    review_tier: str
    width: int
    height: int
    source_file_sha256: str
    pixel_sha256: str
    canonical_file_sha256: str
    canonical_png: bytes
    source_artifact_id: int

    @property
    def label(self) -> str:
        return f"{self.artifact_node}/{self.scenario}/{self.role}/{self.step}"


@dataclass(frozen=True)
class EvidenceBundle:
    provenance: dict[str, Any]
    matrix: dict[str, Any]
    matrix_sha256: str
    contract: ScenarioContract
    frames: tuple[VisualFrame, ...]


def _fail(message: str) -> None:
    raise VisualEvidenceError(message)


def _text(value: Any, label: str, *, maximum: int = 512) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        _fail(f"{label} must be a trimmed printable string of at most {maximum} characters")
    return value


def _positive_integer(value: Any, label: str, *, maximum: int = 2**63 - 1) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= maximum:
        _fail(f"{label} must be a positive integer no greater than {maximum}")
    return value


def _finite_number(value: Any, label: str, *, minimum: float, maximum: float) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or not minimum <= value <= maximum
    ):
        _fail(f"{label} must be finite and in [{minimum}, {maximum}]")
    return float(value)


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _read_regular_bytes(path: Path, *, label: str, maximum: int) -> bytes:
    descriptor = -1
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode):
            _fail(f"{label} must be a regular non-symlink file: {path}")
        if not 1 <= before.st_size <= maximum:
            _fail(f"{label} must contain between 1 and {maximum} bytes: {path}")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino, opened.st_size)
            != (before.st_dev, before.st_ino, before.st_size)
        ):
            _fail(f"{label} changed while opening: {path}")
        chunks: list[bytes] = []
        remaining = maximum + 1
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            (after.st_dev, after.st_ino, after.st_size)
            != (opened.st_dev, opened.st_ino, opened.st_size)
            or len(payload) != opened.st_size
        ):
            _fail(f"{label} changed while reading: {path}")
        return payload
    except VisualEvidenceError:
        raise
    except OSError as exc:
        raise VisualEvidenceError(f"cannot read {label} {path}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _require_real_directory(path: Path, label: str) -> Path:
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise VisualEvidenceError(f"cannot inspect {label} {path}: {exc}") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail(f"{label} must be a real directory: {path}")
    return path.resolve()


def _reject_component_symlinks(path: Path, boundary: Path, label: str) -> None:
    absolute_boundary = boundary.absolute()
    absolute_path = path.absolute()
    try:
        relative = absolute_path.relative_to(absolute_boundary)
    except ValueError as exc:
        raise VisualEvidenceError(f"{label} escapes {absolute_boundary}: {path}") from exc
    current = absolute_boundary
    for part in ((), *[(item,) for item in relative.parts]):
        if part:
            current /= part[0]
        try:
            metadata = current.lstat()
        except OSError as exc:
            raise VisualEvidenceError(f"cannot inspect {label} component {current}: {exc}") from exc
        if stat.S_ISLNK(metadata.st_mode):
            _fail(f"{label} contains a symbolic link: {current}")


def _validated_string_list(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or len(value) > 128:
        _fail(f"{label} must be a non-empty array with at most 128 entries")
    normalized: list[str] = []
    for index, item in enumerate(value):
        text = _text(item, f"{label}[{index}]", maximum=160)
        if SAFE_ID.fullmatch(text) is None:
            _fail(f"{label}[{index}] has an unsafe identifier")
        normalized.append(text)
    if len(set(normalized)) != len(normalized) or normalized != sorted(normalized):
        _fail(f"{label} must be sorted and duplicate-free")
    return tuple(normalized)


def validate_attestation(
    value: Any,
    expectation: SourceExpectation,
    *,
    expected_matrix_sha256: str,
    expected_contract_sha256: str,
) -> dict[str, Any]:
    """Validate exact API-authenticated provenance; self-asserted values are insufficient."""

    try:
        record = require_object(value, label="visual source attestation", required=ATTESTATION_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if record["schema_version"] != 1:
        _fail("visual source attestation schema_version must be 1")
    expected_fields = {
        field: getattr(expectation, field)
        for field in (
            "repository",
            "source_head_repository",
            "source_head_branch",
            "base_branch",
            "source_head_commit",
            "tested_commit",
            "tested_tree",
            "workflow_path",
            "workflow_sha256",
            "job_graph_sha256",
            "event",
            "run_id",
            "run_attempt",
            "artifact_id",
            "artifact_name",
            "artifact_sha256",
        )
    }
    mismatches = [
        field for field, expected in expected_fields.items() if record[field] != expected
    ]
    if mismatches:
        _fail(f"visual source provenance disagrees with authenticated API fields: {mismatches}")
    for field in ("repository", "source_head_repository"):
        if SAFE_REPOSITORY.fullmatch(_text(record[field], field, maximum=201)) is None:
            _fail(f"visual source {field} is unsafe")
    for field in ("source_head_branch", "base_branch"):
        if not valid_branch_name(record[field]):
            _fail(f"visual source {field} is not a safe Git branch")
    for field in ("source_head_commit", "tested_commit", "tested_tree"):
        if not isinstance(record[field], str) or SHA1.fullmatch(record[field]) is None:
            _fail(f"visual source {field} must be a lowercase GitHub SHA-1")
    if record["event"] == "pull_request_target":
        if record["tested_commit"] == record["source_head_commit"]:
            _fail("pull_request_target visual source must distinguish tested merge and source head")
    elif (
        record["source_head_repository"] != record["repository"]
        or record["tested_commit"] != record["source_head_commit"]
    ):
        _fail("non-PR visual source tested/source identity is inconsistent")
    if SAFE_WORKFLOW.fullmatch(str(record["workflow_path"])) is None:
        _fail("visual source workflow_path must identify a repository Actions workflow")
    for field in (
        "workflow_sha256",
        "job_graph_sha256",
        "artifact_sha256",
        "matrix_sha256",
        "contract_sha256",
    ):
        if not isinstance(record[field], str) or SHA256.fullmatch(record[field]) is None:
            _fail(f"visual source {field} must be a lowercase SHA-256")
    if record["matrix_sha256"] != expected_matrix_sha256:
        _fail("visual source matrix hash is stale or mixed")
    if record["contract_sha256"] != expected_contract_sha256:
        _fail("visual source scenario contract hash is stale or mixed")
    if record["event"] not in ALLOWED_EVENTS:
        _fail("visual source event is not an approved Actions trigger")
    for field in ("run_id", "run_attempt", "artifact_id"):
        _positive_integer(record[field], f"visual source {field}")
    if SAFE_ID.fullmatch(_text(record["artifact_name"], "artifact_name", maximum=160)) is None:
        _fail("visual source artifact_name is unsafe")
    if record["status"] != "completed" or record["conclusion"] != "success":
        _fail("visual source run must be completed successfully")
    _validated_string_list(record["artifact_nodes"], "artifact_nodes")
    _validated_string_list(record["scenarios"], "scenarios")
    return dict(record)


def read_attestation(
    path: Path,
    expectation: SourceExpectation,
    *,
    expected_matrix_sha256: str,
    expected_contract_sha256: str,
) -> dict[str, Any]:
    try:
        value, _ = read_secure_json(
            path, label="visual source attestation", max_bytes=MAX_ATTESTATION_BYTES
        )
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    return validate_attestation(
        value,
        expectation,
        expected_matrix_sha256=expected_matrix_sha256,
        expected_contract_sha256=expected_contract_sha256,
    )


def _safe_archive_name(raw: str) -> PurePosixPath:
    if (
        not raw
        or len(raw.encode("utf-8")) > 512
        or "\\" in raw
        or "\x00" in raw
        or raw.startswith("/")
        or ":" in raw
    ):
        _fail(f"artifact archive contains an unsafe path {raw!r}")
    trimmed = raw[:-1] if raw.endswith("/") else raw
    path = PurePosixPath(trimmed)
    if (
        not trimmed
        or path.is_absolute()
        or trimmed != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        _fail(f"artifact archive contains a non-canonical path {raw!r}")
    return path


def extract_authenticated_artifact(
    archive: Path,
    destination: Path,
    *,
    expected_sha256: str,
) -> Path:
    """Digest-check and safely extract one fresh GitHub artifact ZIP."""

    if SHA256.fullmatch(expected_sha256) is None:
        _fail("expected artifact digest must be a lowercase SHA-256")
    payload = _read_regular_bytes(archive, label="visual evidence artifact", maximum=MAX_ARCHIVE_BYTES)
    if _sha256_bytes(payload) != expected_sha256:
        _fail("downloaded visual evidence artifact digest disagrees with authenticated API")
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        _fail(f"artifact extraction destination must be fresh: {destination}")
    parent = _require_real_directory(destination.parent, "artifact extraction parent")
    staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}.extracting-", dir=parent))
    files = 0
    expanded = 0
    observed: set[str] = set()
    observed_components: dict[str, str] = {}
    file_names: set[str] = set()
    try:
        try:
            source = zipfile.ZipFile(io.BytesIO(payload))
        except (OSError, zipfile.BadZipFile) as exc:
            raise VisualEvidenceError(f"visual evidence artifact is not a valid ZIP: {exc}") from exc
        with source:
            if source.comment:
                _fail("visual evidence artifact ZIP comments are forbidden")
            for entry in source.infolist():
                path = _safe_archive_name(entry.filename)
                normalized = path.as_posix()
                collision = normalized.casefold()
                if collision in observed:
                    _fail(f"visual evidence artifact repeats path {normalized!r}")
                observed.add(collision)
                cumulative: list[str] = []
                for component in path.parts:
                    cumulative.append(component)
                    component_path = "/".join(cumulative)
                    component_collision = component_path.casefold()
                    previous_component = observed_components.setdefault(
                        component_collision, component_path
                    )
                    if previous_component != component_path:
                        _fail(
                            "visual evidence artifact contains a case-colliding path: "
                            f"{previous_component!r} and {component_path!r}"
                        )
                if entry.comment or entry.flag_bits & 0x1:
                    _fail(f"visual evidence artifact entry is commented or encrypted: {normalized}")
                mode = (entry.external_attr >> 16) & 0xFFFF
                file_type = stat.S_IFMT(mode) if mode else 0
                is_directory = entry.is_dir()
                if file_type == stat.S_IFLNK or (
                    file_type not in {0, stat.S_IFREG, stat.S_IFDIR}
                ):
                    _fail(f"visual evidence artifact contains a special file: {normalized}")
                if is_directory != (file_type == stat.S_IFDIR) and file_type not in {0, stat.S_IFREG}:
                    _fail(f"visual evidence artifact directory metadata is inconsistent: {normalized}")
                if entry.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
                    _fail(f"visual evidence artifact uses an unsupported compression method: {normalized}")
                for parent_path in path.parents:
                    if parent_path == PurePosixPath("."):
                        continue
                    if parent_path.as_posix().casefold() in file_names:
                        _fail(f"visual evidence artifact nests below a file: {normalized}")
                output = staging.joinpath(*path.parts)
                if is_directory:
                    output.mkdir(parents=True, exist_ok=True)
                    continue
                files += 1
                expanded += entry.file_size
                if (
                    files > MAX_ARCHIVE_FILES
                    or entry.file_size <= 0
                    or entry.file_size > MAX_ARCHIVE_MEMBER_BYTES
                    or expanded > MAX_ARCHIVE_EXPANDED_BYTES
                ):
                    _fail("visual evidence artifact exceeds its file or expanded-byte limits")
                file_names.add(collision)
                output.parent.mkdir(parents=True, exist_ok=True)
                copied = 0
                try:
                    with source.open(entry, "r") as input_stream, output.open("xb") as output_stream:
                        for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                            copied += len(chunk)
                            if copied > entry.file_size:
                                _fail(f"visual evidence artifact entry grew while extracting: {normalized}")
                            output_stream.write(chunk)
                except (OSError, EOFError, RuntimeError, zipfile.BadZipFile) as exc:
                    raise VisualEvidenceError(
                        f"cannot extract visual evidence artifact entry {normalized}: {exc}"
                    ) from exc
                if copied != entry.file_size:
                    _fail(f"visual evidence artifact entry size changed: {normalized}")
                os.chmod(output, 0o600)
        if files == 0:
            _fail("visual evidence artifact contains no files")
        staging.rename(destination)
        return destination
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def canonicalize_png(
    path: Path,
    *,
    expected_size: tuple[int, int],
) -> tuple[int, int, str, str, str, bytes, dict[str, Any]]:
    """Read once, fully decode, and encode a deterministic metadata-free RGB PNG."""

    payload = _read_regular_bytes(path, label="visual screenshot", maximum=MAX_SCREENSHOT_BYTES)
    try:
        Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != "PNG" or getattr(image, "n_frames", 1) != 1:
                _fail(f"visual screenshot must be one static PNG: {path}")
            width, height = image.size
            if (
                width <= 0
                or height <= 0
                or width * height > MAX_IMAGE_PIXELS
                or (width, height) != expected_size
            ):
                _fail(
                    f"visual screenshot dimensions must be {expected_size[0]}x{expected_size[1]}: "
                    f"{path} ({width}x{height})"
                )
            image.load()
            rgb = image.convert("RGB")
            pixels = rgb.tobytes()
            sample = rgb.resize((160, 90), Image.Resampling.BILINEAR)
            luma = sample.convert("L")
            palette = sample.quantize(colors=32).getcolors() or []
            histogram = luma.histogram()
            sample_pixels = sample.width * sample.height
            metrics = {
                "width": width,
                "height": height,
                "file_sha256": _sha256_bytes(payload),
                "pixel_sha256": _sha256_bytes(pixels),
                "luma_entropy": round(float(luma.entropy()), 3),
                "meaningful_colors": sum(
                    count >= max(2, sample_pixels // 1000) for count, _ in palette
                ),
                "dark_fraction": round(sum(histogram[:8]) / sample_pixels, 4),
                "light_fraction": round(sum(histogram[248:]) / sample_pixels, 4),
                "channel_stddev": max(float(item) for item in ImageStat.Stat(sample).stddev),
            }
        encoded = io.BytesIO()
        Image.frombytes("RGB", (width, height), pixels).save(
            encoded, format="PNG", optimize=False, compress_level=9
        )
        canonical = encoded.getvalue()
        if not canonical or len(canonical) > MAX_SCREENSHOT_BYTES:
            _fail(f"normalized visual screenshot exceeds its byte limit: {path}")
        with Image.open(io.BytesIO(canonical)) as check:
            check.load()
            if check.format != "PNG" or check.mode != "RGB" or check.size != (width, height):
                _fail(f"normalized visual screenshot identity changed: {path}")
            if _sha256_bytes(check.tobytes()) != metrics["pixel_sha256"]:
                _fail(f"normalized visual screenshot pixels changed: {path}")
        return (
            width,
            height,
            metrics["file_sha256"],
            metrics["pixel_sha256"],
            _sha256_bytes(canonical),
            canonical,
            metrics,
        )
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError, Image.DecompressionBombError) as exc:
        raise VisualEvidenceError(f"cannot decode visual screenshot {path}: {exc}") from exc


def _validate_metrics(reported: Any, actual: dict[str, Any], label: str) -> None:
    try:
        record = require_object(reported, label=label, required=SCREENSHOT_METRIC_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    for field in ("width", "height", "meaningful_colors"):
        if isinstance(record[field], bool) or not isinstance(record[field], int):
            _fail(f"{label}.{field} must be an integer")
    if not 0 <= record["meaningful_colors"] <= 32:
        _fail(f"{label}.meaningful_colors is outside [0, 32]")
    for field in ("file_sha256", "pixel_sha256"):
        if not isinstance(record[field], str) or SHA256.fullmatch(record[field]) is None:
            _fail(f"{label}.{field} must be a lowercase SHA-256")
    _finite_number(record["luma_entropy"], f"{label}.luma_entropy", minimum=0, maximum=8)
    _finite_number(record["dark_fraction"], f"{label}.dark_fraction", minimum=0, maximum=1)
    _finite_number(record["light_fraction"], f"{label}.light_fraction", minimum=0, maximum=1)
    for field in SCREENSHOT_METRIC_KEYS:
        if record[field] != actual[field]:
            _fail(f"{label}.{field} disagrees with decoded screenshot")
    if (
        actual["luma_entropy"] < 0.75
        or actual["channel_stddev"] < 2.0
        or actual["meaningful_colors"] < 4
        or actual["dark_fraction"] > 0.98
        or actual["light_fraction"] > 0.995
    ):
        _fail(f"{label} describes an effectively blank screenshot")


def validate_contract_probes(canonical_png: bytes, capture: Capture, label: str) -> None:
    """Independently re-run every deterministic visual probe owned by a capture."""

    try:
        with Image.open(io.BytesIO(canonical_png)) as image:
            image.load()
            rgb = image.convert("RGB")
            for probe in capture.probes:
                if isinstance(probe, OpaqueStarsProbe):
                    left, top, right, bottom = probe.region
                    box = (
                        int(left * rgb.width),
                        int(top * rgb.height),
                        int(right * rgb.width),
                        int(bottom * rgb.height),
                    )
                    if box[0] >= box[2] or box[1] >= box[3]:
                        _fail(f"{label} opaque-background probe is empty")
                    luma = rgb.crop(box).convert("L")
                    histogram = luma.histogram()
                    pixels = luma.width * luma.height
                    mean_luma = sum(
                        value * count for value, count in enumerate(histogram)
                    ) / pixels
                    bright_fraction = sum(histogram[probe.bright_luma :]) / pixels
                    if (
                        mean_luma > probe.maximum_mean_luma
                        or bright_fraction > probe.maximum_bright_fraction
                    ):
                        _fail(
                            f"{label} fails {probe.kind}: mean_luma={mean_luma:.3f}, "
                            f"bright_fraction={bright_fraction:.6f}"
                        )
                elif isinstance(probe, RequiredGuiTextProbe):
                    left, top, right, bottom = probe.box
                    if (
                        left < 0
                        or top < 0
                        or right > rgb.width
                        or bottom > rgb.height
                        or left >= right
                        or top >= bottom
                    ):
                        _fail(f"{label} text probe {probe.label!r} is outside the image")
                    histogram = rgb.crop(probe.box).convert("L").histogram()
                    matching = sum(histogram[probe.minimum_luma_exclusive + 1 :])
                    if matching < probe.minimum_pixels:
                        _fail(
                            f"{label} fails {probe.kind} {probe.label!r}: "
                            f"bright_pixels={matching}, required={probe.minimum_pixels}"
                        )
                else:  # pragma: no cover - scenario_contract owns this closed union
                    _fail(f"{label} contains an unknown deterministic visual probe")
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise VisualEvidenceError(f"cannot apply deterministic probes to {label}: {exc}") from exc


def _comparison(
    first: VisualFrame,
    second: VisualFrame,
    region: tuple[float, float, float, float] | None,
) -> tuple[float, float]:
    try:
        with Image.open(io.BytesIO(first.canonical_png)) as first_image, Image.open(
            io.BytesIO(second.canonical_png)
        ) as second_image:
            if first_image.size != second_image.size:
                _fail("contracted screenshots have incompatible dimensions")
            first_rgb = first_image.convert("RGB")
            second_rgb = second_image.convert("RGB")
            if region is not None:
                width, height = first_rgb.size
                box = (
                    int(region[0] * width),
                    int(region[1] * height),
                    int(region[2] * width),
                    int(region[3] * height),
                )
                if box[0] >= box[2] or box[1] >= box[3]:
                    _fail("contracted comparison region is empty")
                first_rgb = first_rgb.crop(box)
                second_rgb = second_rgb.crop(box)
            difference = ImageChops.difference(first_rgb, second_rgb).convert("L")
            histogram = difference.histogram()
            count = difference.width * difference.height
            changed = sum(histogram[8:]) / count
            rms = (sum(value * value * amount for value, amount in enumerate(histogram)) / count) ** 0.5
            return round(changed, 7), round(rms, 3)
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise VisualEvidenceError(f"cannot recompute screenshot comparison: {exc}") from exc


def _safe_profile_name(value: str) -> bool:
    return SAFE_ID.fullmatch(value) is not None and value not in {".", ".."}


def _inventory_profile(profile: Path, roles: tuple[str, ...], screenshots: set[Path]) -> None:
    allowed_directories = {profile, profile / "logs"}
    allowed_files = {profile / "result.json"}
    for role in roles:
        allowed_directories.update(
            {profile / role, profile / role / "e2e-report", profile / role / "screenshots"}
        )
        allowed_files.update(
            {
                profile / role / "e2e-report" / "report.json",
                profile / role / "e2e-report" / "done.marker",
            }
        )
    allowed_files.update(screenshots)
    total = 0
    files = 0
    for path in sorted(profile.rglob("*")):
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            _fail(f"packaged profile contains a symbolic link: {path}")
        if stat.S_ISDIR(metadata.st_mode):
            if path not in allowed_directories:
                _fail(f"packaged profile contains an unexpected directory: {path}")
            continue
        if not stat.S_ISREG(metadata.st_mode):
            _fail(f"packaged profile contains a special file: {path}")
        files += 1
        total += metadata.st_size
        if path.parent == profile / "logs" and path.suffix == ".log":
            if metadata.st_size > MAX_LOG_BYTES:
                _fail(f"packaged evidence log exceeds its byte limit: {path}")
        elif path not in allowed_files:
            _fail(f"packaged profile contains an unexpected file: {path}")
    if files > MAX_EVIDENCE_FILES or total > MAX_EVIDENCE_TOTAL_BYTES:
        _fail("packaged profile exceeds its bounded evidence inventory")


def _validate_raw_report(profile: Path, role: str, report: dict[str, Any]) -> None:
    path = profile / role / "e2e-report" / "report.json"
    try:
        raw, _ = read_secure_json(path, label=f"{role} raw report", max_bytes=MAX_JSON_BYTES)
        raw_record = require_object(raw, label=f"{role} raw report", required=RAW_REPORT_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    expected = {key: report[key] for key in RAW_REPORT_KEYS}
    if raw_record != expected:
        _fail(f"{role} exported raw report disagrees with packaged result")
    marker = _read_regular_bytes(
        profile / role / "e2e-report" / "done.marker",
        label=f"{role} done marker",
        maximum=16,
    )
    try:
        marker_text = marker.decode("utf-8", errors="strict").strip()
    except UnicodeDecodeError as exc:
        raise VisualEvidenceError(f"{role} done marker is not UTF-8") from exc
    if marker_text != "pass":
        _fail(f"{role} done marker is not pass")


def _validate_result(
    result: Any,
    *,
    result_path: Path,
    root: Path,
    rows: dict[str, dict[str, Any]],
    contract: ScenarioContract,
    source_artifact_id: int,
) -> tuple[tuple[str, str], tuple[VisualFrame, ...]]:
    try:
        record = require_object(result, label="packaged E2E result", required=RESULT_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if record["schema_version"] != 1 or record["status"] != "pass" or record["error"] is not None:
        _fail(f"visual evidence cannot include a non-passing result: {result_path}")
    node = _text(record["artifact_node"], "result.artifact_node", maximum=160)
    scenario_id = _text(record["scenario"], "result.scenario", maximum=160)
    row = rows.get(node)
    if row is None:
        _fail(f"packaged result uses an artifact node outside its attested matrix: {node}")
    if record["minecraft"] != row["minecraft"] or record["loader"] != row["loader"]:
        _fail(f"packaged result lane identity disagrees with matrix row {node}")
    if record["contract_sha256"] != contract.sha256:
        _fail("packaged result scenario contract hash is stale or mixed")
    try:
        scenario = contract.scenario(scenario_id)
    except ScenarioContractError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    profile = result_path.parent
    expected_profile = profile.relative_to(root).as_posix()
    if record["profile"] != expected_profile or not _safe_profile_name(profile.name):
        _fail(f"packaged result profile identity is unsafe or stale: {record['profile']!r}")
    for field in ("production_jar_sha256", "harness_jar_sha256"):
        if not isinstance(record[field], str) or SHA256.fullmatch(record[field]) is None:
            _fail(f"packaged result {field} is invalid")
    if record["production_jar_sha256"] == record["harness_jar_sha256"]:
        _fail("production and E2E harness jars must have distinct identities")
    _positive_integer(record["port"], "result.port", maximum=65535)
    _finite_number(record["elapsed_s"], "result.elapsed_s", minimum=0, maximum=86400)
    roles = tuple(role.role for role in scenario.roles)
    expected_install_roots = {"server", *roles}
    installed = record["installed_blockpops"]
    if not isinstance(installed, list) or len(installed) != len(expected_install_roots):
        _fail("packaged result must prove the production jar on server and every client")
    observed_roots: set[str] = set()
    observed_paths: set[str] = set()
    for index, item in enumerate(installed):
        try:
            entry = require_object(
                item,
                label=f"installed_blockpops[{index}]",
                required={"path", "sha256"},
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        raw_path = _text(entry["path"], f"installed_blockpops[{index}].path")
        path = PurePosixPath(raw_path)
        if (
            path.is_absolute()
            or raw_path != path.as_posix()
            or len(path.parts) != 3
            or path.parts[0] not in expected_install_roots
            or path.parts[1] != "mods"
            or not path.name.lower().endswith(".jar")
            or any(part in {"", ".", ".."} for part in path.parts)
            or raw_path.casefold() in observed_paths
            or path.parts[0] in observed_roots
        ):
            _fail(f"installed_blockpops[{index}] path is unsafe or duplicated")
        if entry["sha256"] != record["production_jar_sha256"]:
            _fail(f"installed_blockpops[{index}] does not identify the production jar")
        observed_paths.add(raw_path.casefold())
        observed_roots.add(path.parts[0])
    if observed_roots != expected_install_roots:
        _fail("installed production jar roots are incomplete")
    reports = record["reports"]
    if not isinstance(reports, dict) or set(reports) != set(roles):
        _fail("packaged result report roles disagree with the scenario contract")
    frames: list[VisualFrame] = []
    screenshots: set[Path] = set()
    frames_by_role_step: dict[tuple[str, str], VisualFrame] = {}
    for role_contract in scenario.roles:
        role = role_contract.role
        try:
            report = require_object(
                reports[role], label=f"report {role}", required=REPORT_KEYS
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        if (
            report["schema_version"] != 1
            or report["minecraft"] != row["minecraft"]
            or report["role"] != role
            or report["scenario"] != scenario_id
            or report["contract_sha256"] != contract.sha256
            or report["status"] != "pass"
        ):
            _fail(f"report identity/status mismatch for {node}/{scenario_id}/{role}")
        steps = report["steps"]
        if not isinstance(steps, list) or len(steps) != len(role_contract.steps):
            _fail(f"report steps disagree with contract for {node}/{scenario_id}/{role}")
        try:
            pixel_validation = require_object(
                report["pixel_validation"],
                label=f"pixel_validation {role}",
                required=PIXEL_VALIDATION_KEYS,
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        screenshot_metrics = pixel_validation["screenshots"]
        comparison_metrics = pixel_validation["comparisons"]
        expected_capture_steps = {
            step.id for step in role_contract.steps if step.capture is not None
        }
        if not isinstance(screenshot_metrics, dict) or set(screenshot_metrics) != expected_capture_steps:
            _fail(f"screenshot metric inventory disagrees with contract for {role}")
        if not isinstance(comparison_metrics, dict):
            _fail(f"comparison metric inventory must be an object for {role}")
        for index, (step_contract, raw_step) in enumerate(
            zip(role_contract.steps, steps, strict=True)
        ):
            try:
                step = require_object(
                    raw_step,
                    label=f"report {role}.steps[{index}]",
                    required=STEP_KEYS,
                )
            except SecureJsonError as exc:
                raise VisualEvidenceError(str(exc)) from exc
            capture = step_contract.capture
            expected_capture_id = capture.capture_id if capture is not None else None
            expected_screenshot = f"{expected_capture_id}.png" if capture is not None else None
            if (
                step["id"] != step_contract.id
                or step["status"] != "pass"
                or step["capture_id"] != expected_capture_id
                or step["screenshot"] != expected_screenshot
                or not isinstance(step["message"], str)
                or len(step["message"]) > MAX_MESSAGE_CHARS
                or "\x00" in step["message"]
            ):
                _fail(f"report step disagrees with contract for {node}/{role}/{step_contract.id}")
            if capture is None:
                continue
            screenshot = profile / role / "screenshots" / expected_screenshot
            _reject_component_symlinks(screenshot, root, "visual screenshot")
            (
                width,
                height,
                source_digest,
                pixel_digest,
                canonical_digest,
                canonical_png,
                actual_metrics,
            ) = canonicalize_png(screenshot, expected_size=contract.gui_text_reference_size)
            _validate_metrics(
                screenshot_metrics[step_contract.id],
                actual_metrics,
                f"pixel metrics {node}/{role}/{step_contract.id}",
            )
            validate_contract_probes(
                canonical_png,
                capture,
                f"{node}/{scenario_id}/{role}/{step_contract.id}",
            )
            frame = VisualFrame(
                artifact_node=node,
                minecraft=row["minecraft"],
                loader=row["loader"],
                scenario=scenario_id,
                role=role,
                step=step_contract.id,
                capture_id=capture.capture_id,
                title=capture.title,
                expectation=capture.expectation,
                review_tier=capture.review_tier,
                width=width,
                height=height,
                source_file_sha256=source_digest,
                pixel_sha256=pixel_digest,
                canonical_file_sha256=canonical_digest,
                canonical_png=canonical_png,
                source_artifact_id=source_artifact_id,
            )
            frames.append(frame)
            frames_by_role_step[(role, step_contract.id)] = frame
            screenshots.add(screenshot)
        expected_comparison_ids = {
            f"{comparison.first_step}->{comparison.second_step}"
            for comparison in role_contract.comparisons
        }
        if set(comparison_metrics) != expected_comparison_ids:
            _fail(f"comparison metric inventory disagrees with contract for {role}")
        for comparison in role_contract.comparisons:
            identity = f"{comparison.first_step}->{comparison.second_step}"
            raw_metrics = comparison_metrics[identity]
            expected_keys = COMPARISON_REGION_KEYS if comparison.region is not None else COMPARISON_KEYS
            try:
                metrics = require_object(
                    raw_metrics, label=f"comparison {role}/{identity}", required=expected_keys
                )
            except SecureJsonError as exc:
                raise VisualEvidenceError(str(exc)) from exc
            changed = _finite_number(
                metrics["changed_fraction"], f"comparison {identity}.changed_fraction", minimum=0, maximum=1
            )
            rms = _finite_number(
                metrics["rms_difference"], f"comparison {identity}.rms_difference", minimum=0, maximum=255
            )
            if metrics["required_changed_fraction"] != comparison.minimum_changed_fraction:
                _fail(f"comparison {identity} required threshold is stale")
            if changed < comparison.minimum_changed_fraction:
                _fail(f"comparison {identity} did not meet its deterministic change threshold")
            if comparison.region is not None and metrics["region"] != list(comparison.region):
                _fail(f"comparison {identity} region is stale")
            actual_changed, actual_rms = _comparison(
                frames_by_role_step[(role, comparison.first_step)],
                frames_by_role_step[(role, comparison.second_step)],
                comparison.region,
            )
            if changed != actual_changed or rms != actual_rms:
                _fail(f"comparison {identity} metrics disagree with decoded screenshots")
        _validate_raw_report(profile, role, report)
    _inventory_profile(profile, roles, screenshots)
    return (node, scenario_id), tuple(frames)


def _validate_evidence_root_outputs(
    root: Path,
    results: list[dict[str, Any]],
    contract: ScenarioContract,
    coverage: dict[str, Any],
) -> None:
    allowed = {
        "aggregate.json",
        "profiles",
        "resolved-matrix.json",
        "summary.json",
        "runtime-store.json",
        ".blockpops-run-workspace.json",
    }
    observed = {path.name for path in root.iterdir()}
    if not {"profiles", "resolved-matrix.json", "summary.json", "runtime-store.json"} <= observed:
        _fail("packaged evidence root is missing orchestrator identity files")
    if not observed <= allowed:
        _fail(f"packaged evidence root has unexpected entries: {sorted(observed - allowed)}")
    marker_path = root / ".blockpops-run-workspace.json"
    if marker_path.exists() or marker_path.is_symlink():
        try:
            marker, marker_raw = read_secure_json(
                marker_path,
                label="packaged workspace ownership marker",
                max_bytes=4096,
            )
            marker = require_object(
                marker,
                label="packaged workspace ownership marker",
                required={"schema", "kind", "generation"},
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        expected_marker = {
            "schema": 1,
            "kind": "blockpops-run-workspace-snapshot",
            "generation": marker["generation"],
        }
        if (
            not isinstance(marker["generation"], str)
            or re.fullmatch(r"[0-9a-f]{32}", marker["generation"]) is None
            or marker_raw != canonical_json(expected_marker) + b"\n"
        ):
            _fail("packaged workspace ownership marker is invalid")
    try:
        resolved, _ = read_secure_json(
            root / "resolved-matrix.json", label="resolved E2E matrix", max_bytes=MAX_JSON_BYTES
        )
        resolved = require_object(
            resolved, label="resolved E2E matrix", required={"schema_version", "rows"} | set(coverage)
        )
        summary, _ = read_secure_json(
            root / "summary.json", label="packaged E2E summary", max_bytes=MAX_JSON_BYTES
        )
        summary = require_object(
            summary,
            label="packaged E2E summary",
            required={"schema_version", "contract_sha256", "results", "runtime_store"} | set(coverage),
        )
        runtime, _ = read_secure_json(
            root / "runtime-store.json", label="runtime store summary", max_bytes=MAX_JSON_BYTES
        )
        runtime = require_object(
            runtime, label="runtime store summary", required={"schema_version", "metrics"}
        )
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    for payload in (resolved, summary):
        if canonical_json({key: payload[key] for key in coverage}) != canonical_json(coverage):
            _fail("packaged evidence coverage disagrees with external scope")
    if "aggregate_scope" in coverage:
        try:
            aggregate, _ = read_secure_json(root / "aggregate.json", label="visual aggregate receipt",
                                           max_bytes=MAX_JSON_BYTES)
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        if not isinstance(aggregate, dict) or canonical_json(aggregate.get("aggregate_scope")) != canonical_json(coverage["aggregate_scope"]):
            _fail("visual aggregate receipt disagrees with external scope")
    elif coverage and "aggregate.json" in observed:
        _fail("aggregate evidence requires an external projection")
    if any(type(payload["schema_version"]) is not int or payload["schema_version"] != 1
           for payload in (resolved, summary, runtime)):
        _fail("packaged orchestrator output schema_version must be integer 1")
    expected_rows = [
        {
            "artifact_node": result["artifact_node"],
            "minecraft": result["minecraft"],
            "loader": result["loader"],
            "scenario": result["scenario"],
            "production_jar_sha256": result["production_jar_sha256"],
        }
        for result in results
    ]
    def identity(record: dict[str, Any]) -> tuple[str, str]:
        return str(record.get("artifact_node")), str(record.get("scenario"))

    expected_result_map = {identity(result): result for result in results}
    if len(expected_result_map) != len(results):
        _fail("packaged E2E result inventory contains duplicate identities")
    raw_resolved_rows = resolved["rows"]
    if not isinstance(raw_resolved_rows, list):
        _fail("resolved E2E matrix rows must be an array")
    resolved_rows: list[dict[str, Any]] = []
    for index, raw_row in enumerate(raw_resolved_rows):
        try:
            resolved_rows.append(
                require_object(
                    raw_row,
                    label=f"resolved E2E matrix.rows[{index}]",
                    required={
                        "artifact_node",
                        "minecraft",
                        "loader",
                        "scenario",
                        "production_jar_sha256",
                    },
                )
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
    expected_row_map = {identity(row): row for row in expected_rows}
    resolved_row_map = {identity(row): row for row in resolved_rows}
    if (
        len(resolved_row_map) != len(resolved_rows)
        or resolved_row_map != expected_row_map
    ):
        _fail("resolved E2E matrix disagrees with exact result inventory")
    summary_results = summary["results"]
    if not isinstance(summary_results, list) or any(
        not isinstance(item, dict) for item in summary_results
    ):
        _fail("packaged E2E summary results must be an array of objects")
    summary_result_map = {identity(result): result for result in summary_results}
    if (
        summary["contract_sha256"] != contract.sha256
        or len(summary_result_map) != len(summary_results)
        or summary_result_map != expected_result_map
    ):
        _fail("packaged E2E summary is stale or mixed")
    if summary["runtime_store"] != runtime["metrics"]:
        _fail("packaged E2E runtime-store summaries disagree")
    metrics = runtime["metrics"]
    expected_metric_keys = {"hits", "misses", "pruned_entries", "pruned_bytes", "total_bytes"}
    if not isinstance(metrics, dict) or set(metrics) != expected_metric_keys or any(
        isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in metrics.values()
    ):
        _fail("packaged E2E runtime-store metrics use an unknown schema")


def _visual_selection(matrix, provenance, contract, *, scope, artifact_node, artifact_scope, projection):
    """Scope arguments come from the caller; none are inferred from artifact contents.

    artifact_scope is the scope of an externally verified artifact bundle. Execution
    may select one lane from that bundle; an aggregate must cover the entire bundle.
    This checks coverage only; the caller still authenticates fan-in and JAR provenance.
    """
    try:
        document = MatrixDocument(normalize_matrix_inventory(matrix), canonical_json(matrix).decode("utf-8"))
        if document.inventory.schema_version == 1:
            if any(value is not None for value in (scope, artifact_node, artifact_scope, projection)):
                _fail("scoped visual evidence requires schema2")
            return {lane.identity.artifact_node: lane.runtime for lane in document.inventory.lanes}, {}
        if scope not in {"lane", "legacy", "full"}:
            _fail("schema2 visual evidence requires explicit external scope")
        lanes = document.select_lanes(scope=scope, artifact_node=artifact_node)
        nodes = sorted(lane.identity.artifact_node for lane in lanes)
        if nodes != provenance["artifact_nodes"]:
            _fail("attested visual lanes disagree with external scope")
        if not isinstance(artifact_scope, dict) or artifact_scope.get("kind") not in {"lane", "legacy", "full"}:
            _fail("schema2 visual evidence requires external artifact bundle scope")
        bundle_nodes = artifact_scope.get("selected_nodes")
        if not isinstance(bundle_nodes, list) or not bundle_nodes:
            _fail("external artifact bundle scope has no selected nodes")
        bundle_lanes = document.select_lanes(scope=artifact_scope["kind"],
            artifact_node=bundle_nodes[0] if artifact_scope["kind"] == "lane" else None)
        bundle_lanes = sorted(bundle_lanes, key=lambda lane: (
            tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader))
        expected_bundle = {"kind": artifact_scope["kind"],
            "selected_nodes": [lane.identity.artifact_node for lane in bundle_lanes],
            "target_nodes": list(document.inventory.target_nodes),
            "migration_mode": document.inventory.migration_mode,
            "partial": len(bundle_lanes) != len(document.inventory.targets)}
        if canonical_json(artifact_scope) != canonical_json(expected_bundle) or not set(nodes) <= set(bundle_nodes):
            _fail("external artifact bundle scope disagrees with selected matrix lanes")
        scenarios = provenance["scenarios"]
        if projection is not None:
            if projection not in {"pr-anchors", "scheduled-anchors"}:
                _fail("visual aggregate projection is unsupported")
            projected = document.projection(projection, scope=scope, artifact_node=artifact_node, contract=contract)
            if (scope != artifact_scope["kind"] or set(nodes) != set(bundle_nodes)
                    or scenarios != sorted({scenario for row in projected["include"] for scenario in row["scenarios"].split(",")})):
                _fail("visual aggregate must cover the external bundle and projected scenarios")
            coverage = {"aggregate_scope": {**expected_bundle, "projection": projection, "scenarios": scenarios}}
        else:
            coverage = {"execution_scope": {"kind": scope, "selected_nodes": [lane.identity.artifact_node for lane in lanes],
                "scenarios": scenarios, "target_nodes": list(document.inventory.target_nodes),
                "partial": len(lanes) != len(document.inventory.targets), "artifact_scope": expected_bundle}}
        return {lane.identity.artifact_node: lane.runtime for lane in lanes}, coverage
    except MatrixError as exc:
        raise VisualEvidenceError(str(exc)) from exc


def collect_evidence(
    root: Path,
    *,
    matrix: dict[str, Any],
    contract: ScenarioContract,
    provenance: dict[str, Any],
    scope: str | None = None,
    artifact_node: str | None = None,
    artifact_scope: dict[str, Any] | None = None,
    projection: str | None = None,
) -> tuple[VisualFrame, ...]:
    """Validate a complete attested lane/scenario inventory below an extracted artifact."""

    root = _require_real_directory(root, "packaged evidence root")
    profiles = _require_real_directory(root / "profiles", "packaged evidence profiles")
    nodes = _validated_string_list(provenance["artifact_nodes"], "artifact_nodes")
    scenarios = _validated_string_list(provenance["scenarios"], "scenarios")
    matrix_rows, coverage = _visual_selection(matrix, provenance, contract, scope=scope,
        artifact_node=artifact_node, artifact_scope=artifact_scope, projection=projection)
    if not set(nodes) <= set(matrix_rows):
        _fail("attested visual artifact contains lanes outside the branch matrix")
    if not set(scenarios) <= set(contract.scenario_ids):
        _fail("attested visual artifact contains scenarios outside the contract")
    expected_identities = sorted((node, scenario) for node in nodes for scenario in scenarios)
    profile_directories: list[Path] = []
    for child in sorted(profiles.iterdir()):
        metadata = child.lstat()
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISDIR(metadata.st_mode)
            or not _safe_profile_name(child.name)
        ):
            _fail(f"packaged profiles root contains an unexpected entry: {child}")
        profile_directories.append(child)
    result_paths = [profile / "result.json" for profile in profile_directories]
    if not result_paths or len(result_paths) > MAX_EVIDENCE_PROFILES:
        _fail("packaged evidence profile count is empty or excessive")
    frames: list[VisualFrame] = []
    results: list[dict[str, Any]] = []
    identities: list[tuple[str, str]] = []
    for result_path in result_paths:
        _reject_component_symlinks(result_path, root, "packaged result")
        try:
            raw, _ = read_secure_json(
                result_path, label="packaged E2E result", max_bytes=MAX_JSON_BYTES
            )
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        identity, selected = _validate_result(
            raw,
            result_path=result_path,
            root=root,
            rows=matrix_rows,
            contract=contract,
            source_artifact_id=provenance["artifact_id"],
        )
        identities.append(identity)
        results.append(raw)
        frames.extend(selected)
    if identities != expected_identities:
        _fail(
            "packaged E2E lane/scenario coverage disagrees with attestation: "
            f"expected={expected_identities}, found={identities}"
        )
    if len({frame.label for frame in frames}) != len(frames):
        _fail("packaged evidence contains duplicate semantic frame identities")
    _validate_evidence_root_outputs(root, results, contract, coverage)
    return tuple(frames)


def load_archived_evidence(
    *,
    archive: Path,
    attestation_path: Path,
    expectation: SourceExpectation,
    matrix_path: Path,
    contract_path: Path,
    extraction_destination: Path,
    scope: str | None = None,
    artifact_node: str | None = None,
    artifact_scope: dict[str, Any] | None = None,
    projection: str | None = None,
) -> EvidenceBundle:
    """High-level secretless boundary from authenticated ZIP to normalized frames."""

    try:
        matrix, matrix_bytes = read_secure_json(matrix_path, label="visual release matrix", max_bytes=MAX_MATRIX_BYTES)
        normalize_matrix_inventory(matrix, repository=matrix_path.resolve().parents[1])
        contract = load_contract(contract_path)
        selected_matrix_sha256 = _sha256_bytes(matrix_bytes)
    except (MatrixError, ScenarioContractError, SecureJsonError) as exc:
        raise VisualEvidenceError(str(exc)) from exc
    provenance = read_attestation(
        attestation_path,
        expectation,
        expected_matrix_sha256=selected_matrix_sha256,
        expected_contract_sha256=contract.sha256,
    )
    if provenance["base_branch"] != matrix["branch"]["name"]:
        _fail("authenticated run base branch disagrees with branch-local matrix")
    extracted = extract_authenticated_artifact(
        archive,
        extraction_destination,
        expected_sha256=provenance["artifact_sha256"],
    )
    frames = collect_evidence(
        extracted, matrix=matrix, contract=contract, provenance=provenance,
        scope=scope, artifact_node=artifact_node, artifact_scope=artifact_scope, projection=projection,
    )
    return EvidenceBundle(
        provenance=provenance,
        matrix=matrix,
        matrix_sha256=selected_matrix_sha256,
        contract=contract,
        frames=frames,
    )


def canonical_reference_identity(matrix: dict[str, Any]) -> dict[str, str]:
    """Return the sole protected baseline identity owned by the release matrix."""

    visual = matrix["visual_reference"]
    branch = matrix["branch"]
    if (
        branch["canonical"] != visual["release_branch"]
        or visual["release_branch"] != "master"
        or visual["artifact_node"] != "fabric-1.20.1"
    ):
        _fail("release matrix does not preserve the protected master/fabric-1.20.1 visual anchor")
    return {
        "release_branch": visual["release_branch"],
        "artifact_node": visual["artifact_node"],
        "scenario_contract": visual["scenario_contract"],
    }
