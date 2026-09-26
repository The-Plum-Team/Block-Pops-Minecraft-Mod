"""Live API re-reading is modeled explicitly; local JSON cannot claim freshness."""

import copy
import unittest

from scripts.release.github_api import SelectionError
from scripts.release.run_selection import select_current_run

REPOSITORY = "owner/repository"
CONTROLLER, COMMIT, TREE = "a" * 40, "b" * 40, "c" * 40


def run(run_id=10, *, workflow="build-gate.yml", commit=COMMIT, **changes):
    title = "Build gate" if workflow == "build-gate.yml" else "Packaged E2E"
    return {"id": run_id, "run_attempt": 2, "created_at": "2026-09-19T10:00:00Z",
        "workflow_id": 5, "path": ".github/workflows/" + workflow,
        "display_title": title + " / " + commit, "head_branch": "master", "head_sha": CONTROLLER,
        "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
        "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY}, **changes}


class Api:
    repository = REPOSITORY

    def __init__(self, rows):
        self.rows = rows
        self.late_rows = rows
        self.calls = []
        self.current_mutations, self.attempt_mutations = {}, {}
        self.heads = {"release/example": (COMMIT, TREE), "master": (CONTROLLER, "d" * 40)}
        self.late_heads = self.heads
        self.workflow_changes = {}

    def branch_head(self, branch):
        self.calls.append(("branch", branch))
        return (self.late_heads if sum(call[0] == "runs" for call in self.calls) >= 2 else self.heads)[branch]

    def workflow(self, workflow):
        self.calls.append(("workflow", workflow))
        return {"id": 5, "path": ".github/workflows/" + workflow, "state": "active", **self.workflow_changes}

    def runs(self, workflow_id, branch):
        self.calls.append(("runs", workflow_id, branch))
        return copy.deepcopy(self.late_rows if sum(call[0] == "runs" for call in self.calls) > 1 else self.rows)

    def run(self, run_id):
        self.calls.append(("run", run_id))
        return {**copy.deepcopy(next(row for row in self.rows if row["id"] == run_id)), **self.current_mutations}

    def run_attempt(self, run_id, attempt):
        self.calls.append(("attempt", run_id, attempt))
        return {**copy.deepcopy(next(row for row in self.rows if row["id"] == run_id)), **self.attempt_mutations}


class ReleaseRunSelectionTests(unittest.TestCase):
    def select(self, api, **changes):
        return select_current_run(api, **{**dict(repository=REPOSITORY, canonical_branch="master",
            controller_sha=CONTROLLER, branch="release/example", commit=COMMIT, tree=TREE,
            workflow="build-gate.yml"), **changes})

    def test_selects_newest_exact_run_and_rereads_current_and_historical_attempt(self):
        for workflow in ("build-gate.yml", "on-demand-e2e.yml"):
            api = Api([run(9, workflow=workflow), run(10, workflow=workflow),
                       run(99, workflow=workflow, commit="e" * 40)])
            result = self.select(api, workflow=workflow)
            self.assertEqual((10, 2, CONTROLLER), (result["run_id"], result["run_attempt"], result["controller_sha"]))
            self.assertNotIn("qualified", result)
            self.assertIn(("run", 10), api.calls)
            self.assertIn(("attempt", 10, 2), api.calls)
            self.assertEqual(2, sum(call[0] == "runs" for call in api.calls))
            self.assertEqual(4, sum(call[0] == "branch" for call in api.calls))

    def test_newest_failed_pending_or_wrong_controller_never_falls_back(self):
        for changes in ({"conclusion": "failure"}, {"conclusion": "cancelled"},
                        {"status": "in_progress", "conclusion": None}, {"head_sha": "e" * 40}):
            with self.subTest(changes=changes), self.assertRaises(SelectionError):
                self.select(Api([run(9), run(10, **changes)]))

    def test_current_or_historical_rerun_and_new_dispatch_are_rejected(self):
        for attribute in ("current_mutations", "attempt_mutations"):
            for mutation in ({"run_attempt": 3}, {"id": 11}, {"conclusion": "failure"}):
                api = Api([run()]); setattr(api, attribute, mutation)
                with self.subTest(attribute=attribute, mutation=mutation), self.assertRaises(SelectionError): self.select(api)
        for newest in (run(11), run(10, run_attempt=3), run(11, conclusion="failure")):
            api = Api([run()]); api.late_rows = [newest]
            with self.subTest(newest=newest), self.assertRaises(SelectionError): self.select(api)

    def test_source_and_controller_advancement_or_wrong_workflow_fail(self):
        for branch in ("release/example", "master"):
            for early in (False, True):
                api = Api([run()]); changed = {**api.heads, branch: ("e" * 40, TREE)}
                if early: api.heads = changed
                else: api.late_heads = changed
                with self.subTest(branch=branch, early=early), self.assertRaises(SelectionError): self.select(api)
        for mutation in ({"id": True}, {"path": ".github/workflows/other.yml"}, {"state": "disabled_manually"}):
            api = Api([run()]); api.workflow_changes = mutation
            with self.subTest(mutation=mutation), self.assertRaises(SelectionError): self.select(api)

    def test_matching_malformed_records_cannot_hide_a_newer_run(self):
        for mutation in ({"id": True}, {"run_attempt": 2.0}, {"workflow_id": 5.0},
                {"repository": {"full_name": "foreign/repo"}}, {"head_repository": {}},
                {"head_sha": None}, {"path": "wrong"}, {"event": "workflow_run"},
                {"created_at": "not a timestamp"}):
            with self.subTest(mutation=mutation), self.assertRaises(SelectionError):
                self.select(Api([run(9), run(10, **mutation)]))
        for rows in ([], [run(), run()], [run(), "malformed"], [run()] * 1001):
            with self.subTest(size=len(rows)), self.assertRaises(SelectionError): self.select(Api(rows))

    def test_push_and_schedule_are_only_accepted_for_the_canonical_source(self):
        for workflow, event in (("build-gate.yml", "push"), ("on-demand-e2e.yml", "schedule")):
            with self.assertRaises(SelectionError): self.select(Api([run(workflow=workflow, event=event)]), workflow=workflow)
            api = Api([run(workflow=workflow, commit=CONTROLLER, event=event)])
            api.heads["master"] = (CONTROLLER, TREE)
            self.assertEqual(event, self.select(api, workflow=workflow, branch="master", commit=CONTROLLER)["event"])

    def test_nonidentity_api_metadata_does_not_change_the_selected_producer(self):
        api = Api([run()])
        api.current_mutations = {"repository": {"full_name": REPOSITORY, "description": "updated description"}}
        self.assertEqual(10, self.select(api)["run_id"])

    def test_canonical_source_has_one_identity_and_cannot_accept_alternating_heads(self):
        api = Api([run()])
        sequence = iter(((COMMIT, TREE), (CONTROLLER, "d" * 40)) * 2)
        api.branch_head = lambda branch: next(sequence)
        with self.assertRaisesRegex(SelectionError, "same commit"):
            self.select(api, branch="master")
        self.assertEqual([], api.calls)
        api = Api([run(commit=CONTROLLER)])
        api.heads["master"] = (CONTROLLER, TREE)
        api.late_heads = {**api.heads, "master": ("e" * 40, TREE)}
        with self.assertRaisesRegex(SelectionError, "source branch advanced"):
            self.select(api, branch="master", commit=CONTROLLER)
