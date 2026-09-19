#!/usr/bin/env python3
"""Plan serial, isolated lane builds without starting Gradle or claiming success."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
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


def file_snapshot(repository: Path, path: str | Path) -> dict[str, Any]:
    """Hash a stable regular input/output without admitting links or escapes."""
    repository = repository.resolve(strict=True)
    relative = Path(path)
    if relative.is_absolute():
        relative = relative.relative_to(repository)
    if not relative.parts or ".." in relative.parts or "\\" in str(relative):
        raise BuildProcessError("snapshot path must stay inside the checkout")
    candidate = repository / relative
    for parent in reversed(candidate.parents):
        if parent == repository or repository in parent.parents:
            if _linked(parent.lstat()):
                raise BuildProcessError("snapshot parent must not be linked")
    before = candidate.lstat()
    if _linked(before) or not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise BuildProcessError("snapshot requires a regular, unlinked file")
    descriptor = os.open(candidate, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    sha256, blob = hashlib.sha256(), hashlib.sha1(f"blob {before.st_size}\0".encode())
    with os.fdopen(descriptor, "rb") as stream:
        opened = os.fstat(stream.fileno())
        if _identity(opened) != _identity(before) or not stat.S_ISREG(opened.st_mode):
            raise BuildProcessError("file identity changed before snapshot read")
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha256.update(chunk)
            blob.update(chunk)
        after, current = os.fstat(stream.fileno()), candidate.lstat()
    stamp = lambda info: (_identity(info), info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    if any(stamp(info) != stamp(before) for info in (opened, after, current)):
        raise BuildProcessError("file changed while its snapshot was read")
    return {"path": relative.as_posix(), "size": before.st_size, "sha256": sha256.hexdigest(),
            "git_blob": blob.hexdigest(), "executable": bool(before.st_mode & stat.S_IXUSR)}


def output_snapshot(repository: Path, outputs: dict[str, str]) -> dict[str, Any]:
    return {kind: file_snapshot(repository, path) for kind, path in sorted(outputs.items())}


def source_snapshot(repository: Path) -> dict[str, Any]:
    """All tracked bytes plus untracked build inputs; raw CRLF drift is diagnostic."""
    repository = repository.resolve(strict=True)
    def git(*arguments):
        result = subprocess.run(["git", "-C", str(repository), *arguments], capture_output=True, check=True)
        return result.stdout
    commit, tree = git("rev-parse", "HEAD", "HEAD^{tree}").decode().splitlines()
    baseline = {}
    for entry in git("ls-tree", "-rz", "--full-tree", commit).split(b"\0"):
        if entry:
            metadata, name = entry.split(b"\t", 1)
            mode, kind, digest = metadata.decode().split()
            if kind != "blob":
                raise BuildProcessError("source submodules are not supported")
            baseline[os.fsdecode(name)] = (digest, mode == "100755")
    tracked = {os.fsdecode(name) for name in git("ls-files", "-z", "--cached").split(b"\0") if name}
    index_sha256 = hashlib.sha256(git("ls-files", "--stage", "-z")).hexdigest()
    roots = {"common", "fabric", "forge", "neoforge", "gradle", "release", "scripts", "e2e", "tests", ".github", "buildSrc"}
    extra = set()
    for name in git("ls-files", "-z", "--others").split(b"\0"):
        if name:
            path = Path(os.fsdecode(name))
            parts = path.parts
            if "__pycache__" in parts or (len(parts) > 1 and parts[0] in {"common", "fabric", "forge", "neoforge", "buildSrc"}
                    and parts[1] in {"build", ".gradle", ".architectury-transformer", "run", "logs"}) or (
                    len(parts) > 3 and parts[1] == "versions" and parts[3] == "build"):
                continue
            if path.parts[0] in roots or (len(path.parts) == 1 and (
                path.suffix in {".gradle", ".kts", ".properties", ".json", ".toml", ".yaml", ".yml"}
                or path.name in {"gradlew", "gradlew.bat", ".gitignore", ".gitattributes"}
            )):
                extra.add(path.as_posix())
    files, dirty = [], bool(git("diff-index", "--cached", "--name-only", "-z", commit))
    for name in sorted(set(baseline) | tracked | extra):
        try:
            record = file_snapshot(repository, name)
            dirty |= baseline.get(name) != (record["git_blob"], record["executable"])
        except FileNotFoundError:
            record, dirty = {"path": name, "missing": True}, True
        files.append(record)
    if git("rev-parse", "HEAD").decode().strip() != commit:
        raise BuildProcessError("source commit changed while its snapshot was read")
    if hashlib.sha256(git("ls-files", "--stage", "-z")).hexdigest() != index_sha256:
        raise BuildProcessError("source index changed while its snapshot was read")
    fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {"commit": commit, "tree": tree, "index_sha256": index_sha256,
            "dirty": dirty, "fingerprint": fingerprint, "files": files}


def _atomic_report(lock: CheckoutLock, report: dict[str, Any]) -> None:
    if not lock.guard.acquire(blocking=False):
        raise BuildProcessError("cannot publish a report while a lane process is active")
    try:
        _write_report(lock, report)
    finally:
        lock.guard.release()


def _write_report(lock: CheckoutLock, report: dict[str, Any]) -> None:
    lock.verify()
    path = lock.repository / "build/build-matrix-report.json"
    if path.exists() or path.is_symlink():
        file_snapshot(lock.repository, path)
    descriptor, temporary = tempfile.mkstemp(prefix=".build-report-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(report, stream, sort_keys=True, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        lock.verify()
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def begin_report(lock: CheckoutLock, plan: dict[str, Any]) -> str:
    """Invalidate prior success before startup; this is build evidence, not qualification."""
    lock.verify()
    if plan.get("status") != "planned" or plan.get("kind") != "blockpops-build-plan":
        raise BuildProcessError("a validated build plan is required")
    if file_snapshot(lock.repository, plan["matrix"]["path"])["sha256"] != plan["matrix"]["sha256"]:
        raise BuildProcessError("matrix changed since planning")
    run_id = uuid.uuid4().hex
    _atomic_report(lock, {"schema_version": 1, "kind": "blockpops-build-run", "status": "running",
                         "run_id": run_id, "plan": plan, "source": source_snapshot(lock.repository), "lanes": []})
    return run_id


def finish_report(lock: CheckoutLock, run_id: str, results: list[dict[str, Any]], *, error: str | None = None):
    lock.verify()
    report, _ = read_secure_json(lock.repository / "build/build-matrix-report.json",
                                 label="build report", max_bytes=8 * 1024 * 1024)
    if report.get("status") != "running" or report.get("run_id") != run_id:
        raise BuildProcessError("stale runner cannot finish this report")
    try:
        if source_snapshot(lock.repository) != report["source"]:
            raise BuildProcessError("source or matrix changed during the run")
        if [row["artifact_node"] for row in results] != report["plan"]["selected_nodes"]:
            raise BuildProcessError("lane result scope is incomplete or reordered")
        for lane, result in zip(report["plan"]["lanes"], results, strict=True):
            if type(result["exit_code"]) is not int or result["exit_code"] != 0:
                raise BuildProcessError("a lane process did not succeed")
            if json.dumps(result["outputs"], sort_keys=True, allow_nan=False) != json.dumps(
                output_snapshot(lock.repository, lane["outputs"]), sort_keys=True, allow_nan=False
            ):
                raise BuildProcessError("lane outputs changed or do not match the plan")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, BuildProcessError) as exc:
        error = error or str(exc)
    report.update(status="failed" if error is not None else "success", lanes=results, error=error)
    _atomic_report(lock, report)
    return report


def numeric_version(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def verify_toolchains(plan: dict[str, Any], java_home: Path, java_homes: dict[int, Path],
                      *, environment: dict[str, str] | None = None) -> dict[str, Any]:
    """Probe explicit JDKs only; callers must reuse the validated environment plus overlay.

    Bindings override persistent Gradle properties. Gradle JVM/compiler observation
    remains required during execution; these probes do not certify a build.
    """
    env = dict(os.environ if environment is None else environment)
    for name in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        if env.get(name, "").strip():
            raise BuildProcessError(f"{name} must be empty for explicit toolchain binding")
    try:
        if plan.get("kind") != "blockpops-build-plan" or plan.get("status") != "planned":
            raise BuildProcessError("a validated build plan is required")
        first = plan["lanes"][0]["command"]
        if not isinstance(first, list) or not all(isinstance(argument, str) for argument in first):
            raise BuildProcessError("build command must contain only planned string arguments")
        canonical = plan_build(
            Path(plan["matrix"]["path"]), scope="full" if plan["scope"] == "lane" else plan["scope"],
            artifact_node=plan["selected_nodes"][0] if plan["scope"] == "lane" else None,
            clean=any(argument.endswith(":clean") for argument in first), windows=first[0] == "gradlew.bat",
        )
        if json.dumps(plan, sort_keys=True, allow_nan=False) != json.dumps(canonical, sort_keys=True):
            raise BuildProcessError("build plan changed or contains unreviewed command overrides")
        repository = Path(plan["source"]["repository"])
        criteria = repository / "gradle/gradle-daemon-jvm.properties"
        if criteria.exists() or criteria.is_symlink():
            raise BuildProcessError("daemon JVM criteria can override explicit Java home; unsupported")
        def home_path(value):
            path = Path(value)
            if not path.is_absolute() or any(character in str(path) for character in (",", "\n", "\r")):
                raise BuildProcessError("JDK homes must be absolute, comma-free single-line paths")
            return path.resolve(strict=True)
        launch = home_path(java_home)
        if any(type(major) is not int or major not in {17, 21} for major in java_homes):
            raise BuildProcessError("explicit JDK keys must be integer 17 or 21")
        homes = {major: home_path(path) for major, path in java_homes.items()}
        if 21 in homes and homes[21] != launch:
            raise BuildProcessError("Java 21 toolchain home conflicts with the Gradle launch home")
        homes[21] = launch
        required = {major for lane in plan["lanes"] for major in lane["required_java"].values()}
        if required - homes.keys():
            raise BuildProcessError("missing explicit JDK home for a selected compile/runtime major")
        suffix = ".exe" if os.name == "nt" else ""
        paths = {"java": f"bin/java{suffix}", "javac": f"bin/javac{suffix}", "release": "release"}
        snapshots = {major: output_snapshot(home, paths) for major, home in sorted(homes.items())}
        for files in snapshots.values():
            if os.name != "nt" and any(not files[name]["executable"] for name in ("java", "javac")):
                raise BuildProcessError("explicit JDK launchers must be executable")
        observed = {}
        for major, home in sorted(homes.items()):
            probe_env = {**env, "JAVA_HOME": str(home)}
            def probe(binary, *arguments):
                result = subprocess.run([str(home / paths[binary]), *arguments], cwd=repository,
                                        env=probe_env, stdin=subprocess.DEVNULL, capture_output=True,
                                        text=True, timeout=15, check=False)
                if result.returncode != 0:
                    raise BuildProcessError(f"JDK {major} {binary} probe failed")
                return result.stdout + "\n" + result.stderr
            output = probe("java", "-XshowSettings:properties", "-version")
            def property_value(name):
                values = re.findall(r"^\s*" + re.escape(name) + r" = (.+)$", output, re.MULTILINE)
                if len(values) != 1:
                    raise BuildProcessError(f"JDK {major} probe lacks an unambiguous {name}")
                return values[0].strip()
            version = property_value("java.version")
            headers = re.findall(r'^(?:openjdk|java) version "([^"]+)"', output, re.MULTILINE)
            if (not re.match(rf"^{major}(?:\.|$)", version) or headers != [version]
                    or property_value("java.specification.version") != str(major)):
                raise BuildProcessError(f"JDK {major} reported a different Java version")
            actual_home = home_path(property_value("java.home"))
            if actual_home != home:
                raise BuildProcessError(f"JDK {major} reported a different Java home")
            compiler = probe("javac", "-version")
            versions = re.findall(r"^javac (\S+)\s*$", compiler, re.MULTILINE)
            if len(versions) != 1 or not re.match(rf"^{major}(?:\.|$)", versions[0]):
                raise BuildProcessError(f"JDK {major} compiler reported a different Java version")
            observed[str(major)] = {"home": str(home), "java_version": version,
                                    "javac_version": versions[0], "files": snapshots[major]}
        if snapshots != {major: output_snapshot(home, paths) for major, home in sorted(homes.items())}:
            raise BuildProcessError("JDK files changed during toolchain probes")
    except (OSError, ValueError, KeyError, IndexError, TypeError, subprocess.SubprocessError) as exc:
        raise BuildProcessError(f"explicit toolchain validation failed: {exc}") from exc
    flags = [f"-Dorg.gradle.java.home={launch}", "-Dorg.gradle.parallel=false", "-Dorg.gradle.workers.max=1",
             "-Porg.gradle.java.installations.paths=" + ",".join(str(homes[major]) for major in sorted(homes)),
             "-Porg.gradle.java.installations.fromEnv=", "-Porg.gradle.java.installations.auto-detect=false",
             "-Porg.gradle.java.installations.auto-download=false"]
    lanes = [{"artifact_node": lane["artifact_node"], "command": [lane["command"][0], *flags, *lane["command"][1:]],
              "compile_home": str(homes[lane["required_java"]["artifact"]]),
              "runtime_home": str(homes[lane["required_java"]["runtime"]])} for lane in plan["lanes"]]
    return {"status": "probed", "gradle_jvm": "unverified", "compiler_selection": "unverified",
            "homes": observed, "environment": {"JAVA_HOME": str(launch)},
            "property_overrides": flags, "lanes": lanes}


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
