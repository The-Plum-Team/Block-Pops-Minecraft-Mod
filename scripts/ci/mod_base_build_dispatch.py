"""Block Pops' Build dispatcher for mod-base, run as ``<python> -I -B <this file> --hook <name>``.

The kit runs every hook of ``BUILD_ADAPTER_API = 1`` through this one program, each in a disposable
account whose ``HOME`` lies directly below the worker root (see the kit's ``docs/BUILD-ADAPTER.md``):

* a protected hook (``derive_plan``, ``verify_target``, ``verify_build``, ``derive_runtime``,
  ``verify_runtime``) runs as the validator from the protected adapter copy (``controller/``),
  reads ``<root>/validation-input/`` and the sealed exports and writes ``$HOME/validation/``. The
  native knowledge it applies lives in ``mod_base_build_adapter.py``;
* a candidate hook (``policy``, ``build_target``, ``run_lane``) runs from the tested checkout
  (``repository/``, its working directory) and writes ``$HOME/export/``. It runs the same native
  commands as the candidate steps of ``build-gate.yml``, ``on-demand-e2e.yml`` and
  ``.github/actions/run-packaged-e2e/action.yml``, in a clone of the tested commit at
  ``$HOME/work``: the kit fails a hook that leaves anything untracked in its checkout, and the
  native commands write ``build/``, ``e2e-out/``, Gradle homes and bytecode into theirs.

A candidate hook makes itself the subreaper of everything it starts and stops every process that
is still alive after each native command, so no Gradle, Minecraft or Xvfb process outlives it.
The kit gives a hook no system package: the display stack of ``run_lane`` (Xvfb, xauth, Mesa,
ALSA/OpenAL and the X client libraries) comes from the kit's ``xvfb-mesa`` system profile, which
the protected Build config declares and the lane job installs before it fences the host.
"""

from __future__ import annotations

import ctypes
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

# ``-I`` keeps this directory and the repository root off ``sys.path``; the adapter imports the
# native modules through the root.
_HERE = Path(__file__).resolve().parent
for _entry in (str(_HERE.parents[1]), str(_HERE)):
    if _entry not in sys.path:
        sys.path.insert(0, _entry)

import mod_base_build_adapter as adapter  # noqa: E402
import mod_base_build_policy as policy  # noqa: E402

HOOKS = ("derive_plan", "policy", "build_target", "verify_target", "verify_build", "derive_runtime", "run_lane",
         "verify_runtime")
UNIT_NAMES = {"build_target": "MB_TARGET_ID", "verify_target": "MB_TARGET_ID", "derive_runtime": "MB_LANE_ID",
              "run_lane": "MB_LANE_ID", "verify_runtime": "MB_LANE_ID"}
CANDIDATE_HOOKS = ("policy", "build_target", "run_lane")
KIT_FILES = frozenset({"ci-envelope.json", "ci-runtime-envelope.json"})
CONFIG = "scripts/ci/mod-base-build.json"
INVENTORY = "release/release-matrix.json"
CONTRACT = "e2e/scenario-contract.json"
# The display of the native packaged lane (run-packaged-e2e/action.yml).
DISPLAY = {"LIBGL_ALWAYS_SOFTWARE": "1", "GALLIUM_DRIVER": "llvmpipe", "SDL_VIDEO_FORCE_EGL": "1",
           "__GLX_VENDOR_LIBRARY_NAME": "mesa"}
XVFB_ARGS = "-screen 0 1920x1080x24 -ac +extension GLX +render -noreset"
WORKSPACE_MARKER = ".blockpops-run-workspace.json"
PR_SET_CHILD_SUBREAPER = 36
LOG_TAIL_BYTES = 64 * 1024


