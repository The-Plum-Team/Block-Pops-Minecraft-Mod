"""Scoped raw-reader fixtures use real synthetic pixels, never game qualification."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.pages import evidence
from tests import test_visual_capsule as pixels


class ScopedRawPagesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.shared.cleanup)
        cls.base = Path(cls.shared.name)
        cls.full = schema2_configuration(shared=True)
        cls.contract = evidence.default_contract()
        with patch.object(pixels, "load_matrix", return_value=cls.full):
            pixels._build_evidence(cls.base, [row["artifact_node"] for row in cls.full["runtimes"]],
                                   metadata="synthetic raw coverage")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def prepare(self, scope="legacy", node=None, *, shared=False, projection="pr-anchors"):
        matrix = schema2_configuration(shared=shared)
        self.matrix_path = self.root / "matrix.json"
        self.matrix_path.write_text(json.dumps(matrix))
        nodes = ([node] if scope == "lane" else matrix["migration"]["legacy_nodes"] if scope == "legacy"
                 else [row["artifact_node"] for row in matrix["artifacts"]])
        scenarios = list(self.contract.scenarios_for_profile("release"))
        target_nodes = [row["artifact_node"] for row in matrix["targets"]]
        coverage = {"kind": scope, "selected_nodes": nodes, "target_nodes": target_nodes,
                    "migration_mode": matrix["migration"]["mode"], "partial": set(nodes) != set(target_nodes),
                    "projection": projection, "scenarios": scenarios}
        self.raw = self.root / "raw"
        self.raw.mkdir()
        lanes, frames, records = [], [], []
        for row in matrix["runtimes"]:
            if row["artifact_node"] not in nodes: continue
            for scenario in scenarios:
                lane, collected, files = evidence._collect_lane(self.base, row, scenario, self.contract)
                lanes.append(lane); frames.extend(collected)
                for relative, data in files:
                    path = self.raw / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                    records.append({"path": relative, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
        branch = matrix["branch"]["name"]
        handoff = {"path": evidence.E2E_WORKFLOW, "run_id": 101, "run_attempt": 2,
                   "controller_branch": "master", "controller_sha": "a" * 40}
        self.expected = {"repository": "AkaNebur/BlockPops", "branch": branch, "commit": "b" * 40,
            "tree": "c" * 40, "matrix_sha256": hashlib.sha256(self.matrix_path.read_bytes()).hexdigest(),
            "contract_sha256": self.contract.sha256, "handoff": handoff}
        self.manifest = {"schema_version": 2, "kind": evidence.RAW_KIND,
            "provenance": {**self.expected, "packaged": {**handoff, "branch": branch, "commit": "b" * 40, "tree": "c" * 40}},
            "aggregate_scope": coverage, "lanes": lanes, "frames": frames, "files": records}
        self.arguments = dict(scope=scope, artifact_node=node, projection=projection)
        self.write(self.manifest)

    def write(self, value):
        (self.raw / "pages-evidence.json").write_text(json.dumps(value))

    def validate(self, **overrides):
        return evidence.validate_raw(self.raw, matrix_path=self.matrix_path, expected=self.expected,
                                     **{**self.arguments, **overrides})

    def test_legacy_modern_lane_and_full_shared_reconstruct_exact_pixels(self):
        cases = (("legacy", None, False, "pr-anchors", 2),
                 ("lane", "neoforge-1.21.1", False, "scheduled-anchors", 1),
                 ("full", None, True, "pr-anchors", 12))
        for index, (scope, node, shared, projection, count) in enumerate(cases):
            with self.subTest(scope=scope):
                self.root = Path(self.temporary.name) / str(index)
                self.root.mkdir()
                self.prepare(scope, node, shared=shared, projection=projection)
                result = self.validate()
                self.assertEqual(count, len(result["lanes"]))
                self.assertEqual(count * len(self.contract.capture_ids), len(result["frames"]))
                self.assertEqual(count != 12, result["aggregate_scope"]["partial"])
                self.assertEqual(12, len(result["aggregate_scope"]["target_nodes"]))

    def test_external_scope_projection_and_resolved_selection_are_required(self):
        self.prepare()
        for overrides in ({"scope": None}, {"projection": None}, {"scope": "full"},
                          {"scope": "lane", "artifact_node": "forge-1.20.1"},
                          {"scope": "lane", "artifact_node": "fabric-1.21.7"},
                          {"projection": "scheduled-anchors"}, {"artifact_node": "fabric-1.20.1"}):
            with self.subTest(overrides=overrides), self.assertRaises(evidence.EvidenceError): self.validate(**overrides)

    def test_partial_cannot_be_presented_as_full_or_coerced_coverage(self):
        self.prepare()
        for key, value in (("partial", False), ("partial", 1), ("selected_nodes", ["fabric-1.20.1"]),
                           ("target_nodes", ["fabric-1.20.1", "forge-1.20.1"]), ("kind", "full"),
                           ("migration_mode", "shared"), ("scenarios", []), ("projection", "scheduled-anchors")):
            changed = copy.deepcopy(self.manifest)
            changed["aggregate_scope"][key] = value
            self.write(changed)
            with self.subTest(key=key, value=value), self.assertRaises(evidence.EvidenceError): self.validate()
        for version in (1, True, 2.0):
            self.write({**self.manifest, "schema_version": version})
            with self.subTest(version=version), self.assertRaises(evidence.EvidenceError): self.validate()

    def test_lane_inventory_frames_and_file_hashes_stay_strict(self):
        self.prepare()
        mutations = (lambda m: m["lanes"].pop(), lambda m: m["lanes"].append(copy.deepcopy(m["lanes"][0])),
                     lambda m: m["lanes"][0].update(java=17.0), lambda m: m["frames"].pop(),
                     lambda m: m["files"][0].update(sha256="f" * 64),
                     lambda m: m["frames"][0].update(artifact_node="neoforge-1.21.1"))
        for mutate in mutations:
            changed = copy.deepcopy(self.manifest); mutate(changed); self.write(changed)
            with self.assertRaises(evidence.EvidenceError): self.validate()

    def test_authenticated_identity_and_exact_matrix_bytes_remain_bound(self):
        self.prepare()
        for key in ("commit", "tree", "matrix_sha256", "contract_sha256"):
            changed = copy.deepcopy(self.manifest)
            changed["provenance"][key] = "d" * len(changed["provenance"][key])
            self.write(changed)
            with self.subTest(key=key), self.assertRaises(evidence.EvidenceError): self.validate()
        self.write(self.manifest)
        with patch.object(evidence, "_json", wraps=evidence._json) as read: self.validate()
        self.assertEqual(1, sum(call.args[0] == self.matrix_path for call in read.call_args_list))
        self.matrix_path.write_bytes(self.matrix_path.read_bytes() + b" ")
        with self.assertRaises(evidence.EvidenceError): self.validate()

    def test_protected_pixel_reconstruction_is_not_replaced_by_manifest_claims(self):
        self.prepare("lane", "fabric-1.20.1")
        result_record = next(row for row in self.manifest["files"] if row["path"].endswith("result.json"))
        path = self.raw / result_record["path"]
        result = json.loads(path.read_bytes())
        report = next(iter(result["reports"].values()))
        metrics = next(iter(report["pixel_validation"]["screenshots"].values()))
        metrics["meaningful_colors"] += 1
        path.write_text(json.dumps(result))
        result_record.update(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), size=path.stat().st_size)
        self.write(self.manifest)
        with self.assertRaisesRegex(evidence.EvidenceError, "protected pixel validation disagrees"): self.validate()

    def test_existing_unadapted_consumers_remain_closed(self):
        self.prepare()
        with self.assertRaisesRegex(evidence.EvidenceError, "external scope and projection"):
            evidence.validate_raw(self.raw, matrix_path=self.matrix_path, expected=self.expected)
        with self.assertRaises(evidence.EvidenceError):
            evidence.compact(input_root=self.raw, output=self.root / "compact", matrix_path=self.matrix_path,
                expected=self.expected, source_artifact_id=123, source_artifact_name="irrelevant",
                source_artifact_digest="sha256:" + "d" * 64)
        self.assertFalse((self.root / "compact").exists())
