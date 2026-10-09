"""Block Pops' native glue for the mod-base Build adapter contract (``BUILD_ADAPTER_API = 1``).

The shared mod-base Build and Packaged E2E workflows plan, build, run and verify Block Pops
through four protected hooks (``derive_plan``, ``verify_target``/``verify_build``,
``derive_runtime``, ``verify_runtime``) and three candidate hooks (``policy``, ``build_target``,
``run_lane``); ``scripts/ci/mod_base_build_dispatch.py`` is the one program the kit runs for each
of them. This module holds what the protected hooks know about Block Pops. It adds no policy of
its own: every decision is the native code's.

* **Plan.** The release matrix decides everything. Lanes are the matrix's artifact nodes in
  ``scripts/release/build_matrix.py`` ``plan_build`` order (numeric Minecraft version, then
  loader) for the matrix's own default scope; a target is the lanes that share a Minecraft
  version, and its id is that version. Each lane stages, exactly as the native lane-scoped
  ``verify_release.py --artifact-node`` does, its production JAR under ``files/``, its harness
  JAR under ``harness/`` and its schema-3 manifest, plus the lane-scoped
  ``build-matrix-report.json`` the native serial runner writes; the two reports carry the target
  and the lane in their path (``targets/<target>/lanes/<lane>/``) because plan paths are unique
  across the whole Build.
* **Runtime.** A lane's values are its native ``pr-anchors`` row (``scripts/release/matrix.py``
  ``gha_matrix``) and that row's scenario list. ``scheduled-anchors`` selects the same rows and
  scenarios (the matrix rejects anything else), so the projection is fixed.
* **Verification.** The validator has the protected adapter copy, the candidate's matrix and
  scenario contract, and the sealed exports, but no checkout. It lays the sealed files of one lane
  out the way the native stage has them, under a private directory of its home, and calls the
  native verifiers with the commit and tree the plan authenticated: ``verify_scoped_staged``
  (archives, embedded build identity, manifest), ``read_lane_build_evidence`` (the build report)
  and ``e2e_fanin.validate_lane`` (packaged results). The source-route inventory needs the source
  tree, which only the candidate's build has; the native runner checked it there, and the tested
  commit and tree the report binds authenticate it.

Two native modules read files of the checkout they live in: ``e2e/scenario_contract.py`` loads
``e2e/scenario-contract.json`` beside itself as the default contract, and the matrix loaders inspect
the source routes below the matrix's repository. :func:`bind_contract` points the default contract
at the bytes the hook was given and :func:`without_source_tree` turns the route inspection off for
the duration of one verification; both are restored afterwards.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
for _entry in (str(ROOT), str(ROOT / "e2e")):
    if _entry not in sys.path:
        sys.path.insert(0, _entry)

import e2e.scenario_contract as packaged_contract  # noqa: E402
from scripts.lib.secure_json import SecureJsonError, canonical_json, loads  # noqa: E402
from scripts.release import matrix as native_matrix  # noqa: E402
from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES,
    MatrixDocument,
    MatrixError,
    normalize_matrix_inventory,
)

BUILD_ADAPTER_API = 1
PROJECTION = "pr-anchors"
STAGE = "build/release"
MANIFEST = "artifacts.json"
BUILD_REPORT = "build-matrix-report.json"
MAX_CONTRACT_BYTES = packaged_contract.MAX_CONTRACT_BYTES
SUPPORTED_JAVA = frozenset({17, 21, 25})
SUPPORTED_GRADLE_JAVA = frozenset({21, 25})
TARGET_CONTRACT = "blockpops-ci-target-v1"
LANE_CONTRACT = "blockpops-ci-lane-v1"


class AdapterError(ValueError):
    """The inputs do not describe a Block Pops plan, or a native verifier rejected an export."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encode(value: Any) -> bytes:
    """Canonical JSON with one final newline: what the kit requires of every report."""

    return canonical_json(value) + b"\n"


def decode(data: bytes, label: str, *, max_bytes: int = 4 * 1024 * 1024) -> Any:
    """Strict JSON: UTF-8, no duplicate key, no non-finite number, bounded."""

    try:
        return loads(data, label=label, max_bytes=max_bytes)
    except SecureJsonError as error:
        raise AdapterError(str(error)) from error


