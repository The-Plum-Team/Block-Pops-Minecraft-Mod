"""Explicit job selection is a caller input, never inferred from observed jobs."""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci import e2e_job_graph as jobs
from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.ci.tests.test_e2e_job_graph import graph
from scripts.release.matrix import MatrixError


class ScopedJobGraphTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.matrix = self.root / "matrix.json"
        self.write(schema2_configuration())

    def write(self, matrix):
        self.matrix.write_text(json.dumps(matrix))

    def expected(self, **kwargs):
        return jobs.expected_jobs(self.matrix, "on-demand-e2e.yml", **kwargs)

    def test_legacy_lane_and_full_keep_exact_control_jobs(self):
        for scope, node, shared, count in (("legacy", None, False, 2),
                ("lane", "neoforge-1.21.1", False, 1), ("full", None, True, 18)):
            with self.subTest(scope=scope):
                self.write(schema2_configuration(shared=shared))
                selected = self.expected(scope=scope, artifact_node=node)
                scenario = [item for item in selected if item.name.endswith(jobs.SCENARIO_SUFFIX)]
                self.assertEqual(count, len(scenario))
                self.assertEqual(6, len(selected) - len(scenario))
                self.assertEqual(tuple(item.name for item in selected), jobs.expected_names(
                    self.matrix, "on-demand-e2e.yml", scope=scope, artifact_node=node))
                for removed in range(len(selected)):
                    records = graph(selected)
                    del records[removed]
                    with self.assertRaises(jobs.JobGraphError):
                        jobs.validate_jobs(records, expected=selected, run_attempt=2)

    def test_invalid_or_unresolved_scope_never_falls_back(self):
        for arguments in ({"scope": "full"}, {"scope": "lane"},
                {"artifact_node": "fabric-1.20.1"}, {"scope": "legacy", "artifact_node": "fabric-1.20.1"},
                {"scope": "lane", "artifact_node": "fabric-1.21.7"}, {"scope": "unknown"}):
            with self.subTest(arguments=arguments), self.assertRaises((jobs.JobGraphError, MatrixError)):
                self.expected(**arguments)
        for event in ("schedule", "pull_request_target"):
            with self.subTest(event=event), self.assertRaises(jobs.JobGraphError):
                self.expected(scope="lane", artifact_node="neoforge-1.21.1", event=event)
        self.write(schema1_matrix())
        with self.assertRaises(jobs.JobGraphError): self.expected(scope="full")

    def test_partial_and_crossed_observations_do_not_redefine_expected_scope(self):
        legacy = self.expected(scope="legacy")
        lane = self.expected(scope="lane", artifact_node="neoforge-1.21.1")
        with self.assertRaises(jobs.JobGraphError):
            jobs.validate_jobs(graph(lane), expected=legacy, run_attempt=2)
        self.write(schema2_configuration(shared=True))
        full = self.expected(scope="full")
        with self.assertRaises(jobs.JobGraphError):
            jobs.validate_jobs(graph(legacy), expected=full, run_attempt=2)

    def test_explicit_build_scope_validates_configuration_without_claiming_job_scope(self):
        baseline = jobs.expected_jobs(self.matrix, "build-gate.yml")
        self.assertEqual(baseline, jobs.expected_jobs(self.matrix, "build-gate.yml",
            scope="lane", artifact_node="neoforge-1.21.1"))
        with self.assertRaises(MatrixError):
            jobs.expected_jobs(self.matrix, "build-gate.yml", scope="full")

    def test_cli_selected_lane_and_exclusive_selectors(self):
        node = "neoforge-1.21.1"
        selected = self.expected(scope="lane", artifact_node=node)
        path = self.root / "jobs.json"
        path.write_text(json.dumps({"jobs": graph(selected)}))
        arguments = ["--matrix", str(self.matrix), "--workflow", "on-demand-e2e.yml",
                     "--jobs", str(path), "--run-attempt", "2", "--artifact-node", node]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, jobs.main(arguments))
        self.assertEqual(sorted(item.name for item in selected), json.loads(output.getvalue())["required_jobs"])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            jobs.main(arguments + ["--scope", "legacy"])
