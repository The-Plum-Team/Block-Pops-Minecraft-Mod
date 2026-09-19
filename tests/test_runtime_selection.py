"""Packaged runtime selection is an exact normalized matrix projection."""

from __future__ import annotations

import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

from e2e.orchestrator import CONTRACT, main, parse_args, select_rows
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.matrix import MatrixDocument, MatrixError, gha_matrix, normalize_matrix_inventory
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix


def document(matrix: dict) -> MatrixDocument:
    return MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))


class RuntimeSelectionTests(unittest.TestCase):
    def test_schema_one_preserves_runtime_and_exact_pr_row_selection(self):
        matrix = arbitrary_named_1211_release_matrix()
        source = document(matrix)
        self.assertEqual(matrix["runtimes"], select_rows(source, parse_args([])))
        for expected in gha_matrix(matrix, "pr-anchors", contract=CONTRACT)["include"]:
            rows = select_rows(source, parse_args(["--row-json", json.dumps(expected)]))
            self.assertEqual([expected["artifact_node"]], [row["artifact_node"] for row in rows])

    def test_preparation_defaults_to_legacy_and_modern_selection_is_explicit(self):
        source = document(schema2_configuration())
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"],
                         [row["artifact_node"] for row in select_rows(source, parse_args([]))])
        args = parse_args(["--artifact-node", "neoforge-1.21.1"])
        self.assertEqual([source.inventory.lane("neoforge-1.21.1").runtime], select_rows(source, args))
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--scope", "full"]))
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--artifact-node", "fabric-1.21.7"]))
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--artifact-node", "fabric-1.21.1", "--loader", "neoforge"]))

    def test_shared_default_covers_every_target(self):
        source = document(schema2_configuration(shared=True))
        self.assertEqual(set(source.inventory.target_nodes),
                         {row["artifact_node"] for row in select_rows(source, parse_args([]))})
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--scope", "legacy"]))
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--loader", "fabric"]))

    def test_caller_runtime_overrides_never_replace_the_authoritative_row(self):
        source = document(schema2_configuration())
        expected = source.projection("pr-anchors", contract=CONTRACT)["include"][0]
        for key, value in (("java", 21), ("artifact_node", "fabric-1.21.1"),
                           ("scenarios", "skip"), ("id", "wrong"), ("loader_version", "latest")):
            changed = copy.deepcopy(expected)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                select_rows(source, parse_args(["--row-json", json.dumps(changed)]))
        modern = source.projection("pr-anchors", scope="lane", artifact_node="neoforge-1.21.1",
                                   contract=CONTRACT)["include"][0]
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--row-json", json.dumps(modern)]))
        args = parse_args(["--row-json", json.dumps(modern), "--artifact-node", "neoforge-1.21.1"])
        self.assertEqual("neoforge-1.21.1", select_rows(source, args)[0]["artifact_node"])

    def test_schema_two_listing_exposes_partial_scope_and_packaged_requires_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix = schema2_configuration()
            for route in matrix["source_routing"].values():
                for key in ("canonical", "e2e"):
                    (root / route[key]).mkdir(parents=True)
            path = root / "release/matrix.json"
            path.parent.mkdir()
            path.write_text(json.dumps(matrix))
            arguments = ["--matrix", str(path), "--artifacts-manifest", str(root / "absent.json")]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, main(arguments + ["--list"]))
            report = json.loads(output.getvalue())
            self.assertEqual(("legacy", "preparing", 12, 4),
                             tuple(report[key] for key in ("scope", "migration_mode", "target_count", "configured_lane_count")))
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(2, main(arguments + ["--packaged"]))
            self.assertIn("requires", error.getvalue())


if __name__ == "__main__":
    unittest.main()