class Output:
    """The hook's output directory: files are created, never replaced."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.written: list[Path] = []

    def write(self, relative: str, data: bytes, *, allow_empty: bool = False) -> None:
        if not data and not allow_empty:
            raise adapter.AdapterError(f"{relative} would be empty")
        path = self.root.joinpath(*relative.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "xb") as stream:
            stream.write(data)
        self.written.append(path)


def _read(root: Path, relative: str) -> bytes:
    path = root.joinpath(*relative.split("/"))
    if path.is_symlink() or not path.is_file():
        raise adapter.AdapterError(f"{relative} is not a regular file")
    return path.read_bytes()


def _files(root: Path) -> set[str]:
    """Every file below ``root`` by relative path; a link or a special file is a rejection."""

    found = set()
    for path in sorted(root.rglob("*")) if root.is_dir() else ():
        if path.is_symlink() or not (path.is_dir() or path.is_file()):
            raise adapter.AdapterError(f"{path.relative_to(root).as_posix()} is not a regular file or directory")
        if path.is_file():
            found.add(path.relative_to(root).as_posix())
    return found


# Protected hooks ---------------------------------------------------------------------------------


class Inputs:
    """What a protected hook was given: the matrix and the contract bytes, both loaded natively."""

    def __init__(self, root: Path) -> None:
        self.directory = root / "validation-input"
        self.matrix_bytes = _read(self.directory, "inventory")
        self.contract_bytes = _read(self.directory, "scenario-contract")
        self.contract = adapter.bind_contract(self.directory / "scenario-contract")
        if self.contract.sha256 != adapter.sha256(self.contract_bytes):
            raise adapter.AdapterError("the scenario contract changed while it was read")
        self.document = adapter.load_document(self.matrix_bytes, self.contract)

    def plan(self) -> dict[str, Any]:
        """The protected plan, which must bind both candidate files and be this matrix's plan."""

        plan = adapter.decode(_read(self.directory, "ci-plan.json"), "plan")
        identity = plan["identity"]
        if (identity["inventory_sha256"], identity["scenario_sha256"]) != (
                adapter.sha256(self.matrix_bytes), self.contract.sha256):
            raise adapter.AdapterError("the plan was not derived from this matrix and scenario contract")
        if plan["plan_inputs"] != []:
            raise adapter.AdapterError("the plan binds candidate files Block Pops does not plan from")
        derived = adapter.derive_plan(self.document, self.contract)
        if adapter.encode({key: plan[key] for key in ("targets", "lanes")}) != adapter.encode(derived):
            raise adapter.AdapterError("the plan's targets and lanes are not this matrix's")
        return plan


def _unit(plan: dict[str, Any], kind: str, unit: str) -> dict[str, Any]:
    for entry in plan[kind]:
        if entry["id"] == unit:
            return entry
    raise adapter.AdapterError(f"the plan has no {kind[:-1]} {unit!r}")


def _workspace(home: Path) -> Path:
    workspace = home / "tmp" / "blockpops-native-stage"
    shutil.rmtree(workspace, ignore_errors=True)
    workspace.mkdir(parents=True)
    return workspace


def _verify_targets(root: Path, home: Path, output: Output, hook: str, unit: str | None) -> None:
    inputs = Inputs(root)
    plan = inputs.plan()
    identity = plan["identity"]
    sealed = root / "sealed-build"
    planned = {entry["path"] for target in plan["targets"] for entry in target["outputs"]}
    present = _files(sealed) - KIT_FILES
    selected = plan["targets"] if unit is None else [_unit(plan, "targets", unit)]
    wanted = {entry["path"] for target in selected for entry in target["outputs"]}
    if not present <= planned or not wanted <= present or (unit is None and present != planned):
        raise adapter.AdapterError("the sealed Build does not hold exactly the planned outputs")
    workspace = _workspace(home)
    try:
        for entry in selected:
            members = adapter.target_lanes(inputs.document, entry["id"])
            records = [adapter.verify_lane_build(
                workspace, matrix_bytes=inputs.matrix_bytes, contract_bytes=inputs.contract_bytes,
                target_id=entry["id"], lane=lane, tested_sha=identity["tested_sha"],
                tested_tree=identity["tested_tree"], read=lambda path: _read(sealed, path))["record"]
                for lane in members]
            output.write(f"{entry['id']}.json", adapter.encode({
                "schema_version": 1, "hook": hook, "unit": entry["id"],
                "native_contract_sha256": entry["native_contract_sha256"],
                "tested_sha": identity["tested_sha"], "tested_tree": identity["tested_tree"], "lanes": records}))
    finally:
        shutil.rmtree(workspace, ignore_errors=True)


