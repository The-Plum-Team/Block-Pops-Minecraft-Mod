"""Execute fan-in workflow shell selection without decoding or uploading artifacts."""

import hashlib
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
        # Every projection branch of the retained step: a direct dispatch and an attested schedule
        # select the PR anchors, and only a direct schedule selects the scheduled anchors.
        for event, attestation, projection in (("workflow_dispatch", "", "pr-anchors"),
                                               ("schedule", "", "scheduled-anchors"),
                                               ("schedule", "123", "pr-anchors")):
            for scope in ("unscoped", "legacy", "full", "lane"):
                with self.subTest(event=event, attestation=attestation, scope=scope):
                    result, rows = self.run_public_revalidation(scope, event=event, attestation=attestation)
                    if scope == "lane":
                        self.assertNotEqual(0, result.returncode)
                        self.assertEqual(1, len(rows))
                        continue
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(["python3", "scripts/ci/matrix_scope.py", "--matrix",
                                      "release/release-matrix.json"], rows[0])
                    command = rows[-1]
                    self.assertEqual(["python3", "scripts/ci/e2e_fanin.py", "validate"], command[:3])
                    self.assertEqual(projection, command[command.index("--projection") + 1])
                    for option, value in (("--input", "/downloaded aggregate/$(touch forbidden)"),
                                          ("--source-branch", "master"), ("--commit", "1" * 40),
                                          ("--tree", "2" * 40), ("--run-id", "123"), ("--run-attempt", "2")):
                        self.assertEqual(value, command[command.index(option) + 1])
                    if scope == "unscoped": self.assertNotIn("--artifact-repository", command)
                    else:
                        self.assertEqual(["--scope", scope, "--artifact-repository", "/published checkout",
                            "--stage", "/downloaded bundle", "--artifact-manifest", "/downloaded bundle/artifacts.json"], command[-8:])
        # A failed scope query or fan-in revalidation fails the step, so the pinned composite
        # (the next step, which carries no if:) never hands off unauthenticated evidence.
        for overrides in ({"SCOPE_EXIT": "2"}, {"FANIN_EXIT": "2"}):
            with self.subTest(overrides=overrides):
                result, rows = self.run_public_revalidation("legacy", **overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1 if "SCOPE_EXIT" in overrides else 2, len(rows))

    def run_public_revalidation(self, scope, *, event="schedule", attestation="", **overrides):
        text = WORKFLOW.read_text().split("  public-evidence:", 1)[1]
        body = text.split("      - name: Revalidate the exact aggregate inventory", 1)[1].split("      - name:", 1)[0]
        self.assertNotIn("\n        if:", body)
        script = textwrap.dedent(body.split("        run: |\n", 1)[1]).replace("${{ inputs.attest_run_id }}", attestation)
        self.assertNotIn("${{", script)
        env = {**self.env, "MATRIX_SCOPE": scope, "GITHUB_EVENT_NAME": event,
            "GITHUB_WORKSPACE": "/published checkout", "GITHUB_REPOSITORY": "owner/repo",
            "PUBLIC_AGGREGATE": "/downloaded aggregate/$(touch forbidden)", "PUBLIC_BUNDLE": "/downloaded bundle",
            "PACKAGED_BRANCH": "master", "PACKAGED_SHA": "1" * 40, "PACKAGED_TREE": "2" * 40,
            "PACKAGED_RUN_ID": "123", "PACKAGED_RUN_ATTEMPT": "2", **overrides}
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script], cwd=self.root,
                                env=env, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.log.unlink()
        self.assertFalse((self.root / "forbidden").exists())
        return result, rows

    def public_steps(self):
        text = WORKFLOW.read_text().split("  public-evidence:", 1)[1]
        names = re.findall(r"(?m)^      - name: (.+)$", text)
        bodies = {name: text.split("      - name: " + name + "\n", 1)[1].split("\n      - name:", 1)[0]
                  for name in names}
        return text, names, bodies

    def test_public_handoff_is_the_pinned_composite_after_exact_revalidation(self):
        text, names, bodies = self.public_steps()
        self.assertEqual(["Check out the exact published head", "Install Python",
            "Install the hash-locked evidence decoder", "Authenticate current branch and derive opaque artifact identity",
            "Download the exact validated aggregate", "Download the matching aggregate artifact bundle",
            "Revalidate the exact aggregate inventory", "Prepare and hand off current-head public evidence"], names)
        handoff = bodies["Prepare and hand off current-head public evidence"]
        lines = handoff.rstrip("\n").split("\n")
        self.assertRegex(lines[0], r"^        uses: The-Plum-Team/mod-base/actions/prepare-evidence@[0-9a-f]{40} # v\d+\.\d+\.\d+$")
        self.assertEqual("        with:", lines[1])
        # No if:, env: or token: the composite takes its only token for its own tree check.
        inputs = dict(line.strip().split(": ", 1) for line in lines[2:])
        self.assertTrue(all(line.startswith("          ") for line in lines[2:]))
        self.assertEqual({
            "e2e-root": "${{ runner.temp }}/blockpops-public-aggregate",
            "key": "${{ steps.identity.outputs.token }}",
            "subject-branch": "${{ env.PUBLISHED_BRANCH }}",
            "subject-commit": "${{ env.BLOCKPOPS_TESTED_SHA }}",
            "subject-tree": "${{ steps.identity.outputs.tree }}",
            "tested-run-id": "${{ env.PACKAGED_RUN_ID }}",
            "tested-run-attempt": "${{ env.PACKAGED_RUN_ATTEMPT }}",
            "tested-branch": "${{ env.PACKAGED_BRANCH }}",
            "tested-commit": "${{ env.PACKAGED_SHA }}",
            "tested-controller-branch": "${{ env.PACKAGED_CONTROLLER_BRANCH }}",
            "tested-controller-sha": "${{ env.PACKAGED_CONTROLLER_SHA }}",
            # Only a direct canonical run may cut the durable lossless anchor, never an attestation.
            "anchor": "${{ inputs.attest_run_id == '' && 'auto' || 'off' }}",
        }, inputs)
        # The composite reads exactly the aggregate that was downloaded and just revalidated.
        download = bodies["Download the exact validated aggregate"]
        self.assertIn("path: ${{ runner.temp }}/blockpops-public-aggregate\n", download)
        revalidate = bodies["Revalidate the exact aggregate inventory"]
        self.assertIn("PUBLIC_AGGREGATE: ${{ runner.temp }}/blockpops-public-aggregate\n", revalidate)
        self.assertNotIn("\n        if:", revalidate)
        # The kit owns every upload name and retention; the retired producers are gone.
        for retired in ("actions/upload-artifact@", "scripts/pages/evidence.py", "scripts/pages/visual_anchor.py",
                        "pages-e2e-", "visual-anchor", "public-evidence/", "steps.identity.outputs.artifact"):
            self.assertNotIn(retired, text)

    def run_identity(self, token, **env):
        text, _names, bodies = self.public_steps()
        script = textwrap.dedent(bodies["Authenticate current branch and derive opaque artifact identity"]
                                 .split("        run: |\n", 1)[1])
        self.assertNotIn("${{", script)
        stub = f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
