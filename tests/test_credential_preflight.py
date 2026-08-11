from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
import urllib.error
from argparse import Namespace
from pathlib import Path
from unittest import mock

from scripts.visual.credential_preflight import (
    HANDOFF_KEYS,
    Api,
    PreflightError,
    StaleError,
    TransientError,
    _reference_identity,
    _source_identity,
    preflight,
)


REPOSITORY = "AkaNebur/BlockPops"
REVIEWER_SHA = "a" * 40
QUEUE_SHA = "b" * 40
DIGEST = "d" * 64
CONTROLLER_SHA = "9" * 40
SOURCE_SHA = "c" * 40
BASE_SHA = "d" * 40
TESTED_SHA = "e" * 40
TREE_SHA = "f" * 40
MASTER_TOKEN = hashlib.sha256(b"master").hexdigest()[:24]


class _Api:
    def __init__(
        self, *, owner_sha: str = REVIEWER_SHA, comparison_status: str = "ahead"
    ) -> None:
        self.repository = REPOSITORY
        self.owner_sha = owner_sha
        self.comparison_status = comparison_status

    def branch(self, name: str) -> str:
        if name != "master":
            raise AssertionError(name)
        return REVIEWER_SHA

    def compare(self, base: str, head: str) -> dict[str, object]:
        if (base, head) != (QUEUE_SHA, REVIEWER_SHA):
            raise AssertionError((base, head))
        merge_base = QUEUE_SHA if self.comparison_status == "ahead" else "0" * 40
        return {
            "status": self.comparison_status,
            "base_commit": {"sha": QUEUE_SHA},
            "merge_base_commit": {"sha": merge_base},
        }

    def repo(self, route: str):
        if route == "/actions/artifacts/77":
            return {
                "id": 77,
                "name": "visual-review-admitted-test",
                "expired": False,
                "digest": "sha256:" + DIGEST,
                "size_in_bytes": 1024,
                "workflow_run": {"id": 900},
            }
        if route == "/actions/runs/900":
            return {
                "id": 900,
                "run_attempt": 2,
                "path": ".github/workflows/visual-review-drain.yml",
                "head_branch": "master",
                "head_sha": self.owner_sha,
                "repository": {"full_name": REPOSITORY},
                "event": "schedule",
                "status": "in_progress",
            }
        raise AssertionError(route)


class _SourceApi:
    repository = REPOSITORY

    def __init__(self) -> None:
        self.current_attempt = 1
        self.comparison_status = "ahead"
        self.merged = False
        self.delivered = REVIEWER_SHA

    def _run(self, attempt: int) -> dict[str, object]:
        return {
            "id": 10,
            "run_attempt": attempt,
            "path": ".github/workflows/on-demand-e2e.yml",
            "event": "pull_request_target",
            "head_branch": "master",
            "head_sha": CONTROLLER_SHA,
            "status": "completed",
            "conclusion": "success",
            "repository": {"full_name": REPOSITORY},
            "head_repository": {"full_name": REPOSITORY},
            "pull_requests": [{"number": 17}],
        }

    def repo(self, route: str):
        if route == "/actions/runs/10/attempts/1":
            return self._run(1)
        if route == "/actions/runs/10":
            return self._run(self.current_attempt)
        if route == "/pulls/17":
            return {
                "state": "closed" if self.merged else "open",
                "merged": self.merged,
                "merge_commit_sha": self.delivered if self.merged else TESTED_SHA,
                "head": {
                    "sha": SOURCE_SHA,
                    "ref": "feature/visual",
                    "repo": {"full_name": REPOSITORY},
                },
                "base": {
                    "sha": BASE_SHA,
                    "ref": "master",
                    "repo": {"full_name": REPOSITORY},
                },
            }
        raise AssertionError(route)

    def compare(self, base: str, head: str) -> dict[str, object]:
        if (base, head) != (CONTROLLER_SHA, QUEUE_SHA):
            raise AssertionError((base, head))
        merge_base = CONTROLLER_SHA if self.comparison_status == "ahead" else "0" * 40
        return {
            "status": self.comparison_status,
            "base_commit": {"sha": CONTROLLER_SHA},
            "merge_base_commit": {"sha": merge_base},
        }

    def branch(self, name: str) -> str:
        if name != "master":
            raise AssertionError(name)
        return self.delivered if self.merged else BASE_SHA

    def commit(self, commit: str) -> tuple[str, tuple[str, ...]]:
        if commit not in {TESTED_SHA, self.delivered}:
            raise AssertionError(commit)
        return TREE_SHA, (BASE_SHA, SOURCE_SHA)


