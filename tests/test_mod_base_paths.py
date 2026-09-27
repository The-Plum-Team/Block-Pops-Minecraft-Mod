"""Every kit-owned Block Pops path stays inside the protected, controller-upgrade-admissible roots.

Block Pops can change its protected control plane only through a controller upgrade, which the
protected evaluator admits path by path (``scripts/ci/pr_gate.py``). The mod-base caller, notifier,
bootstrap, adapter and configuration must therefore live under ``PROTECTED_PATHS`` and stay
admissible, so that every kit bump is an ordinary controller upgrade. The template files that are
not admissible are exactly the root files the adoption defers to its ordinary follow-up pull request.
"""

from __future__ import annotations

import ast
import json
import unittest
from pathlib import Path, PurePosixPath

from scripts.ci import gate_controller, mod_base_boundary, mod_base_kit, pr_gate
from tests import mod_base_path


REPO = Path(__file__).resolve().parents[1]
CONFIG = REPO / "site" / "mod-base.json"
#: The root files a controller upgrade cannot change: the adoption lands them in an ordinary PR.
DEFERRABLE_ROOT_FILES = frozenset(
    {
        ".gitattributes",
        ".gitignore",
        ".github/dependabot.yml",
        ".github/pull_request_template.md",
        "AGENTS.md",
    }
)


def loader_paths() -> frozenset[str]:
    """The matrix-selected loader roots, derived exactly as ``pr_gate`` derives them."""

    matrix = json.loads((REPO / "release" / "release-matrix.json").read_text("utf-8"))
    return frozenset(
        path
        for loader in {row["loader"] for row in matrix["artifacts"]}
        for path in (f"{loader}/build.gradle", f"{loader}/src/e2e")
    )


def admissible(path: str) -> bool:
    return path not in pr_gate.EXACT_BASE_OWNED_PATHS and pr_gate._upgrade_path_allowed(path, loader_paths())


def protected(path: str) -> bool:
    return any(path == root or path.startswith(f"{root}/") for root in gate_controller.PROTECTED_PATHS)


def config() -> dict:
    return json.loads(CONFIG.read_text("utf-8"))


def kit_owned_paths() -> dict[str, str]:
    """Each control path through which mod-base code or data reaches Block Pops, with its role."""

    adapter = config()["adapter"]
    pin = mod_base_kit.parse_pin(REPO)
    paths = {
        "site/mod-base.json": "configuration",
        adapter["path"]: "adapter",
        "scripts/ci/mod_base_kit.py": "bootstrap",
        ".github/workflows/pages.yml": "caller",
        ".github/workflows/notify-pages.yml": "notifier",
    }
    if adapter.get("fixtures_path"):
        paths[adapter["fixtures_path"]] = "conformance fixtures"
    for reference in pin.references:
        paths.setdefault(reference.rsplit("@", 1)[0], "pinned workflow")
    return paths


def _module_file(name: str) -> Path | None:
    """The repository file of the first-party module ``name``, or ``None`` if it is not one."""

    base = REPO.joinpath(*name.split("."))
    for candidate in (base.with_suffix(".py"), base / "__init__.py"):
        if candidate.is_file():
            return candidate
    return None


def adapter_import_closure() -> dict[str, str]:
    """Every first-party module the adapter and its fixtures import, transitively, with its path.

    Imports inside functions count too: the adapter child resolves them lazily from the same path.
    A ``from package import name`` counts ``package.name`` when that is a module file.
    """

    adapter = config()["adapter"]
    pending = [REPO / adapter["path"]]
    if adapter.get("fixtures_path"):
        pending.append(REPO / adapter["fixtures_path"])
    seen: dict[str, str] = {}
    while pending:
        source = pending.pop()
        relative = source.relative_to(REPO).as_posix()
        if relative in seen.values():
            continue
        seen[relative.removesuffix("/__init__.py").removesuffix(".py").replace("/", ".")] = relative
        for node in ast.walk(ast.parse(source.read_text("utf-8"), filename=relative)):
            names: list[str] = []
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                if node.level:
                    raise AssertionError(f"{relative} uses a relative import")
                names = [node.module or ""]
                names += [f"{node.module}.{alias.name}" for alias in node.names]
            for name in names:
                parts = name.split(".")
                for depth in range(1, len(parts) + 1):
                    found = _module_file(".".join(parts[:depth]))
                    if found is not None and found.relative_to(REPO).as_posix() not in seen.values():
                        pending.append(found)
    return seen


def template_entries() -> list[dict]:
    manifest = json.loads(
        (mod_base_path.kit_root() / "template" / "manifest.json").read_text("utf-8")
    )
    return manifest["files"]


