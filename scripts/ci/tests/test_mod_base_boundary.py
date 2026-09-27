"""The protected Build-gate checks at the Block Pops / mod-base boundary.

``import-root`` keeps the repository root, which the kit's adapter host and every controller put on
the Python path, free of anything Python could import ahead of protected code. ``composite`` verifies
the output of ``mod_base_kit.py stage`` (the bytes the sandbox receives at ``out/mod-base-kit``),
requires its lock-bound ``actions/``, and checks the candidate-pinned ``prepare-evidence`` composite
there. The staged kits here are real ``stage`` outputs of synthetic kits, plus one of the pinned kit;
``test_workflow_security`` also runs the composite check against whichever kit root the tests resolve.
"""

from __future__ import annotations

import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci import mod_base_boundary, mod_base_kit


REPO = Path(__file__).resolve().parents[3]
UNSET = "unset ACTIONS_RUNTIME_TOKEN ACTIONS_CACHE_URL ACTIONS_RESULTS_URL GITHUB_TOKEN"
KIT = "env -u PYTHONPATH PYTHONPATH=src PYTHONSAFEPATH=1 python3 -P"


def _step(name: str, script: str, *, env: str = "", shell: str = "bash") -> str:
    body = "".join(f"        {line}\n" if line else "\n" for line in script.splitlines())
    return f"    - name: {name}\n      shell: {shell}\n{env}      run: |\n{body}"


def composite(*steps: str) -> str:
    return "name: prepare-evidence\nruns:\n  using: composite\n  steps:\n" + "".join(steps)


TREE = _step(
    "Verify the executing kit",
    f"set -euo pipefail\n{UNSET}\n{KIT} tools/verify_action_tree.py --mod-root .",
    env="      env:\n        GH_TOKEN: ${{ github.token }}\n",
)
KIT_STEPS = tuple(
    _step(f"Kit step {number}", f"set -euo pipefail\n{UNSET} GH_TOKEN\n{KIT} -m mod_base {verb}")
    for number, verb in enumerate(("prepare", "validate", "anchor create"), start=1)
)
UPLOAD = (
    "    - name: Upload\n      uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1\n"
    "      with:\n        name: mb-handoff\n"
)
CLEAN = composite(TREE, UPLOAD, *KIT_STEPS)


