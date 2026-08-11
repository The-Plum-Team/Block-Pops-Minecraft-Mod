#!/usr/bin/env python3
"""Create and validate the durable lossless canonical visual anchor.

The anchor is deliberately smaller than the ordinary public-evidence handoff: it
contains only the canonical matrix lane's original PNG bytes and one strict
manifest.  It is suitable for later import only when the caller also supplies
the authenticated matrix and API-derived run/artifact identity.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from e2e.scenario_contract import default_contract  # noqa: E402
from e2e.visual_evidence import (  # noqa: E402
    VisualEvidenceError,
    canonicalize_png,
)
from scripts.pages.evidence import (  # noqa: E402
    E2E_WORKFLOW,
    MAX_SCREENSHOT_BYTES,
    REPOSITORY as REPOSITORY_PATTERN,
    SHA1,
    SHA256,
    EvidenceError,
    _atomic_directory,
    _canonical_path,
    _child_file,
    _copy_bytes,
    _digest,
    _inventory,
    _json,
    _object,
    _positive_int,
    _profile_name,
    _stable_bytes,
    _text,
    _write_json,
    branch_token,
    raw_artifact_name,
    sha256_bytes,
    validate_raw,
)
from scripts.release.matrix import MatrixError, load_matrix  # noqa: E402

ANCHOR_SCHEMA = 1
ANCHOR_KIND = "lossless-visual-anchor"
ANCHOR_MANIFEST = "visual-anchor.json"
ANCHOR_PREFIX = "visual-anchor-v1"
MAX_ANCHOR_MANIFEST_BYTES = 1024 * 1024
MAX_ANCHOR_FILES = 64
MAX_ANCHOR_BYTES = 128 * 1024 * 1024


# Reuse the Pages trust-boundary exception so imported strict helpers and this
# module expose one stable failure type to API and CLI callers.
VisualAnchorError = EvidenceError


def visual_anchor_artifact_name(
    branch: str, commit: str, run_id: int, run_attempt: int
) -> str:
    """Return the exact-head, exact-producer-attempt identity for one anchor."""

    _digest(commit, "anchor commit", SHA1)
    _positive_int(run_id, "anchor producer run id")
    _positive_int(run_attempt, "anchor producer run attempt")
    return visual_anchor_artifact_prefix(branch) + f"{commit}-{run_id}-{run_attempt}"


def visual_anchor_artifact_prefix(branch: str) -> str:
    """Return the branch-scoped prefix used for bounded retention rotation."""

    return f"{ANCHOR_PREFIX}-{branch_token(branch)}--"


def _normalized_artifact_digest(value: Any, label: str) -> str:
    digest = _text(value, label, maximum=71)
    if SHA256.fullmatch(digest):
        return f"sha256:{digest}"
    if digest.startswith("sha256:") and SHA256.fullmatch(digest[7:]):
        return digest
    raise VisualAnchorError(f"{label} must be one exact SHA-256 digest")


def _canonical_identity(
    matrix_path: Path, *, branch: str, commit: str
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    _text(branch, "anchor branch", maximum=240)
    _digest(commit, "anchor commit", SHA1)
    try:
        matrix = load_matrix(matrix_path, validate_sources=False)
    except MatrixError as exc:
        raise VisualAnchorError(str(exc)) from exc
    if matrix["branch"]["name"] != branch:
        raise VisualAnchorError("anchor branch differs from its authenticated matrix")
    reference = matrix["visual_reference"]
    runtime = next(
        (
            row
            for row in matrix["runtimes"]
            if row["artifact_node"] == reference["artifact_node"]
        ),
        None,
    )
    eligible = branch == reference["release_branch"] and runtime is not None
    identity = {
        "eligible": eligible,
        "branch": branch,
        "commit": commit,
        "artifact_node": reference["artifact_node"],
    }
    return matrix, runtime or {}, identity


def anchor_identity(
    matrix_path: Path,
    *,
    branch: str,
    commit: str,
    run_id: int,
    run_attempt: int,
) -> dict[str, Any]:
    """Project whether this branch owns the canonical lossless anchor."""

    _, _, canonical = _canonical_identity(
        matrix_path, branch=branch, commit=commit
    )
    identity = canonical.copy()
    identity["artifact"] = (
        visual_anchor_artifact_name(branch, commit, run_id, run_attempt)
        if identity["eligible"]
        else ""
    )
    return identity


def _strict_inventory(root: Path) -> dict[str, int]:
    files = _inventory(root)
    if len(files) > MAX_ANCHOR_FILES + 1 or sum(files.values()) > MAX_ANCHOR_BYTES:
        raise VisualAnchorError("visual anchor exceeds its file-count or byte limit")
    directories: set[str] = set()
    for directory, names, _ in os.walk(root, followlinks=False):
        parent = Path(directory)
        for name in names:
            relative = (parent / name).relative_to(root).as_posix()
            directories.add(_canonical_path(relative, "anchor directory"))
    if directories != {"images"}:
        raise VisualAnchorError("visual anchor directory inventory must be exactly images/")
    return files


def _source_run(value: Any, label: str, *, packaged: bool) -> dict[str, Any]:
    required = {
        "path",
        "run_id",
        "run_attempt",
        "controller_branch",
        "controller_sha",
    }
    if packaged:
        required |= {"branch", "commit", "tree"}
    run = _object(value, label, required)
    if run["path"] != E2E_WORKFLOW:
        raise VisualAnchorError(f"{label} names an unapproved workflow")
    _positive_int(run["run_id"], f"{label}.run_id")
    _positive_int(run["run_attempt"], f"{label}.run_attempt")
    _text(run["controller_branch"], f"{label}.controller_branch", maximum=240)
    _digest(run["controller_sha"], f"{label}.controller_sha", SHA1)
    if packaged:
        _text(run["branch"], f"{label}.branch", maximum=240)
        _digest(run["commit"], f"{label}.commit", SHA1)
        _digest(run["tree"], f"{label}.tree", SHA1)
    return run


def _file_records(root: Path, value: Any) -> dict[str, bytes]:
    if not isinstance(value, list) or not value:
        raise VisualAnchorError("anchor files must be a non-empty array")
    files: dict[str, bytes] = {}
    for index, raw in enumerate(value):
        record = _object(raw, f"files[{index}]", {"path", "sha256", "size"})
        relative = _canonical_path(record["path"], f"files[{index}].path")
        digest = _digest(record["sha256"], f"files[{index}].sha256")
        size = _positive_int(record["size"], f"files[{index}].size")
        if (
            relative in files
            or relative != f"images/{digest}.png"
            or size > MAX_SCREENSHOT_BYTES
        ):
            raise VisualAnchorError("anchor file identity is duplicate or non-canonical")
        _, data = _child_file(
            root,
            relative,
            label=f"anchor file {relative}",
            maximum=MAX_SCREENSHOT_BYTES,
        )
        if len(data) != size or sha256_bytes(data) != digest:
            raise VisualAnchorError(f"anchor file record is stale for {relative}")
        files[relative] = data
    inventory = _strict_inventory(root)
    if set(inventory) != set(files) | {ANCHOR_MANIFEST}:
        raise VisualAnchorError("anchor bundle inventory differs from its manifest")
    return files


def _validate_lanes(
    value: Any,
    *,
    runtime: dict[str, Any],
    scenarios: tuple[str, ...],
) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise VisualAnchorError("anchor lanes must be an array")
    lanes: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(value):
        lane = _object(
            raw,
            f"lanes[{index}]",
            {
                "artifact_node",
                "minecraft",
                "loader",
                "java",
                "scenario",
                "production_jar_sha256",
                "harness_jar_sha256",
            },
        )
        for key in ("artifact_node", "minecraft", "loader", "scenario"):
            _text(lane[key], f"lanes[{index}].{key}", maximum=240)
        _positive_int(lane["java"], f"lanes[{index}].java")
        if (
            lane["artifact_node"] != runtime["artifact_node"]
            or lane["minecraft"] != runtime["minecraft"]
            or lane["loader"] != runtime["loader"]
            or lane["java"] != runtime["java"]
            or lane["scenario"] not in scenarios
            or lane["scenario"] in seen
        ):
            raise VisualAnchorError("anchor lane identity is stale or duplicate")
        _digest(lane["production_jar_sha256"], "production jar sha256")
        _digest(lane["harness_jar_sha256"], "harness jar sha256")
        seen.add(lane["scenario"])
        lanes.append(lane)
    if seen != set(scenarios):
        raise VisualAnchorError("anchor lane inventory is incomplete")
    return lanes


def _expected_frames(contract: Any, scenarios: tuple[str, ...]) -> dict[str, Any]:
    captures = {
        capture.capture_id: capture
        for scenario in scenarios
        for role in contract.scenario(scenario).roles
        for step in role.steps
        if step.capture is not None
        for capture in [step.capture]
    }
    if len(captures) != sum(
        step.capture is not None
        for scenario in scenarios
        for role in contract.scenario(scenario).roles
        for step in role.steps
    ):
        raise VisualAnchorError("scenario contract repeats a semantic capture_id")
    return captures


def _validate_frames(
    value: Any,
    *,
    files: dict[str, bytes],
    runtime: dict[str, Any],
    contract: Any,
    scenarios: tuple[str, ...],
    root: Path,
) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise VisualAnchorError("anchor frames must be an array")
    expected = _expected_frames(contract, scenarios)
    seen: set[str] = set()
    referenced: set[str] = set()
    frames: list[dict[str, Any]] = []
    for index, raw in enumerate(value):
        frame = _object(
            raw,
            f"frames[{index}]",
            {
                "artifact_node",
                "minecraft",
                "loader",
                "scenario",
                "role",
                "step",
                "capture_id",
                "title",
                "expectation",
                "review_tier",
                "source",
            },
        )
        for key in (
            "artifact_node",
            "minecraft",
            "loader",
            "scenario",
            "role",
            "step",
            "capture_id",
            "title",
            "expectation",
            "review_tier",
        ):
            _text(frame[key], f"frames[{index}].{key}", maximum=2048)
        capture = expected.get(frame["capture_id"])
        if capture is None:
            raise VisualAnchorError("anchor frame has an unknown capture_id")
        if (
            (frame["scenario"], frame["role"], frame["step"])
            != (capture.scenario, capture.role, capture.step)
            or frame["capture_id"] in seen
            or frame["artifact_node"] != runtime["artifact_node"]
            or frame["minecraft"] != runtime["minecraft"]
            or frame["loader"] != runtime["loader"]
            or frame["scenario"] not in scenarios
            or frame["title"] != capture.title
            or frame["expectation"] != capture.expectation
            or frame["review_tier"] != capture.review_tier
        ):
            raise VisualAnchorError("anchor semantic frame identity is stale or duplicate")
        source = _object(
            frame["source"],
            f"frames[{index}].source",
            {
                "raw_path",
                "path",
                "sha256",
                "pixel_sha256",
                "size",
                "width",
                "height",
                "format",
            },
        )
        digest = _digest(source["sha256"], "frame source sha256")
        pixel_digest = _digest(source["pixel_sha256"], "frame pixel sha256")
        size = _positive_int(source["size"], "frame source size")
        width = _positive_int(source["width"], "frame source width")
        height = _positive_int(source["height"], "frame source height")
        path = _canonical_path(source["path"], "frame anchor path")
        raw_path = _canonical_path(source["raw_path"], "frame raw path")
        expected_raw_path = (
            f"profiles/{_profile_name(runtime['artifact_node'], runtime['minecraft'], frame['scenario'])}/"
            f"{frame['role']}/screenshots/{frame['capture_id']}.png"
        )
        data = files.get(path)
        if (
            data is None
            or path != f"images/{digest}.png"
            or raw_path != expected_raw_path
            or source["format"] != "PNG"
            or size != len(data)
            or (width, height) != contract.gui_text_reference_size
        ):
            raise VisualAnchorError("anchor frame source identity is stale")
        try:
            width, height, file_sha, actual_pixels, _, _, _ = canonicalize_png(
                root / path,
                expected_size=contract.gui_text_reference_size,
            )
        except VisualEvidenceError as exc:
            raise VisualAnchorError(str(exc)) from exc
        if (
            file_sha != digest
            or actual_pixels != pixel_digest
            or (width, height) != (source["width"], source["height"])
        ):
            raise VisualAnchorError("anchor frame file or pixel identity is stale")
        seen.add(frame["capture_id"])
        referenced.add(path)
        frames.append(frame)
    if seen != set(expected) or referenced != set(files):
        raise VisualAnchorError("anchor semantic/image inventory is incomplete")
    return frames


def create_anchor(
    *,
    input_root: Path,
    output: Path,
    matrix_path: Path,
    repository: str,
    branch: str,
    commit: str,
    tree: str,
    source_run_id: int,
    source_run_attempt: int,
    source_controller_branch: str,
    source_controller_sha: str,
    raw_artifact_id: int,
    raw_artifact_name_value: str,
    raw_artifact_digest: str,
) -> dict[str, Any]:
    """Create one atomic canonical anchor from already-curated raw evidence."""

    if REPOSITORY_PATTERN.fullmatch(_text(repository, "repository")) is None:
        raise VisualAnchorError("repository must use owner/name form")
    _digest(tree, "anchor tree", SHA1)
    _positive_int(source_run_id, "source run id")
    _positive_int(source_run_attempt, "source run attempt")
    _text(source_controller_branch, "source controller branch", maximum=240)
    _digest(source_controller_sha, "source controller SHA", SHA1)
    _positive_int(raw_artifact_id, "raw artifact id")
    matrix, runtime, identity = _canonical_identity(
        matrix_path, branch=branch, commit=commit
    )
    if not identity["eligible"]:
        raise VisualAnchorError("this branch/matrix does not own the canonical visual anchor")
    if source_controller_branch != branch or source_controller_sha != commit:
        raise VisualAnchorError(
            "canonical anchor controller must be the canonical published head"
        )
    expected_raw_name = raw_artifact_name(branch, source_run_attempt)
    if raw_artifact_name_value != expected_raw_name:
        raise VisualAnchorError("raw artifact name is not bound to the source run attempt")
    artifact_digest = _normalized_artifact_digest(
        raw_artifact_digest, "raw artifact digest"
    )
    matrix_bytes = _stable_bytes(
        matrix_path, label="authenticated anchor matrix", maximum=256 * 1024
    )
    matrix_sha = sha256_bytes(matrix_bytes)
    contract = default_contract()
    expected_provenance = {
        "repository": repository,
        "branch": branch,
        "commit": commit,
        "tree": tree,
        "matrix_sha256": matrix_sha,
        "contract_sha256": contract.sha256,
        "handoff": {
            "path": E2E_WORKFLOW,
            "run_id": source_run_id,
            "run_attempt": source_run_attempt,
            "controller_branch": source_controller_branch,
            "controller_sha": source_controller_sha,
        },
    }
    raw = validate_raw(
        input_root, matrix_path=matrix_path, expected=expected_provenance
    )
    expected_packaged = {
        "path": E2E_WORKFLOW,
        "run_id": source_run_id,
        "run_attempt": source_run_attempt,
        "branch": branch,
        "commit": commit,
        "tree": tree,
        "controller_branch": source_controller_branch,
        "controller_sha": source_controller_sha,
    }
    if raw["provenance"]["packaged"] != expected_packaged:
        raise VisualAnchorError(
            "canonical anchor must come from one direct exact-head packaged run"
        )
    scenarios = tuple(contract.scenarios_for_profile("release"))
    selected_lanes = [
        {
            key: lane[key]
            for key in (
                "artifact_node",
                "minecraft",
                "loader",
                "java",
                "scenario",
                "production_jar_sha256",
                "harness_jar_sha256",
            )
        }
        for lane in raw["lanes"]
        if lane["artifact_node"] == runtime["artifact_node"]
    ]
    images: dict[str, bytes] = {}
    frames: list[dict[str, Any]] = []
    for frame in raw["frames"]:
        if frame["artifact_node"] != runtime["artifact_node"]:
            continue
        source_path, data = _child_file(
            input_root,
            frame["source"]["path"],
            label=f"canonical source {frame['capture_id']}",
            maximum=MAX_SCREENSHOT_BYTES,
        )
        try:
            width, height, file_sha, pixel_sha, _, _, _ = canonicalize_png(
                source_path,
                expected_size=contract.gui_text_reference_size,
            )
        except VisualEvidenceError as exc:
            raise VisualAnchorError(str(exc)) from exc
        if (
            file_sha != frame["source"]["sha256"]
            or file_sha != sha256_bytes(data)
            or len(data) != frame["source"]["size"]
            or (width, height)
            != (frame["source"]["width"], frame["source"]["height"])
        ):
            raise VisualAnchorError("raw frame changed after strict public validation")
        image_path = f"images/{file_sha}.png"
        previous = images.setdefault(image_path, data)
        if previous != data:
            raise VisualAnchorError("source PNG digest collision")
        frames.append(
            {
                key: frame[key]
                for key in (
                    "artifact_node",
                    "minecraft",
                    "loader",
                    "scenario",
                    "role",
                    "step",
                    "capture_id",
                    "title",
                    "expectation",
                    "review_tier",
                )
            }
            | {
                "source": {
                    "raw_path": frame["source"]["path"],
                    "path": image_path,
                    "sha256": file_sha,
                    "pixel_sha256": pixel_sha,
                    "size": len(data),
                    "width": width,
                    "height": height,
                    "format": "PNG",
                }
            }
        )
    frames.sort(key=lambda row: (row["scenario"], row["role"], row["step"]))
    selected_lanes.sort(key=lambda row: row["scenario"])
    manifest = {
        "schema_version": ANCHOR_SCHEMA,
        "kind": ANCHOR_KIND,
        "provenance": {
            "repository": repository,
            "branch": branch,
            "commit": commit,
            "tree": tree,
            "matrix": {"branch": matrix["branch"]["name"], "sha256": matrix_sha},
            "contract": {
                "path": matrix["visual_reference"]["scenario_contract"],
                "schema_version": contract.schema_version,
                "sha256": contract.sha256,
            },
            "source_run": raw["provenance"]["handoff"],
            "packaged_run": raw["provenance"]["packaged"],
        },
        "reference": {
            "release_branch": matrix["visual_reference"]["release_branch"],
            "artifact_node": runtime["artifact_node"],
            "minecraft": runtime["minecraft"],
            "loader": runtime["loader"],
        },
        "source_artifact": {
            "id": raw_artifact_id,
            "name": raw_artifact_name_value,
            "digest": artifact_digest,
        },
        "lanes": selected_lanes,
        "frames": frames,
        "files": [
            {"path": path, "sha256": sha256_bytes(data), "size": len(data)}
            for path, data in sorted(images.items())
        ],
    }

    def writer(stage: Path) -> None:
        for relative, data in images.items():
            _copy_bytes(stage / relative, data)
        _write_json(stage / ANCHOR_MANIFEST, manifest)
        validate_anchor(
            stage,
            matrix_path=matrix_path,
            expected={
                **expected_provenance,
                "source_artifact": manifest["source_artifact"],
            },
        )

    _atomic_directory(output, writer)
    return manifest


def validate_anchor(
    root: Path,
    *,
    matrix_path: Path,
    expected: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Validate one anchor against an authenticated exact-commit matrix."""

    manifest, _ = _json(
        root / ANCHOR_MANIFEST,
        label="visual anchor manifest",
        maximum=MAX_ANCHOR_MANIFEST_BYTES,
    )
    value = _object(
        manifest,
        "visual anchor manifest",
        {
            "schema_version",
            "kind",
            "provenance",
            "reference",
            "source_artifact",
            "lanes",
            "frames",
            "files",
        },
    )
    _positive_int(value["schema_version"], "anchor schema_version")
    if value["schema_version"] != ANCHOR_SCHEMA or value["kind"] != ANCHOR_KIND:
        raise VisualAnchorError("visual anchor schema/kind is unsupported")
    provenance = _object(
        value["provenance"],
        "anchor provenance",
        {
            "repository",
            "branch",
            "commit",
            "tree",
            "matrix",
            "contract",
            "source_run",
            "packaged_run",
        },
    )
    if REPOSITORY_PATTERN.fullmatch(
        _text(provenance["repository"], "anchor repository")
    ) is None:
        raise VisualAnchorError("anchor repository is invalid")
    _text(provenance["branch"], "anchor branch", maximum=240)
    _digest(provenance["commit"], "anchor commit", SHA1)
    _digest(provenance["tree"], "anchor tree", SHA1)
    source_run = _source_run(provenance["source_run"], "source_run", packaged=False)
    packaged_run = _source_run(
        provenance["packaged_run"], "packaged_run", packaged=True
    )
    if (
        source_run["controller_branch"] != provenance["branch"]
        or source_run["controller_sha"] != provenance["commit"]
        or packaged_run
        != {
            "path": E2E_WORKFLOW,
            "run_id": source_run["run_id"],
            "run_attempt": source_run["run_attempt"],
            "branch": provenance["branch"],
            "commit": provenance["commit"],
            "tree": provenance["tree"],
            "controller_branch": source_run["controller_branch"],
            "controller_sha": source_run["controller_sha"],
        }
    ):
        raise VisualAnchorError(
            "anchor packaged run differs from its direct source/controller identity"
        )
    matrix_record = _object(
        provenance["matrix"], "anchor matrix", {"branch", "sha256"}
    )
    contract_record = _object(
        provenance["contract"],
        "anchor contract",
        {"path", "schema_version", "sha256"},
    )
    _text(matrix_record["branch"], "anchor matrix branch", maximum=240)
    _canonical_path(contract_record["path"], "anchor contract path")
    _positive_int(contract_record["schema_version"], "anchor contract schema_version")
    _digest(matrix_record["sha256"], "anchor matrix sha256")
    _digest(contract_record["sha256"], "anchor contract sha256")
    matrix, runtime, identity = _canonical_identity(
        matrix_path,
        branch=provenance["branch"],
        commit=provenance["commit"],
    )
    if not identity["eligible"]:
        raise VisualAnchorError("anchor matrix no longer identifies the canonical lane")
    matrix_bytes = _stable_bytes(
        matrix_path, label="authenticated anchor matrix", maximum=256 * 1024
    )
    contract = default_contract()
    if (
        matrix_record["branch"] != provenance["branch"]
        or matrix_record["sha256"] != sha256_bytes(matrix_bytes)
        or contract_record["path"]
        != matrix["visual_reference"]["scenario_contract"]
        or contract_record["schema_version"] != contract.schema_version
        or contract_record["sha256"] != contract.sha256
    ):
        raise VisualAnchorError("anchor matrix/contract identity is stale")
    reference = _object(
        value["reference"],
        "anchor reference",
        {"release_branch", "artifact_node", "minecraft", "loader"},
    )
    if reference != {
        "release_branch": matrix["visual_reference"]["release_branch"],
        "artifact_node": runtime["artifact_node"],
        "minecraft": runtime["minecraft"],
        "loader": runtime["loader"],
    }:
        raise VisualAnchorError("anchor reference lane is stale")
    source_artifact = _object(
        value["source_artifact"],
        "anchor source artifact",
        {"id", "name", "digest"},
    )
    _positive_int(source_artifact["id"], "source artifact id")
    _text(source_artifact["name"], "source artifact name", maximum=240)
    if source_artifact["name"] != raw_artifact_name(
        provenance["branch"], source_run["run_attempt"]
    ):
        raise VisualAnchorError("anchor source artifact name is stale")
    if source_artifact["digest"] != _normalized_artifact_digest(
        source_artifact["digest"], "source artifact digest"
    ):
        raise VisualAnchorError("anchor source artifact digest is not canonical")
    if expected:
        expected_values = {
            "repository": provenance["repository"],
            "branch": provenance["branch"],
            "commit": provenance["commit"],
            "tree": provenance["tree"],
            "matrix_sha256": matrix_record["sha256"],
            "contract_sha256": contract_record["sha256"],
        }
        for key, actual in expected_values.items():
            if key in expected and expected[key] != actual:
                raise VisualAnchorError(f"expected anchor {key} is stale")
        if "handoff" in expected and expected["handoff"] != source_run:
            raise VisualAnchorError("expected anchor source run is stale")
        if "source_artifact" in expected:
            expected_artifact = _object(
                expected["source_artifact"],
                "expected source artifact",
                {"id", "name", "digest"},
            ).copy()
            expected_artifact["digest"] = _normalized_artifact_digest(
                expected_artifact.get("digest"), "expected source artifact digest"
            )
            if expected_artifact != source_artifact:
                raise VisualAnchorError("expected anchor source artifact is stale")
    scenarios = tuple(contract.scenarios_for_profile("release"))
    lanes = _validate_lanes(value["lanes"], runtime=runtime, scenarios=scenarios)
    files = _file_records(root, value["files"])
    frames = _validate_frames(
        value["frames"],
        files=files,
        runtime=runtime,
        contract=contract,
        scenarios=scenarios,
        root=root,
    )
    if {lane["scenario"] for lane in lanes} != {frame["scenario"] for frame in frames}:
        raise VisualAnchorError("anchor frames do not cover every canonical lane scenario")
    return value


