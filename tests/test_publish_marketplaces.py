"""Marketplace publication sends only the jars a Build tested, and only its upload steps hold tokens."""

import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path, PurePosixPath

from scripts.release.matrix import load_matrix_inventory, matrix_sha256
from scripts.release.publish_marketplaces import PublishError, changelog, curseforge_ids, verify_bundle

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "release/release-matrix.json"
WORKFLOW = ROOT / ".github/workflows/publish-marketplaces.yml"
TREE = "a" * 40


def lanes():
    return load_matrix_inventory(MATRIX).require_complete()


def write_bundle(root: Path, *, tree: str = TREE, matrix_digest: str | None = None) -> str:
    """A bundle laid out as the Build writes it, with one small jar per lane."""

    digest = matrix_digest or matrix_sha256(MATRIX)
    for lane in lanes():
        node = lane.identity.artifact_node
        filename = PurePosixPath(lane.production_jar).name
        jar = f"jar of {node}".encode()
        (root / "files").mkdir(parents=True, exist_ok=True)
        (root / "files" / filename).write_bytes(jar)
        record = {
            "git_tree": tree,
            "matrix": {"path": "release/release-matrix.json", "sha256": digest},
            "artifacts": [{
                "artifact_node": node, "loader": lane.identity.loader, "minecraft": lane.identity.minecraft,
                "mod_version": lane.mod_version,
                "production": {"bytes": len(jar), "filename": filename, "path": f"files/{filename}",
                               "sha256": hashlib.sha256(jar).hexdigest()},
            }],
        }
        directory = root / "targets" / lane.identity.minecraft / "lanes" / node
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "artifacts.json").write_text(json.dumps(record), encoding="utf-8")
    return lanes()[0].mod_version


class BundleVerificationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.bundle = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def first_jar(self) -> Path:
        return self.bundle / "files" / PurePosixPath(lanes()[0].production_jar).name

    def test_every_lane_of_the_matrix_is_verified_with_its_marketplace_title(self):
        version = write_bundle(self.bundle)
        verified = verify_bundle(self.bundle, version=version, tree=TREE)
        self.assertEqual(sorted(lane.identity.artifact_node for lane in lanes()), [lane["node"] for lane in verified])
        first = next(lane for lane in verified if lane["node"] == "fabric-1.20.1")
        self.assertEqual(f"Block Pops {version} [Fabric] [MC 1.20.1]", first["title"])
        self.assertEqual(17, first["java"])

    def test_a_jar_whose_bytes_changed_is_refused(self):
        version = write_bundle(self.bundle)
        self.first_jar().write_bytes(b"jar of something else")
        with self.assertRaisesRegex(PublishError, "differs from the one its lane recorded"):
            verify_bundle(self.bundle, version=version, tree=TREE)

    def test_a_linked_jar_is_refused(self):
        version = write_bundle(self.bundle)
        jar = self.first_jar()
        target = self.bundle / "elsewhere.jar"
        target.write_bytes(jar.read_bytes())
        jar.unlink()
        jar.symlink_to(target)
        with self.assertRaisesRegex(PublishError, "regular file"):
            verify_bundle(self.bundle, version=version, tree=TREE)

    def test_another_tree_or_matrix_or_version_is_refused(self):
        version = write_bundle(self.bundle, tree="b" * 40)
        with self.assertRaisesRegex(PublishError, "was built from tree"):
            verify_bundle(self.bundle, version=version, tree=TREE)
        write_bundle(self.bundle, matrix_digest="0" * 64)
        with self.assertRaisesRegex(PublishError, "another release matrix"):
            verify_bundle(self.bundle, version=version, tree=TREE)
        write_bundle(self.bundle)
        with self.assertRaisesRegex(PublishError, "not 0.0.1"):
            verify_bundle(self.bundle, version="0.0.1", tree=TREE)

    def test_a_missing_lane_record_is_refused(self):
        version = write_bundle(self.bundle)
        lane = lanes()[-1]
        (self.bundle / "targets" / lane.identity.minecraft / "lanes" / lane.identity.artifact_node
         / "artifacts.json").unlink()
        with self.assertRaisesRegex(PublishError, "0 artifacts.json"):
            verify_bundle(self.bundle, version=version, tree=TREE)


class ReleaseNotesTests(unittest.TestCase):
    def test_the_matrix_version_has_its_changelog(self):
        version = lanes()[0].mod_version
        self.assertTrue(changelog(version).strip())

    def test_curseforge_ids_name_one_version_of_each_kind(self):
        versions = [("minecraft-1-20", "1.20.1", 1), ("modloader", "Fabric", 2), ("environment", "Client", 3),
                    ("environment", "Server", 4), ("java", "Java 17", 5), ("modloader", "Forge", 6)]
        lane = {"minecraft": "1.20.1", "loader": "fabric", "java": 17}
        self.assertEqual([1, 2, 3, 4, 5], curseforge_ids(versions, lane))
        with self.assertRaisesRegex(PublishError, "2 minecraft versions"):
            curseforge_ids([*versions, ("minecraft-1-20-snapshot", "1.20.1", 7)], lane)


def steps(text: str) -> dict[str, str]:
    body = text.split("    steps:\n", 1)[1]
    found = re.split(r"(?m)^      - name: ", body)[1:]
    return {step.split("\n", 1)[0]: step for step in found}


class WorkflowBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text("utf-8")

    def test_only_an_operator_dispatch_starts_it_with_no_default_permission(self):
        header = self.text.split("\njobs:\n", 1)[0]
        self.assertEqual(["workflow_dispatch"], re.findall(r"(?m)^  ([a-z_]+):$", header.split("\non:\n", 1)[1]
                                                                 .split("\n\n", 1)[0]))
        self.assertIn("\npermissions: {}\n", header)
        job = self.text.split("\njobs:\n", 1)[1].split("    steps:\n", 1)[0]
        self.assertIn("    environment: marketplaces\n", job)
        self.assertTrue(job.endswith("    permissions:\n      actions: read\n      contents: read\n"))

    def test_tokens_reach_only_their_own_upload_step(self):
        found = steps(self.text)
        self.assertEqual(["Check out the default branch's exact commit", "Check the inputs and the branch",
                          "Authenticate the tested Build and its jars", "Publish to Modrinth",
                          "Publish to CurseForge"], list(found))
        for name, step in found.items():
            with self.subTest(step=name):
                secrets = re.findall(r"secrets\.([A-Z_]+)", step)
                expected = {"Publish to Modrinth": ["MODRINTH_TOKEN"],
                            "Publish to CurseForge": ["CURSEFORGE_TOKEN"]}.get(name, [])
                self.assertEqual(expected, secrets)
                if expected:
                    self.assertNotRegex(step, r"GH_TOKEN|GITHUB_TOKEN|github\.token")
                    self.assertIn("unset ACTIONS_RUNTIME_TOKEN", step)
        self.assertIn("persist-credentials: false", found["Check out the default branch's exact commit"])

    def test_inputs_reach_scripts_only_through_the_environment(self):
        for name, step in steps(self.text).items():
            run = step.split("        run: |\n", 1)[1] if "        run: |\n" in step else ""
            with self.subTest(step=name):
                self.assertNotIn("${{", run)


if __name__ == "__main__":
    unittest.main()