def _verify_runtime(root: Path, home: Path, output: Output, lane_id: str) -> None:
    inputs = Inputs(root)
    plan = inputs.plan()
    identity = plan["identity"]
    entry = _unit(plan, "lanes", lane_id)
    target_id, lane = adapter.lane_target(inputs.document, lane_id)
    if entry["target_id"] != target_id:
        raise adapter.AdapterError(f"lane {lane_id} belongs to another target")
    runtime = root / "sealed-runtime"
    prefix = f"lanes/{lane_id}/"
    if any(not path.startswith(prefix) for path in _files(runtime) - KIT_FILES):
        raise adapter.AdapterError(f"the sealed results hold files outside {prefix}")
    workspace = _workspace(home)
    try:
        sealed = root / "sealed-build"
        verified = adapter.verify_lane_build(
            workspace, matrix_bytes=inputs.matrix_bytes, contract_bytes=inputs.contract_bytes, target_id=target_id,
            lane=lane, tested_sha=identity["tested_sha"], tested_tree=identity["tested_tree"],
            read=lambda path: _read(sealed, path))
        row = adapter.runtime_row(inputs.document, inputs.contract, lane_id)
        summary = adapter.verify_lane_runtime(
            matrix_path=inputs.directory / "inventory", contract_path=inputs.directory / "scenario-contract",
            document=inputs.document, contract=inputs.contract, node=lane_id, row=row,
            manifest=verified["manifest"], results=runtime / "lanes" / lane_id)
    finally:
        shutil.rmtree(workspace, ignore_errors=True)
    output.write(f"{lane_id}.json", adapter.encode({
        "schema_version": 1, "hook": "verify_runtime", "unit": lane_id,
        "native_contract_sha256": entry["native_contract_sha256"], "tested_sha": identity["tested_sha"],
        "build": verified["record"], "runtime": summary}))


# Candidate hooks ---------------------------------------------------------------------------------


def _become_subreaper() -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "cannot become the subreaper of the hook's processes")


def _descendants() -> list[int]:
    parents: dict[int, int] = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/stat", "rb") as stream:
                fields = stream.read().rsplit(b")", 1)[1].split()
        except OSError:
            continue
        parents[int(entry)] = int(fields[1])
    found, frontier = [], [os.getpid()]
    while frontier:
        current = frontier.pop()
        children = [pid for pid, parent in parents.items() if parent == current]
        found.extend(children)
        frontier.extend(children)
    return found


def _reap() -> None:
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            return
        if pid == 0:
            return


def _stop_leftovers(label: str) -> None:
    """Stop every process a native command left behind; they are all ours (we are subreaper)."""

    stopped = set()
    for signum, grace in ((signal.SIGTERM, 10.0), (signal.SIGKILL, 5.0)):
        alive = _descendants()
        if not alive:
            break
        for pid in alive:
            try:
                os.kill(pid, signum)
                stopped.add(pid)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            _reap()
            if not _descendants():
                break
            time.sleep(0.2)
    _reap()
    if _descendants():
        raise adapter.AdapterError(f"{label} left processes that cannot be stopped")
    if stopped:
        print(f"blockpops: stopped {len(stopped)} process(es) {label} left behind", flush=True)


def _run(command: list[str], *, cwd: Path, env: dict[str, str], label: str, quiet: bool = False) -> None:
    print(f"blockpops: {label}", flush=True)
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, start_new_session=True,
                               stdout=subprocess.DEVNULL if quiet else None)
    try:
        code = process.wait()
    finally:
        _stop_leftovers(label)
    print(f"blockpops: {label}: exit {code} after {time.monotonic() - started:.0f} s", flush=True)
    if code != 0:
        raise adapter.AdapterError(f"{label} exited with {code}")


