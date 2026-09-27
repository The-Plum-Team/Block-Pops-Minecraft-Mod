"""The default release-sync discovery CLI stays schema-1 only and byte-compatible.

Re-homed from the retired ``tests/test_pages_branch_discovery.py`` and
``tests/test_pages_discovery_inventory.py``: ``sync-release-branches.yml`` still runs
``scripts/release/version_branches.py --objects``, and this is its only CLI-level test.
"""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.release import version_branches as discovery


class ReleaseSyncDiscoveryCliTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name).resolve()
        self.branch = schema1_matrix()["branch"]["name"]
        self.ref = "refs/remotes/origin/" + self.branch
        self.git("init", "-q", "-b", self.branch)
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.path = self.repo / "release/release-matrix.json"
        self.path.parent.mkdir()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE).decode().strip()

    def commit(self, matrix):
        raw = (json.dumps(matrix, indent=2) + "\n").encode()
        self.path.write_bytes(raw)
        self.git("add", "release/release-matrix.json")
        self.git("commit", "-qm", "matrix fixture", "--allow-empty")
        self.git("update-ref", self.ref, self.git("rev-parse", "HEAD"))
        return raw

    def test_default_sync_discovery_and_cli_stay_schema1_only_and_byte_compatible(self):
        matrix = schema1_matrix()
        self.commit(matrix)
        legacy = discovery.inspect_branch(self.repo, branch=self.branch, ref=self.ref, canonical_branch="master")
        for route in matrix["source_routing"].values():
            for path in (route["canonical"], *route["overlays"].values()):
                (self.repo / path).mkdir(parents=True, exist_ok=True)
        stdout = io.StringIO()
        args = ["--repository", str(self.repo), "--matrix", str(self.path), "--objects", "--include-integration"]
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(0, discovery.main(args))
        self.assertEqual(json.dumps([legacy.as_dict()], sort_keys=True, separators=(",", ":")) + "\n",
                         stdout.getvalue())
        raw = self.commit(schema2_configuration())
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.inspect_branch(self.repo, branch=self.branch, ref=self.ref, canonical_branch="master")
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.discover_repository(self.repo, remote="origin", integration_branch="master")
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.discover_from_snapshots({self.branch: raw}, integration_branch="master")
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()) as rejected:
            self.assertEqual(2, discovery.main(args))
        self.assertEqual("", rejected.getvalue())

    def test_default_cli_rejects_the_pages_only_canonical_option(self):
        self.commit(schema1_matrix())
        args = ["--repository", str(self.repo), "--matrix", str(self.path), "--objects",
                "--include-integration", "--canonical-branch", "master"]
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()) as rejected:
            self.assertEqual(2, discovery.main(args))
        self.assertEqual("", rejected.getvalue())


if __name__ == "__main__":
    unittest.main()
