from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from scripts.release.matrix import MatrixError, load_matrix_document
from scripts.ci.tests.matrix_fixtures import schema2_configuration

from scripts.ci.e2e_job_graph import (
    E2E_AGGREGATE,
    E2E_PUBLIC,
    ExpectedJob,
    JobGraphError,
    SCENARIO_SUFFIX,
    expected_jobs,
    expected_names,
    validate_jobs,
)


def job(
    job_id: int,
    name: str,
    *,
    attempt: int = 2,
    conclusion: str = "success",
) -> dict[str, object]:
    return {
        "id": job_id,
        "name": name,
        "run_attempt": attempt,
        "status": "completed",
        "conclusion": conclusion,
    }


def graph(expectations: tuple[ExpectedJob, ...], *, attempt: int = 2) -> list[dict[str, object]]:
    return [
        job(index, expected.name, attempt=attempt, conclusion=expected.conclusion)
        for index, expected in enumerate(expectations, start=1)
    ]


class JobGraphTests(unittest.TestCase):
    def test_expected_lanes_derive_from_branch_projection_not_default_names(self) -> None:
        branch_matrix = {"branch": {"name": "opaque/next"}}
        projected = {
            "include": [
                {"id": "quiltish-9_9--pr-behavior"},
                {"id": "neo-era-9_9--pr-behavior"},
            ]
        }
        document = SimpleNamespace(branch_name=branch_matrix["branch"]["name"],
                                   projection=mock.Mock(return_value=projected))
        with mock.patch("scripts.ci.e2e_job_graph.load_matrix_document", return_value=document):
            names = expected_names(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                source_branch="automation/release-sync/opaque",
            )
        self.assertIn("quiltish-9_9--pr-behavior" + SCENARIO_SUFFIX, names)
        self.assertIn("neo-era-9_9--pr-behavior" + SCENARIO_SUFFIX, names)
        self.assertNotIn("fabric-1.20.1--pr-behavior" + SCENARIO_SUFFIX, names)
        self.assertIn(E2E_AGGREGATE, names)

    def test_exact_attempt_and_every_control_job_are_mandatory(self) -> None:
        expected = (
            ExpectedJob("Resolve", "success"),
            ExpectedJob("Build", "success"),
            ExpectedJob("Aggregate", "success"),
            ExpectedJob("Gate", "success"),
            ExpectedJob("Attest", "skipped"),
        )
        jobs = graph(expected)
        result = validate_jobs(jobs, expected=expected, run_attempt=2)
        self.assertEqual(result["required_jobs"], sorted(item.name for item in expected))
        self.assertEqual(result["expected_conclusions"]["Attest"], "skipped")

        for removed in ("Resolve", "Aggregate", "Gate", "Attest"):
            with self.subTest(removed=removed), self.assertRaisesRegex(
                JobGraphError, "exact job inventory mismatch"
            ):
                validate_jobs(
                    [item for item in jobs if item["name"] != removed],
                    expected=expected,
                    run_attempt=2,
                )

    def test_any_extra_job_or_duplicate_name_fails(self) -> None:
        expected = (ExpectedJob("Resolve", "success"), ExpectedJob("Gate", "success"))
        jobs = graph(expected)
        with self.assertRaisesRegex(JobGraphError, "unexpected=.*invented controller"):
            validate_jobs(
                [*jobs, job(90, "invented controller")], expected=expected, run_attempt=2
            )
        with self.assertRaisesRegex(JobGraphError, "appears 2 times"):
            validate_jobs(
                [*jobs, job(91, "Gate")], expected=expected, run_attempt=2
            )

    def test_expected_skips_and_successes_are_exact(self) -> None:
        expected = (ExpectedJob("Gate", "success"), ExpectedJob("Advisory", "skipped"))
        jobs = graph(expected)
        jobs[1]["conclusion"] = "success"
        with self.assertRaisesRegex(JobGraphError, "did not complete as 'skipped'"):
            validate_jobs(jobs, expected=expected, run_attempt=2)
        jobs[1]["conclusion"] = "skipped"
        jobs[0]["conclusion"] = "failure"
        with self.assertRaisesRegex(JobGraphError, "did not complete as 'success'"):
            validate_jobs(jobs, expected=expected, run_attempt=2)

    def test_old_success_cannot_replace_current_attempt_failure_or_absence(self) -> None:
        expected = (ExpectedJob("Build", "success"),)
        with self.assertRaisesRegex(JobGraphError, "did not complete as 'success'"):
            validate_jobs(
                [
                    job(1, "Build", attempt=1),
                    job(2, "Build", attempt=2, conclusion="failure"),
                ],
                expected=expected,
                run_attempt=2,
            )
        with self.assertRaisesRegex(JobGraphError, "missing=.*Build"):
            validate_jobs(
                [job(1, "Build", attempt=1)], expected=expected, run_attempt=2
            )

    def test_public_evidence_conclusion_follows_event_and_matrix_branch(self) -> None:
        branch_matrix = {"branch": {"name": "ship/aurora"}}
        projected = {"include": [{"id": "neo-9_9--pr-behavior"}]}
        document = SimpleNamespace(branch_name=branch_matrix["branch"]["name"],
                                   projection=mock.Mock(return_value=projected))
        with mock.patch("scripts.ci.e2e_job_graph.load_matrix_document", return_value=document):
            ordinary = expected_jobs(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                event="workflow_dispatch",
                source_branch="ship/aurora",
            )
            automation = expected_jobs(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                event="workflow_dispatch",
                source_branch="automation/release-sync/opaque",
            )
            pull_request_target = expected_jobs(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                event="pull_request_target",
                source_branch="master",
            )
        self.assertEqual("success", next(item.conclusion for item in ordinary if item.name == E2E_PUBLIC))
        self.assertEqual("skipped", next(item.conclusion for item in automation if item.name == E2E_PUBLIC))
        self.assertEqual(
            "skipped",
            next(
                item.conclusion
                for item in pull_request_target
                if item.name == E2E_PUBLIC
            ),
        )

    def test_prt_uses_pr_anchors_and_legacy_pull_request_is_rejected(self) -> None:
        branch_matrix = {"branch": {"name": "master"}}
        projected = {"include": [{"id": "neo-9_9--pr-behavior"}]}
        document = SimpleNamespace(branch_name="master", projection=mock.Mock(return_value=projected))
        with mock.patch(
            "scripts.ci.e2e_job_graph.load_matrix_document", return_value=document
        ) as load:
            expected_jobs(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                event="pull_request_target",
                source_branch="master",
            )
        load.assert_called_once()
        document.projection.assert_called_once_with("pr-anchors")
        with self.assertRaisesRegex(JobGraphError, "unsupported protected source event"):
            expected_jobs(
                None,  # type: ignore[arg-type]
                "build-gate.yml",
                event="pull_request",
            )


