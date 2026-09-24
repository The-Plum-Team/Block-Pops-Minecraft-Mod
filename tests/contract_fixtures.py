"""A representative scenario contract for fixtures whose behaviour does not depend on its size.

Pages and visual fixtures synthesise, validate, canonicalize and compact one 1600x900 frame per
contract capture per lane, so they grew with every capture a scenario added. This contract keeps
every scenario, execution profile, orchestration, role and step of the real one, but keeps a
capture only where it carries distinct behaviour: per role, the two steps of its first screenshot
comparison (with their probes and that comparison), otherwise its first capture. Tests that use
it derive every count from it, never from the real contract. Tests that exercise the real
contract end to end keep using ``REAL_CONTRACT_PATH``.

The automation binds the contract in several places: ``default_contract()`` in two module
copies (``e2e.scenario_contract`` and the ``scenario_contract`` alias that the matrix code
imports), ``DEFAULT_CONTRACT`` paths imported by name, and contract objects built at import time.
``representative_contract()`` swaps every such binding in every loaded ``e2e``/``scripts``
module, so production code and fixtures agree on one contract, and restores them all on exit.
"""

from __future__ import annotations

import atexit
import copy
import json
import shutil
import sys
import tempfile
from collections.abc import Iterator
from contextlib import ExitStack, contextmanager
from functools import lru_cache
from pathlib import Path
from types import ModuleType
from typing import Any
from unittest import mock

import e2e.scenario_contract as scenario_contract
# Load every module that binds the contract at import time before any swap, so none of them can
# first be imported while the representative contract is in place.
from e2e import orchestrator, packaged_runtime  # noqa: F401
from scripts.pages import authenticate_source, build_site, evidence, visual_anchor  # noqa: F401
import scripts.release.matrix  # noqa: F401 - loads the top-level scenario_contract alias

REAL_CONTRACT_PATH = scenario_contract.DEFAULT_CONTRACT
REAL_CONTRACT_SHA256 = scenario_contract.default_contract().sha256
PROBE_TABLES = ("REQUIRED_GUI_TEXT_PROBES", "OPAQUE_STARS_PROBES")
_full_contract_depth = 0


def bound_contract_path() -> Path:
    """The contract the code under test reads now: representative inside the context below."""

    return scenario_contract.DEFAULT_CONTRACT


def representative_document(source: Path = REAL_CONTRACT_PATH) -> dict[str, Any]:
    document = copy.deepcopy(json.loads(source.read_text(encoding="utf-8")))
    for scenario in document["scenarios"]:
        for role in scenario["roles"]:
            comparisons = role["comparisons"]
            if comparisons:
                kept = {comparisons[0]["first_step"], comparisons[0]["second_step"]}
                role["comparisons"] = comparisons[:1]
            else:
                kept = {next(step["id"] for step in role["steps"] if "capture" in step)}
            for step in role["steps"]:
                if step["id"] not in kept:
                    step.pop("capture", None)
    return document


@lru_cache(maxsize=1)
def representative_contract_path() -> Path:
    directory = Path(tempfile.mkdtemp(prefix="blockpops-representative-contract-"))
    atexit.register(shutil.rmtree, directory, True)
    path = directory / "scenario-contract.json"
    path.write_text(json.dumps(representative_document(), indent=2) + "\n", encoding="utf-8")
    return path


def contract_modules() -> list[ModuleType]:
    """Loaded automation modules; test modules keep their own explicit contract constants."""

    return [
        module
        for name, module in list(sys.modules.items())
        if name == "scenario_contract"
        or (name.startswith(("e2e.", "scripts.")) and ".tests." not in f"{name}.")
    ]


def contract_bindings(sha256: str, path: Path) -> list[tuple[ModuleType, str, Any]]:
    """Every module attribute that holds this contract's path or a parsed copy of it."""

    found = []
    for module in contract_modules():
        for attribute, value in list(vars(module).items()):
            if isinstance(value, Path) and value == path:
                found.append((module, attribute, value))
            elif type(value).__name__ == "ScenarioContract" and getattr(value, "sha256", None) == sha256:
                found.append((module, attribute, value))
    return found


def _contract_modules_with_loaders() -> list[ModuleType]:
    return [scenario_contract, sys.modules["scenario_contract"]]


@contextmanager
def full_contract() -> Iterator[Any]:
    """Keep the real contract even where a shared fixture would switch to the representative one.

    The end-to-end tests that must cover every scenario and every capture reuse the same fixture
    classes as the representative ones; inside this context ``representative_contract()`` is a
    no-op that yields the real contract.
    """

    global _full_contract_depth
    _full_contract_depth += 1
    try:
        yield scenario_contract.default_contract()
    finally:
        _full_contract_depth -= 1


@contextmanager
def representative_contract() -> Iterator[Any]:
    """Make the representative contract the one every loaded e2e/scripts binding reads."""

    if _full_contract_depth:
        yield scenario_contract.default_contract()
        return
    path = representative_contract_path()
    parsed = {
        loader.__name__: loader.load_contract(path) for loader in _contract_modules_with_loaders()
    }
    contract = parsed[scenario_contract.__name__]
    captured = {
        (scenario.scenario, role.role, step.id)
        for scenario in contract.scenarios
        for role in scenario.roles
        for step in role.steps
        if step.capture is not None
    }

    def restore_leaks() -> None:
        # A binding made while the swap was active (a module first imported inside it) would
        # otherwise keep the representative contract after exit.
        real = {loader.__name__: loader.default_contract() for loader in _contract_modules_with_loaders()}
        for module, attribute, value in contract_bindings(contract.sha256, path):
            setattr(module, attribute, REAL_CONTRACT_PATH if isinstance(value, Path) else real[type(value).__module__])

    with ExitStack() as stack:
        stack.callback(restore_leaks)
        for loader in _contract_modules_with_loaders():
            stack.callback(loader.default_contract.cache_clear)
        for module, attribute, value in contract_bindings(REAL_CONTRACT_SHA256, REAL_CONTRACT_PATH):
            replacement = path if isinstance(value, Path) else parsed[type(value).__module__]
            stack.enter_context(mock.patch.object(module, attribute, replacement))
        for loader in _contract_modules_with_loaders():
            loader.default_contract.cache_clear()
        for table in PROBE_TABLES:
            stack.enter_context(
                mock.patch.object(
                    packaged_runtime,
                    table,
                    {
                        key: value
                        for key, value in getattr(packaged_runtime, table).items()
                        if key in captured
                    },
                )
            )
        yield contract
