"""Opt-in Pages inspection binds explicit coverage to real immutable Git blobs."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import TARGET_COUNT, schema1_matrix, schema2_configuration
from scripts.release import version_branches as discovery


class PagesBranchDiscoveryTests(unittest.TestCase):
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

    def commit(self, matrix, *, raw=None, advance=True):
        raw = (json.dumps(matrix, indent=2) + "\n").encode() if raw is None else raw
        self.path.write_bytes(raw)
        self.git("add", "release/release-matrix.json")
        self.git("commit", "-qm", "matrix fixture", "--allow-empty")
        commit = self.git("rev-parse", "HEAD")
        if advance: self.git("update-ref", self.ref, commit)
        return commit, raw

    def inspect(self, scope="legacy", **overrides):
        return discovery.inspect_pages_branch(self.repo, **{
            "branch": self.branch, "ref": self.ref, "canonical_branch": "master", "scope": scope, **overrides})

    def test_schema1_preparing_and_shared_bind_exact_raw_bytes_and_selected_inventory(self):
        for matrix, scope, count in ((schema1_matrix(), "unscoped", 2),
                                     (schema2_configuration(), "legacy", 2),
                                     (schema2_configuration(shared=True), "full", TARGET_COUNT)):
            with self.subTest(scope=scope):
                raw = (json.dumps(matrix, indent=2) + "\n").replace("\n", "\r\n").encode()
                commit, raw = self.commit(matrix, raw=raw)
                result = self.inspect(scope)
                self.assertEqual(commit, result["commit"])
                self.assertEqual(self.git("rev-parse", commit + "^{tree}"), result["tree"])
                self.assertEqual(self.git("rev-parse", commit + ":release/release-matrix.json"), result["matrix_blob"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["matrix_sha256"])
                self.assertEqual(matrix["schema_version"], result["matrix_schema_version"])
                selected = result["scope"]["selected_nodes"]
                rows = [row for row in matrix["artifacts"] if row["artifact_node"] in selected]
                self.assertEqual(count, len(selected))
                self.assertEqual(sorted({row["minecraft"] for row in rows}, key=lambda v: tuple(map(int, v.split(".")))),
                                 result["minecraft_versions"])
                self.assertEqual(sorted({row["loader"] for row in rows}), result["loaders"])
                self.assertEqual(sorted({row["java"] for row in rows}), result["java"])
                self.assertEqual({row["artifact_node"] for row in matrix["artifacts"]}, set(result["configured_nodes"]))
                self.assertEqual(2 if scope == "unscoped" else TARGET_COUNT, len(result["scope"]["target_nodes"]))
                self.assertEqual(scope == "legacy", result["scope"]["partial"])
                self.assertEqual(scope, result["scope"]["kind"])
                self.assertNotIn("projection", result)
                self.assertNotIn("qualified", result)

    def test_arbitrary_release_name_retains_exact_enrollment_identity(self):
        matrix = schema2_configuration()
        self.branch = "ship/aurora-ui"
        self.ref = "refs/remotes/origin/" + self.branch
        matrix["branch"] = {"name": self.branch, "canonical": "master", "role": "release",
                            "sync": {"enabled": True, "source": "master"}}
        self.commit(matrix)
        result = self.inspect()
        self.assertEqual(self.branch, result["name"])
        self.assertEqual(["1.20.1"], result["minecraft_versions"])

    def test_crossed_missing_or_incomplete_scopes_never_fall_back(self):
        for matrix, scopes in ((schema1_matrix(), ("legacy", "full")),
                (schema2_configuration(), ("unscoped", "full", "lane", "unknown", None)),
                (schema2_configuration(shared=True), ("legacy", "unscoped"))):
            self.commit(matrix)
            for scope in scopes:
                with self.subTest(scope=scope), self.assertRaises(discovery.BranchDiscoveryError):
                    self.inspect(scope)

    def test_branch_claim_corruption_and_unsafe_blob_modes_are_rejected(self):
        matrix = schema2_configuration()
        self.commit(matrix)
        for overrides in ({"branch": "feature/copied-matrix"}, {"canonical_branch": "develop"}, {"branch": "../unsafe"}):
            with self.subTest(overrides=overrides), self.assertRaises(discovery.BranchDiscoveryError):
                self.inspect(**overrides)
        malformed = schema2_configuration()
        malformed["runtimes"].pop()
        for raw in (b'{"schema_version":2,"schema_version":2}', json.dumps(malformed).encode(),
                    b"not JSON", b" " * (discovery.MAX_MATRIX_BYTES + 1)):
            self.commit(matrix, raw=raw)
            with self.assertRaises(discovery.BranchDiscoveryError): self.inspect()
        commit, _ = self.commit(matrix)
        blob = self.git("rev-parse", commit + ":release/release-matrix.json")
        for mode in ("100755", "120000", "160000"):
            oid = commit if mode == "160000" else blob
            self.git("update-index", "--cacheinfo", f"{mode},{oid},release/release-matrix.json")
            self.git("commit", "-qm", "unsafe matrix mode")
            self.git("update-ref", self.ref, self.git("rev-parse", "HEAD"))
            with self.subTest(mode=mode), self.assertRaisesRegex(discovery.BranchDiscoveryError, "regular non-executable"):
                self.inspect()

    def test_one_blob_read_and_normalization_ignore_mutable_worktree_bytes(self):
        self.commit(schema2_configuration())
        expected = self.inspect()
        self.path.write_bytes(b"uncommitted broken worktree")
        with patch.object(discovery, "_pages_git", wraps=discovery._pages_git) as git, \
             patch.object(discovery, "secure_loads", wraps=discovery.secure_loads) as read, \
             patch.object(discovery, "normalize_matrix_inventory", wraps=discovery.normalize_matrix_inventory) as normalize:
            self.assertEqual(expected, self.inspect())
        self.assertEqual(1, read.call_count)
        self.assertEqual(1, normalize.call_count)
        self.assertEqual(1, sum(call.args[1:3] == ("cat-file", "blob") for call in git.call_args_list))

    def test_ref_movement_and_changed_blob_payload_are_not_mixed_into_a_snapshot(self):
        matrix = schema2_configuration()
        _, raw = self.commit(matrix)
        matrix["project"]["description"] += " changed"
        newer, _ = self.commit(matrix, advance=False)
        original = discovery._pages_git
        def move(repository, *args):
            value = original(repository, *args)
            if args[:2] == ("cat-file", "blob"):
                self.git("update-ref", self.ref, newer)
                self.assertEqual(raw, value)
            return value
        with patch.object(discovery, "_pages_git", side_effect=move):
            with self.assertRaisesRegex(discovery.BranchDiscoveryError, "ref changed"):
                self.inspect()
        def corrupt(repository, *args):
            value = original(repository, *args)
            return b" " + value[1:] if args[:2] == ("cat-file", "blob") else value
        with patch.object(discovery, "_pages_git", side_effect=corrupt):
            with self.assertRaisesRegex(discovery.BranchDiscoveryError, "blob identity"):
                self.inspect()

    def test_replacement_objects_and_graft_environment_cannot_redefine_source(self):
        matrix = schema2_configuration()
        original, _ = self.commit(matrix)
        expected = self.inspect()
        matrix["project"]["description"] += " replacement"
        replacement, _ = self.commit(matrix, advance=False)
        self.git("replace", original, replacement)
        graft = self.repo / ".git/info/grafts"
        graft.write_text(original + " " + replacement + "\n")
        run = subprocess.run
        with patch.dict(os.environ, {"GIT_NO_REPLACE_OBJECTS": "0", "GIT_GRAFT_FILE": str(graft)}), \
             patch.object(discovery.subprocess, "run", wraps=run) as calls:
            self.assertEqual(expected, self.inspect())
        for call in calls.call_args_list:
            self.assertEqual("1", call.kwargs["env"]["GIT_NO_REPLACE_OBJECTS"])
            self.assertEqual(os.devnull, call.kwargs["env"]["GIT_GRAFT_FILE"])

    def test_ambient_git_redirection_cannot_replace_the_explicit_repository(self):
        matrix = schema2_configuration()
        self.commit(matrix)
        expected = self.inspect()
        foreign = self.repo / "foreign repository"
        foreign.mkdir()
        self.git("-C", str(foreign), "init", "-q", "-b", self.branch)
        self.git("-C", str(foreign), "config", "user.name", "Other")
        self.git("-C", str(foreign), "config", "user.email", "other@example.invalid")
        path = foreign / "release/release-matrix.json"
        path.parent.mkdir()
        matrix["project"]["description"] += " foreign repository"
        path.write_text(json.dumps(matrix))
        self.git("-C", str(foreign), "add", "release/release-matrix.json")
        self.git("-C", str(foreign), "commit", "-qm", "foreign matrix")
        self.git("-C", str(foreign), "update-ref", self.ref, "HEAD")
        poison = {"GIT_DIR": str(foreign / ".git"), "GIT_WORK_TREE": str(foreign),
                  "GIT_COMMON_DIR": str(foreign / ".git"), "GIT_OBJECT_DIRECTORY": str(foreign / ".git/objects"),
                  "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(foreign / ".git/objects"),
                  "GIT_INDEX_FILE": str(foreign / ".git/index"), "GIT_CONFIG_COUNT": "1",
                  "GIT_CONFIG_KEY_0": "core.bare", "GIT_CONFIG_VALUE_0": "true",
                  "GIT_CONFIG_PARAMETERS": "'core.abbrev=4'"}
        run = subprocess.run
        for environment in ({"GIT_DIR": poison["GIT_DIR"]}, poison):
            with self.subTest(environment=environment), patch.dict(os.environ, environment), \
                 patch.object(discovery.subprocess, "run", wraps=run) as calls:
                self.assertEqual(expected, self.inspect())
            for call in calls.call_args_list:
                self.assertEqual({"GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": os.devnull,
                                  "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull},
                                 {key: value for key, value in call.kwargs["env"].items() if key.startswith("GIT_")})

    def test_explicit_linked_worktree_head_survives_ambient_main_checkout_redirection(self):
        matrix = schema2_configuration()
        self.commit(matrix)
        expected = self.inspect()
        worktree = self.repo / "linked checkout"
        self.git("worktree", "add", "--detach", str(worktree), "HEAD")
        matrix["project"]["description"] += " newer main checkout"
        self.commit(matrix)
        with patch.dict(os.environ, {"GIT_DIR": str(self.repo / ".git"), "GIT_WORK_TREE": str(self.repo),
                                     "GIT_COMMON_DIR": str(self.repo / ".git")}):
            result = discovery.inspect_pages_branch(worktree, branch=self.branch, ref="HEAD",
                                                    canonical_branch="master", scope="legacy")
        self.assertEqual(expected, result)

    def test_default_sync_discovery_and_cli_stay_schema1_only_and_byte_compatible(self):
        matrix = schema1_matrix()
        _, raw = self.commit(matrix)
        legacy = discovery.inspect_branch(self.repo, branch=self.branch, ref=self.ref, canonical_branch="master")
        for route in matrix["source_routing"].values():
            for path in (route["canonical"], *route["overlays"].values()):
                (self.repo / path).mkdir(parents=True, exist_ok=True)
        stdout = io.StringIO()
        args = ["--repository", str(self.repo), "--matrix", str(self.path), "--objects", "--include-integration"]
        with contextlib.redirect_stdout(stdout): self.assertEqual(0, discovery.main(args))
        self.assertEqual(json.dumps([legacy.as_dict()], sort_keys=True, separators=(",", ":")) + "\n", stdout.getvalue())
        _, raw = self.commit(schema2_configuration())
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.inspect_branch(self.repo, branch=self.branch, ref=self.ref, canonical_branch="master")
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.discover_repository(self.repo, remote="origin", integration_branch="master")
        with self.assertRaises(discovery.BranchDiscoveryError):
            discovery.discover_from_snapshots({self.branch: raw}, integration_branch="master")
        with contextlib.redirect_stderr(io.StringIO()): self.assertEqual(2, discovery.main(args))
