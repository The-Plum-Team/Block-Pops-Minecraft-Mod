"""Real ZIPs/content with simulated producer API, HTTP and fixture toolchains."""

import copy
import hashlib
import io
import unittest
import zipfile
from dataclasses import replace
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from scripts.ci.e2e_job_graph import expected_jobs
from scripts.pages import download_artifact as transport
from scripts.pages.select_artifact import Artifact
from scripts.release import plan_release as planning
from tests import test_release_content_evidence as fixtures
from tests.test_release_run_evidence import raw_artifact
from tests.test_release_run_selection import run


class ProducerApi:
    token = "fixture-token"
    api_url = "https://api.github.com"

    def __init__(self, fixture):
        identity = fixture.arguments["expected_e2e_identity"]
        self.repository = identity.repository
        self.head = (identity.commit, identity.tree)
        self.rows, self.jobs, self.artifacts, self.payloads = {}, {}, {}, {}
        self.downloads, self.job_reads = [], 0
        self.after_jobs = lambda: None
        for index, (workflow, scope) in enumerate((("build-gate.yml", "legacy"), ("on-demand-e2e.yml", "lane")), 1):
            run_id = identity.run_id + 1 if index == 1 else identity.run_id
            attempt = identity.run_attempt
            self.rows[index] = run(run_id, workflow=workflow, commit=identity.commit, workflow_id=index,
                head_sha=identity.commit, run_attempt=attempt, repository={"full_name": self.repository},
                head_repository={"full_name": self.repository})
            self.jobs[run_id] = [{"id": index * 100 + number, "name": job.name, "status": "completed",
                "conclusion": job.conclusion, "run_id": run_id, "run_attempt": attempt}
                for number, job in enumerate(expected_jobs(fixture.matrix_path, workflow, event="workflow_dispatch",
                    source_branch="master", scope=scope, artifact_node=fixture.node if index == 2 else None))]
            inputs = ([("bundle", fixture.arguments["build_stage"]), ("report", fixture.report_path)] if index == 1 else
                      [("bundle", fixture.arguments["e2e_stage"]), ("aggregate", fixture.aggregate)])
            names = ([f"staged-release-bundle-{identity.commit}-{attempt}", f"build-run-report-{identity.commit}-{attempt}"]
                     if index == 1 else [f"e2e-input-bundle-{identity.commit}-{attempt}",
                                        f"packaged-e2e-{identity.commit}-{attempt}-aggregate"])
            self.artifacts[run_id] = []
            for number, ((kind, path), name) in enumerate(zip(inputs, names, strict=True)):
                stream = io.BytesIO()
                with zipfile.ZipFile(stream, "w") as archive:
                    if path.is_file(): archive.write(path, "build-matrix-report.json")
                    else:
                        for file in sorted(path.rglob("*")):
                            if file.is_file(): archive.write(file, file.relative_to(path).as_posix())
                payload = stream.getvalue(); artifact_id = 200 + index * 10 + number
                self.payloads[artifact_id] = payload
                self.artifacts[run_id].append(Artifact(artifact_id, name, len(payload), False,
                    "2026-09-19T10:01:00Z", "sha256:" + hashlib.sha256(payload).hexdigest(), run_id, "master", identity.commit))

    def branch_head(self, branch): return self.head
    def workflow(self, workflow):
        index = 1 if workflow == "build-gate.yml" else 2
        return {"id": index, "path": ".github/workflows/" + workflow, "state": "active"}
    def runs(self, workflow_id, branch): return [copy.deepcopy(self.rows[workflow_id])]
    def run(self, run_id): return copy.deepcopy(next(row for row in self.rows.values() if row["id"] == run_id))
    def run_attempt(self, run_id, attempt): return self.run(run_id)
    def jobs_for_attempt(self, run_id, attempt):
        self.job_reads += 1; self.after_jobs()
        return copy.deepcopy(self.jobs[run_id])
    def artifacts_for_run(self, run_id): return self.artifacts[run_id]
    def get(self, route):
        return raw_artifact(next(row for rows in self.artifacts.values() for row in rows if route.endswith("/" + str(row.id))))
    def open(self, request, timeout):
        artifact_id = int(request.full_url.split("/")[-2])
        self.downloads.append(artifact_id)
        return io.BytesIO(self.payloads[artifact_id])


class ReleaseAcquisitionTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ReleaseContentEvidenceTests(methodName="runTest")
        self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.api = ProducerApi(self.fixture)
        identity = self.fixture.arguments["expected_e2e_identity"]
        self.arguments = dict(repository=self.fixture.root, matrix_path=self.fixture.matrix_path,
            expected_matrix_sha256=self.fixture.arguments["expected_matrix_sha256"], artifact_node=self.fixture.node,
            build_scope="legacy", e2e_scope="lane", source_repository=identity.repository, canonical_branch="master",
            controller_sha=identity.commit, branch="master", commit=identity.commit, tree=identity.tree)

    def acquire(self, **changes):
        opener = type("Opener", (), {})(); opener.open = self.api.open
        with patch.object(transport.urllib.request, "build_opener", return_value=opener):
            return planning.acquire_lane_evidence(self.api, **{**self.arguments, **changes})

    def test_live_metadata_four_real_zips_and_content_choose_only_e2e_production(self):
        result = self.acquire()
        self.assertEqual(4, len(self.api.downloads)); self.assertEqual(8, self.api.job_reads)
        self.assertEqual(4, len(result["paths"]))
        self.assertEqual({"build-bundle", "build-report", "e2e-bundle", "e2e-aggregate"}, set(result["downloads"]))
        content = result["content"]
        self.assertEqual(self.fixture.e2e_manifest["artifacts"][0]["production"]["sha256"], content["production"]["sha256"])
        self.assertNotEqual(content["build"]["production"]["sha256"], content["production"]["sha256"])
        self.assertEqual("legacy", content["build"]["scope"]["kind"])
        self.assertEqual("lane", content["e2e"]["scope"]["kind"])
        self.assertTrue(content["e2e"]["scope"]["partial"])
        self.assertEqual("pr-anchors", content["e2e"]["source_identity"]["projection"])
        self.assertFalse({"qualified", "publication", "migration_complete", "fresh"} & set(result))
        for path in result["paths"].values(): self.assertEqual(self.fixture.root / "build", Path(path).parent)

    def test_external_source_and_missing_scope_node_fail_before_download(self):
        for changes in ({"expected_matrix_sha256": "0" * 64}, {"commit": "0" * 40}, {"tree": "0" * 40},
                {"e2e_scope": "full"}, {"build_scope": None}, {"artifact_node": "neoforge-1.21.1"},
                {"source_repository": "foreign/repository"}, {"controller_sha": "0" * 40}):
            with self.subTest(changes=changes), self.assertRaises(planning.ReleaseEvidenceError): self.acquire(**changes)
        self.assertEqual([], self.api.downloads)

    def test_wrong_transport_bytes_and_crossed_report_role_fail_without_plan(self):
        artifact = self.api.artifacts[self.api.rows[1]["id"]][0]
        self.api.payloads[artifact.id] += b"corrupt"
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()
        self.api = ProducerApi(self.fixture)
        report = self.api.artifacts[self.api.rows[1]["id"]][1]
        bundle = self.api.artifacts[self.api.rows[1]["id"]][0]
        self.api.payloads[report.id] = self.api.payloads[bundle.id]
        self.api.artifacts[report.run_id][1] = replace(report, size=bundle.size, digest=bundle.digest)
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()

    def test_failed_producer_during_final_reads_rejects_without_fallback(self):
        def drift():
            if self.api.job_reads >= 5: self.api.rows[2]["conclusion"] = "failure"
        self.api.after_jobs = drift
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()
        self.assertEqual(4, len(self.api.downloads))
        self.assertEqual(4, len(list((self.fixture.root / "build").glob("release-evidence-*"))))

    def test_final_api_callback_cannot_replace_a_downloaded_report_with_rehashed_content(self):
        def drift():
            if self.api.job_reads == 8:
                root = next((self.fixture.root / "build").glob("release-evidence-*-build-report"))
                report = root / "build-matrix-report.json"
                report.write_bytes(report.read_bytes().replace(b'"success"', b'"failure"', 1))
        self.api.after_jobs = drift
        with self.assertRaisesRegex(planning.ReleaseEvidenceError, "downloaded bytes changed"): self.acquire()

    def test_extra_file_after_content_validation_and_crossed_e2e_attempt_are_rejected(self):
        def drift():
            if self.api.job_reads == 8:
                root = next((self.fixture.root / "build").glob("release-evidence-*-e2e-aggregate"))
                (root / "unbound.txt").write_bytes(b"not authenticated")
        self.api.after_jobs = drift
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()
        self.api = ProducerApi(self.fixture)
        run_id = self.api.rows[2]["id"]; self.api.rows[2]["run_attempt"] += 1
        for job in self.api.jobs[run_id]: job["run_attempt"] += 1
        self.api.artifacts[run_id] = [replace(row, name=row.name.replace(
            f'-{self.fixture.arguments["expected_e2e_identity"].run_attempt}',
            f'-{self.api.rows[2]["run_attempt"]}')) for row in self.api.artifacts[run_id]]
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()

    def test_schedule_cannot_narrow_to_one_lane_or_relabel_dispatch_evidence(self):
        self.api.rows[2]["event"] = "schedule"
        with self.assertRaises(planning.ReleaseEvidenceError): self.acquire()
        self.assertEqual([], self.api.downloads)

    def test_later_transport_and_final_source_checks_cannot_change_an_earlier_download(self):
        original = planning._verify_download
        @contextmanager
        def drift(path, receipt):
            with original(path, receipt) as unchanged:
                if path.name.endswith("e2e-aggregate"):
                    report = next((self.fixture.root / "build").glob("release-evidence-*-build-report"))
                    file = report / "build-matrix-report.json"
                    file.write_bytes(file.read_bytes().replace(b'"success"', b'"failure"', 1))
                yield unchanged
        with patch.object(planning, "_verify_download", side_effect=drift), self.assertRaises(planning.ReleaseEvidenceError):
            self.acquire()
        original_context, calls = planning.scoped_manifest_context, []
        def changed_context(*args, **kwargs):
            value = original_context(*args, **kwargs); calls.append(value)
            if len(calls) == 4:
                for root in (self.fixture.root / "build").glob("release-evidence-*-build-report"):
                    file = root / "build-matrix-report.json"
                    file.write_bytes(file.read_bytes().replace(b'"success"', b'"failure"', 1))
            return value
        with patch.object(planning, "scoped_manifest_context", side_effect=changed_context), self.assertRaises(
            planning.ReleaseEvidenceError
        ): self.acquire()