class ModBasePathTests(unittest.TestCase):
    def test_kit_owned_paths_are_protected_and_admissible_controller_files(self) -> None:
        paths = kit_owned_paths()
        self.assertGreaterEqual(len(paths), 6)
        for path, role in sorted(paths.items()):
            with self.subTest(path=path, role=role):
                self.assertEqual(path, PurePosixPath(path).as_posix())
                self.assertTrue((REPO / path).is_file(), f"{role} {path} is missing")
                self.assertFalse((REPO / path).is_symlink())
                self.assertTrue(protected(path), f"{role} {path} is outside PROTECTED_PATHS")
                self.assertTrue(admissible(path), f"{role} {path} is not controller-upgrade admissible")

    def test_adapter_and_its_import_roots_live_under_protected_roots(self) -> None:
        adapter = config()["adapter"]
        self.assertTrue(adapter["path"].startswith("scripts/pages/"))
        if adapter.get("fixtures_path"):
            self.assertTrue(adapter["fixtures_path"].startswith("scripts/pages/"))
        # The adapter child imports the protected scripts.* and e2e.* packages from the repository
        # root, which is therefore its only python_path entry.
        self.assertEqual(["."], adapter["python_path"])

    def test_every_first_party_module_the_adapter_imports_is_protected(self) -> None:
        closure = adapter_import_closure()
        self.assertIn("scripts.ci.gate_controller", closure)
        self.assertIn("scripts.release.matrix", closure)
        self.assertIn("e2e.packaged_runtime", closure)
        for module, path in sorted(closure.items()):
            with self.subTest(module=module):
                self.assertTrue(protected(path), f"{module} ({path}) is outside PROTECTED_PATHS")
                self.assertTrue(admissible(path), f"{module} ({path}) is not controller-upgrade admissible")

    def test_the_unprotected_repository_root_on_the_adapter_path_is_guarded(self) -> None:
        # python_path "." exposes the root, which PROTECTED_PATHS does not cover, to the adapter child
        # (and every controller puts it on sys.path). The protected Build gate's identity job runs
        # mod_base_boundary.py import-root against every candidate, and this tree passes it.
        self.assertEqual([], mod_base_boundary.import_root_problems(REPO))
        self.assertTrue(protected("scripts/ci/mod_base_boundary.py"))
        identity = (REPO / ".github" / "workflows" / "build-gate.yml").read_text("utf-8")
        identity = identity.split("\n  identity:\n", 1)[1].split("\n  build:\n", 1)[0]
        self.assertIn(
            "python3 -P controller/scripts/ci/mod_base_boundary.py \\\n            import-root --repo candidate\n",
            identity,
        )
        # Every first-party root package the adapter reaches is one of the root's protected packages
        # or the scripts namespace, whose own __init__ the same check refuses.
        roots = {module.split(".", 1)[0] for module in adapter_import_closure()}
        self.assertLessEqual(roots, mod_base_boundary.ROOT_PACKAGES | {"scripts"})
        self.assertFalse((REPO / "scripts" / "__init__.py").exists())

    def test_site_is_never_empty(self) -> None:
        # Controller parity runs ``git cat-file -e <sha>:site``: the configuration keeps site/ alive.
        self.assertTrue(protected("site/mod-base.json"))
        self.assertTrue(CONFIG.is_file())
        self.assertIn("site", gate_controller.PROTECTED_PATHS)

    def test_only_the_deferred_root_files_of_the_template_are_inadmissible(self) -> None:
        checked = {
            entry["path"] for entry in template_entries() if entry["class"] in ("managed", "fragment")
        }
        self.assertIn(".github/workflows/pages.yml", checked)
        self.assertEqual(DEFERRABLE_ROOT_FILES, {path for path in checked if not admissible(path)})
        # A seeded file is copied once and never checked again: an inadmissible one must already
        # exist, so the kit never needs a controller upgrade to change it.
        for entry in template_entries():
            if entry["class"] == "seeded" and not admissible(entry["path"]):
                with self.subTest(seeded=entry["path"]):
                    self.assertTrue((REPO / entry["path"]).is_file())

    def test_deferred_files_are_only_the_inadmissible_root_files(self) -> None:
        deferred = config()["template"]["deferred"]
        self.assertEqual(len(set(deferred)), len(deferred))
        # Durable: the list shrinks to empty once the follow-up pull requests land.
        self.assertLessEqual(set(deferred), DEFERRABLE_ROOT_FILES)
        for path in DEFERRABLE_ROOT_FILES - set(deferred):
            with self.subTest(path=path):
                self.assertTrue((REPO / path).is_file(), f"{path} is neither deferred nor present")


if __name__ == "__main__":
    unittest.main()
