"""Block Pops adapter for the pinned mod-base kit (``ADAPTER_API = 1``).

The kit owns public-evidence selection, authentication, compaction, rendering and rotation. This
module is the only Block Pops code the kit runs for Pages: it tells the kit which branches carry
public evidence, what one packaged run of a branch head must contain, and how to read the
packaged output this repository's harness writes. The kit runs every hook in an isolated child
(``python_path`` is the repository root) and re-verifies every answer before any byte is
published, so each hook here is a pure function of its arguments, of inert Git objects read through
``ctx.read_blob`` and of the packaged files under ``runtime_root``.

* ``targets``: the enrolled-branch rule of ``version_branches.py --pages --include-integration``.
  The canonical branch always carries a valid integration matrix; any other branch is enrolled
  only when its own matrix names it as a ``release`` branch of the canonical branch, and a branch
  that claims enrollment must then validate completely. Only a branch with no matrix, or with a
  matrix that is not JSON, is skipped; any other unreadable matrix fails the whole inventory, so an
  enrolled branch can never silently drop out of the atomic site. The key is the opaque 24-hex
  ``gate_controller.branch_token``; the label is the branch name. Today only ``master`` enrolls.
* ``expectation``: the subject's matrix and scenario contract, read at the subject commit. The
  lanes are the matrix's default dispatch scope (``legacy`` while the migration is ``preparing``,
  ``full`` once it is ``shared``; schema 1 keeps every runtime), each crossed with the protected
  release scenarios, exactly as ``on-demand-e2e.yml`` runs them. The profile is the projection of
  the run's event (``schedule`` gives ``scheduled-anchors``, anything else ``pr-anchors``) and
  ``scope.detail`` is the aggregate scope the packaged gate recorded.
* ``collect``: the reading half of the retired ``scripts/pages/evidence.py``: every
  ``profiles/<node>--<minecraft>--<scenario>/result.json`` is validated with
  ``e2e.packaged_runtime``, every report step is bound to the contract, and every screenshot and
  comparison is measured again, including the ``OPAQUE_STARS`` and required GUI text probes.
* ``expected_source_jobs``: ``scripts.ci.e2e_job_graph.expected_jobs``, unchanged.
* ``authenticate_extensions``: recomputes a supplied ``block-pops.aggregate_scope``.
* ``anchor_selection``: the matrix's canonical visual-reference lane on its release branch.

Block Pops produces neither selected nor family evidence, so ``compose``, ``verify_publication``
and ``family_validate`` are deliberately absent: the kit then refuses such evidence.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from e2e.scenario_contract import (
    MAX_CONTRACT_BYTES,
    OpaqueStarsProbe,
    RequiredGuiTextProbe,
    ScenarioContract,
    ScenarioContractError,
    load_contract,
)
from mod_base.errors import MbError
from mod_base.model.canonical import canonical_sha256
from scripts.ci.gate_controller import GateControllerError, branch_token
from scripts.lib.secure_json import SecureJsonError, loads as secure_loads
from scripts.release.matrix import (
    MAX_MATRIX_BYTES,
    MatrixDocument,
    MatrixError,
    normalize_matrix_inventory,
    valid_branch_name,
)

ADAPTER_API = 1

MATRIX = "release/release-matrix.json"
CONTRACT = "e2e/scenario-contract.json"
SOURCE_WORKFLOW = "on-demand-e2e.yml"
AGGREGATE_SCOPE = "block-pops.aggregate_scope"
PROJECTIONS = ("pr-anchors", "scheduled-anchors")
MAX_RESULT_BYTES = 2 * 1024 * 1024
SOURCES = re.compile(r"^https://github\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]{1,100})$")
#: ``scripts/ci/e2e_fanin.py``'s ``SAFE_ARTIFACT`` rule for a packaged profile directory name.
PROFILE_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,240}$")


class AdapterError(ValueError):
    """The subject's matrix, contract or packaged output cannot back public evidence."""


