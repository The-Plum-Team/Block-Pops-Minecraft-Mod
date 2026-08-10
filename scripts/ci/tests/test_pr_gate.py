from __future__ import annotations

import copy
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci.e2e_job_graph import expected_jobs
from scripts.ci.pr_gate import (
    CONTEXTS,
    EXACT_BASE_OWNED_PATHS,
    GateResult,
    NotEligible,
    PrGateError,
    PullIdentity,
    SelectedRun,
    _gate_result,
    _result,
    resolve_pull_identity,
    select_newest_pull_run,
    validate_exact_artifact,
    validate_pr_tree,
)


REPO = Path(__file__).resolve().parents[3]
MATRIX = REPO / "release/release-matrix.json"
MERGE = "d" * 40
HEAD = "c" * 40
BASE = "b" * 40
DEFAULT = "a" * 40


def identity(**overrides: object) -> PullIdentity:
    values: dict[str, object] = {
        "number": 17,
        "default_branch": "master",
        "default_sha": DEFAULT,
        "base_branch": "master",
        "base_sha": BASE,
        "head_branch": "feature/ui",
        "head_sha": HEAD,
        "merge_sha": MERGE,
        "merge_tree": "e" * 40,
    }
    values.update(overrides)
    return PullIdentity(**values)  # type: ignore[arg-type]


def run(
    run_id: int,
    *,
    workflow: str = "build-gate.yml",
    attempt: int = 1,
    status: str = "completed",
    conclusion: str | None = "success",
    created_at: str = "2026-08-10T10:00:00Z",
    pull: int = 17,
    branch: str = "feature/ui",
    head: str = HEAD,
) -> dict[str, object]:
    return {
        "id": run_id,
        "run_attempt": attempt,
        "path": f".github/workflows/{workflow}",
        "event": "pull_request",
        "head_branch": branch,
        "head_sha": head,
        "status": status,
        "conclusion": conclusion,
        "created_at": created_at,
        "repository": {"full_name": "owner/repo"},
        "head_repository": {"full_name": "owner/repo"},
        "pull_requests": [{"number": pull}],
    }


class RunSelectionTests(unittest.TestCase):
    def test_newest_pending_attempt_supersedes_an_older_success(self) -> None:
        old = run(10)
        current = run(
            11,
            attempt=2,
            status="in_progress",
            conclusion=None,
            created_at="2026-08-10T10:01:00Z",
        )
        selected = select_newest_pull_run(
            [old, current],
            workflow="build-gate.yml",
            repository="owner/repo",
            identity=identity(),
        )
        self.assertEqual(SelectedRun(11, 2, "in_progress", None, current["created_at"]), selected)

    def test_wrong_pr_repository_and_head_are_not_evidence(self) -> None:
        wrong_pr = run(10, pull=99)
        wrong_head = run(11, head="f" * 40)
        foreign = run(12)
        foreign["head_repository"] = {"full_name": "attacker/repo"}
        self.assertIsNone(
            select_newest_pull_run(
                [wrong_pr, wrong_head, foreign],
                workflow="build-gate.yml",
                repository="owner/repo",
                identity=identity(),
            )
        )

    def test_duplicate_current_run_attempt_fails_closed(self) -> None:
        current = run(10)
        with self.assertRaisesRegex(PrGateError, "repeats"):
            select_newest_pull_run(
                [current, copy.deepcopy(current)],
                workflow="build-gate.yml",
                repository="owner/repo",
                identity=identity(),
            )


