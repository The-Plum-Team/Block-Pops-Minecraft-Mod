from __future__ import annotations

import copy
import unittest
import urllib.request
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any
from unittest import mock

from scripts.ci.visual_review_queue import (
    DRAIN_WORKFLOW,
    MAX_COOLDOWN_SECONDS,
    MAX_INPUT_BYTES,
    MAX_QUEUE_INPUTS,
    PREPARE_WORKFLOW,
    SOURCE_WORKFLOW,
    INPUT_NAME,
    Artifact,
    GitHubApi,
    QueueError,
    ReviewIdentity,
    authenticate_source_identity,
    cleanup_target_for_outcome,
    parse_identity,
    parse_producer_attempt,
    plan_exact_cleanup,
    select_pending,
)


REPOSITORY = "AkaNebur/BlockPops"
DEFAULT_BRANCH = "master"
IMPLEMENTATION_SHA = "a" * 40
TESTED_SHA = "b" * 40
SOURCE_HEAD_SHA = "c" * 40
BASE_SHA = "d" * 40
NOW = datetime(2026, 8, 10, 12, 0, tzinfo=timezone.utc)


def identity_name(
    prefix: str,
    run_id: int,
    attempt: int,
    sha: str = TESTED_SHA,
    *,
    producer_attempt: int = 1,
) -> str:
    name = f"{prefix}-{run_id}-{attempt}-{sha}"
    if prefix == "visual-review-attempt":
        return f"{name}-1"
    if prefix == "visual-review-input":
        return f"{name}-{producer_attempt}"
    return name


def cooldown_name(
    run_id: int,
    attempt: int,
    *,
    ordinal: int,
    delay_seconds: int,
    sha: str = TESTED_SHA,
) -> str:
    return (
        f"visual-review-cooldown-{run_id}-{attempt}-{sha}-"
        f"{ordinal}-{delay_seconds}"
    )


def artifact(
    artifact_id: int,
    name: str,
    *,
    producer_run_id: int,
    minutes_ago: int,
    size: int = 1024,
) -> Artifact:
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        size_in_bytes=size,
        digest="sha256:" + f"{artifact_id:064x}"[-64:],
        expired=False,
        created_at=NOW - timedelta(minutes=minutes_ago),
        producer_run_id=producer_run_id,
        producer_head_branch=DEFAULT_BRANCH,
        producer_head_sha=IMPLEMENTATION_SHA,
    )


def owner(
    run_id: int,
    workflow: str,
    *,
    status: str = "completed",
    conclusion: str | None = "success",
    attempt: int = 1,
) -> dict[str, Any]:
    return {
        "id": run_id,
        "run_attempt": attempt,
        "status": status,
        "conclusion": conclusion,
        "event": "workflow_run" if workflow == PREPARE_WORKFLOW else "schedule",
        "path": workflow,
        "head_branch": DEFAULT_BRANCH,
        "head_sha": IMPLEMENTATION_SHA,
        "repository": {"full_name": REPOSITORY},
        "head_repository": {"full_name": REPOSITORY},
    }


def source_run(
    identity: ReviewIdentity,
    *,
    event: str = "pull_request_target",
    head_sha: str = SOURCE_HEAD_SHA,
) -> dict[str, Any]:
    repository_url = f"https://api.github.com/repos/{REPOSITORY}"
    value: dict[str, Any] = {
        "id": identity.source_run_id,
        "run_attempt": identity.source_run_attempt,
        "head_branch": DEFAULT_BRANCH,
        "head_sha": IMPLEMENTATION_SHA,
        "path": SOURCE_WORKFLOW,
        "event": event,
        "status": "completed",
        "conclusion": "success",
        "repository": {
            "id": 10,
            "full_name": REPOSITORY,
            "url": repository_url,
        },
        "head_repository": {
            "id": 10,
            "url": repository_url,
        },
    }
    if event == "pull_request_target":
        value["pull_requests"] = [
            {
                "id": 80,
                "number": 8,
                "url": f"{repository_url}/pulls/8",
                "head": {
                    "ref": "feature/visual-change",
                    "sha": head_sha,
                    "repo": {
                        "id": 10,
                        "url": repository_url,
                    },
                },
                "base": {
                    "ref": DEFAULT_BRANCH,
                    "sha": BASE_SHA,
                    "repo": {"id": 10, "url": repository_url},
                },
            }
        ]
    return value


