"""Execute Build-only report transport shell with no sandbox/account/Gradle processes."""

import hashlib
import json
import subprocess
import unittest

from scripts.ci.tests.test_matrix_build_workflow import MatrixBuildWorkflowTests, WORKFLOW


def block(name):
    return WORKFLOW.read_text().split("      - name: " + name, 1)[1].split("      - name:", 1)[0]


class BuildReportTransportTests(unittest.TestCase):
    def setUp(self):
        self.fixture = MatrixBuildWorkflowTests(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def run_outer(self, name, **values):
        script = block(name).split("        run: |\n", 1)[1]
        script = "\n".join(line[10:] for line in script.splitlines())
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script], cwd=self.fixture.root,
            env={**self.fixture.env, **values}, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.fixture.log.read_text().splitlines()] if self.fixture.log.exists() else []
        self.fixture.log.unlink(missing_ok=True)
        return result, rows

    def test_schema2_validator_receives_exact_digest_source_and_scope(self):
        for scope in ("legacy", "full", "unscoped"):
            result, rows = self.fixture.run_step(self.fixture.validate_step, scope, "/sealed path/$(touch forbidden)")
            self.assertEqual(0, result.returncode, result.stderr)
            evidence = [row for row in rows if row[1:2] == ["scripts/release/build_evidence.py"]]
            if scope == "unscoped":
                self.assertEqual([], evidence)
            else:
                self.assertEqual([["python3", "scripts/release/build_evidence.py", "--repository",
                    "/sealed path/$(touch forbidden)", "--scope", scope, "--expected-report-sha256", "a" * 64,
                    "--expected-commit", "a" * 40, "--expected-tree", "b" * 40]], evidence)
            self.assertFalse((self.fixture.root / "forbidden").exists())
        result, _ = self.fixture.run_step(self.fixture.validate_step, "legacy", "/sealed", EVIDENCE_EXIT="2")
        self.assertEqual(2, result.returncode)

    def test_seal_always_runs_and_schema2_adds_only_the_report_export(self):
        for required in ("true", "false", "invalid"):
            result, rows = self.run_outer("Kill and lock the candidate identity before any artifact credential exists",
                SANDBOX_ROOT="/sealed path", BUILD_REPORT_REQUIRED=required)
            expected = ["python3", "controller/scripts/ci/untrusted_runner.py", "seal", "--root", "/sealed path",
                        "--export", "build/release"]
            if required == "true": expected += ["--export", "build/build-matrix-report.json"]
            self.assertEqual([expected], rows)
            self.assertEqual(required == "invalid", result.returncode != 0)

    def test_missing_report_aborts_before_validator_or_upload_marker(self):
        output = self.fixture.root / "output"
        sandbox = self.fixture.root / "sealed path"
        args = dict(SANDBOX_ROOT=str(sandbox), BUILD_REPORT_REQUIRED="true", BLOCKPOPS_TREE="b" * 40, GITHUB_OUTPUT=str(output))
        result, rows = self.run_outer(self.fixture.validate_step, **args)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], rows)
        self.assertFalse(output.exists())
        report = sandbox / "repository/build/build-matrix-report.json"
        report.parent.mkdir(parents=True); report.write_text("exact bytes")
        result, rows = self.run_outer(self.fixture.validate_step, **args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([str(sandbox / "repository"), hashlib.sha256(report.read_bytes()).hexdigest(), "b" * 40], rows[0][-3:])
        self.assertEqual("build_report=true\n", output.read_text())
        output.unlink()
        result, _ = self.run_outer(self.fixture.validate_step, **args, VALIDATOR_EXIT="7")
        self.assertEqual(7, result.returncode)
        self.assertFalse(output.exists())
        args["BUILD_REPORT_REQUIRED"] = "false"
        report.unlink()
        result, rows = self.run_outer(self.fixture.validate_step, **args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("build_report=false\n", output.read_text())

    def test_upload_uses_separate_fixed_file_and_requires_all_successes(self):
        upload = block("Upload the separately verified build report")
        for step in ("candidate", "seal", "validator"):
            self.assertIn(f"steps.{step}.outcome == 'success'", upload)
        self.assertIn("steps.validator.outputs.build_report == 'true'", upload)
        self.assertIn("build-run-report-${{ needs.identity.outputs.tested_sha }}-${{ github.run_attempt }}", upload)
        self.assertIn("/repository/build/build-matrix-report.json", upload)
        self.assertNotIn("/build/release/", upload)
        self.assertIn("if-no-files-found: error", upload)
        workflow = WORKFLOW.read_text()
        self.assertLess(workflow.index(self.fixture.validate_step), workflow.index("Upload the separately verified build report"))
