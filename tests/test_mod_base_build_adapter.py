"""The mod-base Build adapter derives exactly the native Block Pops Build and E2E matrix.

``scripts/ci/mod-base-build.json`` names the dispatcher, the adapter and every file the protected
hooks import, each with its SHA-256. The kit runs a protected hook from a copy that holds the
config and exactly those files, as a validator account with an environment built from nothing, and
gives it the candidate's matrix and scenario contract in ``validation-input/``. These tests build
that copy from the listed files, run ``derive_plan`` and ``derive_runtime`` through the dispatcher
the way the kit runs them (``<python> -I -B <dispatcher> --hook <name>``) and compare the result
with what the native code derives: ``build_matrix.plan_build`` for targets, lanes and JAR names,
``matrix.py``'s ``pr-anchors`` projection for the runtime rows. Once the pin is a kit with the
Build adapter contract (v1.1.1 or later), the derived document also goes through the kit's own
planning (``mod_base.build_ci.planning.build_plan``) and protected config loader.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path, PurePosixPath
from typing import Any

from scripts.lib.secure_json import canonical_json
from scripts.release.build_matrix import plan_build
from scripts.release.matrix import load_matrix_document

REPO = Path(__file__).resolve().parents[1]
CONFIG = "scripts/ci/mod-base-build.json"
REPOSITORY = "The-Plum-Team/Block-Pops-Minecraft-Mod"
MATRIX = REPO / "release" / "release-matrix.json"
CONTRACT = REPO / "e2e" / "scenario-contract.json"
TESTED_SHA = "e" * 40
TESTED_TREE = "f" * 40
KIT_WITH_BUILD_ADAPTER = (1, 1, 0)


def _config() -> dict[str, Any]:
    return json.loads((REPO / CONFIG).read_bytes())


def _native_lanes() -> list[dict[str, Any]]:
    return plan_build(MATRIX, scope=load_matrix_document(MATRIX).default_scope)["lanes"]


def _native_rows() -> dict[str, dict[str, Any]]:
    document = load_matrix_document(MATRIX)
    rows = document.projection("pr-anchors", scope=document.default_scope)["include"]
    return {row["artifact_node"]: row for row in rows}


class Worker:
    """A worker root as the kit lays it out for a protected hook, holding the protected adapter
    copy (the config and exactly its listed files) and the staged candidate files."""

    def __init__(self, directory: Path) -> None:
        self.root = directory / "worker"
        self.controller = self.root / "controller"
        self.home = self.root / "validator-home"
        self.inputs = self.root / "validation-input"
        for path in (self.home / "tmp", self.inputs):
            path.mkdir(parents=True)
        config = _config()
        for name in (CONFIG, *(entry["path"] for entry in config["adapter"]["files"])):
            target = self.controller / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / name, target)
        shutil.copyfile(MATRIX, self.inputs / "inventory")
        shutil.copyfile(CONTRACT, self.inputs / "scenario-contract")
        self.dispatcher = self.controller / config["adapter"]["dispatcher"]

    def environment(self, **values: str) -> dict[str, str]:
        """The validator's environment: nothing of this process, the kit's Python safety settings."""

        return {"HOME": str(self.home), "TMPDIR": str(self.home / "tmp"), "PATH": os.defpath,
                "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "PYTHONNOUSERSITE": "1", "PYTHONSAFEPATH": "1",
                "PYTHONDONTWRITEBYTECODE": "1", "PYTHONPYCACHEPREFIX": str(self.home / "tmp" / "pycache"),
                "MB_TESTED_SHA": TESTED_SHA, "MB_TESTED_TREE": TESTED_TREE, **values}

    def run(self, hook: str, **values: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, "-I", "-B", str(self.dispatcher), "--hook", hook],
                              cwd=self.controller, env=self.environment(**values), stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=300, check=False)

    def output(self, name: str) -> bytes:
        return (self.home / "validation" / name).read_bytes()

    def clear_output(self) -> None:
        shutil.rmtree(self.home / "validation", ignore_errors=True)

    def stage_plan(self, document: dict[str, Any], *, inventory: bytes | None = None) -> None:
        """What ``ci plan`` leaves for the later hooks: the plan bound to the candidate files."""

        plan = {"identity": {"inventory_sha256": hashlib.sha256(inventory or MATRIX.read_bytes()).hexdigest(),
                             "scenario_sha256": hashlib.sha256(CONTRACT.read_bytes()).hexdigest(),
                             "tested_sha": TESTED_SHA, "tested_tree": TESTED_TREE},
                "plan_inputs": [], **document}
        (self.inputs / "ci-plan.json").write_bytes(canonical_json(plan))


class BuildConfigTests(unittest.TestCase):
    def test_the_config_lists_every_adapter_file_with_its_hash(self) -> None:
        config = _config()
        self.assertEqual(config["kind"], "mod-base.build.config")
        self.assertEqual(config["repository"], REPOSITORY)
        self.assertEqual(config["profile"], "block-pops")
        self.assertEqual(config["runtime"], {"system_profile": "xvfb-mesa"})
        self.assertEqual(config["plan_inputs"], [])
        self.assertEqual(config["inventory"], {"path": "release/release-matrix.json"})
        self.assertEqual(config["scenario_contract"], {"path": "e2e/scenario-contract.json"})
        self.assertEqual(config["contexts"], {"build": "Trusted PR / Build and verify",
                                              "packaged": "Trusted PR / Packaged E2E gate"})
        files = config["adapter"]["files"]
        paths = [entry["path"] for entry in files]
        self.assertEqual(paths, sorted(set(paths)))
        self.assertLessEqual({config["adapter"][key] for key in ("path", "dispatcher", "policy")}, set(paths))
        for entry in files:
            with self.subTest(path=entry["path"]):
                self.assertEqual(hashlib.sha256((REPO / entry["path"]).read_bytes()).hexdigest(), entry["sha256"])

    def test_the_activation_manifest_names_the_same_repository_and_profile(self) -> None:
        manifest = json.loads((REPO / "site" / "mod-base-build-activation.json").read_bytes())
        self.assertEqual(set(manifest), {"kind", "schema_version", "repository", "profile", "mode", "rollback_from"})
        self.assertEqual((manifest["kind"], manifest["schema_version"]), ("mod-base.ci.activation", 1))
        self.assertEqual((manifest["repository"], manifest["profile"]), (REPOSITORY, "block-pops"))
        self.assertIn(manifest["mode"], {"disabled", "shadow"})
        self.assertIsNone(manifest["rollback_from"])

    def test_the_protected_copy_imports_every_verifier(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            worker = Worker(Path(directory))
            # What the verification hooks import, from the protected copy alone.
            probe = ("import sys\n"
                     "from pathlib import Path\n"
                     "sys.path[:0] = [sys.argv[1], sys.argv[1] + '/scripts/ci']\n"
                     "import mod_base_build_adapter as adapter\n"
                     "adapter.bind_contract(Path(sys.argv[2]))\n"
                     "from scripts.ci import e2e_fanin\n"
                     "from scripts.release import artifact_manifest, build_evidence, build_matrix\n"
                     "import mod_base_build_dispatch, mod_base_build_policy\n"
                     "assert all(Path(m.__file__).resolve().is_relative_to(Path(sys.argv[1]).resolve())\n"
                     "           for m in list(sys.modules.values()) if getattr(m, '__file__', None)\n"
                     "           and m.__name__.split('.')[0] in {'scripts', 'e2e', 'scenario_contract'})\n")
            process = subprocess.run([sys.executable, "-I", "-B", "-c", probe,
                                      str(worker.controller), str(worker.inputs / "scenario-contract")],
                                     cwd=worker.controller, env=worker.environment(), capture_output=True, text=True,
                                     timeout=120, check=False)
            self.assertEqual(process.returncode, 0, process.stderr)


class DerivePlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.directory = tempfile.TemporaryDirectory()
        cls.worker = Worker(Path(cls.directory.name))
        process = cls.worker.run("derive_plan")
        if process.returncode != 0:
            raise AssertionError(f"derive_plan failed: {process.stdout}{process.stderr}")
        cls.raw = cls.worker.output("plan.json")
        cls.plan = json.loads(cls.raw)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def test_the_document_is_canonical_and_closed(self) -> None:
        self.assertEqual(set(self.plan), {"targets", "lanes"})
        self.assertEqual(self.raw, canonical_json(self.plan) + b"\n")

    def test_targets_are_the_native_build_order_grouped_by_minecraft(self) -> None:
        native = _native_lanes()
        expected: dict[str, list[dict[str, Any]]] = {}
        for lane in native:
            expected.setdefault(lane["minecraft"], []).append(lane)
        self.assertEqual([target["id"] for target in self.plan["targets"]], list(expected))
        self.assertEqual(len(self.plan["targets"]), 10)
        self.assertEqual(len(self.plan["lanes"]), 20)
        self.assertEqual([lane["id"] for lane in self.plan["lanes"]], [lane["artifact_node"] for lane in native])
        for target in self.plan["targets"]:
            members = expected[target["id"]]
            with self.subTest(target=target["id"]):
                self.assertEqual({target["java"]}, {lane["required_java"]["artifact"] for lane in members})
                outputs = []
                for lane in members:
                    node = lane["artifact_node"]
                    reports = f"targets/{target['id']}/lanes/{node}"
                    outputs += [
                        {"path": "files/" + PurePosixPath(lane["outputs"]["production"]).name, "lane_id": node,
                         "role": "production"},
                        {"path": "harness/" + PurePosixPath(lane["outputs"]["harness"]).name, "lane_id": node,
                         "role": "harness"},
                        {"path": f"{reports}/artifacts.json", "lane_id": node, "role": "native-report"},
                        {"path": f"{reports}/build-matrix-report.json", "lane_id": node, "role": "native-report"},
                    ]
                self.assertEqual(target["outputs"], outputs)
                self.assertRegex(target["native_contract_sha256"], r"^[0-9a-f]{64}$")

    def test_every_output_path_is_unique_and_a_valid_export_path(self) -> None:
        paths = [output["path"] for target in self.plan["targets"] for output in target["outputs"]]
        self.assertEqual(len(paths), 80)
        self.assertEqual(len({path.casefold() for path in paths}), len(paths))
        component = re.compile(r"^[A-Za-z0-9._+-]+(?: [A-Za-z0-9._+-]+)*$")
        for path in paths:
            self.assertTrue(all(component.fullmatch(part) and not part.startswith(".") and not part.endswith(".")
                                for part in path.split("/")), path)

    def test_lanes_carry_the_native_pr_anchor_scenarios(self) -> None:
        rows = _native_rows()
        targets = {lane["artifact_node"]: lane["minecraft"] for lane in _native_lanes()}
        for lane in self.plan["lanes"]:
            with self.subTest(lane=lane["id"]):
                self.assertEqual(lane["target_id"], targets[lane["id"]])
                self.assertEqual(lane["obligations"], rows[lane["id"]]["scenarios"].split(","))
                self.assertNotIn("--", lane["id"])

    def test_the_same_inputs_give_the_same_document(self) -> None:
        self.worker.clear_output()
        try:
            process = self.worker.run("derive_plan")
            self.assertEqual(process.returncode, 0, process.stdout)
            self.assertEqual(self.worker.output("plan.json"), self.raw)
        finally:
            self.worker.clear_output()
            (self.worker.home / "validation").mkdir()
            (self.worker.home / "validation" / "plan.json").write_bytes(self.raw)


class DeriveRuntimeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.worker = Worker(Path(self.directory.name))
        process = self.worker.run("derive_plan")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.plan = json.loads(self.worker.output("plan.json"))
        self.worker.clear_output()

    def test_every_lane_receives_its_native_pr_anchor_row(self) -> None:
        self.worker.stage_plan(self.plan)
        for node, row in _native_rows().items():
            with self.subTest(lane=node):
                process = self.worker.run("derive_runtime", MB_LANE_ID=node)
                self.assertEqual(process.returncode, 0, process.stdout)
                values = json.loads(self.worker.output("runtime.json"))["values"]
                self.worker.clear_output()
                self.assertEqual(values["E2E_ROW_JSON"], canonical_json(row).decode())
                self.assertEqual(values["E2E_SCENARIOS"], row["scenarios"])
                self.assertEqual(row["id"], node.replace(".", "_") + "--pr-behavior")

    def test_a_plan_of_other_inputs_or_targets_is_refused(self) -> None:
        self.worker.stage_plan(self.plan, inventory=b"{}")
        self.assertNotEqual(self.worker.run("derive_runtime", MB_LANE_ID="fabric-1.20.1").returncode, 0)
        changed = json.loads(json.dumps(self.plan))
        changed["targets"][0]["java"] = 21
        self.worker.stage_plan(changed)
        self.assertNotEqual(self.worker.run("derive_runtime", MB_LANE_ID="fabric-1.20.1").returncode, 0)
        self.worker.stage_plan(self.plan)
        self.assertNotEqual(self.worker.run("derive_runtime", MB_LANE_ID="fabric-1.19.2").returncode, 0)
        self.assertNotEqual(self.worker.run("derive_runtime").returncode, 0)
        self.assertFalse((self.worker.home / "validation" / "runtime.json").exists())

    def test_a_sealed_build_with_an_unplanned_file_is_refused(self) -> None:
        self.worker.stage_plan(self.plan)
        sealed = self.worker.root / "sealed-build"
        sealed.mkdir()
        (sealed / "unplanned.txt").write_bytes(b"x\n")
        process = self.worker.run("verify_target", MB_TARGET_ID="1.20.1")
        self.assertNotEqual(process.returncode, 0)
        self.assertIn("planned outputs", process.stdout)


def _pinned_version() -> tuple[int, ...]:
    from scripts.ci import mod_base_kit

    version = mod_base_kit.parse_pin(REPO).version
    return tuple(int(part) for part in re.findall(r"\d+", version)[:3])


class KitPlanningTests(unittest.TestCase):
    """The derived document through the pinned kit's planning, as every kit job builds it."""

    def test_the_kit_builds_the_plan_from_the_derived_document(self) -> None:
        if _pinned_version() < KIT_WITH_BUILD_ADAPTER:
            self.skipTest("the pinned kit predates the Build adapter contract; the mod-base v1.1.1 bump runs this")
        from tests.mod_base_path import kit_root

        kit_root()
        import mod_base
        from mod_base.build_ci import adapter, planning
        from mod_base.build_ci.config import load_build_config
        from mod_base.build_ci.protocol import BUILD_GRAPH_VERSION
        from mod_base.model import grammar
        from mod_base.workflow import CI_CALLER_WORKFLOWS

        config = load_build_config(REPO, repository=REPOSITORY)
        with tempfile.TemporaryDirectory() as directory:
            worker = Worker(Path(directory))
            process = worker.run("derive_plan")
            self.assertEqual(process.returncode, 0, process.stdout)
            derived = worker.output("plan.json")
        controller = "c" * 40
        subject = {
            "repository": REPOSITORY, "source_repository": REPOSITORY, "pr_number": 7, "head_sha": "a" * 40,
            "head_branch": "feature/adapter", "base_sha": controller, "base_branch": "master",
            "controller_sha": controller, "controller_workflow": CI_CALLER_WORKFLOWS["build"],
            "controller_ref": grammar.workflow_ref(REPOSITORY, CI_CALLER_WORKFLOWS["build"], "master"),
            "kit": {"repository": mod_base.KIT_REPOSITORY, "sha": "3" * 40, "version": mod_base.__version__,
                    "tree_digest": "sha256:" + "4" * 64},
            "tested_sha": TESTED_SHA, "tested_tree": TESTED_TREE, "tested_parents": [controller, "a" * 40],
            "graph_version": BUILD_GRAPH_VERSION,
        }
        plan = planning.build_plan(subject=subject, config=config, inventory=MATRIX.read_bytes(),
                                   scenario_contract=CONTRACT.read_bytes(), plan_inputs={}, derived=derived)
        self.assertEqual((len(plan["targets"]), len(plan["lanes"])), (10, 20))
        self.assertEqual({key: plan[key] for key in ("targets", "lanes")}, json.loads(derived))
        for target in plan["targets"]:
            self.assertEqual(len(adapter.target_outputs(plan, target["id"])), 8)