class _ReferenceApi:
    repository = REPOSITORY

    def __init__(self) -> None:
        self.current = REVIEWER_SHA
        self.expired = False

    def branch(self, name: str) -> str:
        if name != "master":
            raise AssertionError(name)
        return self.current

    def commit(self, commit: str) -> tuple[str, tuple[str, ...]]:
        if commit != BASE_SHA:
            raise AssertionError(commit)
        return TREE_SHA, ("1" * 40,)

    def repo(self, route: str):
        if route.startswith(
            "/actions/workflows/on-demand-e2e.yml/runs?branch=master&head_sha="
        ):
            return {
                "total_count": 1,
                "workflow_runs": [
                    {
                        "id": 20,
                        "run_attempt": 1,
                        "path": ".github/workflows/on-demand-e2e.yml",
                        "repository": {"full_name": REPOSITORY},
                        "head_repository": {"full_name": REPOSITORY},
                        "head_branch": "master",
                        "head_sha": BASE_SHA,
                        "display_title": f"Packaged E2E / {BASE_SHA}",
                        "event": "schedule",
                        "status": "completed",
                        "conclusion": "success",
                        "created_at": "2026-08-10T00:00:00Z",
                    }
                ],
            }
        if route == "/actions/runs/20/jobs?filter=all&per_page=100":
            return {
                "jobs": [
                    {
                        "name": "Resolve authoritative packaged matrix",
                        "run_attempt": 1,
                    }
                ]
            }
        if route == "/actions/artifacts/55":
            return {
                "id": 55,
                "name": f"visual-anchor-v1-{MASTER_TOKEN}--{BASE_SHA}-20-1",
                "expired": self.expired,
                "digest": "sha256:" + "6" * 64,
                "size_in_bytes": 4096,
                "workflow_run": {
                    "id": 20,
                    "head_branch": "master",
                    "head_sha": BASE_SHA,
                },
            }
        raise AssertionError(route)


def _source_queue() -> dict[str, object]:
    return {
        "implementation_sha": QUEUE_SHA,
        "source_run_id": 10,
        "source_run_attempt": 1,
        "source_head_sha": SOURCE_SHA,
        "tested_sha": TESTED_SHA,
        "tested_tree": TREE_SHA,
    }


def _source_candidate() -> dict[str, object]:
    return {
        "event": "pull_request_target",
        "run_id": 10,
        "run_attempt": 1,
        "repository": REPOSITORY,
        "source_head_repository": REPOSITORY,
        "source_head_branch": "feature/visual",
        "source_head_commit": SOURCE_SHA,
        "base_branch": "master",
        "tested_commit": TESTED_SHA,
        "tested_tree": TREE_SHA,
    }


def _reference_queue() -> dict[str, object]:
    return {
        "reference_run_id": 20,
        "reference_run_attempt": 1,
        "reference_sha": BASE_SHA,
    }


