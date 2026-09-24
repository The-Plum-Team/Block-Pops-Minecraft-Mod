"""Execute the workflow's shell dispatch with inert build/verifier processes."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[3]
WORKFLOW = REPO / ".github/workflows/build-gate.yml"


def script(workflow, step):
    text = workflow.read_text().split("      - name: " + step, 1)[1].split("      - name:", 1)[0]
    return re.search(r"bash -euo pipefail -c '\n(.*?)\n            ' _", text, re.S).group(1)


class MatrixBuildWorkflowTests(unittest.TestCase):
    workflow = WORKFLOW
    build_step = "Validate and build entirely inside the credentialless account"
    validate_step = "Reverify inert outputs under a fresh credentialless validator identity"
    test_runner = ["python3", "scripts/ci/parallel_unittest.py", "-v", "-t", ".", "scripts/ci/tests", "tests"]

    def test_java17_path_is_a_positional_argument_and_gradle_jdk_remains_default(self):
        workflow = self.workflow.read_text()
        self.assertLess(workflow.index("name: Install the legacy compiler JDK"),
                        workflow.index("name: Install the matrix-owned Gradle JDK"))
        self.assertIn("BLOCKPOPS_JAVA17_HOME: ${{ steps.java17.outputs.path }}", workflow)
        self.assertIn("' _ \"$BLOCKPOPS_JAVA17_HOME\"", workflow)
        self.assertNotIn("--pass-env BLOCKPOPS_JAVA17_HOME", workflow)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / "commands.jsonl"
        fake = self.root / "python3"
        fake.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["COMMAND_LOG"], "a") as stream:
    stream.write(json.dumps([Path(sys.argv[0]).name, *args]) + "\\n")
if args and args[0] == "scripts/ci/matrix_scope.py":
    print(os.environ["MATRIX_SCOPE"])
    sys.exit(int(os.environ.get("SCOPE_EXIT", "0")))
if args and args[0] == "scripts/release/build_matrix.py":
    sys.exit(int(os.environ.get("BUILD_EXIT", "0")))
if args and args[0] == "scripts/ci/parallel_unittest.py":
    sys.exit(int(os.environ.get("TESTS_EXIT", "0")))
if args and args[0] == "scripts/release/build_evidence.py":
    sys.exit(int(os.environ.get("EVIDENCE_EXIT", "0")))
if args[:2] == ["controller/scripts/ci/untrusted_runner.py", "validate"]:
    sys.exit(int(os.environ.get("VALIDATOR_EXIT", "0")))
''')
        fake.chmod(0o755)
        (self.root / "gradlew").write_bytes(fake.read_bytes())
        (self.root / "gradlew").chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.root) + os.pathsep + os.environ["PATH"],
                    "COMMAND_LOG": str(self.log), "JAVA_HOME": "/jdk21 home",
                    "GRADLE_USER_HOME": str(self.root), "BLOCKPOPS_TESTED_SHA": "a" * 40}

    def run_step(self, step, scope, argument, **env):
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script(self.workflow, step), "_", argument, "a" * 64, "b" * 40],
            cwd=self.root, env={**self.env, "MATRIX_SCOPE": scope, **env}, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        self.log.unlink(missing_ok=True)
        return result, rows

    def test_schema_one_retains_aggregate_and_scoped_builds_use_serial_runner(self):
        for scope in ("unscoped", "legacy", "full"):
            with self.subTest(scope=scope):
                home17 = "/jdk17 home/$(touch forbidden)"
                result, rows = self.run_step(self.build_step, scope, home17)
                self.assertEqual(0, result.returncode, result.stderr)
                builds = [row for row in rows if row[0] == "gradlew" or
                          row[1:2] == ["scripts/release/build_matrix.py"]]
                self.assertEqual(1, len(builds))
                if scope == "unscoped":
                    self.assertEqual(["gradlew", "--no-daemon", "--no-parallel", "--max-workers=1",
                        "--dependency-verification", "strict", "--stacktrace", "clean", "buildAllLanes",
                        "buildAllE2EHarnesses", "check"], builds[0])
                else:
                    self.assertEqual(["python3", "scripts/release/build_matrix.py", "--matrix",
                        "release/release-matrix.json", "--scope", scope, "--java-home", "/jdk21 home",
                        "--java17-home", home17, "--clean"], builds[0])
                scope_args = [] if scope == "unscoped" else ["--scope", scope]
                self.assertEqual(["python3", "scripts/release/verify_release.py", *scope_args], rows[-1])
                self.assertFalse((self.root / "forbidden").exists())

    def test_fresh_validator_derives_selection_from_matrix_and_preserves_exact_paths(self):
        for scope in ("unscoped", "legacy", "full"):
            with self.subTest(scope=scope):
                repository = "/sealed candidate"
                result, rows = self.run_step(self.validate_step, scope, repository)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["python3", "scripts/ci/matrix_scope.py", "--matrix",
                                  repository + "/release/release-matrix.json"], rows[0])
                scope_args = [] if scope == "unscoped" else ["--scope", scope]
                self.assertEqual(["python3", "scripts/release/verify_release.py", "--repository", repository,
                    "--matrix", repository + "/release/release-matrix.json", "--stage", repository + "/build/release",
                    "--manifest", repository + "/build/release/artifacts.json", "--verify-staged", *scope_args], rows[1])

    def test_both_candidate_suites_run_once_and_gate_the_build(self):
        result, rows = self.run_step(self.build_step, "legacy", "/jdk17")
        self.assertEqual(0, result.returncode, result.stderr)
        runs = [index for index, row in enumerate(rows)
                if row[1:2] == ["scripts/ci/parallel_unittest.py"] or row[1:3] == ["-m", "unittest"]]
        if self.test_runner is None:
            self.assertEqual([], runs)
            return
        self.assertEqual([self.test_runner], [rows[index] for index in runs])
        installs = [index for index, row in enumerate(rows) if row[1:4] == ["-m", "pip", "install"]]
        builds = [index for index, row in enumerate(rows) if row[1:2] == ["scripts/release/build_matrix.py"]]
        self.assertLess(installs[0], runs[0])
        self.assertLess(runs[0], builds[0])
        result, rows = self.run_step(self.build_step, "legacy", "/jdk17", TESTS_EXIT="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(row[1:2] == ["scripts/release/build_matrix.py"] for row in rows))

    def test_invalid_selection_and_failed_build_never_stage(self):
        for scope, env in (("lane", {}), ("legacy", {"SCOPE_EXIT": "2"}),
                           ("legacy", {"BUILD_EXIT": "1"})):
            with self.subTest(scope=scope, env=env):
                result, rows = self.run_step(self.build_step, scope, "/jdk17", **env)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(row[1:2] == ["scripts/release/verify_release.py"] for row in rows))


class E2EInputBuildWorkflowTests(MatrixBuildWorkflowTests):
    workflow = REPO / ".github/workflows/on-demand-e2e.yml"
    build_step = "Build and stage inside the credentialless account"
    validate_step = "Reverify inert runtime inputs under a fresh credentialless validator"
    test_runner = None


if __name__ == "__main__":
    unittest.main()
