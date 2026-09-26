from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci.gate_controller import (
    FORBIDDEN_PATHS,
    GateControllerError,
    PROTECTED_PATHS,
    branch_token,
    select_newest_exact_run,
    validate_exact_artifact,
    validate_controller_parity,
)


CONTROLLER_SHA = "1" * 40
TESTED_SHA = "2" * 40
TESTED_BRANCH = "automation/release-sync/abc"


def run_record(
    run_id: int,
    *,
    created: str,
    status: str = "completed",
    conclusion: str | None = "success",
    attempt: int = 1,
    display_title: str | None = None,
) -> dict[str, object]:
    return {
        "id": run_id,
        "run_attempt": attempt,
        "created_at": created,
        "status": status,
        "conclusion": conclusion,
        "path": ".github/workflows/build-gate.yml",
        "event": "workflow_dispatch",
        "display_title": display_title or f"Build gate / {TESTED_SHA}",
        "head_branch": "master",
        "head_sha": CONTROLLER_SHA,
        "repository": {"full_name": "owner/repo"},
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
            controller_branch="master",
            controller_sha=CONTROLLER_SHA,
            tested_branch=TESTED_BRANCH,
            tested_sha=TESTED_SHA,
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
            controller_branch="master",
            controller_sha=CONTROLLER_SHA,
            tested_branch=TESTED_BRANCH,
            tested_sha=TESTED_SHA,
            repository="owner/repo",
        )
        self.assertEqual((selected["id"], selected["run_attempt"]), (91, 2))
        self.assertEqual(TESTED_SHA, selected["tested_sha"])
        self.assertEqual(CONTROLLER_SHA, selected["controller_sha"])

    def test_packaged_selector_requires_its_distinct_protected_title_and_path(self) -> None:
        record = run_record(
            91,
            created="2026-08-10T01:00:00Z",
            display_title=f"Packaged E2E / {TESTED_SHA}",
        )
        record["path"] = ".github/workflows/on-demand-e2e.yml"
        selected = select_newest_exact_run(
            {"workflow_runs": [record]},
            workflow="on-demand-e2e.yml",
            controller_branch="master",
            controller_sha=CONTROLLER_SHA,
            tested_branch=TESTED_BRANCH,
            tested_sha=TESTED_SHA,
            repository="owner/repo",
        )
        self.assertEqual("Packaged E2E / " + TESTED_SHA, selected["display_title"])

    def test_wrong_repository_is_not_evidence(self) -> None:
        record = run_record(1, created="2026-08-10T01:00:00Z")
        record["head_repository"] = {"full_name": "attacker/fork"}
        with self.assertRaises(GateControllerError):
            select_newest_exact_run(
                {"workflow_runs": [record]},
                workflow="build-gate.yml",
                controller_branch="master",
                controller_sha=CONTROLLER_SHA,
                tested_branch=TESTED_BRANCH,
                tested_sha=TESTED_SHA,
                repository="owner/repo",
            )

    def test_default_controller_and_display_title_drift_are_not_evidence(self) -> None:
        for field, value in (
            ("head_branch", "old-default"),
            ("head_sha", "f" * 40),
            ("display_title", f"Build gate / {'e' * 40}"),
        ):
            record = run_record(1, created="2026-08-10T01:00:00Z")
            record[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                GateControllerError, "no exact default-controller"
            ):
                select_newest_exact_run(
                    {"workflow_runs": [record]},
                    workflow="build-gate.yml",
                    controller_branch="master",
                    controller_sha=CONTROLLER_SHA,
                    tested_branch=TESTED_BRANCH,
                    tested_sha=TESTED_SHA,
                    repository="owner/repo",
                )

    def test_non_release_sync_tested_branch_is_rejected(self) -> None:
        with self.assertRaisesRegex(GateControllerError, "isolated release-sync"):
            select_newest_exact_run(
                {"workflow_runs": [run_record(1, created="2026-08-10T01:00:00Z")]},
                workflow="build-gate.yml",
                controller_branch="master",
                controller_sha=CONTROLLER_SHA,
                tested_branch="feature/not-release-sync",
                tested_sha=TESTED_SHA,
                repository="owner/repo",
            )

    def test_duplicate_run_id_records_fail_closed_even_with_another_attempt(self) -> None:
        record = run_record(7, created="2026-08-10T01:00:00Z", attempt=2)
        repeated = dict(record)
        repeated["run_attempt"] = 3
        with self.assertRaisesRegex(GateControllerError, "repeats"):
            select_newest_exact_run(
                {"workflow_runs": [record, repeated]},
                workflow="build-gate.yml",
                controller_branch="master",
                controller_sha=CONTROLLER_SHA,
                tested_branch=TESTED_BRANCH,
                tested_sha=TESTED_SHA,
                repository="owner/repo",
            )

    def test_incomplete_matching_run_cannot_claim_a_conclusion(self) -> None:
        record = run_record(
            7,
            created="2026-08-10T01:00:00Z",
            status="in_progress",
            conclusion="success",
        )
        with self.assertRaisesRegex(GateControllerError, "unexpectedly has a conclusion"):
            select_newest_exact_run(
                {"workflow_runs": [record]},
                workflow="build-gate.yml",
                controller_branch="master",
                controller_sha=CONTROLLER_SHA,
                tested_branch=TESTED_BRANCH,
                tested_sha=TESTED_SHA,
                repository="owner/repo",
            )

    def test_branch_token_is_stable_and_collision_resistant_for_slugs(self) -> None:
        self.assertEqual(branch_token("release/a"), branch_token("release/a"))
        self.assertNotEqual(branch_token("release/a"), branch_token("release-a"))
        self.assertRegex(branch_token("release/a"), r"^[0-9a-f]{24}$")