def _fail(message: str) -> None:
    raise AdapterError(message)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _scratch_file(ctx: Any, prefix: str, data: bytes) -> Path:
    """Write ``data`` to a fresh file in the hook's private directory (loaders take a path)."""

    descriptor, name = tempfile.mkstemp(prefix=prefix, suffix=".json", dir=ctx.tmpdir)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
    return Path(name)


def read_contract(ctx: Any, commit: str) -> tuple[ScenarioContract, bytes]:
    """The scenario contract of ``commit``, read as an inert object and fully validated."""

    data = ctx.read_blob(commit, CONTRACT, MAX_CONTRACT_BYTES)
    path = _scratch_file(ctx, "scenario-contract-", data)
    try:
        contract = load_contract(path)
    except ScenarioContractError as exc:
        raise AdapterError(f"the scenario contract at {commit} is invalid: {exc}") from exc
    finally:
        path.unlink()
    if contract.sha256 != _sha256(data):
        _fail("the scenario contract changed while it was validated")
    return contract, data


def parse_matrix(data: bytes, contract: ScenarioContract) -> tuple[dict[str, Any], MatrixDocument]:
    """Strictly decode and fully validate one branch matrix against its own contract."""

    try:
        matrix = secure_loads(data, label="branch release matrix", max_bytes=MAX_MATRIX_BYTES)
        document = MatrixDocument(normalize_matrix_inventory(matrix, contract=contract), json.dumps(matrix))
    except (MatrixError, SecureJsonError) as exc:
        raise AdapterError(f"the branch release matrix is invalid: {exc}") from exc
    return matrix, document


def require_branch_identity(matrix: dict[str, Any], *, branch: str, canonical_branch: str) -> None:
    """The matrix names exactly this branch, the canonical branch, and the role of the branch."""

    identity = matrix["branch"]
    role = "integration" if branch == canonical_branch else "release"
    if (identity["name"], identity["canonical"], identity["role"]) != (branch, canonical_branch, role):
        _fail(f"the matrix of {branch!r} does not name it as the {role} branch of {canonical_branch!r}")


def repository_of(matrix: dict[str, Any]) -> str:
    """``owner/name`` of the GitHub repository the matrix declares as its sources."""

    match = SOURCES.fullmatch(matrix["project"]["sources"])
    if match is None:
        _fail("the matrix project.sources is not a https://github.com/<owner>/<repository> URL")
    return f"{match.group(1)}/{match.group(2)}"


def projection(tested_run: dict[str, Any] | None) -> str:
    """The packaged projection a run of this event executes (as ``on-demand-e2e.yml`` chooses it)."""

    return "scheduled-anchors" if tested_run is not None and tested_run["event"] == "schedule" else "pr-anchors"


def _version_key(lane: Any) -> tuple[tuple[int, ...], str]:
    return tuple(int(part) for part in lane.identity.minecraft.split(".")), lane.identity.loader


def coverage(document: MatrixDocument, contract: ScenarioContract,
             profile: str) -> tuple[list[dict[str, Any]], list[str], dict[str, Any] | None]:
    """The runtimes and scenarios one packaged run of ``profile`` executes, and its aggregate scope.

    Schema 2 runs the matrix's default dispatch scope and records it as the aggregate scope (the
    detail ``scripts/pages/evidence.py`` embedded as ``aggregate_scope``); schema 1 runs every
    runtime and records no scope.
    """

    if profile not in PROJECTIONS:
        _fail(f"unknown packaged projection {profile!r}")
    release = list(contract.scenarios_for_profile("release"))
    if not release:
        _fail("the scenario contract selects no release scenario")
    if document.inventory.schema_version == 1:
        return [dict(row) for row in document.data["runtimes"]], release, None
    scope = document.default_scope
    try:
        selected = sorted(document.select_lanes(scope=scope), key=_version_key)
        projected = document.projection(profile, scope=scope, contract=contract)["include"]
    except MatrixError as exc:
        raise AdapterError(str(exc)) from exc
    scenarios = list(contract.scenarios_for_profile("pr" if profile == "pr-anchors" else "release"))
    if set(scenarios) != set(release):
        _fail("the packaged projection must preserve every protected release scenario")
    nodes = [lane.identity.artifact_node for lane in selected]
    if not nodes or {row["artifact_node"] for row in projected} != set(nodes):
        _fail("the packaged projection does not cover the matrix's default scope")
    targets = list(document.inventory.target_nodes)
    detail = {"kind": scope, "selected_nodes": nodes, "target_nodes": targets,
              "migration_mode": document.inventory.migration_mode, "partial": set(nodes) != set(targets),
              "projection": profile, "scenarios": scenarios}
    return [dict(lane.runtime) for lane in selected], scenarios, detail


