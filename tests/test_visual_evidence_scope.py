"""Synthetic pixels exercise scoped consumption; no game execution or qualification."""

import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from e2e import visual_evidence as visual
from e2e.scenario_contract import load_contract
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.matrix import MatrixDocument, normalize_matrix_inventory
from tests import test_visual_capsule as fixture
from tests.matrix_fixtures import SCHEMA1_MATRIX_PATH


class ScopedVisualEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.matrix = schema2_configuration()
        cls.contract = load_contract(fixture.CONTRACT_PATH)
        cls.nodes = sorted(row["artifact_node"] for row in cls.matrix["runtimes"])
        cls.base = cls.root / "pixels"
        with patch.object(fixture, "load_matrix", return_value=cls.matrix):
            fixture._build_evidence(cls.base, cls.nodes, metadata="synthetic scoped pixels")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.case = Path(self.temp.name)

    def bundle(self, scope="legacy", node=None, matrix=None):
        matrix = matrix or self.matrix
        document = MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))
        lanes = sorted(document.select_lanes(scope=scope, artifact_node=node),
                       key=lambda lane: (tuple(map(int, lane.identity.minecraft.split("."))), lane.identity.loader))
        return {"kind": scope, "selected_nodes": [lane.identity.artifact_node for lane in lanes],
                "target_nodes": list(document.inventory.target_nodes),
                "migration_mode": document.inventory.migration_mode,
                "partial": len(lanes) != len(document.inventory.targets)}

    def prepare(self, nodes, scope, *, artifact_scope=None, projection=None):
        root = self.case / "evidence"
        shutil.copytree(self.base, root)
        for profile in (root / "profiles").iterdir():
            if profile.name.partition("--")[0] not in nodes:
                shutil.rmtree(profile)
        provenance = {"artifact_id": 31, "artifact_nodes": sorted(nodes),
                      "scenarios": sorted(self.contract.scenarios_for_profile("release"))}
        kwargs = dict(scope=scope, artifact_node=nodes[0] if scope == "lane" else None,
                      artifact_scope=artifact_scope or self.bundle(scope, nodes[0] if scope == "lane" else None),
                      projection=projection)
        bundle = kwargs["artifact_scope"]
        coverage = ({"aggregate_scope": {**bundle, "projection": projection, "scenarios": provenance["scenarios"]}}
                    if projection else {"execution_scope": {"kind": scope, "selected_nodes": nodes,
                        "scenarios": provenance["scenarios"], "target_nodes": bundle["target_nodes"],
                        "partial": True, "artifact_scope": bundle}})
        for filename, key in (("summary.json", "results"), ("resolved-matrix.json", "rows")):
            path = root / filename
            data = json.loads(path.read_bytes())
            data[key] = [row for row in data[key] if row["artifact_node"] in nodes]
            fixture._write_json(path, {**data, **coverage})
        if projection:
            fixture._write_json(root / "aggregate.json", coverage)
        return root, provenance, kwargs

    def collect(self, root, provenance, kwargs):
        return visual.collect_evidence(root, matrix=self.matrix, contract=self.contract,
                                       provenance=provenance, **kwargs)

    def test_legacy_and_modern_lanes_validate_real_synthetic_pixels(self):
        for nodes, scope, bundle in ((["fabric-1.20.1", "forge-1.20.1"], "legacy", None),
                (["fabric-1.20.1"], "lane", self.bundle()),
                (["neoforge-1.21.1"], "lane", None)):
            with self.subTest(nodes=nodes):
                root, provenance, kwargs = self.prepare(nodes, scope, artifact_scope=bundle)
                frames = self.collect(root, provenance, kwargs)
                self.assertEqual(set(nodes), {frame.artifact_node for frame in frames})
                self.assertEqual(len(nodes) * len(self.contract.capture_ids), len(frames))
                shutil.rmtree(root)

    def test_aggregate_uses_external_projection_and_exact_bundle(self):
        root, provenance, kwargs = self.prepare(["fabric-1.20.1", "forge-1.20.1"], "legacy",
                                                projection="pr-anchors")
        self.assertTrue(self.collect(root, provenance, kwargs))
        for overrides in ({"projection": None}, {"projection": "scheduled-anchors"},
                          {"artifact_scope": self.bundle("lane", "fabric-1.20.1")}):
            with self.subTest(overrides=overrides), self.assertRaises(visual.VisualEvidenceError):
                self.collect(root, provenance, {**kwargs, **overrides})

    def test_missing_external_scope_unresolved_and_cross_lane_are_rejected(self):
        root, provenance, kwargs = self.prepare(["fabric-1.20.1"], "lane", artifact_scope=self.bundle())
        for overrides in ({"scope": None}, {"scope": "full", "artifact_node": None},
                {"artifact_node": "fabric-1.21.7"}, {"artifact_node": "forge-1.20.1"},
                {"artifact_scope": None}, {"artifact_scope": self.bundle("lane", "forge-1.20.1")}):
            with self.subTest(overrides=overrides), self.assertRaises(visual.VisualEvidenceError):
                self.collect(root, provenance, {**kwargs, **overrides})

    def test_partial_as_full_and_stale_bundle_coverage_are_rejected(self):
        root, provenance, kwargs = self.prepare(["fabric-1.20.1"], "lane", artifact_scope=self.bundle())
        for key, value in (("partial", False), ("partial", 1), ("kind", "full"),
                           ("selected_nodes", ["forge-1.20.1"]), ("target_nodes", ["fabric-1.20.1"])):
            with self.subTest(key=key, value=value):
                original = json.loads((root / "summary.json").read_bytes())
                changed = copy.deepcopy(original)
                changed["execution_scope"][key] = value
                fixture._write_json(root / "summary.json", changed)
                with self.assertRaises(visual.VisualEvidenceError): self.collect(root, provenance, kwargs)
                fixture._write_json(root / "summary.json", original)
        false_bundle = {**kwargs["artifact_scope"], "partial": False}
        with self.assertRaises(visual.VisualEvidenceError):
            self.collect(root, provenance, {**kwargs, "artifact_scope": false_bundle})

    def test_selected_inventory_and_render_assertions_remain_mandatory(self):
        root, provenance, kwargs = self.prepare(["fabric-1.20.1"], "lane")
        self.assertTrue(self.collect(root, provenance, kwargs))
        result_path = next((root / "profiles").glob("*/result.json"))
        result = json.loads(result_path.read_bytes())
        result["loader"] = "forge"
        fixture._write_json(result_path, result)
        with self.assertRaises(visual.VisualEvidenceError): self.collect(root, provenance, kwargs)
        result["loader"] = "fabric"
        fixture._write_json(result_path, result)
        next((root / "profiles").rglob("*.png")).write_bytes(b"not pixels")
        with self.assertRaises(visual.VisualEvidenceError): self.collect(root, provenance, kwargs)

    def test_root_output_versions_require_exact_integers(self):
        root, provenance, kwargs = self.prepare(["fabric-1.20.1"], "lane")
        for filename in ("resolved-matrix.json", "summary.json", "runtime-store.json"):
            path = root / filename
            original = json.loads(path.read_bytes())
            for version in (True, 1.0):
                with self.subTest(filename=filename, version=version):
                    fixture._write_json(path, {**original, "schema_version": version})
                    with self.assertRaisesRegex(visual.VisualEvidenceError, "schema_version"):
                        self.collect(root, provenance, kwargs)
            fixture._write_json(path, original)

    def test_shared_full_scope_and_preparing_full_are_distinct(self):
        matrix = schema2_configuration(shared=True)
        bundle = self.bundle("full", matrix=matrix)
        provenance = {"artifact_nodes": sorted(bundle["selected_nodes"]),
                      "scenarios": sorted(self.contract.scenarios_for_profile("release"))}
        rows, coverage = visual._visual_selection(matrix, provenance, self.contract, scope="full",
            artifact_node=None, artifact_scope=bundle, projection="pr-anchors")
        self.assertEqual(12, len(rows))
        self.assertIs(False, coverage["aggregate_scope"]["partial"])
        with self.assertRaises(visual.VisualEvidenceError):
            visual._visual_selection(self.matrix, provenance, self.contract, scope="full",
                artifact_node=None, artifact_scope=bundle, projection="pr-anchors")

    def test_archive_authentication_binds_exact_schema2_matrix_bytes(self):
        root, _, kwargs = self.prepare(["fabric-1.20.1"], "lane")
        legacy_path = fixture.write_matrix_fixture(self.case / "legacy", json.loads(SCHEMA1_MATRIX_PATH.read_bytes()))
        source = fixture._source(self.case, name="candidate", nodes=["fabric-1.20.1"],
            artifact_id=31, source_head_branch="feature/visual", base_branch=self.matrix["branch"]["name"],
            event="pull_request_target", metadata="synthetic", matrix_path=legacy_path)
        matrix_path = self.case / "release/release-matrix.json"
        fixture._write_json(matrix_path, self.matrix)
        for route in self.matrix["source_routing"].values():
            for key in ("canonical", "e2e"): (self.case / route[key]).mkdir(parents=True, exist_ok=True)
        digest = fixture._zip_tree(root, source[0])
        expectation = replace(source[2], artifact_sha256=digest)
        attestation = json.loads(source[1].read_bytes())
        attestation.update(artifact_sha256=digest, matrix_sha256=hashlib.sha256(matrix_path.read_bytes()).hexdigest())
        fixture._write_json(source[1], attestation)
        arguments = dict(archive=source[0], attestation_path=source[1], expectation=expectation,
            matrix_path=matrix_path, contract_path=fixture.CONTRACT_PATH, **kwargs)
        bundle = visual.load_archived_evidence(**arguments, extraction_destination=self.case / "extracted")
        self.assertEqual(2, bundle.matrix["schema_version"])
        self.assertEqual(attestation["matrix_sha256"], bundle.matrix_sha256)
        matrix_path.write_bytes(matrix_path.read_bytes() + b" ")
        with self.assertRaisesRegex(visual.VisualEvidenceError, "matrix hash"):
            visual.load_archived_evidence(**arguments, extraction_destination=self.case / "stale")
