"""Synthetic pixel round trips preserve caller-selected raw aggregate coverage."""

import copy
import unittest
from unittest.mock import patch

from scripts.pages import evidence
from tests import test_pages_raw_scope as reader


class ScopedPagesProducerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        reader.ScopedRawPagesTests.setUpClass()
        cls.addClassCleanup(reader.ScopedRawPagesTests.doClassCleanups)

    def setUp(self):
        self.fixture = reader.ScopedRawPagesTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def create(self, **overrides):
        fixture = self.fixture
        expected = fixture.expected
        handoff = expected["handoff"]
        return evidence.curate(input_root=fixture.raw, output=fixture.root / "curated",
            matrix_path=fixture.matrix_path, repository=expected["repository"], branch=expected["branch"],
            commit=expected["commit"], tree=expected["tree"], run_id=handoff["run_id"],
            run_attempt=handoff["run_attempt"], controller_branch=handoff["controller_branch"],
            controller_sha=handoff["controller_sha"], **{**fixture.arguments, **overrides})

    def test_legacy_lane_and_shared_producer_reader_round_trips(self):
        for index, (scope, node, shared, projection) in enumerate((
            ("legacy", None, False, "pr-anchors"),
            ("lane", "neoforge-1.21.1", False, "scheduled-anchors"),
            ("full", None, True, "pr-anchors"),
        )):
            with self.subTest(scope=scope):
                self.fixture.root = self.fixture.root / str(index)
                self.fixture.root.mkdir()
                self.fixture.prepare(scope, node, shared=shared, projection=projection)
                result = self.create()
                expected = copy.deepcopy(self.fixture.manifest)
                expected["files"].sort(key=lambda record: record["path"])
                self.assertEqual(expected, result)
                self.assertEqual(result, evidence.validate_raw(self.fixture.root / "curated",
                    matrix_path=self.fixture.matrix_path, expected=self.fixture.expected, **self.fixture.arguments))

    def test_scope_is_never_inferred_from_input_receipt(self):
        self.fixture.prepare()
        for overrides in ({"scope": None}, {"projection": None}, {"scope": "full"},
                          {"scope": "lane", "artifact_node": "fabric-1.20.1"}):
            with self.subTest(overrides=overrides), self.assertRaises(evidence.EvidenceError):
                self.create(**overrides)
            self.assertFalse((self.fixture.root / "curated").exists())

    def test_missing_or_extra_profiles_cannot_be_silently_omitted(self):
        self.fixture.prepare()
        profiles = self.fixture.raw / "profiles"
        extra = profiles / "unknown"
        extra.mkdir()
        with self.assertRaisesRegex(evidence.EvidenceError, "profile inventory mismatch"):
            self.create()
        extra.rmdir()
        known = next(profiles.iterdir())
        known.rename(self.fixture.root / "removed")
        with self.assertRaisesRegex(evidence.EvidenceError, "profile inventory mismatch"):
            self.create()
        self.assertFalse((self.fixture.root / "curated").exists())

    def test_matrix_drift_before_publication_discards_owned_output(self):
        self.fixture.prepare()
        original = evidence._write_json
        def changed(path, value):
            original(path, value)
            matrix = self.fixture.matrix_path
            matrix.write_bytes(matrix.read_bytes() + b" ")
        with patch.object(evidence, "_write_json", side_effect=changed):
            with self.assertRaisesRegex(evidence.EvidenceError, "authenticated matrix/contract"):
                self.create()
        self.assertFalse((self.fixture.root / "curated").exists())
        self.assertFalse(list(self.fixture.root.glob(".curated.building-*")))

    def test_pixels_and_scope_are_revalidated_before_publication(self):
        self.fixture.prepare()
        original = evidence._write_json
        def changed(path, value):
            value = copy.deepcopy(value)
            value["aggregate_scope"]["partial"] = False
            original(path, value)
        with patch.object(evidence, "_write_json", side_effect=changed):
            with self.assertRaisesRegex(evidence.EvidenceError, "external scope/projection"):
                self.create()
        self.assertFalse((self.fixture.root / "curated").exists())
