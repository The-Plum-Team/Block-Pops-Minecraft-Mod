from __future__ import annotations

import contextlib
import copy
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci import matrix_scope
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.matrix import MatrixError
from tests.matrix_fixtures import schema1_matrix

REPO = Path(__file__).resolve().parents[3]


class MatrixScopeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / "release" / "release-matrix.json"
        self.path.parent.mkdir()

    def write(self, matrix):
        self.path.write_text(json.dumps(matrix))

    def test_normalized_schema_and_mode_select_exact_default_dispatch(self):
        legacy = schema1_matrix()
        for matrix, token in ((legacy, "unscoped"), (schema2_configuration(), "legacy"),
                              (schema2_configuration(shared=True), "full")):
            self.write(matrix)
            with self.subTest(token=token):
                self.assertEqual(token, matrix_scope.default_dispatch_scope(self.path, validate_sources=False))

    def test_malformed_context_and_incomplete_shared_never_emit_a_selector(self):
        base = schema2_configuration(shared=True)
        mutations = (
            lambda value: value.update(schema_version=True),
            lambda value: value["migration"].update(mode="unknown"),
            lambda value: value["artifacts"][0].update(gradle_java=17),
            lambda value: value["runtimes"][0].update(loader="neoforge"),
            lambda value: (value["artifacts"].pop(), value["runtimes"].pop(), value.update(lane_count=11)),
        )
        for mutate in mutations:
            matrix = copy.deepcopy(base)
            mutate(matrix)
            self.write(matrix)
            with self.subTest(mutate=mutate), self.assertRaises(MatrixError):
                matrix_scope.default_dispatch_scope(self.path, validate_sources=False)
        self.path.write_text('{"schema_version":1,"schema_version":2}')
        with self.assertRaises(MatrixError): matrix_scope.default_dispatch_scope(self.path, validate_sources=False)

    def test_source_validation_defaults_on_and_can_only_skip_tree_inspection(self):
        matrix = schema2_configuration()
        self.write(matrix)
        with self.assertRaises(MatrixError): matrix_scope.default_dispatch_scope(self.path)
        self.assertEqual("legacy", matrix_scope.default_dispatch_scope(self.path, validate_sources=False))
        for route in matrix["source_routing"].values():
            for key in ("canonical", "e2e"): (self.root / route[key]).mkdir(parents=True)
        self.assertEqual("legacy", matrix_scope.default_dispatch_scope(self.path))
        matrix["migration"]["legacy_nodes"] = []
        self.write(matrix)
        with self.assertRaises(MatrixError): matrix_scope.default_dispatch_scope(self.path, validate_sources=False)

    def test_explicit_path_cli_works_outside_checkout_without_manifest_authority(self):
        self.write(schema2_configuration())
        (self.root / "artifacts.json").write_text('{"scope":{"kind":"full"}}')
        result = subprocess.run([sys.executable, str(REPO / "scripts/ci/matrix_scope.py"),
            "--matrix", str(self.path), "--no-source-check"], cwd=self.root,
            capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("legacy\n", result.stdout)
        self.assertEqual("", result.stderr)

    def test_cli_failure_has_no_selector_and_no_custom_scope_surface(self):
        self.write(schema2_configuration())
        output, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            self.assertEqual(2, matrix_scope.main(["--matrix", str(self.path)]))
        self.assertEqual("", output.getvalue())
        self.assertIn("matrix dispatch scope error", error.getvalue())
        for option in ("--scope", "--artifact-node", "--artifact-manifest"):
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
                matrix_scope.main(["--matrix", str(self.path), option, "untrusted"])
            self.assertEqual(2, exit.exception.code)


if __name__ == "__main__":
    unittest.main()
