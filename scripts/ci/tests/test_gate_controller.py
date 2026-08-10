from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci.gate_controller import (
    GateControllerError,
    PROTECTED_PATHS,
    branch_token,
    select_newest_exact_run,
    validate_controller_parity,
)


SHA = "1" * 40


def run_record(
    run_id: int,
    *,
    created: str,
    status: str = "completed",
    conclusion: str | None = "success",
    attempt: int = 1,
) -> dict[str, object]:
    return {
        "id": run_id,
        "run_attempt": attempt,
        "created_at": created,
        "status": status,
        "conclusion": conclusion,
        "path": ".github/workflows/build-gate.yml",
        "event": "workflow_dispatch",
        "head_branch": "automation/release-sync/abc",
        "head_sha": SHA,
        "head_repository": {"full_name": "owner/repo"},
    }


class RunSelectionTests(unittest.TestCase):
    def test_newest_pending_attempt_supersedes_older_success(self) -> None:
        document = {
            "workflow_runs": [
                run_record(10, created="2026-08-10T01:00:00Z"),
                run_record(
                    11,
                    created="2026-08-10T02:00:00Z",
                    status="in_progress",
                    conclusion=None,
                    attempt=3,
                ),
            ]
        }
        selected = select_newest_exact_run(
            document,
            workflow="build-gate.yml",
            branch="automation/release-sync/abc",
            sha=SHA,
            repository="owner/repo",
        )
        self.assertEqual(selected["id"], 11)
        self.assertEqual(selected["run_attempt"], 3)
        self.assertEqual(selected["status"], "in_progress")

    def test_id_breaks_created_at_tie(self) -> None:
        document = {
            "workflow_runs": [
                run_record(90, created="2026-08-10T01:00:00Z"),
                run_record(91, created="2026-08-10T01:00:00Z", attempt=2),
            ]
        }
        selected = select_newest_exact_run(
            document,
            workflow="build-gate.yml",
            branch="automation/release-sync/abc",
            sha=SHA,
            repository="owner/repo",
        )
        self.assertEqual((selected["id"], selected["run_attempt"]), (91, 2))

    def test_wrong_repository_is_not_evidence(self) -> None:
        record = run_record(1, created="2026-08-10T01:00:00Z")
        record["head_repository"] = {"full_name": "attacker/fork"}
        with self.assertRaises(GateControllerError):
            select_newest_exact_run(
                {"workflow_runs": [record]},
                workflow="build-gate.yml",
                branch="automation/release-sync/abc",
                sha=SHA,
                repository="owner/repo",
            )

    def test_duplicate_run_attempt_records_fail_closed(self) -> None:
        record = run_record(7, created="2026-08-10T01:00:00Z", attempt=2)
        with self.assertRaisesRegex(GateControllerError, "repeats"):
            select_newest_exact_run(
                {"workflow_runs": [record, dict(record)]},
                workflow="build-gate.yml",
                branch="automation/release-sync/abc",
                sha=SHA,
                repository="owner/repo",
            )

    def test_branch_token_is_stable_and_collision_resistant_for_slugs(self) -> None:
        self.assertEqual(branch_token("release/a"), branch_token("release/a"))
        self.assertNotEqual(branch_token("release/a"), branch_token("release-a"))
        self.assertRegex(branch_token("release/a"), r"^[0-9a-f]{24}$")


class ParityTests(unittest.TestCase):
    def test_all_evidence_publishers_and_visual_curators_are_protected(self) -> None:
        self.assertIn("scripts/pages", PROTECTED_PATHS)
        self.assertIn("scripts/visual", PROTECTED_PATHS)
        for path in (
            "build.gradle",
            "settings.gradle",
            "gradle.properties",
            "common/build.gradle",
        ):
            self.assertIn(path, PROTECTED_PATHS)

    def test_parity_detects_a_protected_difference(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            subprocess.run(["git", "init", "-q", repository], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.name", "Test"], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.email", "t@example.test"], check=True)
            (repository / "release").mkdir()
            (repository / "release/release-matrix.json").write_text("{}\n", encoding="utf-8")
            (repository / "controller").write_text("one\n", encoding="utf-8")
            subprocess.run(["git", "-C", raw, "add", "controller", "release/release-matrix.json"], check=True)
            subprocess.run(["git", "-C", raw, "commit", "-qm", "base"], check=True)
            protected = subprocess.check_output(["git", "-C", raw, "rev-parse", "HEAD"], text=True).strip()
            (repository / "controller").write_text("two\n", encoding="utf-8")
            subprocess.run(["git", "-C", raw, "commit", "-qam", "change"], check=True)
            candidate = subprocess.check_output(["git", "-C", raw, "rev-parse", "HEAD"], text=True).strip()
            with mock.patch("scripts.ci.gate_controller.PROTECTED_PATHS", ("controller",)), mock.patch(
                "scripts.ci.gate_controller.load_matrix_bytes",
                return_value={"artifacts": []},
            ):
                with self.assertRaisesRegex(GateControllerError, "controller"):
                    validate_controller_parity(
                        repository,
                        protected_sha=protected,
                        candidate_sha=candidate,
                    )

    def test_shared_loader_build_bootstrap_is_part_of_dynamic_parity(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            subprocess.run(["git", "init", "-q", repository], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.name", "Test"], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.email", "t@example.test"], check=True)
            (repository / "release").mkdir()
            (repository / "release/release-matrix.json").write_text("{}\n", encoding="utf-8")
            (repository / "controller").write_text("same\n", encoding="utf-8")
            (repository / "fabric/src/e2e").mkdir(parents=True)
            (repository / "fabric/build.gradle").write_text("shared\n", encoding="utf-8")
            (repository / "fabric/src/e2e/Harness.java").write_text("shared\n", encoding="utf-8")
            subprocess.run(["git", "-C", raw, "add", "."], check=True)
            subprocess.run(["git", "-C", raw, "commit", "-qm", "base"], check=True)
            protected = subprocess.check_output(
                ["git", "-C", raw, "rev-parse", "HEAD"], text=True
            ).strip()
            (repository / "unrelated").write_text("candidate\n", encoding="utf-8")
            subprocess.run(["git", "-C", raw, "add", "unrelated"], check=True)
            subprocess.run(["git", "-C", raw, "commit", "-qm", "candidate"], check=True)
            candidate = subprocess.check_output(
                ["git", "-C", raw, "rev-parse", "HEAD"], text=True
            ).strip()
            matrix = {"artifacts": [{"loader": "fabric"}]}
            with mock.patch(
                "scripts.ci.gate_controller.PROTECTED_PATHS", ("controller",)
            ), mock.patch(
                "scripts.ci.gate_controller.load_matrix_bytes", return_value=matrix
            ), mock.patch(
                "scripts.ci.gate_controller.validate_loader_bootstrap_commit"
            ) as bootstrap:
                paths = validate_controller_parity(
                    repository,
                    protected_sha=protected,
                    candidate_sha=candidate,
                )
            self.assertIn("fabric/build.gradle", paths)
            self.assertIn("fabric/src/e2e", paths)
            bootstrap.assert_called_once_with(
                repository.resolve(),
                head_sha=candidate,
                contract_sha=protected,
            )


if __name__ == "__main__":
    unittest.main()
