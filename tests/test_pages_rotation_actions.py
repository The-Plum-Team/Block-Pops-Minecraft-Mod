"""Rotation action leases pin exact artifact IDs and re-verify identity before each deletion."""

import copy
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.pages import evidence, rotate_artifacts
from scripts.pages.rotate_artifacts import RotationError, _RotationReads, current_rotation_actions
from scripts.pages.select_artifact import Artifact
from scripts.pages.visual_anchor import visual_anchor_artifact_name

REPOSITORY = "AkaNebur/BlockPops"
BRANCH = "master"
COMMIT = "1" * 40
TREE = "2" * 40
CONTROLLER_SHA = "a" * 40
PAGES_SHA = "b" * 40
STALE_SHA = "9" * 40
WORKFLOW_ID = 77
PAGES_RUN, SOURCE_RUN, ANCHOR_RUN, STALE_RUN = 200, 101, 102, 100
NOW = datetime(2026, 9, 20, 12, 0, tzinfo=timezone.utc)
ROWS = [{"name": BRANCH, "commit": COMMIT, "tree": TREE, "matrix_sha256": "c" * 64}]
HANDOFF = {"run_id": SOURCE_RUN, "run_attempt": 2, "controller_branch": BRANCH,
           "controller_sha": CONTROLLER_SHA, "path": ".github/workflows/on-demand-e2e.yml"}


def _raw(identifier, name, run_id, head_sha, *, created, branch=BRANCH, digest="a" * 64):
    return {"id": identifier, "name": name, "expired": False, "size_in_bytes": 100,
            "created_at": created.isoformat().replace("+00:00", "Z"), "digest": "sha256:" + digest,
            "workflow_run": {"id": run_id, "head_branch": branch, "head_sha": head_sha}}


def _run(identifier, path, head_sha, *, attempt, title=None, event="workflow_dispatch",
         status="completed", conclusion="success"):
    return {"id": identifier, "workflow_id": WORKFLOW_ID, "path": path, "head_branch": BRANCH,
            "head_sha": head_sha, "display_title": title, "event": event, "status": status,
            "conclusion": conclusion, "run_attempt": attempt,
            "created_at": NOW.isoformat().replace("+00:00", "Z"),
            "head_repository": {"full_name": REPOSITORY}}


CACHE_NAME = evidence.cache_artifact_name(BRANCH, COMMIT)
KEEP_ID, COLLECTED_ID, DEPLOY_ID, PROMOTION_ID, SOURCE_ID, STALE_ID, ANCHOR_ID = 20, 19, 21, 22, 5, 10, 30
SOURCE_NAME = evidence.raw_artifact_name(BRANCH, 2)


class FakeApi:
    """Authenticated rotation reads plus recorded deletions; no network is used."""

    def __init__(self):
        self.deleted, self.owner_status = [], ("in_progress", None)
        self.raw = {row["id"]: row for row in [
            _raw(KEEP_ID, CACHE_NAME, PAGES_RUN, PAGES_SHA, created=NOW),
            _raw(COLLECTED_ID, evidence.collection_artifact_name(BRANCH, COMMIT), PAGES_RUN, PAGES_SHA, created=NOW),
            _raw(DEPLOY_ID, "github-pages", PAGES_RUN, PAGES_SHA, created=NOW),
            _raw(PROMOTION_ID, "pages-promotion", PAGES_RUN, PAGES_SHA, created=NOW),
            _raw(STALE_ID, evidence.cache_artifact_name(BRANCH, STALE_SHA), STALE_RUN, STALE_SHA,
                 created=NOW - timedelta(days=1)),
            _raw(SOURCE_ID, SOURCE_NAME, SOURCE_RUN, CONTROLLER_SHA, created=NOW - timedelta(hours=1)),
            _raw(ANCHOR_ID, visual_anchor_artifact_name(BRANCH, COMMIT, ANCHOR_RUN, 2), ANCHOR_RUN, COMMIT,
                 created=NOW - timedelta(minutes=30)),
        ]}

    def _owned(self, *identifiers):
        return [Artifact.parse(copy.deepcopy(self.raw[identifier])) for identifier in identifiers
                if identifier in self.raw]

    def workflow(self, filename):
        return {"id": WORKFLOW_ID}

    def run(self, run_id):
        if run_id == PAGES_RUN:
            status, conclusion = self.owner_status
            return _run(run_id, ".github/workflows/pages.yml", PAGES_SHA, attempt=3, event="schedule",
                        status=status, conclusion=conclusion)
        if run_id == STALE_RUN:
            return _run(run_id, ".github/workflows/pages.yml", STALE_SHA, attempt=1, event="schedule")
        sha = COMMIT if run_id == ANCHOR_RUN else CONTROLLER_SHA
        return _run(run_id, ".github/workflows/on-demand-e2e.yml", sha, attempt=2,
                    title=f"Packaged E2E / {COMMIT}")

    def run_attempt(self, run_id, attempt):
        return {**self.run(run_id), "run_attempt": attempt}

    def runs(self, workflow_id, branch):
        return [self.run(SOURCE_RUN)]

    def branch_head(self, branch):
        return COMMIT, TREE

    def artifacts_for_run(self, run_id):
        return self._owned(KEEP_ID, COLLECTED_ID, DEPLOY_ID, PROMOTION_ID)

    def all_artifacts(self):
        return self._owned(KEEP_ID, STALE_ID, ANCHOR_ID, DEPLOY_ID, PROMOTION_ID)

    def artifacts_named(self, name):
        return self._owned(SOURCE_ID) if name == SOURCE_NAME else []

    def get(self, route):
        return copy.deepcopy(self.raw[int(route.rsplit("/", 1)[1])])

    def delete_artifact(self, identifier):
        self.deleted.append(identifier)
        self.raw.pop(identifier)


class RotationReadsTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeApi()
        self.reads = _RotationReads(self.api)

    def test_repeated_reads_pin_policy_inputs_and_reject_later_drift(self):
        self.assertEqual(self.reads.run(PAGES_RUN), self.api.run(PAGES_RUN))
        self.reads.run(PAGES_RUN)
        self.api.owner_status = ("completed", "success")
        with self.assertRaisesRegex(RotationError, "changed while planning"):
            self.reads.run(PAGES_RUN)

    def test_artifact_listings_reject_malformed_duplicate_and_mutated_metadata(self):
        self.reads.all_artifacts()
        with patch.object(self.api, "all_artifacts", return_value=[{"id": 1}]):
            with self.assertRaisesRegex(RotationError, "malformed"):
                self.reads.all_artifacts()
        duplicate = self.api._owned(KEEP_ID) * 2
        with patch.object(self.api, "all_artifacts", return_value=duplicate):
            with self.assertRaisesRegex(RotationError, "duplicated"):
                self.reads.all_artifacts()
        self.api.raw[KEEP_ID]["size_in_bytes"] = 999
        with self.assertRaisesRegex(RotationError, "metadata changed"):
            self.reads.all_artifacts()

    def test_recheck_tolerates_only_this_invocation_completed_deletions(self):
        self.reads.all_artifacts()
        self.api.delete_artifact(STALE_ID)
        with self.assertRaisesRegex(RotationError, "inventory or owner changed"):
            self.reads.recheck(set())
        self.reads.recheck({STALE_ID})

    def test_pin_current_attempts_rejects_a_superseded_historical_owner(self):
        self.reads.run_attempt(PAGES_RUN, 3)
        self.reads.pin_current_attempts()
        superseded = _RotationReads(self.api)
        superseded.run_attempt(PAGES_RUN, 2)
        with self.assertRaisesRegex(RotationError, "no longer the current attempt"):
            superseded.pin_current_attempts()


class RotationActionTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeApi()
        self.manifests = {BRANCH: {"source_artifact": {"id": SOURCE_ID, "name": SOURCE_NAME,
                                                       "digest": "sha256:" + "a" * 64},
                                   "provenance": {"handoff": dict(HANDOFF)}}}
        self.rechecks = 0

    @contextmanager
    def _inputs(self, *args, **kwargs):
        def recheck(*, _after_api=None):
            self.rechecks += 1
            if _after_api is not None:
                _after_api()
        yield ROWS, self.manifests, recheck

    @contextmanager
    def lease(self):
        with patch.object(rotate_artifacts, "current_rotation_inputs", self._inputs):
            with current_rotation_actions(self.api, repository=REPOSITORY, pages_run_id=PAGES_RUN,
                    pages_run_attempt=3, implementation_sha=PAGES_SHA, canonical_branch=BRANCH,
                    inventory_path=Path("unused"), caches_root=Path("unused"), now=NOW) as value:
                yield value

    def test_plan_pins_exact_ids_and_deletes_them_in_order_without_touching_retained_caches(self):
        with self.lease() as (planned, delete_next):
            self.assertEqual([SOURCE_ID, STALE_ID, COLLECTED_ID, DEPLOY_ID, PROMOTION_ID], list(planned))
            self.assertNotIn(KEEP_ID, planned)
            self.assertEqual([], self.api.deleted)
            for expected in planned:
                self.assertEqual(expected, delete_next())
            self.assertEqual(list(planned), self.api.deleted)
            with self.assertRaisesRegex(RotationError, "exhausted"):
                delete_next()

    def test_closed_lease_refuses_further_deletions(self):
        with self.lease() as (planned, delete_next):
            self.assertEqual(planned[0], delete_next())
        with self.assertRaisesRegex(RotationError, "closed"):
            delete_next()
        self.assertEqual([planned[0]], self.api.deleted)

    def test_changed_exact_id_permit_stops_the_deletion_and_latches_failure(self):
        with self.lease() as (planned, delete_next):
            mutated = {**self.api.raw[planned[0]], "size_in_bytes": 999}
            with patch.object(self.api, "get", return_value=mutated):
                with self.assertRaisesRegex(RotationError, "permit changed"):
                    delete_next()
            self.assertEqual([], self.api.deleted)
            with self.assertRaisesRegex(RotationError, "failed"):
                delete_next()

    def test_cache_that_does_not_describe_the_newest_exact_source_is_rejected(self):
        self.manifests[BRANCH]["provenance"]["handoff"]["run_attempt"] = 3
        with self.assertRaisesRegex(RotationError, "newest exact source"):
            with self.lease():
                self.fail("stale rotation cache was leased")
        self.assertEqual([], self.api.deleted)

    def test_every_action_rechecks_the_input_lease_before_deleting(self):
        with self.lease() as (planned, delete_next):
            opened = self.rechecks
            delete_next()
            self.assertEqual(opened + 1, self.rechecks)
        self.assertEqual(1, len(self.api.deleted))


if __name__ == "__main__":
    unittest.main()
