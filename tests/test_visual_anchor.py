from __future__ import annotations

import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image

from e2e.scenario_contract import default_contract
from scripts.ci.tests.matrix_fixtures import (
    canonical_integration_matrix,
    write_matrix_fixture,
)
from scripts.pages import evidence, visual_anchor
from scripts.release.matrix import load_matrix
from tests.matrix_fixtures import schema1_source_matrix
from tests.test_pages_publication import (
    COMMIT,
    REPO_NAME,
    TREE,
    _comparison,
    _fixture_profiles,
    _metrics,
)


REPO = Path(__file__).resolve().parents[1]
BRANCH_MATRIX_PATH = schema1_source_matrix()


class VisualAnchorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        branch_matrix = load_matrix(BRANCH_MATRIX_PATH)
        self.matrix_path = write_matrix_fixture(
            self.root / "canonical",
            canonical_integration_matrix(branch_matrix),
        )
        self.matrix = load_matrix(self.matrix_path)
        self.branch = self.matrix["branch"]["name"]
        self.node = self.matrix["visual_reference"]["artifact_node"]
        self.packaged = self.root / "packaged"
        _fixture_profiles(self.packaged, self.matrix_path)
        self.raw = self.root / "raw"
        with mock.patch.object(
            evidence, "inspect_screenshot_for_step", return_value=_metrics()
        ), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            self.raw_manifest = evidence.curate(
                input_root=self.packaged,
                output=self.raw,
                matrix_path=self.matrix_path,
                repository=REPO_NAME,
                branch=self.branch,
                commit=COMMIT,
                tree=TREE,
                run_id=101,
                run_attempt=2,
                controller_branch=self.branch,
                controller_sha=COMMIT,
            )
        self.anchor = self.root / "anchor"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @property
    def matrix_sha256(self) -> str:
        return hashlib.sha256(self.matrix_path.read_bytes()).hexdigest()

    @property
    def raw_name(self) -> str:
        return evidence.raw_artifact_name(self.branch, 2)

    def create(self, output: Path | None = None) -> dict:
        with mock.patch.object(
            evidence, "inspect_screenshot_for_step", return_value=_metrics()
        ), mock.patch.object(
            evidence, "compare_screenshots", return_value=_comparison()
        ):
            return visual_anchor.create_anchor(
                input_root=self.raw,
                output=output or self.anchor,
                matrix_path=self.matrix_path,
                repository=REPO_NAME,
                branch=self.branch,
                commit=COMMIT,
                tree=TREE,
                source_run_id=101,
                source_run_attempt=2,
                source_controller_branch=self.branch,
                source_controller_sha=COMMIT,
                raw_artifact_id=501,
                raw_artifact_name_value=self.raw_name,
                # upload-artifact emits a bare digest; manifests normalize it.
                raw_artifact_digest="5" * 64,
            )

    def expected(self) -> dict:
        return {
            "repository": REPO_NAME,
            "branch": self.branch,
            "commit": COMMIT,
            "tree": TREE,
            "matrix_sha256": self.matrix_sha256,
            "contract_sha256": default_contract().sha256,
            "handoff": {
                "path": evidence.E2E_WORKFLOW,
                "run_id": 101,
                "run_attempt": 2,
                "controller_branch": self.branch,
                "controller_sha": COMMIT,
            },
            "source_artifact": {
                "id": 501,
                "name": self.raw_name,
                "digest": "sha256:" + "5" * 64,
            },
        }

    def test_anchor_keeps_only_exact_original_canonical_png_bytes(self) -> None:
        manifest = self.create()
        identity = visual_anchor.anchor_identity(
            self.matrix_path,
            branch=self.branch,
            commit=COMMIT,
            run_id=101,
            run_attempt=2,
        )
        self.assertTrue(identity["eligible"])
        self.assertEqual(
            identity["artifact"],
            visual_anchor.visual_anchor_artifact_name(
                self.branch, COMMIT, 101, 2
            ),
        )
        self.assertNotEqual(
            identity["artifact"],
            visual_anchor.visual_anchor_artifact_name(
                self.branch, COMMIT, 101, 3
            ),
        )
        self.assertEqual(manifest["reference"]["artifact_node"], self.node)
        self.assertEqual(manifest["source_artifact"], self.expected()["source_artifact"])
        self.assertEqual(
            {lane["artifact_node"] for lane in manifest["lanes"]}, {self.node}
        )
        raw_frames = {
            frame["capture_id"]: frame
            for frame in self.raw_manifest["frames"]
            if frame["artifact_node"] == self.node
        }
        self.assertEqual(set(raw_frames), {frame["capture_id"] for frame in manifest["frames"]})
        for frame in manifest["frames"]:
            source = frame["source"]
            original = self.raw / raw_frames[frame["capture_id"]]["source"]["path"]
            anchored = self.anchor / source["path"]
            self.assertEqual(anchored.read_bytes(), original.read_bytes())
            with Image.open(anchored) as image:
                image.load()
                self.assertEqual(
                    source["pixel_sha256"],
                    hashlib.sha256(image.convert("RGB").tobytes()).hexdigest(),
                )
        self.assertEqual(
            {path.relative_to(self.anchor).as_posix() for path in self.anchor.rglob("*") if path.is_file()},
            {visual_anchor.ANCHOR_MANIFEST}
            | {record["path"] for record in manifest["files"]},
        )
        validated = visual_anchor.validate_anchor(
            self.anchor,
            matrix_path=self.matrix_path,
            expected=self.expected(),
        )
        self.assertEqual(validated, manifest)

    def test_noncanonical_branch_cannot_create_or_name_an_anchor(self) -> None:
        release = copy.deepcopy(self.matrix)
        release["branch"] = {
            "role": "release",
            "name": "release/test-fixture",
            "canonical": self.branch,
            "sync": {"enabled": True, "source": self.branch},
        }
        release_path = write_matrix_fixture(self.root / "release", release)
        identity = visual_anchor.anchor_identity(
            release_path,
            branch="release/test-fixture",
            commit=COMMIT,
            run_id=101,
            run_attempt=2,
        )
        self.assertFalse(identity["eligible"])
        self.assertEqual(identity["artifact"], "")
        with self.assertRaisesRegex(
            visual_anchor.VisualAnchorError, "does not own the canonical"
        ):
            visual_anchor.create_anchor(
                input_root=self.raw,
                output=self.anchor,
                matrix_path=release_path,
                repository=REPO_NAME,
                branch="release/test-fixture",
                commit=COMMIT,
                tree=TREE,
                source_run_id=101,
                source_run_attempt=2,
                source_controller_branch=self.branch,
                source_controller_sha=COMMIT,
                raw_artifact_id=501,
                raw_artifact_name_value=evidence.raw_artifact_name(
                    "release/test-fixture", 2
                ),
                raw_artifact_digest="5" * 64,
            )

    def _copy_anchor(self, name: str) -> Path:
        destination = self.root / name
        shutil.copytree(self.anchor, destination)
        return destination

    @staticmethod
    def _manifest(root: Path) -> tuple[Path, dict]:
        path = root / visual_anchor.ANCHOR_MANIFEST
        return path, json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write_manifest(path: Path, manifest: dict) -> None:
        path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def test_manifest_duplicate_keys_traversal_and_unknown_fields_fail_closed(self) -> None:
        self.create()
        duplicate = self._copy_anchor("duplicate")
        duplicate_manifest = duplicate / visual_anchor.ANCHOR_MANIFEST
        text = duplicate_manifest.read_text(encoding="utf-8")
        duplicate_manifest.write_text(
            text.replace(
                '"kind": "lossless-visual-anchor",',
                '"kind": "lossless-visual-anchor",\n  "kind": "lossless-visual-anchor",',
                1,
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "duplicate"):
            visual_anchor.validate_anchor(duplicate, matrix_path=self.matrix_path)

        traversal = self._copy_anchor("traversal")
        path, manifest = self._manifest(traversal)
        manifest["files"][0]["path"] = "../escape.png"
        self._write_manifest(path, manifest)
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "canonical"):
            visual_anchor.validate_anchor(traversal, matrix_path=self.matrix_path)

        unknown = self._copy_anchor("unknown")
        path, manifest = self._manifest(unknown)
        manifest["trusted_by_default"] = True
        self._write_manifest(path, manifest)
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "unknown"):
            visual_anchor.validate_anchor(unknown, matrix_path=self.matrix_path)

    def test_symlink_extra_file_oversize_and_decode_mutations_fail_closed(self) -> None:
        self.create()
        symlink = self._copy_anchor("symlink")
        image = next((symlink / "images").iterdir())
        image.unlink()
        image.symlink_to("../visual-anchor.json")
        with self.assertRaises(visual_anchor.VisualAnchorError):
            visual_anchor.validate_anchor(symlink, matrix_path=self.matrix_path)

        extra = self._copy_anchor("extra")
        (extra / "images" / "unmanifested.png").write_bytes(b"not an image")
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "inventory"):
            visual_anchor.validate_anchor(extra, matrix_path=self.matrix_path)

        oversized = self._copy_anchor("oversized")
        image = next((oversized / "images").iterdir())
        with image.open("wb") as stream:
            stream.seek(visual_anchor.MAX_SCREENSHOT_BYTES)
            stream.write(b"x")
        with self.assertRaises(visual_anchor.VisualAnchorError):
            visual_anchor.validate_anchor(oversized, matrix_path=self.matrix_path)

        incompatible = self._copy_anchor("incompatible")
        path, manifest = self._manifest(incompatible)
        old_record = manifest["files"][0]
        old_image = incompatible / old_record["path"]
        old_relative = old_image.relative_to(incompatible).as_posix()
        replacement = self.root / "small.png"
        Image.new("RGB", (800, 900), (1, 2, 3)).save(replacement, "PNG")
        data = replacement.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        new_relative = f"images/{digest}.png"
        old_image.unlink()
        (incompatible / new_relative).write_bytes(data)
        old_record.update(path=new_relative, sha256=digest, size=len(data))
        # Update every frame which referred to the pre-mutation content address.
        for frame in manifest["frames"]:
            if frame["source"]["path"] == old_relative:
                frame["source"].update(
                    path=new_relative,
                    sha256=digest,
                    size=len(data),
                )
        self._write_manifest(path, manifest)
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "dimensions"):
            visual_anchor.validate_anchor(incompatible, matrix_path=self.matrix_path)

    def test_pixel_digest_and_api_derived_source_identity_are_recomputed(self) -> None:
        self.create()
        controller = self._copy_anchor("controller")
        path, manifest = self._manifest(controller)
        manifest["provenance"]["source_run"]["controller_sha"] = "8" * 40
        self._write_manifest(path, manifest)
        with self.assertRaisesRegex(
            visual_anchor.VisualAnchorError, "source/controller identity"
        ):
            visual_anchor.validate_anchor(controller, matrix_path=self.matrix_path)

        pixel = self._copy_anchor("pixel")
        path, manifest = self._manifest(pixel)
        manifest["frames"][0]["source"]["pixel_sha256"] = "9" * 64
        self._write_manifest(path, manifest)
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "pixel identity"):
            visual_anchor.validate_anchor(pixel, matrix_path=self.matrix_path)

        stale_expected = self.expected()
        stale_expected["source_artifact"] = dict(stale_expected["source_artifact"])
        stale_expected["source_artifact"]["id"] = 999
        with self.assertRaisesRegex(visual_anchor.VisualAnchorError, "source artifact"):
            visual_anchor.validate_anchor(
                self.anchor,
                matrix_path=self.matrix_path,
                expected=stale_expected,
            )


if __name__ == "__main__":
    unittest.main()
