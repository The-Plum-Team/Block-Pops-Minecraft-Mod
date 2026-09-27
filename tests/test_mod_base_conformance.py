"""The pinned kit's end-to-end conformance simulation passes on Block Pops' real contract and matrix.

This runs exactly the command an owner runs before a kit bump,
``python3 scripts/ci/mod_base_kit.py run conformance --repo . --all``: the kit commits a private
snapshot of this checkout and simulates, against its fake GitHub, a packaged producer, admission,
collection, the job graph, the display title, the attested and newest-run variants, the site
build, the cache refresh, rotation and a second generation at a later head, for every key the
adapter enrolls. The snapshot never touches this checkout. The same run in the matrix's future
``full`` scope (every target under the one ``master`` key) is proven by the adapter tests, which
derive that expectation without rendering 440 frames.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

from tests import mod_base_path

REPO = mod_base_path.REPO
MASTER_KEY = "fc613b4dfd6736a7bd268c8a"
TIMEOUT_SECONDS = 1800


def run_conformance() -> dict:
    # The simulation creates and removes many scratch directories; a private TMPDIR keeps that
    # churn away from the shared temporary directory other tests running in parallel stat.
    with tempfile.TemporaryDirectory(prefix="blockpops-conformance-") as private:
        environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "TMPDIR": private}
        completed = subprocess.run(
            [sys.executable, str(REPO / "scripts" / "ci" / "mod_base_kit.py"), "run", "conformance", "--repo",
             str(REPO), "--all"],
            cwd=REPO, env=environment, capture_output=True, timeout=TIMEOUT_SECONDS, check=False)
    if completed.returncode != 0:
        raise AssertionError(f"conformance exited {completed.returncode}: "
                             f"{completed.stderr.decode('utf-8', 'replace')[-4000:]}")
    return json.loads(completed.stdout)


class BlockPopsConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        mod_base_path.kit_root()
        cls.report = run_conformance()

    def test_the_one_enrolled_master_key_passes_every_generation(self) -> None:
        report = self.report
        self.assertEqual("The-Plum-Team/Block-Pops-Minecraft-Mod", report["repository"])
        self.assertEqual([{"anchor": True, "comparisons": 6, "frames": 44, "key": MASTER_KEY, "lanes": 4,
                           "scope": "complete"}], report["keys"])
        self.assertEqual([], report["families"])
        self.assertGreater(report["checks"], 100)
        site = report["site"]
        self.assertTrue(site["node_check"])
        self.assertEqual(44, site["frames"])
        self.assertLessEqual(site["max_job_reads"], 160)
        self.assertEqual([2], [generation["generation"] for generation in site["generations"]])

    def test_block_pops_source_policy_variants_pass(self) -> None:
        variants = self.report["variants"]
        self.assertEqual("passed", variants["attested"])
        self.assertEqual("passed", variants["newest-run"])
        for skipped in ("delegated", "selected", "family-outcomes", "carried"):
            self.assertTrue(variants[skipped].startswith("skipped: "), (skipped, variants[skipped]))

    def test_every_block_pops_hook_answers_and_admission_is_always(self) -> None:
        self.assertEqual(["anchor_selection", "collect", "expectation", "expected_source_jobs", "targets"],
                         self.report["hooks"])
        admission = self.report["admission"]
        self.assertIn("deploy:always", admission)
        self.assertIn("recovery:always", admission)
        self.assertFalse(any(entry.startswith("family:") for entry in admission))


if __name__ == "__main__":
    unittest.main()