class NormalizedGraphTests(unittest.TestCase):
    def test_preparing_preserves_legacy_graph_and_shared_covers_twelve(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            for matrix, count in ((schema2_configuration(), 2), (schema2_configuration(shared=True), 12)):
                path.write_text(json.dumps(matrix))
                for event in ("schedule", "pull_request_target"):
                    names = expected_names(path, "on-demand-e2e.yml", event=event)
                    scenarios = [name for name in names if name.endswith(SCENARIO_SUFFIX)]
                    self.assertEqual(count, len(scenarios))
                    self.assertTrue(all("1_20_1" in name for name in scenarios) if count == 2 else True)
                    expected = expected_jobs(path, "on-demand-e2e.yml", event=event)
                    with self.assertRaises(JobGraphError):
                        validate_jobs(graph(expected)[1:], expected=expected, run_attempt=2)

    def test_selected_projection_is_explicit_and_cannot_hide_unresolved_full_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "matrix.json"
            path.write_text(json.dumps(schema2_configuration()))
            document = load_matrix_document(path, validate_sources=False)
            self.assertEqual(2, document.data["schema_version"])
            rows = document.projection("runtime", scope="lane", artifact_node="neoforge-1.21.1")["include"]
            self.assertEqual(["neoforge-1.21.1"], [row["artifact_node"] for row in rows])
            for scope, node in (("full", None), ("lane", None), ("lane", "fabric-1.21.7"),
                                ("legacy", "neoforge-1.21.1"), ("invented", None)):
                with self.subTest(scope=scope, node=node), self.assertRaises(MatrixError):
                    document.select_lanes(scope=scope, artifact_node=node)
            matrix = schema2_configuration()
            matrix["runtimes"][-1]["artifact_node"] = "forge-1.20.1"
            path.write_text(json.dumps(matrix))
            with self.assertRaises(MatrixError):
                expected_names(path, "on-demand-e2e.yml")


if __name__ == "__main__":
    unittest.main()
