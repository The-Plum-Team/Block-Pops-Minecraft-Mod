#!/usr/bin/env python3
"""Plan or execute serial isolated lane builds; reports never qualify a release."""

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

from scripts.lib.secure_json import SecureJsonError, canonical_json, read as read_secure_json  # noqa: E402
from scripts.release.matrix import (  # noqa: E402
    MAX_MATRIX_BYTES, MatrixDocument, MatrixError, load_matrix_document, normalize_matrix_inventory,
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
        self.observations = {}

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
    """Synchronous POSIX process-tree primitive; reap the owned group before returning."""
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
        result = subprocess.run(["git", "-C", str(repository), *arguments], capture_output=True, check=True,
                                env={**os.environ, "GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": os.devnull})
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
                    len(parts) > 3 and parts[0] in {"common", "fabric", "forge", "neoforge"}
                    and parts[1] == "versions"
                    and parts[3] in {"build", ".gradle", ".architectury-transformer", "run", "logs"}):
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


@contextmanager
def _observation_access(lock):
    if not lock.guard.acquire(blocking=False):
        raise BuildProcessError("observation access requires an idle checkout lease")
    try:
        lock.verify()
        yield
    except (OSError, ValueError, KeyError, TypeError, IndexError) as exc:
        raise BuildProcessError(f"invalid build observation: {exc}") from exc
    finally:
        lock.guard.release()


def _observation_json(repository, path, limit=2 * 1024 * 1024):
    if not 0 < path.lstat().st_size <= limit:
        raise BuildProcessError("observation JSON is outside its size bound")
    before = file_snapshot(repository, path)
    data, payload = read_secure_json(path, label="build observation", max_bytes=limit)
    if not isinstance(data, dict) or before != file_snapshot(repository, path) or hashlib.sha256(payload).hexdigest() != before["sha256"]:
        raise BuildProcessError("observation JSON changed during read")
    return data, before


def _observation_report(lock, run_id):
    if not isinstance(run_id, str) or not re.fullmatch(r"[a-f0-9]{32}", run_id):
        raise BuildProcessError("invalid observation run identity")
    report, _ = _observation_json(lock.repository, lock.repository / "build/build-matrix-report.json", 8 * 1024 * 1024)
    if report.get("status") != "running" or report.get("run_id") != run_id:
        raise BuildProcessError("observation requires the current running report")
    return report


def _verify_jdk_files(toolchains):
    if (toolchains["status"] != "probed" or not isinstance(toolchains["homes"], dict)
            or not set(toolchains["homes"]) <= {"17", "21"}):
        raise BuildProcessError("explicit JDK probes are required")
    for major, row in toolchains["homes"].items():
        home = Path(row["home"])
        suffix = ".exe" if os.name == "nt" else ""
        paths = {"java": f"bin/java{suffix}", "javac": f"bin/javac{suffix}", "release": "release"}
        if (str(home.resolve(strict=True)) != str(home) or not home.is_absolute()
                or any(character in str(home) for character in ",\r\n")
                or canonical_json(output_snapshot(home, paths)) != canonical_json(row["files"])
                or any(not re.match(rf"^{major}(?:\.|$)", row[key]) for key in ("java_version", "javac_version"))):
            raise BuildProcessError("JDK probe identity changed")


def _observation_inputs(repository, binding):
    for name in ("request_file", "init"):
        if file_snapshot(repository, binding[name]["path"]) != binding[name]:
            raise BuildProcessError("observation request/init changed during execution")
    _verify_jdk_files(binding["toolchains"])


