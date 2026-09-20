"""API metadata is rebound independently of local qualification claims."""

import copy
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from scripts.ci.e2e_job_graph import expected_jobs
from scripts.ci import e2e_job_graph
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.pages.select_artifact import Artifact, SelectionError
from scripts.release.run_evidence import read_current_evidence
from tests.test_release_run_selection import Api, CONTROLLER, REPOSITORY, TREE, run


def raw_artifact(row):
    return {"id": row.id, "name": row.name, "size_in_bytes": row.size, "expired": row.expired,
            "created_at": row.created_at, "digest": row.digest,
            "workflow_run": {"id": row.run_id, "head_branch": row.head_branch, "head_sha": row.head_sha}}


class EvidenceApi(Api):
    def __init__(self, matrix, workflow, scope, node, event="workflow_dispatch"):
        super().__init__([run(commit=CONTROLLER, workflow=workflow, event=event)])
        self.heads["master"] = (CONTROLLER, TREE)
        self.jobs = [{"id": 100 + index, "name": item.name, "status": "completed",
                     "conclusion": item.conclusion, "run_id": 10, "run_attempt": 2}
                    for index, item in enumerate(expected_jobs(matrix, workflow, event=event,
                        source_branch="master", scope=scope, artifact_node=node))]
        names = ([f"staged-release-bundle-{CONTROLLER}-2", f"build-run-report-{CONTROLLER}-2"]
                 if workflow == "build-gate.yml" else
                 [f"e2e-input-bundle-{CONTROLLER}-2", f"packaged-e2e-{CONTROLLER}-2-aggregate"])
        self.artifacts = [Artifact(200 + index, name, 1000, False, "2026-09-19T10:01:00Z",
                                  "sha256:" + "e" * 64, 10, "master", CONTROLLER)
                          for index, name in enumerate(names)]
        self.late_jobs, self.late_artifacts = self.jobs, self.artifacts
        self.direct_mutations = {}
        self.after_jobs = lambda: None

    def jobs_for_attempt(self, run_id, attempt):
        self.calls.append(("jobs", run_id, attempt))
        rows = self.late_jobs if sum(call[0] == "jobs" for call in self.calls) > 1 else self.jobs
        self.after_jobs()
        return copy.deepcopy(rows)

    def artifacts_for_run(self, run_id):
        self.calls.append(("artifacts", run_id))
        return self.late_artifacts if sum(call[0] == "artifacts" for call in self.calls) > 1 else self.artifacts

    def get(self, path):
        self.calls.append(("artifact", path))
        row = next(row for row in self.artifacts if path.endswith("/" + str(row.id)))
        return {**raw_artifact(row), **self.direct_mutations}


class CurrentReleaseEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.matrix = Path(temporary.name) / "matrix.json"
        self.matrix.write_text(json.dumps(schema2_configuration()))
        self.digest = hashlib.sha256(self.matrix.read_bytes()).hexdigest()

    def api(self, workflow="build-gate.yml", scope="legacy", node=None, event="workflow_dispatch"):
        return EvidenceApi(self.matrix, workflow, scope, node, event)

    def read(self, api, **changes):
        return read_current_evidence(api, **{**dict(matrix_path=self.matrix, expected_matrix_sha256=self.digest,
            scope="legacy", artifact_node=None, repository=REPOSITORY, canonical_branch="master",
            controller_sha=CONTROLLER, branch="master", commit=CONTROLLER, tree=TREE,
            workflow=api.rows[0]["path"].split("/")[-1]), **changes})

    def test_exact_jobs_and_two_distinct_artifacts_for_both_producers_and_scopes(self):
        for workflow in ("build-gate.yml", "on-demand-e2e.yml"):
            for scope, node, shared, count in (("legacy", None, False, 2),
                    ("lane", "neoforge-1.21.1", False, 1), ("full", None, True, 18)):
                self.matrix.write_text(json.dumps(schema2_configuration(shared=shared)))
                self.digest = hashlib.sha256(self.matrix.read_bytes()).hexdigest()
                api = self.api(workflow, scope, node)
                result = self.read(api, scope=scope, artifact_node=node)
                self.assertEqual(count, len(result["selection"]["artifact_nodes"]))
                self.assertEqual(2, len(result["artifacts"]))
                self.assertEqual(4, sum(call[0] == "runs" for call in api.calls))
                self.assertEqual(2, sum(call[0] == "jobs" for call in api.calls))
                self.assertNotIn("qualified", result)
                self.assertNotIn("publication", result)

    def test_missing_wrong_or_changed_jobs_fail(self):
        for mutation in (lambda jobs: jobs.pop(), lambda jobs: jobs.append({**jobs[0], "id": 999}),
                lambda jobs: jobs[0].update(conclusion="failure"), lambda jobs: jobs[0].update(run_id=11),
                lambda jobs: jobs[0].update(run_attempt=1), lambda jobs: jobs[0].update(run_id=10.0)):
            api = self.api("on-demand-e2e.yml"); mutation(api.jobs)
            with self.assertRaises(SelectionError): self.read(api)
        api = self.api(); api.late_jobs = copy.deepcopy(api.jobs); api.late_jobs[0]["id"] = 999
        with self.assertRaisesRegex(SelectionError, "changed"): self.read(api)
        api = self.api(); api.late_jobs = copy.deepcopy(api.jobs)
        api.late_jobs[0]["id"], api.late_jobs[1]["id"] = api.jobs[1]["id"], api.jobs[0]["id"]
        with self.assertRaisesRegex(SelectionError, "changed"): self.read(api)

    def test_missing_duplicate_stale_expired_or_crossed_artifacts_fail(self):
        for mutation in (lambda rows: rows.pop(), lambda rows: rows.append(rows[0]),
                lambda rows: rows.__setitem__(0, replace(rows[0], run_id=11)),
                lambda rows: rows.__setitem__(0, replace(rows[0], head_sha="f" * 40)),
                lambda rows: rows.__setitem__(0, replace(rows[0], head_branch="other")),
                lambda rows: rows.__setitem__(1, replace(rows[1], expired=True)),
                lambda rows: rows.__setitem__(1, replace(rows[1], size=9 * 1024 * 1024)),
                lambda rows: rows.__setitem__(1, replace(rows[1], name=rows[1].name[:-1] + "1")),
                lambda rows: rows.__setitem__(0, replace(rows[0], created_at="2026-09-19T09:00:00Z"))):
            api = self.api(); mutation(api.artifacts)
            with self.assertRaises(SelectionError): self.read(api)
        for changes in ({"digest": "sha256:" + "d" * 64}, {"id": True}, {"size_in_bytes": 1000.0}):
            api = self.api(); api.direct_mutations = changes
            with self.assertRaises(SelectionError): self.read(api)
        api = self.api(); api.late_artifacts = [replace(row, digest="sha256:" + "d" * 64) for row in api.artifacts]
        with self.assertRaises(SelectionError): self.read(api)

    def test_external_scope_digest_and_branch_are_required(self):
        for changes in ({"scope": None}, {"scope": "full"}, {"scope": "lane"},
                {"scope": "legacy", "artifact_node": "fabric-1.20.1"},
                {"scope": "lane", "artifact_node": "fabric-1.21.7"},
                {"expected_matrix_sha256": "f" * 64}, {"branch": "release/other"}, {"canonical_branch": "other"}):
            with self.subTest(changes=changes), self.assertRaises(SelectionError): self.read(self.api(), **changes)

    def test_run_or_matrix_changes_during_metadata_reads_fail(self):
        api = self.api()
        def advance():
            api.rows = [run(11, commit=CONTROLLER)]
            api.late_rows = api.rows
        api.after_jobs = advance
        with self.assertRaises(SelectionError): self.read(api)
        api = self.api()
        api.after_jobs = lambda: self.matrix.write_text(self.matrix.read_text() + " ")
        with self.assertRaisesRegex(SelectionError, "matrix changed"): self.read(api)

    def test_scheduled_graph_comes_from_authenticated_event_and_order_is_not_identity(self):
        api = self.api("on-demand-e2e.yml", event="schedule")
        api.late_jobs = list(reversed(api.jobs)); api.late_artifacts = list(reversed(api.artifacts))
        self.assertEqual("schedule", self.read(api)["run"]["event"])
        api = self.api("on-demand-e2e.yml", "lane", "neoforge-1.21.1")
        api.rows[0]["event"] = "schedule"
        with self.assertRaises(SelectionError): self.read(api, scope="lane", artifact_node="neoforge-1.21.1")

    def test_canonical_build_push_keeps_its_event_and_e2e_push_is_rejected(self):
        api = self.api(event="push")
        self.assertEqual("push", self.read(api)["run"]["event"])
        jobs = self.matrix.parent / "jobs.json"
        jobs.write_text(json.dumps({"jobs": api.jobs}))
        for workflow, status in (("build-gate.yml", 0), ("on-demand-e2e.yml", 2)):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(status, e2e_job_graph.main(["--matrix", str(self.matrix), "--jobs", str(jobs),
                    "--workflow", workflow, "--event", "push", "--scope", "legacy", "--run-attempt", "2"]))
        api.rows[0]["event"] = "push"
        with self.assertRaises(SelectionError): self.read(api, scope="lane", artifact_node="fabric-1.20.1")
