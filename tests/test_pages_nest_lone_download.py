"""A lone downloaded cache is nested under the name its promotion inventory gives it."""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from scripts.pages.evidence import cache_artifact_name
from scripts.pages.nest_lone_download import NestError, main, nest_lone_cache

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/pages.yml"
COMMIT = "1" * 40
ROW = {"name": "master", "commit": COMMIT, "tree": "2" * 40}


class NestLoneDownloadTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / "owner-caches"
        self.root.mkdir()
        self.inventory = self.base / "pages-inventory.json"
        self.write_inventory([ROW])

    def write_inventory(self, rows):
        self.inventory.write_text(json.dumps(rows))

    def files(self):
        return sorted(str(path.relative_to(self.root)) for path in self.root.rglob("*") if path.is_file())

    def populate(self, *relatives):
        for relative in relatives:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(relative)

    def test_a_flattened_lone_cache_moves_under_its_artifact_name(self):
        self.populate("manifest.json", "release-matrix.json", "images/a/frame.webp")
        name = cache_artifact_name("master", COMMIT)
        self.assertEqual(name, nest_lone_cache(self.root, self.inventory))
        self.assertEqual([f"{name}/images/a/frame.webp", f"{name}/manifest.json", f"{name}/release-matrix.json"],
                         self.files())
        self.assertEqual(["owner-caches", "pages-inventory.json"], sorted(p.name for p in self.base.iterdir()))

    def test_named_directories_and_empty_roots_are_left_alone(self):
        several = [cache_artifact_name("master", COMMIT) + "/manifest.json", "pages-cache-other/manifest.json"]
        self.populate(*several)
        self.assertIsNone(nest_lone_cache(self.root, self.inventory))
        self.assertEqual(sorted(several), self.files())
        for path in list(self.root.rglob("*"))[::-1]:
            path.unlink() if path.is_file() else path.rmdir()
        self.assertIsNone(nest_lone_cache(self.root, self.inventory))

    def test_a_linked_manifest_is_not_taken_for_a_bundle(self):
        (self.base / "elsewhere.json").write_text("{}")
        (self.root / "manifest.json").symlink_to(self.base / "elsewhere.json")
        self.assertIsNone(nest_lone_cache(self.root, self.inventory))

    def test_a_lone_cache_needs_exactly_one_nameable_branch(self):
        self.populate("manifest.json")
        for rows in ([], [ROW, {**ROW, "name": "other"}], [{**ROW, "commit": "short"}], [{"name": 1, "commit": COMMIT}],
                     {"name": "master"}):
            with self.subTest(rows=rows):
                self.write_inventory(rows)
                with self.assertRaises(NestError):
                    nest_lone_cache(self.root, self.inventory)
                self.assertEqual(["manifest.json"], self.files())
        self.write_inventory([ROW])
        arguments = ["--root", str(self.root), "--inventory", str(self.inventory)]
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, main(arguments))
        self.assertIn(cache_artifact_name("master", COMMIT), output.getvalue())
        self.inventory.write_text("[]")
        self.assertEqual(0, main(arguments))

    def test_both_rotations_nest_after_their_downloads_and_before_deleting(self):
        steps = WORKFLOW.read_text().split("      - name: ")
        nests = [index for index, step in enumerate(steps)
                 if step.startswith("Keep a lone promoted cache under its artifact name\n")]
        self.assertEqual(2, len(nests))
        for index in nests:
            self.assertIn("pattern: pages-cache-*\n          path: owner-caches", steps[index - 2])
            self.assertIn("name: pages-promotion\n          path: owner-promotion", steps[index - 1])
            self.assertIn("python3 scripts/pages/nest_lone_download.py\n          --root owner-caches\n"
                          "          --inventory owner-promotion/pages-inventory.json\n", steps[index])
            self.assertIn("python3 scripts/pages/rotate_artifacts.py", steps[index + 1])
            self.assertIn("--caches-root owner-caches", steps[index + 1])


if __name__ == "__main__":
    unittest.main()
