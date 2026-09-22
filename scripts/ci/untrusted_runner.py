#!/usr/bin/env python3
"""Run candidate-controlled workloads under a disposable, credentialless OS user.

GitHub's artifact/cache credentials are injected into the runner process around JavaScript
actions.  Merely unsetting those variables in a child shell is insufficient: candidate code can
leave a process behind or modify the cached action implementation before a later upload step.
This helper copies the candidate tree into a directory owned by a fresh non-sudo user, invokes
commands with an explicit empty environment, and kills/locks that identity before returning any
declared output to the trusted runner user.

The helper itself is protected controller code.  It never evaluates or interpolates the command:
the argv after ``--`` is passed directly to exec through ``sudo`` (a protected workflow may choose
``bash -c`` as that argv). GitHub command-file paths, Actions runtime credentials, runner workspace
paths, proxy configuration, and ambient secrets are never passed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pwd
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

STATE_NAME = "sandbox-state.json"
USER_NAME = "blockpops_candidate"
VALIDATOR_USER_NAME = "blockpops_validator"
TERMINATION_GRACE_SECONDS = 15.0
MAX_STATE_BYTES = 16 * 1024
MAX_ENV_BYTES = 256 * 1024
MAX_EXPORTS = 16
MAX_EXPORT_FILES = 10_000
MAX_EXPORT_ENTRIES = 20_000
MAX_EXPORT_FILE_BYTES = 1024 * 1024 * 1024
MAX_EXPORT_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
SANDBOX_BOUNDARY = Path("/tmp/blockpops-sandbox-boundary")
SAFE_ENV_NAMES = frozenset(
    {
        "BLOCKPOPS_TESTED_SHA",
        "BLOCKPOPS_PROJECTION",
        "BLOCKPOPS_REPOSITORY",
        "BLOCKPOPS_RUN_ATTEMPT",
        "BLOCKPOPS_RUN_ID",
        "BLOCKPOPS_SOURCE_BRANCH",
        "BLOCKPOPS_TREE",
        "E2E_ROW_JSON",
        "E2E_SCENARIOS",
        "GALLIUM_DRIVER",
        "LIBGL_ALWAYS_SOFTWARE",
        "MATRIX_KIND",
        "SOURCE_DATE_EPOCH",
        "__GLX_VENDOR_LIBRARY_NAME",
    }
)
FORBIDDEN_ENV_PREFIXES = ("ACTIONS_", "GITHUB_", "RUNNER_")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
BRANCH = re.compile(r"^(?!/)(?!.*(?:\.\.|//))[A-Za-z0-9._/-]{1,200}$")
GENERATED_TOP_LEVEL = frozenset(
    {
        ".gradle",
        "build",
        "e2e-out",
        "logs",
        "out",
        "packaged-e2e-aggregate",
        "packaged-e2e-lanes",
        "run",
    }
)
GENERATED_MODULES = frozenset({"common", "fabric", "forge", "neoforge"})


class SandboxError(ValueError):
    """The requested sandbox operation is unsafe or inconsistent."""


def _run(arguments: Iterable[str], *, accepted: frozenset[int] = frozenset({0})) -> bytes:
    command = list(arguments)
    result = subprocess.run(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        env={
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
        },
    )
    if result.returncode not in accepted:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise SandboxError(detail or f"sandbox command failed with {result.returncode}")
    return result.stdout


def _root(value: Path) -> Path:
    expected = SANDBOX_BOUNDARY / "blockpops-candidate-sandbox"
    if value.absolute() != expected:
        raise SandboxError("sandbox root must be the exact dedicated boundary child")
    resolved = value.resolve()
    if resolved != expected:
        raise SandboxError("sandbox root boundary resolves outside its exact path")
    return resolved


def _state_path(root: Path) -> Path:
    return root / STATE_NAME


def _read_state(root: Path, *, sealed: bool | None = None) -> dict[str, Any]:
    path = _state_path(root)
    metadata = path.lstat()
    if (
        not stat.S_ISREG(metadata.st_mode)
        or path.is_symlink()
        or metadata.st_uid != os.getuid()
        or not 1 <= metadata.st_size <= MAX_STATE_BYTES
        or stat.S_IMODE(metadata.st_mode) != 0o600
    ):
        raise SandboxError("sandbox state file is not a private runner-owned regular file")
    try:
        value = json.loads(path.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise SandboxError(f"cannot read sandbox state: {exc}") from exc
    if set(value) != {
        "schema_version",
        "root",
        "repository",
        "controller",
        "source",
        "source_commit",
        "source_tree",
        "user",
        "uid",
        "sealed",
    }:
        raise SandboxError("sandbox state schema is unknown")
    if (
        value["schema_version"] != 2
        or value["root"] != str(root)
        or value["repository"] != str(root / "repository")
        or value["controller"] != str(root / "controller")
        or not isinstance(value["source"], str)
        or not Path(value["source"]).is_absolute()
        or SHA1.fullmatch(value["source_commit"]) is None
        or SHA1.fullmatch(value["source_tree"]) is None
        or value["user"] != USER_NAME
        or isinstance(value["uid"], bool)
        or not isinstance(value["uid"], int)
        or value["uid"] <= 0
        or not isinstance(value["sealed"], bool)
    ):
        raise SandboxError("sandbox state identity is invalid")
    if sealed is not None and value["sealed"] is not sealed:
        raise SandboxError("sandbox lifecycle state is invalid")
    record = pwd.getpwnam(USER_NAME)
    if record.pw_uid != value["uid"] or Path(record.pw_dir) != root / "home":
        raise SandboxError("sandbox operating-system identity changed")
    return value


def _write_state(root: Path, value: dict[str, Any]) -> None:
    path = _state_path(root)
    temporary = root / f".{STATE_NAME}.tmp"
    payload = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if len(payload) > MAX_STATE_BYTES:
        raise SandboxError("sandbox state is oversized")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _source_identity(source: Path) -> tuple[str, str]:
    status = _run(
        ("git", "-C", str(source), "status", "--porcelain=v1", "--untracked-files=all")
    )
    if status:
        raise SandboxError("authenticated candidate checkout is not clean")
    commit = _run(("git", "-C", str(source), "rev-parse", "HEAD")).decode().strip()
    tree = _run(("git", "-C", str(source), "rev-parse", "HEAD^{tree}")).decode().strip()
    if SHA1.fullmatch(commit) is None or SHA1.fullmatch(tree) is None:
        raise SandboxError("authenticated candidate checkout has an invalid Git identity")
    return commit, tree


def _tracked_paths(source: Path) -> tuple[str, ...]:
    raw = _run(("git", "-C", str(source), "ls-files", "--cached", "-z"))
    try:
        values = tuple(item.decode("utf-8") for item in raw.split(b"\0") if item)
    except UnicodeDecodeError as exc:
        raise SandboxError("tracked candidate paths must be UTF-8") from exc
    if not values or len(values) != len(set(values)):
        raise SandboxError("tracked candidate path inventory is empty or duplicated")
    for value in values:
        parsed = PurePosixPath(value)
        if (
            parsed.is_absolute()
            or parsed.as_posix() != value
            or any(part in {"", ".", "..", ".git"} for part in parsed.parts)
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
        ):
            raise SandboxError("tracked candidate path inventory is unsafe")
    return values


def _file_digest(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            total += len(chunk)
            digest.update(chunk)
    return total, digest.hexdigest()


def _generated_path(relative: str) -> bool:
    parts = PurePosixPath(relative).parts
    return bool(
        parts
        and (
            parts[0] in GENERATED_TOP_LEVEL
            or (
                len(parts) >= 2
                and parts[0] in GENERATED_MODULES
                and parts[1] in {".gradle", "build", "logs", "out", "run"}
            )
        )
    )


def _restore_authenticated_tree(state: dict[str, Any]) -> None:
    """Prove source bytes are immutable and replace candidate-controlled Git metadata."""

    source = Path(state["source"])
    repository = Path(state["repository"])
    commit, tree = _source_identity(source)
    if commit != state["source_commit"] or tree != state["source_tree"]:
        raise SandboxError("authenticated candidate checkout changed during sandbox execution")
    tracked = _tracked_paths(source)
    tracked_set = set(tracked)
    ancestors = {
        PurePosixPath(*PurePosixPath(relative).parts[:index]).as_posix()
        for relative in tracked
        for index in range(1, len(PurePosixPath(relative).parts))
    }
    for relative in tracked:
        expected = source / relative
        actual = repository / relative
        try:
            expected_metadata = expected.lstat()
            actual_metadata = actual.lstat()
        except FileNotFoundError as exc:
            raise SandboxError(f"tracked candidate path {relative!r} is missing") from exc
        if stat.S_ISLNK(expected_metadata.st_mode):
            if not stat.S_ISLNK(actual_metadata.st_mode) or os.readlink(expected) != os.readlink(
                actual
            ):
                raise SandboxError(f"tracked candidate symlink {relative!r} changed")
        elif stat.S_ISREG(expected_metadata.st_mode):
            if not stat.S_ISREG(actual_metadata.st_mode):
                raise SandboxError(f"tracked candidate file {relative!r} changed type")
            if (
                bool(expected_metadata.st_mode & stat.S_IXUSR)
                != bool(actual_metadata.st_mode & stat.S_IXUSR)
                or _file_digest(expected) != _file_digest(actual)
            ):
                raise SandboxError(f"tracked candidate file {relative!r} changed")
        else:
            raise SandboxError(f"tracked candidate path {relative!r} has an unsupported type")

    pending = [repository]
    while pending:
        directory = pending.pop()
        for entry in os.scandir(directory):
            path = Path(entry.path)
            relative = path.relative_to(repository).as_posix()
            if relative == ".git":
                continue
            if _generated_path(relative):
                if path.is_symlink() or not entry.is_dir(follow_symlinks=False):
                    raise SandboxError(
                        f"generated candidate root {relative!r} is not a real directory"
                    )
                continue
            if relative in tracked_set:
                continue
            if relative not in ancestors:
                raise SandboxError(f"candidate created an undeclared path {relative!r}")
            if path.is_symlink() or not entry.is_dir(follow_symlinks=False):
                raise SandboxError(f"tracked candidate directory {relative!r} changed type")
            pending.append(path)

    candidate_git = repository / ".git"
    if candidate_git.is_symlink() or (candidate_git.exists() and not candidate_git.is_dir()):
        candidate_git.unlink()
    elif candidate_git.exists():
        shutil.rmtree(candidate_git)
    source_git = source / ".git"
    if not source_git.exists() or source_git.is_symlink():
        raise SandboxError("authenticated checkout lacks safe Git metadata")
    _run(("cp", "-a", "--", str(source_git), str(candidate_git)))


def _refresh_restored_index(state: dict[str, Any]) -> None:
    """Bind the restored index stat cache to the sealed, runner-owned checkout."""

    repository = Path(state["repository"])
    git = ("/usr/bin/git", "-c", "core.fsmonitor=false", "-C", str(repository))
    commit = _run((*git, "rev-parse", "HEAD")).decode("ascii", "strict").strip()
    tree = _run((*git, "rev-parse", "HEAD^{tree}")).decode("ascii", "strict").strip()
    if commit != state["source_commit"] or tree != state["source_tree"]:
        raise SandboxError("restored candidate Git metadata has the wrong identity")
    _run((*git, "update-index", "--really-refresh"))
    _run((*git, "diff-index", "--no-ext-diff", "--quiet", state["source_commit"], "--"))


def prepare(
    root_value: Path,
    source_value: Path,
    controller_source_value: Path,
    seed_gradle_value: Path | None,
    overlay_values: tuple[tuple[Path, str], ...],
) -> dict[str, Any]:
    root = _root(root_value)
    boundary = root.parent
    try:
        boundary.mkdir(mode=0o711)
    except FileExistsError:
        pass
    boundary_metadata = boundary.lstat()
    if (
        boundary.is_symlink()
        or not stat.S_ISDIR(boundary_metadata.st_mode)
        or boundary_metadata.st_uid != os.getuid()
        or stat.S_IMODE(boundary_metadata.st_mode) != 0o711
    ):
        raise SandboxError("sandbox boundary is not a private runner-owned traversal directory")
    source = source_value.resolve()
    controller_source = controller_source_value.resolve()
    if (
        root.exists()
        or not source.is_dir()
        or not controller_source.is_dir()
        or source == controller_source
        or source == root
        or controller_source == root
        or root in source.parents
        or root in controller_source.parents
        or source in controller_source.parents
        or controller_source in source.parents
    ):
        raise SandboxError("sandbox source/root topology is unsafe")
    source_commit, source_tree = _source_identity(source)
    try:
        pwd.getpwnam(USER_NAME)
    except KeyError:
        pass
    else:
        raise SandboxError("sandbox user unexpectedly already exists")

    root.mkdir(mode=0o755)
    home = root / "home"
    repository = root / "repository"
    gradle = root / "gradle-home"
    temporary = root / "tmp"
    controller = root / "controller"
    for path in (home, repository, controller, gradle, temporary):
        path.mkdir(mode=0o700 if path == home else 0o755)
    _run(
        (
            "sudo",
            "-n",
            "useradd",
            "--create-home",
            "--home-dir",
            str(home),
            "--shell",
            "/bin/bash",
            "--user-group",
            USER_NAME,
        )
    )
    record = pwd.getpwnam(USER_NAME)
    if record.pw_uid <= 0 or record.pw_gid <= 0:
        raise SandboxError("sandbox user must be unprivileged")
    _run(("cp", "-a", "--", f"{source}/.", str(repository)))
    _run(("cp", "-a", "--", f"{controller_source}/.", str(controller)))
    _run(("chmod", "-R", "go-w", str(controller)))
    if len(overlay_values) > MAX_EXPORTS:
        raise SandboxError("sandbox overlay inventory is oversized")
    seen_overlay_sources: set[Path] = set()
    seen_overlay_targets: set[str] = set()
    runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve()
    for source_overlay_value, raw_target in overlay_values:
        source_overlay = source_overlay_value.absolute()
        metadata = source_overlay.lstat()
        if (
            source_overlay.is_symlink()
            or not stat.S_ISDIR(metadata.st_mode)
            or source_overlay.resolve().parent != runner_temp
        ):
            raise SandboxError("sandbox overlay must be a real direct RUNNER_TEMP directory")
        source_overlay = source_overlay.resolve()
        target_relative = _relative(raw_target)
        if (
            source_overlay in seen_overlay_sources
            or target_relative in seen_overlay_targets
        ):
            raise SandboxError("sandbox overlays must have unique sources and targets")
        seen_overlay_sources.add(source_overlay)
        seen_overlay_targets.add(target_relative)
        target = repository / target_relative
        current = repository
        for part in PurePosixPath(target_relative).parent.parts:
            current /= part
            if current.exists() or current.is_symlink():
                current_metadata = current.lstat()
                if current.is_symlink() or not stat.S_ISDIR(current_metadata.st_mode):
                    raise SandboxError("sandbox overlay parent is not a real directory")
            else:
                current.mkdir(mode=0o755)
        if target.exists() or target.is_symlink():
            raise SandboxError("sandbox candidate already owns an overlay target")
        target.mkdir(mode=0o755)
        _run(("cp", "-a", "--", f"{source_overlay}/.", str(target)))
    if seed_gradle_value is not None:
        seed = seed_gradle_value.absolute()
        metadata = seed.lstat()
        if seed.is_symlink() or not stat.S_ISDIR(metadata.st_mode):
            raise SandboxError("Gradle cache seed must be a real directory")
        _validate_export_tree(
            seed,
            "Gradle cache seed",
            max_entries=250_000,
            max_files=200_000,
            max_file_bytes=2 * 1024 * 1024 * 1024,
            max_total_bytes=20 * 1024 * 1024 * 1024,
        )
        _run(("cp", "-a", "--", f"{seed}/.", str(gradle)))
    _run(
        (
            "sudo",
            "-n",
            "chown",
            "-hR",
            f"{record.pw_uid}:{record.pw_gid}",
            str(home),
            str(repository),
            str(gradle),
            str(temporary),
        )
    )
    _refresh_candidate_index(root, repository, record.pw_uid)
    state = {
        "schema_version": 2,
        "root": str(root),
        "repository": str(repository),
        "controller": str(controller),
        "source": str(source),
        "source_commit": source_commit,
        "source_tree": source_tree,
        "user": USER_NAME,
        "uid": record.pw_uid,
        "sealed": False,
    }
    _write_state(root, state)
    return state


def _candidate_environment(root: Path, pass_names: tuple[str, ...]) -> list[str]:
    python = Path(sys.executable).resolve()
    java_home = os.environ.get("JAVA_HOME", "")
    if java_home and not Path(java_home).is_dir():
        raise SandboxError("JAVA_HOME is invalid")
    paths = [
        str(python.parent),
        "/usr/local/sbin",
        "/usr/local/bin",
        "/usr/sbin",
        "/usr/bin",
        "/sbin",
        "/bin",
    ]
    if java_home:
        paths.insert(1, str(Path(java_home) / "bin"))
    values = {
        "CI": "true",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "GRADLE_USER_HOME": str(root / "gradle-home"),
        "HOME": str(root / "home"),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "LOGNAME": USER_NAME,
        "PATH": ":".join(paths),
        "PYTHONUNBUFFERED": "1",
        "PYTHONPYCACHEPREFIX": str(root / "tmp" / "pycache"),
        "SHELL": "/bin/bash",
        "TERM": "dumb",
        "TMPDIR": str(root / "tmp"),
        "TZ": "UTC",
        "USER": USER_NAME,
    }
    if java_home:
        values["JAVA_HOME"] = java_home
    for name in pass_names:
        if (
            name not in SAFE_ENV_NAMES
            or name.startswith(FORBIDDEN_ENV_PREFIXES)
            or name not in os.environ
        ):
            raise SandboxError(f"candidate environment name {name!r} is not allowed")
        value = os.environ[name]
        if "\0" in value or len(value.encode("utf-8")) > 128 * 1024:
            raise SandboxError(f"candidate environment value {name!r} is unsafe")
        values[name] = value
    if "BLOCKPOPS_TESTED_SHA" in values and SHA1.fullmatch(values["BLOCKPOPS_TESTED_SHA"]) is None:
        raise SandboxError("candidate tested SHA is invalid")
    if "BLOCKPOPS_TREE" in values and SHA1.fullmatch(values["BLOCKPOPS_TREE"]) is None:
        raise SandboxError("candidate tree is invalid")
    if "BLOCKPOPS_REPOSITORY" in values and REPOSITORY.fullmatch(values["BLOCKPOPS_REPOSITORY"]) is None:
        raise SandboxError("candidate repository identity is invalid")
    if "BLOCKPOPS_SOURCE_BRANCH" in values and BRANCH.fullmatch(values["BLOCKPOPS_SOURCE_BRANCH"]) is None:
        raise SandboxError("candidate source branch is invalid")
    if values.get("BLOCKPOPS_PROJECTION") not in {None, "pr-anchors", "scheduled-anchors"}:
        raise SandboxError("candidate projection is invalid")
    for name in ("BLOCKPOPS_RUN_ID", "BLOCKPOPS_RUN_ATTEMPT"):
        if name in values and re.fullmatch(r"[1-9][0-9]{0,19}", values[name]) is None:
            raise SandboxError(f"candidate numeric identity {name!r} is invalid")
    encoded = [f"{name}={value}" for name, value in sorted(values.items())]
    if sum(len(item.encode("utf-8")) for item in encoded) > MAX_ENV_BYTES:
        raise SandboxError("candidate environment exceeds its total byte budget")
    return encoded


def _emit_bounded_log(label: str, payload: bytes, *, truncated: bool) -> None:
    text = payload.decode("utf-8", "replace")
    for line in text.splitlines():
        safe = "".join(character if character == "\t" or ord(character) >= 32 else "?" for character in line)
        print(f"[{label}] {safe}")
    if truncated:
        print(f"[{label}] output exceeded {MAX_LOG_BYTES} bytes and was truncated")


def _execute_untrusted(
    *, uid: int, cwd: Path, environment: list[str], command: tuple[str, ...], label: str
) -> int:
    process = subprocess.Popen(
        [
            "sudo",
            "-n",
            "--user",
            f"#{uid}",
            "--",
            "/usr/bin/setpriv",
            "--no-new-privs",
            "--",
            "/usr/bin/env",
            "-i",
            *environment,
            *command,
        ],
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    assert process.stdout is not None
    captured = bytearray()
    truncated = False
    while chunk := process.stdout.read(64 * 1024):
        remaining = MAX_LOG_BYTES - len(captured)
        if remaining > 0:
            captured.extend(chunk[:remaining])
        if len(chunk) > remaining:
            truncated = True
    return_code = process.wait()
    _emit_bounded_log(label, bytes(captured), truncated=truncated)
    return return_code


def _refresh_candidate_index(root: Path, repository: Path, uid: int) -> None:
    """Refresh copy/chown stat metadata without inheriting runner credentials."""

    environment = _candidate_environment(root, ())
    for command in (
        ("/usr/bin/git", "update-index", "--really-refresh"),
        ("/usr/bin/git", "diff-index", "--quiet", "HEAD", "--"),
    ):
        if _execute_untrusted(
            uid=uid,
            cwd=repository,
            environment=environment,
            command=command,
            label="prepare",
        ):
            raise SandboxError("copied candidate index could not be refreshed cleanly")


def run_candidate(
    root_value: Path, command: tuple[str, ...], pass_names: tuple[str, ...]
) -> int:
    root = _root(root_value)
    state = _read_state(root, sealed=False)
    if not command or any("\0" in item for item in command):
        raise SandboxError("candidate command is empty or unsafe")
    environment = _candidate_environment(root, pass_names)
    def interrupted(signum: int, _frame: Any) -> None:
        raise SandboxError(f"candidate command interrupted by signal {signum}")

    previous = {
        signum: signal.signal(signum, interrupted)
        for signum in (signal.SIGINT, signal.SIGTERM)
    }
    try:
        return _execute_untrusted(
            uid=state["uid"],
            cwd=Path(state["repository"]),
            environment=environment,
            command=command,
            label="candidate",
        )
    finally:
        _terminate_identity(state["uid"], USER_NAME)
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def _terminate_identity(uid: int, name: str) -> None:
    """Kill every process for one sandbox UID and make future logins impossible."""

    effective = b""
    real = b""
    try:
        # Check both effective and real identities.  A setuid transition must not let a child
        # survive until the host-side artifact action receives its runtime credential.
        # SIGKILL is asynchronous, and a large JVM such as a dedicated server can still be
        # listed for a moment while the kernel tears it down, so the sweep repeats until
        # nothing is listed; only a process that outlasts the bounded grace is an escape.
        deadline = time.monotonic() + TERMINATION_GRACE_SECONDS
        while True:
            for _attempt in range(2):
                _run(
                    ("sudo", "-n", "pkill", "-KILL", "-u", str(uid)),
                    accepted=frozenset({0, 1}),
                )
                _run(
                    ("sudo", "-n", "pkill", "-KILL", "-U", str(uid)),
                    accepted=frozenset({0, 1}),
                )
            effective = _run(
                ("pgrep", "-u", str(uid)), accepted=frozenset({0, 1})
            ).strip()
            real = _run(
                ("pgrep", "-U", str(uid)), accepted=frozenset({0, 1})
            ).strip()
            if not (effective or real) or time.monotonic() >= deadline:
                break
            time.sleep(0.25)
    finally:
        # Lock even on the adversarial path where a process survived the bounded kill sweep.
        _run(
            (
                "sudo",
                "-n",
                "usermod",
                "--lock",
                "--expiredate",
                "1970-01-02",
                name,
            )
        )
    if effective or real:
        raise SandboxError(f"{name} processes survived sandbox termination")


def _relative(value: str) -> str:
    if not value or "\\" in value or "\0" in value:
        raise SandboxError("export path is unsafe")
    parsed = PurePosixPath(value)
    if (
        parsed.is_absolute()
        or not parsed.parts
        or parsed.as_posix() != value
        or any(part in {"", ".", ".."} for part in parsed.parts)
    ):
        raise SandboxError("export path must be canonical and relative")
    return parsed.as_posix()


def _validate_export_tree(
    root: Path,
    label: str,
    *,
    max_entries: int = MAX_EXPORT_ENTRIES,
    max_files: int = MAX_EXPORT_FILES,
    max_file_bytes: int = MAX_EXPORT_FILE_BYTES,
    max_total_bytes: int = MAX_EXPORT_TOTAL_BYTES,
) -> None:
    """Reject upload-hanging/special output before any JavaScript artifact action sees it."""

    pending = [root]
    entries = 0
    files = 0
    total = 0
    while pending:
        current = pending.pop()
        entries += 1
        if entries > max_entries:
            raise SandboxError(f"sandbox export {label!r} exceeds its entry budget")
        metadata = current.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            raise SandboxError(f"sandbox export {label!r} contains a symlink")
        if stat.S_ISDIR(metadata.st_mode):
            try:
                children = list(os.scandir(current))
            except OSError as exc:
                raise SandboxError(f"cannot enumerate sandbox export {label!r}: {exc}") from exc
            pending.extend(Path(child.path) for child in children)
            continue
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise SandboxError(f"sandbox export {label!r} contains a special or hard-linked file")
        files += 1
        total += metadata.st_size
        if (
            files > max_files
            or not 0 <= metadata.st_size <= max_file_bytes
            or total > max_total_bytes
        ):
            raise SandboxError(f"sandbox export {label!r} exceeds its file/byte budget")


def seal(root_value: Path, exports: tuple[str, ...]) -> dict[str, Any]:
    root = _root(root_value)
    state = _read_state(root, sealed=False)
    if len(exports) > MAX_EXPORTS or len(set(exports)) != len(exports):
        raise SandboxError("sandbox export inventory must be bounded and unique")
    uid = state["uid"]
    _terminate_identity(uid, USER_NAME)
    repository = Path(state["repository"])
    runner_identity = f"{os.getuid()}:{os.getgid()}"
    # Once the candidate identity is dead, return the inert tree to the runner user.  This makes
    # the candidate-owned metadata readable for fail-closed comparison and makes permissions
    # deterministic for the separate credentialless validator identity.  Ownership changes do
    # not execute or trust anything in the tree.
    _run(("sudo", "-n", "chown", "-hR", runner_identity, str(repository)))
    _run(("chmod", "-R", "u+rwX,go+rX,go-w", str(repository)))
    _restore_authenticated_tree(state)
    _refresh_restored_index(state)
    normalized: list[str] = []
    for raw in exports:
        relative = _relative(raw)
        target = repository / relative
        try:
            metadata = target.lstat()
        except FileNotFoundError as exc:
            raise SandboxError(f"declared sandbox export {relative!r} is missing") from exc
        if stat.S_ISLNK(metadata.st_mode):
            raise SandboxError(f"declared sandbox export {relative!r} is a symlink")
        _validate_export_tree(target, relative)
        normalized.append(relative)
    state["sealed"] = True
    _write_state(root, state)
    return {**state, "exports": normalized}


def validate_sealed(
    root_value: Path, command: tuple[str, ...], pass_names: tuple[str, ...]
) -> int:
    """Run protected validation under a second no-sudo identity, then destroy it."""

    root = _root(root_value)
    state = _read_state(root, sealed=True)
    if not command or any("\0" in item for item in command):
        raise SandboxError("validator command is empty or unsafe")
    try:
        pwd.getpwnam(VALIDATOR_USER_NAME)
    except KeyError:
        pass
    else:
        raise SandboxError("validator user unexpectedly already exists")
    validator_home = root / "validator-home"
    validator_home.mkdir(mode=0o700)
    validator_temp = validator_home / "tmp"
    validator_temp.mkdir(mode=0o700)
    _run(
        (
            "sudo",
            "-n",
            "useradd",
            "--create-home",
            "--home-dir",
            str(validator_home),
            "--shell",
            "/bin/bash",
            "--user-group",
            VALIDATOR_USER_NAME,
        )
    )
    record = pwd.getpwnam(VALIDATOR_USER_NAME)
    _run(
        (
            "sudo",
            "-n",
            "chown",
            "-hR",
            f"{record.pw_uid}:{record.pw_gid}",
            str(validator_home),
        )
    )
    environment = _candidate_environment(root, pass_names)
    environment = [
        item
        for item in environment
        if not item.startswith(
            ("HOME=", "LOGNAME=", "PYTHONPYCACHEPREFIX=", "TMPDIR=", "USER=")
        )
    ]
    environment.extend(
        (
            "GIT_CONFIG_COUNT=1",
            "GIT_CONFIG_GLOBAL=/dev/null",
            "GIT_CONFIG_KEY_0=safe.directory",
            "GIT_CONFIG_NOSYSTEM=1",
            f"GIT_CONFIG_VALUE_0={state['repository']}",
            f"HOME={validator_home}",
            f"LOGNAME={VALIDATOR_USER_NAME}",
            f"PYTHONPYCACHEPREFIX={validator_temp / 'pycache'}",
            f"TMPDIR={validator_temp}",
            f"USER={VALIDATOR_USER_NAME}",
        )
    )
    def interrupted(signum: int, _frame: Any) -> None:
        raise SandboxError(f"validator command interrupted by signal {signum}")

    previous = {
        signum: signal.signal(signum, interrupted)
        for signum in (signal.SIGINT, signal.SIGTERM)
    }
    try:
        return _execute_untrusted(
            uid=record.pw_uid,
            cwd=Path(state["controller"]),
            environment=environment,
            command=command,
            label="validator",
        )
    finally:
        _terminate_identity(record.pw_uid, VALIDATOR_USER_NAME)
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command_name", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--root", required=True, type=Path)
    prepare_parser.add_argument("--source", required=True, type=Path)
    prepare_parser.add_argument("--controller-source", required=True, type=Path)
    prepare_parser.add_argument("--seed-gradle-home", type=Path)
    prepare_parser.add_argument(
        "--overlay", nargs=2, action="append", default=[], metavar=("SOURCE", "TARGET")
    )
    run_parser = commands.add_parser("run")
    run_parser.add_argument("--root", required=True, type=Path)
    run_parser.add_argument("--pass-env", action="append", default=[])
    run_parser.add_argument("candidate_command", nargs=argparse.REMAINDER)
    seal_parser = commands.add_parser("seal")
    seal_parser.add_argument("--root", required=True, type=Path)
    seal_parser.add_argument("--export", action="append", default=[])
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--root", required=True, type=Path)
    validate_parser.add_argument("--pass-env", action="append", default=[])
    validate_parser.add_argument("validator_command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        if args.command_name == "prepare":
            value = prepare(
                args.root,
                args.source,
                args.controller_source,
                args.seed_gradle_home,
                tuple((Path(source), target) for source, target in args.overlay),
            )
            print(json.dumps(value, sort_keys=True, separators=(",", ":")))
            return 0
        if args.command_name == "run":
            command = tuple(args.candidate_command)
            if command[:1] == ("--",):
                command = command[1:]
            return run_candidate(args.root, command, tuple(args.pass_env))
        if args.command_name == "seal":
            value = seal(args.root, tuple(args.export))
            print(json.dumps(value, sort_keys=True, separators=(",", ":")))
            return 0
        command = tuple(args.validator_command)
        if command[:1] == ("--",):
            command = command[1:]
        return validate_sealed(args.root, command, tuple(args.pass_env))
    except (KeyError, OSError, SandboxError, subprocess.SubprocessError) as exc:
        print(f"candidate sandbox error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