class ArtifactIdentityTests(unittest.TestCase):
    NAME = f"staged-release-bundle-{TESTED_SHA}-3"

    @classmethod
    def artifact(cls, **overrides: object) -> dict[str, object]:
        value: dict[str, object] = {
            "id": 701,
            "name": cls.NAME,
            "expired": False,
            "size_in_bytes": 4096,
            "digest": "sha256:" + "9" * 64,
            "workflow_run": {"id": 51},
        }
        value.update(overrides)
        return value

    def test_exact_candidate_attempt_artifact_is_authenticated(self) -> None:
        value = validate_exact_artifact(
            {"artifacts": [self.artifact()]},
            expected_name=self.NAME,
            run_id=51,
        )
        self.assertEqual(701, value["id"])
        self.assertEqual(self.NAME, value["name"])
        self.assertEqual("sha256:" + "9" * 64, value["digest"])

    def test_stale_owner_digest_expiry_and_duplicate_fail_closed(self) -> None:
        invalid = (
            self.artifact(name=f"staged-release-bundle-{CONTROLLER_SHA}-3"),
            self.artifact(workflow_run={"id": 52}),
            self.artifact(digest=None),
            self.artifact(expired=True),
        )
        for artifact in invalid:
            with self.subTest(artifact=artifact), self.assertRaises(GateControllerError):
                validate_exact_artifact(
                    {"artifacts": [artifact]},
                    expected_name=self.NAME,
                    run_id=51,
                )
        duplicate = self.artifact(id=702)
        with self.assertRaisesRegex(GateControllerError, "repeat"):
            validate_exact_artifact(
                {"artifacts": [self.artifact(), duplicate]},
                expected_name=self.NAME,
                run_id=51,
            )


class ParityTests(unittest.TestCase):
    def test_new_gradle_controllers_cannot_change_outside_protected_parity(self) -> None:
        owner_rules = (Path(__file__).resolve().parents[3] / ".github/CODEOWNERS").read_text()
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            def git(*arguments):
                return subprocess.check_output(["git", "-C", raw, *arguments], text=True,
                                               stderr=subprocess.PIPE).strip()
            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "t@example.test")
            for relative in (*PROTECTED_PATHS, "release/release-matrix.json"):
                path = repository / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("baseline\n")
            git("add", ".")
            git("commit", "-qm", "baseline")
            for relative in ("gradle/build-conventions.gradle", "gradle/build-observation.init.gradle",
                             "gradle/stonecutter-branch.gradle",
                             "stonecutter.gradle"):
                self.assertIn(f"/{relative} @AkaNebur", owner_rules)
                base = git("rev-parse", "HEAD")
                (repository / relative).write_text("changed controller\n")
                git("add", relative)
                git("commit", "-qm", relative)
                head = git("rev-parse", "HEAD")
                with self.subTest(relative=relative), mock.patch(
                    "scripts.ci.gate_controller.load_trusted_gate_matrix_bytes", return_value={"artifacts": []}
                ), mock.patch("scripts.ci.gate_controller.validate_loader_bootstrap_commit"), self.assertRaisesRegex(
                    GateControllerError, relative
                ):
                    validate_controller_parity(repository, protected_sha=base, candidate_sha=head)

    def test_all_evidence_publishers_and_visual_curators_are_protected(self) -> None:
        self.assertIn("scripts/pages", PROTECTED_PATHS)
        self.assertIn("scripts/visual", PROTECTED_PATHS)
        self.assertIn("tests", PROTECTED_PATHS)
        for path in (
            ".github/CODEOWNERS",
            "build.gradle",
            "settings.gradle",
            "gradle.properties",
            "common/build.gradle",
        ):
            self.assertIn(path, PROTECTED_PATHS)
        self.assertEqual(FORBIDDEN_PATHS, (".gradle", "buildSrc"))

    def test_parity_rejects_a_tracked_gradle_cache(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            subprocess.run(["git", "init", "-q", repository], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.name", "Test"], check=True)
            subprocess.run(["git", "-C", raw, "config", "user.email", "t@example.test"], check=True)
            (repository / "release").mkdir()
            (repository / "release/release-matrix.json").write_text("{}\n", encoding="utf-8")
            (repository / "controller").write_text("same\n", encoding="utf-8")
            subprocess.run(
                ["git", "-C", raw, "add", "controller", "release/release-matrix.json"],
                check=True,
            )
            subprocess.run(["git", "-C", raw, "commit", "-qm", "base"], check=True)
            protected = subprocess.check_output(
                ["git", "-C", raw, "rev-parse", "HEAD"], text=True
            ).strip()
            payload = repository / ".gradle/loom-cache/minecraftMaven/evil.jar"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b"not generated by Loom")
            subprocess.run(
                ["git", "-C", raw, "add", "--force", "--", ".gradle"], check=True
            )
            subprocess.run(["git", "-C", raw, "commit", "-qm", "candidate"], check=True)
            candidate = subprocess.check_output(
                ["git", "-C", raw, "rev-parse", "HEAD"], text=True
            ).strip()
            with mock.patch(
                "scripts.ci.gate_controller.PROTECTED_PATHS", ("controller",)
            ), mock.patch(
                "scripts.ci.gate_controller.load_trusted_gate_matrix_bytes",
                return_value={"artifacts": []},
            ):
                with self.assertRaisesRegex(GateControllerError, "forbidden Gradle path"):
                    validate_controller_parity(
                        repository,
                        protected_sha=protected,
                        candidate_sha=candidate,
                    )

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
                "scripts.ci.gate_controller.load_trusted_gate_matrix_bytes",
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
                "scripts.ci.gate_controller.load_trusted_gate_matrix_bytes", return_value=matrix
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