class FakeApi:
    def __init__(
        self,
        artifacts: list[Artifact],
        runs: dict[int, dict[str, Any]],
        *,
        run_attempts: dict[tuple[int, int], dict[str, Any]] | None = None,
    ) -> None:
        self.artifacts = artifacts
        self.runs = runs
        self.run_attempts = run_attempts or {}
        self.sources = {
            (identity.source_run_id, identity.source_run_attempt): identity
            for value in artifacts
            if (identity := parse_identity(value.name, INPUT_NAME)) is not None
        }

    def list_artifacts(self) -> list[Artifact]:
        return list(self.artifacts)

    def get_run(self, run_id: int) -> dict[str, Any]:
        if run_id in self.runs:
            return self.runs[run_id]
        matches = [
            identity
            for (source_run_id, _attempt), identity in self.sources.items()
            if source_run_id == run_id
        ]
        if not matches:
            return {}
        return source_run(max(matches, key=lambda identity: identity.source_run_attempt))

    def get_run_attempt(self, run_id: int, run_attempt: int) -> dict[str, Any]:
        if (run_id, run_attempt) in self.run_attempts:
            return self.run_attempts[(run_id, run_attempt)]
        if run_id in self.runs:
            return self.runs[run_id]
        identity = self.sources.get((run_id, run_attempt))
        if identity is None:
            return {}
        return source_run(identity)


def select(api: FakeApi):
    return select_pending(
        api,
        repository=REPOSITORY,
        default_branch=DEFAULT_BRANCH,
        now=NOW,
    )


