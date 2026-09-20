"""Real CLI refresh uses Git, companion ZIPs and pixels; no publication is mocked."""

import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from scripts.pages import download_artifact
from tests import test_pages_site_companions as companions


class RefreshCacheTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        companions.PagesCompanionTests.setUpClass()
        cls.addClassCleanup(companions.PagesCompanionTests.doClassCleanups)

    def prepare(self, **options):
        fixture = companions.PagesCompanionTests(); self.addCleanup(fixture.doCleanups)
        fixture.prepare(additional_paths=("scripts/pages/refresh_cache.py",), **options)
        self.f = fixture
        self.prerequisites = [dict(id=301+index, name=name, run_id=811, run_attempt=3,
            status="completed", conclusion="success") for index, name in enumerate((
                "Assemble one atomic current-head gallery", "Deploy current-head evidence"))]
        fixture.jobs.extend(self.prerequisites)
        spec = importlib.util.spec_from_file_location("refresh_fixture", fixture.repo / "scripts/pages/refresh_cache.py")
        self.module = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.module)
        self.module.site = fixture.module  # Both real implementations are in the same committed fixture checkout.
        self.branch = "master"
        self.root = next(path for path in fixture.fixture.collected.iterdir()
            if json.loads((path / "manifest.json").read_bytes())["provenance"]["branch"] == self.branch)

    def invoke(self, *, branch=None, root=None):
        f = self.f; stdout, stderr = io.StringIO(), io.StringIO()
        args = ["--input", str(root or self.root), "--branch", branch or self.branch,
                "--inventory", str(f.fixture.inventory_path), "--repository", f.repository, *f.options]
        with patch.dict(os.environ, f.environment), patch.object(f.module, "GitHubApi", return_value=companions.Api(f)) as api, \
                patch.object(download_artifact.urllib.request, "build_opener", return_value=f), redirect_stdout(stdout), redirect_stderr(stderr):
            code = self.module.main(args)
        self.assertEqual("https://api.github.com", api.call_args.kwargs["api_url"])
        return code, stdout.getvalue(), stderr.getvalue()

    def rejected(self, **options):
        code, summary, detail = self.invoke(**options)
        self.assertEqual(2, code, detail); self.assertEqual("", summary)
        self.assertFalse(self.f.output.exists())

    def test_real_preparing_full_and_mixed_branch_validation_preserves_bytes_and_scope(self):
        for options in ({}, {"shared": True}, {"mixed": True}):
            self.prepare(**options)
            for row in self.f.fixture.rows:
                root = next(path for path in self.f.fixture.collected.iterdir()
                    if json.loads((path / "manifest.json").read_bytes())["provenance"]["branch"] == row["name"])
                before = {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()}
                code, summary, detail = self.invoke(branch=row["name"], root=root)
                self.assertEqual(0, code, detail); result = json.loads(summary)
                self.assertEqual(row["name"], result["branch"])
                manifest = json.loads(before["manifest.json"])
                self.assertEqual(manifest.get("aggregate_scope"), result.get("aggregate_scope"))
                self.assertEqual(before, {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()})
                self.assertFalse(self.f.output.exists())

    def test_exact_successful_build_and_deploy_are_required_before_download(self):
        self.prepare()
        for prerequisite in self.prerequisites:
            for key, bad in (("name", "not-the-job"), ("status", "in_progress"), ("conclusion", "failure"),
                             ("run_attempt", 2), ("run_attempt", True), ("run_id", 812)):
                before = prerequisite[key]; prerequisite[key] = bad
                with self.subTest(key=key, bad=bad): self.rejected()
                prerequisite[key] = before
            self.f.jobs.remove(prerequisite); self.rejected(); self.f.jobs.append(prerequisite)
            duplicate = {**prerequisite, "id": 999}; self.f.jobs.append(duplicate); self.rejected(); self.f.jobs.pop()
        self.assertEqual([], self.f.downloads)

    def test_unknown_cross_branch_and_cross_projection_never_emit_a_summary(self):
        self.prepare(mixed=True)
        self.rejected(branch="not-enrolled")
        other = next(row["name"] for row in self.f.fixture.rows if row["name"] != "master")
        self.rejected(branch=other)
        saved = copy.deepcopy(self.f.payloads[100])
        selection = json.loads(self.f.payloads[100][1][1])
        selection["aggregate_scope"]["projection"] = "scheduled-anchors"
        self.f.payloads[100][1] = ("selection.json", json.dumps(selection).encode())
        self.f.archive(100); self.rejected(); self.f.payloads[100] = saved

    def test_post_validation_job_source_bytes_inventory_and_directory_drift_fail(self):
        for mutation in ("job", "source", "bytes", "extra", "link", "directory", "rename"):
            self.prepare()
            original_jobs = self.f.jobs_for_attempt; observed = 0
            image = next((self.root / "images").iterdir())
            def jobs(run, attempt):
                nonlocal observed
                observed += 1
                if observed == 2:
                    if mutation == "job": self.prerequisites[1]["conclusion"] = "failure"
                    elif mutation == "source":
                        with (self.f.repo / "scripts/pages/refresh_cache.py").open("a") as stream: stream.write("\n# changed\n")
                    elif mutation == "bytes":
                        with image.open("r+b") as stream: stream.write(b"x")
                    elif mutation == "extra": (self.root / "extra").write_bytes(b"x")
                    elif mutation == "link": (self.root / "extra").symlink_to(image)
                    elif mutation == "directory": (self.root / "empty").mkdir()
                    else:
                        held = self.root.with_name("held-cache"); self.root.rename(held); shutil.copytree(held, self.root)
                return original_jobs(run, attempt)
            with patch.object(self.f, "jobs_for_attempt", side_effect=jobs): self.rejected()
            self.assertEqual(2, observed)

    def test_cache_lease_survives_all_api_and_companion_rechecks(self):
        self.prepare(mixed=True)
        original = self.f.module._seal_output; cache_leases = []; companions = []
        def seal(descriptor, expected, **options):
            result = original(descriptor, expected, **options)
            if "manifest.json" in expected: cache_leases.append(descriptor)
            else:
                companions.append(descriptor)
                self.assertTrue(cache_leases)
                self.assertIn("manifest.json", os.listdir(cache_leases[0]))
            return result
        with patch.object(self.f.module, "_seal_output", side_effect=seal):
            code, _, detail = self.invoke(); self.assertEqual(0, code, detail)
        self.assertEqual(2, len(companions)); self.assertEqual(1, len(cache_leases))
        for descriptor in cache_leases + companions:
            with self.assertRaises(OSError): os.fstat(descriptor)


if __name__ == "__main__":
    unittest.main()
