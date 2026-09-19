"""Synthetic canonical-anchor reader compatibility; no schema2 producer authority."""

import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.pages import visual_anchor as anchor
from tests import test_visual_anchor as legacy


class VisualAnchorSchema2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = legacy.VisualAnchorTests()
        cls.fixture.setUp()
        cls.addClassCleanup(cls.fixture.tearDown)
        cls.fixture.create()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.matrix_path = self.root / "matrix.json"
        self.bundle = self.root / "anchor"
        shutil.copytree(self.fixture.anchor, self.bundle)
        self.matrix = schema2_configuration()
        self.matrix["branch"] = copy.deepcopy(self.fixture.matrix["branch"])
        self.write_matrix()
        self.rebind_synthetic_anchor()

    def write_matrix(self):
        self.matrix_path.write_text(json.dumps(self.matrix, indent=2) + "\n")

    def rebind_synthetic_anchor(self):
        # Model an externally authenticated schema2 matrix while exercising the
        # reader independently of the opt-in scoped producer.
        path = self.bundle / anchor.ANCHOR_MANIFEST
        manifest = json.loads(path.read_bytes())
        self.digest = hashlib.sha256(self.matrix_path.read_bytes()).hexdigest()
        manifest["provenance"]["matrix"]["sha256"] = self.digest
        path.write_text(json.dumps(manifest))
        self.expected = {**self.fixture.expected(), "matrix_sha256": self.digest}

    def identity(self, **overrides):
        return anchor.anchor_identity(self.matrix_path, **{**dict(branch=self.fixture.branch,
            commit=legacy.COMMIT, run_id=101, run_attempt=2), **overrides})

    def validate(self):
        return anchor.validate_anchor(self.bundle, matrix_path=self.matrix_path, expected=self.expected)

    def test_preparing_and_shared_select_only_the_unchanged_canonical_lane(self):
        for shared in (False, True):
            with self.subTest(shared=shared):
                self.matrix = schema2_configuration(shared=shared)
                self.matrix["branch"] = copy.deepcopy(self.fixture.matrix["branch"])
                self.write_matrix()
                self.rebind_synthetic_anchor()
                identity = self.identity()
                self.assertTrue(identity["eligible"])
                self.assertEqual("fabric-1.20.1", identity["artifact_node"])
                manifest = self.validate()
                self.assertEqual({"fabric-1.20.1"}, {row["artifact_node"] for row in manifest["lanes"]})
                self.assertEqual({"fabric-1.20.1"}, {row["artifact_node"] for row in manifest["frames"]})
                self.assertNotIn("scope", manifest)
                self.assertEqual(self.fixture.create(self.root / f"legacy-{shared}")["reference"], manifest["reference"])

    def test_noncanonical_branch_and_matrix_branch_mismatch_remain_ineligible(self):
        with self.assertRaisesRegex(anchor.VisualAnchorError, "branch differs"):
            self.identity(branch="release/other")
        self.matrix["branch"] = {"role": "release", "name": "release/other", "canonical": "master",
                                 "sync": {"enabled": True, "source": "master"}}
        self.write_matrix()
        identity = self.identity(branch="release/other")
        self.assertFalse(identity["eligible"])
        self.assertEqual("", identity["artifact"])

    def test_matrix_reference_identity_and_schema_cannot_be_relaxed(self):
        original = copy.deepcopy(self.matrix)
        for mutate in (lambda m: m["visual_reference"].update(artifact_node="neoforge-1.21.1"),
                       lambda m: m["runtimes"][0].update(loader="forge"),
                       lambda m: m["runtimes"].pop(0),
                       lambda m: m.update(schema_version=True),
                       lambda m: m.update(schema_version=2.0)):
            self.matrix = copy.deepcopy(original)
            mutate(self.matrix)
            self.write_matrix()
            with self.assertRaises(anchor.VisualAnchorError): self.identity()

    def test_partial_scenario_or_extra_lane_cannot_become_a_canonical_anchor(self):
        path = self.bundle / anchor.ANCHOR_MANIFEST
        original = json.loads(path.read_bytes())
        mutations = (lambda m: m["frames"].pop(), lambda m: m["lanes"].clear(),
                     lambda m: m["lanes"].append({**m["lanes"][0], "artifact_node": "forge-1.20.1"}))
        for mutate in mutations:
            manifest = copy.deepcopy(original)
            mutate(manifest)
            path.write_text(json.dumps(manifest))
            with self.assertRaises(anchor.VisualAnchorError): self.validate()

    def test_matrix_bytes_are_read_once_and_raw_digest_remains_mandatory(self):
        with patch.object(anchor, "_json", wraps=anchor._json) as read:
            self.validate()
        self.assertEqual(1, sum(call.args[0] == self.matrix_path for call in read.call_args_list))
        self.matrix_path.write_bytes(self.matrix_path.read_bytes() + b" ")
        with self.assertRaisesRegex(anchor.VisualAnchorError, "matrix/contract identity is stale"):
            self.validate()

    def test_malformed_or_linked_matrix_is_rejected(self):
        raw = self.matrix_path.read_bytes()
        self.matrix_path.write_bytes(raw.replace(b'"schema_version": 2', b'"schema_version": 2, "schema_version": 2'))
        with self.assertRaises(anchor.VisualAnchorError): self.identity()
        real = self.matrix_path.with_suffix(".real")
        real.write_bytes(raw)
        self.matrix_path.unlink()
        self.matrix_path.symlink_to(real)
        with self.assertRaises(anchor.VisualAnchorError): self.identity()

    def test_schema2_creation_requires_external_raw_scope(self):
        raw = self.root / "raw"
        shutil.copytree(self.fixture.raw, raw)
        path = raw / "pages-evidence.json"
        manifest = json.loads(path.read_bytes())
        manifest["provenance"]["matrix_sha256"] = self.digest
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(anchor.VisualAnchorError, "external scope and projection"):
            anchor.create_anchor(input_root=raw, output=self.root / "new-anchor",
                matrix_path=self.matrix_path, repository=legacy.REPO_NAME, branch=self.fixture.branch,
                commit=legacy.COMMIT, tree=legacy.TREE, source_run_id=101, source_run_attempt=2,
                source_controller_branch=self.fixture.branch, source_controller_sha=legacy.COMMIT,
                raw_artifact_id=501, raw_artifact_name_value=self.fixture.raw_name, raw_artifact_digest="5" * 64)
        self.assertFalse((self.root / "new-anchor").exists())