class ImportRootTests(unittest.TestCase):
    def root(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        for package in ("e2e", "tests"):
            (root / package).mkdir()
            (root / package / "__init__.py").write_text("")
        for namespace in ("scripts/ci", "docs", "json", "sitecustomize"):
            (root / namespace).mkdir(parents=True)
        (root / "scripts" / "ci" / "__init__.py").write_text("")
        for name in ("README.md", "gradlew", "build.gradle", "usercustomize", "json.md"):
            (root / name).write_text("")
        return root

    def test_block_pops_root_holds_nothing_importable(self) -> None:
        self.assertEqual([], mod_base_boundary.import_root_problems(REPO))

    def test_protected_packages_and_namespace_directories_are_allowed(self) -> None:
        root = self.root()
        (root / "LICENSE.md").symlink_to(root / "README.md")
        self.assertEqual([], mod_base_boundary.import_root_problems(root))

    def test_every_importable_root_entry_is_refused(self) -> None:
        cases = {
            "sitecustomize.py": "file",
            "usercustomize.pyc": "file",
            "json.py": "file",
            "JSON.PY": "file",
            "hashlib.cpython-313-x86_64-linux-gnu.so": "file",
            "tempfile.abi3.so": "file",
            "legacy.pyo": "file",
            "shell.pyw": "file",
            "windows.pyd": "file",
            "hook.pth": "file",
            "PIL/__init__.py": "package",
            "sitecustomize/__init__.pyc": "package",
            "scripts/__init__.py": "package",
            "docs/__init__.so": "package",
            "tempfile/__INIT__.PY": "package",
        }
        for relative, kind in cases.items():
            with self.subTest(relative=relative):
                root = self.root()
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_text("")
                problems = mod_base_boundary.import_root_problems(root)
                self.assertEqual(1, len(problems), problems)
                top = relative.split("/", 1)[0]
                self.assertTrue(problems[0].startswith(f"{top}{'/' if kind == 'package' else ''}:"))

    def test_module_named_symlinks_and_linked_packages_are_refused(self) -> None:
        for name in ("hashlib", "e2e", "PIL"):
            with self.subTest(name=name):
                root = self.root()
                if name == "e2e":
                    shutil.rmtree(root / "e2e")
                (root / name).symlink_to(root / "tests", target_is_directory=True)
                problems = mod_base_boundary.import_root_problems(root)
                self.assertEqual([f"{name}: a root symbolic link with a module name"], problems)

    def test_unreadable_or_unbounded_roots_fail_closed(self) -> None:
        root = self.root()
        with self.assertRaises(mod_base_boundary.BoundaryError):
            mod_base_boundary.import_root_problems(root / "README.md")
        link = root.parent / f"{root.name}-link"
        link.symlink_to(root, target_is_directory=True)
        self.addCleanup(link.unlink)
        with self.assertRaises(mod_base_boundary.BoundaryError):
            mod_base_boundary.import_root_problems(link)
        with mock.patch.object(mod_base_boundary, "MAX_DIRECTORY_ENTRIES", 3):
            with self.assertRaises(mod_base_boundary.BoundaryError):
                mod_base_boundary.import_root_problems(root)

    def test_cli_reports_each_problem_and_fails(self) -> None:
        root = self.root()
        (root / "json.py").write_text("")
        (root / "scripts" / "__init__.py").write_text("")
        with mock.patch("sys.stderr") as stderr, mock.patch("sys.stdout"):
            status = mod_base_boundary.main(["import-root", "--repo", str(root)])
        self.assertEqual(1, status)
        written = "".join(call.args[0] for call in stderr.write.call_args_list)
        self.assertIn("json.py: a root file with an importable suffix", written)
        self.assertIn("scripts/: a root regular package", written)
        with mock.patch("sys.stderr"):
            self.assertEqual(2, mod_base_boundary.main(["import-root", "--repo", str(root / "missing")]))


class CompositeTests(unittest.TestCase):
    def test_the_kit_shaped_composite_passes(self) -> None:
        self.assertEqual([], mod_base_boundary.composite_problems(CLEAN))

    def test_weakened_token_handling_is_refused(self) -> None:
        prepare = KIT_STEPS[0]
        mutants = {
            "no scrub": prepare.replace(f"        {UNSET} GH_TOKEN\n", ""),
            "partial scrub": prepare.replace(" GITHUB_TOKEN GH_TOKEN\n", " GH_TOKEN\n"),
            "scrub after Python": prepare.replace(
                f"        {UNSET} GH_TOKEN\n        {KIT} -m mod_base prepare\n",
                f"        {KIT} -m mod_base prepare\n        {UNSET} GH_TOKEN\n",
            ),
            "token handed in": prepare.replace(
                "      run: |\n", "      env:\n        GH_TOKEN: ${{ github.token }}\n      run: |\n"
            ),
            "bare python": prepare.replace("python3 -P -m mod_base", "python -m mod_base").replace(
                f"        {UNSET} GH_TOKEN\n", ""
            ),
            "python shell": prepare.replace("      shell: bash\n", "      shell: python {0}\n"),
            "no shell": prepare.replace("      shell: bash\n", ""),
            "inline run": prepare.replace(
                "      run: |\n", f"      run: {KIT} -m mod_base prepare\n      shell: bash\n", 1
            ).replace("      shell: bash\n      shell: bash\n", "      shell: bash\n"),
            "folded run": prepare.replace("      run: |\n", "      run: >\n"),
            "second run": prepare + f"      run: {KIT} -m mod_base prepare\n",
            "secret": prepare.replace("-m mod_base prepare", "-m mod_base prepare ${{ secrets.PAT }}"),
        }
        # A fourth kit step keeps the Python step count at its minimum even when a mutant hides one.
        extra = KIT_STEPS[-1].replace("Kit step 3", "Kit step 4").replace("anchor create", "anchor validate")
        for label, mutant in mutants.items():
            with self.subTest(mutant=label):
                text = composite(TREE, UPLOAD, mutant, *KIT_STEPS[1:], extra)
                self.assertNotEqual(CLEAN, text)
                self.assertTrue(mod_base_boundary.composite_problems(text))

    def test_only_the_tree_check_keeps_its_read_only_token(self) -> None:
        tree_with_kit_code = TREE.replace("--mod-root .", "--mod-root .\n        python3 -P -m mod_base prepare")
        tree_with_runtime_token = TREE.replace(f"{UNSET}\n", UNSET.replace(" ACTIONS_RUNTIME_TOKEN", "") + "\n")
        for label, tree in (("kit code", tree_with_kit_code), ("runtime token", tree_with_runtime_token)):
            with self.subTest(tree=label):
                self.assertTrue(mod_base_boundary.composite_problems(composite(tree, UPLOAD, *KIT_STEPS)))

    def test_unrecognized_shapes_fail_closed(self) -> None:
        self.assertTrue(mod_base_boundary.composite_problems(composite(TREE, *KIT_STEPS[:2])))
        self.assertTrue(mod_base_boundary.composite_problems(CLEAN.replace("\n  steps:\n", "\n  steps: []\n")))
        self.assertTrue(
            mod_base_boundary.composite_problems(CLEAN.replace("    - name: ", "  - name: "))
        )
        self.assertTrue(
            mod_base_boundary.composite_problems(CLEAN.replace("  steps:\n", "  steps:\n    - uses: x\n", 1))
        )

    def test_composite_reads_refuse_links_and_oversized_files(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw) / "kit"
            action = root / "actions" / "prepare-evidence"
            action.mkdir(parents=True)
            (action / "action.yml").write_text(CLEAN, encoding="utf-8")
            self.assertEqual(CLEAN, mod_base_boundary.read_composite(root))
            (action / "action.yml").write_bytes(b"x" * (mod_base_boundary.MAX_ACTION_BYTES + 1))
            with self.assertRaises(mod_base_boundary.BoundaryError):
                mod_base_boundary.read_composite(root)
            (action / "action.yml").unlink()
            (action / "action.yml").symlink_to(Path(raw) / "elsewhere.yml")
            (Path(raw) / "elsewhere.yml").write_text(CLEAN, encoding="utf-8")
            with self.assertRaises(mod_base_boundary.BoundaryError):
                mod_base_boundary.read_composite(root)
            (action / "action.yml").unlink()
            (action / "action.yml").write_text(CLEAN, encoding="utf-8")
            os.rename(root / "actions", Path(raw) / "actions")
            (root / "actions").symlink_to(Path(raw) / "actions", target_is_directory=True)
            with self.assertRaises(mod_base_boundary.BoundaryError):
                mod_base_boundary.read_composite(root)


class StagedKitTests(unittest.TestCase):
    """``composite --kit`` reads only a verified ``stage`` output that carries lock-bound composites."""

    SHA = "4" * 40
    VERSION = "v1.2.3"

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        previous = os.umask(0o022)  # the stage step's umask
        self.addCleanup(os.umask, previous)

    def make_kit(self, name: str = "kit", *, action: str | None = CLEAN) -> Path:
        """A kit tree with its staged-file locks; ``action=None`` is a kit older than v0.9.2."""

        kit = self.root / name
        files = {
            "src/mod_base/__init__.py": "",
            "src/mod_base/template/__init__.py": "",
            "site/assets/site.css": "body{}\n",
            "requirements/pillow.txt": "pillow==12.3.0\n",
            "template/manifest.json": "{}\n",
            "tools/kit_digest.sh": "#!/bin/sh\n",
        }
        if action is not None:
            files["actions/setup/action.yml"] = "name: setup\n"
            files["actions/prepare-evidence/action.yml"] = action
        for relative, text in files.items():
            path = kit / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        (kit / mod_base_kit.STAGED_LOCK).write_bytes(mod_base_kit.staged_listing(kit))
        if action is not None:
            (kit / mod_base_kit.ACTIONS_LOCK).write_bytes(mod_base_kit.actions_listing(kit))
        return kit

    def make_repository(self, name: str, sha: str | None = None, version: str | None = None) -> Path:
        repository = self.root / name
        workflows = repository / ".github" / "workflows"
        workflows.mkdir(parents=True)
        (workflows / "build-gate.yml").write_text(
            "jobs:\n  build:\n    steps:\n"
            f"      - uses: The-Plum-Team/mod-base/actions/setup@{sha or self.SHA} # {version or self.VERSION}\n",
            encoding="utf-8",
        )
        return repository

    def stage(self, kit: Path, name: str = "staged") -> tuple[Path, Path]:
        """Stage ``kit`` for an unchanged pin exactly as the Build gate does; return (output, candidate)."""

        def offline(path: str) -> None:
            raise AssertionError(f"an equal pin must stage without the API: {path}")

        controller = self.make_repository(f"{name}-controller")
        candidate = self.make_repository(f"{name}-candidate")
        environment = {
            "CI": "true",
            "MOD_BASE_KIT_PATH": str(kit),
            "MOD_BASE_KIT_SHA": self.SHA,
            "MOD_BASE_CACHE_DIR": str(self.root / "cache"),
        }
        output = self.root / f"{name}-runner-temp" / "mod-base-kit"
        output.parent.mkdir()
        mod_base_kit.stage(controller, candidate, output, environment, get_json=offline)
        return output, candidate

    def check(self, kit: Path, candidate: Path) -> tuple[int, str]:
        with mock.patch("sys.stderr") as stderr, mock.patch("sys.stdout"):
            status = mod_base_boundary.main(["composite", "--kit", str(kit), "--candidate-repo", str(candidate)])
        return status, "".join(call.args[0] for call in stderr.write.call_args_list)

    def test_a_staged_kit_with_a_clean_composite_passes(self) -> None:
        staged, candidate = self.stage(self.make_kit())
        self.assertEqual(
            CLEAN, (staged / "actions" / "prepare-evidence" / "action.yml").read_text(encoding="utf-8")
        )
        self.assertEqual(staged, mod_base_boundary.verify_staged_kit(staged, candidate))
        self.assertEqual((0, ""), self.check(staged, candidate))

    def test_a_lock_bound_composite_that_hands_python_a_credential_fails(self) -> None:
        unsafe = composite(TREE, UPLOAD, KIT_STEPS[0].replace(f"        {UNSET} GH_TOKEN\n", ""), *KIT_STEPS[1:])
        staged, candidate = self.stage(self.make_kit(action=unsafe))
        status, written = self.check(staged, candidate)
        self.assertEqual(1, status)
        self.assertIn("Kit step 1: runs Python before it unsets any credential", written)

    def test_a_kit_older_than_v0_9_2_stages_no_actions_and_fails_closed(self) -> None:
        staged, candidate = self.stage(self.make_kit(action=None))
        self.assertFalse(os.path.lexists(staged / "actions"))
        status, written = self.check(staged, candidate)
        self.assertEqual(2, status)
        self.assertIn("the staged kit carries no actions", written)

    def test_a_controller_bootstrap_older_than_v0_9_2_fails_closed(self) -> None:
        staged, candidate = self.stage(self.make_kit())
        for name in ("ACTIONS_DIR", "ACTIONS_LOCK"):
            with self.subTest(missing=name), mock.patch.object(mod_base_kit, name, None):
                status, written = self.check(staged, candidate)
                self.assertEqual(2, status)
                self.assertIn("predates mod-base v0.9.2", written)

    def test_every_change_to_the_staged_bytes_or_the_pin_fails_closed(self) -> None:
        mutants = {
            "composite edited": (
                lambda kit: (kit / "actions" / "prepare-evidence" / "action.yml").write_text(CLEAN + "\n"),
                "does not match its src/mod_base/template/staged_actions.sha256",
            ),
            "extra composite": (
                lambda kit: (kit / "actions" / "setup" / "extra.yml").write_text("x\n"),
                "does not match its src/mod_base/template/staged_actions.sha256",
            ),
            "actions removed": (
                lambda kit: shutil.rmtree(kit / "actions"),
                "the staged kit carries no actions",
            ),
            "actions lock removed": (
                lambda kit: (kit / mod_base_kit.ACTIONS_LOCK).unlink(),
                "does not equal its stamp",
            ),
            "digested source edited": (
                lambda kit: (kit / "src" / "mod_base" / "__init__.py").write_text("planted = 1\n"),
                "does not equal its stamp",
            ),
            "stamp removed": (
                lambda kit: (kit / mod_base_kit.STAMP_NAME).unlink(),
                "cannot read MOD_BASE_KIT.json",
            ),
            "linked composite": (
                lambda kit: (
                    (kit / "actions" / "prepare-evidence" / "action.yml").unlink(),
                    (kit / "actions" / "prepare-evidence" / "action.yml").symlink_to(self.root / "elsewhere.yml"),
                ),
                "is a symlink or special file",
            ),
        }
        (self.root / "elsewhere.yml").write_text(CLEAN, encoding="utf-8")
        for number, (label, (mutate, message)) in enumerate(mutants.items()):
            with self.subTest(mutant=label):
                staged, candidate = self.stage(self.make_kit(f"kit-{number}"), f"staged-{number}")
                self.assertEqual((0, ""), self.check(staged, candidate))
                mutate(staged)
                status, written = self.check(staged, candidate)
                self.assertEqual(2, status, written)
                self.assertIn(message, written)

    def test_a_kit_staged_for_another_pin_is_refused(self) -> None:
        staged, _candidate = self.stage(self.make_kit())
        for sha, version in (("5" * 40, self.VERSION), (self.SHA, "v1.2.4")):
            with self.subTest(sha=sha, version=version):
                other = self.make_repository(f"candidate-{sha[0]}-{version}", sha, version)
                status, written = self.check(staged, other)
                self.assertEqual(2, status)
                self.assertIn(f"not the candidate pin {sha} {version}", written)

    def test_the_pinned_kit_staged_as_the_build_gate_stages_it_passes(self) -> None:
        # Stage whichever pinned kit root these tests resolved (the sandbox overlay, the setup
        # kit, or the fetched user cache) with Block Pops as both controller and candidate: the
        # released composite must survive staging under its lock and pass the gate's check.
        kit = Path(mod_base_kit.kit_path(REPO))
        pin = mod_base_kit.parse_pin(REPO)
        output = self.root / "runner-temp" / "mod-base-kit"
        output.parent.mkdir()
        environment = {
            "CI": "true",
            "MOD_BASE_KIT_PATH": str(kit),
            "MOD_BASE_KIT_SHA": pin.sha,
            "MOD_BASE_CACHE_DIR": str(self.root / "cache"),
        }

        def offline(path: str) -> None:
            raise AssertionError(f"an equal pin must stage without the API: {path}")

        mod_base_kit.stage(REPO, REPO, output, environment, get_json=offline)
        self.assertTrue((output / "actions" / "prepare-evidence" / "action.yml").is_file())
        self.assertEqual((0, ""), self.check(output, REPO))


if __name__ == "__main__":
    unittest.main()