class VisualReviewQueueTests(unittest.TestCase):
    def test_authenticated_api_disables_environment_proxies_and_redirects(self) -> None:
        with mock.patch(
            "scripts.ci.visual_review_queue.urllib.request.build_opener",
            wraps=urllib.request.build_opener,
        ) as build_opener:
            GitHubApi(
                repository=REPOSITORY,
                token="test-token",
                api_url="https://api.github.com",
            )
        handlers = build_opener.call_args.args
        self.assertTrue(
            any(
                isinstance(handler, urllib.request.ProxyHandler)
                and handler.proxies == {}
                for handler in handlers
            )
        )
        self.assertTrue(any(type(handler).__name__ == "_NoRedirect" for handler in handlers))

    def test_identity_binds_source_run_attempt_and_tested_sha(self) -> None:
        old_attempt = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        current_attempt = artifact(
            2,
            identity_name("visual-review-input", 100, 2),
            producer_run_id=20,
            minutes_ago=80,
        )
        report_for_old = artifact(
            3,
            identity_name("visual-review", 100, 1),
            producer_run_id=30,
            minutes_ago=70,
        )
        selected = select(
            FakeApi(
                [old_attempt, current_attempt, report_for_old],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, PREPARE_WORKFLOW),
                    30: owner(30, DRAIN_WORKFLOW),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.artifact, current_attempt)
        self.assertEqual(selected.identity.source_run_attempt, 2)
        self.assertEqual(selected.identity.tested_sha, TESTED_SHA)
        self.assertNotEqual(selected.identity.tested_sha, SOURCE_HEAD_SHA)
        self.assertEqual(selected.next_attempt_ordinal, 1)
        self.assertEqual(1, parse_producer_attempt(current_attempt.name))
        self.assertIsNone(
            parse_producer_attempt(
                f"visual-review-input-100-2-{TESTED_SHA}"
            )
        )

    def test_oldest_eligible_fifo_skips_cooling_head_without_starvation(self) -> None:
        cooling = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        eligible = artifact(
            2,
            identity_name("visual-review-input", 200, 3),
            producer_run_id=20,
            minutes_ago=80,
        )
        attempt = artifact(
            3,
            identity_name("visual-review-attempt", 100, 1),
            producer_run_id=30,
            minutes_ago=5,
        )
        selected = select(
            FakeApi(
                [eligible, attempt, cooling],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, PREPARE_WORKFLOW),
                    30: owner(
                        30,
                        DRAIN_WORKFLOW,
                        status="in_progress",
                        conclusion=None,
                    ),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.artifact, eligible)

    def test_expired_cooldown_releases_original_fifo_item(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 7),
            producer_run_id=10,
            minutes_ago=90,
        )
        attempt = artifact(
            2,
            identity_name("visual-review-attempt", 100, 7),
            producer_run_id=20,
            minutes_ago=31,
        )
        selected = select(
            FakeApi(
                [pending, attempt],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.artifact, pending)
        self.assertEqual(selected.next_attempt_ordinal, 2)

    def test_cancelled_attempt_marker_still_consumes_its_ordinal(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        attempt = artifact(
            2,
            identity_name("visual-review-attempt", 100, 1),
            producer_run_id=20,
            minutes_ago=31,
        )
        selected = select(
            FakeApi(
                [pending, attempt],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, DRAIN_WORKFLOW, conclusion="cancelled"),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.next_attempt_ordinal, 2)

    def test_authenticated_six_hour_cooldown_defers_without_blocking_fifo(self) -> None:
        cooling = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        later = artifact(
            2,
            identity_name("visual-review-input", 200, 1),
            producer_run_id=20,
            minutes_ago=80,
        )
        attempt = artifact(
            3,
            identity_name("visual-review-attempt", 100, 1),
            producer_run_id=30,
            minutes_ago=31,
        )
        cooldown_artifact = artifact(
            4,
            cooldown_name(
                100,
                1,
                ordinal=1,
                delay_seconds=MAX_COOLDOWN_SECONDS,
            ),
            producer_run_id=40,
            minutes_ago=5,
        )
        api = FakeApi(
            [cooling, later, attempt, cooldown_artifact],
            {
                10: owner(10, PREPARE_WORKFLOW),
                20: owner(20, PREPARE_WORKFLOW),
                30: owner(30, DRAIN_WORKFLOW, conclusion="failure"),
                40: owner(40, DRAIN_WORKFLOW, conclusion="failure"),
            },
        )
        selected = select(api)
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.artifact, later)

        after_retry_after = select_pending(
            api,
            repository=REPOSITORY,
            default_branch=DEFAULT_BRANCH,
            now=NOW + timedelta(hours=6),
        )
        self.assertIsNotNone(after_retry_after)
        assert after_retry_after is not None
        self.assertEqual(after_retry_after.artifact, cooling)

    def test_cooldown_ordinal_skew_duplicate_and_malformed_fail_closed(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        attempt = artifact(
            2,
            identity_name("visual-review-attempt", 100, 1),
            producer_run_id=20,
            minutes_ago=31,
        )
        skewed = artifact(
            3,
            cooldown_name(100, 1, ordinal=2, delay_seconds=3600),
            producer_run_id=30,
            minutes_ago=5,
        )
        with self.assertRaisesRegex(QueueError, "cooldown ordinals"):
            select(
                FakeApi(
                    [pending, attempt, skewed],
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                        30: owner(30, DRAIN_WORKFLOW, conclusion="failure"),
                    },
                )
            )

        first = artifact(
            4,
            cooldown_name(100, 1, ordinal=1, delay_seconds=3600),
            producer_run_id=40,
            minutes_ago=6,
        )
        duplicate = artifact(
            5,
            cooldown_name(100, 1, ordinal=1, delay_seconds=7200),
            producer_run_id=50,
            minutes_ago=5,
        )
        with self.assertRaisesRegex(QueueError, "cooldown ordinal is duplicated"):
            select(
                FakeApi(
                    [pending, attempt, first, duplicate],
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                        40: owner(40, DRAIN_WORKFLOW, conclusion="failure"),
                        50: owner(50, DRAIN_WORKFLOW, conclusion="failure"),
                    },
                )
            )

        malformed = artifact(
            6,
            cooldown_name(100, 1, ordinal=1, delay_seconds=21_601),
            producer_run_id=60,
            minutes_ago=5,
        )
        with self.assertRaisesRegex(QueueError, "between 1 and"):
            select(
                FakeApi(
                    [pending, attempt, malformed],
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                        60: owner(60, DRAIN_WORKFLOW, conclusion="failure"),
                    },
                )
            )
        self.assertIsNone(select(FakeApi([pending, attempt, malformed], {})))

    def test_unauthenticated_cooldown_owner_cannot_extend_delay(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        attempt = artifact(
            2,
            identity_name("visual-review-attempt", 100, 1),
            producer_run_id=20,
            minutes_ago=31,
        )
        forged = artifact(
            3,
            cooldown_name(
                100,
                1,
                ordinal=1,
                delay_seconds=MAX_COOLDOWN_SECONDS,
            ),
            producer_run_id=30,
            minutes_ago=5,
        )
        selected = select(
            FakeApi(
                [pending, attempt, forged],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                    30: owner(30, PREPARE_WORKFLOW),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.artifact, pending)

    def test_two_exact_attempt_ordinals_produce_terminal_exhaustion(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        first_name = identity_name("visual-review-attempt", 100, 1)
        second_name = first_name[:-1] + "2"
        first = artifact(2, first_name, producer_run_id=20, minutes_ago=60)
        second = artifact(3, second_name, producer_run_id=30, minutes_ago=31)
        selected = select(
            FakeApi(
                [pending, first, second],
                {
                    10: owner(10, PREPARE_WORKFLOW),
                    20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                    30: owner(30, DRAIN_WORKFLOW, conclusion="failure"),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(selected.queue_state, "attempts_exhausted")
        self.assertEqual(selected.completed_attempts, 2)
        self.assertIsNone(selected.next_attempt_ordinal)
        self.assertIsNone(
            cleanup_target_for_outcome(selected, "attempts_exhausted")
        )

    def test_discontinuous_or_duplicate_attempt_ordinals_fail_closed(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=90,
        )
        first_name = identity_name("visual-review-attempt", 100, 1)
        second_name = first_name[:-1] + "2"
        second = artifact(2, second_name, producer_run_id=20, minutes_ago=10)
        with self.assertRaisesRegex(QueueError, "discontinuous"):
            select(
                FakeApi(
                    [pending, second],
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        20: owner(20, DRAIN_WORKFLOW, conclusion="failure"),
                    },
                )
            )
        duplicate = artifact(3, first_name, producer_run_id=30, minutes_ago=5)
        first = artifact(4, first_name, producer_run_id=40, minutes_ago=10)
        with self.assertRaisesRegex(QueueError, "duplicated"):
            select(
                FakeApi(
                    [pending, first, duplicate],
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        30: owner(30, DRAIN_WORKFLOW, conclusion="failure"),
                        40: owner(40, DRAIN_WORKFLOW, conclusion="failure"),
                    },
                )
            )

    def test_unauthenticated_owner_cannot_create_report_or_cooldown(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=30,
        )
        forged_report = artifact(
            2,
            identity_name("visual-review", 100, 1),
            producer_run_id=20,
            minutes_ago=20,
        )
        forged_owner = owner(20, DRAIN_WORKFLOW)
        forged_owner["head_repository"] = {"full_name": "attacker/fork"}
        selected = select(
            FakeApi(
                [pending, forged_report],
                {10: owner(10, PREPARE_WORKFLOW), 20: forged_owner},
            )
        )
        self.assertIsNotNone(selected)

    def test_failed_enqueue_owner_cannot_create_a_durable_queue_item(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=30,
        )
        selected = select(
            FakeApi(
                [pending],
                {
                    10: owner(
                        10,
                        PREPARE_WORKFLOW,
                        conclusion="failure",
                    )
                },
            )
        )
        self.assertIsNone(selected)

    def test_pr_source_run_attempt_is_exact_without_sha_conflation(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 2),
            producer_run_id=10,
            minutes_ago=30,
        )
        wrong_source = source_run(ReviewIdentity(100, 1, TESTED_SHA))
        with self.assertRaisesRegex(QueueError, "source run identity"):
            select(
                FakeApi(
                    [pending],
                    {10: owner(10, PREPARE_WORKFLOW), 100: wrong_source},
                )
            )

    def test_newer_mutable_source_attempt_rejects_an_existing_queue(self) -> None:
        queued = ReviewIdentity(100, 1, TESTED_SHA)
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=30,
        )
        api = FakeApi(
            [pending],
            {
                10: owner(10, PREPARE_WORKFLOW),
                100: source_run(ReviewIdentity(100, 2, TESTED_SHA)),
            },
            run_attempts={(100, 1): source_run(queued)},
        )
        selected = select(api)
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual("stale_source", selected.queue_state)
        self.assertIsNone(selected.next_attempt_ordinal)
        cleanup = cleanup_target_for_outcome(selected, "stale_source")
        self.assertIsNotNone(cleanup)
        assert cleanup is not None
        self.assertEqual(pending.artifact_id, cleanup.artifact_id)

        malformed_current = source_run(queued)
        malformed_current["head_repository"] = None
        with self.assertRaisesRegex(QueueError, "no longer current"):
            select(
                FakeApi(
                    [pending],
                    {10: owner(10, PREPARE_WORKFLOW), 100: malformed_current},
                    run_attempts={(100, 1): source_run(queued)},
                )
            )

    def test_pr_source_requires_one_exact_association_and_head_identity(self) -> None:
        identity = ReviewIdentity(100, 2, TESTED_SHA)
        valid = source_run(identity)
        self.assertTrue(
            authenticate_source_identity(valid, repository=REPOSITORY, identity=identity)
        )
        self.assertNotEqual(valid["head_sha"], identity.tested_sha)
        self.assertNotEqual(valid["pull_requests"][0]["head"]["sha"], valid["head_sha"])

        mutations: list[dict[str, Any]] = []
        missing = copy.deepcopy(valid)
        missing["pull_requests"] = []
        mutations.append(missing)
        duplicate = copy.deepcopy(valid)
        duplicate["pull_requests"].append(copy.deepcopy(duplicate["pull_requests"][0]))
        mutations.append(duplicate)
        wrong_url = copy.deepcopy(valid)
        wrong_url["pull_requests"][0]["url"] = (
            "https://api.github.com/repos/attacker/fork/pulls/8"
        )
        mutations.append(wrong_url)
        wrong_base = copy.deepcopy(valid)
        wrong_base["pull_requests"][0]["base"]["repo"]["url"] = (
            "https://api.github.com/repos/attacker/fork"
        )
        mutations.append(wrong_base)
        wrong_head_repo = copy.deepcopy(valid)
        wrong_head_repo["head_repository"]["id"] = 21
        mutations.append(wrong_head_repo)
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.assertFalse(
                    authenticate_source_identity(
                        mutation, repository=REPOSITORY, identity=identity
                    )
                )

    def test_protected_dispatch_run_head_is_not_conflated_with_tested_sha(self) -> None:
        identity = ReviewIdentity(100, 2, TESTED_SHA)
        exact = source_run(identity, event="workflow_dispatch", head_sha=TESTED_SHA)
        skewed = source_run(identity, event="workflow_dispatch", head_sha=SOURCE_HEAD_SHA)
        self.assertTrue(
            authenticate_source_identity(exact, repository=REPOSITORY, identity=identity)
        )
        self.assertTrue(
            authenticate_source_identity(
                skewed, repository=REPOSITORY, identity=identity
            )
        )

    def test_legacy_pull_request_source_is_rejected(self) -> None:
        identity = ReviewIdentity(100, 2, TESTED_SHA)
        legacy = source_run(identity)
        legacy["event"] = "pull_request"
        self.assertFalse(
            authenticate_source_identity(legacy, repository=REPOSITORY, identity=identity)
        )

    def test_duplicate_authenticated_inputs_fail_closed(self) -> None:
        name = identity_name("visual-review-input", 100, 1)
        values = [
            artifact(1, name, producer_run_id=10, minutes_ago=30),
            artifact(2, name, producer_run_id=20, minutes_ago=20),
        ]
        with self.assertRaisesRegex(QueueError, "ambiguous input"):
            select(
                FakeApi(
                    values,
                    {
                        10: owner(10, PREPARE_WORKFLOW),
                        20: owner(20, PREPARE_WORKFLOW),
                    },
                )
            )

    def test_rerun_attempts_from_one_exact_producer_coalesce_to_newest(self) -> None:
        first = artifact(
            1,
            identity_name("visual-review-input", 100, 1, producer_attempt=1),
            producer_run_id=10,
            minutes_ago=30,
        )
        second = artifact(
            2,
            identity_name("visual-review-input", 100, 1, producer_attempt=2),
            producer_run_id=10,
            minutes_ago=20,
        )
        selected = select(
            FakeApi(
                [first, second],
                {10: owner(10, PREPARE_WORKFLOW, attempt=2)},
                run_attempts={
                    (10, 1): owner(10, PREPARE_WORKFLOW, attempt=1),
                    (10, 2): owner(10, PREPARE_WORKFLOW, attempt=2),
                },
            )
        )
        self.assertIsNotNone(selected)
        assert selected is not None
        self.assertEqual(second, selected.artifact)
        self.assertEqual(2, selected.producer_run_attempt)

    def test_queue_producer_attempt_endpoint_cannot_return_another_attempt(self) -> None:
        name = identity_name(
            "visual-review-input", 100, 2, producer_attempt=2
        )
        pending = artifact(1, name, producer_run_id=10, minutes_ago=30)
        api = FakeApi(
            [pending],
            {10: owner(10, PREPARE_WORKFLOW, attempt=2)},
            run_attempts={(10, 2): owner(10, PREPARE_WORKFLOW, attempt=1)},
        )
        with self.assertRaisesRegex(QueueError, "another attempt"):
            select_pending(
                api,
                repository=REPOSITORY,
                default_branch=DEFAULT_BRANCH,
                now=NOW,
            )

    def test_duplicate_artifact_id_and_oversized_input_fail_closed(self) -> None:
        first = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=20,
        )
        with self.assertRaisesRegex(QueueError, "repeats"):
            select(FakeApi([first, replace(first, name="unrelated")], {}))
        oversized = replace(first, size_in_bytes=MAX_INPUT_BYTES + 1)
        with self.assertRaisesRegex(QueueError, "size bound"):
            select(FakeApi([oversized], {10: owner(10, PREPARE_WORKFLOW)}))

    def test_queue_entry_total_bytes_and_reserved_names_are_bounded(self) -> None:
        values = [
            artifact(
                index,
                identity_name("visual-review-input", 100 + index, 1),
                producer_run_id=1000 + index,
                minutes_ago=30 + index,
            )
            for index in range(1, MAX_QUEUE_INPUTS + 2)
        ]
        self.assertIsNone(select(FakeApi(values, {})))
        with self.assertRaisesRegex(QueueError, "entry bound"):
            select(
                FakeApi(
                    values,
                    {
                        1000 + index: owner(1000 + index, PREPARE_WORKFLOW)
                        for index in range(1, MAX_QUEUE_INPUTS + 2)
                    },
                )
            )

        large = [
            artifact(
                index,
                identity_name("visual-review-input", 200 + index, 1),
                producer_run_id=2000 + index,
                minutes_ago=30 + index,
                size=90 * 1024 * 1024,
            )
            for index in range(1, 4)
        ]
        self.assertIsNone(select(FakeApi(large, {})))
        with self.assertRaisesRegex(QueueError, "byte bound"):
            select(
                FakeApi(
                    large,
                    {
                        2000 + index: owner(2000 + index, PREPARE_WORKFLOW)
                        for index in range(1, 4)
                    },
                )
            )

        malformed = artifact(
            50,
            "visual-review-input-100-1",
            producer_run_id=50,
            minutes_ago=5,
        )
        self.assertIsNone(select(FakeApi([malformed], {})))
        with self.assertRaisesRegex(QueueError, "malformed trusted reserved"):
            select(FakeApi([malformed], {50: owner(50, PREPARE_WORKFLOW)}))

    def test_unknown_authentication_and_provider_failures_retain_input(self) -> None:
        pending = artifact(
            1,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=20,
        )
        selected = select(FakeApi([pending], {10: owner(10, PREPARE_WORKFLOW)}))
        assert selected is not None
        for outcome in (
            "unknown",
            "authentication",
            "configuration",
            "provider",
            "external_capacity",
            "transient_network",
        ):
            with self.subTest(outcome=outcome):
                self.assertIsNone(cleanup_target_for_outcome(selected, outcome))

    def test_cleanup_is_exact_and_404_idempotent(self) -> None:
        pending = artifact(
            7,
            identity_name("visual-review-input", 100, 1),
            producer_run_id=10,
            minutes_ago=20,
        )
        selected = select(FakeApi([pending], {10: owner(10, PREPARE_WORKFLOW)}))
        assert selected is not None
        target = cleanup_target_for_outcome(selected, "reviewed")
        assert target is not None
        self.assertTrue(
            plan_exact_cleanup(target, status_code=404, metadata=None).already_absent
        )
        metadata = {
            "id": pending.artifact_id,
            "name": pending.name,
            "size_in_bytes": pending.size_in_bytes,
            "digest": pending.digest,
            "expired": False,
            "created_at": pending.created_at.isoformat(),
            "workflow_run": {
                "id": pending.producer_run_id,
                "head_branch": pending.producer_head_branch,
                "head_sha": pending.producer_head_sha,
            },
        }
        self.assertTrue(
            plan_exact_cleanup(target, status_code=200, metadata=metadata).delete
        )
        with self.assertRaisesRegex(QueueError, "exact target"):
            plan_exact_cleanup(
                target,
                status_code=200,
                metadata={**metadata, "digest": "sha256:" + "f" * 64},
            )


if __name__ == "__main__":
    unittest.main()
