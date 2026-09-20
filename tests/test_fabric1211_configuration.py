"""The first modern lane is explicitly selected and never replaces legacy dispatch."""

import unittest
from pathlib import Path

from scripts.release.build_matrix import plan_build
from scripts.release.matrix import MatrixError, load_matrix_document


class Fabric1211ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.path = Path(__file__).resolve().parents[1] / "release/release-matrix.json"
        self.document = load_matrix_document(self.path)

    def test_selected_modern_context_is_detached_and_keeps_its_own_version(self):
        context = self.document.gradle_context(artifact_node="fabric-1.21.1")
        self.assertEqual("lane", context["scope"])
        self.assertEqual(1, len(context["lanes"]))
        lane = context["lanes"][0]
        self.assertEqual(("stonecutter", "fabric", 21, 21),
                         (lane["build_layout"], lane["repository_family"],
                          lane["gradle_java"], lane["artifact"]["java"]))
        self.assertEqual("1.1.2", lane["mod_version"])
        self.assertEqual(["common", "fabric"], lane["source_routes"])
        self.assertEqual("net.fabricmc:fabric-loader:0.17.2",
                         context["common_annotation_dependency"]["coordinate"])
        plan = plan_build(self.path, artifact_node="fabric-1.21.1")
        self.assertEqual(["fabric-1.21.1"], plan["selected_nodes"])
        self.assertTrue(plan["partial_scope"])
        self.assertEqual(18, len(plan["target_nodes"]))
        self.assertEqual(["validateReleaseMatrix", ":fabric:1.21.1:remapJar",
                          ":fabric:1.21.1:remapE2EHarnessJar", "check"], plan["lanes"][0]["command"][-4:])
        self.assertTrue(plan["lanes"][0]["outputs"]["production"].endswith(
            "fabric/versions/1.21.1/build/libs/BlockPops - Fabric - 1.21.1-1.1.2.jar"))

    def test_preparing_keeps_legacy_default_and_denies_unconfigured_nodes(self):
        if self.document.inventory.migration_mode == "preparing":
            self.assertEqual({"fabric-1.20.1", "forge-1.20.1"},
                             {lane.identity.artifact_node for lane in self.document.select_lanes()})
        missing = set(self.document.inventory.target_nodes) - set(self.document.inventory.configured_nodes)
        for node in missing:
            with self.subTest(node=node), self.assertRaisesRegex(MatrixError, "unresolved"):
                plan_build(self.path, artifact_node=node)
        if missing:
            with self.assertRaisesRegex(MatrixError, "unresolved"):
                plan_build(self.path)
