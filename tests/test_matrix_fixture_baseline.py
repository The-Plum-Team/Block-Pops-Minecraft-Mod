"""Separate frozen compatibility inputs from the live checkout's matrix contract."""

import unittest
from pathlib import Path

from scripts.release.matrix import load_matrix, load_matrix_document
from tests.matrix_fixtures import SCHEMA1_MATRIX_PATH, schema1_matrix


class MatrixFixtureBaselineTests(unittest.TestCase):
    def test_frozen_schema_one_is_complete_and_each_read_is_independent(self):
        original = schema1_matrix()
        self.assertEqual(1, original["schema_version"])
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"],
                         [row["artifact_node"] for row in original["artifacts"]])
        self.assertEqual(original, load_matrix(SCHEMA1_MATRIX_PATH, validate_sources=False))
        changed = schema1_matrix()
        changed["artifacts"][0]["metadata"]["minecraft"] = "mutated"
        self.assertEqual(original, schema1_matrix())

    def test_live_checkout_matrix_still_validates_configuration_sources_and_default_selection(self):
        path = Path(__file__).resolve().parents[1] / "release/release-matrix.json"
        document = load_matrix_document(path)
        self.assertTrue(document.inventory.sources_checked)
        self.assertTrue(document.select_lanes())


if __name__ == "__main__":
    unittest.main()