def _subject_documents(ctx: Any, subject: dict[str, Any], *, matrix_sha256: str,
                       contract_sha256: str) -> tuple[dict[str, Any], MatrixDocument, ScenarioContract]:
    commit = subject["commit"]
    contract, contract_bytes = read_contract(ctx, commit)
    raw = ctx.read_blob(commit, MATRIX, MAX_MATRIX_BYTES)
    if _sha256(raw) != matrix_sha256 or _sha256(contract_bytes) != contract_sha256:
        _fail("the subject's matrix or scenario contract is not the one its target named")
    matrix, document = parse_matrix(raw, contract)
    require_branch_identity(matrix, branch=subject["branch"], canonical_branch=ctx.config.canonical_branch)
    return matrix, document, contract


def matrix_absent(error: MbError, commit: str) -> bool:
    """True only when ``error`` is ``ctx.read_blob``'s report that no matrix exists at ``commit``.

    ``read_blob`` raises the same :class:`MbError` for every failure: an absent path, but also an
    oversized or non-blob matrix, an object-id mismatch and a git failure. It has no distinct
    absent-path error, so only its exact absence message counts (it also names a commit missing from
    the object store, but Pages discovery fetches and verifies every listed head before ``targets``
    runs). ``version_branches.py`` skipped only a branch without a matrix and failed on every other
    unreadable one; so does this adapter. ``read_blob`` does not expose the tree-entry mode, so the
    old refusal of a non-``100644`` matrix entry cannot be repeated here; the bytes read are still
    exactly the committed blob, never a followed link.
    """

    return str(error) == f"{MATRIX} is not present at {commit} as an inert object"