def _expected_from_args(args: argparse.Namespace) -> dict[str, Any]:
    expected = {
        "repository": args.repository,
        "branch": args.branch,
        "commit": args.commit,
        "tree": args.tree,
        "matrix_sha256": args.matrix_sha256,
        "contract_sha256": args.contract_sha256,
        "handoff": {
            "path": E2E_WORKFLOW,
            "run_id": args.source_run_id,
            "run_attempt": args.source_run_attempt,
            "controller_branch": args.source_controller_branch,
            "controller_sha": args.source_controller_sha,
        },
    }
    raw_identity = (
        args.raw_artifact_id,
        args.raw_artifact_name,
        args.raw_artifact_digest,
    )
    if any(value is not None for value in raw_identity):
        if not all(value is not None for value in raw_identity):
            raise VisualAnchorError(
                "raw artifact expected identity must be supplied all-or-none"
            )
        expected["source_artifact"] = {
            "id": args.raw_artifact_id,
            "name": args.raw_artifact_name,
            "digest": args.raw_artifact_digest,
        }
    return expected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    identity_parser = commands.add_parser("identity")
    identity_parser.add_argument("--matrix", type=Path, required=True)
    identity_parser.add_argument("--branch", required=True)
    identity_parser.add_argument("--commit", required=True)
    identity_parser.add_argument("--run-id", type=int, required=True)
    identity_parser.add_argument("--run-attempt", type=int, required=True)

    create_parser = commands.add_parser("create")
    create_parser.add_argument("--input", type=Path, required=True)
    create_parser.add_argument("--output", type=Path, required=True)
    create_parser.add_argument("--matrix", type=Path, required=True)
    for name in ("repository", "branch", "commit", "tree"):
        create_parser.add_argument(f"--{name}", required=True)
    create_parser.add_argument("--source-run-id", type=int, required=True)
    create_parser.add_argument("--source-run-attempt", type=int, required=True)
    create_parser.add_argument("--source-controller-branch", required=True)
    create_parser.add_argument("--source-controller-sha", required=True)
    create_parser.add_argument("--raw-artifact-id", type=int, required=True)
    create_parser.add_argument("--raw-artifact-name", required=True)
    create_parser.add_argument("--raw-artifact-digest", required=True)

    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--input", type=Path, required=True)
    validate_parser.add_argument("--matrix", type=Path, required=True)
    for name in (
        "repository",
        "branch",
        "commit",
        "tree",
        "matrix_sha256",
        "contract_sha256",
    ):
        validate_parser.add_argument(f"--{name.replace('_', '-')}", required=True)
    validate_parser.add_argument("--source-run-id", type=int, required=True)
    validate_parser.add_argument("--source-run-attempt", type=int, required=True)
    validate_parser.add_argument("--source-controller-branch", required=True)
    validate_parser.add_argument("--source-controller-sha", required=True)
    # The raw handoff normally expires before a 90-day anchor is imported.  If
    # its API record still exists, callers may add all three fields for a second
    # exact comparison; otherwise the protected anchor owner and strict embedded
    # record remain the trust boundary.
    validate_parser.add_argument("--raw-artifact-id", type=int)
    validate_parser.add_argument("--raw-artifact-name")
    validate_parser.add_argument("--raw-artifact-digest")

    args = parser.parse_args(argv)
    try:
        if args.command == "identity":
            result = anchor_identity(
                args.matrix,
                branch=args.branch,
                commit=args.commit,
                run_id=args.run_id,
                run_attempt=args.run_attempt,
            )
        elif args.command == "create":
            result = create_anchor(
                input_root=args.input,
                output=args.output,
                matrix_path=args.matrix,
                repository=args.repository,
                branch=args.branch,
                commit=args.commit,
                tree=args.tree,
                source_run_id=args.source_run_id,
                source_run_attempt=args.source_run_attempt,
                source_controller_branch=args.source_controller_branch,
                source_controller_sha=args.source_controller_sha,
                raw_artifact_id=args.raw_artifact_id,
                raw_artifact_name_value=args.raw_artifact_name,
                raw_artifact_digest=args.raw_artifact_digest,
            )
        else:
            result = validate_anchor(
                args.input,
                matrix_path=args.matrix,
                expected=_expected_from_args(args),
            )
        if args.command == "identity":
            print(json.dumps(result, sort_keys=True))
        else:
            print(
                json.dumps(
                    {
                        "kind": result["kind"],
                        "branch": result["provenance"]["branch"],
                        "commit": result["provenance"]["commit"],
                        "artifact_node": result["reference"]["artifact_node"],
                        "frames": len(result["frames"]),
                        "files": len(result["files"]),
                    },
                    sort_keys=True,
                )
            )
        return 0
    except (EvidenceError, MatrixError, OSError, ValueError) as exc:
        print(f"Visual anchor error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