class ArtifactAndGraphTests(unittest.TestCase):
    def artifact(self, name: str, *, run_id: int = 51) -> dict[str, object]:
        return {
            "id": 701,
            "name": name,
            "expired": False,
            "size_in_bytes": 4096,
            "digest": "sha256:" + "9" * 64,
            "workflow_run": {"id": run_id},
        }

    def test_artifact_identity_binds_tested_merge_attempt_and_owner(self) -> None:
        expected = f"staged-release-bundle-{MERGE}-3"
        artifact = self.artifact(expected)
        self.assertEqual(
            701,
            validate_exact_artifact([artifact], expected_name=expected, run_id=51)["id"],
        )
        for label, mutate in (
            ("stale merge", lambda value: value.__setitem__("name", f"staged-release-bundle-{HEAD}-3")),
            ("wrong owner", lambda value: value.__setitem__("workflow_run", {"id": 52})),
            ("expired", lambda value: value.__setitem__("expired", True)),
            ("no digest", lambda value: value.__setitem__("digest", None)),
        ):
            broken = copy.deepcopy(artifact)
            mutate(broken)
            with self.subTest(label=label), self.assertRaises(PrGateError):
                validate_exact_artifact([broken], expected_name=expected, run_id=51)

    def test_success_requires_exact_job_graph_and_artifact(self) -> None:
        selected = SelectedRun(51, 3, "completed", "success", "2026-08-10T10:00:00Z")
        graph = expected_jobs(
            MATRIX,
            "build-gate.yml",
            event="pull_request",
            source_branch="feature/ui",
        )
        jobs = [
            {
                "id": index + 1,
                "name": item.name,
                "run_attempt": 3,
                "status": "completed",
                "conclusion": item.conclusion,
            }
            for index, item in enumerate(graph)
        ]
        expected_name = f"staged-release-bundle-{MERGE}-3"

        class Api:
            def __init__(self, values: list[dict[str, object]]) -> None:
                self.values = values

            def jobs(self, _run_id: int):
                return self.values

            def artifacts(self, _run_id: int):
                return [ArtifactAndGraphTests().artifact(expected_name)]

        accepted = _gate_result(
            Api(jobs),
            kind="build",
            identity=identity(),
            matrix_path=MATRIX,
            selected=selected,
        )
        self.assertEqual("success", accepted.state)
        rejected = _gate_result(
            Api([*jobs, {"id": 99, "name": "invented", "run_attempt": 3, "status": "completed", "conclusion": "success"}]),
            kind="build",
            identity=identity(),
            matrix_path=MATRIX,
            selected=selected,
        )
        self.assertEqual("failure", rejected.state)


class PullIdentityTests(unittest.TestCase):
    class Api:
        repository = "owner/repo"

        def __init__(self, *, head_branch: str = "feature/ui") -> None:
            self.head_branch = head_branch

        def run(self, run_id: int):
            return run(run_id, branch=self.head_branch)

        def repository_record(self):
            return {"full_name": self.repository, "default_branch": "master"}

        def pull(self, number: int):
            return {
                "number": number,
                "state": "open",
                "head": {
                    "ref": self.head_branch,
                    "sha": HEAD,
                    "repo": {"full_name": self.repository},
                },
                "base": {
                    "ref": "master",
                    "sha": BASE,
                    "repo": {"full_name": self.repository},
                },
                "merge_commit_sha": MERGE,
            }

        def branch_sha(self, branch: str):
            return {"master": DEFAULT if branch == "master" and DEFAULT != BASE else BASE, self.head_branch: HEAD}.get(branch, BASE)

        def commit_identity(self, commit: str):
            self.assert_commit = commit
            return "e" * 40, (BASE, HEAD)

    def test_release_sync_heads_are_explicitly_ineligible(self) -> None:
        api = self.Api(head_branch="automation/release-sync/abc")
        with self.assertRaisesRegex(NotEligible, "release synchronization"):
            resolve_pull_identity(api, trigger_run_id=51, implementation_sha=DEFAULT)


class TreePolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Tests")
        self.git("config", "user.email", "tests@invalid.test")
        for relative in EXACT_BASE_OWNED_PATHS:
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = REPO / relative
            if source.is_file():
                shutil.copyfile(source, target)
            else:
                target.write_text("base-owned\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.check_output(
            ["git", "-C", str(self.repository), *arguments], text=True
        ).strip()

    def merged(self, mutation: str | None = None) -> PullIdentity:
        self.git("switch", "-qc", "feature")
        if mutation is None:
            (self.repository / "product.txt").write_text("candidate\n", encoding="utf-8")
        else:
            path = self.repository / mutation
            path.write_text(path.read_text("utf-8") + "candidate\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "candidate")
        head = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "master")
        self.git("merge", "--no-ff", "--no-edit", "feature")
        merge = self.git("rev-parse", "HEAD")
        tree = self.git("rev-parse", "HEAD^{tree}")
        return identity(
            default_sha=self.base,
            base_sha=self.base,
            head_sha=head,
            merge_sha=merge,
            merge_tree=tree,
        )

    def test_default_to_base_and_base_to_merge_parity_are_both_required(self) -> None:
        current = self.merged()
        with mock.patch("scripts.ci.pr_gate.validate_controller_parity") as parity:
            matrix = validate_pr_tree(self.repository, current)
        self.assertEqual((REPO / "release/release-matrix.json").read_bytes(), matrix)
        self.assertEqual(
            [
                mock.call(self.repository.resolve(), protected_sha=self.base, candidate_sha=self.base),
                mock.call(self.repository.resolve(), protected_sha=self.base, candidate_sha=current.merge_sha),
            ],
            parity.call_args_list,
        )

    def test_matrix_verification_and_version_specific_mutations_fail_closed(self) -> None:
        for path in EXACT_BASE_OWNED_PATHS:
            with self.subTest(path=path):
                # Each mutation needs an isolated repository because merged() advances master.
                self.tearDown()
                self.setUp()
                current = self.merged(path)
                with mock.patch("scripts.ci.pr_gate.validate_controller_parity"):
                    with self.assertRaisesRegex(PrGateError, "base-owned"):
                        validate_pr_tree(self.repository, current)

    def test_enrolled_opaque_release_base_is_authenticated_from_default_policy(self) -> None:
        # Start again from the integration base, then create a branch-local release matrix whose
        # name contains no Minecraft or loader assumptions.
        self.tearDown()
        self.setUp()
        integration = self.base
        self.git("switch", "-qc", "ship/aurora-ui")
        matrix_path = self.repository / "release/release-matrix.json"
        matrix = json.loads(matrix_path.read_text("utf-8"))
        matrix["branch"] = {
            "role": "release",
            "name": "ship/aurora-ui",
            "canonical": "master",
            "sync": {"enabled": True, "source": "master"},
        }
        matrix_path.write_text(json.dumps(matrix) + "\n", encoding="utf-8")
        self.git("add", "release/release-matrix.json")
        self.git("commit", "-qm", "opaque release matrix")
        release_base = self.git("rev-parse", "HEAD")
        self.git("switch", "-qc", "ordinary-change")
        (self.repository / "product.txt").write_text("candidate\n", encoding="utf-8")
        self.git("add", "product.txt")
        self.git("commit", "-qm", "ordinary release PR")
        head = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "ship/aurora-ui")
        self.git("merge", "--no-ff", "--no-edit", "ordinary-change")
        merge = self.git("rev-parse", "HEAD")
        current = identity(
            default_sha=integration,
            base_branch="ship/aurora-ui",
            base_sha=release_base,
            head_sha=head,
            merge_sha=merge,
            merge_tree=self.git("rev-parse", "HEAD^{tree}"),
        )
        with mock.patch("scripts.ci.pr_gate.validate_controller_parity") as parity:
            validate_pr_tree(self.repository, current)
        self.assertEqual(
            [
                mock.call(
                    self.repository.resolve(),
                    protected_sha=integration,
                    candidate_sha=release_base,
                ),
                mock.call(
                    self.repository.resolve(),
                    protected_sha=release_base,
                    candidate_sha=merge,
                ),
            ],
            parity.call_args_list,
        )


class ResultAndWorkflowContractTests(unittest.TestCase):
    def test_contexts_are_fixed_and_result_is_source_head_bound(self) -> None:
        value = _result(
            identity(),
            {
                "build": GateResult("success", "accepted", 1, 2),
                "e2e": GateResult("pending", "waiting", 3, 4),
            },
        )
        self.assertEqual(HEAD, value["head_sha"])
        self.assertEqual(CONTEXTS["build"], value["gates"]["build"]["context"])
        self.assertEqual(CONTEXTS["e2e"], value["gates"]["e2e"]["context"])

    def test_workflow_uses_protected_evaluator_and_fresh_status_only_app_writer(self) -> None:
        workflow = (REPO / ".github/workflows/handle-pr-gate-result.yml").read_text("utf-8")
        self.assertIn("workflow_run:", workflow)
        self.assertIn("types:\n      - requested\n      - in_progress\n      - completed", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("validate", (REPO / "scripts/ci/pr_gate.py").read_text("utf-8"))
        publish = workflow.split("  publish:", 1)[1]
        self.assertIn("environment: pr-gate", publish)
        self.assertIn("permissions: {}", publish)
        self.assertNotIn("actions/checkout", publish)
        self.assertIn(
            "actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1",
            publish,
        )
        self.assertIn("app-id: ${{ vars.PR_GATE_APP_CLIENT_ID }}", publish)
        self.assertIn("private-key: ${{ secrets.PR_GATE_APP_PRIVATE_KEY }}", publish)
        self.assertIn("permission-statuses: write", publish)
        self.assertEqual(4, publish.count('publish_status "Trusted PR /'))
        self.assertNotIn("github.token", publish)
        self.assertNotIn("pull_request_target", workflow)

    def test_codeowners_is_additional_control_plane_review_not_check_provenance(self) -> None:
        codeowners = (REPO / ".github/CODEOWNERS").read_text("utf-8")
        self.assertIn("/.github/ @AkaNebur", codeowners)
        self.assertIn("/scripts/ci/ @AkaNebur", codeowners)


if __name__ == "__main__":
    unittest.main()
