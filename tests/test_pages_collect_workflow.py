"""Execute the protected collect shell; API/pixel validators have separate suites."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

from scripts.pages.evidence import branch_token

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/pages.yml"
STUB = r'''
import json, os, pathlib, sys
args = sys.argv[1:]
if args[0] == "-":
    os.execv(os.environ["REAL_PYTHON"], [os.environ["REAL_PYTHON"], *args])
operation = ("scope" if args[0].endswith("matrix_scope.py") else
    "bind" if "--bind-compact" in args else "auth" if args[0].endswith("authenticate_source.py") else args[1])
assert ("GH_TOKEN" in os.environ) == (operation in {"auth", "bind"})
with open(os.environ["CALLS"], "a") as stream:
    stream.write(json.dumps([operation, args]) + "\n")
if os.environ.get("FAIL") == operation:
    raise SystemExit(9)
if operation == "scope":
    print(os.environ["SCOPE"])
elif operation in {"auth", "bind"}:
    result = dict(schema_version=1, attested=os.environ["ATTESTED"] == "true",
        handoff_run_id=31, packaged_run_id=32, packaged_job_graph_sha256="a" * 64)
    if os.environ["SCOPE"] != "unscoped":
        result.update(source=dict(branch=os.environ["BRANCH"], commit=os.environ["COMMIT_SHA"],
            tree=os.environ["TREE_SHA"], matrix_sha256=os.environ["MATRIX_SHA"]),
            aggregate_scope=dict(kind=os.environ.get("RESULT_SCOPE", os.environ["SCOPE"]),
                                 projection=os.environ["PROJECTION"]))
    if operation == "bind":
        result.update(kind="pages-compact-selection", compact_manifest_sha256="b" * 64)
        if os.environ.get("PREEXIST"):
            pathlib.Path("candidate-selection").mkdir()
            pathlib.Path("candidate-selection/sentinel").write_bytes(b"preserve")
        if os.environ.get("DRIFT") == "identity": result["packaged_run_id"] += 1
        if os.environ.get("DRIFT") == "matrix":
            with open(pathlib.Path(os.environ["RUNNER_TEMP"]) / "release-matrix.json", "ab") as stream:
                stream.write(b" ")
    print("null" if os.environ.get("MALFORMED") == operation else json.dumps(result))
else:
    output = pathlib.Path(args[args.index("--output") + 1])
    output.mkdir()
    (output / "manifest.json").write_text('{"fixture":"compact bytes"}')
'''


class PagesCollectWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="pages collect ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / "bin"; self.bin.mkdir()
        stub = self.bin / "python3"
        stub.write_text(f"#!{sys.executable}\n" + STUB); stub.chmod(0o755)
        workflow = WORKFLOW.read_text()
        self.collect = workflow.split("  collect:\n", 1)[1].split("  build:\n", 1)[0]
        step = self.collect.split("      - name: Authenticate and compact the exact selected source\n", 1)[1]
        self.script = textwrap.dedent(step.split("        run: |\n", 1)[1].split("      - name:", 1)[0])

    def run_collect(self, *, scope="legacy", kind="raw", projection="pr-anchors", **overrides):
        root = self.root / str(len(list(self.root.iterdir())))
        root.mkdir(); temporary = root / "runner temporary"; temporary.mkdir()
        raw = b'{"fixture":"external matrix bytes"}\n'
        (temporary / "release-matrix.json").write_bytes(raw)
        env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "PYTHONPATH": str(ROOT), "REAL_PYTHON": sys.executable, "CALLS": str(root / "calls"),
            "RUNNER_TEMP": str(temporary), "GITHUB_OUTPUT": str(root / "output"),
            "GH_TOKEN": "fixture-bearer-not-for-compaction",
            "GITHUB_REPOSITORY": "AkaNebur/BlockPops", "GITHUB_RUN_ID": "811", "GITHUB_RUN_ATTEMPT": "3",
            "GITHUB_EVENT_NAME": "schedule", "BRANCH": "release/1.20.1;literal", "CANONICAL_BRANCH": "master",
            "COMMIT_SHA": "c" * 40, "TREE_SHA": "d" * 40, "MATRIX_SHA": hashlib.sha256(raw).hexdigest(),
            "SELECTED_KIND": kind, "SELECTED_ARTIFACT_ID": "77", "SELECTED_ARTIFACT_NAME": "source name;literal",
            "SELECTED_ARTIFACT_DIGEST": "sha256:" + "e" * 64, "SELECTED_RUN_ID": "31", "SELECTED_RUN_ATTEMPT": "2",
            "EXPECTED_HANDOFF_RUN_ID": "31", "EXPECTED_HANDOFF_RUN_ATTEMPT": "2",
            "SOURCE_CONTROLLER_BRANCH": "master", "SOURCE_CONTROLLER_SHA": "f" * 40,
            "SCOPE": scope, "PROJECTION": projection, "ATTESTED": "false", **overrides}
        result = subprocess.run(["bash", "-c", self.script], cwd=root, env=env, capture_output=True, text=True)
        calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
        output = (root / "output").read_text() if (root / "output").exists() else ""
        return result, root, calls, output, env, raw

    def test_legacy_raw_and_cache_keep_arguments_and_companion_has_only_external_matrix(self):
        for kind, command in (("raw", "compact"), ("compact", "copy-compact")):
            result, root, calls, output, env, raw = self.run_collect(scope="unscoped", kind=kind)
            with self.subTest(kind=kind):
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["scope", "auth", command], [call[0] for call in calls])
                self.assertIn("--no-source-check", calls[0][1])
                for _, args in calls:
                    self.assertNotIn("--scope", args); self.assertNotIn("--projection", args)
                    self.assertNotIn("--expected-branch", args); self.assertNotIn("--bind-compact", args)
                self.assertEqual({"release-matrix.json"}, {path.name for path in (root / "candidate-selection").iterdir()})
                self.assertEqual(raw, (root / "candidate-selection/release-matrix.json").read_bytes())
                self.assertIn("ready=true\n", output)

    def test_preparing_shared_and_authenticated_projections_reach_exact_compact_binding(self):
        for scope, kind, projection, attested in (("legacy", "raw", "pr-anchors", "true"),
                ("legacy", "compact", "scheduled-anchors", "false"),
                ("full", "raw", "scheduled-anchors", "false"), ("full", "compact", "pr-anchors", "false")):
            result, root, calls, output, env, raw = self.run_collect(scope=scope, kind=kind,
                projection=projection, ATTESTED=attested)
            with self.subTest(scope=scope, kind=kind, projection=projection):
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["scope", "auth", "compact" if kind == "raw" else "copy-compact", "bind"],
                                 [call[0] for call in calls])
                auth, compact, bound = [call[1] for call in calls[1:]]
                self.assertEqual(auth + ["--bind-compact", "candidate-cache"], bound)
                for key, value in (("scope", scope), ("expected-branch", env["BRANCH"]),
                        ("expected-commit", env["COMMIT_SHA"]), ("expected-tree", env["TREE_SHA"]),
                        ("expected-matrix-sha256", env["MATRIX_SHA"]), ("selected-artifact-name", env["SELECTED_ARTIFACT_NAME"]),
                        ("selected-artifact-id", "77"), ("selected-artifact-digest", env["SELECTED_ARTIFACT_DIGEST"])):
                    self.assertEqual(value, auth[auth.index("--" + key) + 1])
                self.assertEqual(projection, compact[compact.index("--projection") + 1])
                self.assertEqual(scope, compact[compact.index("--scope") + 1])
                self.assertEqual(raw, (root / "candidate-selection/release-matrix.json").read_bytes())
                bound_raw = (Path(env["RUNNER_TEMP"]) / "pages-bound-selection.json").read_bytes()
                self.assertEqual(bound_raw, (root / "candidate-selection/selection.json").read_bytes())
                self.assertEqual({"manifest.json"}, {path.name for path in (root / "candidate-cache").iterdir()})
                name = f"pages-selection-{branch_token(env['BRANCH'])}--{env['COMMIT_SHA']}-811-3"
                self.assertEqual(f"name={name}\nready=true\n", output)

    def test_failures_never_emit_ready_or_a_companion_and_do_not_continue(self):
        cases = [({"FAIL": step}, step) for step in ("scope", "auth", "compact", "bind")]
        cases += [({"kind": "compact", "FAIL": "copy-compact"}, "copy-compact"),
            ({"scope": "lane"}, "scope"), ({"projection": "unknown"}, "auth"),
            ({"RESULT_SCOPE": "full"}, "auth"), ({"MALFORMED": "auth"}, "auth"),
            ({"kind": "invalid"}, "auth"), ({"MALFORMED": "bind"}, "bind"),
            ({"DRIFT": "identity"}, "bind"), ({"DRIFT": "matrix"}, "bind"),
            ({"GITHUB_RUN_ATTEMPT": "0"}, "bind"), ({"GITHUB_RUN_ID": "1\nready=true"}, "bind")]
        for overrides, last in cases:
            result, root, calls, output, _, _ = self.run_collect(**overrides)
            with self.subTest(overrides=overrides):
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(last, calls[-1][0])
                self.assertNotIn("ready=true", output)
                self.assertFalse((root / "candidate-selection").exists())

    def test_companion_upload_is_separate_pinned_and_gated_without_activating_other_jobs(self):
        step = self.collect.split("      - name: Upload the exact external branch selection companion\n", 1)[1]
        self.assertIn("if: steps.compact.outputs.ready == 'true'", step)
        self.assertIn("name: ${{ steps.compact.outputs.name }}", step)
        self.assertIn("path: candidate-selection/", step)
        self.assertIn("if-no-files-found: error", step)
        self.assertIn("retention-days: 1", step)
        self.assertRegex(step, r"uses: actions/upload-artifact@[0-9a-f]{40}")
        self.assertNotIn("actions: write", self.collect)
        workflow = WORKFLOW.read_text()
        self.assertIn("version_branches.py --include-integration --objects", workflow)
        self.assertNotIn("version_branches.py --pages", workflow)

    def test_attempt_names_do_not_reuse_companions_and_existing_output_is_preserved(self):
        first = self.run_collect(GITHUB_RUN_ATTEMPT="3")
        second = self.run_collect(GITHUB_RUN_ATTEMPT="4")
        self.assertEqual((0, 0), (first[0].returncode, second[0].returncode))
        self.assertNotEqual(first[3], second[3])
        result, root, _, output, _, _ = self.run_collect(PREEXIST="true")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("ready=true", output)
        self.assertEqual(b"preserve", (root / "candidate-selection/sentinel").read_bytes())


if __name__ == "__main__":
    unittest.main()
