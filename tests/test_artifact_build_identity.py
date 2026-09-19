"""Embedded lane provenance is checked against caller-owned expected inputs."""

import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.artifact_manifest import (
    ArtifactError, BUILD_IDENTITY_PATH, lane_build_identity, verify_harness_jar, verify_production_jar,
)
from scripts.release.matrix import MatrixDocument, MatrixError, normalize_matrix_inventory
from tests.test_artifact_and_report_validation import (
    _fabric_harness_entries, _fabric_production_entries, _write_zip,
)


class ArtifactBuildIdentityTests(unittest.TestCase):
    def setUp(self):
        self.matrix = schema2_configuration()
        self.document = MatrixDocument(normalize_matrix_inventory(self.matrix), json.dumps(self.matrix))
        self.node = "fabric-1.20.1"
        self.artifact = self.document.inventory.lane(self.node).artifact
        self.inputs = dict(matrix_digest="a" * 64, contract_digest="b" * 64,
                           commit="c" * 40, tree="d" * 40)
        self.expected = lane_build_identity(self.document, self.node, **self.inputs)

    def entries(self, harness=False):
        entries = _fabric_harness_entries() if harness else _fabric_production_entries()
        if not harness:
            metadata = json.loads(entries["fabric.mod.json"])
            metadata["version"] = self.expected["mod_version"]
            entries["fabric.mod.json"] = json.dumps(metadata).encode()
        entries[BUILD_IDENTITY_PATH] = json.dumps(self.expected).encode()
        return entries

    def test_separate_archives_share_exact_expected_lane_inputs(self):
        with tempfile.TemporaryDirectory() as raw:
            for harness in (False, True):
                path = Path(raw) / ("harness.jar" if harness else "production.jar")
                _write_zip(path, self.entries(harness))
                verify = verify_harness_jar if harness else verify_production_jar
                verify(path, self.artifact, build_identity=self.expected)
        self.assertEqual("1.20.1", self.expected["minecraft"])
        self.assertEqual((17, 21), (self.expected["java"], self.expected["gradle_java"]))

    def test_missing_stale_cross_lane_extra_and_coercible_identity_fail(self):
        with tempfile.TemporaryDirectory() as raw:
            for harness in (False, True):
                for key, value in (("missing", None), ("artifact_node", "fabric-1.21.1"),
                                   ("java", 17.0), ("gradle_java", True), ("schema_version", 1.0),
                                   ("matrix_sha256", "e" * 64), ("git_tree", "f" * 40),
                                   ("git_commit", "f" * 40), ("scenario_contract_sha256", "e" * 64),
                                   ("build_context_sha256", "e" * 64), ("extra", "untrusted")):
                    entries = self.entries(harness)
                    observed = copy.deepcopy(self.expected)
                    if key == "missing":
                        entries.pop(BUILD_IDENTITY_PATH)
                    else:
                        observed[key] = value
                        entries[BUILD_IDENTITY_PATH] = json.dumps(observed).encode()
                    path = Path(raw) / "renamed.jar"
                    _write_zip(path, entries)
                    verify = verify_harness_jar if harness else verify_production_jar
                    with self.subTest(harness=harness, key=key), self.assertRaises(ArtifactError):
                        verify(path, self.artifact, build_identity=self.expected)

    def test_mod_version_cannot_be_forged_by_only_changing_embedded_identity(self):
        entries = self.entries()
        changed = dict(self.expected, mod_version="99.0.0")
        entries[BUILD_IDENTITY_PATH] = json.dumps(changed).encode()
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "production.jar"
            _write_zip(path, entries)
            with self.assertRaisesRegex(ArtifactError, "different artifact lane"):
                verify_production_jar(path, self.artifact, build_identity=changed)
            entries = self.entries()
            metadata = json.loads(entries["fabric.mod.json"])
            metadata["version"] = "99.0.0"
            entries["fabric.mod.json"] = json.dumps(metadata).encode()
            _write_zip(path, entries)
            with self.assertRaisesRegex(ArtifactError, "Fabric production mod version"):
                verify_production_jar(path, self.artifact, build_identity=self.expected)

    def test_fml_version_is_read_from_its_own_mod_record(self):
        node = "neoforge-1.21.1"
        artifact = self.document.inventory.lane(node).artifact
        identity = lane_build_identity(self.document, node, **self.inputs)
        metadata = artifact["metadata"]
        entries = {
            "com/theplumteam/BlockPopsMod.class": b"class",
            "com/theplumteam/neoforge/BlockPopsModForge.class": b"class",
            "blockpops.mixins.json": b"{}",
            BUILD_IDENTITY_PATH: json.dumps(identity).encode(),
        }
        dependencies = "".join(f'[[dependencies.blockpops]]\nmodId = "{name}"\nversionRange = "{metadata[name]}"\n'
                               for name in ("minecraft", "architectury", "geckolib"))
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "production.jar"
            for version in (identity["mod_version"], "99.0.0", "${version}"):
                entries[metadata["file"]] = (f'[[mods]]\nmodId = "blockpops"\nversion = "{version}"\n'
                                            + dependencies).encode()
                _write_zip(path, entries)
                if version == identity["mod_version"]:
                    verify_production_jar(path, artifact, build_identity=identity)
                else:
                    with self.assertRaisesRegex(ArtifactError, "FML production mod version"):
                        verify_production_jar(path, artifact, build_identity=identity)

    def test_builder_rejects_unresolved_lanes_and_unusable_caller_digests(self):
        with self.assertRaises(MatrixError):
            lane_build_identity(self.document, "fabric-1.21.7", **self.inputs)
        for field in self.inputs:
            for invalid in (None, True, "", "A" * len(self.inputs[field]), "0" * 39):
                arguments = dict(self.inputs, **{field: invalid})
                with self.subTest(field=field, invalid=invalid), self.assertRaises(ArtifactError):
                    lane_build_identity(self.document, self.node, **arguments)
        other = lane_build_identity(self.document, "neoforge-1.21.1", **self.inputs)
        self.assertNotEqual(self.expected["build_context_sha256"], other["build_context_sha256"])


if __name__ == "__main__":
    unittest.main()
