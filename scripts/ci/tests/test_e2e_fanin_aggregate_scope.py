"""Scoped aggregate reader fixtures; real payload validation, no game or release qualification."""

import copy
import hashlib
import json
import shutil
import unittest
from dataclasses import replace
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests.test_e2e_fanin import CONTRACT_PATH, _json
from scripts.ci.tests import test_e2e_fanin_scope as scoped_fixture


class ScopedAggregateReaderTests(unittest.TestCase):
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
        self.lanes = fanin.expected_lanes(self.inputs.matrix, fixture.contract, projection,
            scope=scope, artifact_node=node,
            artifact_prefix=fanin.run_artifact_prefix(self.identity.commit, self.identity.run_attempt))
        self.output.mkdir()
        results, resolved, metrics = [], [], {}
        for lane in self.lanes:
            fixture.populate_lane(lane)
            source = fixture.root / lane.artifact_name
            shutil.copytree(source / "profiles", self.output / "profiles", dirs_exist_ok=True)
            summary = json.loads((source / "summary.json").read_bytes())
            results.extend(summary["results"])
            resolved.extend(json.loads((source / "resolved-matrix.json").read_bytes())["rows"])
            for key, value in summary["runtime_store"].items(): metrics[key] = metrics.get(key, 0) + value
        self.coverage = {**self.inputs.artifact_scope, "projection": projection,
                         "scenarios": list(self.lanes[0].scenarios)}
        _json(self.output / "summary.json", {"schema_version": 1, "contract_sha256": fixture.contract.sha256,
            "results": results, "runtime_store": metrics, "aggregate_scope": self.coverage})
        _json(self.output / "resolved-matrix.json", {"schema_version": 1, "rows": resolved, "aggregate_scope": self.coverage})
        _json(self.output / "runtime-store.json", {"schema_version": 1, "metrics": metrics})
        self.manifest_digest = hashlib.sha256(fanin._json_bytes(self.inputs.manifest)).hexdigest()
        self.receipt = {"schema_version": 1, "kind": "packaged-e2e-aggregate", "aggregate_scope": self.coverage,
            "provenance": {"repository": self.identity.repository, "source_branch": self.identity.source_branch,
                "commit": self.identity.commit, "tree": self.identity.tree, "run_id": self.identity.run_id,
                "run_attempt": self.identity.run_attempt, "workflow": ".github/workflows/on-demand-e2e.yml",
                "projection": projection, "matrix_branch": self.inputs.matrix["branch"]["name"],
                "matrix_sha256": hashlib.sha256(self.inputs.matrix_path.read_bytes()).hexdigest(),
                "contract_sha256": fixture.contract.sha256},
            "artifact_manifest": {"path": "build/release/artifacts.json", "schema_version": 3,
                "sha256": self.manifest_digest, "commit": self.identity.commit, "tree": self.identity.tree},
            "lanes": [{"artifact_name": lane.artifact_name, "job_id": lane.job_id, "artifact_node": lane.artifact_node,
                "minecraft": lane.minecraft, "loader": lane.loader, "java": lane.java, "scenarios": list(lane.scenarios),
                "production_jar_sha256": fixture.hashes[lane.artifact_node][0],
                "harness_jar_sha256": fixture.hashes[lane.artifact_node][1]} for lane in self.lanes]}
        self.refresh_receipt()

    def refresh_receipt(self):
        self.receipt["files"] = [{"path": path.relative_to(self.output).as_posix(),
            "size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in sorted(self.output.rglob("*")) if path.is_file() and path.name != fanin.AGGREGATE_RECEIPT]
        _json(self.output / fanin.AGGREGATE_RECEIPT, self.receipt)

    def validate(self, **overrides):
        arguments = dict(root=self.output, matrix_path=self.inputs.matrix_path, contract_path=self.contract_path,
            projection=self.identity.projection, scope=self.inputs.scope, artifact_node=self.inputs.node,
            artifact_manifest=self.inputs.manifest, artifact_manifest_sha256=self.manifest_digest,
            expected_hashes=self.inputs.fixture.hashes)
        arguments.update({"expected_" + key: getattr(self.identity, key)
                          for key in ("repository", "source_branch", "commit", "tree", "run_id", "run_attempt")})
        return fanin.validate_aggregate(**{**arguments, **overrides})

    def test_legacy_aggregate_binds_two_lanes_without_claiming_twelve_target_coverage(self):
        self.prepare()
        result = self.validate()
        self.assertEqual(self.receipt, result)
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"], result["aggregate_scope"]["selected_nodes"])
        self.assertEqual(12, len(result["aggregate_scope"]["target_nodes"]))
        self.assertTrue(result["aggregate_scope"]["partial"])
        self.assertNotIn("execution_scope", result)
        no_source = {"expected_" + key: None for key in
                     ("repository", "source_branch", "commit", "tree", "run_id", "run_attempt")}
        self.assertEqual(result, self.validate(**no_source))

    def test_modern_scheduled_lane_retains_its_scope_and_exact_scenario_profile(self):
        self.prepare("lane", "neoforge-1.21.1", projection="scheduled-anchors")
        result = self.validate()
        self.assertEqual(["neoforge-1.21.1"], result["aggregate_scope"]["selected_nodes"])
        self.assertEqual("scheduled-anchors", result["aggregate_scope"]["projection"])
        self.assertTrue(result["aggregate_scope"]["partial"])
        with self.assertRaises(fanin.FanInError): self.validate(projection="pr-anchors")

    def test_shared_full_aggregate_requires_every_lane(self):
        self.prepare("full", shared=True)
        result = self.validate()
        self.assertEqual(12, len(result["lanes"]))
        self.assertFalse(result["aggregate_scope"]["partial"])
        omitted = self.lanes[-1]
        for path in (self.output / "profiles").iterdir():
            if path.name.startswith(omitted.artifact_node + "--"): shutil.rmtree(path)
        self.receipt["lanes"].pop()
        self.refresh_receipt()
        with self.assertRaisesRegex(fanin.FanInError, "filesystem inventory"): self.validate()

    def test_external_scope_bundle_and_digest_cannot_be_inferred_from_receipt(self):
        self.prepare()
        for overrides in ({"scope": None}, {"scope": "full"}, {"scope": "lane", "artifact_node": "fabric-1.20.1"},
                          {"artifact_manifest": None}, {"artifact_manifest_sha256": None},
                          {"artifact_manifest_sha256": "f" * 64}, {"expected_hashes": {}}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError): self.validate(**overrides)

    def test_partial_as_full_and_payload_scope_aliases_fail_even_with_refreshed_inventory(self):
        self.prepare()
        original = copy.deepcopy(self.receipt)
        for target in ("aggregate.json", "summary.json", "resolved-matrix.json"):
            path = self.output / target
            baseline = json.loads(path.read_bytes())
            for key, value in (("kind", "full"), ("partial", False), ("partial", 1),
                               ("selected_nodes", ["fabric-1.20.1"]), ("scenarios", []),
                               ("projection", "scheduled-anchors")):
                payload = copy.deepcopy(baseline)
                payload["aggregate_scope"][key] = value
                if target == "aggregate.json": self.receipt = payload
                else: _json(path, payload)
                self.refresh_receipt()
                with self.subTest(target=target, key=key), self.assertRaises(fanin.FanInError): self.validate()
            _json(path, baseline)
            self.receipt = copy.deepcopy(original)
            self.refresh_receipt()

    def test_wrong_source_inputs_and_bundle_hashes_are_not_self_authenticated(self):
        self.prepare()
        for key, value in (("repository", "other/repo"), ("source_branch", "other"), ("commit", "3" * 40),
                           ("tree", "4" * 40), ("run_id", 999), ("run_attempt", 3), ("run_id", None)):
            with self.subTest(key=key), self.assertRaises(fanin.FanInError): self.validate(**{"expected_" + key: value})
        original = copy.deepcopy(self.inputs.manifest)
        for mutate in (lambda item: item["matrix"].update(sha256="0" * 64),
                       lambda item: item["scenario_contract"].update(sha256="0" * 64),
                       lambda item: item["artifacts"][0]["production"].update(sha256="f" * 64)):
            self.inputs.manifest = copy.deepcopy(original)
            mutate(self.inputs.manifest)
            with self.assertRaises(fanin.FanInError): self.validate(expected_hashes=None)

    def test_summary_and_runtime_schema_versions_reject_numeric_aliases(self):
        self.prepare()
        lane = self.inputs.fixture.lanes[0]
        self.inputs.populate_lane(lane)
        lane_root = self.inputs.fixture.root / lane.artifact_name
        for root, validate in ((self.output, self.validate), (lane_root, self.inputs.validate)):
            for filename in ("summary.json", "runtime-store.json"):
                path = root / filename
                baseline = json.loads(path.read_bytes())
                for value in (True, 1.0):
                    _json(path, {**baseline, "schema_version": value})
                    self.refresh_receipt()
                    with self.subTest(root=root.name, file=filename, value=value), self.assertRaises(fanin.FanInError):
                        validate()
                _json(path, baseline)
                self.refresh_receipt()

    def test_input_drift_and_pixel_tampering_still_fail(self):
        self.prepare()
        original = fanin._validate_result
        for path in (self.inputs.matrix_path, self.contract_path):
            before = path.read_bytes()
            def change(*args, **kwargs):
                original(*args, **kwargs)
                path.write_bytes(path.read_bytes() + b"\n")
            with self.subTest(path=path), patch.object(fanin, "_validate_result", side_effect=change), self.assertRaises(fanin.FanInError):
                self.validate()
            path.write_bytes(before)
        screenshot = next(self.output.rglob("*.png"))
        screenshot.write_bytes(b"not a PNG")
        self.refresh_receipt()
        with self.assertRaises(fanin.FanInError): self.validate()


if __name__ == "__main__":
    unittest.main()