def bind_contract(path: Path) -> packaged_contract.ScenarioContract:
    """Make ``path`` the default scenario contract of both names the native code imports the
    contract module under (``e2e.scenario_contract`` and, through ``scripts/release/matrix.py``,
    ``scenario_contract``), and return it loaded.

    Call it before anything imports ``e2e.packaged_runtime``, which loads the default contract
    when it is imported."""

    modules = {id(module): module for module in (packaged_contract, sys.modules.get("scenario_contract"))
               if module is not None}
    for module in modules.values():
        module.DEFAULT_CONTRACT = Path(path)
        module.default_contract.cache_clear()
    contract = packaged_contract.load_contract(Path(path))
    for module in modules.values():
        if module.default_contract().sha256 != contract.sha256:
            raise AdapterError("the scenario contract could not be bound")
    return contract


@contextmanager
def without_source_tree() -> Iterator[None]:
    """Inside the block, the native matrix loaders validate configuration without inspecting the
    source routes below the matrix's repository (the validator has no source tree)."""

    from scripts.release import build_evidence, build_matrix

    original_load = build_evidence.load_matrix_document
    original_normalize = build_matrix.normalize_matrix_inventory

    def load_document(path: Path, *, validate_sources: bool = True) -> MatrixDocument:
        del validate_sources
        return original_load(path, validate_sources=False)

    def normalize(data: Any, *, contract: Any = None, repository: Path | None = None) -> Any:
        del repository
        return original_normalize(data, contract=contract, repository=None)

    build_evidence.load_matrix_document = load_document
    build_matrix.normalize_matrix_inventory = normalize
    try:
        yield
    finally:
        build_evidence.load_matrix_document = original_load
        build_matrix.normalize_matrix_inventory = original_normalize


def load_document(matrix_bytes: bytes, contract: packaged_contract.ScenarioContract) -> MatrixDocument:
    """The release matrix as the native consumers read it, without inspecting source routes."""

    data = decode(matrix_bytes, "release matrix", max_bytes=MAX_MATRIX_BYTES)
    try:
        return MatrixDocument(normalize_matrix_inventory(data, contract=contract), json.dumps(data))
    except MatrixError as error:
        raise AdapterError(f"release matrix: {error}") from error