def prepare_observation(lock: CheckoutLock, run_id: str, toolchains: dict[str, Any], artifact_node: str):
    """Bind one fresh request; the private lease state owns validation expectations."""
    with _observation_access(lock):
        report = _observation_report(lock, run_id)
        plan = report["plan"]
        canonical = plan_build(Path(plan["matrix"]["path"]),
            scope="full" if plan["scope"] == "lane" else plan["scope"],
            artifact_node=plan["selected_nodes"][0] if plan["scope"] == "lane" else None,
            clean=any(arg.endswith(":clean") for arg in plan["lanes"][0]["command"]),
            windows=plan["lanes"][0]["command"][0] == "gradlew.bat")
        if (canonical_json(plan) != canonical_json(canonical)
                or canonical_json(source_snapshot(lock.repository)) != canonical_json(report["source"])):
            raise BuildProcessError("planned source or matrix changed before observation")
        lanes = [row for row in plan["lanes"] if row["artifact_node"] == artifact_node]
        if len(lanes) != 1:
            raise BuildProcessError("observation lane is outside the selected plan")
        lane = lanes[0]
        _verify_jdk_files(toolchains)
        homes = {int(major): Path(row["home"]) for major, row in toolchains["homes"].items()}
        major = lane["required_java"]["artifact"]
        flags = _toolchain_flags(homes)
        expected = {"artifact_node": artifact_node, "command": [lane["command"][0], *flags, *lane["command"][1:]],
                    "compile_home": str(homes[major]), "runtime_home": str(homes[lane["required_java"]["runtime"]])}
        selected = [row for row in toolchains["lanes"] if row["artifact_node"] == artifact_node]
        if canonical_json(selected) != canonical_json([expected]) or toolchains["environment"] != {"JAVA_HOME": str(homes[21])}:
            raise BuildProcessError("toolchain command binding differs from the selected plan")
        suffix = f":{lane['minecraft']}" if lane["build_layout"] == "stonecutter" else ""
        projects = [f":common{suffix}", f":{lane['loader']}{suffix}"]
        compile_tasks = [f"{project}:compileJava" for project in projects] + [f"{projects[1]}:compileE2eJava"]
        destinations = {}
        for task in compile_tasks:
            module = task.split(":")[1]
            base = lock.repository / module
            if suffix:
                base = base / "versions" / lane["minecraft"]
            destinations[task] = str(base / "build/classes/java" / ("e2e" if task.endswith("compileE2eJava") else "main"))
        optional_destinations = {f"{project}:compileTestJava": str(Path(destinations[f"{project}:compileJava"]).with_name("test"))
                                 for project in projects}
        start = lane["command"].index("validateReleaseMatrix")
        if start and lane["command"][start - 1].endswith(":clean"):
            start -= 1
        request = {"schema_version": 1, "run_id": run_id, "artifact_node": artifact_node,
                   "repository": str(lock.repository), "caller_source": {**{key: value for key, value in report["source"].items()
                    if key != "files"}, "matrix_sha256": plan["matrix"]["sha256"]}, "gradle_home": str(homes[21]),
                   "compile_home": str(homes[major]), "compile_major": major, "requested_tasks": lane["command"][start:],
                   "compile_tasks": compile_tasks, "projects": projects}
        payload = canonical_json(request)
        if len(payload) > 65536:
            raise BuildProcessError("observation request exceeds its bounded protocol")
        init = file_snapshot(lock.repository, "gradle/build-observation.init.gradle")
        directory = lock.repository / "build"
        for part in ("observations", run_id, artifact_node):
            directory = directory / part
            directory.mkdir(exist_ok=part != artifact_node)
            if _linked(directory.lstat()) or not directory.is_dir():
                raise BuildProcessError("observation directory must not be linked")
        path = directory / "request.json"
        with path.open("xb") as stream:
            stream.write(payload)
        request_file = file_snapshot(lock.repository, path)
        command = [expected["command"][0], "--no-configuration-cache", "--init-script",
                   str(lock.repository / init["path"]), f"-Dblockpops.observation.request={path}", *expected["command"][1:]]
        lock.observations[(run_id, artifact_node)] = json.loads(json.dumps({"request": request, "request_file": request_file,
            "init": init, "source": report["source"], "toolchains": toolchains, "destinations": destinations,
            "optional_destinations": optional_destinations}))
        return {"command": command, "request": str(path), "receipt": str(directory / "observation.json")}


