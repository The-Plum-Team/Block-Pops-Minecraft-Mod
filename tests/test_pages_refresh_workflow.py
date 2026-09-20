"""Execute the refresh shell without API calls or artifact publication."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
STUB = r'''
import json, os, pathlib, sys
args = sys.argv[1:]
kind = ("git" if pathlib.Path(sys.argv[0]).name == "git" else "scope" if args[0].endswith("matrix_scope.py")
        else "scoped" if args[0].endswith("refresh_cache.py") else "legacy")
with open(os.environ["CALLS"], "a") as stream: stream.write(json.dumps([kind, args, "GH_TOKEN" in os.environ]) + "\n")
if os.environ.get("FAIL") == kind: raise SystemExit(9)
if kind == "scope": print(os.environ["SCOPE"])
elif kind == "git": print(os.environ["GIT_HEAD"])
else:
    assert ("GH_TOKEN" in os.environ) == (kind == "scoped")
    if kind == "scoped":
        assert pathlib.Path(args[args.index("--inventory")+1]).read_text() == os.environ["INVENTORY"] + "\n"
    assert not pathlib.Path("pages-inventory.json").exists()
    assert pathlib.Path("promoted-cache/payload").read_bytes() == b"untouched bundle"
'''


class PagesRefreshWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="pages refresh ")
        self.addCleanup(temporary.cleanup); self.root = Path(temporary.name)
        self.bin = self.root / "bin"; self.bin.mkdir()
        for name in ("python3", "git"):
            path = self.bin / name; path.write_text(f"#!{sys.executable}\n" + STUB); path.chmod(0o755)
        self.workflow = (ROOT / ".github/workflows/pages.yml").read_text()
        self.job = self.workflow.split("  refresh-cache:\n", 1)[1].split("  rotate-current:\n", 1)[0]
        self.step = self.job.split("      - name: Revalidate the promoted compact bundle\n", 1)[1].split("      - name:", 1)[0]
        self.script = textwrap.dedent(self.step.split("        run: |\n", 1)[1])

    def invoke(self, scope="legacy", **overrides):
        root = self.root / str(len(list(self.root.iterdir()))); root.mkdir()
        checkout = root / "checkout"; checkout.mkdir()
        payload = checkout / "promoted-cache"; payload.mkdir(); (payload / "payload").write_bytes(b"untouched bundle")
        temporary = root / "runner temp with spaces"; temporary.mkdir()
        env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "CALLS": str(root / "calls"), "SCOPE": scope, "RUNNER_TEMP": str(temporary),
            "GITHUB_REPOSITORY": "AkaNebur/BlockPops", "GITHUB_RUN_ID": "811", "GITHUB_RUN_ATTEMPT": "3",
            "IMPLEMENTATION_SHA": "a"*40, "GITHUB_SHA": "a"*40, "GIT_HEAD": "a"*40,
            "GITHUB_API_URL": "https://api.github.com", "GITHUB_SERVER_URL": "https://github.com",
            "CANONICAL_BRANCH": "master", "GITHUB_REF": "refs/heads/master",
            "GITHUB_WORKFLOW_REF": "AkaNebur/BlockPops/.github/workflows/pages.yml@refs/heads/master",
            "GITHUB_EVENT_NAME": "schedule", "GH_TOKEN": "fixture-bearer", "BRANCH": "release/opaque'$(touch injected)",
            "COMMIT_SHA": "b"*40, "TREE_SHA": "c"*40, "MATRIX_SHA": "d"*64,
            "INVENTORY": '[{"literal":"$(touch injected); discovery"}]', **overrides}
        script = self.script + '\nprintf "success\\n" > "$RUNNER_TEMP/refresh-success"\n'
        result = subprocess.run(["bash", "-c", script], cwd=checkout, env=env, capture_output=True, text=True)
        calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
        self.assertEqual(b"untouched bundle", (payload / "payload").read_bytes())
        self.assertFalse((checkout / "injected").exists()); self.assertFalse((checkout / "pages-inventory.json").exists())
        return result, temporary, calls, env

    def test_schema1_preserves_exact_validate_arguments_without_inventory_or_token(self):
        result, temporary, calls, env = self.invoke("unscoped")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["scope", "legacy"], [row[0] for row in calls])
        self.assertEqual(["scripts/pages/evidence.py", "validate-compact", "--input", "promoted-cache",
            "--matrix", "promoted-cache/release-matrix.json", "--repository", env["GITHUB_REPOSITORY"],
            "--branch", env["BRANCH"], "--commit", env["COMMIT_SHA"], "--tree", env["TREE_SHA"],
            "--matrix-sha256", env["MATRIX_SHA"]], calls[-1][1])
        self.assertTrue(all(not row[2] for row in calls)); self.assertFalse((temporary / "pages-inventory.json").exists())
        self.assertTrue((temporary / "refresh-success").is_file())

    def test_scoped_refresh_binds_own_branch_attempt_controller_and_external_inventory(self):
        for scope, event in (("legacy", "schedule"), ("legacy", "workflow_run"), ("full", "repository_dispatch")):
            result, temporary, calls, env = self.invoke(scope, GITHUB_EVENT_NAME=event)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["scope", "git", "scoped"], [row[0] for row in calls])
            self.assertEqual([False, False, True], [row[2] for row in calls])
            self.assertEqual(["scripts/ci/matrix_scope.py", "--matrix", "release/release-matrix.json"], calls[0][1])
            self.assertEqual(["scripts/pages/refresh_cache.py", "--input", "promoted-cache", "--branch", env["BRANCH"],
                "--inventory", str(temporary / "pages-inventory.json"), "--repository", env["GITHUB_REPOSITORY"],
                "--pages-run-id", "811", "--pages-run-attempt", "3", "--implementation-sha", env["IMPLEMENTATION_SHA"],
                "--canonical-branch", "master"], calls[-1][1])
            self.assertNotIn("--scope", calls[-1][1]); self.assertNotIn("--projection", calls[-1][1])
            self.assertTrue((temporary / "refresh-success").is_file())

    def test_guards_and_helper_failure_stop_before_validation(self):
        cases = [{"scope": "lane"}, {"FAIL": "scope"}, {"FAIL": "git"},
            {"GITHUB_API_URL": "https://foreign.invalid"}, {"GITHUB_SERVER_URL": "https://foreign.invalid"},
            {"IMPLEMENTATION_SHA": "malformed"}, {"GITHUB_SHA": "b"*40}, {"GIT_HEAD": "b"*40},
            {"GITHUB_RUN_ID": "0"}, {"GITHUB_RUN_ATTEMPT": "1\nready=true"}, {"CANONICAL_BRANCH": ""},
            {"GITHUB_REF": "refs/heads/other"}, {"GITHUB_WORKFLOW_REF": "other/workflow@refs/heads/master"}]
        for overrides in cases:
            result, temporary, calls, _ = self.invoke(**overrides)
            with self.subTest(overrides=overrides):
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(all(row[0] in ("scope", "git") for row in calls))
                self.assertFalse((temporary / "pages-inventory.json").exists())
                self.assertFalse((temporary / "refresh-success").exists())

    def test_failed_validation_never_reaches_success_in_any_route(self):
        for scope, command in (("unscoped", "legacy"), ("legacy", "scoped"), ("full", "scoped")):
            result, temporary, calls, _ = self.invoke(scope, FAIL=command)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(command, calls[-1][0]); self.assertFalse((temporary / "refresh-success").exists())

    def test_download_upload_permissions_and_discovery_remain_conservative(self):
        for variable, field in (("CANONICAL_BRANCH", "canonical_branch"), ("IMPLEMENTATION_SHA", "implementation_sha"), ("INVENTORY", "inventory")):
            self.assertIn(f"{variable}: ${{{{ needs.discover.outputs.{field} }}}}", self.step)
        self.assertIn("BRANCH: ${{ matrix.branch.name }}", self.step)
        download = self.job.split("      - name: Download this run's deployed branch bundle\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("name: ${{ steps.names.outputs.collection }}", download)
        self.assertIn("path: promoted-cache", download); self.assertIn("digest-mismatch: error", download)
        self.assertNotIn("run-id:", download)
        upload = self.job.split("      - name: Upload the sole candidate for this branch's rolling cache\n", 1)[1]
        self.assertIn("name: ${{ steps.names.outputs.cache }}", upload); self.assertIn("path: promoted-cache/", upload)
        self.assertNotIn("always()", self.job); self.assertNotIn("actions: write", self.job); self.assertNotIn("pages: write", self.job)
        self.assertIn("version_branches.py --include-integration --objects", self.workflow)
        self.assertNotIn("version_branches.py --pages", self.workflow)


if __name__ == "__main__":
    unittest.main()
