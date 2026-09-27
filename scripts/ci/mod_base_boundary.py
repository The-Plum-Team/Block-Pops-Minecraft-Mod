#!/usr/bin/env python3
"""Protected Build-gate checks at the boundary between Block Pops and the pinned mod-base kit.

Both checks run in the protected controller and read the candidate only as inert data.

``import-root --repo DIR``
    The kit's adapter host starts every hook child with ``<kit>/src`` and the repository root on
    ``PYTHONPATH`` (``site/mod-base.json`` ``adapter.python_path`` is ``["."]``: the adapter imports
    the protected ``scripts.*`` and ``e2e.*`` packages), and every Block Pops controller puts its
    checkout root first on ``sys.path``. The root itself is not a protected path, so an ordinary pull
    request could add an entry there that the privileged Pages jobs and the evidence producer would
    import before any protected code: a ``sitecustomize`` module runs at interpreter start-up, and a
    module or regular package named like a standard-library or installed module (``json.py``,
    ``PIL/``) replaces it. This check refuses, at the repository root, every file whose suffix makes
    it importable, every symbolic link with a module name, and every regular package except the
    protected ``e2e`` and ``tests``. ``scripts`` must stay a namespace package: an
    ``scripts/__init__.py`` would run before every protected ``scripts.*`` module.

``composite --kit DIR --candidate-repo DIR``
    Run right after ``mod_base_kit.py stage``, against its output: the exact bytes the sandbox then
    receives at ``out/mod-base-kit``. From mod-base v0.9.2 a staged kit carries the pinned composites
    under ``actions/``, bound by ``src/mod_base/template/staged_actions.sha256`` inside its digested
    ``src/``. This check verifies the staged kit as the sandbox's resolution will (its stamp names the
    candidate's pin, its kit-digest-v1 equals the stamp, and ``template/``, ``tools/`` and
    ``actions/`` equal their locks) but, unlike that resolution, requires ``actions/`` and its lock.
    It then requires every step of the ``prepare-evidence`` composite that runs Python to be a
    literal ``bash`` block that unsets every credential first. Only the kit tree check keeps the
    read-only ``GH_TOKEN`` its API call needs, and it runs no ``-m mod_base`` code. A controller
    bootstrap older than v0.9.2, or a candidate pinned to a kit older than v0.9.2, stages no
    ``actions/``, so the check fails closed for both.

Exit status: 0 when the check passes, 1 when it finds problems (each printed on stderr), 2 when
the input cannot be read or the staged kit does not verify.
"""

from __future__ import annotations

import argparse
import importlib.machinery
import os
import re
import stat
import sys
from collections.abc import Sequence
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from scripts.ci import mod_base_kit  # noqa: E402

#: File suffixes Python imports as a module, or reads as path configuration in a site directory.
IMPORTABLE_SUFFIXES = tuple(
    sorted({".py", ".pyc", ".pyo", ".pyw", ".pyd", ".so", ".pth", *importlib.machinery.all_suffixes()})
)
#: The only regular packages allowed at the repository root; both are protected paths.
ROOT_PACKAGES = frozenset({"e2e", "tests"})
MAX_DIRECTORY_ENTRIES = 4096

#: Every credential a composite step must unset before it runs Python.
SCRUBBED_CREDENTIALS = frozenset(
    {"ACTIONS_RUNTIME_TOKEN", "ACTIONS_CACHE_URL", "ACTIONS_RESULTS_URL", "GITHUB_TOKEN", "GH_TOKEN"}
)
#: The one step allowed to keep ``GH_TOKEN``: the kit's check of its executing action tree.
TREE_CHECK = "tools/verify_action_tree.py"
COMPOSITE = ("actions", "prepare-evidence", "action.yml")
MIN_PYTHON_STEPS = 4
MAX_ACTION_BYTES = 256 * 1024
PYTHON = re.compile(r"\bpython")
UNSET = re.compile(r"(?m)^[ \t]*unset ([A-Z_ ]+)$")


class BoundaryError(RuntimeError):
    """The checked input cannot be read as a bounded, real file tree."""


