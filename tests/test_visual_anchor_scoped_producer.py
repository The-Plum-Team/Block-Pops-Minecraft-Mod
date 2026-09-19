"""A scoped aggregate produces only the fixed canonical visual anchor."""

import copy
import contextlib
import hashlib
import io
import json
import unittest
from unittest.mock import patch

from scripts.pages import evidence, visual_anchor as anchor
from tests import test_pages_raw_scope as reader


class ScopedAnchorProducerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        reader.ScopedRawPagesTests.setUpClass()
        cls.addClassCleanup(reader.ScopedRawPagesTests.doClassCleanups)

    def setUp(self):
        self.fixture = reader.ScopedRawPagesTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def prepare(self, scope="legacy", node=None, *, shared=False):
        f = self.fixture
        f.prepare(scope, node, shared=shared, projection="scheduled-anchors")
        matrix = json.loads(f.matrix_path.read_bytes())
        matrix["branch"] = {"role": "integration", "name": "master", "canonical": "master",
                            "sync": {"enabled": False, "source": "master"}}
        f.matrix_path.write_text(json.dumps(matrix))
        f.expected.update(branch="master", matrix_sha256=hashlib.sha256(f.matrix_path.read_bytes()).hexdigest())
        f.expected["handoff"]["controller_sha"] = f.expected["commit"]
        f.manifest["provenance"] = {**copy.deepcopy(f.expected), "packaged": {
            **f.expected["handoff"], "branch": "master", "commit": f.expected["commit"], "tree": f.expected["tree"]}}
        f.write(f.manifest)

    def create(self, **overrides):
        f = self.fixture
        return anchor.create_anchor(input_root=f.raw, output=f.root / "anchor", matrix_path=f.matrix_path,
            repository=f.expected["repository"], branch="master", commit=f.expected["commit"], tree=f.expected["tree"],
            source_run_id=101, source_run_attempt=2, source_controller_branch="master",
            source_controller_sha=f.expected["commit"], raw_artifact_id=123,
            raw_artifact_name_value=evidence.raw_artifact_name("master", 2), raw_artifact_digest="a" * 64,
            **{**f.arguments, **overrides})

    def test_partial_lane_and_full_raw_inputs_preserve_one_canonical_anchor(self):
        root = self.fixture.root
        for index, (scope, node, shared) in enumerate((("legacy", None, False),
                ("lane", "fabric-1.20.1", False), ("full", None, True))):
            with self.subTest(scope=scope):
                self.fixture.root = root / str(index); self.fixture.root.mkdir()
                self.prepare(scope, node, shared=shared)
                result = self.create()
                self.assertEqual(result, anchor.validate_anchor(self.fixture.root / "anchor",
                    matrix_path=self.fixture.matrix_path, expected=self.fixture.expected))
                self.assertEqual({"fabric-1.20.1"}, {row["artifact_node"] for row in result["lanes"]})
                self.assertEqual(set(evidence.default_contract().capture_ids), {row["capture_id"] for row in result["frames"]})
                self.assertNotIn("aggregate_scope", result)
                self.assertEqual(1, result["schema_version"])
                f = self.fixture
                args = ["create", "--input", str(f.raw), "--output", str(f.root / "cli-anchor"),
                    "--matrix", str(f.matrix_path), "--repository", f.expected["repository"], "--branch", "master",
                    "--commit", f.expected["commit"], "--tree", f.expected["tree"], "--source-run-id", "101",
                    "--source-run-attempt", "2", "--source-controller-branch", "master",
                    "--source-controller-sha", f.expected["commit"], "--raw-artifact-id", "123",
                    "--raw-artifact-name", evidence.raw_artifact_name("master", 2), "--raw-artifact-digest", "a" * 64,
                    "--projection", "scheduled-anchors"]
                args += ["--artifact-node", node] if node else ["--scope", scope]
                with contextlib.redirect_stdout(io.StringIO()) as printed:
                    self.assertEqual(0, anchor.main(args))
                summary = json.loads(printed.getvalue())
                self.assertEqual("fabric-1.20.1", summary["artifact_node"])
                self.assertNotIn("aggregate_scope", summary)
                self.assertEqual(result, anchor.validate_anchor(f.root / "cli-anchor",
                    matrix_path=f.matrix_path, expected=f.expected))
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    anchor.main(args + (["--scope", "legacy"] if node else ["--artifact-node", "fabric-1.20.1"]))

    def test_absent_crossed_or_unresolved_scope_cannot_be_inferred(self):
        self.prepare()
        for overrides in ({"scope": None}, {"projection": None}, {"projection": "pr-anchors"},
                {"scope": "full"}, {"scope": "lane", "artifact_node": "fabric-1.20.1"},
                {"scope": "lane", "artifact_node": "fabric-1.21.7"}):
            with self.subTest(overrides=overrides), self.assertRaises(evidence.EvidenceError):
                self.create(**overrides)
            self.assertFalse((self.fixture.root / "anchor").exists())

    def test_valid_raw_scope_without_canonical_lane_is_ineligible(self):
        self.prepare("lane", "neoforge-1.21.1")
        with self.assertRaisesRegex(anchor.VisualAnchorError, "does not include the canonical"):
            self.create()
        self.assertFalse((self.fixture.root / "anchor").exists())

    def test_scoped_creation_retains_direct_run_and_post_validation_pixel_binding(self):
        self.prepare()
        f = self.fixture
        original = copy.deepcopy(f.manifest)
        f.manifest["provenance"]["packaged"]["run_id"] += 1
        f.write(f.manifest)
        with self.assertRaisesRegex(anchor.VisualAnchorError, "one direct exact-head"):
            self.create()
        f.manifest = original; f.write(original)
        validate = anchor.validate_raw
        def replace_png(*args, **kwargs):
            raw = validate(*args, **kwargs)
            source = next(frame["source"] for frame in raw["frames"] if frame["artifact_node"] == "fabric-1.20.1")
            replacement = next(frame["source"] for frame in raw["frames"] if frame["source"]["sha256"] != source["sha256"])
            (f.raw / source["path"]).write_bytes((f.raw / replacement["path"]).read_bytes())
            return raw
        with patch.object(anchor, "validate_raw", side_effect=replace_png):
            with self.assertRaisesRegex(anchor.VisualAnchorError, "raw frame changed"):
                self.create()
        self.assertFalse((f.root / "anchor").exists())
