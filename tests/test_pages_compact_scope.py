"""Scoped WebP derivatives retain authenticated matrix bytes and partial coverage."""

import copy
import json
import unittest
from unittest.mock import patch

from scripts.pages import evidence
from tests import test_pages_raw_scope as reader


class ScopedCompactPagesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        reader.ScopedRawPagesTests.setUpClass()
        cls.addClassCleanup(reader.ScopedRawPagesTests.doClassCleanups)

    def setUp(self):
        self.fixture = reader.ScopedRawPagesTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def create(self, **overrides):
        f = self.fixture
        self.output = f.root / "compact"
        return evidence.compact(input_root=f.raw, output=self.output, matrix_path=f.matrix_path,
            expected=f.expected, source_artifact_id=123,
            source_artifact_name=evidence.raw_artifact_name(f.expected["branch"], 2),
            source_artifact_digest="sha256:" + "a" * 64, **{**f.arguments, **overrides})

    def validate(self, **overrides):
        f = self.fixture
        return evidence.validate_compact(self.output, matrix_path=f.matrix_path, expected=f.expected,
                                         **{**f.arguments, **overrides})

    def test_selected_scopes_round_trip_through_webp_and_cache_copy(self):
        for index, (scope, node, shared, projection) in enumerate((
            ("legacy", None, False, "pr-anchors"),
            ("lane", "neoforge-1.21.1", False, "scheduled-anchors"),
            ("full", None, True, "pr-anchors"),
        )):
            with self.subTest(scope=scope):
                f = self.fixture
                f.root = f.root / str(index); f.root.mkdir()
                f.prepare(scope, node, shared=shared, projection=projection)
                manifest = self.create()
                self.assertEqual(manifest, self.validate())
                self.assertEqual(2, manifest["schema_version"])
                self.assertEqual(f.manifest["aggregate_scope"], manifest["aggregate_scope"])
                self.assertEqual(f.expected["matrix_sha256"], manifest["matrix"]["sha256"])
                self.assertEqual(f.matrix_path.read_bytes(), (self.output / "release-matrix.json").read_bytes())
                copied = evidence.copy_compact(input_root=self.output, output=f.root / "copied",
                    matrix_path=f.matrix_path, expected=f.expected, **f.arguments)
                self.assertEqual(manifest, copied)

    def test_unadapted_or_crossed_scope_cannot_consume_compact_evidence(self):
        self.fixture.prepare(); self.create()
        for overrides in ({"scope": None}, {"projection": None}, {"scope": "full"},
                          {"projection": "scheduled-anchors"}, {"scope": "lane", "artifact_node": "fabric-1.20.1"}):
            with self.subTest(overrides=overrides), self.assertRaises(evidence.EvidenceError):
                self.validate(**overrides)

    def test_coverage_inventory_and_numeric_types_remain_exact(self):
        self.fixture.prepare(); original = self.create()
        mutations = [lambda m: m.update(schema_version=2.0), lambda m: m["aggregate_scope"].update(partial=False),
                     lambda m: m["aggregate_scope"].update(partial=1), lambda m: m["lanes"].pop(),
                     lambda m: m["frames"].pop(), lambda m: m["files"].pop()]
        for key in ("size",):
            mutations.append(lambda m, key=key: m["matrix"].update({key: float(m["matrix"][key])}))
            mutations.append(lambda m, key=key: m["files"][0].update({key: float(m["files"][0][key])}))
        for key in ("size", "width", "height"):
            mutations.append(lambda m, key=key: m["frames"][0]["derivative"].update(
                {key: float(m["frames"][0]["derivative"][key])}))
        for mutate in mutations:
            manifest = copy.deepcopy(original); mutate(manifest)
            (self.output / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(evidence.EvidenceError): self.validate()

    def test_matrix_is_read_once_and_embedded_bytes_cannot_replace_external_identity(self):
        self.fixture.prepare(); self.create()
        with patch.object(evidence, "_json", wraps=evidence._json) as read:
            self.validate()
        self.assertEqual(1, sum(call.args[0] == self.fixture.matrix_path for call in read.call_args_list))
        embedded = self.output / "release-matrix.json"
        embedded.write_bytes(embedded.read_bytes() + b" ")
        with self.assertRaises(evidence.EvidenceError): self.validate()

    def test_post_raw_matrix_drift_and_altered_derivative_prevent_publication(self):
        self.fixture.prepare()
        original = evidence.validate_raw
        def changed(*args, **kwargs):
            raw = original(*args, **kwargs)
            matrix = self.fixture.matrix_path
            matrix.write_bytes(matrix.read_bytes() + b" ")
            return raw
        with patch.object(evidence, "validate_raw", side_effect=changed):
            with self.assertRaisesRegex(evidence.EvidenceError, "matrix changed after raw validation"):
                self.create()
        self.assertFalse(self.output.exists())
        self.fixture.matrix_path.write_bytes(self.fixture.matrix_path.read_bytes()[:-1])
        with patch.object(evidence, "_encode_webp", return_value=b"not an image"):
            with self.assertRaises(evidence.EvidenceError): self.create()
        self.assertFalse(self.output.exists())

    def test_png_replaced_after_raw_validation_cannot_be_compacted(self):
        self.fixture.prepare()
        validate = evidence.validate_raw
        def replace_png(*args, **kwargs):
            raw = validate(*args, **kwargs)
            source = raw["frames"][0]["source"]
            replacement = next(frame["source"] for frame in raw["frames"]
                               if frame["source"]["sha256"] != source["sha256"])
            (self.fixture.raw / source["path"]).write_bytes((self.fixture.raw / replacement["path"]).read_bytes())
            return raw
        with patch.object(evidence, "validate_raw", side_effect=replace_png):
            with self.assertRaisesRegex(evidence.EvidenceError, "raw frame changed after validation"):
                self.create()
        self.assertFalse(self.output.exists())

    def test_copy_rejects_a_different_valid_manifest_after_initial_validation(self):
        self.fixture.prepare(); self.create()
        validate = evidence.validate_compact
        def replace_manifest(root, **kwargs):
            manifest = validate(root, **kwargs)
            if root == self.output:
                changed = copy.deepcopy(manifest)
                changed["source_artifact"]["id"] += 1
                (root / "manifest.json").write_text(json.dumps(changed))
            return manifest
        copied = self.fixture.root / "copied"
        with patch.object(evidence, "validate_compact", side_effect=replace_manifest):
            with self.assertRaisesRegex(evidence.EvidenceError, "compact manifest changed during copy"):
                evidence.copy_compact(input_root=self.output, output=copied,
                    matrix_path=self.fixture.matrix_path, expected=self.fixture.expected, **self.fixture.arguments)
        self.assertFalse(copied.exists())

    def test_unreferenced_file_cannot_hide_in_the_compact_inventory(self):
        self.fixture.prepare(); manifest = self.create()
        data = b"not an image and not referenced by any frame"
        digest = evidence.sha256_bytes(data)
        relative = f"images/{digest}.webp"
        (self.output / relative).write_bytes(data)
        manifest["files"].append({"path": relative, "sha256": digest, "size": len(data)})
        (self.output / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(evidence.EvidenceError, "unreferenced compact derivatives"):
            self.validate()