def _entries(directory: Path) -> list[os.DirEntry[str]]:
    try:
        info = os.lstat(directory)
    except OSError as exc:
        raise BoundaryError(f"cannot inspect {directory}: {exc.strerror or exc}") from None
    if not stat.S_ISDIR(info.st_mode):
        raise BoundaryError(f"{directory} is not a real directory")
    listed: list[os.DirEntry[str]] = []
    try:
        with os.scandir(directory) as entries:
            for entry in entries:
                listed.append(entry)
                if len(listed) > MAX_DIRECTORY_ENTRIES:
                    raise BoundaryError(f"{directory} holds more than {MAX_DIRECTORY_ENTRIES} entries")
    except OSError as exc:
        raise BoundaryError(f"cannot list {directory}: {exc.strerror or exc}") from None
    return sorted(listed, key=lambda entry: entry.name)


def _importable_name(name: str) -> bool:
    return name.lower().endswith(IMPORTABLE_SUFFIXES)


def _regular_package(directory: Path) -> bool:
    """Whether ``directory`` holds an ``__init__`` entry of any suffix (not a namespace portion)."""

    return any(entry.name.lower().startswith("__init__.") for entry in _entries(directory))


def import_root_problems(repo: Path) -> list[str]:
    """Every entry at the root of ``repo`` that Python could import ahead of protected code."""

    problems = []
    for entry in _entries(repo):
        name = entry.name
        try:
            mode = entry.stat(follow_symlinks=False).st_mode
        except OSError as exc:
            raise BoundaryError(f"cannot inspect {name}: {exc.strerror or exc}") from None
        if _importable_name(name):
            problems.append(f"{name}: a root file with an importable suffix")
        elif stat.S_ISLNK(mode):
            if name.isidentifier():
                problems.append(f"{name}: a root symbolic link with a module name")
        elif stat.S_ISDIR(mode) and name not in ROOT_PACKAGES and _regular_package(repo / name):
            problems.append(f"{name}/: a root regular package (only {sorted(ROOT_PACKAGES)} may be one)")
    return problems


def _unset_names(script: str) -> tuple[int, frozenset[str]]:
    match = UNSET.search(script)
    if match is None:
        return len(script), frozenset()
    return match.start(), frozenset(match.group(1).split())


def composite_problems(action: str) -> list[str]:
    """Every credential-boundary problem of a ``prepare-evidence`` composite's text."""

    if "\n  steps:\n" not in action:
        return ["the composite has no runs.steps block"]
    problems = []
    if "secrets." in action:
        problems.append("the composite reads a secret")
    head, *steps = re.split(r"(?m)^    - name: ", action.split("\n  steps:\n", 1)[1])
    if head.strip():
        problems.append("runs.steps holds an entry before its first named step")
    python_steps = 0
    for step in steps:
        name = step.split("\n", 1)[0]
        runs = re.findall(r"(?m)^ *run:(.*)$", step)
        if not runs:
            continue
        if runs != [" |"] or "\n      run: |\n" not in f"\n{step}":
            problems.append(f"{name}: run must be one literal block at step level (run: |)")
            continue
        if re.findall(r"(?m)^ *shell:(.*)$", step) != [" bash"]:
            problems.append(f"{name}: a run step must use shell: bash")
            continue
        script = step.split("      run: |\n", 1)[1]
        python = PYTHON.search(script)
        if python is None:
            continue
        python_steps += 1
        position, names = _unset_names(script)
        if position > python.start():
            problems.append(f"{name}: runs Python before it unsets any credential")
        if TREE_CHECK in script:
            if "-m mod_base" in script:
                problems.append(f"{name}: the kit tree check step also runs kit code")
            missing = SCRUBBED_CREDENTIALS - {"GH_TOKEN"} - names
        else:
            missing = SCRUBBED_CREDENTIALS - names
            if re.search(r"(?m)^ *GH_TOKEN:", step):
                problems.append(f"{name}: a Python step other than the tree check receives GH_TOKEN")
        if missing:
            problems.append(f"{name}: does not unset {', '.join(sorted(missing))} before Python")
    if python_steps < MIN_PYTHON_STEPS:
        problems.append(f"only {python_steps} Python steps found; expected at least {MIN_PYTHON_STEPS}")
    return problems