def targets(ctx: Any, branches: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    """Every enrolled branch head, in branch-name order (``enrolled-branches`` mode)."""

    if branches is None:
        _fail("Block Pops enrolls branches: targets needs the listed branch heads")
    canonical = ctx.config.canonical_branch
    enrolled = []
    for head in sorted(branches, key=lambda item: item["name"]):
        name, commit = head["name"], head["commit"]
        if not valid_branch_name(name):
            _fail(f"the listed branch name {name!r} is unsafe")
        try:
            raw = ctx.read_blob(commit, MATRIX, MAX_MATRIX_BYTES)
        except MbError as exc:
            if name == canonical or not matrix_absent(exc, commit):
                raise
            continue  # a branch without a matrix is simply not enrolled
        try:
            claim = secure_loads(raw, label=f"branch {name!r} release-matrix claim", max_bytes=MAX_MATRIX_BYTES)
        except SecureJsonError as exc:
            if name == canonical:
                raise AdapterError(f"the canonical branch {name!r} has an unreadable release matrix: {exc}") from exc
            continue
        if name != canonical:
            identity = claim.get("branch") if isinstance(claim, dict) else None
            if not isinstance(identity, dict) or identity.get("name") != name or identity.get("role") != "release":
                continue  # feature branches carry an unchanged integration or release matrix
        # Once a branch self-identifies as enrolled, its matrix and contract must validate completely.
        contract, contract_bytes = read_contract(ctx, commit)
        matrix, _ = parse_matrix(raw, contract)
        require_branch_identity(matrix, branch=name, canonical_branch=canonical)
        try:
            key = branch_token(name)
        except GateControllerError as exc:
            raise AdapterError(str(exc)) from exc
        enrolled.append({"key": key, "label": name,
                         "subject": {"branch": name, "commit": commit, "tree": head["tree"]},
                         "matrix_sha256": _sha256(raw), "contract_sha256": _sha256(contract_bytes)})
    return enrolled


def expectation(ctx: Any, target: dict[str, Any], tested_run: dict[str, Any] | None,
                extensions: dict[str, Any]) -> dict[str, Any]:
    """The exact lanes, captures and comparisons one packaged run of the target's head holds."""

    subject = target["subject"]
    matrix, document, contract = _subject_documents(ctx, subject, matrix_sha256=target["matrix_sha256"],
                                                    contract_sha256=target["contract_sha256"])
    try:
        if target["key"] != branch_token(subject["branch"]):
            _fail("the target key is not the branch token of its subject branch")
    except GateControllerError as exc:
        raise AdapterError(str(exc)) from exc
    profile = projection(tested_run)
    runtimes, scenarios, detail = coverage(document, contract, profile)
    unknown = sorted(set(extensions) - {AGGREGATE_SCOPE})
    if unknown:
        _fail(f"unsupported Block Pops extensions {unknown}")
    if AGGREGATE_SCOPE in extensions and (detail is None or extensions[AGGREGATE_SCOPE] != detail):
        _fail("the supplied aggregate scope is not the scope this run executes")
    lanes: list[dict[str, Any]] = []
    captures: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    for runtime in runtimes:
        for scenario in scenarios:
            lane_id = f"{runtime['artifact_node']}/{scenario}"
            roles = list(contract.expected_roles(scenario))
            lanes.append({"lane_id": lane_id, "artifact_node": runtime["artifact_node"],
                          "minecraft": runtime["minecraft"], "loader": runtime["loader"], "java": runtime["java"],
                          "scenario": scenario, "roles": roles})
            for role in roles:
                order = 0
                for step in contract.role(scenario, role).steps:
                    if step.capture is None:
                        continue
                    capture = step.capture
                    captures.append({"frame_id": f"{lane_id}/{role}/{step.id}", "capture_id": capture.capture_id,
                                     "capture_order": order, "lane_id": lane_id, "role": role, "step": step.id,
                                     "title": capture.title, "expectation": capture.expectation,
                                     "review_tier": capture.review_tier})
                    order += 1
                for comparison in contract.comparisons_for(scenario, role):
                    first, second = comparison.first_step, comparison.second_step
                    record = {"comparison_id": f"{lane_id}/{role}/{first}+{second}", "lane_id": lane_id,
                              "role": role, "first_frame_id": f"{lane_id}/{role}/{first}",
                              "second_frame_id": f"{lane_id}/{role}/{second}",
                              "minimum_changed_fraction": comparison.minimum_changed_fraction}
                    if comparison.region is not None:
                        record["region"] = list(comparison.region)
                    comparisons.append(record)
    reference = matrix["visual_reference"]
    anchor = None
    if (subject["branch"] == reference["release_branch"]
            and reference["artifact_node"] in {runtime["artifact_node"] for runtime in runtimes}):
        anchor = {"artifact_nodes": [reference["artifact_node"]]}
    scope: dict[str, Any] = {"kind": "complete"}
    if detail is not None:
        scope.update(detail=detail, detail_sha256=canonical_sha256(detail))
    return {
        "kind": "mod-base.evidence.expectation", "schema_version": 1, "repository": repository_of(matrix),
        "key": target["key"], "label": target["label"], "subject": subject,
        "matrix_sha256": target["matrix_sha256"], "contract_sha256": target["contract_sha256"],
        "contract_path": CONTRACT, "profile": profile, "scope": scope,
        "image_policy": ctx.config.image_policy(),
        "scenarios": [{"id": scenario} for scenario in scenarios],
        "lanes": lanes, "captures": captures, "comparisons": comparisons, "anchor": anchor,
    }


def profile_path(lane: dict[str, Any]) -> str:
    """The packaged profile directory of one expectation lane (``e2e_fanin``'s naming)."""

    name = f"{lane['artifact_node']}--{lane['minecraft']}--{lane['scenario']}"
    if PROFILE_NAME.fullmatch(name) is None:
        _fail(f"packaged profile identity is unsafe: {name!r}")
    return f"profiles/{name}"


def _probe_tables(contract: ScenarioContract) -> tuple[dict[Any, Any], dict[Any, Any]]:
    """The ``OPAQUE_STARS_PROBES`` and ``REQUIRED_GUI_TEXT_PROBES`` tables ``packaged_runtime``
    derives from its contract, derived here from ``contract``."""

    stars: dict[Any, Any] = {}
    text: dict[Any, Any] = {}
    for scenario in contract.scenarios:
        for role in scenario.roles:
            for step in role.steps:
                if step.capture is None:
                    continue
                key = (scenario.scenario, role.role, step.id)
                for probe in step.capture.probes:
                    if isinstance(probe, OpaqueStarsProbe):
                        stars[key] = probe
                labels = tuple((probe.label, probe.box, probe.minimum_luma_exclusive, probe.minimum_pixels)
                               for probe in step.capture.probes if isinstance(probe, RequiredGuiTextProbe))
                if labels:
                    text[key] = labels
    return stars, text


def _runtime() -> Any:
    """``e2e.packaged_runtime``, imported only by the hooks that measure pixels."""

    from e2e import packaged_runtime

    return packaged_runtime


def require_protected_probes(contract: ScenarioContract) -> None:
    """The protected measurement code applies exactly the subject contract's pixel probes.

    ``packaged_runtime`` builds its probe tables from the contract of the checkout that runs it.
    A subject whose contract probes differ could otherwise pass on another branch's probes.
    """

    runtime = _runtime()
    stars, text = _probe_tables(contract)
    if (tuple(runtime.GUI_TEXT_REFERENCE_SIZE) != tuple(contract.gui_text_reference_size)
            or runtime.OPAQUE_STARS_PROBES != stars or runtime.REQUIRED_GUI_TEXT_PROBES != text):
        _fail("the protected screenshot probes differ from the subject's scenario contract")


def _report_steps(report: Any, *, contract: ScenarioContract, lane: dict[str, Any], role: str) -> dict[str, Any]:
    """Validate one role report against the contract; return its steps by id."""

    label = f"{lane['lane_id']}/{role}"
    keys = {"schema_version", "minecraft", "role", "scenario", "contract_sha256", "status", "steps",
            "pixel_validation"}
    if not isinstance(report, dict) or set(report) != keys:
        _fail(f"report {label} has an unexpected shape")
    if (type(report["schema_version"]) is not int or report["schema_version"] != 1
            or report["minecraft"] != lane["minecraft"] or report["role"] != role
            or report["scenario"] != lane["scenario"] or report["contract_sha256"] != contract.sha256
            or report["status"] != "pass"):
        _fail(f"report identity/status mismatch for {label}")
    expected = contract.role(lane["scenario"], role).steps
    steps = report["steps"]
    if not isinstance(steps, list) or len(steps) != len(expected):
        _fail(f"report step count mismatch for {label}")
    by_id: dict[str, Any] = {}
    for step, wanted in zip(steps, expected, strict=True):
        if not isinstance(step, dict) or set(step) != {"id", "status", "message", "capture_id", "screenshot"}:
            _fail(f"report step of {label} has an unexpected shape")
        capture = wanted.capture
        capture_id = None if capture is None else capture.capture_id
        if (step["id"] != wanted.id or step["status"] != "pass" or step["capture_id"] != capture_id
                or step["screenshot"] != (None if capture is None else f"{capture_id}.png")
                or not isinstance(step["message"], str) or len(step["message"]) > 1024):
            _fail(f"report step mismatch for {label}/{wanted.id}")
        by_id[step["id"]] = step
    pixels = report["pixel_validation"]
    if (not isinstance(pixels, dict) or set(pixels) != {"screenshots", "comparisons"}
            or not isinstance(pixels["screenshots"], dict) or not isinstance(pixels["comparisons"], dict)):
        _fail(f"report pixel validation of {label} must hold screenshot and comparison objects")
    return by_id


def collect(ctx: Any, runtime_root: str, target: dict[str, Any], expectation: dict[str, Any]) -> dict[str, Any]:
    """Read and re-measure the packaged output of every expectation lane (pure in its bytes)."""

    runtime = _runtime()
    contract, contract_bytes = read_contract(ctx, expectation["subject"]["commit"])
    if _sha256(contract_bytes) != expectation["contract_sha256"]:
        _fail("the subject's scenario contract is not the expectation's")
    require_protected_probes(contract)
    tree = ctx.runtime_tree(runtime_root)
    files: list[str] = []
    lanes: list[dict[str, Any]] = []
    reports: dict[tuple[str, str], tuple[str, dict[str, Any], dict[str, Any]]] = {}
    lanes_by_id = {lane["lane_id"]: lane for lane in expectation["lanes"]}
    for lane in expectation["lanes"]:
        profile = profile_path(lane)
        result = tree.read_json(f"{profile}/result.json", max_bytes=MAX_RESULT_BYTES)
        if not isinstance(result, dict):
            _fail(f"packaged result of {lane['lane_id']} must be an object")
        try:
            runtime.validate_packaged_result(result)
        except runtime.RuntimeFailure as exc:
            raise AdapterError(f"invalid packaged result {profile}: {exc}") from exc
        if (result["status"] != "pass" or result["error"] is not None
                or result["artifact_node"] != lane["artifact_node"] or result["minecraft"] != lane["minecraft"]
                or result["loader"] != lane["loader"] or result["scenario"] != lane["scenario"]
                or result["contract_sha256"] != expectation["contract_sha256"] or result["profile"] != profile):
            _fail(f"packaged result identity/status mismatch for {profile}")
        if sorted(result["reports"]) != sorted(lane["roles"]):
            _fail(f"packaged report role inventory mismatch for {profile}")
        for role in lane["roles"]:
            steps = _report_steps(result["reports"][role], contract=contract, lane=lane, role=role)
            reports[(lane["lane_id"], role)] = (profile, result["reports"][role], steps)
        files.append(f"{profile}/result.json")
        record = {"lane_id": lane["lane_id"], "java": lane["java"], "profile": expectation["profile"],
                  "status": "pass", "jars": {"production_sha256": result["production_jar_sha256"],
                                             "harness_sha256": result["harness_jar_sha256"]},
                  "elapsed_s": result["elapsed_s"]}
        lanes.append(record)
    frames: list[dict[str, Any]] = []
    paths: dict[str, Path] = {}
    for capture in expectation["captures"]:
        lane = lanes_by_id[capture["lane_id"]]
        profile, report, steps = reports[(capture["lane_id"], capture["role"])]
        step = steps[capture["step"]]
        if step["capture_id"] != capture["capture_id"]:
            _fail(f"report step does not carry capture {capture['frame_id']}")
        source = f"{profile}/{capture['role']}/screenshots/{step['screenshot']}"
        path = tree.path(source)
        try:
            measured = runtime.inspect_screenshot_for_step(path, lane["scenario"], capture["role"], capture["step"])
        except runtime.RuntimeFailure as exc:
            raise AdapterError(str(exc)) from exc
        recorded = report["pixel_validation"]["screenshots"].get(capture["step"])
        if measured != recorded:
            _fail(f"protected pixel validation disagrees for {capture['frame_id']}")
        files.append(source)
        paths[capture["frame_id"]] = path
        frames.append({"frame_id": capture["frame_id"], "source_path": source, "runtime_evidence": step["message"],
                       "reported_pixel": recorded})
    comparisons: list[dict[str, Any]] = []
    for comparison in expectation["comparisons"]:
        _, report, _ = reports[(comparison["lane_id"], comparison["role"])]
        first = comparison["first_frame_id"].rsplit("/", 1)[1]
        second = comparison["second_frame_id"].rsplit("/", 1)[1]
        region = comparison.get("region")
        try:
            measured = runtime.compare_screenshots(paths[comparison["first_frame_id"]],
                                                   paths[comparison["second_frame_id"]],
                                                   comparison["minimum_changed_fraction"],
                                                   None if region is None else tuple(region))
        except runtime.RuntimeFailure as exc:
            raise AdapterError(f"comparison validation failed for {comparison['comparison_id']}: {exc}") from exc
        recorded = report["pixel_validation"]["comparisons"].get(f"{first}->{second}")
        if measured != recorded:
            _fail(f"protected comparison disagrees for {comparison['comparison_id']}")
        comparisons.append({"comparison_id": comparison["comparison_id"], "reported": recorded})
    return {"runtime_files": sorted(set(files)), "lanes": lanes, "frames": frames, "comparisons": comparisons}


def expected_source_jobs(ctx: Any, expectation: dict[str, Any], tested_run: dict[str, Any]) -> list[dict[str, Any]]:
    """The exact job graph of the tested ``on-demand-e2e.yml`` attempt (``e2e_job_graph``)."""

    from scripts.ci.e2e_job_graph import JobGraphError, expected_jobs

    raw = ctx.read_blob(expectation["subject"]["commit"], MATRIX, MAX_MATRIX_BYTES)
    if _sha256(raw) != expectation["matrix_sha256"]:
        _fail("the subject's matrix is not the expectation's")
    detail = expectation["scope"].get("detail")
    path = _scratch_file(ctx, "release-matrix-", raw)
    try:
        jobs = expected_jobs(path, SOURCE_WORKFLOW, event=tested_run["event"], source_branch=tested_run["branch"],
                             scope=None if detail is None else detail["kind"])
    except (JobGraphError, MatrixError) as exc:
        raise AdapterError(str(exc)) from exc
    finally:
        path.unlink()
    return [{"name": job.name, "conclusion": job.conclusion} for job in jobs]


def authenticate_extensions(ctx: Any, manifest: dict[str, Any], extensions: dict[str, Any]) -> dict[str, Any]:
    """Recompute a supplied aggregate scope from the subject's matrix, as the gate executed it."""

    unknown = sorted(set(extensions) - {AGGREGATE_SCOPE})
    if unknown:
        _fail(f"unsupported Block Pops extensions {unknown}")
    if AGGREGATE_SCOPE in extensions:
        detail = extensions[AGGREGATE_SCOPE]
        _, document, contract = _subject_documents(ctx, manifest["subject"], matrix_sha256=manifest["matrix_sha256"],
                                                   contract_sha256=manifest["contract_sha256"])
        profile = detail.get("projection") if isinstance(detail, dict) else None
        if profile not in PROJECTIONS:
            _fail("the aggregate scope names no packaged projection")
        _, _, expected = coverage(document, contract, profile)
        if expected is None or detail != expected:
            _fail("the aggregate scope does not match the subject's matrix")
        if manifest["scope"].get("detail_sha256") != canonical_sha256(detail):
            _fail("the aggregate scope is not the manifest's scope")
    return {"verified": sorted(extensions), "reuse_verified": False}


def anchor_selection(ctx: Any, expectation: dict[str, Any]) -> dict[str, Any] | None:
    """The canonical visual-reference lane, which the expectation declares on its release branch."""

    return expectation["anchor"]