def _reference_source() -> dict[str, object]:
    return {
        "repository": REPOSITORY,
        "source_head_repository": REPOSITORY,
        "source_head_branch": "master",
        "source_head_commit": BASE_SHA,
        "base_branch": "master",
        "tested_commit": BASE_SHA,
        "tested_tree": TREE_SHA,
        "workflow_path": ".github/workflows/on-demand-e2e.yml",
        "event": "schedule",
        "run_id": 20,
        "run_attempt": 1,
        "artifact_id": 55,
        "artifact_name": f"visual-anchor-v1-{MASTER_TOKEN}--{BASE_SHA}-20-1",
        "artifact_sha256": "6" * 64,
    }


def _manifest() -> dict[str, object]:
    value: dict[str, object] = {
        "schema_version": 1,
        "purpose": "claude-advisory-visual-tool-handoff",
        "reviewer_implementation_sha": REVIEWER_SHA,
        "queue_manifest_sha256": "1" * 64,
        "client_sha256": "2" * 64,
        "preflight_sha256": "3" * 64,
        "sonnet_prompt_sha256": "4" * 64,
        "fable_prompt_sha256": "5" * 64,
        "inventory": [],
        "total_bytes": 1,
    }
    assert set(value) == HANDOFF_KEYS
    return value


class CredentialPreflightTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="blockpops-preflight-")
        self.handoff = Path(self.temporary.name) / "handoff"
        (self.handoff / "queue" / "capsule").mkdir(parents=True)
        self.manifest = _manifest()
        self._write_manifest()
        capsule = {
            "candidate_source": {
                "event": "pull_request_target",
                "run_id": 10,
                "run_attempt": 1,
                "repository": REPOSITORY,
                "source_head_repository": REPOSITORY,
                "source_head_branch": "feature/visual",
                "source_head_commit": "c" * 40,
                "base_branch": "master",
                "tested_commit": "e" * 40,
                "tested_tree": "f" * 40,
            },
            "reference_source": {},
        }
        capsule_payload = (
            json.dumps(capsule, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode()
        (self.handoff / "queue" / "capsule" / "visual-capsule.json").write_bytes(
            capsule_payload
        )
        (self.handoff / "queue" / "queue.json").write_text(
            json.dumps(
                {
                    "implementation_sha": QUEUE_SHA,
                    "source_run_id": 10,
                    "source_run_attempt": 1,
                    "source_head_sha": "c" * 40,
                    "tested_sha": "e" * 40,
                    "tested_tree": "f" * 40,
                    "capsule_manifest_sha256": hashlib.sha256(capsule_payload).hexdigest(),
                    "reference_run_id": 20,
                    "reference_run_attempt": 1,
                    "reference_sha": REVIEWER_SHA,
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_manifest(self) -> None:
        self.payload = (
            json.dumps(self.manifest, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode()
        (self.handoff / "handoff.json").write_bytes(self.payload)

    def _args(self) -> Namespace:
        return Namespace(
            repository=REPOSITORY,
            handoff=self.handoff,
            manifest_sha256=hashlib.sha256(self.payload).hexdigest(),
            workflow_sha=REVIEWER_SHA,
            artifact_id=77,
            artifact_name="visual-review-admitted-test",
            artifact_digest=DIGEST,
            owner_run_id=900,
            owner_run_attempt=2,
        )

    def _environment(self):
        return mock.patch.dict(
            os.environ,
            {
                "GITHUB_REF": "refs/heads/master",
                "GITHUB_SHA": REVIEWER_SHA,
                "PREFLIGHT_GITHUB_TOKEN": "github-test-token",
            },
            clear=False,
        )

    def test_current_reviewer_sha_is_distinct_from_historical_queue_sha(self) -> None:
        api = _Api()
        with self._environment(), mock.patch(
            "scripts.visual.credential_preflight.Api", return_value=api
        ), mock.patch(
            "scripts.visual.credential_preflight._source_identity"
        ) as source, mock.patch(
            "scripts.visual.credential_preflight._reference_identity"
        ) as reference:
            preflight(self._args())
        source.assert_called_once()
        reference.assert_called_once()

    def test_historical_queue_sha_must_be_an_ancestor_of_current_reviewer(self) -> None:
        api = _Api(comparison_status="diverged")
        with self._environment(), mock.patch(
            "scripts.visual.credential_preflight.Api", return_value=api
        ), mock.patch("scripts.visual.credential_preflight._source_identity"), mock.patch(
            "scripts.visual.credential_preflight._reference_identity"
        ):
            with self.assertRaisesRegex(StaleError, "not an ancestor"):
                preflight(self._args())

    def test_workflow_owner_and_manifest_skew_fail_closed(self) -> None:
        with self._environment(), mock.patch(
            "scripts.visual.credential_preflight.Api", return_value=_Api(owner_sha=QUEUE_SHA)
        ), mock.patch("scripts.visual.credential_preflight._source_identity"), mock.patch(
            "scripts.visual.credential_preflight._reference_identity"
        ):
            with self.assertRaises(StaleError):
                preflight(self._args())

        self.manifest["unexpected"] = True
        self._write_manifest()
        with self._environment():
            with self.assertRaises(PreflightError):
                preflight(self._args())

    def test_transient_github_failure_is_distinct_from_authentication(self) -> None:
        api = Api(REPOSITORY, "github-test-token")
        error = urllib.error.HTTPError(
            "https://api.github.com/repos/test",
            503,
            "unavailable",
            {},
            None,
        )
        api.opener = mock.Mock()
        api.opener.open.side_effect = error
        with self.assertRaises(TransientError):
            api.get("/repos/AkaNebur/BlockPops")

    def test_source_controller_may_be_an_exact_ancestor_of_queue_code(self) -> None:
        api = _SourceApi()
        _source_identity(api, _source_queue(), _source_candidate())
        api.comparison_status = "diverged"
        with self.assertRaisesRegex(StaleError, "not an ancestor"):
            _source_identity(api, _source_queue(), _source_candidate())

    def test_merged_pr_exposes_only_its_exact_parent_zero_as_historical_reference(self) -> None:
        api = _SourceApi()
        api.merged = True
        self.assertEqual(
            (BASE_SHA, REVIEWER_SHA),
            _source_identity(api, _source_queue(), _source_candidate()),
        )

    def test_historical_reference_run_and_anchor_remain_exact_at_paid_boundary(self) -> None:
        api = _ReferenceApi()
        _reference_identity(
            api,
            _reference_queue(),
            _reference_source(),
            historical_binding=(BASE_SHA, REVIEWER_SHA),
        )
        with self.assertRaisesRegex(StaleError, "canonical branch advanced"):
            _reference_identity(
                api,
                _reference_queue(),
                _reference_source(),
                historical_binding=None,
            )
        api.current = "0" * 40
        with self.assertRaisesRegex(StaleError, "no longer the current"):
            _reference_identity(
                api,
                _reference_queue(),
                _reference_source(),
                historical_binding=(BASE_SHA, REVIEWER_SHA),
            )
        api.current = REVIEWER_SHA
        api.expired = True
        with self.assertRaisesRegex(StaleError, "anchor artifact"):
            _reference_identity(
                api,
                _reference_queue(),
                _reference_source(),
                historical_binding=(BASE_SHA, REVIEWER_SHA),
            )

    def test_mutable_source_attempt_must_still_equal_the_queued_attempt(self) -> None:
        api = _SourceApi()
        api.current_attempt = 2
        with self.assertRaisesRegex(StaleError, "re-run"):
            _source_identity(api, _source_queue(), _source_candidate())

        api.current_attempt = 1
        current = api._run(1)
        current["head_repository"] = None
        original_repo = api.repo
        api.repo = (
            lambda route: current
            if route == "/actions/runs/10"
            else original_repo(route)
        )
        with self.assertRaisesRegex(PreflightError, "malformed"):
            _source_identity(api, _source_queue(), _source_candidate())


if __name__ == "__main__":
    unittest.main()
