"""Execute fan-in workflow shell selection without decoding or uploading artifacts."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/on-demand-e2e.yml"


class FanInWorkflowScopeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.log = self.root / "commands.jsonl"
        stub = f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
name, args = Path(sys.argv[0]).name, sys.argv[1:]
with open(os.environ["COMMAND_LOG"], "a") as stream:
    stream.write(json.dumps([name, *args]) + "\\n")
if args and args[0] == "scripts/ci/matrix_scope.py":
    print(os.environ["MATRIX_SCOPE"])
    sys.exit(int(os.environ.get("SCOPE_EXIT", "0")))
if args[:2] == ["scripts/ci/e2e_fanin.py", "validate"]:
    sys.exit(int(os.environ.get("FANIN_EXIT", "0")))
if args[:2] == ["scripts/pages/evidence.py", "curate"]:
    if any(key in os.environ for key in ("ACTIONS_RUNTIME_TOKEN", "ACTIONS_CACHE_URL", "ACTIONS_RESULTS_URL",
                                         "GITHUB_TOKEN", "GH_TOKEN")): sys.exit(95)
    sys.exit(int(os.environ.get("CURATE_EXIT", "0")))
if name == "sha256sum": print("a" * 64 + "  artifacts.json")
if name == "jq": print(os.environ.get("RECEIPT_HASH", "a" * 64))
'''
        for name in ("python3", "sha256sum", "jq"):
            path = self.root / name
            path.write_text(stub)
            path.chmod(0o755)
        self.identity = {"repository": "owner/repo", "source-branch": "integration/next", "commit": "1" * 40,
                         "tree": "2" * 40, "run-id": "123", "run-attempt": "2", "projection": "scheduled-anchors"}
        self.env = {**os.environ, "PATH": str(self.root) + os.pathsep + os.environ["PATH"], "COMMAND_LOG": str(self.log)}
        for key, value in self.identity.items():
            name = "TESTED_SHA" if key == "commit" else key.replace("-", "_").upper()
            self.env["BLOCKPOPS_" + name] = value

    def run_step(self, validate, scope, **env):
        step = "Revalidate the sealed aggregate" if validate else "Validate and reconstruct the exact aggregate"
        body = WORKFLOW.read_text().split("      - name: " + step, 1)[1].split("      - name:", 1)[0]
        script = re.search(r"bash -euo pipefail -c '\n(.*?)\n            '", body, re.S).group(1)
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script, "_", "/sealed candidate"],
            cwd=self.root, env={**self.env, "MATRIX_SCOPE": scope, **env}, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.log.unlink()
        return result, rows

    def test_create_and_fresh_validator_bind_external_scope_and_source_identity(self):
        for scope in ("unscoped", "legacy", "full"):
            for validate in (False, True):
                with self.subTest(scope=scope, validate=validate):
                    result, rows = self.run_step(validate, scope)
                    self.assertEqual(0, result.returncode, result.stderr)
                    prefix = "/sealed candidate/" if validate else ""
                    self.assertEqual(["python3", "scripts/ci/matrix_scope.py", "--matrix",
                                      prefix + "release/release-matrix.json"], rows[0])
                    command = rows[1]
                    self.assertEqual(["python3", "scripts/ci/e2e_fanin.py", "validate" if validate else "create"], command[:3])
                    for key, value in self.identity.items(): self.assertEqual(value, command[command.index("--" + key) + 1])
                    if scope == "unscoped":
                        self.assertNotIn("--scope", command)
                        if validate: self.assertNotIn("--artifact-manifest", command)
                    else:
                        self.assertEqual(scope, command[command.index("--scope") + 1])
                        self.assertEqual(prefix + "build/release/artifacts.json", command[command.index("--artifact-manifest") + 1])
                        if validate: self.assertEqual("/sealed candidate", command[command.index("--artifact-repository") + 1])
                    if validate:
                        verifier = rows[2]
                        self.assertEqual(["python3", "scripts/release/verify_release.py"], verifier[:2])
                        self.assertEqual(scope != "unscoped", "--scope" in verifier)

    def test_unknown_scope_or_crossed_manifest_cannot_complete_validation(self):
        for validate in (False, True):
            result, rows = self.run_step(validate, "lane")
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(1, len(rows))
        result, rows = self.run_step(True, "legacy", RECEIPT_HASH="b" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("jq", rows[-1][0])

    def test_public_consumer_downloads_exact_source_bundle_and_forwards_scope(self):
        text = WORKFLOW.read_text().split("  public-evidence:", 1)[1]
        download = text.split("      - name: Download the matching aggregate artifact bundle", 1)[1].split("      - name:", 1)[0]
        for binding in ("e2e-input-bundle-${{ inputs.attest_sha || github.sha }}-${{",
                        "inputs.attest_run_attempt || github.run_attempt }}",
                        "run-id: ${{ steps.identity.outputs.source_run_id }}", "digest-mismatch: error"):
            self.assertIn(binding, download)
        body = text.split("      - name: Revalidate the exact aggregate inventory", 1)[1].split("      - name:", 1)[0]
        script = textwrap.dedent(body.split("        run: |\n", 1)[1]).replace("${{ inputs.attest_run_id }}", "")
        for scope in ("unscoped", "legacy", "full", "lane"):
            with self.subTest(scope=scope):
                env = {**self.env, "MATRIX_SCOPE": scope, "GITHUB_EVENT_NAME": "schedule",
                    "GITHUB_WORKSPACE": "/published checkout", "GITHUB_REPOSITORY": "owner/repo",
                    "PUBLIC_AGGREGATE": "/downloaded aggregate", "PUBLIC_BUNDLE": "/downloaded bundle",
                    "PACKAGED_BRANCH": "master", "PACKAGED_SHA": "1" * 40, "PACKAGED_TREE": "2" * 40,
                    "PACKAGED_RUN_ID": "123", "PACKAGED_RUN_ATTEMPT": "2"}
                result = subprocess.run(["bash", "-euo", "pipefail", "-c", script], cwd=self.root,
                                        env=env, capture_output=True, text=True)
                rows = [json.loads(line) for line in self.log.read_text().splitlines()]
                self.log.unlink()
                if scope == "lane":
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(1, len(rows))
                    continue
                self.assertEqual(0, result.returncode, result.stderr)
                command = rows[-1]
                self.assertEqual("scheduled-anchors", command[command.index("--projection") + 1])
                if scope == "unscoped": self.assertNotIn("--artifact-repository", command)
                else:
                    self.assertEqual(["--scope", scope, "--artifact-repository", "/published checkout",
                        "--stage", "/downloaded bundle", "--artifact-manifest", "/downloaded bundle/artifacts.json"], command[-8:])

    def run_public_handoff(self, scope, *, event="workflow_dispatch", attestation="", curate_only=False, **overrides):
        text = WORKFLOW.read_text().split("  public-evidence:", 1)[1]
        steps = ("Revalidate the exact aggregate inventory", "Curate strict SHA-bound public evidence")
        self.assertLess(text.index("name: " + steps[0]), text.index("name: " + steps[1]))
        scripts = []
        values = {"inputs.attest_run_id": attestation, "steps.identity.outputs.tree": "2" * 40,
                  "steps.identity.outputs.source_run_id": "123",
                  "inputs.attest_run_attempt || github.run_attempt": "2",
                  "inputs.attest_branch || github.ref_name": "source/branch",
                  "inputs.attest_sha || github.sha": "1" * 40}
        for step in steps:
            body = text.split("      - name: " + step, 1)[1].split("      - name:", 1)[0]
            self.assertNotIn("\n        if:", body)
            if step == steps[1]:
                self.assertIn("PUBLIC_ATTEST_RUN_ID: ${{ inputs.attest_run_id }}", body)
            script = textwrap.dedent(body.split("        run: |\n", 1)[1])
            for key, value in values.items(): script = script.replace("${{ " + key + " }}", value)
            self.assertNotIn("${{", script)
            scripts.append(script)
        env = {**self.env, "MATRIX_SCOPE": scope, "GITHUB_EVENT_NAME": event,
            "GITHUB_WORKSPACE": "/published checkout", "GITHUB_REPOSITORY": "owner/repo",
            "PUBLIC_AGGREGATE": "/downloaded aggregate/$(touch forbidden)", "PUBLIC_BUNDLE": "/downloaded bundle",
            "PACKAGED_BRANCH": "source/branch", "PACKAGED_SHA": "1" * 40, "PACKAGED_TREE": "2" * 40,
            "PACKAGED_RUN_ID": "123", "PACKAGED_RUN_ATTEMPT": "2", "GITHUB_RUN_ID": "456",
            "GITHUB_RUN_ATTEMPT": "3", "BLOCKPOPS_TESTED_SHA": "3" * 40, "PUBLISHED_BRANCH": "release/branch",
            "HANDOFF_CONTROLLER_BRANCH": "master", "HANDOFF_CONTROLLER_SHA": "4" * 40,
            "PACKAGED_CONTROLLER_BRANCH": "master", "PACKAGED_CONTROLLER_SHA": "5" * 40,
            "ACTIONS_RUNTIME_TOKEN": "fixture-token", "ACTIONS_CACHE_URL": "fixture-url",
            "ACTIONS_RESULTS_URL": "fixture-url", "GITHUB_TOKEN": "fixture-token", "GH_TOKEN": "fixture-token",
            "PUBLIC_ATTEST_RUN_ID": attestation, **overrides}
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", "\n".join(scripts[1:] if curate_only else scripts)],
            cwd=self.root, env=env, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.log.unlink()
        self.assertFalse((self.root / "forbidden").exists())
        return result, rows

    def test_public_curate_matches_authenticated_fanin_selection_and_quoted_identity(self):
        for scope in ("unscoped", "legacy", "full"):
            for event, attestation, projection in (("workflow_dispatch", "", "pr-anchors"),
                    ("schedule", "", "scheduled-anchors"), ("schedule", "123", "pr-anchors")):
                with self.subTest(scope=scope, event=event, attestation=attestation):
                    result, rows = self.run_public_handoff(scope, event=event, attestation=attestation)
                    self.assertEqual(0, result.returncode, result.stderr)
                    query = ["python3", "scripts/ci/matrix_scope.py", "--matrix", "release/release-matrix.json"]
                    self.assertEqual(query, rows[0])
                    self.assertEqual(query, rows[2])
                    fanin, curate = rows[1], rows[3]
                    self.assertEqual(["python3", "scripts/ci/e2e_fanin.py", "validate"], fanin[:3])
                    self.assertEqual(["python3", "scripts/pages/evidence.py", "curate"], curate[:3])
                    self.assertEqual(projection, fanin[fanin.index("--projection") + 1])
                    expected = {"input": "/downloaded aggregate/$(touch forbidden)", "output": "public-evidence",
                        "repository": "owner/repo", "branch": "release/branch", "commit": "3" * 40,
                        "tree": "2" * 40, "run-id": "456", "run-attempt": "3", "controller-branch": "master",
                        "controller-sha": "4" * 40, "packaged-run-id": "123", "packaged-run-attempt": "2",
                        "packaged-branch": "source/branch", "packaged-commit": "1" * 40,
                        "packaged-tree": "2" * 40, "packaged-controller-branch": "master",
                        "packaged-controller-sha": "5" * 40}
                    if scope != "unscoped": expected.update(scope=scope, projection=projection)
                    self.assertEqual(expected, dict(zip((arg[2:] for arg in curate[3::2]), curate[4::2])))
                    self.assertEqual(len(expected) * 2 + 3, len(curate))

    def test_public_curate_is_not_invoked_after_unknown_scope_or_failed_authentication(self):
        for scope, overrides in (("lane", {}), ("legacy", {"SCOPE_EXIT": "2"}),
                                 ("legacy", {"FANIN_EXIT": "2"})):
            with self.subTest(scope=scope, overrides=overrides):
                result, rows = self.run_public_handoff(scope, **overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(row[1:2] == ["scripts/pages/evidence.py"] for row in rows))
        result, rows = self.run_public_handoff("legacy", CURATE_EXIT="2")
        self.assertEqual(2, result.returncode)
        self.assertEqual(["scripts/pages/evidence.py", "curate"], rows[-1][1:3])
        for scope, overrides in (("lane", {}), ("legacy", {"SCOPE_EXIT": "2"})):
            with self.subTest(curate_only=True, scope=scope, overrides=overrides):
                result, rows = self.run_public_handoff(scope, curate_only=True, **overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, len(rows))
                self.assertEqual("scripts/ci/matrix_scope.py", rows[0][1])


if __name__ == "__main__":
    unittest.main()
