"""Pages writers must not follow replaced output directories or delete impostors."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.lib import atomic_directory
from scripts.pages import evidence


class AtomicPagesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.parent = self.root / "publication"
        self.parent.mkdir()
        self.output = self.parent / "bundle"
        self.held = self.root / "held"
        self.outside = self.root / "unrelated"
        self.outside.mkdir()
        self.sentinel = self.outside / "sentinel"
        self.sentinel.write_bytes(b"preserve")

    def swap_parent(self, stage):
        self.parent.rename(self.held)
        self.parent.symlink_to(self.outside, target_is_directory=True)
        impostor = self.outside / stage.name
        impostor.mkdir()
        return impostor

    def assert_preserved(self):
        self.assertEqual(b"preserve", self.sentinel.read_bytes())
        self.assertFalse((self.outside / "bundle").exists())

    def test_parent_swap_after_writing_cannot_publish_impostor(self):
        def writer(stage):
            evidence._write_json(stage / "manifest.json", {"checked": True})
            (self.swap_parent(stage) / "impostor").write_bytes(b"unvalidated")
        with self.assertRaises(evidence.EvidenceError):
            evidence._atomic_directory(self.output, writer)
        self.assert_preserved()
        self.assertEqual([b"unvalidated"], [p.read_bytes() for p in self.outside.glob(".bundle.building-*/impostor")])
        self.assertEqual([], list(self.held.iterdir()))

    def test_parent_swap_before_write_never_writes_in_impostor(self):
        def writer(stage):
            self.swap_parent(stage)
            evidence._copy_bytes(stage / "nested/value", b"owned")
        with self.assertRaises(evidence.EvidenceError):
            evidence._atomic_directory(self.output, writer)
        self.assert_preserved()
        self.assertEqual([], [p for d in self.outside.glob(".bundle.building-*") for p in d.rglob("*")])
        self.assertEqual([], list(self.held.iterdir()))

    def test_replaced_stage_is_not_published_or_cleaned(self):
        def writer(stage):
            stage.rename(self.held)
            stage.mkdir()
            (stage / "impostor").write_bytes(b"keep")
            evidence._copy_bytes(stage / "owned", b"owned")
        with self.assertRaises(evidence.EvidenceError):
            evidence._atomic_directory(self.output, writer)
        self.assertFalse(self.output.exists())
        self.assertEqual([b"keep"], [p.read_bytes() for p in self.parent.glob(".bundle.building-*/impostor")])
        self.assertEqual([], list(self.held.iterdir()))

    def test_concurrent_destination_is_preserved(self):
        occupied = []
        def writer(stage):
            evidence._copy_bytes(stage / "owned", b"owned")
            self.output.mkdir()
            occupied.append(self.output.stat().st_ino)
        with self.assertRaises(evidence.EvidenceError):
            evidence._atomic_directory(self.output, writer)
        self.assertEqual(occupied, [self.output.stat().st_ino])
        self.assertEqual([], list(self.output.iterdir()))

    def test_success_failure_and_nested_writer_context_restore(self):
        def inner(stage):
            evidence._copy_bytes(stage / "value", b"inner")
        def outer(stage):
            evidence._atomic_directory(self.root / "inner", inner)
            evidence._write_json(stage / "manifest.json", {"outer": True})
            return "result"
        self.assertEqual("result", evidence._atomic_directory(self.output, outer))
        self.assertEqual(b"inner", (self.root / "inner/value").read_bytes())
        self.assertTrue((self.output / "manifest.json").is_file())
        def failed(stage):
            evidence._copy_bytes(stage / "owned", b"owned")
            (stage / "link").symlink_to(self.outside, target_is_directory=True)
            raise OSError("simulated failure")
        with self.assertRaisesRegex(OSError, "simulated"):
            evidence._atomic_directory(self.parent / "failed", failed)
        self.assertEqual(b"preserve", self.sentinel.read_bytes())
        self.assertEqual([], list(self.parent.glob(".failed.building-*")))

    def test_unavailable_primitives_fail_before_parent_creation(self):
        with patch.object(atomic_directory.os, "supports_dir_fd", set()):
            with self.assertRaises(evidence.EvidenceError):
                evidence._atomic_directory(self.root / "missing/bundle", lambda stage: None)
        self.assertFalse((self.root / "missing").exists())

    def test_escaped_writes_and_failed_inner_scope_cannot_redirect_outer(self):
        def failed(stage):
            raise OSError("inner failed")
        def outer(stage):
            for destination in (self.sentinel, stage / "../escape"):
                with self.assertRaises((evidence.EvidenceError, atomic_directory.AtomicDirectoryError)):
                    evidence._copy_bytes(destination, b"changed")
            with self.assertRaisesRegex(OSError, "inner failed"):
                evidence._atomic_directory(self.root / "inner-failure", failed)
            evidence._copy_bytes(stage / "value", b"outer")
        evidence._atomic_directory(self.output, outer)
        self.assertEqual(b"outer", (self.output / "value").read_bytes())
        self.assertEqual(b"preserve", self.sentinel.read_bytes())
        self.assertFalse((self.parent / "escape").exists())
        self.assertIsNone(evidence._owned_output.get())
