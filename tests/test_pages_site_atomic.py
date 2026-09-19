"""Static-site publication must preserve foreign directories during path replacement."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.lib import atomic_directory
from scripts.pages import build_site as site, evidence
from tests import test_pages_publication as fixtures


class AtomicSiteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture = fixtures.EvidenceRoundTripTests()
        fixture.setUp(); cls.addClassCleanup(fixture.tearDown)
        fixture.make_compact()
        cls.collected = fixture.root / "collected"
        cls.collected.mkdir()
        fixture.compact.rename(cls.collected / evidence.collection_artifact_name(fixtures.ACTIVE_BRANCH, fixtures.COMMIT))
        cls.inventory = fixture.root / "inventory.json"
        cls.inventory.write_text(json.dumps(fixtures._inventory()))

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.parent = self.root / "publication"; self.parent.mkdir()
        self.output = self.parent / "site"
        self.held = self.root / "held"
        self.outside = self.root / "outside"; self.outside.mkdir()
        self.sentinel = self.outside / "sentinel"; self.sentinel.write_bytes(b"preserve")

    def build(self, **overrides):
        return site.build(**{**dict(evidence_root=self.collected, inventory_path=self.inventory,
            output=self.output, repository=fixtures.REPO_NAME, canonical_matrix=fixtures.MATRIX_PATH), **overrides})

    def test_default_schema1_site_preserves_static_and_compact_bytes(self):
        summary = self.build()
        self.assertEqual(1, summary["branches"])
        for relative in ("index.html", "assets/site.css", "assets/gallery.js"):
            self.assertEqual((site.SITE_SOURCE / relative).read_bytes(), (self.output / relative).read_bytes())
        gallery = json.loads((self.output / "gallery-data.json").read_bytes())
        self.assertEqual(fixtures.COMMIT, gallery["releases"][0]["commit"])
        self.assertEqual(fixtures.TREE, gallery["releases"][0]["tree"])
        self.assertEqual(summary["frames"], len(gallery["frames"]))
        for frame in gallery["frames"]:
            self.assertEqual(frame["published_sha256"], evidence.sha256_bytes((self.output / frame["image"]).read_bytes()))

    def test_parent_swap_before_static_copy_cannot_write_or_publish_foreign_stage(self):
        original = site._copy_static
        def swapped(*arguments):
            stage = next(self.parent.glob(".site.building-*"))
            self.parent.rename(self.held)
            self.parent.symlink_to(self.outside, target_is_directory=True)
            impostor = self.outside / stage.name; impostor.mkdir()
            (impostor / "impostor").write_bytes(b"keep")
            return original(*arguments)
        with patch.object(site, "_copy_static", side_effect=swapped):
            with self.assertRaises(site.SiteError): self.build()
        self.assertEqual(b"preserve", self.sentinel.read_bytes())
        self.assertFalse((self.outside / "site").exists())
        self.assertEqual(["impostor"], [p.name for d in self.outside.glob(".site.building-*") for p in d.iterdir()])
        self.assertEqual([], list(self.held.iterdir()))

    def test_replaced_stage_is_neither_published_nor_deleted(self):
        original = site._copy_static
        def swapped(*arguments):
            stage = next(self.parent.glob(".site.building-*"))
            result = original(*arguments)
            stage.rename(self.held); stage.mkdir()
            (stage / "impostor").write_bytes(b"keep")
            return result
        with patch.object(site, "_copy_static", side_effect=swapped):
            with self.assertRaises(site.SiteError): self.build()
        self.assertFalse(self.output.exists())
        self.assertEqual(["impostor"], [p.name for d in self.parent.glob(".site.building-*") for p in d.iterdir()])
        self.assertEqual([], list(self.held.iterdir()))

    def test_concurrent_empty_destination_is_not_replaced(self):
        original = site._copy_static
        occupied = []
        def occupied_output(*arguments):
            result = original(*arguments)
            self.output.mkdir(); occupied.append(self.output.stat().st_ino)
            return result
        with patch.object(site, "_copy_static", side_effect=occupied_output):
            with self.assertRaises(site.SiteError): self.build()
        self.assertEqual(occupied, [self.output.stat().st_ino])
        self.assertEqual([], list(self.output.iterdir()))

    def test_unsafe_parent_and_unavailable_primitives_fail_before_publication(self):
        link = self.root / "link"; link.symlink_to(self.outside, target_is_directory=True)
        with self.assertRaises(site.SiteError): self.build(output=link / "site")
        self.assertEqual([self.sentinel], list(self.outside.iterdir()))
        with patch.object(atomic_directory.os, "supports_dir_fd", set()):
            with self.assertRaises(site.SiteError): self.build(output=self.root / "missing/site")
        self.assertFalse((self.root / "missing").exists())
