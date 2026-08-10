from __future__ import annotations

import unittest
from unittest import mock

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
        with mock.patch("scripts.ci.e2e_job_graph.load_matrix", return_value=branch_matrix), mock.patch(
            "scripts.ci.e2e_job_graph.gha_matrix", return_value=projected
        ):
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
        with mock.patch("scripts.ci.e2e_job_graph.load_matrix", return_value=branch_matrix), mock.patch(
            "scripts.ci.e2e_job_graph.gha_matrix", return_value=projected
        ):
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
            pull_request = expected_jobs(
                None,  # type: ignore[arg-type]
                "on-demand-e2e.yml",
                event="pull_request",
                source_branch="123/merge",
            )
        self.assertEqual("success", next(item.conclusion for item in ordinary if item.name == E2E_PUBLIC))
        self.assertEqual("skipped", next(item.conclusion for item in automation if item.name == E2E_PUBLIC))
        self.assertEqual("skipped", next(item.conclusion for item in pull_request if item.name == E2E_PUBLIC))


if __name__ == "__main__":
    unittest.main()
