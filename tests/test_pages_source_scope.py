"""Authenticate scope against API fixtures; this does not qualify game evidence."""

import contextlib
import copy
from dataclasses import replace
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.pages import authenticate_source as source, evidence
from scripts.pages.select_artifact import Artifact


class ScopedSourceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.matrix_path = self.root / "matrix.json"
        self.contract_path = self.root / "contract.json"
        self.contract_path.write_bytes(source.DEFAULT_CONTRACT.read_bytes())
        patcher = patch.object(source, "DEFAULT_CONTRACT", self.contract_path)
        patcher.start(); self.addCleanup(patcher.stop)
        self.prepare()

    def prepare(self, *, shared=False, schema1=False, event="workflow_dispatch", attested=False, compact=False):
        matrix = schema1_matrix() if schema1 else schema2_configuration(shared=shared)
        self.matrix_path.write_text(json.dumps(matrix))
        branch = matrix["branch"]["name"]
        self.expected = dict(branch=branch, commit="b" * 40, tree="c" * 40,
            matrix_sha256=hashlib.sha256(self.matrix_path.read_bytes()).hexdigest())
        handoff = dict(path=evidence.E2E_WORKFLOW, run_id=101, run_attempt=2,
            controller_branch="master", controller_sha="a" * 40)
        packaged = dict(handoff, branch=branch, commit="b" * 40, tree="c" * 40)
        if attested:
            packaged.update(run_id=99, run_attempt=3, branch="automation/release-sync/test", commit="d" * 40)
        self.manifest = dict(schema_version=1 if schema1 else 2,
            kind=evidence.COMPACT_KIND if compact else evidence.RAW_KIND,
            provenance=dict(self.expected, repository="AkaNebur/BlockPops", handoff=handoff,
                packaged=packaged, contract_sha256=source.default_contract().sha256))
        scope = "full" if shared else "legacy"
        projection = "scheduled-anchors" if event == "schedule" and not attested else "pr-anchors"
        if not schema1:
            nodes = ([row["artifact_node"] for row in matrix["artifacts"]] if shared
                     else matrix["migration"]["legacy_nodes"])
            self.manifest["aggregate_scope"] = dict(kind=scope, selected_nodes=nodes,
                target_nodes=[row["artifact_node"] for row in matrix["targets"]],
                migration_mode=matrix["migration"]["mode"], partial=not shared, projection=projection,
                scenarios=list(source.default_contract().scenarios_for_profile("release")))
        for filename in ("pages-evidence.json", "manifest.json"):
            (self.root / filename).unlink(missing_ok=True)
        self.manifest_path = self.root / ("manifest.json" if compact else "pages-evidence.json")
        self.write()
        name = (evidence.cache_artifact_name(branch, self.expected["commit"]) if compact
                else evidence.raw_artifact_name(branch, 2))
        self.selected = Artifact(501, name, 100, False, "2026-09-19T00:00:00Z", "sha256:" + "e" * 64,
            202 if compact else 101, "master", "a" * 40)
        self.arguments = dict(repository="AkaNebur/BlockPops", canonical_branch="master",
            matrix_path=self.matrix_path, evidence_root=self.root, selected_kind="compact" if compact else "raw",
            selected_artifact_id=501, selected_artifact_name=name, selected_artifact_digest=self.selected.digest,
            selected_run_id=self.selected.run_id, selected_run_attempt=2,
            expected_handoff_run_id=101, expected_handoff_run_attempt=2)
        if not schema1: self.arguments.update(scope=scope, expected=self.expected)
        self.runs = {}
        for record in (packaged, handoff):
            self.runs[record["run_id"]] = dict(id=record["run_id"], run_attempt=record["run_attempt"],
                workflow_id=8, path=evidence.E2E_WORKFLOW, head_branch="master", head_sha="a" * 40,
                display_title=f"Packaged E2E / {record.get('commit', self.expected['commit'])}",
                event="workflow_dispatch" if attested and record is handoff else event,
                head_repository={"full_name": "AkaNebur/BlockPops"}, status="completed", conclusion="success")
        if compact:
            self.runs[202] = dict(self.runs[101], id=202, workflow_id=9, path=source.PAGES_WORKFLOW, event="workflow_run")
        self.jobs = [{"id": index + 1000, "name": row.name, "run_attempt": packaged["run_attempt"],
            "status": "completed", "conclusion": row.conclusion} for index, row in enumerate(
                source._expected_jobs(self.matrix_path, event, source_branch=packaged["branch"]))]
        fixture = self
        class MockGitHub:
            def workflow(self, filename): return {"id": 9 if filename == "pages.yml" else 8}
            def run_attempt(self, run_id, attempt): return copy.deepcopy(fixture.runs[run_id])
            def branch_head(self, branch): return "b" * 40, "c" * 40
            def commit_tree(self, commit): return "c" * 40
            def artifacts_for_run(self, run_id): return [fixture.selected]
            def jobs_for_attempt(self, run_id, attempt):
                if attested and run_id == 101:
                    return [dict(id=900, name="Attest / Verify exact tested tree", run_attempt=2,
                        status="completed", conclusion="success")]
                return copy.deepcopy(fixture.jobs)
        self.api = MockGitHub()

    def write(self):
        self.manifest_path.write_text(json.dumps(self.manifest))

    def authenticate(self, **overrides):
        return source.authenticate(self.api, **{**self.arguments, **overrides})

    def test_direct_attested_and_compact_owners_preserve_exact_external_coverage(self):
        for shared, event, attested, compact in ((False, "workflow_dispatch", False, False),
                (False, "schedule", False, False), (True, "workflow_dispatch", False, False),
                (True, "workflow_dispatch", True, False), (False, "workflow_dispatch", True, True)):
            with self.subTest(shared=shared, event=event, attested=attested, compact=compact):
                self.prepare(shared=shared, event=event, attested=attested, compact=compact)
                result = self.authenticate()
                self.assertEqual(dict(self.expected, contract_sha256=source.default_contract().sha256), result["source"])
                self.assertEqual(self.manifest["aggregate_scope"], result["aggregate_scope"])
                self.assertEqual(attested, result["attested"])
                self.assertEqual(99 if attested else 101, result["packaged_run_id"])

    def test_schema1_result_is_unchanged_and_cannot_accept_scoped_options(self):
        self.prepare(schema1=True)
        result = self.authenticate()
        self.assertEqual({"schema_version", "attested", "handoff_run_id", "packaged_run_id",
                          "packaged_job_graph_sha256"}, set(result))
        self.assertEqual((1, False, 101, 101), tuple(result[key] for key in
            ("schema_version", "attested", "handoff_run_id", "packaged_run_id")))
        with self.assertRaises(source.SourceAuthenticationError): self.authenticate(scope="legacy")
        with self.assertRaises(source.SourceAuthenticationError): self.authenticate(manifest_snapshot=(self.manifest, "raw", b""))

    def test_missing_external_bindings_crossed_scopes_and_unresolved_full_fail(self):
        for override in ({"scope": None}, {"scope": "full"}, {"scope": "lane"}, {"scope": []},
                         {"expected": None}, {"expected": {}}, {"expected": dict(self.expected, extra=True)}):
            with self.subTest(override=override), self.assertRaises(source.SourceAuthenticationError):
                self.authenticate(**override)
        for key in self.expected:
            with self.subTest(key=key), self.assertRaises(source.SourceAuthenticationError):
                self.authenticate(expected={**self.expected, key: "f" * (64 if key == "matrix_sha256" else 40)})

    def test_manifest_scope_projection_types_and_contract_cannot_select_authority(self):
        original = copy.deepcopy(self.manifest)
        mutations = [lambda m: m.update(schema_version=2.0),
            lambda m: m["aggregate_scope"].update(partial=1),
            lambda m: m["aggregate_scope"].update(kind="full"),
            lambda m: m["aggregate_scope"].update(projection="scheduled-anchors"),
            lambda m: m["aggregate_scope"]["selected_nodes"].pop(),
            lambda m: m["aggregate_scope"]["target_nodes"].pop(),
            lambda m: m["aggregate_scope"].update(scenarios=[]),
            lambda m: m["provenance"].update(contract_sha256="f" * 64),
            lambda m: m["provenance"].update(repository="Other/Repository")]
        for mutation in mutations:
            self.manifest = copy.deepcopy(original); mutation(self.manifest); self.write()
            with self.assertRaises(source.SourceAuthenticationError): self.authenticate()

    def test_wrong_run_owner_attempt_artifact_and_missing_jobs_fail(self):
        for key, value in (("event", "push"), ("head_sha", "f" * 40), ("run_attempt", 3),
                           ("run_attempt", 2.0), ("id", 101.0), ("workflow_id", 8.0),
                           ("conclusion", "failure"), ("display_title", "wrong source")):
            self.prepare(); self.runs[101][key] = value
            with self.subTest(key=key), self.assertRaises(source.SourceAuthenticationError): self.authenticate()
        for field, value in (("digest", "sha256:" + "f" * 64), ("run_id", 99), ("expired", True)):
            self.prepare(); self.selected = replace(self.selected, **{field: value})
            with self.subTest(field=field), self.assertRaises(source.SourceAuthenticationError): self.authenticate()
        self.prepare(); self.jobs.pop()
        with self.assertRaises(source.SourceAuthenticationError): self.authenticate()
        self.prepare()
        with self.assertRaises(source.SourceAuthenticationError): self.authenticate(expected_handoff_run_id=102)
        with patch.object(self.api, "branch_head", side_effect=[("b" * 40, "c" * 40), ("f" * 40, "c" * 40)]):
            with self.assertRaisesRegex(source.SourceAuthenticationError, "advanced"): self.authenticate()

    def test_attested_schedule_and_conflicting_direct_event_cannot_be_relabelled_pr(self):
        self.prepare(event="schedule", attested=True)
        self.manifest["provenance"]["packaged"]["branch"] = "master"; self.write()
        with self.assertRaisesRegex(source.SourceAuthenticationError, "projection"): self.authenticate()
        self.prepare()
        run = self.runs[101]
        with patch.object(self.api, "run_attempt", side_effect=[dict(run, event="schedule"), run]):
            with self.assertRaisesRegex(source.SourceAuthenticationError, "projection"): self.authenticate()

    def test_matrix_snapshot_is_shared_and_original_inputs_are_rechecked(self):
        paths = []
        graph, coverage = source._expected_jobs, source._raw_matrix_context
        def jobs(path, *args, **kwargs):
            paths.append(path)
            self.matrix_path.write_bytes(b"invalid during graph read")
            return graph(path, *args, **kwargs)
        def context(path, *args, **kwargs):
            paths.append(path)
            return coverage(path, *args, **kwargs)
        with patch.object(source, "_expected_jobs", side_effect=jobs), patch.object(source, "_raw_matrix_context", side_effect=context):
            with self.assertRaisesRegex(source.SourceAuthenticationError, "final Pages matrix"): self.authenticate()
        self.assertEqual(2, len(paths)); self.assertEqual(paths[0], paths[1])
        self.assertNotEqual(self.matrix_path, paths[0]); self.assertFalse(paths[0].exists())
        for label in ("matrix", "contract", "manifest"):
            self.prepare()
            path = {"matrix": self.matrix_path, "contract": self.contract_path, "manifest": self.manifest_path}[label]
            original = path.read_bytes()
            def changed(run_id):
                path.write_bytes(original + b" ")
                return [self.selected]
            with patch.object(self.api, "artifacts_for_run", side_effect=changed):
                with self.subTest(label=label), self.assertRaisesRegex(source.SourceAuthenticationError, "changed"):
                    self.authenticate()
            path.write_bytes(original)

    def test_external_binding_is_copied_before_api_callbacks(self):
        expected = dict(self.expected, contract_sha256=source.default_contract().sha256)
        def changed(run_id):
            self.expected["commit"] = "f" * 40
            return [self.selected]
        with patch.object(self.api, "artifacts_for_run", side_effect=changed):
            self.assertEqual(expected, self.authenticate()["source"])

    def test_cli_transfers_external_binding_and_rejects_incomplete_arguments(self):
        arguments = []
        for key, value in self.arguments.items():
            if key == "expected":
                for name, item in value.items(): arguments.extend(["--expected-" + name.replace("_", "-"), item])
            else:
                name = {"matrix_path": "matrix", "evidence_root": "evidence"}.get(key, key.replace("_", "-"))
                arguments.extend(["--" + name, str(value)])
        with patch.dict(os.environ, GH_TOKEN="fixture"), patch.object(source, "GitHubApi", return_value=self.api):
            output = io.StringIO()
            with contextlib.redirect_stdout(output): self.assertEqual(0, source.main(arguments))
            self.assertEqual(self.manifest["aggregate_scope"], json.loads(output.getvalue())["aggregate_scope"])
            for option in ("--scope", "--expected-tree", "--expected-matrix-sha256"):
                index = arguments.index(option)
                with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()) as rejected:
                    self.assertEqual(2, source.main(arguments[:index] + arguments[index + 2:]))
                self.assertEqual("", rejected.getvalue())
