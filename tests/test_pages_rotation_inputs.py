"""Rotation input leases retain real Git/ZIP/pixel bindings without deleting artifacts."""

from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

from scripts.pages import download_artifact, evidence
from tests import test_pages_site_companions as companions


class RotationInputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        companions.PagesCompanionTests.setUpClass()
        cls.addClassCleanup(companions.PagesCompanionTests.doClassCleanups)

    def prepare(self, **options):
        self.f = companions.PagesCompanionTests(); self.addCleanup(self.f.doCleanups)
        self.f.prepare(additional_paths=("scripts/pages/refresh_cache.py", "scripts/pages/rotate_artifacts.py"), **options)
        def load(name):
            spec = importlib.util.spec_from_file_location(name+"_fixture", self.f.repo / ("scripts/pages/"+name+".py"))
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            module.site = self.f.module
            return module
        self.module = load("rotate_artifacts"); self.module.refresh_cache = load("refresh_cache")
        self.root = self.f.fixture.current / "owner-caches"; self.root.mkdir()
        for bundle in self.f.fixture.collected.iterdir():
            provenance = json.loads((bundle / "manifest.json").read_bytes())["provenance"]
            shutil.copytree(bundle, self.root / evidence.cache_artifact_name(provenance["branch"], provenance["commit"]))
        names = ["Assemble one atomic current-head gallery", "Deploy current-head evidence"]
        names += ["Promote rolling cache for " + row["name"] for row in self.f.fixture.rows]
        self.required = [dict(id=301+i, name=name, run_id=811, run_attempt=3, status="completed", conclusion="success")
                         for i, name in enumerate(names)]
        self.f.jobs.extend(self.required)

    @contextmanager
    def lease(self):
        with patch.dict(os.environ, self.f.environment), patch.object(download_artifact.urllib.request, "build_opener", return_value=self.f):
            with self.module.current_rotation_inputs(companions.Api(self.f), repository=self.f.repository,
                    pages_run_id=811, pages_run_attempt=3, implementation_sha=self.f.head, canonical_branch="master",
                    inventory_path=self.f.fixture.inventory_path, caches_root=self.root) as value:
                yield value

    def rejected(self):
        with self.assertRaises((self.module.RotationError, self.f.module.SiteError, OSError, ValueError)):
            with self.lease(): self.fail("invalid input lease was yielded")
        self.assertFalse(self.f.output.exists())

    def test_preparing_shared_mixed_inputs_recheck_only_while_all_leases_are_live(self):
        for options in ({}, {"shared": True}, {"mixed": True}):
            self.prepare(**options)
            before = {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
            with self.lease() as (rows, manifests, recheck):
                self.assertEqual(self.f.fixture.rows, rows)
                self.assertEqual({row["name"] for row in rows}, set(manifests)); recheck()
            with self.assertRaisesRegex(self.module.RotationError, "closed"): recheck()
            self.assertEqual(before, {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
            self.assertFalse(self.f.output.exists())

    def test_promotion_jobs_require_exact_coverage_success_and_current_attempt_before_download(self):
        self.prepare(mixed=True)
        for job in self.required:
            for key, bad in (("conclusion", "failure"), ("status", "in_progress"), ("run_attempt", 2), ("run_attempt", True)):
                before = job[key]; job[key] = bad
                with self.subTest(key=key, bad=bad): self.rejected()
                job[key] = before
            self.f.jobs.remove(job); self.rejected(); self.f.jobs.append(job)
            self.f.jobs.append({**job, "id": 999}); self.rejected(); self.f.jobs.pop()
        self.f.jobs.append({**self.required[-1], "name": "Promote rolling cache for unknown", "id": 999})
        self.rejected(); self.f.jobs.pop()
        self.f.owner.update(status="completed", conclusion="success"); self.rejected()
        self.assertEqual([], self.f.downloads)

    def test_exact_cache_root_rejects_missing_extra_symlink_and_cross_branch_payloads(self):
        self.prepare(mixed=True); first, second = list(self.root.iterdir())
        held = first.with_name("held"); first.rename(held); self.rejected(); held.rename(first)
        for kind in ("directory", "file", "link"):
            extra = self.root / "extra"
            if kind == "directory": extra.mkdir()
            elif kind == "file": extra.write_bytes(b"extra")
            else: extra.symlink_to(first)
            self.rejected()
            if kind == "directory": extra.rmdir()
            else: extra.unlink()
        source = first / "manifest.json"; saved = source.read_bytes()
        source.write_bytes((second / "manifest.json").read_bytes()); self.rejected(); source.write_bytes(saved)

    def test_aggregate_budget_is_consumed_across_branches(self):
        self.prepare(mixed=True)
        sizes = [sum(path.stat().st_size for path in root.rglob("*") if path.is_file()) for root in self.root.iterdir()]
        budget = sum(sizes)-1; self.assertGreaterEqual(budget, max(sizes))
        acquire = self.f.module._current_pages_inputs
        @contextmanager
        def limited(*args, **kwargs):
            with acquire(*args, **kwargs) as context, patch.object(self.f.module, "MAX_SITE_BYTES", budget):
                yield context
        reader = self.module.refresh_cache._cache_input
        with patch.object(self.f.module, "_current_pages_inputs", side_effect=limited), \
                patch.object(self.module.refresh_cache, "_cache_input", wraps=reader) as reads:
            self.rejected()
        self.assertEqual(2, reads.call_count)
        self.assertEqual(budget, reads.call_args_list[0].kwargs["byte_budget"])
        self.assertLess(reads.call_args_list[1].kwargs["byte_budget"], budget)

    def test_earlier_cache_mutated_during_later_cache_hash_or_final_api_is_rejected(self):
        for timing in ("later-cache", "final-api"):
            self.prepare(mixed=True)
            seal = self.f.module._seal_output; descriptors = []
            def corrupt():
                target = os.open("release-matrix.json", os.O_WRONLY, dir_fd=descriptors[0])
                try: os.write(target, b"x")
                finally: os.close(target)
            def sealed(descriptor, expected, **options):
                result = seal(descriptor, expected, **options)
                if "manifest.json" in expected:
                    descriptors.append(descriptor)
                    if len(descriptors) == 2 and timing == "later-cache": corrupt()
                return result
            jobs = self.f.jobs_for_attempt; observed = 0
            def observed_jobs(*args):
                nonlocal observed
                observed += 1
                if observed == 2 and timing == "final-api": corrupt()
                return jobs(*args)
            with patch.object(self.f.module, "_seal_output", side_effect=sealed), patch.object(self.f, "jobs_for_attempt", side_effect=observed_jobs):
                self.rejected()
            self.assertEqual(2, len(descriptors))
            for descriptor in descriptors:
                with self.assertRaises(OSError): os.fstat(descriptor)

    def test_recheck_rejects_post_yield_job_root_and_payload_changes(self):
        for mutation in ("job", "root", "bytes", "extra"):
            self.prepare()
            with self.lease() as (_, _, recheck):
                if mutation == "job": self.required[-1]["conclusion"] = "failure"
                elif mutation == "root":
                    held = self.root.with_name("held-root"); self.root.rename(held); shutil.copytree(held, self.root)
                elif mutation == "extra": (self.root / "extra").mkdir()
                else:
                    image = next(self.root.glob("*/images/*"))
                    with image.open("r+b") as stream: stream.write(b"x")
                with self.assertRaises((self.module.RotationError, self.f.module.SiteError, OSError, ValueError)): recheck()


if __name__ == "__main__":
    unittest.main()
