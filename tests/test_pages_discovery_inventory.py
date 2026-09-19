"""Pages dispatch inventory uses exact Git snapshots, independently of sync."""

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.release import version_branches as discovery


class PagesInventoryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name).resolve()
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.path = self.repo / "release/release-matrix.json"
        self.path.parent.mkdir()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE).decode().strip()

    def publish(self, branch, matrix, *, raw=None, enrolled=True):
        matrix = copy.deepcopy(matrix)
        if matrix is not None:
            if enrolled:
                matrix["branch"] = dict(name=branch, canonical="master", role="integration" if branch == "master" else "release",
                    sync=dict(enabled=branch != "master", source="master"))
            raw = (json.dumps(matrix, indent=2) + "\n").encode() if raw is None else raw
            self.path.write_bytes(raw)
            self.git("add", "release/release-matrix.json")
        else:
            self.git("rm", "--ignore-unmatch", "release/release-matrix.json")
        self.git("commit", "-qm", "branch fixture", "--allow-empty")
        commit = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/" + branch, commit)
        return commit, raw

    def discover(self, **overrides):
        return discovery.discover_pages_repository(self.repo, **{
            "remote": "origin", "canonical_branch": "master", "include_integration": True, **overrides})

    def test_mixed_branch_schemas_choose_their_own_default_scope_and_exact_identity(self):
        expected = {}
        for name, matrix, scope in (("master", schema2_configuration(), "legacy"),
                ("shipping/art-show", schema1_matrix(), "unscoped"),
                ("releases/modern", schema2_configuration(shared=True), "full")):
            expected[name] = (*self.publish(name, matrix), scope)
        self.publish("feature/copied-matrix", schema2_configuration(), enrolled=False)
        self.publish("feature/unreadable", {}, raw=b"not JSON", enrolled=False)
        self.publish("feature/no-matrix", None)
        self.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/master")
        result = self.discover()
        self.assertEqual(sorted(expected), [row["name"] for row in result])
        for row in result:
            commit, raw, scope = expected[row["name"]]
            self.assertEqual(commit, row["commit"])
            self.assertEqual(self.git("rev-parse", commit + "^{tree}"), row["tree"])
            self.assertEqual(self.git("rev-parse", commit + ":release/release-matrix.json"), row["matrix_blob"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row["matrix_sha256"])
            self.assertEqual(scope, row["scope"]["kind"])
            self.assertEqual(12 if scope == "full" else 2, len(row["scope"]["selected_nodes"]))
            self.assertNotIn("projection", row["scope"])
            self.assertNotIn("projection", row)
            self.assertNotIn("qualified", row)
        self.assertEqual([row for row in result if row["name"] != "master"], self.discover(include_integration=False))

    def test_each_enrolled_blob_is_read_and_normalized_once(self):
        self.publish("master", schema2_configuration())
        self.publish("release/older", schema1_matrix())
        with patch.object(discovery, "_pages_git", wraps=discovery._pages_git) as git, \
             patch.object(discovery, "secure_loads", wraps=discovery.secure_loads) as parse, \
             patch.object(discovery, "normalize_matrix_inventory", wraps=discovery.normalize_matrix_inventory) as normalize:
            self.assertEqual(2, len(self.discover()))
        self.assertEqual(2, sum(call.args[1:3] == ("cat-file", "blob") for call in git.call_args_list))
        self.assertEqual((2, 2), (parse.call_count, normalize.call_count))

    def test_canonical_absence_or_corruption_is_never_skipped(self):
        self.publish("release/older", schema1_matrix())
        with self.assertRaisesRegex(discovery.BranchDiscoveryError, "canonical.*absent"): self.discover()
        for raw in (b"not JSON", b"{}", b'{"schema_version":2,"schema_version":1}'):
            self.publish("master", {}, raw=raw, enrolled=False)
            with self.assertRaises(discovery.BranchDiscoveryError): self.discover(include_integration=False)
        self.publish("master", None)
        with self.assertRaisesRegex(discovery.BranchDiscoveryError, "regular"): self.discover()

    def test_self_enrolled_inconsistent_or_incomplete_release_fails_without_fallback(self):
        self.publish("master", schema2_configuration())
        for kind in ("canonical", "sync", "unresolved", "unknown-schema"):
            matrix = schema2_configuration()
            matrix["branch"] = dict(name="release/current", role="release", canonical="master",
                sync=dict(enabled=True, source="master"))
            if kind == "canonical": matrix["branch"]["canonical"] = "other"
            if kind == "sync": matrix["branch"]["sync"]["enabled"] = False
            if kind == "unresolved": matrix["migration"]["mode"] = "shared"
            if kind == "unknown-schema": matrix["schema_version"] = 99
            self.publish("release/current", matrix, enrolled=False)
            with self.subTest(kind=kind), self.assertRaises(discovery.BranchDiscoveryError): self.discover()

    def test_ref_movement_deletion_and_new_branch_invalidate_the_whole_inventory(self):
        old, _ = self.publish("master", schema2_configuration())
        other, _ = self.publish("feature/copied", schema1_matrix(), enrolled=False)
        original = discovery._pages_git
        for change in ("move", "delete", "add"):
            self.git("update-ref", "refs/remotes/origin/master", old)
            self.git("update-ref", "-d", "refs/remotes/origin/new")
            changed = False
            def mutate(repository, *args):
                nonlocal changed
                result = original(repository, *args)
                if not changed and args[:2] == ("cat-file", "blob"):
                    changed = True
                    if change == "move": self.git("update-ref", "refs/remotes/origin/master", other)
                    elif change == "delete": self.git("update-ref", "-d", "refs/remotes/origin/master")
                    else: self.git("update-ref", "refs/remotes/origin/new", other)
                return result
            with patch.object(discovery, "_pages_git", side_effect=mutate):
                with self.subTest(change=change), self.assertRaisesRegex(discovery.BranchDiscoveryError, "inventory changed"):
                    self.discover()

    def test_enumeration_uses_the_explicit_repository_and_rejects_symbolic_aliases(self):
        self.publish("master", schema2_configuration())
        expected = self.discover()
        foreign = self.repo / "foreign repository"
        foreign.mkdir(); self.git("-C", str(foreign), "init", "-q")
        with patch.dict(os.environ, {"GIT_DIR": str(foreign / ".git"), "GIT_CONFIG_COUNT": "1",
                                     "GIT_CONFIG_KEY_0": "core.bare", "GIT_CONFIG_VALUE_0": "true"}):
            self.assertEqual(expected, self.discover())
        self.git("symbolic-ref", "refs/remotes/origin/alias", "refs/remotes/origin/master")
        with self.assertRaisesRegex(discovery.BranchDiscoveryError, "ref identity"): self.discover()
        for remote in (None, "../origin", "origin/other"):
            with self.assertRaises(discovery.BranchDiscoveryError): self.discover(remote=remote)

    def test_inventory_limit_and_non_boolean_integration_selection_fail_closed(self):
        commit, _ = self.publish("master", schema2_configuration())
        with self.assertRaises(discovery.BranchDiscoveryError): self.discover(include_integration=1)
        updates = "".join(f"update refs/remotes/origin/feature-{index:04} {commit}\n" for index in range(1000))
        subprocess.run(["git", "-C", str(self.repo), "update-ref", "--stdin"],
            input=updates.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        with self.assertRaisesRegex(discovery.BranchDiscoveryError, "exceeds 1000"): self.discover()

    def test_pages_cli_uses_external_canonical_and_default_sync_stays_identical(self):
        self.publish("master", schema1_matrix())
        matrix = schema1_matrix()
        for route in matrix["source_routing"].values():
            for path in (route["canonical"], *route["overlays"].values()): (self.repo / path).mkdir(parents=True, exist_ok=True)
        args = ["--repository", str(self.repo), "--matrix", str(self.path), "--objects", "--include-integration"]
        before = io.StringIO()
        with contextlib.redirect_stdout(before): self.assertEqual(0, discovery.main(args))
        expected = discovery.inspect_branch(self.repo, branch="master", ref="refs/remotes/origin/master", canonical_branch="master")
        self.assertEqual(json.dumps([expected.as_dict()], sort_keys=True, separators=(",", ":")) + "\n", before.getvalue())
        self.publish("master", schema2_configuration())
        self.path.write_bytes(b"mutable checkout is not Pages inventory")
        pages = args + ["--pages", "--canonical-branch", "master"]
        output = io.StringIO()
        with contextlib.redirect_stdout(output): self.assertEqual(0, discovery.main(pages))
        self.assertEqual(self.discover(), json.loads(output.getvalue()))
        for invalid in (args, args + ["--pages"], pages + ["--target", "unknown"], pages + ["--target", ""],
                        args + ["--canonical-branch", "master"]):
            with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()) as rejected:
                self.assertEqual(2, discovery.main(invalid))
            self.assertEqual("", rejected.getvalue())