name, args = Path(sys.argv[0]).name, sys.argv[1:]
with open(os.environ["COMMAND_LOG"], "a") as stream:
    stream.write(json.dumps([name, *args]) + "\\n")
if name == "git": print("2" * 40 if args[-1] == "HEAD^{tree}" else "1" * 40)
elif name == "jq": print("master")
elif name == "gh": print("1" * 40)
elif args[:1] == ["-c"]: print(args[-1])
elif args[:2] == ["scripts/ci/gate_controller.py", "branch-token"]: print(os.environ["TOKEN"])
else: sys.exit(97)
'''
        for name in ("git", "jq", "gh", "python3"):
            path = self.root / name
            path.write_text(stub)
            path.chmod(0o755)
        output = self.root / "identity output"
        # Startup files could define a gh function that shadows the PATH stub.
        hermetic = {key: value for key, value in self.env.items() if key not in ("BASH_ENV", "ENV")}
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script], cwd=self.root, capture_output=True,
            text=True, env={**hermetic, "GITHUB_OUTPUT": str(output), "GITHUB_REPOSITORY": "owner/repo",
                            "BLOCKPOPS_TESTED_SHA": "1" * 40, "PUBLISHED_BRANCH": "master", "SOURCE_RUN_ID": "123",
                            "TOKEN": token, "GH_TOKEN": "fixture-token", **env})
        self.log.unlink(missing_ok=True)
        markers = output.read_text().splitlines() if output.exists() else []
        output.unlink(missing_ok=True)
        return result, markers

    def test_public_identity_emits_only_an_opaque_branch_token_as_the_bundle_key(self):
        token = hashlib.sha256(b"master").hexdigest()[:24]
        result, markers = self.run_identity(token)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["token=" + token, "source_run_id=123", "tree=" + "2" * 40], markers)
        for bad in ("", token.upper(), token[:-1], token + "0", "../" + token[3:], token[:-1] + "\n0"):
            with self.subTest(token=bad):
                result, markers = self.run_identity(bad)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], markers)
        result, markers = self.run_identity(token, BLOCKPOPS_TESTED_SHA="3" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], markers)

if __name__ == "__main__":
    unittest.main()
