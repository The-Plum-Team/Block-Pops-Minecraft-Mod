"""Execute fan-in workflow shell selection without decoding or uploading artifacts."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
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
if args and args[0] == "scripts/ci/matrix_scope.py": print(os.environ["MATRIX_SCOPE"])
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


if __name__ == "__main__":
    unittest.main()