def validate_observation(lock: CheckoutLock, run_id: str, artifact_node: str):
    """Validate selected/finished compilers and current class outputs; never qualify a release."""
    with _observation_access(lock):
        report = _observation_report(lock, run_id)
        binding = lock.observations.get((run_id, artifact_node))
        if binding is None:
            raise BuildProcessError("observation does not belong to this checkout lease")
        request = binding["request"]
        _observation_inputs(lock.repository, binding)
        receipt_path = lock.repository / binding["request_file"]["path"]
        receipt, receipt_file = _observation_json(lock.repository, receipt_path.with_name("observation.json"))
        expected = {"schema_version": 1, "run_id": run_id, "artifact_node": artifact_node,
                    "request_sha256": binding["request_file"]["sha256"], "caller_source": request["caller_source"],
                    "status": "completed", "gradle_jvm": {"home": request["gradle_home"], "major": 21}}
        if canonical_json({key: value for key, value in receipt.items() if key != "compilers"}) != canonical_json(expected):
            raise BuildProcessError("observation receipt identity or JVM differs from the bound request")
        destinations = {**binding["destinations"], **binding["optional_destinations"]}
        if (not isinstance(receipt["compilers"], dict)
                or not set(request["compile_tasks"]) <= set(receipt["compilers"]) <= set(destinations)):
            raise BuildProcessError("observation receipt has missing required or unexpected compiler scope")
        classes = {}
        for task, row in receipt["compilers"].items():
            selected, outcome = row["selected"], row["outcome"]
            probe = binding["toolchains"]["homes"][str(request["compile_major"])]
            version = selected["version"]
            expected_selection = {"home": request["compile_home"], "major": request["compile_major"],
                "release": request["compile_major"], "version": version,
                "executable": str(Path(probe["home"]) / probe["files"]["javac"]["path"]),
                "destination": destinations[task]}
            compiled = {"did_work": True, "skipped": False, "up_to_date": False, "no_source": False, "skip_message": None, "failed": False}
            unchanged = {"did_work": False, "skipped": True, "up_to_date": True, "no_source": False, "skip_message": "UP-TO-DATE", "failed": False}
            empty = {"did_work": False, "skipped": True, "up_to_date": False, "no_source": True, "skip_message": "NO-SOURCE", "failed": False}
            no_source = task in binding["optional_destinations"] and canonical_json(outcome) == canonical_json(empty)
            if (set(row) != {"selected", "outcome"} or canonical_json(selected) != canonical_json(expected_selection)
                    or not re.fullmatch(re.escape(probe["java_version"]) + r"(?:\+[A-Za-z0-9.+-]+)?", version)
                    or (not no_source and canonical_json(outcome) not in (canonical_json(compiled), canonical_json(unchanged)))):
                raise BuildProcessError("compiler metadata or execution outcome is invalid")
            destination = Path(selected["destination"])
            for parent in (destination, *destination.parents):
                if parent == lock.repository:
                    break
                try:
                    metadata = parent.lstat()
                except FileNotFoundError:
                    if no_source:
                        continue
                    raise
                if _linked(metadata) or not stat.S_ISDIR(metadata.st_mode):
                    raise BuildProcessError("compiler output directory must be real and inside its build root")
            outputs = []
            for path in sorted(destination.rglob("*")):
                if _linked(path.lstat()):
                    raise BuildProcessError("compiler outputs must not contain links")
                if not path.is_dir():
                    output = file_snapshot(lock.repository, path)
                    if path.suffix == ".class":
                        outputs.append(output)
            if (no_source and outputs) or (not no_source and not outputs):
                raise BuildProcessError("compiler class outputs disagree with its execution outcome")
            classes[task] = outputs
        if (canonical_json(source_snapshot(lock.repository)) != canonical_json(binding["source"])
                or canonical_json(report["source"]) != canonical_json(binding["source"])):
            raise BuildProcessError("source or matrix changed during observation")
        _observation_inputs(lock.repository, binding)
        if file_snapshot(lock.repository, receipt_file["path"]) != receipt_file or any(
            file_snapshot(lock.repository, row["path"]) != row for rows in classes.values() for row in rows
        ):
            raise BuildProcessError("receipt or compiler output changed during validation")
        del lock.observations[(run_id, artifact_node)]
        return {"status": "observed", "receipt": receipt_file, "compilers": receipt["compilers"], "classes": classes}


def _toolchain_flags(homes):
    return [f"-Dorg.gradle.java.home={homes[21]}", "-Dorg.gradle.parallel=false", "-Dorg.gradle.workers.max=1",
            "-Porg.gradle.java.installations.paths=" + ",".join(str(homes[major]) for major in sorted(homes)),
            "-Porg.gradle.java.installations.fromEnv=", "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.auto-download=false"]


def verify_toolchains(plan: dict[str, Any], java_home: Path, java_homes: dict[int, Path],
                      *, environment: dict[str, str] | None = None) -> dict[str, Any]:
    """Probe explicit JDKs only; callers must reuse the validated environment plus overlay.

    Bindings override persistent Gradle properties. Gradle JVM/compiler observation
    remains required during execution; these probes do not certify a build.
    """
    env = dict(os.environ if environment is None else environment)
    for name, value in env.items():
        if name.startswith("ORG_GRADLE_PROJECT_") and value:
            raise BuildProcessError(f"{name} must be empty for explicit toolchain binding")
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
    flags = _toolchain_flags(homes)
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
        document.gradle_context(artifact_node=lane.identity.artifact_node)
        artifact, runtime = lane.artifact, lane.runtime
        if artifact["java"] not in {17, 21} or runtime["java"] not in {17, 21}:
            raise MatrixError("the serial runner requires artifact/runtime Java 17 or 21")
        home = repository / "build/gradle-home" / lane.identity.artifact_node
        command = [wrapper, "--no-daemon", "--no-parallel", "--max-workers=1",
                   "--dependency-verification", "strict", "--gradle-user-home", str(home),
                   f"-PblockpopsLane={lane.identity.artifact_node}"]
        if clean:
            command.append(artifact["gradle_task"].rsplit(":", 1)[0] + ":clean")
        command.extend(["validateReleaseMatrix", artifact["gradle_task"], artifact["harness_task"], "check"])
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


