#!/usr/bin/env python3
"""Assemble one atomic static gallery from exact-head compact branch caches."""

from __future__ import annotations

import argparse
import json
import stat
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import SecureJsonError, read as read_secure_json, require_object  # noqa: E402
from scripts.lib.atomic_directory import AtomicDirectoryError, atomic_directory, write_new  # noqa: E402
from scripts.pages.evidence import (  # noqa: E402
    EvidenceError,
    _child_file,
    branch_token,
    sha256_bytes,
    validate_compact,
)
from scripts.release.matrix import MatrixError, load_matrix  # noqa: E402

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


def _copy_static(stage_fd: int) -> None:
    allowed = {"index.html", "assets/site.css", "assets/gallery.js"}
    actual: set[str] = set()
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
    if actual != allowed:
        raise SiteError(f"site source inventory mismatch: {sorted(actual)}")


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


def build(*, evidence_root: Path, inventory_path: Path, output: Path, repository: str, canonical_matrix: Path) -> dict[str, int]:
    inventory = _inventory(inventory_path)
    expected = {row["name"]: row for row in inventory}
    manifests: dict[str, tuple[Path, dict[str, Any]]] = {}
    for candidate in _candidate_directories(evidence_root):
        manifest_path = candidate / "manifest.json"
        if not manifest_path.is_file():
            raise SiteError(f"collected artifact has no compact manifest: {candidate.name}")
        try:
            raw, _ = read_secure_json(manifest_path, label="compact manifest", max_bytes=2 * 1024 * 1024)
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
            manifest = validate_compact(
                candidate,
                matrix_path=candidate / "release-matrix.json",
                expected=expected_identity,
            )
        except EvidenceError as exc:
            raise SiteError(str(exc)) from exc
        manifests[branch] = (candidate, manifest)
    if set(manifests) != set(expected):
        raise SiteError(
            f"compact cache coverage mismatch: missing={sorted(set(expected) - set(manifests))}, "
            f"extra={sorted(set(manifests) - set(expected))}"
        )
    try:
        canonical = load_matrix(canonical_matrix)
    except MatrixError as exc:
        raise SiteError(str(exc)) from exc
    project = canonical["project"]
    expected_source = f"https://github.com/{repository}"
    if project["sources"].rstrip("/") != expected_source:
        raise SiteError("canonical project source URL disagrees with the repository")

    def writer(stage: Path, stage_fd: int) -> dict[str, int]:
        _copy_static(stage_fd)
        write_new(stage_fd, ".nojekyll", b"")
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
        total = 0
        count = 0
        for path in stage.rglob("*"):
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode):
                raise SiteError("generated site contains a symlink")
            if stat.S_ISREG(info.st_mode):
                count += 1
                total += info.st_size
        if count > MAX_SITE_FILES or total > MAX_SITE_BYTES:
            raise SiteError("generated site exceeds its file-count or byte bound")
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
