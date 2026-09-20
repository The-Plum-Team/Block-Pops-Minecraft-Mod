"""Observe exact source metadata without selecting or downloading an artifact."""

import copy
import unittest

from scripts.pages.select_artifact import newest_exact_source, SelectionError

REPOSITORY = "AkaNebur/BlockPops"
COMMIT, TREE, CONTROLLER = "b"*40, "c"*40, "a"*40


def run(**changes):
    return {**dict(id=100, run_attempt=1, workflow_id=77, path=".github/workflows/on-demand-e2e.yml",
        head_branch="master", head_sha=CONTROLLER, head_repository=dict(full_name=REPOSITORY),
        display_title=f"Packaged E2E / {COMMIT}", event="workflow_dispatch",
        created_at="2026-09-20T00:00:00Z", status="completed", conclusion="success"), **changes}


class Api:
    def __init__(self):
        self.records = [run()]; self.head = (COMMIT, TREE); self.calls = []

    def branch_head(self, branch):
        self.calls.append(("branch", branch)); return self.head

    def workflow(self, filename):
        self.calls.append(("workflow", filename)); return {"id": 77}

    def runs(self, workflow_id, branch):
        self.calls.append(("runs", workflow_id, branch)); return copy.deepcopy(self.records)


class NewestSourceTests(unittest.TestCase):
    def setUp(self):
        self.api = Api()

    def observe(self, **changes):
        return newest_exact_source(self.api, **{**dict(repository=REPOSITORY, branch="master", commit=COMMIT,
            tree=TREE, canonical_branch="master"), **changes})

    def test_canonical_and_release_return_only_exact_metadata_without_artifact_reads(self):
        for branch in ("master", "release/1.21.1"):
            self.api.calls.clear()
            self.assertEqual((100, 1, "master", CONTROLLER), self.observe(branch=branch))
            self.assertEqual([("branch", branch), ("workflow", "on-demand-e2e.yml"), ("runs", 77, "master")], self.api.calls)
        self.api.records = [run(head_sha="d"*40)]
        self.assertEqual((100, 1, "master", "d"*40), self.observe(branch="release/1.21.1"))

    def test_schedule_is_accepted_only_for_canonical_published_branch(self):
        self.api.records = [run(event="schedule")]
        self.assertEqual((100, 1, "master", CONTROLLER), self.observe())
        with self.assertRaises(SelectionError): self.observe(branch="release/1.21.1")

    def test_newest_failed_pending_or_cancelled_never_falls_back_to_older_success(self):
        for status, conclusion in (("completed", "failure"), ("queued", None), ("in_progress", None), ("completed", "cancelled")):
            self.api.records = [run(), run(id=101, created_at="2026-09-20T01:00:00Z", status=status, conclusion=conclusion)]
            with self.subTest(status=status, conclusion=conclusion), self.assertRaisesRegex(SelectionError, "newest"):
                self.observe()

    def test_retry_uses_attempt_order_but_dispatch_order_uses_immutable_creation(self):
        self.api.records = [run(), run(run_attempt=2)]
        self.assertEqual((100, 2, "master", CONTROLLER), self.observe())
        self.api.records[-1]["conclusion"] = "failure"
        with self.assertRaisesRegex(SelectionError, "newest"): self.observe()
        self.api.records = [run(run_attempt=9, updated_at="2026-09-21T00:00:00Z"),
                            run(id=101, created_at="2026-09-20T01:00:00Z")]
        self.assertEqual((101, 1, "master", CONTROLLER), self.observe())

    def test_wrong_controller_repository_workflow_title_and_event_are_never_sources(self):
        mutations = dict(head_branch="release/1.21.1", head_repository={"full_name": "fork/BlockPops"},
            head_sha="malformed", workflow_id=78, path=".github/workflows/other.yml",
            display_title="Packaged E2E / " + "e"*40, event="pull_request")
        for key, value in mutations.items():
            self.api.records = [run(**{key: value})]
            with self.subTest(key=key), self.assertRaises(SelectionError): self.observe()
            self.api.records.insert(0, run())
            self.assertEqual((100, 1, "master", CONTROLLER), self.observe())

    def test_duplicate_matching_identity_and_malformed_ordering_fail_closed(self):
        self.api.records = [run(), run()]
        with self.assertRaisesRegex(SelectionError, "repeats"): self.observe()
        for key, value in (("id", True), ("id", 100.0), ("run_attempt", 0), ("run_attempt", True),
                           ("created_at", "yesterday"), ("created_at", "2026-09-20T00:00:00")):
            self.api.records = [run(**{key: value})]
            with self.subTest(key=key, value=value), self.assertRaises(SelectionError): self.observe()

    def test_fresh_observation_rejects_new_failed_retry_and_source_head_drift(self):
        self.assertEqual((100, 1, "master", CONTROLLER), self.observe())
        self.api.records = [run(run_attempt=2, status="in_progress", conclusion=None)]
        with self.assertRaises(SelectionError): self.observe()
        self.api.records = [run()]; self.api.head = ("e"*40, TREE)
        with self.assertRaisesRegex(SelectionError, "branch advanced"): self.observe()
        self.api.head = (COMMIT, "e"*40)
        with self.assertRaisesRegex(SelectionError, "tree"): self.observe()


if __name__ == "__main__":
    unittest.main()