@contextmanager
def _lane_log(lock, path):
    """Anchor creation to owned directory descriptors, never a replaceable parent link."""
    lock.verify()
    relative = path.relative_to(lock.repository / "build")
    if ".." in relative.parts or relative.name != "gradle.log":
        raise BuildProcessError("lane log must stay in its build directory")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory = os.open(lock.path.parent, flags)
    try:
        if _identity(os.fstat(directory)) != lock.parent:
            raise BuildProcessError("lane log build directory identity changed")
        for part in relative.parts[:-1]:
            child = os.open(part, flags, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(relative.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=directory)
        with os.fdopen(descriptor, "wb") as output:
            yield output
    finally:
        os.close(directory)


def execute_build(matrix_path: Path, *, java_home: Path, java_homes: dict[int, Path],
                  scope: str = "full", artifact_node: str | None = None, clean: bool = False,
                  environment: dict[str, str] | None = None) -> dict[str, Any]:
    """Fail-fast build diagnostics; process exit, observations and archive boundaries are separate evidence."""
    from scripts.release.artifact_manifest import verify_harness_jar, verify_production_jar
    plan = plan_build(matrix_path, scope=scope, artifact_node=artifact_node, clean=clean)
    repository = Path(plan["source"]["repository"])
    env, results = dict(os.environ if environment is None else environment), []
    def unchanged_outputs():
        for completed in results:
            lane = next(row for row in plan["lanes"] if row["artifact_node"] == completed["artifact_node"])
            if canonical_json(output_snapshot(repository, lane["outputs"])) != canonical_json(completed["outputs"]):
                raise BuildProcessError("an earlier or current lane archive changed")
    with checkout_lock(repository) as lock:
        run_id = begin_report(lock, plan)
        try:
            if plan["lanes"][0]["command"][0] == "gradlew.bat":
                raise BuildProcessError("Windows owned process-tree cleanup is not implemented")
            toolchains = verify_toolchains(plan, java_home, java_homes, environment=env)
            env.update(toolchains["environment"])
            report = _observation_report(lock, run_id)
            report["toolchains"] = toolchains
            _atomic_report(lock, report)
            document = load_matrix_document(Path(plan["matrix"]["path"]))
            for lane in plan["lanes"]:
                node = lane["artifact_node"]
                bound = prepare_observation(lock, run_id, toolchains, node)
                unchanged_outputs()
                result = {"artifact_node": node, "command": bound["command"], "exit_code": None, "outputs": {}}
                results.append(result)
                report["lanes"] = results
                _atomic_report(lock, report)
                log = Path(bound["request"]).with_name("gradle.log")
                with _lane_log(lock, log) as output:
                    result["exit_code"] = run_lane(bound["command"], lock=lock, env=env, output=output)
                result["log"] = file_snapshot(repository, log)
                if type(result["exit_code"]) is not int or result["exit_code"] != 0:
                    raise BuildProcessError(f"lane {node} exited with {result['exit_code']}")
                result["observation"] = validate_observation(lock, run_id, node)
                result["outputs"] = output_snapshot(repository, lane["outputs"])
                artifact = document.inventory.lane(node).artifact
                verify_production_jar(Path(lane["outputs"]["production"]), artifact)
                verify_harness_jar(Path(lane["outputs"]["harness"]), artifact)
                unchanged_outputs()
                result["archive_validation"] = "boundary-and-metadata"
                _atomic_report(lock, report)
            return finish_report(lock, run_id, results)
        except BaseException as exc:
            failed = finish_report(lock, run_id, results, error=f"{type(exc).__name__}: {exc}")
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            return failed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true", help="emit a pure plan without probing JDKs or building")
    parser.add_argument("--java-home", type=Path, help="explicit Java 21 home for Gradle (required for execution)")
    parser.add_argument("--java17-home", type=Path, help="explicit compile/runtime Java 17 home when selected")
    parser.add_argument("--java21-home", type=Path, help="optional Java 21 toolchain home; must equal --java-home")
    parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--scope", choices=("full", "legacy"))
    selection.add_argument("--artifact-node")
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args(argv)
    if args.plan and any((args.java_home, args.java17_home, args.java21_home)):
        parser.error("--plan cannot be combined with execution JDK homes")
    if not args.plan and args.java_home is None:
        parser.error("execution requires --java-home; use --plan for planning only")
    try:
        options = dict(scope=args.scope or "full", artifact_node=args.artifact_node, clean=args.clean)
        if args.plan:
            result = plan_build(args.matrix, **options)
        else:
            homes = {major: home for major, home in ((17, args.java17_home), (21, args.java21_home)) if home is not None}
            result = execute_build(args.matrix, java_home=args.java_home, java_homes=homes, **options)
    except KeyboardInterrupt:
        print("build matrix interrupted; execution did not complete", file=sys.stderr)
        return 130
    except (MatrixError, SecureJsonError, BuildProcessError, OSError) as exc:
        print(f"build matrix failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2))
    return 0 if result["status"] in {"planned", "success"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
