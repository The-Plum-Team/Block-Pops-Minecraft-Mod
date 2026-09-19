"""Legacy staging consumes validated lanes without enabling unscoped schema 2."""

from __future__ import annotations

import copy
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import canonical_integration_matrix, schema2_configuration
from scripts.release.artifact_manifest import ArtifactError, ROOT_KEYS, stage_release, verify_staged
from scripts.release.matrix import MatrixError
from tests.test_artifact_and_report_validation import MATRIX, _fabric_harness_entries, _fabric_production_entries


class ArtifactNormalizedLaneTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "release/release-matrix.json"
        self.path.parent.mkdir()
        self.stage = self.root / "build/release"
        self.manifest_path = self.stage / "artifacts.json"
        self.matrix = canonical_integration_matrix(MATRIX)
        self.write_matrix(self.matrix)
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("add", "release/release-matrix.json")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "-qm", "fixture")
        self.commit = self.git("rev-parse", "HEAD")
        self.tree = self.git("rev-parse", "HEAD^{tree}")
        environment = patch.dict(os.environ, {"BLOCKPOPS_TESTED_SHA": self.commit})
        environment.start()
        self.addCleanup(environment.stop)
        artifact = self.matrix["artifacts"][0]
        for relative, entries in ((artifact["jar"].replace("{mod_version}", self.matrix["project"]["mod_version"]),
                                    _fabric_production_entries()),
                                   (artifact["harness_jar"], _fabric_harness_entries())):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(target, "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True,
                                       stderr=subprocess.PIPE).strip()

    def write_matrix(self, matrix):
        for route in matrix["source_routing"].values():
            for key in ("canonical", "e2e"):
                if key in route:
                    (self.root / route[key]).mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(matrix))

    def arguments(self):
        return dict(repository=self.root, matrix_path=self.path,
                    manifest_path=self.manifest_path, stage=self.stage)

    def test_legacy_manifest_round_trip_preserves_identity_and_exact_artifact_records(self):
        report = stage_release(**self.arguments())
        self.assertEqual(ROOT_KEYS, set(report))
        self.assertEqual((2, 1, self.commit, self.tree),
                         tuple(report[key] for key in ("schema_version", "lane_count", "git_commit", "git_tree")))
        self.assertEqual({"path": "release/release-matrix.json",
                          "sha256": hashlib.sha256(self.path.read_bytes()).hexdigest()}, report["matrix"])
        artifact = self.matrix["artifacts"][0]
        row = report["artifacts"][0]
        for key in ("artifact_node", "minecraft", "loader", "java"):
            self.assertEqual(artifact[key], row[key])
        for kind, directory in (("production", "files"), ("harness", "harness")):
            record = row[kind]
            path = self.stage / record["path"]
            self.assertEqual(directory, path.parent.name)
            self.assertEqual(path.name, record["filename"])
            self.assertEqual(path.stat().st_size, record["bytes"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), record["sha256"])
        self.assertEqual(report, verify_staged(**self.arguments()))

    def test_unknown_duplicate_and_cross_lane_manifest_rows_fail(self):
        original = stage_release(**self.arguments())
        for key, value in (("artifact_node", "forge-1.20.1"), ("minecraft", "1.21.1"),
                           ("loader", "neoforge"), ("java", 21)):
            changed = copy.deepcopy(original)
            changed["artifacts"][0][key] = value
            self.manifest_path.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ArtifactError):
                verify_staged(**self.arguments())
        original["artifacts"].append(copy.deepcopy(original["artifacts"][0]))
        self.manifest_path.write_text(json.dumps(original))
        with self.assertRaises(ArtifactError):
            verify_staged(**self.arguments())

    def test_schema_two_stays_blocked_before_staging_or_git_provenance(self):
        for shared in (False, True):
            self.write_matrix(schema2_configuration(shared=shared))
            with patch("scripts.release.artifact_manifest.git_commit") as git, self.assertRaisesRegex(
                ArtifactError, "scoped schema-3"
            ):
                stage_release(**self.arguments())
            git.assert_not_called()
            self.assertFalse(self.stage.exists())

    def test_inconsistent_matrix_fails_before_copying_outputs(self):
        self.matrix["runtimes"][0]["artifact_node"] = "fabric-1.21.1"
        self.write_matrix(self.matrix)
        with self.assertRaises(MatrixError):
            stage_release(**self.arguments())
        self.assertFalse(self.stage.exists())


if __name__ == "__main__":
    unittest.main()