def verify_staged_kit(kit: Path, candidate_repo: Path) -> Path:
    """Verify the ``stage`` output ``kit`` for the pin of ``candidate_repo``, including its ``actions/``.

    The checks are the sandbox resolution's (stamp, kit-digest-v1, staged-file locks), made through
    the controller's own bootstrap, plus the one it leaves optional: ``actions/`` and its lock must
    be present, because this gate reads the composite from them.
    """

    actions_dir = getattr(mod_base_kit, "ACTIONS_DIR", None)
    actions_lock = getattr(mod_base_kit, "ACTIONS_LOCK", None)
    if actions_dir is None or actions_lock is None:
        raise BoundaryError("the controller bootstrap predates mod-base v0.9.2 and stages no actions/")
    pin = mod_base_kit.parse_pin(candidate_repo)
    kit = Path(os.path.abspath(kit))
    stamp = mod_base_kit.read_stamp(kit)
    if (stamp["sha"], stamp["version"]) != (pin.sha, pin.version[1:]):
        raise BoundaryError(
            f"the staged kit is {stamp['sha']} {stamp['version']}, not the candidate pin {pin.sha} {pin.version}"
        )
    digest = mod_base_kit.tree_digest(kit)
    if digest != stamp["tree_digest"]:
        raise BoundaryError(f"the staged kit digest {digest} does not equal its stamp {stamp['tree_digest']}")
    for relative in (actions_dir, actions_lock):
        if not os.path.lexists(kit.joinpath(*relative.split("/"))):
            raise BoundaryError(
                f"the staged kit carries no {relative}: a kit older than mod-base v0.9.2 has no "
                "lock-bound composites, and this gate cannot check the one the candidate pins"
            )
    mod_base_kit.verify_staged_files(kit)
    return kit


def read_composite(root: Path) -> str:
    """The bounded text of ``<root>/actions/prepare-evidence/action.yml``, refusing any symlink."""

    current = Path(os.path.abspath(root))
    components = [current]
    for part in COMPOSITE:
        current = current / part
        components.append(current)
    for path in components:
        try:
            info = os.lstat(path)
        except OSError as exc:
            raise BoundaryError(f"cannot inspect {path}: {exc.strerror or exc}") from None
        if stat.S_ISLNK(info.st_mode):
            raise BoundaryError(f"{path} is a symbolic link")
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_ACTION_BYTES:
        raise BoundaryError(f"{current} is not a regular file of at most {MAX_ACTION_BYTES} bytes")
    try:
        descriptor = os.open(current, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as stream:
            data = stream.read(MAX_ACTION_BYTES + 1)
    except OSError as exc:
        raise BoundaryError(f"cannot read {current}: {exc.strerror or exc}") from None
    if len(data) > MAX_ACTION_BYTES:
        raise BoundaryError(f"{current} exceeds {MAX_ACTION_BYTES} bytes")
    try:
        return data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        raise BoundaryError(f"{current} is not UTF-8") from None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    root = commands.add_parser("import-root", help="refuse importable entries at the repository root")
    root.add_argument("--repo", type=Path, required=True)
    composite = commands.add_parser("composite", help="check the staged kit's prepare-evidence composite")
    composite.add_argument("--kit", type=Path, required=True, help="the output directory of mod_base_kit.py stage")
    composite.add_argument("--candidate-repo", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        if arguments.command == "import-root":
            problems = import_root_problems(arguments.repo)
            label = f"repository root {arguments.repo}"
        else:
            root = verify_staged_kit(arguments.kit, arguments.candidate_repo)
            problems = composite_problems(read_composite(root))
            label = f"prepare-evidence composite of {mod_base_kit.read_stamp(root)['sha']}"
    except (BoundaryError, mod_base_kit.KitError, OSError) as exc:
        print(f"mod_base_boundary: error: {exc}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"mod_base_boundary: {label}: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"mod_base_boundary: {label}: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
