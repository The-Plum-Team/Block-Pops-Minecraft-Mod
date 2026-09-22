"""Gradle receives one normalized lane or a compatible legacy aggregate."""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.matrix import MatrixDocument, MatrixError, main, normalize_matrix_inventory
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix


def document(matrix):
    return MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))


class GradleContextTests(unittest.TestCase):
    def test_schema_one_keeps_exact_rows_and_global_version(self):
        matrix = arbitrary_named_1211_release_matrix()
        context = document(matrix).gradle_context()
        self.assertEqual(matrix, context["matrix"])
        self.assertEqual(matrix["artifacts"], [lane["artifact"] for lane in context["lanes"]])
        self.assertEqual(matrix["runtimes"], [lane["runtime"] for lane in context["lanes"]])
        self.assertEqual({matrix["project"]["mod_version"]},
                         {lane["mod_version"] for lane in context["lanes"]})
        self.assertEqual({"legacy"}, {lane["build_layout"] for lane in context["lanes"]})
        self.assertEqual(("full", None), (context["scope"], context["artifact_node"]))
        self.assertEqual({"artifact_node": "fabric-1.21.1",
                          "coordinate": "net.fabricmc:fabric-loader:0.17.0"},
                         context["common_annotation_dependency"])

    def test_preparing_defaults_only_to_legacy_context(self):
        source = document(schema2_configuration())
        context = source.gradle_context()
        self.assertEqual("legacy", context["scope"])
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"],
                         [lane["artifact"]["artifact_node"] for lane in context["lanes"]])
        self.assertEqual(4, len(context["matrix"]["artifacts"]))
        self.assertEqual({"artifact_node": "fabric-1.20.1",
                          "coordinate": "net.fabricmc:fabric-loader:0.17.3"},
                         context["common_annotation_dependency"])
        context["lanes"][0]["artifact"]["java"] = 99
        self.assertEqual(17, source.gradle_context()["lanes"][0]["artifact"]["java"])

    def test_annotation_dependency_uses_same_era_regardless_of_row_order(self):
        matrix = schema2_configuration()
        next(row for row in matrix["runtimes"]
             if row["artifact_node"] == "fabric-1.21.1")["loader_version"] = "0.17.0"
        for reordered in (False, True):
            if reordered:
                matrix["artifacts"].reverse()
                matrix["runtimes"] = matrix["runtimes"][1:] + matrix["runtimes"][:1]
            source = document(matrix)
            for node, fabric_node, version in (
                ("forge-1.20.1", "fabric-1.20.1", "0.17.3"),
                ("neoforge-1.21.1", "fabric-1.21.1", "0.17.0"),
                ("fabric-1.21.1", "fabric-1.21.1", "0.17.0"),
            ):
                with self.subTest(reordered=reordered, node=node):
                    context = source.gradle_context(artifact_node=node)
                    expected = source.inventory.lane(node)
                    self.assertEqual({"artifact_node": fabric_node,
                                      "coordinate": f"net.fabricmc:fabric-loader:{version}"},
                                     context["common_annotation_dependency"])
                    self.assertEqual([node], [lane["artifact"]["artifact_node"]
                                              for lane in context["lanes"]])
                    selected = context["lanes"][0]
                    self.assertEqual(expected.runtime, selected["runtime"])
                    self.assertEqual(expected.mod_version, selected["mod_version"])
                    self.assertEqual(expected.repository_family, selected["repository_family"])
                    context["common_annotation_dependency"]["coordinate"] = "mutated"
                    self.assertEqual(f"net.fabricmc:fabric-loader:{version}",
                                     source.gradle_context(artifact_node=node)
                                     ["common_annotation_dependency"]["coordinate"])

    def test_annotation_dependency_rejects_missing_same_era_fabric_configuration(self):
        for matrix in (arbitrary_named_1211_release_matrix(), schema2_configuration()):
            with self.subTest(schema=matrix["schema_version"]):
                for key in ("artifacts", "runtimes"):
                    matrix[key] = [row for row in matrix[key]
                                   if row["artifact_node"] != "fabric-1.21.1"]
                matrix["lane_count"] -= 1
                if matrix["schema_version"] == 1:
                    matrix["unit_test_lane"] = "neoforge-1.21.1"
                    del matrix["source_routing"]["fabric"]
                    del matrix["installers"]["fabric-1.1.0"]
                source = document(matrix)
                self.assertEqual("neoforge-1.21.1",
                                 source.inventory.lane("neoforge-1.21.1").identity.artifact_node)
                with self.assertRaisesRegex(MatrixError, "common annotations.*fabric-1.21.1"):
                    source.gradle_context(artifact_node="neoforge-1.21.1")
                if matrix["schema_version"] == 2:
                    self.assertEqual("fabric-1.20.1", source.gradle_context()
                                     ["common_annotation_dependency"]["artifact_node"])

    def test_selected_context_never_inherits_another_lane(self):
        for shared in (False, True):
            source = document(schema2_configuration(shared=shared))
            for expected in source.inventory.lanes:
                node = expected.identity.artifact_node
                context = source.gradle_context(artifact_node=node)
                self.assertEqual(("lane", node), (context["scope"], context["artifact_node"]))
                self.assertEqual(1, len(context["lanes"]))
                lane = context["lanes"][0]
                self.assertEqual(expected.artifact, lane["artifact"])
                self.assertEqual(expected.runtime, lane["runtime"])
                self.assertEqual(expected.mod_version, lane["mod_version"])
                self.assertEqual(expected.repository_family, lane["repository_family"])
                self.assertEqual(["common", expected.identity.loader], lane["source_routes"])
                self.assertEqual(expected.gradle_java, lane["gradle_java"])

    def test_missing_unknown_unresolved_and_incompatible_aggregate_fail(self):
        with self.assertRaisesRegex(MatrixError, "explicit"):
            document(schema2_configuration(shared=True)).gradle_context()
        source = document(schema2_configuration())
        for node in ("", "fabric-1.21.7", "forge-1.21.1", "neoforge-1.20.1"):
            with self.subTest(node=node), self.assertRaises(MatrixError):
                source.gradle_context(artifact_node=node)
        matrix = schema2_configuration()
        matrix["artifacts"][0]["mod_version"] = "9.8.7"
        with self.assertRaisesRegex(MatrixError, "legacy project version"):
            document(matrix).gradle_context()
        matrix = schema2_configuration()
        matrix["artifacts"][-1]["mod_version"] = "9.8.7"
        self.assertEqual("9.8.7", document(matrix).gradle_context(
            artifact_node="neoforge-1.21.1")["lanes"][0]["mod_version"])

    def test_cli_projection_and_selector_are_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            path.write_text(json.dumps(schema2_configuration()))
            args = ["--matrix", str(path), "--no-source-check", "--artifact-node", "neoforge-1.21.1"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, main(args + ["--kind", "gradle-context"]))
            self.assertEqual("neoforge", json.loads(output.getvalue())["lanes"][0]["repository_family"])
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, main(args))
                self.assertEqual(2, main(args + ["--kind", "inventory"]))


if __name__ == "__main__":
    unittest.main()
