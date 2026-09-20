"""Configured-lane selection never hides unresolved targets or claims qualification."""

import dataclasses
import json
import tempfile
import unittest
from pathlib import Path

from scripts.release.build_matrix import plan_build
from scripts.release.matrix import MatrixError, load_matrix_document

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "release/release-matrix.json"


class ConfiguredLaneScopeTests(unittest.TestCase):
    def setUp(self):
        self.document = load_matrix_document(MATRIX)
        self.inventory = self.document.inventory

    def nodes(self, **options):
        return [lane.identity.artifact_node for lane in self.document.select_lanes(**options)]

    def test_configured_scope_selects_every_configured_lane_in_matrix_order(self):
        # Which lanes are configured moves as the migration advances, so this binds the
        # scope's own contract rather than a snapshot of the list: matrix order, the
        # legacy pair always present, and never a lane outside the declared targets.
        self.assertEqual(list(self.inventory.configured_nodes), self.nodes(scope="configured"))
        configured = set(self.nodes(scope="configured"))
        self.assertTrue({"fabric-1.20.1", "forge-1.20.1"} <= configured)
        self.assertTrue(configured <= set(self.inventory.target_nodes))

    def test_configured_scope_excludes_unresolved_targets_and_never_implies_completion(self):
        unresolved = set(self.inventory.target_nodes) - set(self.inventory.configured_nodes)
        self.assertTrue(unresolved, "this test is only meaningful while targets remain unresolved")
        self.assertFalse(unresolved & set(self.nodes(scope="configured")))
        with self.assertRaisesRegex(MatrixError, "unresolved targets"):
            self.document.select_lanes(scope="full")

    def test_preparing_keeps_its_legacy_default_and_rejects_unknown_scopes(self):
        if self.inventory.migration_mode == "preparing":
            self.assertEqual("legacy", self.document.default_scope)
            self.assertEqual({"fabric-1.20.1", "forge-1.20.1"}, set(self.nodes()))
        with self.assertRaisesRegex(MatrixError, "unsupported matrix scope"):
            self.document.select_lanes(scope="everything")

    def test_configured_scope_fails_closed_on_empty_or_undeclared_lanes(self):
        empty = dataclasses.replace(self.inventory, lanes=())
        with self.assertRaisesRegex(MatrixError, "no configured lane"):
            empty.require_configured()
        undeclared = dataclasses.replace(self.inventory, targets=self.inventory.targets[:1])
        with self.assertRaisesRegex(MatrixError, "not declared targets"):
            undeclared.require_configured()

    def test_configured_scope_rejects_an_artifact_node_argument(self):
        with self.assertRaisesRegex(MatrixError, "artifact_node is only valid with lane scope"):
            self.document.select_lanes(scope="configured", artifact_node="fabric-1.21.1")


class NeoForge1211ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.document = load_matrix_document(MATRIX)

    def test_selected_neoforge_context_is_detached_and_keeps_its_own_version(self):
        context = self.document.gradle_context(artifact_node="neoforge-1.21.1")
        self.assertEqual("lane", context["scope"])
        self.assertEqual(1, len(context["lanes"]))
        lane = context["lanes"][0]
        self.assertEqual(("stonecutter", "neoforge", 21, 21),
                         (lane["build_layout"], lane["repository_family"],
                          lane["gradle_java"], lane["artifact"]["java"]))
        self.assertEqual(["common", "neoforge"], lane["source_routes"])

    def test_neoforge_plan_targets_its_own_nodes_and_stays_partial(self):
        plan = plan_build(MATRIX, artifact_node="neoforge-1.21.1")
        self.assertEqual(["neoforge-1.21.1"], plan["selected_nodes"])
        self.assertTrue(plan["partial_scope"])
        self.assertEqual(18, len(plan["target_nodes"]))
        self.assertEqual([":neoforge:1.21.1:remapJar", ":neoforge:1.21.1:remapE2EHarnessJar"],
                         plan["lanes"][0]["command"][-3:-1])
        outputs = plan["lanes"][0]["outputs"]
        self.assertTrue(outputs["production"].endswith(
            "neoforge/versions/1.21.1/build/libs/BlockPops - NeoForge - 1.21.1-1.1.2.jar"))
        self.assertNotEqual(outputs["production"], outputs["harness"])

    def test_a_modern_lane_can_never_join_the_legacy_aggregate(self):
        """The aggregate context stays legacy-only, so no Stonecutter lane leaks into it."""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "release-matrix.json"
            data = json.loads(MATRIX.read_bytes())
            data["migration"]["legacy_nodes"] = ["forge-1.20.1", "neoforge-1.21.1"]
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(MatrixError, "must retain both 1.20.1 legacy nodes"):
                load_matrix_document(path, validate_sources=False)
        # The unmutated document keeps the aggregate on the two legacy lanes only.
        context = self.document.gradle_context()
        self.assertEqual({"fabric-1.20.1", "forge-1.20.1"},
                         {lane["artifact"]["artifact_node"] for lane in context["lanes"]})
        self.assertTrue(all(lane["build_layout"] == "legacy" for lane in context["lanes"]))


if __name__ == "__main__":
    unittest.main()
