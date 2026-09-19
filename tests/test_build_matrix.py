"""Planning cannot launch builds, hide missing lanes, or soften verification."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.lib.secure_json import SecureJsonError
from scripts.release.build_matrix import main, numeric_version, plan_build
from scripts.release.matrix import MatrixError
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix


ROOT = Path(__file__).resolve().parents[1]


class BuildMatrixPlanningTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "release/release-matrix.json"
        self.path.parent.mkdir()

    def write_matrix(self, matrix):
        for route in matrix["source_routing"].values():
            for key in ("canonical", "e2e"):
                if key in route:
                    (self.root / route[key]).mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(matrix))

    def test_schema_one_current_and_historical_commands_retain_owned_tasks_and_java(self):
        current = json.loads((ROOT / "release/release-matrix.json").read_text())
        for matrix in (current, arbitrary_named_1211_release_matrix()):
            self.write_matrix(matrix)
            plan = plan_build(self.path, windows=False)
            self.assertEqual("full", plan["scope"])
            self.assertFalse(plan["partial_scope"])
            for row in plan["lanes"]:
                node = row["artifact_node"]
                artifact = next(item for item in matrix["artifacts"] if item["artifact_node"] == node)
                home = str(self.root / "build/gradle-home" / node)
                self.assertEqual([
                    "./gradlew", "--no-daemon", "--no-parallel", "--max-workers=1",
                    "--dependency-verification", "strict", "--gradle-user-home", home,
                    f"-PblockpopsLane={node}", "validateReleaseMatrix",
                    artifact["gradle_task"], artifact["harness_task"],
                ], row["command"])
                self.assertEqual({"gradle": 21, "artifact": artifact["java"], "runtime": artifact["java"]},
                                 row["required_java"])
                self.assertEqual(home, row["gradle_user_home"])

    def test_full_is_default_even_during_preparation_and_partial_scope_is_explicit(self):
        self.write_matrix(schema2_configuration())
        with self.assertRaisesRegex(MatrixError, "unresolved"):
            plan_build(self.path)
        legacy = plan_build(self.path, scope="legacy")
        self.assertTrue(legacy["partial_scope"])
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"], legacy["selected_nodes"])
        selected = plan_build(self.path, artifact_node="neoforge-1.21.1", clean=True, windows=True)
        lane = selected["lanes"][0]
        self.assertEqual("lane", selected["scope"])
        self.assertTrue(selected["partial_scope"])
        self.assertEqual("gradlew.bat", lane["command"][0])
        self.assertIn(":neoforge:1.21.1:clean", lane["command"])
        self.assertNotIn("clean", lane["command"])
        self.assertTrue(lane["outputs"]["production"].endswith("1.21.1-2.3.4.jar"))
        for node in ("fabric-1.21.7", "forge-1.21.7"):
            with self.subTest(node=node), self.assertRaises(MatrixError):
                plan_build(self.path, artifact_node=node)
        with self.assertRaises(MatrixError):
            plan_build(self.path, scope="legacy", artifact_node="neoforge-1.21.1")

    def test_shared_inventory_is_sorted_numerically_then_by_loader_and_isolated(self):
        matrix = schema2_configuration(shared=True)
        expected = [item["artifact_node"] for item in matrix["targets"]]
        matrix["artifacts"].reverse()
        matrix["runtimes"].reverse()
        self.write_matrix(matrix)
        plan = plan_build(self.path)
        self.assertEqual(expected, plan["selected_nodes"])
        self.assertEqual(12, len({row["gradle_user_home"] for row in plan["lanes"]}))
        self.assertFalse(plan["partial_scope"])
        self.assertEqual(["1.20.1", "1.21.7", "1.21.10"],
                         sorted(["1.21.10", "1.21.7", "1.20.1"], key=numeric_version))

    def test_plan_binds_exact_matrix_bytes_without_claiming_source_toolchains_or_execution(self):
        self.write_matrix(schema2_configuration(shared=True))
        with patch("subprocess.run") as run, patch("subprocess.Popen") as popen:
            first = plan_build(self.path)
            run.assert_not_called()
            popen.assert_not_called()
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), first["matrix"]["sha256"])
        self.assertEqual("planned", first["status"])
        self.assertEqual("unverified", first["source"]["status"])
        self.assertEqual("unverified", first["toolchains"]["status"])
        self.assertFalse((self.root / "build").exists())
        self.path.write_bytes(self.path.read_bytes() + b"\n")
        second = plan_build(self.path)
        self.assertNotEqual(first["matrix"]["sha256"], second["matrix"]["sha256"])
        self.assertEqual(first["lanes"], second["lanes"])

    def test_invalid_configuration_and_wrong_gradle_java_cannot_be_planned(self):
        matrix = arbitrary_named_1211_release_matrix()
        matrix["gradle_java"] = 25
        self.write_matrix(matrix)
        with self.assertRaisesRegex(MatrixError, "Gradle Java 21"):
            plan_build(self.path)
        matrix = schema2_configuration()
        matrix["runtimes"].pop()
        self.write_matrix(matrix)
        with self.assertRaises(MatrixError):
            plan_build(self.path, scope="legacy")

    def test_plan_preserves_the_secure_reader_and_explicit_scope_boundary(self):
        self.write_matrix(schema2_configuration(shared=True))
        link = self.path.with_name("linked.json")
        link.symlink_to(self.path.name)
        with self.assertRaisesRegex(SecureJsonError, "symlink"):
            plan_build(link)
        for scope in (None, "lane", "configured"):
            with self.subTest(scope=scope), self.assertRaises(MatrixError):
                plan_build(self.path, scope=scope)
        self.path.write_text('{"schema_version": 1, "schema_version": 2}')
        with self.assertRaisesRegex(SecureJsonError, "duplicate"):
            plan_build(self.path)

    def test_cli_requires_plan_rejects_extra_gradle_flags_and_prints_actionable_failure(self):
        self.write_matrix(schema2_configuration())
        arguments = ["--matrix", str(self.path)]
        for extra in ([], ["--plan", "--parallel"], ["--plan", "--max-workers=4"],
                      ["--plan", "--dependency-verification", "off"],
                      ["--plan", "--scope", "full", "--artifact-node", "fabric-1.20.1"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                main(arguments + extra)
            self.assertEqual(2, error.exception.code)
        error = io.StringIO()
        with contextlib.redirect_stderr(error):
            self.assertEqual(2, main(arguments + ["--plan"]))
        self.assertIn("unresolved", error.getvalue())
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, main(arguments + ["--plan", "--scope", "legacy"]))
        self.assertEqual("planned", json.loads(output.getvalue())["status"])


if __name__ == "__main__":
    unittest.main()
