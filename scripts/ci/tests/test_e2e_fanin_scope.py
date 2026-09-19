"""Scoped lane validation against synthetic, already-verified artifact records."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.ci.tests.test_e2e_fanin import Fixture, CONTRACT_PATH, COMMIT, TREE
from scripts.release.artifact_manifest import lane_build_identity
from scripts.release.matrix import MatrixDocument, normalize_matrix_inventory


class ScopedLaneFanInTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixture = Fixture(self.root / "lanes")
        self.matrix_path = self.root / "matrix.json"
        self.configure("legacy")

    def configure(self, scope, node=None, *, shared=False):
        self.matrix = schema2_configuration(shared=shared)
        self.matrix_path.write_text(json.dumps(self.matrix))
        self.document = MatrixDocument(normalize_matrix_inventory(self.matrix), json.dumps(self.matrix))
        self.scope, self.node = scope, node
        self.fixture.lanes = fanin.expected_lanes(self.matrix, self.fixture.contract, "pr-anchors",
                                                scope=scope, artifact_node=node)
        self.fixture.hashes = {lane.artifact_node: tuple(
            hashlib.sha256((lane.artifact_node + kind).encode()).hexdigest()
            for kind in ("production", "harness")) for lane in self.fixture.lanes}
        nodes = [lane.artifact_node for lane in self.fixture.lanes]
        self.artifact_scope = {"kind": scope, "selected_nodes": nodes,
            "target_nodes": list(self.document.inventory.target_nodes), "migration_mode": self.matrix["migration"]["mode"],
            "partial": set(nodes) != set(self.document.inventory.target_nodes)}
        digest = hashlib.sha256(self.matrix_path.read_bytes()).hexdigest()
        rows = []
        for node in nodes:
            lane = self.document.inventory.lane(node)
            row = {key: lane.artifact[key] for key in ("artifact_node", "minecraft", "loader", "java")}
            row.update(mod_version=lane.mod_version, gradle_java=lane.gradle_java,
                build_identity=lane_build_identity(self.document, node, matrix_digest=digest,
                    contract_digest=self.fixture.contract.sha256, commit=COMMIT, tree=TREE),
                production={"sha256": self.fixture.hashes[node][0]}, harness={"sha256": self.fixture.hashes[node][1]})
            rows.append(row)
        self.manifest = {"schema_version": 3, "scope": self.artifact_scope, "artifacts": rows,
            "matrix": {"path": "release/release-matrix.json", "sha256": digest},
            "scenario_contract": {"path": "e2e/scenario-contract.json", "sha256": self.fixture.contract.sha256},
            "git_commit": COMMIT, "git_tree": TREE, "release_branch": self.document.branch_name}

    def populate_lane(self, lane):
        self.fixture.populate_lane(lane)
        coverage = {"kind": "lane", "selected_nodes": [lane.artifact_node], "scenarios": list(lane.scenarios),
                    "target_nodes": list(self.document.inventory.target_nodes), "partial": True,
                    "artifact_scope": self.artifact_scope}
        for filename in ("summary.json", "resolved-matrix.json"):
            path = self.fixture.root / lane.artifact_name / filename
            payload = json.loads(path.read_bytes())
            payload["execution_scope"] = coverage
            path.write_text(json.dumps(payload))
        return coverage

    def validate(self, lane=None, **overrides):
        lane = lane or self.fixture.lanes[0]
        row = next(item for item in self.document.projection("pr-anchors", scope=self.scope,
            artifact_node=self.node)["include"] if item["artifact_node"] == lane.artifact_node)
        return fanin.validate_lane(**{**dict(root=self.fixture.root / lane.artifact_name, matrix_path=self.matrix_path,
            contract_path=CONTRACT_PATH, projection="pr-anchors", row=row, artifact_manifest=self.manifest,
            scope=self.scope, artifact_node=self.node), **overrides})

    def test_legacy_bundle_validates_each_exact_lane_with_partial_execution_coverage(self):
        for lane in self.fixture.lanes:
            expected = self.populate_lane(lane)
            result = self.validate(lane)
            self.assertEqual(expected, result["execution_scope"])
            self.assertEqual(len(lane.scenarios), result["scenario_count"])

    def test_modern_lane_and_full_bundle_have_distinct_external_scopes(self):
        self.configure("lane", "neoforge-1.21.1")
        lane = self.fixture.lanes[0]
        self.populate_lane(lane)
        self.assertEqual("neoforge-1.21.1", self.validate()["artifact_node"])
        self.configure("full", shared=True)
        self.assertEqual(12, len(self.fixture.lanes))
        lane = self.fixture.lanes[-1]
        self.populate_lane(lane)
        result = self.validate(lane)
        self.assertFalse(result["execution_scope"]["artifact_scope"]["partial"])
        self.assertTrue(result["execution_scope"]["partial"])

    def test_scope_and_manifest_identity_mutations_fail_before_payload_validation(self):
        baseline = copy.deepcopy(self.manifest)
        for mutate in (
            lambda value: value["scope"].update(kind="full"),
            lambda value: value["scope"].update(partial=1),
            lambda value: value["matrix"].update(sha256="0" * 64),
            lambda value: value["scenario_contract"].update(sha256="0" * 64),
            lambda value: value["artifacts"].pop(),
            lambda value: value["artifacts"].reverse(),
            lambda value: value["artifacts"][0].update(java=True),
            lambda value: value["artifacts"][0]["build_identity"].update(loader="neoforge"),
        ):
            self.manifest = copy.deepcopy(baseline)
            mutate(self.manifest)
            with patch.object(fanin, "_validate_lane_payload") as payload, self.assertRaises(fanin.FanInError):
                self.validate()
            payload.assert_not_called()
        self.manifest = baseline
        with self.assertRaises(fanin.FanInError):
            fanin.expected_lanes(self.matrix, self.fixture.contract, "pr-anchors")

    def test_execution_payload_cannot_claim_other_nodes_scenarios_or_bundle_scope(self):
        lane = self.fixture.lanes[0]
        self.populate_lane(lane)
        path = self.fixture.root / lane.artifact_name / "summary.json"
        baseline = json.loads(path.read_bytes())
        for key, value in (("selected_nodes", ["forge-1.20.1"]), ("scenarios", []),
                           ("partial", 1), ("artifact_scope", {**self.artifact_scope, "kind": "full"})):
            payload = copy.deepcopy(baseline)
            payload["execution_scope"][key] = value
            path.write_text(json.dumps(payload))
            with self.subTest(key=key), self.assertRaises(fanin.FanInError): self.validate()

    def test_projection_and_exact_row_types_cannot_be_overridden(self):
        with self.assertRaises(fanin.FanInError): self.validate(projection="runtime")
        with self.assertRaises(fanin.FanInError):
            fanin.expected_lanes(self.matrix, self.fixture.contract, "runtime", scope="legacy")
        row = self.document.projection("pr-anchors", scope="legacy")["include"][0]
        for key, value in (("java", 17.0), ("pr_anchor", 1), ("scheduled_anchor", 1)):
            with self.subTest(key=key), self.assertRaises(fanin.FanInError):
                self.validate(row={**row, key: value})

    def test_input_change_during_payload_validation_invalidates_the_result(self):
        lane = self.fixture.lanes[0]
        self.populate_lane(lane)
        validate = fanin._validate_lane_payload
        def changed(*args, **kwargs):
            result = validate(*args, **kwargs)
            self.matrix_path.write_bytes(self.matrix_path.read_bytes() + b"\n")
            return result
        with patch.object(fanin, "_validate_lane_payload", side_effect=changed), self.assertRaisesRegex(
                fanin.FanInError, "changed during validation"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