def numeric_version(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def lanes(document: MatrixDocument) -> tuple[Any, ...]:
    """Every lane the native Build compiles, in its order: the matrix's default scope (the scope
    ``scripts/ci/matrix_scope.py`` gives the native gate) sorted like ``build_matrix.plan_build``."""

    selected = sorted(document.select_lanes(scope=document.default_scope),
                      key=lambda lane: (numeric_version(lane.identity.minecraft), lane.identity.loader))
    if {lane.gradle_java for lane in selected} - SUPPORTED_GRADLE_JAVA or len({lane.gradle_java for lane in selected}) != 1:
        raise AdapterError("the selected lanes must share one Gradle Java of 21 or 25")
    for lane in selected:
        if lane.artifact["java"] not in SUPPORTED_JAVA or lane.runtime["java"] not in SUPPORTED_JAVA:
            raise AdapterError(f"lane {lane.identity.artifact_node} needs a Java the kit does not install")
    return tuple(selected)


def targets(document: MatrixDocument) -> dict[str, tuple[Any, ...]]:
    """Target id (the Minecraft version) to its lanes, in Build order."""

    grouped: dict[str, list[Any]] = {}
    for lane in lanes(document):
        grouped.setdefault(lane.identity.minecraft, []).append(lane)
    return {target: tuple(members) for target, members in grouped.items()}


def lane_paths(target_id: str, lane: Any) -> dict[str, str]:
    """The export paths of one lane's outputs, by kind."""

    node = lane.identity.artifact_node
    reports = f"targets/{target_id}/lanes/{node}"
    return {
        "production": f"files/{PurePosixPath(lane.production_jar).name}",
        "harness": f"harness/{PurePosixPath(lane.harness_jar).name}",
        "manifest": f"{reports}/{MANIFEST}",
        "report": f"{reports}/{BUILD_REPORT}",
    }


def target_outputs(target_id: str, members: tuple[Any, ...]) -> list[dict[str, Any]]:
    outputs = []
    for lane in members:
        node = lane.identity.artifact_node
        paths = lane_paths(target_id, lane)
        outputs.extend([
            {"path": paths["production"], "lane_id": node, "role": "production"},
            {"path": paths["harness"], "lane_id": node, "role": "harness"},
            {"path": paths["manifest"], "lane_id": node, "role": "native-report"},
            {"path": paths["report"], "lane_id": node, "role": "native-report"},
        ])
    return outputs


def _lane_configuration(lane: Any) -> dict[str, Any]:
    return {
        "artifact_node": lane.identity.artifact_node, "artifact": lane.artifact, "runtime": lane.runtime,
        "mod_version": lane.mod_version, "build_layout": lane.build_layout, "gradle_java": lane.gradle_java,
        "repository_family": lane.repository_family, "source_routes": list(lane.source_routes),
        "production_jar": lane.production_jar, "harness_jar": lane.harness_jar,
    }


def target_contract(document: MatrixDocument, target_id: str, members: tuple[Any, ...]) -> str:
    """What a target's Build depends on in the matrix: every field of its lanes and the scope."""

    return sha256(encode({"contract": TARGET_CONTRACT, "target": target_id, "scope": document.default_scope,
                          "migration_mode": document.inventory.migration_mode,
                          "lanes": [_lane_configuration(lane) for lane in members]}))


def runtime_row(document: MatrixDocument, contract: Any, node: str) -> dict[str, Any]:
    """The native ``pr-anchors`` row of one lane, exactly as ``matrix.py --kind pr-anchors`` emits it."""

    rows = [row for row in document.projection(PROJECTION, contract=contract, scope=document.default_scope)["include"]
            if row["artifact_node"] == node]
    if len(rows) != 1:
        raise AdapterError(f"lane {node} has no single {PROJECTION} row")
    return rows[0]


def lane_contract(document: MatrixDocument, contract: Any, node: str) -> str:
    return sha256(encode({"contract": LANE_CONTRACT, "projection": PROJECTION,
                          "scenario_contract_sha256": contract.sha256,
                          "row": runtime_row(document, contract, node)}))


def derive_plan(document: MatrixDocument, contract: Any) -> dict[str, Any]:
    """The kit's ``plan.json`` (``{"targets": [...], "lanes": [...]}``) for this matrix."""

    planned_targets, planned_lanes = [], []
    for target_id, members in targets(document).items():
        majors = {lane.artifact["java"] for lane in members}
        if len(majors) != 1:
            raise AdapterError(f"target {target_id} mixes compile Java majors")
        planned_targets.append({"id": target_id, "java": majors.pop(),
                                "native_contract_sha256": target_contract(document, target_id, members),
                                "outputs": target_outputs(target_id, members)})
        for lane in members:
            node = lane.identity.artifact_node
            row = runtime_row(document, contract, node)
            planned_lanes.append({"id": node, "target_id": target_id,
                                  "native_contract_sha256": lane_contract(document, contract, node),
                                  "obligations": row["scenarios"].split(",")})
    return {"targets": planned_targets, "lanes": planned_lanes}


def runtime_values(document: MatrixDocument, contract: Any, node: str) -> dict[str, str]:
    row = runtime_row(document, contract, node)
    return {"E2E_ROW_JSON": canonical_json(row).decode("utf-8"), "E2E_SCENARIOS": row["scenarios"]}


def target_lanes(document: MatrixDocument, target_id: str) -> tuple[Any, ...]:
    members = targets(document).get(target_id)
    if not members:
        raise AdapterError(f"the matrix has no target {target_id!r}")
    return members


def lane_target(document: MatrixDocument, node: str) -> tuple[str, Any]:
    for target_id, members in targets(document).items():
        for lane in members:
            if lane.identity.artifact_node == node:
                return target_id, lane
    raise AdapterError(f"the matrix has no lane {node!r}")


def _write_new(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "xb") as stream:
        stream.write(data)


def _native_stage(workspace: Path, *, matrix_bytes: bytes, contract_bytes: bytes, target_id: str, lane: Any,
                  read: Callable[[str], bytes]) -> Path:
    """Lay one lane's sealed files out as the native lane-scoped stage: a new directory holding
    the matrix and the contract at their repository paths, ``build/release/`` with the two JARs and
    the manifest, and the build report at ``build/build-matrix-report.json``."""

    repository = workspace / lane.identity.artifact_node
    repository.mkdir(parents=True)
    paths = lane_paths(target_id, lane)
    _write_new(repository / "release" / "release-matrix.json", matrix_bytes)
    _write_new(repository / "e2e" / "scenario-contract.json", contract_bytes)
    stage = repository / STAGE
    for kind in ("production", "harness"):
        _write_new(stage / paths[kind], read(paths[kind]))
    _write_new(stage / MANIFEST, read(paths["manifest"]))
    _write_new(repository / "build" / BUILD_REPORT, read(paths["report"]))
    return repository


def verify_lane_build(workspace: Path, *, matrix_bytes: bytes, contract_bytes: bytes, target_id: str, lane: Any,
                      tested_sha: str, tested_tree: str, read: Callable[[str], bytes]) -> dict[str, Any]:
    """Verify one lane's sealed Build outputs with the native verifiers and return its record and
    its verified schema-3 manifest. The native stage is removed again afterwards."""

    from scripts.release.artifact_manifest import ArtifactError, verify_scoped_staged
    from scripts.release.build_evidence import BuildEvidenceError, read_lane_build_evidence

    node = lane.identity.artifact_node
    repository = _native_stage(workspace, matrix_bytes=matrix_bytes, contract_bytes=contract_bytes,
                               target_id=target_id, lane=lane, read=read)
    try:
        matrix_path = repository / "release" / "release-matrix.json"
        report_path = repository / "build" / BUILD_REPORT
        report_bytes = report_path.read_bytes()
        with without_source_tree():
            manifest = verify_scoped_staged(
                repository=repository, matrix_path=matrix_path, manifest_path=repository / STAGE / MANIFEST,
                stage=repository / STAGE, scope="lane", artifact_node=node,
                authenticated_source=(tested_sha, tested_tree))
            evidence = read_lane_build_evidence(report_path, matrix_path=matrix_path, artifact_node=node,
                                                artifact_manifest=manifest, expected_sha256=sha256(report_bytes))
        if (manifest["git_commit"], manifest["git_tree"]) != (tested_sha, tested_tree):
            raise AdapterError(f"lane {node} was not built from the tested commit")
        paths = lane_paths(target_id, lane)
        record = {
            "artifact_node": node, "build_identity": evidence["build_identity"], "build_run_id": evidence["run_id"],
            "build_report_sha256": evidence["report_sha256"],
            "manifest_sha256": sha256(read(paths["manifest"])),
            "production": evidence["production"], "harness": evidence["harness"],
        }
        return {"record": record, "manifest": manifest}
    except (ArtifactError, BuildEvidenceError, MatrixError, OSError, KeyError, TypeError) as error:
        raise AdapterError(f"lane {node}: {error}") from error
    finally:
        shutil.rmtree(repository, ignore_errors=True)


def verify_lane_runtime(*, matrix_path: Path, contract_path: Path, document: MatrixDocument, contract: Any,
                        node: str, row: dict[str, Any], manifest: dict[str, Any], results: Path) -> dict[str, Any]:
    """Revalidate one lane's sealed packaged results with ``e2e_fanin.validate_lane``, the native
    validator, against the lane's verified manifest."""

    from e2e.packaged_runtime import RuntimeFailure
    from scripts.ci import e2e_fanin

    if canonical_json(row) != canonical_json(runtime_row(document, contract, node)):
        raise AdapterError(f"lane {node}: the runtime row is not the lane's {PROJECTION} row")
    try:
        return e2e_fanin.validate_lane(root=results, matrix_path=matrix_path, contract_path=contract_path,
                                       projection=PROJECTION, row=row, artifact_manifest=manifest,
                                       scope="lane", artifact_node=node)
    except (e2e_fanin.FanInError, RuntimeFailure, MatrixError, SecureJsonError, OSError,
            packaged_contract.ScenarioContractError) as error:
        raise AdapterError(f"lane {node}: {error}") from error
