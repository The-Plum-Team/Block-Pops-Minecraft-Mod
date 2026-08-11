from __future__ import annotations

import unittest
from datetime import datetime, timezone

from scripts.visual.curate import RunIdentity
from scripts.visual.reauth import (
    ReauthenticationError,
    StaleSourceError,
    _candidate_reference_binding,
    _current_source_run,
    _current_pr_tested,
    _protected_implementation,
    _queue_owner,
    _source_controller_ancestry,
)


REPOSITORY = "AkaNebur/BlockPops"
IMPLEMENTATION = "1" * 40
CURRENT = "2" * 40
SOURCE = "3" * 40
TESTED = "4" * 40
TREE = "5" * 40
BASE = "6" * 40
MERGED = "7" * 40
DIGEST = "8" * 64


def run_identity() -> RunIdentity:
    return RunIdentity(
        repository=REPOSITORY,
        head_repository=REPOSITORY,
        head_branch="master",
        head_sha=IMPLEMENTATION,
        run_id=101,
        run_attempt=3,
        event="pull_request_target",
        created_at=datetime(2026, 8, 10, tzinfo=timezone.utc),
    )


def source_run_record(*, attempt: int = 3, head_sha: str = IMPLEMENTATION):
    return {
        "id": 101,
        "run_attempt": attempt,
        "path": ".github/workflows/on-demand-e2e.yml",
        "event": "pull_request_target",
        "status": "completed",
        "conclusion": "success",
        "head_branch": "master",
        "head_sha": head_sha,
        "created_at": "2026-08-10T00:00:00Z",
        "repository": {"full_name": REPOSITORY},
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeApi:
    repository = REPOSITORY

    def __init__(self) -> None:
        self.current = CURRENT
        self.artifact_record = {
            "id": 31,
            "name": f"visual-review-input-101-3-{TESTED}-2",
            "digest": f"sha256:{DIGEST}",
            "size_in_bytes": 1234,
            "expired": False,
            "workflow_run": {"id": 41, "head_sha": IMPLEMENTATION},
        }
        self.owner_run = {
            "id": 41,
            "run_attempt": 2,
            "path": ".github/workflows/visual-review.yml",
            "event": "workflow_run",
            "status": "completed",
            "conclusion": "success",
            "head_branch": "master",
            "head_sha": IMPLEMENTATION,
            "repository": {"full_name": REPOSITORY},
            "head_repository": {"full_name": REPOSITORY},
        }
        self.pull = {
            "state": "open",
            "merged": False,
            "merge_commit_sha": TESTED,
            "head": {
                "sha": SOURCE,
                "ref": "feature/current-ui",
                "repo": {"full_name": REPOSITORY},
            },
            "base": {
                "sha": BASE,
                "ref": "master",
                "repo": {"full_name": REPOSITORY},
            },
        }

    def artifact(self, artifact_id: int):
        self.assert_equal(31, artifact_id)
        return self.artifact_record

    def run(self, run_id: int):
        self.assert_equal(41, run_id)
        return self.owner_run

    def run_attempt(self, run_id: int, run_attempt: int):
        self.assert_equal((41, 2), (run_id, run_attempt))
        return self.owner_run

    def branch_head(self, repository: str, branch: str) -> str:
        self.assert_equal(REPOSITORY, repository)
        if branch == "master":
            return self.current
        raise AssertionError(branch)

    def compare(self, repository: str, base: str, head: str):
        self.assert_equal((REPOSITORY, IMPLEMENTATION, CURRENT), (repository, base, head))
        return {
            "status": "ahead",
            "base_commit": {"sha": IMPLEMENTATION},
            "merge_base_commit": {"sha": IMPLEMENTATION},
        }

    def pull_request(self, number: int):
        self.assert_equal(17, number)
        return self.pull

    def commit_identity(self, repository: str, commit: str):
        self.assert_equal(REPOSITORY, repository)
        if commit == TESTED:
            return TREE, (BASE, SOURCE)
        if commit == MERGED:
            return TREE, (BASE, SOURCE)
        raise AssertionError(commit)

    @staticmethod
    def assert_equal(expected, actual) -> None:
        if expected != actual:
            raise AssertionError((expected, actual))


class VisualQueueReauthenticationTests(unittest.TestCase):
    def test_queue_owner_binds_exact_attempt_artifact_and_protected_sha(self) -> None:
        api = FakeApi()
        _queue_owner(
            api=api,
            queue={
                "implementation_sha": IMPLEMENTATION,
                "producer_run_id": 41,
                "producer_run_attempt": 2,
            },
            artifact_id=31,
            artifact_name=f"visual-review-input-101-3-{TESTED}-2",
            artifact_digest=DIGEST,
            owner_run_id=41,
            owner_run_attempt=2,
        )
        api.artifact_record["digest"] = "sha256:" + "0" * 64
        with self.assertRaises(ReauthenticationError):
            _queue_owner(
                api=api,
                queue={
                    "implementation_sha": IMPLEMENTATION,
                    "producer_run_id": 41,
                    "producer_run_attempt": 2,
                },
                artifact_id=31,
                artifact_name=f"visual-review-input-101-3-{TESTED}-2",
                artifact_digest=DIGEST,
                owner_run_id=41,
                owner_run_attempt=2,
            )
        with self.assertRaises(ReauthenticationError):
            _queue_owner(
                api=FakeApi(),
                queue={
                    "implementation_sha": IMPLEMENTATION,
                    "producer_run_id": 41,
                    "producer_run_attempt": 1,
                },
                artifact_id=31,
                artifact_name=f"visual-review-input-101-3-{TESTED}-2",
                artifact_digest=DIGEST,
                owner_run_id=41,
                owner_run_attempt=2,
            )

    def test_queue_implementation_must_be_ancestor_of_current_protected_head(self) -> None:
        api = FakeApi()
        _protected_implementation(
            api,
            queue_implementation=IMPLEMENTATION,
            current_implementation=CURRENT,
        )
        api.compare = lambda *_args: {
            "status": "diverged",
            "base_commit": {"sha": IMPLEMENTATION},
            "merge_base_commit": {"sha": "0" * 40},
        }
        with self.assertRaises(ReauthenticationError):
            _protected_implementation(
                api,
                queue_implementation=IMPLEMENTATION,
                current_implementation=CURRENT,
            )

    def test_source_controller_may_be_an_exact_protected_ancestor(self) -> None:
        class SourceControllerApi:
            repository = REPOSITORY

            comparison = {
                "status": "ahead",
                "base_commit": {"sha": IMPLEMENTATION},
                "merge_base_commit": {"sha": IMPLEMENTATION},
            }

            def compare(self, repository: str, base: str, head: str):
                self.assert_equal(
                    (REPOSITORY, IMPLEMENTATION, CURRENT),
                    (repository, base, head),
                )
                return self.comparison

            @staticmethod
            def assert_equal(expected, actual) -> None:
                if expected != actual:
                    raise AssertionError((expected, actual))

        api = SourceControllerApi()
        _source_controller_ancestry(
            api,
            source_controller=IMPLEMENTATION,
            queue_implementation=CURRENT,
        )
        api.comparison = {
            "status": "diverged",
            "base_commit": {"sha": IMPLEMENTATION},
            "merge_base_commit": {"sha": "0" * 40},
        }
        with self.assertRaises(StaleSourceError):
            _source_controller_ancestry(
                api,
                source_controller=IMPLEMENTATION,
                queue_implementation=CURRENT,
            )

    def test_mutable_source_run_attempt_must_remain_current(self) -> None:
        class CurrentRunApi:
            repository = REPOSITORY

            def __init__(self) -> None:
                self.record = source_run_record()

            def run(self, run_id: int):
                if run_id != 101:
                    raise AssertionError(run_id)
                return self.record

        api = CurrentRunApi()
        self.assertEqual(run_identity(), _current_source_run(api, run_identity()))
        api.record = source_run_record(attempt=4)
        with self.assertRaisesRegex(StaleSourceError, "re-run"):
            _current_source_run(api, run_identity())

    def test_open_pr_reauthenticates_current_base_head_merge_and_tree(self) -> None:
        api = FakeApi()
        api.current = BASE
        tested = _current_pr_tested(
            api,
            run=run_identity(),
            run_record={"pull_requests": [{"number": 17}]},
            expected_tested=TESTED,
            expected_tree=TREE,
            expected_base_branch="master",
            expected_source_repository=REPOSITORY,
            expected_source_branch="feature/current-ui",
            expected_source_head=SOURCE,
        )
        self.assertEqual(TESTED, tested.tested_commit)
        api.pull["head"]["sha"] = "0" * 40
        with self.assertRaises(StaleSourceError):
            _current_pr_tested(
                api,
                run=run_identity(),
                run_record={"pull_requests": [{"number": 17}]},
                expected_tested=TESTED,
                expected_tree=TREE,
                expected_base_branch="master",
                expected_source_repository=REPOSITORY,
                expected_source_branch="feature/current-ui",
                expected_source_head=SOURCE,
            )

    def test_merged_pr_requires_exact_delivered_tree_and_current_branch_head(self) -> None:
        api = FakeApi()
        api.pull.update({"state": "closed", "merged": True, "merge_commit_sha": MERGED})
        api.current = MERGED
        tested = _current_pr_tested(
            api,
            run=run_identity(),
            run_record={"pull_requests": [{"number": 17}]},
            expected_tested=TESTED,
            expected_tree=TREE,
            expected_base_branch="master",
            expected_source_repository=REPOSITORY,
            expected_source_branch="feature/current-ui",
            expected_source_head=SOURCE,
        )
        self.assertEqual(TREE, tested.tested_tree)
        self.assertEqual(
            (BASE, True),
            _candidate_reference_binding(
                api,
                run=run_identity(),
                run_record={"pull_requests": [{"number": 17}]},
                tested=tested,
            ),
        )
        api.current = "9" * 40
        with self.assertRaisesRegex(StaleSourceError, "historical baseline"):
            _candidate_reference_binding(
                api,
                run=run_identity(),
                run_record={"pull_requests": [{"number": 17}]},
                tested=tested,
            )
        with self.assertRaises(StaleSourceError):
            _current_pr_tested(
                api,
                run=run_identity(),
                run_record={"pull_requests": [{"number": 17}]},
                expected_tested=TESTED,
                expected_tree=TREE,
                expected_base_branch="master",
                expected_source_repository=REPOSITORY,
                expected_source_branch="feature/current-ui",
                expected_source_head=SOURCE,
            )


if __name__ == "__main__":
    unittest.main()
