"""Normalized reports retain unresolved targets and immutable lane inputs."""

from __future__ import annotations

import copy
import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.release.matrix import MatrixError, gha_matrix, load_matrix_inventory, main, normalize_matrix_inventory
from tests.test_release_matrix_schema2 import BASE_MATRIX, REPOSITORY, schema2_matrix
from tests.test_release_matrix_schema2_configuration import mixed_matrix


def shared_matrix() -> dict:
    """Synthetic parser inputs demonstrate coverage, never lane qualification."""
    matrix = mixed_matrix()
    artifacts = {row["artifact_node"]: row for row in matrix["artifacts"]}
    runtimes = {row["artifact_node"]: row for row in matrix["runtimes"]}
    matrix["artifacts"], matrix["runtimes"] = [], []
    matrix["installers"].pop("neoforge-21.1.77")
    for target in matrix["targets"]:
        node, loader, minecraft = (target[key] for key in ("artifact_node", "loader", "minecraft"))
        template = node if minecraft == "1.20.1" else f"{loader}-1.21.1"
        artifact, runtime = copy.deepcopy(artifacts[template]), copy.deepcopy(runtimes[template])
        artifact.update(target, build_layout="stonecutter")
        runtime.update(target)
        artifact["gradle_task"] = f":{loader}:{minecraft}:remapJar"
        artifact["harness_task"] = f":{loader}:{minecraft}:remapE2EHarnessJar"
        display = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}[loader]
        prefix = f"{loader}/versions/{minecraft}/build/libs/"
        artifact["jar"] = prefix + f"BlockPops - {display} - {minecraft}-{{mod_version}}.jar"
        artifact["harness_jar"] = prefix + f"BlockPops E2E - {display} - {minecraft}-0.0.0.jar"
        artifact["metadata"]["minecraft"] = f"~{minecraft}" if loader == "fabric" else f"[{minecraft}]"
        for dependency in runtime["runtime_dependencies"]:
            if minecraft != "1.20.1":
                dependency["coordinate"] = dependency["coordinate"].replace("1.21.1", minecraft)
        if loader == "neoforge":
            version = f"21.{minecraft.split('.')[-1]}.1"
            runtime.update(loader_version=version, installer=f"neoforge-{version}")
            matrix["installers"][runtime["installer"]] = {
                "url": f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar",
                "sha256": "a" * 64,
            }
        matrix["artifacts"].append(artifact)
        matrix["runtimes"].append(runtime)
    matrix.update(lane_count=18, migration={"mode": "shared", "legacy_nodes": []})
    return matrix


