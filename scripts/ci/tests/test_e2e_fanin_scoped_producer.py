"""Scoped producer round trips with synthetic payloads, without running the game."""

import copy
import hashlib
import json
import shutil
import unittest
from dataclasses import replace
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests import test_e2e_fanin_scope as scoped_fixture
from scripts.ci.tests.matrix_fixtures import TARGET_COUNT
from scripts.ci.tests.test_e2e_fanin import CONTRACT_PATH, _json


class ScopedAggregateProducerTests(unittest.TestCase):
    def setUp(self):
        self.inputs = scoped_fixture.ScopedLaneFanInTests()
        self.inputs.setUp()
        self.addCleanup(self.inputs.doCleanups)
        self.contract_path = self.inputs.root / "contract.json"
        shutil.copyfile(CONTRACT_PATH, self.contract_path)
        self.output = self.inputs.root / "aggregate"

    def prepare(self, scope="legacy", node=None, *, shared=False, projection="pr-anchors"):
        self.inputs.configure(scope, node, shared=shared)
        fixture = self.inputs.fixture
        self.identity = replace(fixture.identity, projection=projection)
        fixture.lanes = fanin.expected_lanes(self.inputs.matrix, fixture.contract, projection,
            scope=scope, artifact_node=node,
            artifact_prefix=fanin.run_artifact_prefix(self.identity.commit, self.identity.run_attempt))
        for lane in fixture.lanes:
            self.inputs.populate_lane(lane)
        self.digest = hashlib.sha256(fanin._json_bytes(self.inputs.manifest)).hexdigest()

    def create(self, **overrides):
        return fanin.create_aggregate(**{**dict(input_root=self.inputs.fixture.root, output=self.output,
            matrix_path=self.inputs.matrix_path, contract_path=self.contract_path, identity=self.identity,
            artifact_manifest=self.inputs.manifest, artifact_manifest_sha256=self.digest,
            scope=self.inputs.scope, artifact_node=self.inputs.node), **overrides})

    def assert_unpublished(self):
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob(".aggregate.building-*")))

    def test_round_trips_legacy_modern_scheduled_and_shared_full_coverage(self):
        for scope, node, shared, projection in (("legacy", None, False, "pr-anchors"),
                ("lane", "neoforge-1.21.1", False, "scheduled-anchors"), ("full", None, True, "pr-anchors")):
            with self.subTest(scope=scope):
                self.prepare(scope, node, shared=shared, projection=projection)
                receipt = self.create()
                external = {"expected_" + key: getattr(self.identity, key) for key in
                            ("repository", "source_branch", "commit", "tree", "run_id", "run_attempt")}
                observed = fanin.validate_aggregate(root=self.output, matrix_path=self.inputs.matrix_path,
                    contract_path=self.contract_path, projection=projection, scope=scope, artifact_node=node,
                    artifact_manifest=self.inputs.manifest, artifact_manifest_sha256=self.digest, **external)
                self.assertEqual(receipt, observed)
                coverage = {**self.inputs.artifact_scope, "projection": projection,
                            "scenarios": list(self.inputs.fixture.lanes[0].scenarios)}
                self.assertEqual(coverage, receipt["aggregate_scope"])
                self.assertEqual(not shared, coverage["partial"])
                self.assertEqual({"legacy": 2, "lane": 1, "full": TARGET_COUNT}[scope], len(receipt["lanes"]))
                for filename in ("summary.json", "resolved-matrix.json"):
                    payload = json.loads((self.output / filename).read_bytes())
                    self.assertEqual(coverage, payload["aggregate_scope"])
                    self.assertNotIn("execution_scope", payload)
                shutil.rmtree(self.output)
                shutil.rmtree(self.inputs.fixture.root)

    def test_source_and_external_scope_cannot_be_inferred_from_bundle(self):
        self.prepare()
        for overrides in ({"scope": None}, {"scope": "full"}, {"artifact_node": "forge-1.20.1"},
                          {"artifact_manifest_sha256": True}, {"artifact_manifest": None},
                          {"identity": replace(self.identity, commit="5" * 40)},
                          {"identity": replace(self.identity, tree="6" * 40)}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError): self.create(**overrides)
            self.assert_unpublished()

    def test_missing_lane_unknown_source_and_cross_lane_coverage_never_publish(self):
        self.prepare()
        lane_root = self.inputs.fixture.root / self.inputs.fixture.lanes[-1].artifact_name
        held = lane_root.with_name("held-back")
        lane_root.rename(held)
        with self.assertRaises(fanin.FanInError): self.create()
        self.assert_unpublished()
        held.rename(lane_root)
        unknown = self.inputs.fixture.root / "unknown.txt"
        unknown.write_bytes(b"unrelated")
        with self.assertRaises(fanin.FanInError): self.create()
        self.assert_unpublished()
        unknown.unlink()
        for filename in ("summary.json", "resolved-matrix.json"):
            path = lane_root / filename
            baseline = json.loads(path.read_bytes())
            for key, value in (("kind", "full"), ("selected_nodes", ["fabric-1.20.1"]),
                               ("partial", 1), ("scenarios", [])):
                changed = copy.deepcopy(baseline)
                changed["execution_scope"][key] = value
                _json(path, changed)
                with self.subTest(file=filename, key=key), self.assertRaises(fanin.FanInError): self.create()
                self.assert_unpublished()
            _json(path, baseline)

    def test_matrix_contract_or_source_drift_after_final_validation_aborts_publication(self):
        self.prepare()
        original = fanin.validate_aggregate
        for path in (self.inputs.matrix_path, self.contract_path, self.inputs.fixture.root / "appeared.txt"):
            existed = path.exists()
            before = path.read_bytes() if existed else b""
            def drift(**kwargs):
                result = original(**kwargs)
                path.write_bytes(before + b"\n")
                return result
            with self.subTest(path=path.name), patch.object(fanin, "validate_aggregate", side_effect=drift):
                with self.assertRaisesRegex(fanin.FanInError, "changed"): self.create()
            self.assert_unpublished()
            if existed: path.write_bytes(before)
            else: path.unlink()

    def test_mixed_input_during_copy_and_failed_write_leave_no_partial_aggregate(self):
        self.prepare()
        original = fanin._write_new
        before = self.inputs.matrix_path.read_bytes()
        def drift(*args):
            original(*args)
            self.inputs.matrix_path.write_bytes(before + b"\n")
        with patch.object(fanin, "_write_new", side_effect=drift), self.assertRaises(fanin.FanInError): self.create()
        self.assert_unpublished()
        self.inputs.matrix_path.write_bytes(before)
        def failed(*args):
            original(*args)
            raise OSError("simulated failed write")
        with patch.object(fanin, "_write_new", side_effect=failed), self.assertRaises(OSError): self.create()
        self.assert_unpublished()

    def test_existing_aggregate_is_preserved_exactly_on_retry(self):
        self.prepare()
        self.create()
        before = {path.relative_to(self.output): path.read_bytes() for path in self.output.rglob("*") if path.is_file()}
        with self.assertRaisesRegex(fanin.FanInError, "refusing to replace"): self.create()
        after = {path.relative_to(self.output): path.read_bytes() for path in self.output.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        self.assertEqual([], list(self.output.parent.glob(".aggregate.building-*")))


if __name__ == "__main__":
    unittest.main()
