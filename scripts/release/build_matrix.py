#!/usr/bin/env python3
"""Plan serial, isolated lane builds without starting Gradle or claiming success."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import stat
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.lib.secure_json import SecureJsonError, read as read_secure_json  # noqa: E402
from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES, MatrixDocument, MatrixError, normalize_matrix_inventory,
)


class BuildProcessError(RuntimeError):
    """The checkout lock or owned process cannot be used safely."""


def _identity(metadata):
    return metadata.st_dev, metadata.st_ino


def _linked(metadata):
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


class CheckoutLock:
    def __init__(self, repository, path, descriptor, parent):
        self.repository, self.path, self.descriptor = repository, path, descriptor
        self.parent = parent
        self.guard = threading.Lock()
        self.unsafe = False

    def verify(self):
        if self.descriptor is None or self.unsafe:
            raise BuildProcessError("checkout lock is closed or unsafe")
        try:
            parent, current, opened = self.path.parent.lstat(), self.path.lstat(), os.fstat(self.descriptor)
        except OSError as exc:
            raise BuildProcessError("checkout lock identity is unavailable") from exc
        if (_linked(parent) or _linked(current) or not stat.S_ISREG(current.st_mode)
                or _identity(parent) != self.parent or _identity(current) != _identity(opened)):
            raise BuildProcessError("checkout lock identity changed")


@contextmanager
def checkout_lock(repository: Path):
    """Exclusive creation never steals even a stale lock; release only our inode."""
    repository = repository.resolve(strict=True)
    directory = repository / "build"
    directory.mkdir(exist_ok=True)
    parent = directory.lstat()
    if _linked(parent) or not stat.S_ISDIR(parent.st_mode):
        raise BuildProcessError("build lock parent must be a real directory")
    path = directory / ".matrix-build.lock"
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    except FileExistsError as exc:
        raise BuildProcessError("checkout build lock already exists; no process started") from exc
    lease = CheckoutLock(repository, path, descriptor, _identity(parent))
    try:
        lease.verify()
        os.write(descriptor, (json.dumps({"pid": os.getpid()}) + "\n").encode())
        yield lease
    finally:
        with lease.guard:
            try:
                if not lease.unsafe:
                    lease.verify()
                    path.unlink()
            finally:
                os.close(descriptor)
                lease.descriptor = None


def _group_alive(pid):
    try:
        os.killpg(pid, 0)
        return True
    except ProcessLookupError:
        return False


def _finish_owned_group(process):
    """The PID is the session/group leader created by our Popen call only."""
    def send(signum):
        try:
            os.killpg(process.pid, signum)
        except ProcessLookupError:
            pass
    def wait(timeout=None):
        while True:
            try:
                return process.wait(timeout=timeout)
            except KeyboardInterrupt:
                continue  # Cancellation cannot release the checkout before reaping.
    send(signal.SIGTERM)
    try:
        wait(timeout=10)
    except subprocess.TimeoutExpired:
        send(signal.SIGKILL)
        wait()
    if _group_alive(process.pid):
        send(signal.SIGKILL)
    deadline = time.monotonic() + 5
    while _group_alive(process.pid):
        if time.monotonic() >= deadline:
            raise BuildProcessError("owned process group did not exit; checkout lock retained")
        try:
            time.sleep(0.02)
        except KeyboardInterrupt:
            continue


def run_lane(command: list[str], *, lock: CheckoutLock, env: dict[str, str], output=None) -> int:
    """Synchronous POSIX process-tree primitive; CLI execution remains disabled."""
    if os.name == "nt":
        raise BuildProcessError("Windows owned process-tree cleanup is not implemented")
    if not lock.guard.acquire(blocking=False):
        raise BuildProcessError("a lane is already running under this checkout lock")
    try:
        lock.verify()
        process = subprocess.Popen(command, cwd=lock.repository, env=dict(env),
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True, close_fds=True)
        try:
            return process.wait()
        finally:
            try:
                _finish_owned_group(process)
            except BaseException:
                lock.unsafe = True
                raise
    finally:
        lock.guard.release()


def numeric_version(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def plan_build(
    matrix_path: Path, *, scope: str = "full", artifact_node: str | None = None,
    clean: bool = False, windows: bool | None = None,
) -> dict[str, Any]:
    if scope not in {"full", "legacy"}:
        raise MatrixError("build scope must be full or legacy")
    matrix_path = matrix_path.parent.resolve() / matrix_path.name
    repository = matrix_path.parents[1]
    data, payload = read_secure_json(matrix_path, label="release matrix", max_bytes=MAX_MATRIX_BYTES)
    document = MatrixDocument(
        normalize_matrix_inventory(data, repository=repository), json.dumps(data),
    )
    if artifact_node is not None and scope != "full":
        raise MatrixError("artifact_node cannot be combined with an explicit scope")
    selected_scope = "lane" if artifact_node is not None else scope
    lanes = sorted(document.select_lanes(scope=selected_scope, artifact_node=artifact_node),
                   key=lambda lane: (numeric_version(lane.identity.minecraft), lane.identity.loader))
    if any(lane.gradle_java != 21 for lane in lanes):
        raise MatrixError("the serial runner requires Gradle Java 21")
    wrapper = "gradlew.bat" if (os.name == "nt" if windows is None else windows) else "./gradlew"
    planned = []
    for lane in lanes:
        artifact, runtime = lane.artifact, lane.runtime
        if artifact["java"] not in {17, 21} or runtime["java"] not in {17, 21}:
            raise MatrixError("the serial runner requires artifact/runtime Java 17 or 21")
        home = repository / "build/gradle-home" / lane.identity.artifact_node
        command = [wrapper, "--no-daemon", "--no-parallel", "--max-workers=1",
                   "--dependency-verification", "strict", "--gradle-user-home", str(home),
                   f"-PblockpopsLane={lane.identity.artifact_node}"]
        if clean:
            command.append(artifact["gradle_task"].rsplit(":", 1)[0] + ":clean")
        command.extend(["validateReleaseMatrix", artifact["gradle_task"], artifact["harness_task"]])
        planned.append({
            "artifact_node": lane.identity.artifact_node,
            "minecraft": lane.identity.minecraft, "loader": lane.identity.loader,
            "build_layout": lane.build_layout, "gradle_user_home": str(home),
            "required_java": {"gradle": 21, "artifact": artifact["java"], "runtime": runtime["java"]},
            "cwd": str(repository), "command": command,
            "outputs": {"production": str(repository / lane.production_jar),
                        "harness": str(repository / lane.harness_jar)},
        })
    selected_nodes = [lane["artifact_node"] for lane in planned]
    return {
        "schema_version": 1, "kind": "blockpops-build-plan", "status": "planned",
        "scope": selected_scope, "selected_nodes": selected_nodes,
        "target_nodes": list(document.inventory.target_nodes),
        "partial_scope": set(selected_nodes) != set(document.inventory.target_nodes),
        "matrix": {"path": str(matrix_path), "sha256": hashlib.sha256(payload).hexdigest(),
                   "schema_version": document.inventory.schema_version,
                   "migration_mode": document.inventory.migration_mode},
        "source": {"repository": str(repository), "status": "unverified"},
        "toolchains": {"status": "unverified"}, "lanes": planned,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true", required=True,
                        help="emit a plan only; execution is not implemented")
    parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("full", "legacy"))
    selection.add_argument("--artifact-node")
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args(argv)
    try:
        plan = plan_build(args.matrix, scope=args.scope or "full", artifact_node=args.artifact_node, clean=args.clean)
    except (MatrixError, SecureJsonError, OSError) as exc:
        print(f"build matrix plan failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(plan, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
