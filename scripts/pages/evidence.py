#!/usr/bin/env python3
"""Curate, validate, and compact hostile packaged-E2E evidence for Pages.

Raw handoffs retain only the passing packaged results and their contracted PNGs.
Compact bundles retain only a strict manifest and content-addressed WebP files.
Neither format trusts filenames as semantic identity: every screenshot is paired
with the versioned scenario contract by ``capture_id``.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import warnings
from pathlib import Path, PurePosixPath
from typing import Any, Callable

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.packaged_runtime import (  # noqa: E402
    RuntimeFailure,
    compare_screenshots,
    inspect_screenshot_for_step,
    validate_packaged_result,
)
from e2e.scenario_contract import ScenarioContract, default_contract  # noqa: E402
from scripts.lib.secure_json import (  # noqa: E402
    SecureJsonError,
    read as read_secure_json,
    require_object,
)
from scripts.release.matrix import MatrixError, load_matrix  # noqa: E402

RAW_SCHEMA = 1
COMPACT_SCHEMA = 1
RAW_KIND = "raw-packaged-e2e"
COMPACT_KIND = "compact-pages-evidence"
E2E_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_RESULT_BYTES = 2 * 1024 * 1024
MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024
MAX_DERIVATIVE_BYTES = 2 * 1024 * 1024
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
MAX_FILES = 512
MAX_PIXELS = 1_600 * 900


class EvidenceError(ValueError):
    """Raised when evidence is incomplete, stale, unsafe, or malformed."""


def branch_token(branch: str) -> str:
    _text(branch, "branch", maximum=240)
    return hashlib.sha256(branch.encode("utf-8")).hexdigest()[:24]


def raw_artifact_name(branch: str, run_attempt: int) -> str:
    _positive_int(run_attempt, "raw artifact run attempt")
    return f"pages-e2e-{branch_token(branch)}-{run_attempt}"


def cache_artifact_name(branch: str, commit: str) -> str:
    _digest(commit, "commit", SHA1)
    return f"pages-cache-{branch_token(branch)}--{commit}"


def collection_artifact_name(branch: str, commit: str) -> str:
    _digest(commit, "commit", SHA1)
    return f"collected-pages-{branch_token(branch)}--{commit}"


def _text(value: Any, label: str, *, maximum: int = 1024) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or "\x00" in value
        or len(value.encode("utf-8")) > maximum
    ):
        raise EvidenceError(f"{label} must be a bounded, non-empty trimmed string")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise EvidenceError(f"{label} must be a positive integer")
    return value


def _digest(value: Any, label: str, pattern: re.Pattern[str] = SHA256) -> str:
    text = _text(value, label, maximum=64)
    if pattern.fullmatch(text) is None:
        raise EvidenceError(f"{label} has an invalid digest")
    return text


def _object(value: Any, label: str, keys: set[str]) -> dict[str, Any]:
    try:
        return require_object(value, label=label, required=keys)
    except SecureJsonError as exc:
        raise EvidenceError(str(exc)) from exc


def _canonical_path(value: Any, label: str) -> str:
    text = _text(value, label, maximum=512)
    path = PurePosixPath(text)
    if (
        path.is_absolute()
        or text != path.as_posix()
        or any(part in {"", ".", ".."} for part in path.parts)
        or "\\" in text
        or ":" in text
    ):
        raise EvidenceError(f"{label} is not a canonical relative path")
    return text


def _stable_bytes(path: Path, *, label: str, maximum: int) -> bytes:
    try:
        before = path.lstat()
    except OSError as exc:
        raise EvidenceError(f"cannot stat {label}: {exc}") from exc
    if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
        raise EvidenceError(f"{label} must be a regular non-symlink file")
    if before.st_size <= 0 or before.st_size > maximum:
        raise EvidenceError(f"{label} size is outside 1..{maximum}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise EvidenceError(f"cannot open {label}: {exc}") from exc
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino, opened.st_size)
            != (before.st_dev, before.st_ino, before.st_size)
        ):
            raise EvidenceError(f"{label} changed while opening")
        chunks: list[bytes] = []
        remaining = maximum + 1
        while remaining:
            chunk = os.read(descriptor, min(65536, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            len(data) != opened.st_size
            or (after.st_dev, after.st_ino, after.st_size)
            != (opened.st_dev, opened.st_ino, opened.st_size)
        ):
            raise EvidenceError(f"{label} changed while reading")
        return data
    finally:
        os.close(descriptor)


def _child_file(root: Path, relative: str, *, label: str, maximum: int) -> tuple[Path, bytes]:
    canonical = _canonical_path(relative, label)
    current = root
    for part in PurePosixPath(canonical).parts[:-1]:
        current = current / part
        try:
            info = current.lstat()
        except OSError as exc:
            raise EvidenceError(f"cannot inspect {label} parent: {exc}") from exc
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise EvidenceError(f"{label} has an unsafe parent component")
    path = root.joinpath(*PurePosixPath(canonical).parts)
    return path, _stable_bytes(path, label=label, maximum=maximum)


def _inventory(root: Path) -> dict[str, int]:
    try:
        root_info = root.lstat()
    except OSError as exc:
        raise EvidenceError(f"cannot inspect evidence root: {exc}") from exc
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise EvidenceError("evidence root must be a real directory")
    files: dict[str, int] = {}
    total = 0
    for directory, names, filenames in os.walk(root, followlinks=False):
        parent = Path(directory)
        for name in names:
            candidate = parent / name
            info = candidate.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
                raise EvidenceError(f"bundle contains an unsafe directory: {candidate}")
        for name in filenames:
            candidate = parent / name
            info = candidate.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                raise EvidenceError(f"bundle contains an unsafe file: {candidate}")
            relative = candidate.relative_to(root).as_posix()
            _canonical_path(relative, "bundle path")
            if relative in files:
                raise EvidenceError(f"bundle has duplicate path {relative}")
            files[relative] = info.st_size
            total += info.st_size
            if len(files) > MAX_FILES or total > MAX_BUNDLE_BYTES:
                raise EvidenceError("bundle exceeds its file-count or byte limit")
    return files


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _image(data: bytes, *, expected_format: str, label: str) -> dict[str, Any]:
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover - workflow installs the locked wheel
        raise EvidenceError("Pillow is required to validate public evidence") from exc
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if image.format != expected_format:
                    raise EvidenceError(f"{label} must decode as {expected_format}")
                if getattr(image, "n_frames", 1) != 1:
                    raise EvidenceError(f"{label} must contain exactly one frame")
                width, height = image.size
                if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                    raise EvidenceError(f"{label} dimensions exceed the decode limit")
                image.load()
                return {"format": expected_format, "width": width, "height": height}
    except EvidenceError:
        raise
    except Exception as exc:
        raise EvidenceError(f"cannot decode {label}: {exc}") from exc


def _json(path: Path, *, label: str, maximum: int) -> tuple[dict[str, Any], bytes]:
    try:
        value, raw = read_secure_json(path, label=label, max_bytes=maximum)
    except SecureJsonError as exc:
        raise EvidenceError(str(exc)) from exc
    if not isinstance(value, dict):
        raise EvidenceError(f"{label} must be an object")
    return value, raw


def _atomic_directory(output: Path, writer: Callable[[Path], Any]) -> Any:
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise EvidenceError(f"refusing to replace existing output {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.building-", dir=output.parent))
    try:
        result = writer(staging)
        staging.rename(output)
        return result
    finally:
        if staging.exists() and not staging.is_symlink():
            shutil.rmtree(staging)


def _write_json(path: Path, value: Any) -> None:
    data = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
    if len(data) > MAX_MANIFEST_BYTES:
        raise EvidenceError(f"generated JSON is oversized: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as output:
        output.write(data)


def _copy_bytes(destination: Path, data: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output:
        output.write(data)


def _matrix_identity(matrix_path: Path, matrix: dict[str, Any]) -> tuple[str, bytes]:
    raw = _stable_bytes(matrix_path, label="release matrix", maximum=256 * 1024)
    return sha256_bytes(raw), raw


def _expected_lanes(matrix: dict[str, Any], contract: ScenarioContract) -> list[tuple[dict[str, Any], str]]:
    scenarios = contract.scenarios_for_profile("release")
    if not scenarios:
        raise EvidenceError("release scenario selection is empty")
    return [(runtime, scenario) for runtime in matrix["runtimes"] for scenario in scenarios]


def _profile_name(artifact_node: str, minecraft: str, scenario: str) -> str:
    value = f"{artifact_node}--{minecraft}--{scenario}"
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-.")
    if not safe:
        raise EvidenceError("packaged profile identity is empty")
    return safe[:180]


def _validate_report(
    report: Any,
    *,
    contract: ScenarioContract,
    scenario: str,
    role: str,
    minecraft: str,
) -> list[dict[str, Any]]:
    value = _object(
        report,
        f"report {scenario}/{role}",
        {"schema_version", "minecraft", "role", "scenario", "contract_sha256", "status", "steps", "pixel_validation"},
    )
    if (
        value["schema_version"] != 1
        or value["minecraft"] != minecraft
        or value["role"] != role
        or value["scenario"] != scenario
        or value["contract_sha256"] != contract.sha256
        or value["status"] != "pass"
    ):
        raise EvidenceError(f"report identity/status mismatch for {scenario}/{role}")
    role_contract = contract.role(scenario, role)
    steps = value["steps"]
    if not isinstance(steps, list) or len(steps) != len(role_contract.steps):
        raise EvidenceError(f"report step count mismatch for {scenario}/{role}")
    normalized: list[dict[str, Any]] = []
    for index, (step, expected) in enumerate(zip(steps, role_contract.steps, strict=True)):
        item = _object(
            step,
            f"report {scenario}/{role}.steps[{index}]",
            {"id", "status", "message", "capture_id", "screenshot"},
        )
        capture = expected.capture
        expected_capture = capture.capture_id if capture is not None else None
        expected_screenshot = f"{expected_capture}.png" if capture is not None else None
        if (
            item["id"] != expected.id
            or item["status"] != "pass"
            or item["capture_id"] != expected_capture
            or item["screenshot"] != expected_screenshot
            or not isinstance(item["message"], str)
            or len(item["message"]) > 1024
        ):
            raise EvidenceError(f"report step mismatch for {scenario}/{role}/{expected.id}")
        normalized.append(item)
    pixels = _object(value["pixel_validation"], "report pixel_validation", {"screenshots", "comparisons"})
    if not isinstance(pixels["screenshots"], dict) or not isinstance(pixels["comparisons"], dict):
        raise EvidenceError("report pixel validation must contain objects")
    return normalized


def _collect_lane(
    root: Path,
    matrix_row: dict[str, Any],
    scenario: str,
    contract: ScenarioContract,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[tuple[str, bytes]]]:
    profile_name = _profile_name(matrix_row["artifact_node"], matrix_row["minecraft"], scenario)
    profile = root / "profiles" / profile_name
    result_path = profile / "result.json"
    result, result_raw = _json(result_path, label=f"packaged result {profile_name}", maximum=MAX_RESULT_BYTES)
    try:
        validate_packaged_result(result)
    except RuntimeFailure as exc:
        raise EvidenceError(f"invalid packaged result {profile_name}: {exc}") from exc
    if (
        result["status"] != "pass"
        or result["artifact_node"] != matrix_row["artifact_node"]
        or result["minecraft"] != matrix_row["minecraft"]
        or result["loader"] != matrix_row["loader"]
        or result["scenario"] != scenario
        or result["contract_sha256"] != contract.sha256
        or result["profile"] != f"profiles/{profile_name}"
        or result["error"] is not None
    ):
        raise EvidenceError(f"packaged result identity/status mismatch for {profile_name}")
    expected_roles = contract.expected_roles(scenario)
    if set(result["reports"]) != set(expected_roles):
        raise EvidenceError(f"packaged report role inventory mismatch for {profile_name}")
    files: list[tuple[str, bytes]] = [(f"profiles/{profile_name}/result.json", result_raw)]
    frames: list[dict[str, Any]] = []
    for role in expected_roles:
        steps = _validate_report(
            result["reports"][role],
            contract=contract,
            scenario=scenario,
            role=role,
            minecraft=matrix_row["minecraft"],
        )
        expected_pngs: set[str] = set()
        paths_by_step: dict[str, Path] = {}
        for item in steps:
            if item["capture_id"] is None:
                continue
            expected_pngs.add(item["screenshot"])
            relative = f"profiles/{profile_name}/{role}/screenshots/{item['screenshot']}"
            source, raw = _child_file(root, relative, label=f"capture {item['capture_id']}", maximum=MAX_SCREENSHOT_BYTES)
            decoded = _image(raw, expected_format="PNG", label=f"capture {item['capture_id']}")
            if (decoded["width"], decoded["height"]) != contract.gui_text_reference_size:
                raise EvidenceError(f"capture {item['capture_id']} has incompatible dimensions")
            try:
                reinspected = inspect_screenshot_for_step(source, scenario, role, item["id"])
            except RuntimeFailure as exc:
                raise EvidenceError(str(exc)) from exc
            recorded = result["reports"][role]["pixel_validation"]["screenshots"].get(item["id"])
            if reinspected != recorded:
                raise EvidenceError(f"protected pixel validation disagrees for {item['capture_id']}")
            capture = contract.capture(scenario, role, item["id"])
            frames.append(
                {
                    "artifact_node": matrix_row["artifact_node"],
                    "minecraft": matrix_row["minecraft"],
                    "loader": matrix_row["loader"],
                    "scenario": scenario,
                    "role": role,
                    "step": item["id"],
                    "capture_id": item["capture_id"],
                    "title": capture.title,
                    "expectation": capture.expectation,
                    "review_tier": capture.review_tier,
                    "source": {
                        "path": relative,
                        "sha256": sha256_bytes(raw),
                        "size": len(raw),
                        "width": decoded["width"],
                        "height": decoded["height"],
                    },
                }
            )
            paths_by_step[item["id"]] = source
            files.append((relative, raw))
        screenshots_dir = profile / role / "screenshots"
        actual = set()
        if screenshots_dir.exists():
            for path in screenshots_dir.iterdir():
                info = path.lstat()
                if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or path.suffix != ".png":
                    raise EvidenceError(f"unsafe/unknown screenshot entry {path}")
                actual.add(path.name)
        if actual != expected_pngs:
            raise EvidenceError(f"screenshot inventory mismatch for {profile_name}/{role}")
        for comparison in contract.comparisons_for(scenario, role):
            try:
                reinspected = compare_screenshots(
                    paths_by_step[comparison.first_step],
                    paths_by_step[comparison.second_step],
                    comparison.minimum_changed_fraction,
                    comparison.region,
                )
            except (KeyError, RuntimeFailure) as exc:
                raise EvidenceError(f"comparison validation failed for {profile_name}/{role}: {exc}") from exc
            key = f"{comparison.first_step}->{comparison.second_step}"
            recorded = result["reports"][role]["pixel_validation"]["comparisons"].get(key)
            if reinspected != recorded:
                raise EvidenceError(f"protected comparison disagrees for {profile_name}/{role}/{key}")
    lane = {
        "artifact_node": matrix_row["artifact_node"],
        "minecraft": matrix_row["minecraft"],
        "loader": matrix_row["loader"],
        "java": matrix_row["java"],
        "scenario": scenario,
        "profile": f"profiles/{profile_name}",
        "production_jar_sha256": result["production_jar_sha256"],
        "harness_jar_sha256": result["harness_jar_sha256"],
    }
    return lane, frames, files


def curate(
    *, input_root: Path, output: Path, matrix_path: Path, repository: str,
    branch: str, commit: str, tree: str, run_id: int, run_attempt: int,
    controller_branch: str, controller_sha: str,
    packaged_run_id: int | None = None,
    packaged_run_attempt: int | None = None,
    packaged_branch: str | None = None,
    packaged_commit: str | None = None,
    packaged_tree: str | None = None,
    packaged_controller_branch: str | None = None,
    packaged_controller_sha: str | None = None,
) -> dict[str, Any]:
    if REPOSITORY.fullmatch(repository) is None:
        raise EvidenceError("repository must use owner/name form")
    _text(branch, "branch", maximum=240)
    _digest(commit, "commit", SHA1)
    _digest(tree, "tree", SHA1)
    _positive_int(run_id, "run_id")
    _positive_int(run_attempt, "run_attempt")
    _text(controller_branch, "controller_branch", maximum=240)
    _digest(controller_sha, "controller_sha", SHA1)
    packaged_run_id = run_id if packaged_run_id is None else packaged_run_id
    packaged_run_attempt = (
        run_attempt if packaged_run_attempt is None else packaged_run_attempt
    )
    packaged_branch = branch if packaged_branch is None else packaged_branch
    packaged_commit = commit if packaged_commit is None else packaged_commit
    packaged_tree = tree if packaged_tree is None else packaged_tree
    packaged_controller_branch = (
        controller_branch
        if packaged_controller_branch is None
        else packaged_controller_branch
    )
    packaged_controller_sha = (
        controller_sha if packaged_controller_sha is None else packaged_controller_sha
    )
    _positive_int(packaged_run_id, "packaged_run_id")
    _positive_int(packaged_run_attempt, "packaged_run_attempt")
    _text(packaged_branch, "packaged_branch", maximum=240)
    _digest(packaged_commit, "packaged_commit", SHA1)
    _digest(packaged_tree, "packaged_tree", SHA1)
    _text(packaged_controller_branch, "packaged_controller_branch", maximum=240)
    _digest(packaged_controller_sha, "packaged_controller_sha", SHA1)
    if packaged_tree != tree:
        raise EvidenceError("packaged source and current handoff must have the exact same tree")
    try:
        matrix = load_matrix(matrix_path, validate_sources=False)
    except MatrixError as exc:
        raise EvidenceError(str(exc)) from exc
    if matrix["branch"]["name"] != branch:
        raise EvidenceError("matrix branch identity does not match the handoff")
    contract = default_contract()
    matrix_sha, _ = _matrix_identity(matrix_path, matrix)
    root = input_root.absolute()
    try:
        root_info = root.lstat()
    except OSError as exc:
        raise EvidenceError(f"cannot inspect packaged evidence root: {exc}") from exc
    if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode):
        raise EvidenceError("packaged evidence root must be a real directory")
    expected = _expected_lanes(matrix, contract)
    expected_profiles = {_profile_name(row["artifact_node"], row["minecraft"], scenario) for row, scenario in expected}
    profiles_root = root / "profiles"
    try:
        actual_profiles: set[str] = set()
        for path in profiles_root.iterdir():
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
                raise EvidenceError(f"packaged profile inventory contains an unsafe entry: {path}")
            actual_profiles.add(path.name)
    except OSError as exc:
        raise EvidenceError(f"cannot inspect packaged profiles: {exc}") from exc
    if actual_profiles != expected_profiles:
        raise EvidenceError(
            f"packaged profile inventory mismatch: missing={sorted(expected_profiles - actual_profiles)}, "
            f"extra={sorted(actual_profiles - expected_profiles)}"
        )
    lanes: list[dict[str, Any]] = []
    frames: list[dict[str, Any]] = []
    selected_files: list[tuple[str, bytes]] = []
    for row, scenario in expected:
        lane, lane_frames, lane_files = _collect_lane(root, row, scenario, contract)
        lanes.append(lane)
        frames.extend(lane_frames)
        selected_files.extend(lane_files)
    if len({frame["capture_id"] + "@" + frame["artifact_node"] for frame in frames}) != len(frames):
        raise EvidenceError("curated evidence contains duplicate semantic frames")
    selected_files.sort(key=lambda item: item[0])
    records = [{"path": path, "sha256": sha256_bytes(raw), "size": len(raw)} for path, raw in selected_files]
    manifest = {
        "schema_version": RAW_SCHEMA,
        "kind": RAW_KIND,
        "provenance": {
            "repository": repository,
            "branch": branch,
            "commit": commit,
            "tree": tree,
            "handoff": {
                "path": E2E_WORKFLOW,
                "run_id": run_id,
                "run_attempt": run_attempt,
                "controller_branch": controller_branch,
                "controller_sha": controller_sha,
            },
            "packaged": {
                "path": E2E_WORKFLOW,
                "run_id": packaged_run_id,
                "run_attempt": packaged_run_attempt,
                "branch": packaged_branch,
                "commit": packaged_commit,
                "tree": packaged_tree,
                "controller_branch": packaged_controller_branch,
                "controller_sha": packaged_controller_sha,
            },
            "matrix_sha256": matrix_sha,
            "contract_sha256": contract.sha256,
        },
        "lanes": lanes,
        "frames": frames,
        "files": records,
    }

    def writer(stage: Path) -> None:
        for relative, raw in selected_files:
            _copy_bytes(stage / relative, raw)
        _write_json(stage / "pages-evidence.json", manifest)
        validate_raw(stage, matrix_path=matrix_path, expected=manifest["provenance"])

    _atomic_directory(output, writer)
    return manifest


def _validate_provenance(value: Any, *, expected: dict[str, Any] | None = None) -> dict[str, Any]:
    provenance = _object(
        value,
        "provenance",
        {
            "repository",
            "branch",
            "commit",
            "tree",
            "handoff",
            "packaged",
            "matrix_sha256",
            "contract_sha256",
        },
    )
    if REPOSITORY.fullmatch(_text(provenance["repository"], "repository")) is None:
        raise EvidenceError("provenance repository is invalid")
    _text(provenance["branch"], "provenance branch", maximum=240)
    _digest(provenance["commit"], "provenance commit", SHA1)
    _digest(provenance["tree"], "provenance tree", SHA1)
    _digest(provenance["matrix_sha256"], "matrix_sha256")
    _digest(provenance["contract_sha256"], "contract_sha256")
    handoff = _object(
        provenance["handoff"],
        "provenance handoff",
        {"path", "run_id", "run_attempt", "controller_branch", "controller_sha"},
    )
    packaged = _object(
        provenance["packaged"],
        "provenance packaged",
        {
            "path",
            "run_id",
            "run_attempt",
            "branch",
            "commit",
            "tree",
            "controller_branch",
            "controller_sha",
        },
    )
    if handoff["path"] != E2E_WORKFLOW or packaged["path"] != E2E_WORKFLOW:
        raise EvidenceError("evidence comes from an unapproved workflow")
    _positive_int(handoff["run_id"], "handoff.run_id")
    _positive_int(handoff["run_attempt"], "handoff.run_attempt")
    _text(handoff["controller_branch"], "handoff.controller_branch", maximum=240)
    _digest(handoff["controller_sha"], "handoff.controller_sha", SHA1)
    _positive_int(packaged["run_id"], "packaged.run_id")
    _positive_int(packaged["run_attempt"], "packaged.run_attempt")
    _text(packaged["branch"], "packaged.branch", maximum=240)
    _digest(packaged["commit"], "packaged.commit", SHA1)
    _digest(packaged["tree"], "packaged.tree", SHA1)
    _text(packaged["controller_branch"], "packaged.controller_branch", maximum=240)
    _digest(packaged["controller_sha"], "packaged.controller_sha", SHA1)
    if packaged["tree"] != provenance["tree"]:
        raise EvidenceError("packaged source tree differs from the current handoff tree")
    if expected:
        for key in ("repository", "branch", "commit", "tree", "matrix_sha256", "contract_sha256"):
            if key in expected and provenance[key] != expected[key]:
                raise EvidenceError(f"provenance {key} is stale")
        expected_handoff = expected.get("handoff")
        if expected_handoff is not None and handoff != expected_handoff:
            raise EvidenceError("provenance handoff run identity is stale")
    return provenance


def _lane_keys(row: dict[str, Any]) -> tuple[str, str, str, str]:
    return row["artifact_node"], row["minecraft"], row["loader"], row["scenario"]


def _validate_lane_rows(value: Any, matrix: dict[str, Any], contract: ScenarioContract) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise EvidenceError("lanes must be an array")
    expected = {(row["artifact_node"], row["minecraft"], row["loader"], scenario): (row, scenario) for row, scenario in _expected_lanes(matrix, contract)}
    lanes: list[dict[str, Any]] = []
    for index, raw in enumerate(value):
        lane = _object(raw, f"lanes[{index}]", {"artifact_node", "minecraft", "loader", "java", "scenario", "profile", "production_jar_sha256", "harness_jar_sha256"})
        key = _lane_keys(lane)
        authoritative = expected.get(key)
        if authoritative is None or key in {_lane_keys(item) for item in lanes}:
            raise EvidenceError(f"unknown/duplicate lane {key}")
        row, _ = authoritative
        if lane["java"] != row["java"] or lane["profile"] != f"profiles/{_profile_name(row['artifact_node'], row['minecraft'], lane['scenario'])}":
            raise EvidenceError(f"stale lane routing for {key}")
        _digest(lane["production_jar_sha256"], "production jar sha256")
        _digest(lane["harness_jar_sha256"], "harness jar sha256")
        lanes.append(lane)
    if {_lane_keys(item) for item in lanes} != set(expected):
        raise EvidenceError("lane inventory is incomplete for the branch matrix")
    return lanes


def _validate_file_records(root: Path, records: Any, *, include_manifest: str) -> dict[str, bytes]:
    if not isinstance(records, list):
        raise EvidenceError("files must be an array")
    loaded: dict[str, bytes] = {}
    for index, raw in enumerate(records):
        record = _object(raw, f"files[{index}]", {"path", "sha256", "size"})
        relative = _canonical_path(record["path"], f"files[{index}].path")
        if relative in loaded:
            raise EvidenceError(f"duplicate file record {relative}")
        expected_size = _positive_int(record["size"], f"files[{index}].size")
        expected_sha = _digest(record["sha256"], f"files[{index}].sha256")
        maximum = MAX_SCREENSHOT_BYTES if relative.endswith(".png") else MAX_RESULT_BYTES
        _, data = _child_file(root, relative, label=f"file {relative}", maximum=maximum)
        if len(data) != expected_size or sha256_bytes(data) != expected_sha:
            raise EvidenceError(f"file record hash/size mismatch for {relative}")
        loaded[relative] = data
    inventory = _inventory(root)
    if set(inventory) != set(loaded) | {include_manifest}:
        raise EvidenceError("bundle file inventory differs from its manifest")
    return loaded


def _validate_frames(
    value: Any, *, root: Path, files: dict[str, bytes], lanes: list[dict[str, Any]],
    contract: ScenarioContract,
) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise EvidenceError("frames must be an array")
    lane_keys = {_lane_keys(lane) for lane in lanes}
    expected = {
        (lane["artifact_node"], capture.capture_id)
        for lane in lanes
        for capture in contract.scenario(lane["scenario"]).roles
        for capture in [capture]
        for step in capture.steps
        if step.capture is not None
        for capture in [step.capture]
    }
    frames: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for index, raw in enumerate(value):
        frame = _object(raw, f"frames[{index}]", {"artifact_node", "minecraft", "loader", "scenario", "role", "step", "capture_id", "title", "expectation", "review_tier", "source"})
        lane_key = (frame["artifact_node"], frame["minecraft"], frame["loader"], frame["scenario"])
        if lane_key not in lane_keys:
            raise EvidenceError(f"frame belongs to unknown lane {lane_key}")
        capture = contract.capture(frame["scenario"], frame["role"], frame["step"])
        identity = (frame["artifact_node"], frame["capture_id"])
        if (
            frame["capture_id"] != capture.capture_id
            or frame["title"] != capture.title
            or frame["expectation"] != capture.expectation
            or frame["review_tier"] != capture.review_tier
            or identity in seen
        ):
            raise EvidenceError(f"frame semantic identity is stale/duplicate: {identity}")
        source = _object(frame["source"], f"frames[{index}].source", {"path", "sha256", "size", "width", "height"})
        path = _canonical_path(source["path"], "frame source path")
        data = files.get(path)
        if data is None or sha256_bytes(data) != source["sha256"] or len(data) != source["size"]:
            raise EvidenceError(f"frame source record is stale for {identity}")
        decoded = _image(data, expected_format="PNG", label=f"frame {identity}")
        if decoded["width"] != source["width"] or decoded["height"] != source["height"] or (decoded["width"], decoded["height"]) != contract.gui_text_reference_size:
            raise EvidenceError(f"frame dimensions are stale/incompatible for {identity}")
        try:
            inspected = inspect_screenshot_for_step(root / path, frame["scenario"], frame["role"], frame["step"])
        except RuntimeFailure as exc:
            raise EvidenceError(str(exc)) from exc
        # The result file is validated below and carries the exact recorded metrics;
        # this independent reinspection proves the decoded source still satisfies probes.
        if inspected["width"] != source["width"] or inspected["height"] != source["height"]:
            raise EvidenceError(f"pixel reinspection dimension mismatch for {identity}")
        seen.add(identity)
        frames.append(frame)
    if seen != expected:
        raise EvidenceError("semantic frame inventory is incomplete")
    return frames


def validate_raw(root: Path, *, matrix_path: Path, expected: dict[str, Any] | None = None) -> dict[str, Any]:
    manifest, raw = _json(root / "pages-evidence.json", label="raw Pages manifest", maximum=MAX_MANIFEST_BYTES)
    value = _object(manifest, "raw Pages manifest", {"schema_version", "kind", "provenance", "lanes", "frames", "files"})
    if value["schema_version"] != RAW_SCHEMA or value["kind"] != RAW_KIND:
        raise EvidenceError("raw Pages manifest schema/kind is unsupported")
    provenance = _validate_provenance(value["provenance"], expected=expected)
    try:
        matrix = load_matrix(matrix_path, validate_sources=False)
    except MatrixError as exc:
        raise EvidenceError(str(exc)) from exc
    matrix_sha, _ = _matrix_identity(matrix_path, matrix)
    contract = default_contract()
    if matrix["branch"]["name"] != provenance["branch"] or matrix_sha != provenance["matrix_sha256"] or contract.sha256 != provenance["contract_sha256"]:
        raise EvidenceError("raw evidence disagrees with its authenticated matrix/contract")
    lanes = _validate_lane_rows(value["lanes"], matrix, contract)
    files = _validate_file_records(root, value["files"], include_manifest="pages-evidence.json")
    frames = _validate_frames(value["frames"], root=root, files=files, lanes=lanes, contract=contract)
    # Reconstruct the complete lane/frame view from the packaged result files with
    # the protected implementation.  This prevents candidate-owned curation code
    # from blessing an invented manifest around unrelated, probe-shaped images.
    matrix_rows = {row["artifact_node"]: row for row in matrix["runtimes"]}
    reconstructed_lanes: list[dict[str, Any]] = []
    reconstructed_frames: list[dict[str, Any]] = []
    reconstructed_files: set[str] = set()
    for lane in lanes:
        reconstructed_lane, lane_frames, lane_files = _collect_lane(
            root,
            matrix_rows[lane["artifact_node"]],
            lane["scenario"],
            contract,
        )
        reconstructed_lanes.append(reconstructed_lane)
        reconstructed_frames.extend(lane_frames)
        reconstructed_files.update(path for path, _ in lane_files)
    if reconstructed_lanes != lanes or reconstructed_frames != frames:
        raise EvidenceError("protected reconstruction disagrees with the raw manifest")
    expected_results = {lane["profile"] + "/result.json" for lane in lanes}
    if {path for path in files if path.endswith("/result.json")} != expected_results:
        raise EvidenceError("raw result inventory is incomplete")
    expected_sources = {frame["source"]["path"] for frame in frames}
    if set(files) != expected_results | expected_sources:
        raise EvidenceError("raw file inventory contains unreferenced evidence")
    if reconstructed_files != set(files):
        raise EvidenceError("protected result reconstruction found a different file inventory")
    return value


def _encode_webp(raw: bytes) -> bytes:
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = MAX_PIXELS
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as source:
                width, height = source.size
                if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                    raise EvidenceError("source image exceeds the WebP decode limit")
                source.load()
                rendered = source.convert("RGB")
                rendered.thumbnail((1280, 720), Image.Resampling.LANCZOS)
                output = io.BytesIO()
                rendered.save(output, "WEBP", quality=82, method=6, exact=True)
                encoded = output.getvalue()
    except Exception as exc:
        raise EvidenceError(f"cannot create WebP derivative: {exc}") from exc
    if not encoded or len(encoded) > MAX_DERIVATIVE_BYTES:
        raise EvidenceError("WebP derivative exceeds its byte bound")
    return encoded


def compact(
    *, input_root: Path, output: Path, matrix_path: Path, expected: dict[str, Any],
    source_artifact_id: int, source_artifact_name: str, source_artifact_digest: str,
) -> dict[str, Any]:
    raw = validate_raw(input_root, matrix_path=matrix_path, expected=expected)
    matrix_bytes = _stable_bytes(matrix_path, label="branch release matrix", maximum=256 * 1024)
    _positive_int(source_artifact_id, "source artifact id")
    if source_artifact_name != raw_artifact_name(
        raw["provenance"]["branch"], raw["provenance"]["handoff"]["run_attempt"]
    ):
        raise EvidenceError("source artifact name is not bound to the exact branch")
    if not source_artifact_digest.startswith("sha256:") or SHA256.fullmatch(source_artifact_digest[7:]) is None:
        raise EvidenceError("source artifact digest is invalid")
    derivatives: dict[str, bytes] = {}
    frames: list[dict[str, Any]] = []
    for frame in raw["frames"]:
        _, source = _child_file(input_root, frame["source"]["path"], label="raw frame", maximum=MAX_SCREENSHOT_BYTES)
        encoded = _encode_webp(source)
        digest = sha256_bytes(encoded)
        decoded = _image(encoded, expected_format="WEBP", label=f"derivative {frame['capture_id']}")
        relative = f"images/{digest}.webp"
        previous = derivatives.setdefault(relative, encoded)
        if previous != encoded:
            raise EvidenceError("derivative digest collision")
        frames.append(
            {**frame, "derivative": {"path": relative, "sha256": digest, "size": len(encoded), "width": decoded["width"], "height": decoded["height"], "format": "WEBP"}}
        )
    manifest = {
        "schema_version": COMPACT_SCHEMA,
        "kind": COMPACT_KIND,
        "provenance": raw["provenance"],
        "source_artifact": {"id": source_artifact_id, "name": source_artifact_name, "digest": source_artifact_digest},
        "matrix": {"path": "release-matrix.json", "sha256": sha256_bytes(matrix_bytes), "size": len(matrix_bytes)},
        "lanes": raw["lanes"],
        "frames": frames,
        "files": [{"path": path, "sha256": sha256_bytes(data), "size": len(data)} for path, data in sorted(derivatives.items())],
    }

    def writer(stage: Path) -> None:
        _copy_bytes(stage / "release-matrix.json", matrix_bytes)
        for relative, data in derivatives.items():
            _copy_bytes(stage / relative, data)
        _write_json(stage / "manifest.json", manifest)
        validate_compact(stage, matrix_path=matrix_path, expected=expected)

    _atomic_directory(output, writer)
    return manifest


def validate_compact(root: Path, *, matrix_path: Path, expected: dict[str, Any] | None = None) -> dict[str, Any]:
    manifest, _ = _json(root / "manifest.json", label="compact Pages manifest", maximum=MAX_MANIFEST_BYTES)
    value = _object(manifest, "compact Pages manifest", {"schema_version", "kind", "provenance", "source_artifact", "matrix", "lanes", "frames", "files"})
    if value["schema_version"] != COMPACT_SCHEMA or value["kind"] != COMPACT_KIND:
        raise EvidenceError("compact Pages manifest schema/kind is unsupported")
    provenance = _validate_provenance(value["provenance"], expected=expected)
    source_artifact = _object(value["source_artifact"], "source_artifact", {"id", "name", "digest"})
    _positive_int(source_artifact["id"], "source_artifact.id")
    if source_artifact["name"] != raw_artifact_name(
        provenance["branch"], provenance["handoff"]["run_attempt"]
    ):
        raise EvidenceError("compact source artifact name is stale")
    if not isinstance(source_artifact["digest"], str) or not source_artifact["digest"].startswith("sha256:") or SHA256.fullmatch(source_artifact["digest"][7:]) is None:
        raise EvidenceError("compact source artifact digest is invalid")
    matrix_record = _object(value["matrix"], "compact matrix", {"path", "sha256", "size"})
    if matrix_record["path"] != "release-matrix.json":
        raise EvidenceError("compact matrix path is invalid")
    _, embedded_matrix = _child_file(root, "release-matrix.json", label="compact release matrix", maximum=256 * 1024)
    if matrix_record["sha256"] != sha256_bytes(embedded_matrix) or matrix_record["size"] != len(embedded_matrix):
        raise EvidenceError("compact matrix record is stale")
    external_matrix = _stable_bytes(matrix_path, label="authenticated release matrix", maximum=256 * 1024)
    if external_matrix != embedded_matrix:
        raise EvidenceError("compact embedded matrix differs from the authenticated matrix")
    try:
        matrix = load_matrix(matrix_path, validate_sources=False)
    except MatrixError as exc:
        raise EvidenceError(str(exc)) from exc
    matrix_sha, _ = _matrix_identity(matrix_path, matrix)
    contract = default_contract()
    if matrix["branch"]["name"] != provenance["branch"] or matrix_sha != provenance["matrix_sha256"] or contract.sha256 != provenance["contract_sha256"]:
        raise EvidenceError("compact evidence disagrees with its matrix/contract")
    lanes = _validate_lane_rows(value["lanes"], matrix, contract)
    if not isinstance(value["files"], list):
        raise EvidenceError("compact files must be an array")
    derivative_bytes: dict[str, bytes] = {}
    for index, raw_record in enumerate(value["files"]):
        record = _object(raw_record, f"files[{index}]", {"path", "sha256", "size"})
        relative = _canonical_path(record["path"], "compact file path")
        if relative in derivative_bytes or not relative.startswith("images/") or not relative.endswith(".webp"):
            raise EvidenceError("compact file path is duplicate or outside images/")
        _, data = _child_file(root, relative, label=f"compact file {relative}", maximum=MAX_DERIVATIVE_BYTES)
        digest = sha256_bytes(data)
        if record["sha256"] != digest or record["size"] != len(data) or relative != f"images/{digest}.webp":
            raise EvidenceError(f"compact file identity mismatch for {relative}")
        derivative_bytes[relative] = data
    inventory = _inventory(root)
    if set(inventory) != set(derivative_bytes) | {"manifest.json", "release-matrix.json"}:
        raise EvidenceError("compact bundle inventory differs from its manifest")
    if not isinstance(value["frames"], list):
        raise EvidenceError("compact frames must be an array")
    seen: set[tuple[str, str]] = set()
    expected_frames = {(lane["artifact_node"], capture.capture_id) for lane in lanes for role in contract.scenario(lane["scenario"]).roles for step in role.steps if step.capture is not None for capture in [step.capture]}
    lanes_by_node = {lane["artifact_node"]: lane for lane in lanes}
    for index, raw_frame in enumerate(value["frames"]):
        frame = _object(raw_frame, f"frames[{index}]", {"artifact_node", "minecraft", "loader", "scenario", "role", "step", "capture_id", "title", "expectation", "review_tier", "source", "derivative"})
        capture = contract.capture(frame["scenario"], frame["role"], frame["step"])
        identity = (frame["artifact_node"], frame["capture_id"])
        lane = lanes_by_node.get(frame["artifact_node"])
        if (
            frame["capture_id"] != capture.capture_id
            or frame["title"] != capture.title
            or frame["expectation"] != capture.expectation
            or frame["review_tier"] != capture.review_tier
            or lane is None
            or (frame["minecraft"], frame["loader"], frame["scenario"])
            != (lane["minecraft"], lane["loader"], lane["scenario"])
            or identity in seen
            or identity not in expected_frames
        ):
            raise EvidenceError(f"compact frame identity mismatch {identity}")
        source = _object(frame["source"], "compact frame source", {"path", "sha256", "size", "width", "height"})
        _canonical_path(source["path"], "source path")
        _digest(source["sha256"], "source sha256")
        _positive_int(source["size"], "source size")
        if (source["width"], source["height"]) != contract.gui_text_reference_size:
            raise EvidenceError("compact frame source dimensions are incompatible")
        derivative = _object(frame["derivative"], "compact derivative", {"path", "sha256", "size", "width", "height", "format"})
        data = derivative_bytes.get(derivative["path"])
        if data is None or derivative["format"] != "WEBP" or derivative["sha256"] != sha256_bytes(data) or derivative["size"] != len(data):
            raise EvidenceError(f"compact derivative is stale for {identity}")
        decoded = _image(data, expected_format="WEBP", label=f"compact frame {identity}")
        if decoded["width"] != derivative["width"] or decoded["height"] != derivative["height"] or decoded["width"] > source["width"] or decoded["height"] > source["height"]:
            raise EvidenceError(f"compact derivative dimensions are stale for {identity}")
        seen.add(identity)
    if seen != expected_frames:
        raise EvidenceError("compact semantic frame inventory is incomplete")
    return value


def copy_compact(*, input_root: Path, output: Path, matrix_path: Path, expected: dict[str, Any]) -> dict[str, Any]:
    manifest = validate_compact(input_root, matrix_path=matrix_path, expected=expected)
    inventory = _inventory(input_root)

    def writer(stage: Path) -> None:
        for relative in sorted(inventory):
            _, data = _child_file(input_root, relative, label="compact cache file", maximum=MAX_MANIFEST_BYTES if relative == "manifest.json" else MAX_DERIVATIVE_BYTES)
            _copy_bytes(stage / relative, data)
        validate_compact(stage, matrix_path=stage / "release-matrix.json", expected=expected)

    _atomic_directory(output, writer)
    return manifest


def _expected_args(args: argparse.Namespace) -> dict[str, Any]:
    expected = {
        "repository": args.repository,
        "branch": args.branch,
        "commit": args.commit,
        "tree": args.tree,
        "matrix_sha256": args.matrix_sha256,
        "contract_sha256": default_contract().sha256,
    }
    if getattr(args, "run_id", None) is not None:
        expected["handoff"] = {
            "path": E2E_WORKFLOW,
            "run_id": args.run_id,
            "run_attempt": args.run_attempt,
            "controller_branch": args.controller_branch,
            "controller_sha": args.controller_sha,
        }
    return expected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    curate_parser = sub.add_parser("curate")
    curate_parser.add_argument("--input", type=Path, required=True)
    curate_parser.add_argument("--output", type=Path, required=True)
    curate_parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    for name in ("repository", "branch", "commit", "tree"):
        curate_parser.add_argument(f"--{name}", required=True)
    curate_parser.add_argument("--run-id", type=int, required=True)
    curate_parser.add_argument("--run-attempt", type=int, required=True)
    curate_parser.add_argument("--controller-branch", required=True)
    curate_parser.add_argument("--controller-sha", required=True)
    curate_parser.add_argument("--packaged-run-id", type=int)
    curate_parser.add_argument("--packaged-run-attempt", type=int)
    curate_parser.add_argument("--packaged-branch")
    curate_parser.add_argument("--packaged-commit")
    curate_parser.add_argument("--packaged-tree")
    curate_parser.add_argument("--packaged-controller-branch")
    curate_parser.add_argument("--packaged-controller-sha")

    for command in ("validate-raw", "validate-compact", "compact", "copy-compact"):
        selected = sub.add_parser(command)
        selected.add_argument("--input", type=Path, required=True)
        selected.add_argument("--matrix", type=Path, required=True)
        for name in ("repository", "branch", "commit", "tree", "matrix_sha256"):
            selected.add_argument(f"--{name}", required=True)
        if command in {"compact", "copy-compact"}:
            selected.add_argument("--output", type=Path, required=True)
        if command in {"validate-raw", "compact"}:
            selected.add_argument("--run-id", type=int, required=True)
            selected.add_argument("--run-attempt", type=int, required=True)
            selected.add_argument("--controller-branch", required=True)
            selected.add_argument("--controller-sha", required=True)
        if command == "compact":
            selected.add_argument("--source-artifact-id", type=int, required=True)
            selected.add_argument("--source-artifact-name", required=True)
            selected.add_argument("--source-artifact-digest", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "curate":
            packaged_values = (
                args.packaged_run_id,
                args.packaged_run_attempt,
                args.packaged_branch,
                args.packaged_commit,
                args.packaged_tree,
                args.packaged_controller_branch,
                args.packaged_controller_sha,
            )
            if any(value is not None for value in packaged_values) and not all(
                value is not None for value in packaged_values
            ):
                raise EvidenceError("all packaged source identity arguments must be supplied together")
            result = curate(
                input_root=args.input,
                output=args.output,
                matrix_path=args.matrix,
                repository=args.repository,
                branch=args.branch,
                commit=args.commit,
                tree=args.tree,
                run_id=args.run_id,
                run_attempt=args.run_attempt,
                controller_branch=args.controller_branch,
                controller_sha=args.controller_sha,
                packaged_run_id=args.packaged_run_id,
                packaged_run_attempt=args.packaged_run_attempt,
                packaged_branch=args.packaged_branch,
                packaged_commit=args.packaged_commit,
                packaged_tree=args.packaged_tree,
                packaged_controller_branch=args.packaged_controller_branch,
                packaged_controller_sha=args.packaged_controller_sha,
            )
        else:
            expected = _expected_args(args)
            if args.command == "validate-raw":
                result = validate_raw(args.input, matrix_path=args.matrix, expected=expected)
            elif args.command == "validate-compact":
                result = validate_compact(args.input, matrix_path=args.matrix, expected=expected)
            elif args.command == "compact":
                result = compact(input_root=args.input, output=args.output, matrix_path=args.matrix, expected=expected, source_artifact_id=args.source_artifact_id, source_artifact_name=args.source_artifact_name, source_artifact_digest=args.source_artifact_digest)
            else:
                result = copy_compact(input_root=args.input, output=args.output, matrix_path=args.matrix, expected=expected)
        print(json.dumps({"kind": result["kind"], "branch": result["provenance"]["branch"], "commit": result["provenance"]["commit"], "lanes": len(result["lanes"]), "frames": len(result["frames"])}, sort_keys=True))
        return 0
    except (EvidenceError, MatrixError, OSError, ValueError) as exc:
        print(f"Pages evidence error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
