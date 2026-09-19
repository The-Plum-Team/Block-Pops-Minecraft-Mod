#!/usr/bin/env python3
"""Assemble one atomic static gallery from exact-head compact branch caches."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import SecureJsonError, canonical_json, read as read_secure_json, require_object  # noqa: E402
from scripts.lib.atomic_directory import AtomicDirectoryError, atomic_directory, write_new  # noqa: E402
from scripts.pages.evidence import (  # noqa: E402
    EvidenceError,
    SHA1,
    _child_file,
    _digest,
    _positive_int,
    _validate_file_records,
    branch_token,
    cache_artifact_name,
    sha256_bytes,
    validate_compact,
)
from scripts.release.matrix import MAX_MATRIX_BYTES, MatrixDocument, MatrixError, load_matrix, normalize_matrix_inventory  # noqa: E402
from e2e.scenario_contract import DEFAULT_CONTRACT, MAX_CONTRACT_BYTES, default_contract  # noqa: E402

SITE_SOURCE = REPO / "site"
MAX_INVENTORY_BYTES = 2 * 1024 * 1024
MAX_SITE_BYTES = 384 * 1024 * 1024
MAX_SITE_FILES = 1024


class SiteError(ValueError):
    """Raised when an all-branch site cannot be assembled atomically."""


def _inventory(path: Path) -> list[dict[str, Any]]:
    try:
        value, _ = read_secure_json(path, label="Pages branch inventory", max_bytes=MAX_INVENTORY_BYTES)
    except SecureJsonError as exc:
        raise SiteError(str(exc)) from exc
    if not isinstance(value, list) or not value:
        raise SiteError("Pages branch inventory must be a non-empty array")
    rows: list[dict[str, Any]] = []
    names: set[str] = set()
    tokens: set[str] = set()
    for index, raw in enumerate(value):
        try:
            row = require_object(
                raw,
                label=f"Pages branch inventory[{index}]",
                required={"name", "commit", "tree", "matrix_blob", "matrix_sha256", "minecraft", "loaders", "java"},
            )
        except SecureJsonError as exc:
            raise SiteError(str(exc)) from exc
        if row["name"] in names:
            raise SiteError(f"duplicate enrolled branch {row['name']!r}")
        if not isinstance(row["loaders"], list) or not row["loaders"]:
            raise SiteError("enrolled branch loaders must be non-empty")
        token = branch_token(row["name"])
        if token in tokens:
            raise SiteError("enrolled branch token collision")
        names.add(row["name"])
        tokens.add(token)
        rows.append(row)
    return rows


def _copy_static(stage_fd: int) -> dict[str, bytes]:
    allowed = {"index.html", "assets/site.css", "assets/gallery.js"}
    actual: set[str] = set()
    copied = {}
    for path in SITE_SOURCE.rglob("*"):
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode):
            raise SiteError(f"site source contains a symlink: {path}")
        if stat.S_ISDIR(info.st_mode):
            continue
        if not stat.S_ISREG(info.st_mode):
            raise SiteError(f"site source contains a special file: {path}")
        relative = path.relative_to(SITE_SOURCE).as_posix()
        actual.add(relative)
        if relative not in allowed:
            raise SiteError(f"site source contains an unapproved file: {relative}")
        _, data = _child_file(SITE_SOURCE, relative, label="protected static site source", maximum=MAX_SITE_BYTES)
        write_new(stage_fd, relative, data)
        copied[relative] = data
    if actual != allowed:
        raise SiteError(f"site source inventory mismatch: {sorted(actual)}")
    return copied


def _seal_output(stage_fd: int, expected: dict[str, bytes]) -> tuple[int, int]:
    """Check descriptor-bound bytes, then recheck every stamp and directory entry."""
    count, total = len(expected), sum(len(data) for data in expected.values())
    if count > MAX_SITE_FILES or total > MAX_SITE_BYTES:
        raise SiteError("generated site exceeds its file-count or byte bound")
    children = {"": set()}
    for relative in expected:
        parent = ""
        for name in Path(relative).parts:
            children.setdefault(parent, set()).add(name)
            parent = f"{parent}/{name}" if parent else name
    stamps = {}
    def stamp(info):
        return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
                info.st_mtime_ns, info.st_ctime_ns)
    def walk(directory, parent, *, hash_bytes):
        initial = stamp(os.fstat(directory))
        if set(os.listdir(directory)) != children[parent]:
            raise SiteError("generated site inventory differs from written paths")
        for name in sorted(children[parent]):
            relative = f"{parent}/{name}" if parent else name
            before = os.stat(name, dir_fd=directory, follow_symlinks=False)
            is_directory = relative in children
            if not (stat.S_ISDIR(before.st_mode) if is_directory else
                    stat.S_ISREG(before.st_mode) and before.st_nlink == 1):
                raise SiteError("generated site contains an unexpected file type/link")
            flags = os.O_RDONLY | os.O_NOFOLLOW | (os.O_DIRECTORY if is_directory else os.O_NONBLOCK)
            descriptor = os.open(name, flags, dir_fd=directory)
            try:
                if stamp(os.fstat(descriptor)) != stamp(before):
                    raise SiteError("generated site entry changed while opening")
                if is_directory:
                    walk(descriptor, relative, hash_bytes=hash_bytes)
                elif hash_bytes:
                    wanted = expected[relative]
                    if before.st_size != len(wanted):
                        raise SiteError("generated site file size differs from written bytes")
                    digest = hashlib.sha256()
                    remaining = len(wanted) + 1
                    while remaining:
                        chunk = os.read(descriptor, min(65536, remaining))
                        if not chunk: break
                        digest.update(chunk); remaining -= len(chunk)
                    if remaining != 1 or digest.digest() != hashlib.sha256(wanted).digest():
                        raise SiteError("generated site bytes differ from written bytes")
                elif stamps[relative] != stamp(before):
                    raise SiteError("generated site file changed after byte verification")
                if (stamp(os.fstat(descriptor)) != stamp(before)
                        or stamp(os.stat(name, dir_fd=directory, follow_symlinks=False)) != stamp(before)):
                    raise SiteError("generated site entry changed during verification")
                if hash_bytes: stamps[relative] = stamp(before)
            finally:
                os.close(descriptor)
        if stamp(os.fstat(directory)) != initial or set(os.listdir(directory)) != children[parent]:
            raise SiteError("generated site directory changed during verification")
        if hash_bytes: stamps[parent] = initial
        elif stamps[parent] != initial:
            raise SiteError("generated site directory changed after byte verification")
    try:
        walk(stage_fd, "", hash_bytes=True)
        walk(stage_fd, "", hash_bytes=False)
    except OSError as exc:
        raise SiteError(f"cannot seal generated site: {exc}") from exc
    return count, total


def _candidate_directories(root: Path) -> list[Path]:
    try:
        info = root.lstat()
    except OSError as exc:
        raise SiteError(f"cannot inspect collected caches: {exc}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise SiteError("collected cache root must be a real directory")
    candidates: list[Path] = []
    for path in sorted(root.iterdir()):
        entry = path.lstat()
        if stat.S_ISLNK(entry.st_mode) or not stat.S_ISDIR(entry.st_mode):
            raise SiteError(f"collected cache root contains an unsafe entry: {path}")
        candidates.append(path)
    return candidates


def _scoped_inputs(branch_inputs, inventory_path, canonical_matrix, private):
    snapshots = {}
    def capture(path, maximum=MAX_INVENTORY_BYTES):
        path = Path(path).absolute()
        if path not in snapshots:
            value, raw = read_secure_json(path, label="external Pages input", max_bytes=maximum)
            snapshots[path] = (value, raw, maximum)
        return snapshots[path][:2]
    def recheck():
        for path, (_, raw, maximum) in snapshots.items():
            if read_secure_json(path, label="final Pages input", max_bytes=maximum)[1] != raw:
                raise SiteError("external Pages input changed during rendering")
    rows, _ = capture(inventory_path)
    if not isinstance(rows, list) or not rows or len(rows) > 1000 or not isinstance(branch_inputs, dict):
        raise SiteError("scoped Pages requires a bounded discovery inventory and external branch inputs")
    canonical, _ = capture(canonical_matrix, MAX_MATRIX_BYTES)
    normalize_matrix_inventory(canonical)
    canonical_branch = canonical["branch"]["canonical"]
    if canonical["branch"]["name"] != canonical_branch or canonical["branch"]["role"] != "integration":
        raise SiteError("protected canonical matrix is not the integration branch")
    _, contract_raw = capture(DEFAULT_CONTRACT, MAX_CONTRACT_BYTES)
    if sha256_bytes(contract_raw) != default_contract().sha256:
        raise SiteError("protected Pages contract snapshot differs")
    prepared, tokens = {}, set()
    for row in rows:
        if not isinstance(row, dict):
            raise SiteError("discovery rows must be objects")
        branch = row.get("name")
        token = branch_token(branch)
        if token in tokens or branch not in branch_inputs:
            raise SiteError("missing/duplicate Pages branch input")
        tokens.add(token)
        supplied = require_object(branch_inputs[branch], label="branch input",
            required={"matrix_path", "selection_path", "selection_sha256"})
        matrix, raw = capture(supplied["matrix_path"], MAX_MATRIX_BYTES)
        document = MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))
        inventory = document.inventory
        kind = "unscoped" if inventory.schema_version == 1 else document.default_scope
        key = lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader)
        selected = sorted(document.select_lanes(scope="full" if kind == "unscoped" else kind), key=key)
        nodes = [lane.identity.artifact_node for lane in selected]
        expected_row = dict(name=branch, commit=_digest(row.get("commit"), "discovery commit", SHA1),
            tree=_digest(row.get("tree"), "discovery tree", SHA1),
            matrix_blob=hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest(),
            matrix_sha256=sha256_bytes(raw), matrix_schema_version=inventory.schema_version,
            minecraft_versions=sorted({lane.identity.minecraft for lane in selected}, key=lambda value: tuple(map(int, value.split(".")))),
            loaders=sorted({lane.identity.loader for lane in selected}), java=sorted({lane.artifact["java"] for lane in selected}),
            configured_nodes=[lane.identity.artifact_node for lane in sorted(inventory.lanes, key=key)],
            scope=dict(kind=kind, selected_nodes=nodes, target_nodes=list(inventory.target_nodes),
                migration_mode=inventory.migration_mode, partial=set(nodes) != set(inventory.target_nodes)))
        identity = matrix["branch"]
        if (canonical_json(row) != canonical_json(expected_row) or identity["name"] != branch
                or identity["canonical"] != canonical_branch
                or identity["role"] != ("integration" if branch == canonical_branch else "release")):
            raise SiteError("discovery differs from the exact external matrix/source")
        selection = None
        if kind == "unscoped":
            if supplied["selection_path"] is not None or supplied["selection_sha256"] is not None:
                raise SiteError("schema1 branch cannot accept a scoped selection")
        else:
            digest = _digest(supplied["selection_sha256"], "external selection SHA256")
            selection, selection_raw = capture(supplied["selection_path"])
            if sha256_bytes(selection_raw) != digest:
                raise SiteError("external selection digest differs")
        matrix_path = private / (token + ".json")
        matrix_path.write_bytes(raw)
        prepared[branch] = (matrix_path, selection, kind)
    if set(branch_inputs) != set(prepared):
        raise SiteError("external branch input coverage differs")
    return dict(rows=rows, branches=prepared, canonical=canonical, capture=capture, recheck=recheck)


def _scoped_compact(candidate, row, manifest_raw, expected, context):
    matrix_path, selection, kind = context["branches"][row["name"]]
    if kind == "unscoped":
        return validate_compact(candidate, matrix_path=matrix_path, expected=expected)
    if not isinstance(selection, dict) or not isinstance(selection.get("aggregate_scope"), dict):
        raise SiteError("external Pages selection is malformed")
    manifest = validate_compact(candidate, matrix_path=matrix_path, expected=expected,
        scope=kind, projection=selection["aggregate_scope"].get("projection"))
    provenance = manifest["provenance"]
    chosen = require_object(selection.get("selected_artifact"), label="selected artifact",
        required={"kind", "id", "name", "digest", "run_id", "run_attempt"})
    for field in ("id", "run_id", "run_attempt"):
        _positive_int(chosen[field], "selected " + field)
    if not isinstance(chosen["digest"], str) or not chosen["digest"].startswith("sha256:"):
        raise SiteError("selected artifact digest is malformed")
    _digest(chosen["digest"][7:], "selected artifact digest")
    if chosen["kind"] == "raw":
        if canonical_json(chosen) != canonical_json(dict(kind="raw", **manifest["source_artifact"],
                run_id=provenance["handoff"]["run_id"], run_attempt=provenance["handoff"]["run_attempt"])):
            raise SiteError("selected raw artifact binding differs")
    elif chosen["kind"] != "compact" or chosen["name"] != cache_artifact_name(row["name"], row["commit"]):
        raise SiteError("selected cache artifact binding differs")
    source = {key: provenance[key] for key in ("branch", "commit", "tree", "matrix_sha256", "contract_sha256")}
    handoff, packaged = provenance["handoff"], provenance["packaged"]
    attested = (handoff["run_id"], handoff["run_attempt"]) != (packaged["run_id"], packaged["run_attempt"])
    canonical_branch = context["canonical"]["branch"]["canonical"]
    if (handoff["controller_branch"] != canonical_branch or packaged["controller_branch"] != canonical_branch
            or not attested and any(packaged[key] != provenance[key] for key in ("branch", "commit", "tree"))
            or not attested and handoff["controller_sha"] != packaged["controller_sha"]
            or manifest["aggregate_scope"]["projection"] == "scheduled-anchors" and (attested or row["name"] != canonical_branch)):
        raise SiteError("compact handoff/projection contradicts source authentication")
    exact = dict(schema_version=1, kind="pages-compact-selection", source=source, provenance=provenance,
        aggregate_scope=manifest["aggregate_scope"], compact_manifest_sha256=sha256_bytes(manifest_raw),
        source_artifact=manifest["source_artifact"], selected_artifact=chosen,
        attested=attested,
        handoff_run_id=handoff["run_id"], packaged_run_id=packaged["run_id"],
        packaged_job_graph_sha256=_digest(selection.get("packaged_job_graph_sha256"), "packaged job graph"))
    if canonical_json(selection) != canonical_json(exact):
        raise SiteError("external compact selection binding differs")
    return manifest


def build(*, evidence_root: Path, inventory_path: Path, output: Path, repository: str,
          canonical_matrix: Path, branch_inputs: dict | None = None) -> dict[str, int]:
    """Render exact external selections; their bodies provide no authentication.

    Opt-in requires caller-authenticated discovery and companion artifact ownership,
    ID/digest, same Pages run/attempt/controller, and bounded extraction before its
    raw selection SHA is supplied. This API never supplies that transport authority.
    """
    arguments = dict(evidence_root=evidence_root, inventory_path=inventory_path,
        output=output, repository=repository, canonical_matrix=canonical_matrix)
    if branch_inputs is None:
        return _build(**arguments)
    try:
        with tempfile.TemporaryDirectory(prefix="blockpops-pages-render-") as temporary:
            context = _scoped_inputs(branch_inputs, inventory_path, canonical_matrix, Path(temporary))
            return _build(**arguments, context=context)
    except (EvidenceError, MatrixError, SecureJsonError, TypeError) as exc:
        raise SiteError(str(exc)) from exc


def _build(*, evidence_root, inventory_path, output, repository, canonical_matrix, context=None):
    inventory = context["rows"] if context else _inventory(inventory_path)
    expected = {row["name"]: row for row in inventory}
    manifests: dict[str, tuple[Path, dict[str, Any]]] = {}
    for candidate in _candidate_directories(evidence_root):
        manifest_path = candidate / "manifest.json"
        if not manifest_path.is_file():
            raise SiteError(f"collected artifact has no compact manifest: {candidate.name}")
        try:
            raw, manifest_raw = (context["capture"](manifest_path) if context else
                read_secure_json(manifest_path, label="compact manifest", max_bytes=2 * 1024 * 1024))
        except SecureJsonError as exc:
            raise SiteError(str(exc)) from exc
        if not isinstance(raw, dict) or not isinstance(raw.get("provenance"), dict):
            raise SiteError("compact manifest has no provenance")
        branch = raw["provenance"].get("branch")
        row = expected.get(branch)
        if row is None or branch in manifests:
            raise SiteError(f"compact cache has unknown/duplicate branch {branch!r}")
        expected_identity = {
            "repository": repository,
            "branch": branch,
            "commit": row["commit"],
            "tree": row["tree"],
            "matrix_sha256": row["matrix_sha256"],
        }
        try:
            manifest = _scoped_compact(candidate, row, manifest_raw, expected_identity, context) if context else validate_compact(
                candidate,
                matrix_path=candidate / "release-matrix.json",
                expected=expected_identity,
            )
            if context and canonical_json(manifest) != canonical_json(raw):
                raise SiteError("compact manifest changed during validation")
        except EvidenceError as exc:
            raise SiteError(str(exc)) from exc
        manifests[branch] = (candidate, manifest)
    if set(manifests) != set(expected):
        raise SiteError(
            f"compact cache coverage mismatch: missing={sorted(set(expected) - set(manifests))}, "
            f"extra={sorted(set(manifests) - set(expected))}"
        )
    try:
        canonical = context["canonical"] if context else load_matrix(canonical_matrix)
    except MatrixError as exc:
        raise SiteError(str(exc)) from exc
    project = canonical["project"]
    expected_source = f"https://github.com/{repository}"
    if project["sources"].rstrip("/") != expected_source:
        raise SiteError("canonical project source URL disagrees with the repository")

    def writer(stage: Path, stage_fd: int) -> dict[str, int]:
        written = _copy_static(stage_fd)
        write_new(stage_fd, ".nojekyll", b"")
        written[".nojekyll"] = b""
        releases: list[dict[str, Any]] = []
        frames: list[dict[str, Any]] = []
        copied: dict[str, bytes] = {}
        for branch in sorted(manifests):
            bundle, manifest = manifests[branch]
            provenance = manifest["provenance"]
            releases.append(
                {
                    "branch": branch,
                    "commit": provenance["commit"],
                    "tree": provenance["tree"],
                    "minecraft": sorted({lane["minecraft"] for lane in manifest["lanes"]}),
                    "loaders": sorted({lane["loader"] for lane in manifest["lanes"]}),
                    "run_id": provenance["handoff"]["run_id"],
                    "run_attempt": provenance["handoff"]["run_attempt"],
                    "run_url": f"https://github.com/{repository}/actions/runs/{provenance['handoff']['run_id']}",
                    "packaged_run_id": provenance["packaged"]["run_id"],
                    "packaged_run_attempt": provenance["packaged"]["run_attempt"],
                    "packaged_run_url": f"https://github.com/{repository}/actions/runs/{provenance['packaged']['run_id']}",
                    "packaged_branch": provenance["packaged"]["branch"],
                    "packaged_commit": provenance["packaged"]["commit"],
                    **({"aggregate_scope": manifest["aggregate_scope"]} if "aggregate_scope" in manifest else {}),
                }
            )
            token = branch_token(branch)
            for frame in manifest["frames"]:
                derivative = frame["derivative"]
                _, data = _child_file(
                    bundle,
                    derivative["path"],
                    label="validated compact gallery image",
                    maximum=2 * 1024 * 1024,
                )
                if sha256_bytes(data) != derivative["sha256"]:
                    raise SiteError("compact image changed after validation")
                relative = f"images/{token}/{derivative['sha256']}.webp"
                previous = copied.setdefault(relative, data)
                if previous != data:
                    raise SiteError("published image digest collision")
                frames.append(
                    {
                        "branch": branch,
                        "commit": provenance["commit"],
                        "tree": provenance["tree"],
                        "run_id": provenance["handoff"]["run_id"],
                        "packaged_run_id": provenance["packaged"]["run_id"],
                        "artifact_node": frame["artifact_node"],
                        "minecraft": frame["minecraft"],
                        "loader": frame["loader"],
                        "scenario": frame["scenario"],
                        "role": frame["role"],
                        "step": frame["step"],
                        "capture_id": frame["capture_id"],
                        "title": frame["title"],
                        "expectation": frame["expectation"],
                        "review_tier": frame["review_tier"],
                        "image": relative,
                        "width": derivative["width"],
                        "height": derivative["height"],
                        "source_sha256": frame["source"]["sha256"],
                        "published_sha256": derivative["sha256"],
                    }
                )
        for relative, data in copied.items():
            write_new(stage_fd, relative, data)
        written.update(copied)
        frames.sort(key=lambda frame: (frame["minecraft"], frame["loader"], frame["capture_id"], frame["branch"]))
        site_data = {
            "schema_version": 1,
            "project": {
                "name": project["name"],
                "description": project["description"],
                "license": project["license"],
                "repository": expected_source,
                "issues": project["issues"],
            },
            "releases": releases,
            "frames": frames,
        }
        encoded = (json.dumps(site_data, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
        write_new(stage_fd, "gallery-data.json", encoded)
        written["gallery-data.json"] = encoded
        if context:
            if set(_candidate_directories(evidence_root)) != {directory for directory, _ in manifests.values()}:
                raise SiteError("collected cache inventory changed during rendering")
            for directory, manifest in manifests.values():
                _validate_file_records(directory, manifest["files"] + [manifest["matrix"]], include_manifest="manifest.json")
            context["recheck"]()
        count, total = _seal_output(stage_fd, written)
        return {"branches": len(releases), "frames": len(frames), "files": count, "bytes": total}

    try:
        return atomic_directory(output, writer)
    except AtomicDirectoryError as exc:
        raise SiteError(str(exc)) from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--canonical-matrix", type=Path, default=REPO / "release/release-matrix.json")
    args = parser.parse_args(argv)
    try:
        result = build(evidence_root=args.evidence_root, inventory_path=args.inventory, output=args.output, repository=args.repository, canonical_matrix=args.canonical_matrix)
    except (EvidenceError, MatrixError, SecureJsonError, SiteError, OSError, ValueError) as exc:
        print(f"Pages site error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
