#!/usr/bin/env python3
"""Emit a lane's embedded build inputs; dirty outputs are explicitly diagnostic."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from contextlib import ExitStack
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import SecureJsonError, canonical_json, read as read_json  # noqa: E402
from scripts.release.artifact_manifest import ArtifactError, BUILD_IDENTITY_PATH, lane_build_identity  # noqa: E402
from scripts.release.build_matrix import BuildProcessError, file_snapshot, source_snapshot  # noqa: E402
from scripts.release.matrix import MAX_MATRIX_BYTES, MatrixDocument, MatrixError, normalize_matrix_inventory  # noqa: E402


def generate_identity(repository: Path, matrix_path: Path, artifact_node: str) -> Path:
    """Publish deterministic metadata only after two matching raw input snapshots."""
    repo = repository.resolve()
    matrix_path = matrix_path if matrix_path.is_absolute() else repo / matrix_path
    before = source_snapshot(repo)
    values, records = {}, {}
    for label, path, maximum in (("matrix", matrix_path, MAX_MATRIX_BYTES),
                                 ("contract", repo / "e2e/scenario-contract.json", 1024 * 1024)):
        record = file_snapshot(repo, path)
        value, payload = read_json(path, label=label, max_bytes=maximum)
        if hashlib.sha256(payload).hexdigest() != record["sha256"] or record != file_snapshot(repo, path):
            raise ArtifactError("identity input changed while reading")
        values[label], records[label] = value, record
    document = MatrixDocument(normalize_matrix_inventory(values["matrix"], repository=repo), json.dumps(values["matrix"]))
    lane = document.inventory.lane(artifact_node)
    identity = lane_build_identity(document, artifact_node, matrix_digest=records["matrix"]["sha256"],
                                   contract_digest=records["contract"]["sha256"], commit=before["commit"], tree=before["tree"])
    if before["dirty"]:
        identity["diagnostic"] = {"dirty": True, "source_fingerprint": before["fingerprint"],
                                  "index_sha256": before["index_sha256"]}
    after = source_snapshot(repo)
    files = {row["path"]: row for row in after["files"]}
    if before != after or any(files.get(row["path"]) != row for row in records.values()):
        raise ArtifactError("source or identity inputs changed during generation")
    base = Path(lane.identity.loader)
    if lane.build_layout == "stonecutter":
        base /= Path("versions") / lane.identity.minecraft
    relative = base / "build/generated/build-identity" / BUILD_IDENTITY_PATH
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise ArtifactError("identity generation requires no-follow directory descriptors")
    with ExitStack() as stack:
        parent = os.open(repo, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        stack.callback(os.close, parent)
        for part in relative.parts[:-1]:
            try:
                os.mkdir(part, dir_fd=parent)
            except FileExistsError:
                pass
            parent = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            stack.callback(os.close, parent)
        temporary = ".identity-" + os.urandom(16).hex()
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(canonical_json(identity) + b"\n"); stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, relative.name, src_dir_fd=parent, dst_dir_fd=parent)
        finally:
            try:
                os.unlink(temporary, dir_fd=parent)
            except FileNotFoundError:
                pass
        path = repo / relative
        try:
            if (file_snapshot(repo, path)["sha256"] != hashlib.sha256(canonical_json(identity) + b"\n").hexdigest()
                    or source_snapshot(repo) != before):
                raise ArtifactError("source or generated identity changed before completion")
        except BaseException:
            try:
                os.unlink(relative.name, dir_fd=parent)
            except (FileNotFoundError, IsADirectoryError):
                pass
            raise
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=REPO)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--artifact-node", required=True)
    args = parser.parse_args(argv)
    try:
        path = generate_identity(args.repository, args.matrix, args.artifact_node)
    except (ArtifactError, BuildProcessError, MatrixError, SecureJsonError, OSError) as exc:
        print(f"build identity error: {exc}", file=sys.stderr)
        return 2
    print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
