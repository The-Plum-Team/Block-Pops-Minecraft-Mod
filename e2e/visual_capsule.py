#!/usr/bin/env python3
"""Create and validate the immutable, secret-bearing visual-review handoff.

Creation belongs on a secretless runner.  A later credential-bearing runner should
receive only this directory, call :func:`validate_capsule`, and expose the returned
read-only review records and content-addressed images to the model.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

from e2e.scenario_contract import ScenarioContract
from e2e.visual_evidence import (
    ALLOWED_EVENTS,
    ATTESTATION_KEYS,
    SHA1,
    SHA256,
    SAFE_ID,
    SAFE_REPOSITORY,
    SAFE_WORKFLOW,
    EvidenceBundle,
    VisualEvidenceError,
    VisualFrame,
    _read_regular_bytes,
    canonical_reference_identity,
    canonicalize_png,
)
from scripts.lib.secure_json import (
    SecureJsonError,
    canonical_json,
    read as read_secure_json,
    require_object,
)
from scripts.release.matrix import valid_branch_name


CAPSULE_MANIFEST = "visual-capsule.json"
CAPSULE_DIGEST = "visual-capsule.sha256"
CAPSULE_PURPOSE = "advisory-semantic-ui-review"
MAX_CAPSULE_PAIRS = 512
MAX_CAPSULE_IMAGES = 1024
MAX_CAPSULE_IMAGE_BYTES = 32 * 1024 * 1024
MAX_CAPSULE_TOTAL_BYTES = 480 * 1024 * 1024
MAX_CAPSULE_MANIFEST_BYTES = 4 * 1024 * 1024
CAPSULE_KEYS = frozenset(
    {
        "schema_version",
        "purpose",
        "advisory",
        "contract_sha256",
        "canonical_reference",
        "candidate_source",
        "reference_source",
        "pairs",
        "inventory",
    }
)
PAIR_KEYS = frozenset(
    {
        "label",
        "capture_id",
        "title",
        "expectation",
        "review_tier",
        "candidate",
        "reference",
    }
)
FRAME_KEYS = frozenset(
    {
        "artifact_node",
        "minecraft",
        "loader",
        "scenario",
        "role",
        "step",
        "capture_id",
        "label",
        "path",
        "source_file_sha256",
        "pixel_sha256",
        "file_sha256",
        "width",
        "height",
        "source_artifact_id",
    }
)
INVENTORY_KEYS = frozenset({"path", "sha256", "size"})
REFERENCE_KEYS = frozenset({"release_branch", "artifact_node", "scenario_contract"})
CONTENT_IMAGE = re.compile(r"^images/(?P<digest>[0-9a-f]{64})\.png$")
CAPTURE_ID = re.compile(r"^[a-z][a-z0-9_-]*\.[a-z][a-z0-9_-]*\.[a-z][a-z0-9_-]*$")
SAFE_TEXT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,159}$")


def _fail(message: str) -> None:
    raise VisualEvidenceError(message)


def _text(value: Any, label: str, *, maximum: int) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        _fail(f"{label} must be a trimmed printable string of at most {maximum} characters")
    return value


def _frame_record(frame: VisualFrame, path: str) -> dict[str, Any]:
    return {
        "artifact_node": frame.artifact_node,
        "minecraft": frame.minecraft,
        "loader": frame.loader,
        "scenario": frame.scenario,
        "role": frame.role,
        "step": frame.step,
        "capture_id": frame.capture_id,
        "label": frame.label,
        "path": path,
        "source_file_sha256": frame.source_file_sha256,
        "pixel_sha256": frame.pixel_sha256,
        "file_sha256": frame.canonical_file_sha256,
        "width": frame.width,
        "height": frame.height,
        "source_artifact_id": frame.source_artifact_id,
    }


def _source_record(provenance: dict[str, Any]) -> dict[str, Any]:
    if set(provenance) != set(ATTESTATION_KEYS):
        _fail("validated source provenance schema changed before capsule creation")
    # Round-trip through canonical JSON so no mutable nested value is retained.
    return json.loads(canonical_json(provenance).decode("utf-8"))


def _validate_bundle_identity(bundle: EvidenceBundle, label: str) -> None:
    if bundle.provenance["matrix_sha256"] != bundle.matrix_sha256:
        _fail(f"{label} provenance does not bind its exact release matrix")
    if bundle.provenance["contract_sha256"] != bundle.contract.sha256:
        _fail(f"{label} provenance does not bind its exact scenario contract")
    if bundle.provenance["base_branch"] != bundle.matrix["branch"]["name"]:
        _fail(f"{label} base branch disagrees with its release matrix")


def _protected_scenarios(contract: ScenarioContract) -> set[str]:
    release = set(contract.scenarios_for_profile("release"))
    pull_request = set(contract.scenarios_for_profile("pr"))
    if not release or release != pull_request:
        _fail("scenario contract does not provide equal protected PR/release coverage")
    return release


def build_capsule_manifest(
    candidate: EvidenceBundle,
    reference: EvidenceBundle,
) -> tuple[dict[str, Any], dict[str, bytes]]:
    """Pair every protected candidate capture 1:1 with the current canonical baseline."""

    _validate_bundle_identity(candidate, "candidate")
    _validate_bundle_identity(reference, "reference")
    if candidate.contract.sha256 != reference.contract.sha256:
        _fail("candidate and reference use different scenario contracts")
    candidate_anchor = canonical_reference_identity(candidate.matrix)
    reference_anchor = canonical_reference_identity(reference.matrix)
    if candidate_anchor != reference_anchor:
        _fail("candidate and reference matrices disagree on the canonical visual anchor")
    reference_source = reference.provenance
    if (
        reference.matrix["branch"]["role"] != "integration"
        or reference_source["base_branch"] != reference_anchor["release_branch"]
        or reference_source["source_head_branch"] != reference_anchor["release_branch"]
        or reference_source["source_head_repository"] != reference_source["repository"]
        or reference_source["source_head_commit"] != reference_source["tested_commit"]
        or reference_source["event"] not in {"push", "schedule", "workflow_dispatch"}
    ):
        _fail("visual reference is not authenticated current-head evidence from protected master")
    protected_scenarios = _protected_scenarios(candidate.contract)
    if _protected_scenarios(reference.contract) != protected_scenarios:
        _fail("candidate/reference protected scenario coverage differs")
    candidate_nodes = {row["artifact_node"] for row in candidate.matrix["runtimes"]}
    if set(candidate.provenance["artifact_nodes"]) != candidate_nodes:
        _fail("candidate visual evidence does not cover every branch-matrix runtime lane")
    if set(candidate.provenance["scenarios"]) != protected_scenarios:
        _fail("candidate visual evidence does not cover every protected scenario")
    reference_node = reference_anchor["artifact_node"]
    if reference_node not in reference.provenance["artifact_nodes"]:
        _fail("reference artifact does not include the canonical baseline lane")
    if set(reference.provenance["scenarios"]) != protected_scenarios:
        _fail("reference visual evidence does not cover every protected scenario")

    baseline_frames = [
        frame
        for frame in reference.frames
        if frame.artifact_node == reference_node and frame.scenario in protected_scenarios
    ]
    baseline_by_capture: dict[str, VisualFrame] = {}
    for frame in baseline_frames:
        if frame.capture_id in baseline_by_capture:
            _fail(f"canonical baseline duplicates semantic capture {frame.capture_id!r}")
        baseline_by_capture[frame.capture_id] = frame
    expected_captures = {
        capture.capture_id
        for capture in reference.contract.captures
        if capture.scenario in protected_scenarios
    }
    if set(baseline_by_capture) != expected_captures:
        _fail(
            "canonical baseline semantic coverage is incomplete or mixed: "
            f"missing={sorted(expected_captures - set(baseline_by_capture))}, "
            f"extra={sorted(set(baseline_by_capture) - expected_captures)}"
        )
    candidate_frames = sorted(
        (
            frame
            for frame in candidate.frames
            if frame.artifact_node in candidate_nodes and frame.scenario in protected_scenarios
        ),
        key=lambda frame: frame.label,
    )
    expected_pair_count = len(candidate_nodes) * len(expected_captures)
    if not candidate_frames or len(candidate_frames) != expected_pair_count:
        _fail(
            "candidate semantic capture coverage is incomplete: "
            f"expected {expected_pair_count}, found {len(candidate_frames)}"
        )
    if len(candidate_frames) > MAX_CAPSULE_PAIRS:
        _fail(f"visual capsule exceeds its {MAX_CAPSULE_PAIRS}-pair limit")

    images: dict[str, bytes] = {}

    def content_path(frame: VisualFrame) -> str:
        path = f"images/{frame.canonical_file_sha256}.png"
        previous = images.setdefault(path, frame.canonical_png)
        if previous != frame.canonical_png or hashlib.sha256(previous).hexdigest() != frame.canonical_file_sha256:
            _fail("normalized image content-address collision")
        return path

    pairs: list[dict[str, Any]] = []
    seen_labels: set[str] = set()
    for frame in candidate_frames:
        baseline = baseline_by_capture.get(frame.capture_id)
        if baseline is None:
            _fail(f"canonical baseline is missing capture {frame.capture_id!r}")
        if (
            frame.capture_id != baseline.capture_id
            or frame.scenario != baseline.scenario
            or frame.role != baseline.role
            or frame.step != baseline.step
            or frame.title != baseline.title
            or frame.expectation != baseline.expectation
            or frame.review_tier != baseline.review_tier
        ):
            _fail(f"candidate/reference semantic identity skew for {frame.label}")
        if (frame.width, frame.height) != (baseline.width, baseline.height):
            _fail(f"candidate/reference dimensions are incompatible for {frame.label}")
        if frame.label in seen_labels:
            _fail(f"candidate visual frame label is duplicated: {frame.label}")
        seen_labels.add(frame.label)
        pairs.append(
            {
                "label": frame.label,
                "capture_id": frame.capture_id,
                "title": frame.title,
                "expectation": frame.expectation,
                "review_tier": frame.review_tier,
                "candidate": _frame_record(frame, content_path(frame)),
                "reference": _frame_record(baseline, content_path(baseline)),
            }
        )
    if len(images) > MAX_CAPSULE_IMAGES:
        _fail(f"visual capsule exceeds its {MAX_CAPSULE_IMAGES}-image limit")
    inventory = [
        {
            "path": path,
            "sha256": path.removeprefix("images/").removesuffix(".png"),
            "size": len(payload),
        }
        for path, payload in sorted(images.items())
    ]
    total = sum(item["size"] for item in inventory)
    if total > MAX_CAPSULE_TOTAL_BYTES or any(
        not 1 <= item["size"] <= MAX_CAPSULE_IMAGE_BYTES for item in inventory
    ):
        _fail("visual capsule images exceed their bounded handoff envelope")
    manifest = {
        "schema_version": 1,
        "purpose": CAPSULE_PURPOSE,
        "advisory": True,
        "contract_sha256": candidate.contract.sha256,
        "canonical_reference": candidate_anchor,
        "candidate_source": _source_record(candidate.provenance),
        "reference_source": _source_record(reference.provenance),
        "pairs": pairs,
        "inventory": inventory,
    }
    return manifest, images


def write_capsule(
    destination: Path,
    candidate: EvidenceBundle,
    reference: EvidenceBundle,
) -> str:
    """Atomically create a fresh exact-inventory capsule and return its manifest digest."""

    manifest, images = build_capsule_manifest(candidate, reference)
    manifest_bytes = canonical_json(manifest) + b"\n"
    if len(manifest_bytes) > MAX_CAPSULE_MANIFEST_BYTES:
        _fail("visual capsule manifest exceeds its byte limit")
    manifest_digest = hashlib.sha256(manifest_bytes).hexdigest()
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        _fail(f"visual capsule destination must be fresh: {destination}")
    try:
        parent_metadata = destination.parent.lstat()
    except OSError as exc:
        raise VisualEvidenceError(f"cannot inspect capsule parent: {exc}") from exc
    if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(parent_metadata.st_mode):
        _fail("visual capsule parent must be a real directory")
    staging = Path(
        tempfile.mkdtemp(prefix=f".{destination.name}.curating-", dir=destination.parent)
    )
    try:
        image_root = staging / "images"
        image_root.mkdir()
        for relative, payload in sorted(images.items()):
            output = staging / relative
            with output.open("xb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(output, 0o444)
        manifest_path = staging / CAPSULE_MANIFEST
        with manifest_path.open("xb") as stream:
            stream.write(manifest_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(manifest_path, 0o444)
        digest_path = staging / CAPSULE_DIGEST
        digest_payload = f"{manifest_digest}  {CAPSULE_MANIFEST}\n".encode("ascii")
        with digest_path.open("xb") as stream:
            stream.write(digest_payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(digest_path, 0o444)
        staging.rename(destination)
        return manifest_digest
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _validate_source_record(value: Any, label: str, contract_sha256: str) -> dict[str, Any]:
    try:
        source = require_object(value, label=label, required=ATTESTATION_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if (
        source["schema_version"] != 1
        or source["status"] != "completed"
        or source["conclusion"] != "success"
        or source["contract_sha256"] != contract_sha256
    ):
        _fail(f"{label} is not successful provenance for this capsule contract")
    for field in ("source_head_commit", "tested_commit", "tested_tree"):
        if not isinstance(source[field], str) or SHA1.fullmatch(source[field]) is None:
            _fail(f"{label}.{field} is invalid")
    for field in (
        "workflow_sha256",
        "job_graph_sha256",
        "artifact_sha256",
        "matrix_sha256",
        "contract_sha256",
    ):
        if not isinstance(source[field], str) or SHA256.fullmatch(source[field]) is None:
            _fail(f"{label}.{field} is invalid")
    for field in ("run_id", "run_attempt", "artifact_id"):
        if isinstance(source[field], bool) or not isinstance(source[field], int) or source[field] <= 0:
            _fail(f"{label}.{field} must be a positive integer")
    for field in ("repository", "source_head_repository"):
        if not isinstance(source[field], str) or SAFE_REPOSITORY.fullmatch(source[field]) is None:
            _fail(f"{label}.{field} is unsafe")
    for field in ("source_head_branch", "base_branch"):
        if not valid_branch_name(source[field]):
            _fail(f"{label}.{field} is not a safe Git branch")
    if not isinstance(source["workflow_path"], str) or SAFE_WORKFLOW.fullmatch(source["workflow_path"]) is None:
        _fail(f"{label}.workflow_path is unsafe")
    if source["event"] not in ALLOWED_EVENTS:
        _fail(f"{label}.event is unsupported")
    if source["event"] == "pull_request":
        if source["tested_commit"] == source["source_head_commit"]:
            _fail(f"{label} does not distinguish the tested PR merge from its source head")
    elif (
        source["source_head_repository"] != source["repository"]
        or source["tested_commit"] != source["source_head_commit"]
    ):
        _fail(f"{label} has inconsistent non-PR tested/source identity")
    if not isinstance(source["artifact_name"], str) or SAFE_ID.fullmatch(source["artifact_name"]) is None:
        _fail(f"{label}.artifact_name is unsafe")
    for field in ("artifact_nodes", "scenarios"):
        values = source[field]
        if (
            not isinstance(values, list)
            or not values
            or len(values) > 128
            or any(not isinstance(item, str) or SAFE_ID.fullmatch(item) is None for item in values)
            or values != sorted(set(values))
        ):
            _fail(f"{label}.{field} must be a sorted, bounded identifier inventory")
    return source


def _validate_frame(value: Any, label: str, capture_id: str) -> dict[str, Any]:
    try:
        frame = require_object(value, label=label, required=FRAME_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    for field in ("artifact_node", "minecraft", "loader", "scenario", "role", "step"):
        if SAFE_TEXT_ID.fullmatch(_text(frame[field], f"{label}.{field}", maximum=160)) is None:
            _fail(f"{label}.{field} is unsafe")
    if frame["capture_id"] != capture_id:
        _fail(f"{label}.capture_id disagrees with its pair")
    expected_capture = f"{frame['scenario']}.{frame['role']}.{frame['step']}"
    expected_label = f"{frame['artifact_node']}/{frame['scenario']}/{frame['role']}/{frame['step']}"
    if capture_id != expected_capture or frame["label"] != expected_label:
        _fail(f"{label} semantic identity is inconsistent")
    path = _text(frame["path"], f"{label}.path", maximum=80)
    match = CONTENT_IMAGE.fullmatch(path)
    if match is None or match.group("digest") != frame["file_sha256"]:
        _fail(f"{label} path is not content-addressed by its normalized digest")
    for field in ("source_file_sha256", "pixel_sha256", "file_sha256"):
        if not isinstance(frame[field], str) or SHA256.fullmatch(frame[field]) is None:
            _fail(f"{label}.{field} must be a lowercase SHA-256")
    for field in ("width", "height", "source_artifact_id"):
        if isinstance(frame[field], bool) or not isinstance(frame[field], int) or frame[field] <= 0:
            _fail(f"{label}.{field} must be a positive integer")
    if (
        frame["width"] < 640
        or frame["height"] < 360
        or frame["width"] * frame["height"] > 20_000_000
        or frame["width"] * 9 != frame["height"] * 16
    ):
        _fail(f"{label} dimensions/aspect are outside the review contract")
    return frame


def validate_capsule(root: Path) -> tuple[dict[str, Any], tuple[dict[str, Any], ...]]:
    """Authenticate the exact capsule inventory before any AI credential is exposed."""

    try:
        root_metadata = root.lstat()
    except OSError as exc:
        raise VisualEvidenceError(f"cannot inspect visual capsule {root}: {exc}") from exc
    if stat.S_ISLNK(root_metadata.st_mode) or not stat.S_ISDIR(root_metadata.st_mode):
        _fail("visual capsule root must be a real directory")
    observed_top = {path.name for path in root.iterdir()}
    if observed_top != {CAPSULE_MANIFEST, CAPSULE_DIGEST, "images"}:
        _fail("visual capsule top-level inventory is not exact")
    images_root = root / "images"
    images_metadata = images_root.lstat()
    if stat.S_ISLNK(images_metadata.st_mode) or not stat.S_ISDIR(images_metadata.st_mode):
        _fail("visual capsule images root must be a real directory")
    manifest_bytes = _read_regular_bytes(
        root / CAPSULE_MANIFEST,
        label="visual capsule manifest",
        maximum=MAX_CAPSULE_MANIFEST_BYTES,
    )
    digest_bytes = _read_regular_bytes(
        root / CAPSULE_DIGEST, label="visual capsule digest", maximum=256
    )
    manifest_digest = hashlib.sha256(manifest_bytes).hexdigest()
    if digest_bytes != f"{manifest_digest}  {CAPSULE_MANIFEST}\n".encode("ascii"):
        _fail("visual capsule manifest digest is missing or stale")
    try:
        manifest, raw = read_secure_json(
            root / CAPSULE_MANIFEST,
            label="visual capsule manifest",
            max_bytes=MAX_CAPSULE_MANIFEST_BYTES,
        )
        manifest = require_object(manifest, label="visual capsule", required=CAPSULE_KEYS)
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if raw != canonical_json(manifest) + b"\n":
        _fail("visual capsule manifest is not canonically encoded")
    if (
        manifest["schema_version"] != 1
        or manifest["purpose"] != CAPSULE_PURPOSE
        or manifest["advisory"] is not True
        or not isinstance(manifest["contract_sha256"], str)
        or SHA256.fullmatch(manifest["contract_sha256"]) is None
    ):
        _fail("visual capsule root identity is invalid")
    try:
        anchor = require_object(
            manifest["canonical_reference"],
            label="canonical_reference",
            required=REFERENCE_KEYS,
        )
    except SecureJsonError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if anchor != {
        "release_branch": "master",
        "artifact_node": "fabric-1.20.1",
        "scenario_contract": "e2e/scenario-contract.json",
    }:
        _fail("visual capsule does not use the protected master/fabric-1.20.1 anchor")
    candidate_source = _validate_source_record(
        manifest["candidate_source"], "candidate_source", manifest["contract_sha256"]
    )
    reference_source = _validate_source_record(
        manifest["reference_source"], "reference_source", manifest["contract_sha256"]
    )
    if (
        reference_source["base_branch"] != "master"
        or reference_source["source_head_branch"] != "master"
        or reference_source["source_head_repository"] != reference_source["repository"]
        or reference_source["source_head_commit"] != reference_source["tested_commit"]
    ):
        _fail("visual capsule reference is not current-head master provenance")
    if candidate_source["repository"] != reference_source["repository"]:
        _fail("visual capsule candidate/reference repositories are mixed")
    pairs = manifest["pairs"]
    if not isinstance(pairs, list) or not 1 <= len(pairs) <= MAX_CAPSULE_PAIRS:
        _fail("visual capsule pairs are empty or exceed their bound")
    normalized_pairs: list[dict[str, Any]] = []
    labels: set[str] = set()
    paths_from_pairs: set[str] = set()
    for index, raw_pair in enumerate(pairs):
        try:
            pair = require_object(raw_pair, label=f"pairs[{index}]", required=PAIR_KEYS)
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        label = _text(pair["label"], f"pairs[{index}].label", maximum=512)
        capture_id = _text(pair["capture_id"], f"pairs[{index}].capture_id", maximum=160)
        if CAPTURE_ID.fullmatch(capture_id) is None or label in labels:
            _fail(f"pairs[{index}] has an invalid or duplicate semantic identity")
        labels.add(label)
        _text(pair["title"], f"pairs[{index}].title", maximum=512)
        _text(pair["expectation"], f"pairs[{index}].expectation", maximum=4096)
        if pair["review_tier"] not in {"all", "key"}:
            _fail(f"pairs[{index}].review_tier is invalid")
        candidate_frame = _validate_frame(pair["candidate"], f"pairs[{index}].candidate", capture_id)
        reference_frame = _validate_frame(pair["reference"], f"pairs[{index}].reference", capture_id)
        if candidate_frame["label"] != label:
            _fail(f"pairs[{index}].label disagrees with candidate")
        for field in ("scenario", "role", "step", "capture_id", "width", "height"):
            if candidate_frame[field] != reference_frame[field]:
                _fail(f"pairs[{index}] candidate/reference {field} is incompatible")
        if reference_frame["artifact_node"] != "fabric-1.20.1":
            _fail(f"pairs[{index}] reference frame is not the canonical Fabric lane")
        if candidate_frame["source_artifact_id"] != candidate_source["artifact_id"]:
            _fail(f"pairs[{index}] candidate artifact provenance is mixed")
        if reference_frame["source_artifact_id"] != reference_source["artifact_id"]:
            _fail(f"pairs[{index}] reference artifact provenance is mixed")
        if (
            candidate_frame["artifact_node"] not in candidate_source["artifact_nodes"]
            or candidate_frame["scenario"] not in candidate_source["scenarios"]
            or reference_frame["artifact_node"] not in reference_source["artifact_nodes"]
            or reference_frame["scenario"] not in reference_source["scenarios"]
        ):
            _fail(f"pairs[{index}] frame inventory disagrees with source provenance")
        paths_from_pairs.update({candidate_frame["path"], reference_frame["path"]})
        normalized_pairs.append(pair)
    if [pair["label"] for pair in normalized_pairs] != sorted(labels):
        _fail("visual capsule pair ordering is not canonical")

    inventory = manifest["inventory"]
    if not isinstance(inventory, list) or not 1 <= len(inventory) <= MAX_CAPSULE_IMAGES:
        _fail("visual capsule image inventory is empty or excessive")
    inventory_paths: list[str] = []
    total_bytes = 0
    for index, raw_item in enumerate(inventory):
        try:
            item = require_object(raw_item, label=f"inventory[{index}]", required=INVENTORY_KEYS)
        except SecureJsonError as exc:
            raise VisualEvidenceError(str(exc)) from exc
        path = _text(item["path"], f"inventory[{index}].path", maximum=80)
        match = CONTENT_IMAGE.fullmatch(path)
        if (
            match is None
            or item["sha256"] != match.group("digest")
            or isinstance(item["size"], bool)
            or not isinstance(item["size"], int)
            or not 1 <= item["size"] <= MAX_CAPSULE_IMAGE_BYTES
        ):
            _fail(f"inventory[{index}] is not a bounded content-addressed image")
        inventory_paths.append(path)
        total_bytes += item["size"]
    if inventory_paths != sorted(set(inventory_paths)) or set(inventory_paths) != paths_from_pairs:
        _fail("visual capsule image inventory disagrees with semantic pairs")
    if total_bytes > MAX_CAPSULE_TOTAL_BYTES:
        _fail("visual capsule image inventory exceeds its total-byte bound")
    observed_images = {f"images/{path.name}" for path in images_root.iterdir()}
    if observed_images != set(inventory_paths):
        _fail("visual capsule on-disk image inventory is not exact")
    inventory_by_path = {item["path"]: item for item in inventory}
    for path in inventory_paths:
        source = root / PurePosixPath(path)
        metadata = source.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            _fail(f"visual capsule image is not a regular file: {path}")
        item = inventory_by_path[path]
        if metadata.st_size != item["size"]:
            _fail(f"visual capsule image size disagrees with inventory: {path}")
        referenced = [
            frame
            for pair in normalized_pairs
            for frame in (pair["candidate"], pair["reference"])
            if frame["path"] == path
        ]
        expected_sizes = {(frame["width"], frame["height"]) for frame in referenced}
        expected_pixels = {frame["pixel_sha256"] for frame in referenced}
        if len(expected_sizes) != 1 or len(expected_pixels) != 1:
            _fail(f"visual capsule content address has mixed image identity: {path}")
        expected_size = next(iter(expected_sizes))
        (
            width,
            height,
            source_digest,
            pixel_digest,
            canonical_digest,
            canonical_png,
            _metrics,
        ) = canonicalize_png(source, expected_size=expected_size)
        if (
            source_digest != item["sha256"]
            or canonical_digest != item["sha256"]
            or canonical_png != _read_regular_bytes(
                source, label="canonical capsule image", maximum=MAX_CAPSULE_IMAGE_BYTES
            )
            or pixel_digest != next(iter(expected_pixels))
        ):
            _fail(f"visual capsule image is corrupt or not canonically normalized: {path}")
    return manifest, tuple(normalized_pairs)


def read_only_review_records(root: Path) -> tuple[dict[str, Any], ...]:
    """Return only the bounded semantic prompt surface after capsule validation."""

    _manifest, pairs = validate_capsule(root)
    return tuple(
        {
            "label": pair["label"],
            "capture_id": pair["capture_id"],
            "title": pair["title"],
            "expectation": pair["expectation"],
            "candidate_path": pair["candidate"]["path"],
            "reference_path": pair["reference"]["path"],
        }
        for pair in pairs
    )


def read_only_capsule_image(root: Path, relative_path: str) -> bytes:
    """Return one approved image, revalidating the capsule and digest on every read."""

    manifest, _pairs = validate_capsule(root)
    if not isinstance(relative_path, str) or CONTENT_IMAGE.fullmatch(relative_path) is None:
        _fail("requested capsule image path is not a content-addressed PNG")
    inventory = {item["path"]: item for item in manifest["inventory"]}
    item = inventory.get(relative_path)
    if item is None:
        _fail("requested capsule image is outside the immutable inventory")
    payload = _read_regular_bytes(
        root / PurePosixPath(relative_path),
        label="read-only capsule image",
        maximum=MAX_CAPSULE_IMAGE_BYTES,
    )
    if len(payload) != item["size"] or hashlib.sha256(payload).hexdigest() != item["sha256"]:
        _fail("requested capsule image changed after validation")
    return payload