#: The adoption order: each committed mode enters from this one predecessor (``None``: no
#: manifest), in a controller upgrade of its own that does not move the pin.
PREVIOUS_MODE = {"disabled": None, "shadow": "disabled"}


class ActivationTransitionTests(unittest.TestCase):
    """The kit admits a manifest change only at an unchanged pin, and no workflow checks it: the
    operator procedure runs ``template transition`` against the protected base."""

    def test_the_procedure_runs_the_transition_check_apart_from_the_bump(self) -> None:
        text = (REPO / "docs" / "operations.md").read_text("utf-8")
        section = text.split("### mod-base Build adapter\n", 1)[1].split("\n## ", 1)[0]
        self.assertIn("python3 scripts/ci/mod_base_kit.py run template transition --repo . --base ../base", section)
        self.assertIn("the kit bump\nalone (`bump --to vX.Y.Z`, no Build config and no manifest)", section)

    def test_the_committed_manifest_enters_only_at_an_unchanged_pin(self) -> None:
        if _pinned_version() < KIT_WITH_BUILD_ADAPTER:
            self.skipTest("the pinned kit predates activation manifests; the mod-base v1.1.1 bump runs this")
        from scripts.ci import mod_base_kit
        from tests.mod_base_path import kit_root

        kit_root()
        from mod_base.build_ci.transition import admit_transition
        from mod_base.errors import MbError
        from mod_base.pin import Pin

        current = (REPO / "site" / "mod-base-build-activation.json").read_bytes()
        mode = json.loads(current)["mode"]
        self.assertIn(mode, PREVIOUS_MODE)
        previous = None
        if PREVIOUS_MODE[mode] is not None:
            previous = (json.dumps({**json.loads(current), "mode": PREVIOUS_MODE[mode]}, indent=2) + "\n").encode()
        bootstrap = mod_base_kit.parse_pin(REPO)
        pin = Pin(bootstrap.sha, bootstrap.version, ())
        admitted = admit_transition(previous, current, protected_pin=pin, candidate_pin=pin)
        self.assertEqual((admitted.previous, admitted.current, admitted.changed),
                         (PREVIOUS_MODE[mode] or "absent", mode, True))
        older = Pin("0" * 40, "v1.0.3", ())
        with self.assertRaises(MbError) as caught:
            admit_transition(previous, current, protected_pin=older, candidate_pin=pin)
        self.assertIn("never comes with a pin change", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
