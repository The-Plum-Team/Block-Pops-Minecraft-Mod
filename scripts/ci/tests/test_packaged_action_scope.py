"""Run packaged-action shell selection with inert verifier and display processes."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


ACTION = Path(__file__).resolve().parents[3] / ".github/actions/run-packaged-e2e/action.yml"


class PackagedActionScopeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.log = self.root / "commands.jsonl"
        stub = f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["COMMAND_LOG"], "a") as stream:
    stream.write(json.dumps([Path(sys.argv[0]).name, *args]) + "\\n")
if args and args[0] == "scripts/ci/matrix_scope.py":
    print(os.environ["MATRIX_SCOPE"])
if args and args[0] == "scripts/release/verify_release.py":
    sys.exit(int(os.environ.get("VERIFY_EXIT", "0")))
'''
        for name in ("python3", "xvfb-run"):
            path = self.root / name
            path.write_text(stub)
            path.chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.root) + os.pathsep + os.environ["PATH"],
            "COMMAND_LOG": str(self.log), "E2E_ROW_JSON": '{"id":"literal $(touch forbidden)"}',
            "E2E_SCENARIOS": "scenario-a,scenario-b", "BLOCKPOPS_PROJECTION": "scheduled-anchors"}

    def run_step(self, step, scope, **env):
        body = ACTION.read_text().split("    - name: " + step, 1)[1].split("    - name:", 1)[0]
        script = re.search(r"bash -euo pipefail -c '\n(.*?)\n          '", body, re.S).group(1)
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script, "_", "/sealed candidate", "scheduled-anchors"],
            cwd=self.root, env={**self.env, "MATRIX_SCOPE": scope, **env}, capture_output=True, text=True)
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.log.unlink()
        return result, rows

    def test_verifier_runtime_and_fresh_validator_receive_matching_matrix_scope(self):
        for scope in ("unscoped", "legacy", "full"):
            with self.subTest(scope=scope):
                selection = [] if scope == "unscoped" else ["--scope", scope]
                result, rows = self.run_step("Reverify and launch Minecraft only inside the no-sudo account", scope)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["python3", "scripts/release/verify_release.py", "--verify-staged", *selection], rows[-2])
                self.assertEqual(["xvfb-run", "--auto-servernum",
                    "--server-args=-screen 0 1920x1080x24 -ac +extension GLX +render -noreset",
                    "python3", "e2e/orchestrator.py", "--matrix", "release/release-matrix.json",
                    "--row-json", self.env["E2E_ROW_JSON"], "--projection", "scheduled-anchors",
                    "--artifacts-manifest", "build/release/artifacts.json", "--packaged",
                    "--scenarios", "scenario-a,scenario-b", *selection], rows[-1])
                result, rows = self.run_step("Revalidate passing lane evidence under a fresh credentialless identity", scope)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["python3", "scripts/ci/matrix_scope.py", "--matrix",
                    "/sealed candidate/release/release-matrix.json"], rows[0])
                self.assertEqual(["python3", "scripts/ci/e2e_fanin.py", "validate-lane",
                    "--input", "/sealed candidate/e2e-out/current", "--repository", "/sealed candidate",
                    "--matrix", "/sealed candidate/release/release-matrix.json", "--contract",
                    "/sealed candidate/e2e/scenario-contract.json", "--projection", "scheduled-anchors",
                    "--row-json", self.env["E2E_ROW_JSON"], "--stage", "/sealed candidate/build/release",
                    "--artifact-manifest", "/sealed candidate/build/release/artifacts.json", *selection], rows[1])
                self.assertFalse((self.root / "forbidden").exists())

    def test_invalid_scope_or_bundle_never_launches_a_display_or_game(self):
        for scope, env in (("lane", {}), ("legacy", {"VERIFY_EXIT": "2"})):
            with self.subTest(scope=scope, env=env):
                result, rows = self.run_step("Reverify and launch Minecraft only inside the no-sudo account", scope, **env)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(row[0] == "xvfb-run" for row in rows))


if __name__ == "__main__":
    unittest.main()