def _git(*arguments: str, cwd: Path, env: dict[str, str]) -> str:
    result = subprocess.run(["git", *arguments], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise adapter.AdapterError(f"git {arguments[0]} failed: {result.stderr.strip()[:500]}")
    return result.stdout.strip()


def _java_homes() -> dict[int, Path]:
    """Every JDK home the job installed (``MB_JAVA_HOMES``), by the major its ``release`` names."""

    homes: dict[int, Path] = {}
    for entry in filter(None, os.environ.get("MB_JAVA_HOMES", "").split(":")):
        home = Path(entry)
        match = re.search(r'^JAVA_VERSION="(\d+)', (home / "release").read_text("utf-8"), re.MULTILINE)
        if match is None or int(match.group(1)) in homes:
            raise adapter.AdapterError(f"{entry} is not one more JDK home")
        homes[int(match.group(1))] = home
    return homes


class Candidate:
    """The candidate's private clone of the tested commit and the environment of native commands."""

    def __init__(self, checkout: Path) -> None:
        self.checkout = checkout
        self.home = Path(os.environ["HOME"])
        self.work = self.home / "work"
        self.tested_sha = os.environ["MB_TESTED_SHA"]
        self.tested_tree = os.environ["MB_TESTED_TREE"]
        self.homes = _java_homes()
        # Native candidate steps run ``python3`` with the user site enabled and their working
        # directory importable; the kit's interpreter flags protect only the dispatcher itself.
        self.env = {key: value for key, value in os.environ.items()
                    if key not in {"PYTHONNOUSERSITE", "PYTHONSAFEPATH"}}
        self.env.update(BLOCKPOPS_TESTED_SHA=self.tested_sha, PYTHONDONTWRITEBYTECODE="1")
        self.python = sys.executable

    def clone(self, *, with_kit: bool) -> None:
        git_env = {**self.env, "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "safe.directory",
                   "GIT_CONFIG_VALUE_0": str(self.checkout)}
        if self.work.exists():
            raise adapter.AdapterError(f"{self.work} already exists")
        started = time.monotonic()
        _git("clone", "--quiet", "--no-hardlinks", "--no-checkout", str(self.checkout), str(self.work),
             cwd=self.home, env=git_env)
        _git("checkout", "--quiet", "--detach", self.tested_sha, cwd=self.work, env=self.env)
        if (_git("rev-parse", "HEAD", cwd=self.work, env=self.env),
                _git("rev-parse", "HEAD^{tree}", cwd=self.work, env=self.env)) != (self.tested_sha, self.tested_tree):
            raise adapter.AdapterError("the clone is not the tested commit and tree")
        kit = self.checkout / "out" / "mod-base-kit"
        if with_kit and kit.is_dir():
            shutil.copytree(kit, self.work / "out" / "mod-base-kit", symlinks=True)
        print(f"blockpops: cloned the tested commit in {time.monotonic() - started:.0f} s", flush=True)

    def native(self, label: str, *arguments: str, quiet: bool = False) -> None:
        _run([self.python, *arguments], cwd=self.work, env=self.env, label=label, quiet=quiet)

    def pip_user(self, requirements: str) -> None:
        self.native(f"install {requirements}", "-m", "pip", "install", "--user", "--disable-pip-version-check",
                    "--only-binary=:all:", "--require-hashes", "--requirement", requirements)

    def document(self) -> Any:
        contract = adapter.bind_contract(self.work / CONTRACT)
        return adapter.load_document((self.work / INVENTORY).read_bytes(), contract), contract


def _build_target(candidate: Candidate, output: Output, target_id: str) -> None:
    """``build_matrix.py`` and the lane-scoped ``verify_release.py`` stage, lane by lane, exactly
    as the native gate runs them for its full scope, then the planned files of the target."""

    candidate.clone(with_kit=False)
    document, _ = candidate.document()
    members = adapter.target_lanes(document, target_id)
    gradle_java = members[0].gradle_java
    homes = candidate.homes
    if {gradle_java, 17, 21} - set(homes):
        raise adapter.AdapterError(f"the job did not install JDKs {sorted({gradle_java, 17, 21} - set(homes))}")
    for lane in members:
        node = lane.identity.artifact_node
        candidate.native(f"build {node}", "scripts/release/build_matrix.py", "--matrix", INVENTORY,
                         "--artifact-node", node, "--java-home", str(homes[gradle_java]),
                         "--java17-home", str(homes[17]), "--java21-home", str(homes[21]),
                         "--clean", "--discard-gradle-homes")
        candidate.native(f"stage {node}", "scripts/release/verify_release.py", "--artifact-node", node)
        paths = adapter.lane_paths(target_id, lane)
        stage = candidate.work / adapter.STAGE
        for kind in ("production", "harness"):
            output.write(paths[kind], _read(stage, paths[kind]))
        output.write(paths["manifest"], _read(stage, adapter.MANIFEST))
        output.write(paths["report"], _read(candidate.work / "build", adapter.BUILD_REPORT))


def _print_tails(results: Path) -> None:
    for path in sorted(results.rglob("*.log")) if results.is_dir() else ():
        if path.is_file() and not path.is_symlink():
            data = path.read_bytes()[-LOG_TAIL_BYTES:]
            print(f"blockpops: --- tail of {path.relative_to(results).as_posix()} ---", flush=True)
            sys.stdout.write(data.decode("utf-8", "replace"))
            sys.stdout.flush()


def _run_lane(candidate: Candidate, output: Output, lane_id: str, bundle_path: str) -> None:
    """The candidate step of ``run-packaged-e2e/action.yml`` for one lane of the verified Build."""

    row = adapter.decode(os.environ["E2E_ROW_JSON"].encode("utf-8"), "E2E_ROW_JSON")
    scenarios = os.environ["E2E_SCENARIOS"]
    if type(row) is not dict or row.get("artifact_node") != lane_id or row.get("scenarios") != scenarios:
        raise adapter.AdapterError(f"the runtime values do not describe lane {lane_id}")
    candidate.clone(with_kit=False)
    document, _ = candidate.document()
    target_id, lane = adapter.lane_target(document, lane_id)
    bundle = candidate.checkout / bundle_path
    paths = adapter.lane_paths(target_id, lane)
    stage = candidate.work / adapter.STAGE
    for kind in ("production", "harness"):
        destination = stage / paths[kind]
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(_read(bundle, paths[kind]))
    (stage / adapter.MANIFEST).write_bytes(_read(bundle, paths["manifest"]))
    candidate.pip_user("e2e/requirements.txt")
    candidate.native(f"reverify the staged {lane_id} JARs", "scripts/release/verify_release.py", "--verify-staged",
                     "--artifact-node", lane_id)
    java = {f"BLOCKPOPS_JAVA_{major}": str(home) for major, home in candidate.homes.items()}
    environment = {**candidate.env, **DISPLAY, **java,
                   "BLOCKPOPS_E2E_RUNTIME_STORE": str(candidate.home / "runtime-store")}
    results = candidate.work / "e2e-out" / "current"
    try:
        _run(["xvfb-run", "--auto-servernum", f"--server-args={XVFB_ARGS}", candidate.python, "e2e/orchestrator.py",
              "--matrix", INVENTORY, "--row-json", os.environ["E2E_ROW_JSON"], "--projection", adapter.PROJECTION,
              "--artifacts-manifest", f"{adapter.STAGE}/{adapter.MANIFEST}", "--packaged", "--scenarios", scenarios,
              "--artifact-node", lane_id],
             cwd=candidate.work, env=environment, label=f"run the packaged {lane_id} scenarios")
    except adapter.AdapterError:
        _print_tails(results)
        raise
    for relative in sorted(_files(results)):
        if relative == WORKSPACE_MARKER:
            continue  # The orchestrator's ownership marker is not evidence (scripts/ci/e2e_fanin.py).
        # The kit gives a result file its role by name: a .json report or a .png screenshot must
        # not be empty, a log (every other file) may be.
        output.write(f"lanes/{lane_id}/{relative}", _read(results, relative),
                     allow_empty=not relative.endswith((".json", ".png")))


def _hook(hook: str, unit: str | None, home: Path, output: Output) -> None:
    root = home.parent
    if hook == "derive_plan":
        inputs = Inputs(root)
        output.write("plan.json", adapter.encode(adapter.derive_plan(inputs.document, inputs.contract)))
    elif hook == "derive_runtime":
        inputs = Inputs(root)
        _unit(inputs.plan(), "lanes", unit)
        output.write("runtime.json", adapter.encode({"values": adapter.runtime_values(
            inputs.document, inputs.contract, unit)}))
    elif hook in ("verify_target", "verify_build"):
        _verify_targets(root, home, output, hook, unit)
    elif hook == "verify_runtime":
        _verify_runtime(root, home, output, unit)
    else:
        _become_subreaper()
        candidate = Candidate(Path.cwd())
        if hook == "policy":
            policy.run(candidate)
        elif hook == "build_target":
            _build_target(candidate, output, unit)
        else:
            config = adapter.decode(_read(candidate.checkout, CONFIG), CONFIG)
            _run_lane(candidate, output, unit, config["bundle"]["path"])


def main(arguments: list[str]) -> int:
    if len(arguments) != 2 or arguments[0] != "--hook" or arguments[1] not in HOOKS:
        print("usage: mod_base_build_dispatch.py --hook <name>", flush=True)
        return 2
    hook = arguments[1]
    os.umask(0o077)
    home = Path(os.environ["HOME"])
    unit = os.environ.get(UNIT_NAMES[hook]) if hook in UNIT_NAMES else None
    label = hook if unit is None else f"{hook} {unit}"
    output = Output(home / ("export" if hook in CANDIDATE_HOOKS else "validation"))
    started = time.monotonic()
    try:
        if hook in UNIT_NAMES and not unit:
            raise adapter.AdapterError(f"{UNIT_NAMES[hook]} is required")
        _hook(hook, unit, home, output)
    except (adapter.AdapterError, policy.PolicyError, KeyError, TypeError, AttributeError, ValueError, OSError) as error:
        print(f"blockpops {label} rejected after {time.monotonic() - started:.0f} s: "
              f"{type(error).__name__}: {error}", flush=True)
        return 1
    print(f"blockpops {label}: ok, {len(output.written)} files in {time.monotonic() - started:.0f} s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