class Schema2ReportTests(unittest.TestCase):
    def cli(self, matrix, *arguments):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            path.write_text(json.dumps(matrix))
            output, error = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
                code = main(["--matrix", str(path), "--no-source-check", *arguments])
            return code, output.getvalue(), error.getvalue()

    def test_cli_schema_one_preserves_raw_and_every_projection_byte_for_byte(self):
        for kind in (None, "artifacts", "java", "gradle-java", "runtime", "pr-anchors", "scheduled-anchors"):
            expected = gha_matrix(BASE_MATRIX, kind) if kind else BASE_MATRIX
            arguments = ("--kind", kind) if kind else ()
            code, output, error = self.cli(BASE_MATRIX, *arguments)
            self.assertEqual((0, ""), (code, error))
            self.assertEqual(json.dumps(expected, separators=(",", ":"), sort_keys=True) + "\n", output)

    def test_cli_preparing_dispatch_defaults_to_legacy_and_modern_lane_is_explicit(self):
        matrix = mixed_matrix()
        for kind in ("artifacts", "runtime", "pr-anchors", "scheduled-anchors"):
            code, output, error = self.cli(matrix, "--kind", kind)
            self.assertEqual((0, ""), (code, error))
            self.assertEqual(["fabric-1.20.1", "forge-1.20.1"],
                             [row["artifact_node"] for row in json.loads(output)["include"]])
            code, output, error = self.cli(matrix, "--kind", kind, "--artifact-node", "neoforge-1.21.1")
            self.assertEqual((0, ""), (code, error))
            self.assertEqual(["neoforge-1.21.1"], [row["artifact_node"] for row in json.loads(output)["include"]])
        for selection in (("--scope", "full"), ("--artifact-node", "fabric-1.21.7")):
            code, output, _ = self.cli(matrix, "--kind", "artifacts", *selection)
            self.assertEqual((2, ""), (code, output))

    def test_cli_shared_projects_all_targets_and_rejects_legacy_or_narrowed_inventory(self):
        matrix = shared_matrix()
        code, output, error = self.cli(matrix, "--kind", "artifacts", "--scope", "full")
        self.assertEqual((0, ""), (code, error))
        self.assertEqual({row["artifact_node"] for row in matrix["targets"]},
                         {row["artifact_node"] for row in json.loads(output)["include"]})
        for arguments in (("--kind", "runtime", "--scope", "legacy"),
                          ("--kind", "inventory", "--artifact-node", "fabric-1.20.1"),
                          ("--scope", "full"), ("--kind", "gradle-context", "--scope", "full")):
            code, output, _ = self.cli(matrix, *arguments)
            self.assertEqual((2, ""), (code, output))

    def test_preparing_report_retains_every_target_and_its_unresolved_rest(self):
        inventory = normalize_matrix_inventory(schema2_matrix())
        report = inventory.report()
        self.assertEqual((18, 2), (report["target_count"], report["lane_count"]))
        self.assertEqual((2, "preparing"), (report["schema_version"], report["migration_mode"]))
        self.assertFalse(report["configuration_complete"])
        self.assertFalse(report["execution_supported"])
        self.assertFalse(report["sources_checked"])
        self.assertEqual(("fabric-1.20.1", "forge-1.20.1"), inventory.configured_nodes)
        unresolved = [row for row in report["targets"] if row["configuration"] == "unresolved"]
        self.assertEqual(16, len(unresolved))
        self.assertTrue(all(row["missing_inputs"] == ["artifact", "runtime"] for row in unresolved))
        self.assertTrue(all(row["missing_inputs"] == [] for row in report["targets"]
                            if row["configuration"] == "configured"))
        for node in ("fabric-1.21.1", "forge-1.21.7"):
            with self.subTest(node=node), self.assertRaises(MatrixError):
                inventory.lane(node)
        with self.assertRaises(MatrixError):
            inventory.require_complete()

    def test_lane_values_and_nested_projections_do_not_alias_caller_data(self):
        matrix = mixed_matrix()
        inventory = normalize_matrix_inventory(matrix)
        lane = inventory.lane("neoforge-1.21.1")
        self.assertEqual("neoforge-1.21.1", lane.identity.artifact_node)
        self.assertEqual(("common", "neoforge"), lane.source_routes)
        self.assertEqual(("stonecutter", 21, "neoforge"),
                         (lane.build_layout, lane.gradle_java, lane.repository_family))
        self.assertEqual("2.3.4", lane.mod_version)
        self.assertEqual(BASE_MATRIX["project"]["mod_version"], inventory.lane("fabric-1.20.1").mod_version)
        self.assertTrue(lane.production_jar.endswith("1.21.1-2.3.4.jar"))
        self.assertTrue(lane.harness_jar.endswith("1.21.1-0.0.0.jar"))
        matrix["artifacts"][-1]["metadata"]["minecraft"] = "changed"
        matrix["runtimes"][-1]["runtime_dependencies"][0]["coordinate"] = "changed"
        lane.artifact["metadata"]["minecraft"] = "also changed"
        lane.runtime["runtime_dependencies"].clear()
        self.assertEqual("[1.21.1,1.21.2)", lane.artifact["metadata"]["minecraft"])
        self.assertEqual(2, len(lane.runtime["runtime_dependencies"]))
        self.assertNotEqual("changed", lane.runtime["runtime_dependencies"][0]["coordinate"])

    def test_source_checks_and_legacy_projection_remain_explicit(self):
        self.assertTrue(normalize_matrix_inventory(schema2_matrix(), repository=REPOSITORY).sources_checked)
        inventory = normalize_matrix_inventory(copy.deepcopy(BASE_MATRIX))
        lane = inventory.lane("fabric-1.20.1")
        self.assertEqual("legacy", lane.build_layout)
        self.assertEqual(inventory.lanes, inventory.require_complete())
        self.assertTrue(inventory.execution_supported)
        self.assertEqual(BASE_MATRIX["artifacts"][0]["jar"].format(mod_version=BASE_MATRIX["project"]["mod_version"]),
                         lane.production_jar)

    def test_shared_configuration_requires_all_pairs_without_claiming_execution(self):
        inventory = normalize_matrix_inventory(shared_matrix())
        self.assertEqual(18, len(inventory.require_complete()))
        self.assertTrue(inventory.report()["configuration_complete"])
        self.assertFalse(inventory.execution_supported)
        for mutation in ("missing pair", "orphan", "metadata", "harness"):
            matrix = shared_matrix()
            if mutation == "missing pair":
                matrix["artifacts"].pop(-2)
                matrix["runtimes"].pop(-2)
                matrix["lane_count"] -= 1
            elif mutation == "orphan":
                matrix["runtimes"].pop()
            elif mutation == "metadata":
                matrix["artifacts"][-1]["metadata"]["minecraft"] = "[1.21.4]"
            else:
                matrix["artifacts"][-1]["harness_jar"] = matrix["artifacts"][-3]["harness_jar"]
            with self.subTest(mutation=mutation), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)

    def test_inventory_and_cli_projection_preserve_unresolved_targets(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            path.write_text(json.dumps(schema2_matrix()))
            inventory = load_matrix_inventory(path, validate_sources=False)
            command = [sys.executable, str(REPOSITORY / "scripts/release/matrix.py"),
                       "--matrix", str(path), "--no-source-check"]
            report = subprocess.run(command + ["--kind", "inventory"], capture_output=True, text=True)
            self.assertEqual(0, report.returncode, report.stderr)
            self.assertEqual(inventory.report(), json.loads(report.stdout))
            projection = subprocess.run(command + ["--kind", "artifacts"], capture_output=True, text=True)
            self.assertEqual(0, projection.returncode, projection.stderr)
            self.assertEqual(list(inventory.configured_nodes),
                             [row["artifact_node"] for row in json.loads(projection.stdout)["include"]])
            unresolved = subprocess.run(command + ["--kind", "artifacts", "--scope", "full"],
                                        capture_output=True, text=True)
            self.assertEqual(2, unresolved.returncode)
            self.assertEqual("", unresolved.stdout)

    def test_untrusted_inventory_inputs_fail_with_matrix_errors(self):
        for key in ("unit_test_lane", "side"):
            matrix = schema2_matrix()
            if key == "side":
                matrix["runtimes"][0]["runtime_dependencies"][0][key] = []
            else:
                matrix[key] = []
            with self.subTest(key=key), self.assertRaises(MatrixError):
                normalize_matrix_inventory(matrix)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            for raw in (b'{"schema_version":2,"schema_version":2}', b'{"schema_version":NaN}'):
                path.write_bytes(raw)
                with self.subTest(raw=raw), self.assertRaises(MatrixError):
                    load_matrix_inventory(path, validate_sources=False)


if __name__ == "__main__":
    unittest.main()
