"""Execute the build job shell; API/byte authentication is tested by the real CLI."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/pages.yml"
STUB = r'''
import json, os, pathlib, sys
args = sys.argv[1:]
program = pathlib.Path(sys.argv[0]).name
kind = "git" if program == "git" else "scope" if args[0].endswith("matrix_scope.py") else "render"
with open(os.environ["CALLS"], "a") as stream:
    stream.write(json.dumps([kind, args, "GH_TOKEN" in os.environ]) + "\n")
if os.environ.get("FAIL") == kind: raise SystemExit(9)
if kind == "scope": print(os.environ["SCOPE"])
elif kind == "git": print(os.environ["GIT_HEAD"])
else:
    assert ("GH_TOKEN" in os.environ) == (os.environ["SCOPE"] != "unscoped")
    inventory = pathlib.Path(args[args.index("--inventory") + 1])
    assert inventory.read_text() == os.environ["INVENTORY"] + "\n"
    assert not pathlib.Path("pages-inventory.json").exists()
    pathlib.Path("_site").mkdir()
    pathlib.Path("_site/index.html").write_text("validated fixture output")
'''


class PagesBuildWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="pages build ")
        self.addCleanup(temporary.cleanup); self.root = Path(temporary.name)
        self.bin = self.root / "bin"; self.bin.mkdir()
        for name in ("python3", "git"):
            path = self.bin / name; path.write_text(f"#!{sys.executable}\n" + STUB); path.chmod(0o755)
        self.workflow = WORKFLOW.read_text()
        self.build = self.workflow.split("  build:\n", 1)[1].split("  deploy:\n", 1)[0]
        self.step = self.build.split("      - name: Render all enrolled heads as one immutable site\n", 1)[1].split("      - name:", 1)[0]
        self.script = textwrap.dedent(self.step.split("        run: |\n", 1)[1])

    def invoke(self, scope="legacy", **overrides):
        root = self.root / str(len(list(self.root.iterdir()))); root.mkdir()
        checkout = root / "checkout"; checkout.mkdir()
        temporary = root / "runner temp with spaces"; temporary.mkdir()
        env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "CALLS": str(root / "calls"), "SCOPE": scope, "RUNNER_TEMP": str(temporary),
            "GITHUB_REPOSITORY": "AkaNebur/BlockPops", "GITHUB_RUN_ID": "811", "GITHUB_RUN_ATTEMPT": "3",
            "IMPLEMENTATION_SHA": "a"*40, "GITHUB_SHA": "a"*40, "GIT_HEAD": "a"*40,
            "GITHUB_API_URL": "https://api.github.com", "GITHUB_SERVER_URL": "https://github.com",
            "CANONICAL_BRANCH": "master", "GITHUB_REF": "refs/heads/master",
            "GITHUB_WORKFLOW_REF": "AkaNebur/BlockPops/.github/workflows/pages.yml@refs/heads/master",
            "GITHUB_EVENT_NAME": "schedule", "GH_TOKEN": "fixture-bearer",
            "INVENTORY": '[{"fixture":"literal $(touch injected); discovery"}]', **overrides}
        script = self.script + '\nprintf "success\\n" > "$RUNNER_TEMP/render-success"\n'
        result = subprocess.run(["bash", "-c", script], cwd=checkout, env=env, capture_output=True, text=True)
        calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
        return result, checkout, temporary, calls, env

    def test_schema1_keeps_legacy_arguments_and_temp_inventory_preserves_promotion_basename(self):
        result, checkout, temporary, calls, env = self.invoke("unscoped")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["scope", "render"], [row[0] for row in calls])
        self.assertEqual(["scripts/pages/build_site.py", "--evidence-root", "collected-caches",
            "--inventory", str(temporary / "pages-inventory.json"), "--output", "_site",
            "--repository", env["GITHUB_REPOSITORY"]], calls[-1][1])
        self.assertTrue(all(not row[2] for row in calls))
        self.assertEqual(env["INVENTORY"] + "\n", (temporary / "pages-inventory.json").read_text())
        self.assertEqual("success\n", (temporary / "render-success").read_text())
        self.assertFalse((checkout / "pages-inventory.json").exists())
        self.assertFalse((checkout / "injected").exists())

    def test_scoped_route_binds_exact_attempt_controller_without_overriding_branch_scope_or_projection(self):
        for scope, event in (("legacy", "schedule"), ("legacy", "workflow_run"), ("full", "repository_dispatch")):
            result, checkout, temporary, calls, env = self.invoke(scope, GITHUB_EVENT_NAME=event)
            with self.subTest(scope=scope, event=event):
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["scope", "git", "render"], [row[0] for row in calls])
                self.assertEqual(["scripts/ci/matrix_scope.py", "--matrix", "release/release-matrix.json"], calls[0][1])
                self.assertEqual(["rev-parse", "HEAD"], calls[1][1])
                self.assertEqual([False, False, True], [row[2] for row in calls])
                args = calls[-1][1]
                self.assertEqual(["--pages-run-id", "811", "--pages-run-attempt", "3",
                    "--implementation-sha", env["IMPLEMENTATION_SHA"], "--canonical-branch", "master"], args[-8:])
                self.assertNotIn("--scope", args); self.assertNotIn("--projection", args)
                self.assertTrue((temporary / "render-success").is_file())
                self.assertFalse((checkout / "pages-inventory.json").exists())

    def test_guards_and_failed_helper_never_invoke_renderer_or_emit_success(self):
        cases = [{"scope": "lane"}, {"FAIL": "scope"}, {"FAIL": "git"},
            {"GITHUB_API_URL": "https://foreign.invalid"}, {"GITHUB_SERVER_URL": "https://foreign.invalid"},
            {"IMPLEMENTATION_SHA": "malformed"}, {"GITHUB_SHA": "b"*40}, {"GIT_HEAD": "b"*40},
            {"GITHUB_RUN_ID": "0"}, {"GITHUB_RUN_ATTEMPT": "1\nready=true"}, {"CANONICAL_BRANCH": ""},
            {"GITHUB_REF": "refs/heads/other"}, {"GITHUB_WORKFLOW_REF": "other/workflow@refs/heads/master"}]
        for overrides in cases:
            result, checkout, temporary, calls, _ = self.invoke(**overrides)
            with self.subTest(overrides=overrides):
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("render", [row[0] for row in calls])
                self.assertFalse((temporary / "pages-inventory.json").exists())
                self.assertFalse((temporary / "render-success").exists())
                self.assertFalse((checkout / "_site").exists())

    def test_failed_renderer_stops_both_routes_before_success(self):
        for scope in ("unscoped", "legacy", "full"):
            result, checkout, temporary, calls, _ = self.invoke(scope, FAIL="render")
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("render", calls[-1][0])
            self.assertFalse((temporary / "render-success").exists())
            self.assertFalse((checkout / "_site").exists())

    def test_workflow_transports_protected_inputs_and_preserves_read_permissions_and_pending_discovery(self):
        for variable, source in (("CANONICAL_BRANCH", "canonical_branch"), ("IMPLEMENTATION_SHA", "implementation_sha"),
                                 ("INVENTORY", "inventory")):
            self.assertIn(f"{variable}: ${{{{ needs.discover.outputs.{source} }}}}", self.step)
        self.assertIn("GH_TOKEN: ${{ github.token }}", self.step)
        promotion = self.build.split("      - name: Upload exact promotion inventory\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("name: pages-promotion", promotion)
        self.assertIn("path: ${{ runner.temp }}/pages-inventory.json", promotion)
        self.assertIn("if-no-files-found: error", promotion)
        self.assertNotIn("always()", self.build)
        self.assertNotIn("actions: write", self.build); self.assertNotIn("pages: write", self.build)
        self.assertIn("version_branches.py --pages --include-integration --objects", self.workflow)
        self.assertIn('--canonical-branch "$CANONICAL_BRANCH"', self.workflow)


    def arrange(self, layout):
        root = self.root / f"arrange {len(list(self.root.iterdir()))}"; root.mkdir()
        checkout = root / "checkout"; (checkout / "collected-caches").mkdir(parents=True)
        temporary = root / "runner temp"; temporary.mkdir()
        for relative in layout:
            path = checkout / "collected-caches" / relative
            path.parent.mkdir(parents=True, exist_ok=True); path.write_text(relative)
        step = self.build.split("      - name: Keep a lone fan-in bundle in a directory of its own\n", 1)[1]
        script = textwrap.dedent(step.split("      - name:", 1)[0].split("        run: |\n", 1)[1])
        result = subprocess.run(["bash", "-c", script], cwd=checkout, capture_output=True, text=True,
            env={**os.environ, "RUNNER_TEMP": str(temporary)})
        self.assertEqual(0, result.returncode, result.stderr)
        base = checkout / "collected-caches"
        return sorted(str(path.relative_to(base)) for path in base.rglob("*") if path.is_file())

    def test_a_lone_flattened_bundle_gets_a_directory_and_several_bundles_are_untouched(self):
        self.assertEqual(["collected-pages-lone/.hidden", "collected-pages-lone/manifest.json",
                          "collected-pages-lone/nested/image.png"],
                         self.arrange(["manifest.json", ".hidden", "nested/image.png"]))
        several = ["collected-pages-a/manifest.json", "collected-pages-b/manifest.json"]
        self.assertEqual(several, self.arrange(several))
        mixed = ["collected-pages-a/manifest.json", "stray.json"]
        self.assertEqual(mixed, self.arrange(mixed))
        self.assertEqual([], self.arrange([]))
        order = self.build.index("name: Keep a lone fan-in bundle")
        self.assertLess(self.build.index("name: Download only this run's validated fan-in bundles"), order)
        self.assertLess(order, self.build.index("name: Render all enrolled heads as one immutable site"))


if __name__ == "__main__":
    unittest.main()
