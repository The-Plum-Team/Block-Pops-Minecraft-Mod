"""Run the canonical CLI with real Git/bootstrap/ZIP readers and simulated HTTP."""

import copy
import importlib.util
import io
import json
import os
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

from scripts.ci.tests import test_loader_bootstrap as bootstrap
from scripts.pages import download_artifact as transport
from scripts.release import plan_release
from tests import test_build_matrix as runner, test_release_acquisition as acquisition

ROOT = Path(__file__).resolve().parents[1]


class CanonicalApi(acquisition.ProducerApi):
    def __init__(self, fixture):
        super().__init__(fixture)
        self.records = 0
        self.default = "master"
        self.before_record = lambda: None

    def get(self, route):
        if route == "/repos/" + self.repository:
            self.records += 1
            self.before_record()
            return {"full_name": self.repository, "default_branch": self.default}
        return super().get(route)


class ReleaseSelectionTests(unittest.TestCase):
    def setUp(self):
        clean = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        clean["GH_TOKEN"] = "fixture-token"
        isolated = patch.dict(os.environ, clean, clear=True)
        isolated.start(); self.addCleanup(isolated.stop)
        original = runner.BuildMatrixExecutionTests.setUp
        def seeded(fixture):
            original(fixture)
            contract = bootstrap._current_contract()
            paths = {"scripts/release/plan_release.py"}
            for loader, record in contract["loaders"].items():
                paths.add(f"{loader}/build.gradle")
                paths.update(record["files"])
            for relative in paths:
                target = fixture.root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            target = fixture.root / "e2e/loader-bootstrap-contract.json"
            target.parent.mkdir(exist_ok=True)
            target.write_text(json.dumps(contract))
        self.base = acquisition.ReleaseAcquisitionTests(methodName="runTest")
        with patch.object(runner.BuildMatrixExecutionTests, "setUp", seeded):
            self.base.setUp()
        self.addCleanup(self.base.doCleanups)
        self.fixture = self.base.fixture
        self.api = CanonicalApi(self.fixture)
        path = self.fixture.root / "scripts/release/plan_release.py"
        spec = importlib.util.spec_from_file_location("release_cli_fixture", path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.output = self.fixture.root / "build/local-plan"
        self.arguments = ["--repository", self.api.repository, "--artifact-node", self.fixture.node,
                          "--build-scope", "legacy", "--e2e-scope", "lane"]

    def invoke(self, *, output=True, args=None, real_constructor=False):
        opener = type("Opener", (), {})(); opener.open = self.api.open
        stdout, stderr = io.StringIO(), io.StringIO()
        arguments = self.arguments if args is None else args
        if output: arguments = [*arguments, "--output", str(self.output)]
        with patch.object(transport.urllib.request, "build_opener", return_value=opener), \
             patch.object(self.module, "GitHubApi", wraps=plan_release.GitHubApi if real_constructor else None,
                          **({} if real_constructor else {"return_value": self.api})) as constructor, \
             redirect_stdout(stdout), redirect_stderr(stderr):
            result = self.module.main(arguments)
        if not real_constructor:
            self.assertEqual("https://api.github.com", constructor.call_args.kwargs["api_url"])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_cli_publishes_only_the_selected_e2e_production_and_partial_coverage(self):
        code, stdout, stderr = self.invoke()
        self.assertEqual((0, str(self.output / "plan.json") + "\n", ""), (code, stdout, stderr))
        plan = json.loads((self.output / "plan.json").read_bytes())
        self.assertEqual("blockpops-local-lane-release-plan", plan["kind"])
        selected, content = plan["selected_production"], plan["evidence"]["content"]
        self.assertEqual("e2e", selected["origin"])
        self.assertEqual(content["production"]["sha256"], selected["sha256"])
        self.assertNotEqual(content["build"]["production"]["sha256"], selected["sha256"])
        for key in ("artifact_node", "minecraft", "loader", "java", "mod_version"):
            self.assertEqual(content["build_identity"][key], selected[key])
        matrix = json.loads(self.fixture.matrix_path.read_bytes())
        self.assertEqual([target["artifact_node"] for target in matrix["targets"]], plan["coverage"]["target_nodes"])
        self.assertEqual((18, 17, [self.fixture.node], True), (len(plan["coverage"]["target_nodes"]),
            len(plan["coverage"]["remaining_nodes"]), plan["coverage"]["selected_nodes"], plan["coverage"]["partial"]))
        self.assertEqual(("legacy", "lane"), (content["build"]["scope"]["kind"], content["e2e"]["scope"]["kind"]))
        self.assertEqual(4, len(plan["evidence"]["downloads"]))
        self.assertEqual(4, self.api.records)
        self.assertFalse({"authorized", "qualified", "publication", "migration_complete"} & set(plan))

    def test_default_output_is_new_local_and_ambient_api_origin_is_ignored(self):
        with patch.dict(os.environ, {"GITHUB_API_URL": "https://untrusted.invalid"}):
            code, stdout, stderr = self.invoke(output=False)
        self.assertEqual((0, ""), (code, stderr))
        path = Path(stdout.strip())
        self.assertEqual(self.fixture.root / "build", path.parent.parent)
        self.assertTrue(path.parent.name.startswith("release-plan-"))
        self.assertEqual("plan.json", path.name)
        self.assertTrue(path.is_file())

    def test_parser_has_no_identity_or_authority_override_and_requires_scopes(self):
        for extra in (["--controller-sha", "a" * 40], ["--generation", "1"], ["--manifest", "local.json"],
                      ["--qualification", "local.json"], ["--source", str(ROOT)], ["--api-url", "https://example.invalid"]):
            with self.subTest(extra=extra), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                self.module.main(self.arguments + extra)
            self.assertEqual(2, error.exception.code)
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): self.module.main(self.arguments[:4])
        self.assertEqual(0, self.api.records)

    def test_missing_token_dirty_checkout_and_unresolved_scope_never_emit_a_plan(self):
        with patch.dict(os.environ, {"GH_TOKEN": ""}):
            self.assertEqual(1, self.invoke(real_constructor=True)[0])
        path = self.fixture.root / "scripts/release/plan_release.py"
        raw = path.read_bytes(); path.write_bytes(raw + b"\n# dirty\n")
        self.assertEqual(1, self.invoke()[0]); path.write_bytes(raw)
        args = ["neoforge-1.21.7" if value == self.fixture.node else value for value in self.arguments]
        self.assertEqual(1, self.invoke(args=args)[0])
        self.assertFalse(self.output.exists())
        self.assertEqual([], self.api.downloads)

    def test_late_canonical_or_producer_change_during_final_api_reads_rejects(self):
        for mutation in ("canonical", "producer"):
            original_head, original_rows = self.api.head, copy.deepcopy(self.api.rows)
            self.api.records = 0
            self.api.job_reads = 0
            def changed():
                if self.api.records == 3 and mutation == "canonical":
                    self.api.head = ("a" * 40, self.api.head[1])
            def producer_changed():
                if self.api.job_reads == 9 and mutation == "producer":
                    self.api.rows[1]["conclusion"] = "failure"
            self.api.before_record = changed
            self.api.after_jobs = producer_changed
            with self.subTest(mutation=mutation): self.assertEqual(1, self.invoke()[0])
            self.assertFalse(self.output.exists())
            self.api.head, self.api.rows = original_head, original_rows

    def test_default_changed_during_last_producer_observation_is_reauthenticated(self):
        def changed():
            if self.api.job_reads == 9: self.api.default = "renamed"
        self.api.after_jobs = changed
        self.assertEqual(1, self.invoke()[0])
        self.assertFalse(self.output.exists())

    def test_tracked_source_changed_while_writing_temporary_plan_cannot_be_published(self):
        write = self.module.atomic.write_new
        source = self.fixture.root / "scripts/release/plan_release.py"
        def changed(stage, relative, data):
            write(stage, relative, data)
            if relative == "plan.json": source.write_bytes(source.read_bytes() + b"\n# late source\n")
        with patch.object(self.module.atomic, "write_new", side_effect=changed):
            self.assertEqual(1, self.invoke()[0])
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.output.parent.glob(".local-plan.building-*")))

    def test_last_transport_and_plan_seals_reject_late_mutations_without_output(self):
        write, verify = self.module.atomic.write_new, self.module._verify_stage
        for mutation in ("transport", "plan"):
            captured = {}
            def written(stage, relative, data):
                write(stage, relative, data)
                if relative == "plan.json": captured["stage"] = stage
            def checked(stage, files, directories):
                result = verify(stage, files, directories)
                if "stage" in captured and "plan.json" not in files and not captured.get("changed"):
                    captured["changed"] = True
                    if mutation == "plan":
                        descriptor = os.open("plan.json", os.O_RDWR, dir_fd=captured["stage"])
                        try: os.write(descriptor, b"x")
                        finally: os.close(descriptor)
                    else:
                        report = next((self.fixture.root / "build").glob("release-evidence-*-build-report/build-matrix-report.json"))
                        report.write_bytes(report.read_bytes().replace(b'"success"', b'"failure"', 1))
                return result
            with patch.object(self.module.atomic, "write_new", side_effect=written), \
                 patch.object(self.module, "_verify_stage", side_effect=checked):
                with self.subTest(mutation=mutation): self.assertEqual(1, self.invoke()[0])
            self.assertTrue(captured.get("changed"))
            self.assertFalse(self.output.exists())

    def test_existing_output_is_preserved_and_external_destination_rejected(self):
        self.output.mkdir(); sentinel = self.output / "keep"; sentinel.write_bytes(b"preserve")
        self.assertEqual(1, self.invoke()[0])
        self.assertEqual(b"preserve", sentinel.read_bytes())
        self.output = self.fixture.root / "outside-build"
        downloads = len(self.api.downloads)
        self.assertEqual(1, self.invoke()[0])
        self.assertEqual(downloads, len(self.api.downloads))
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
