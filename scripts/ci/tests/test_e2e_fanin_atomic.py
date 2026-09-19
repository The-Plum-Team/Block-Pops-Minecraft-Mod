"""Directory substitution cannot redirect fan-in writes, publication or cleanup."""

import unittest
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests import test_e2e_fanin_scoped_producer as producer_fixture


class AtomicAggregateTests(unittest.TestCase):
    def setUp(self):
        self.case = producer_fixture.ScopedAggregateProducerTests()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.case.prepare()
        self.parent = self.case.inputs.root / "publication"
        self.case.output = self.parent / "aggregate"
        self.outside = self.case.inputs.root / "unrelated"
        self.outside.mkdir()
        self.sentinel = self.outside / "sentinel.txt"
        self.sentinel.write_bytes(b"untouched")
        self.held = self.case.inputs.root / "held-parent"

    def replace_parent(self, stage):
        self.parent.rename(self.held)
        self.parent.symlink_to(self.outside, target_is_directory=True)
        impostor = self.outside / stage.name
        impostor.mkdir()
        return impostor

    def test_parent_replacement_after_validation_cannot_publish_unvalidated_external_tree(self):
        original = fanin.validate_aggregate
        def substituted(**kwargs):
            result = original(**kwargs)
            impostor = self.replace_parent(kwargs["root"])
            (impostor / "unvalidated.txt").write_bytes(b"not validated")
            return result
        with patch.object(fanin, "validate_aggregate", side_effect=substituted):
            with self.assertRaises(fanin.FanInError): self.case.create()
        self.assertFalse((self.outside / "aggregate").exists())
        self.assertEqual(b"untouched", self.sentinel.read_bytes())
        self.assertEqual([b"not validated"], [p.read_bytes() for p in self.outside.glob(".aggregate.building-*/unvalidated.txt")])
        self.assertEqual([], list(self.held.glob(".aggregate.building-*")))

    def test_parent_replacement_before_first_write_never_writes_outside_owned_stage(self):
        original = fanin._write_new
        replaced = False
        def substituted(*args, **kwargs):
            nonlocal replaced
            if not replaced:
                replaced = True
                self.replace_parent(next(self.parent.glob(".aggregate.building-*")))
            return original(*args, **kwargs)
        with patch.object(fanin, "_write_new", side_effect=substituted):
            with self.assertRaises(fanin.FanInError): self.case.create()
        self.assertEqual(b"untouched", self.sentinel.read_bytes())
        self.assertEqual([], [p for d in self.outside.glob(".aggregate.building-*") for p in d.rglob("*")])
        self.assertEqual([], list(self.held.glob(".aggregate.building-*")))

    def test_replaced_stage_is_neither_published_nor_removed_by_cleanup(self):
        original = fanin.validate_aggregate
        held_stage = self.case.inputs.root / "held-stage"
        def substituted(**kwargs):
            result = original(**kwargs)
            stage = kwargs["root"]
            stage.rename(held_stage)
            stage.mkdir()
            (stage / "unrelated.txt").write_bytes(b"preserve replacement")
            return result
        with patch.object(fanin, "validate_aggregate", side_effect=substituted):
            with self.assertRaises(fanin.FanInError): self.case.create()
        self.assertFalse(self.case.output.exists())
        self.assertEqual([b"preserve replacement"], [p.read_bytes() for p in self.parent.glob(".aggregate.building-*/unrelated.txt")])
        self.assertEqual([], list(held_stage.iterdir()))

    def test_concurrent_empty_output_is_preserved_by_exclusive_publication(self):
        original = fanin.validate_aggregate
        identity = []
        def occupied(**kwargs):
            result = original(**kwargs)
            self.case.output.mkdir()
            identity.append(self.case.output.stat().st_ino)
            return result
        with patch.object(fanin, "validate_aggregate", side_effect=occupied):
            with self.assertRaises((fanin.FanInError, FileExistsError)): self.case.create()
        self.assertEqual(identity, [self.case.output.stat().st_ino])
        self.assertEqual([], list(self.case.output.iterdir()))
        self.assertEqual([], list(self.parent.glob(".aggregate.building-*")))

    def test_cleanup_of_failed_owned_stage_unlinks_links_without_following_them(self):
        original = fanin._write_new
        def failed(*args, **kwargs):
            original(*args, **kwargs)
            stage = next(self.parent.glob(".aggregate.building-*"))
            (stage / "external-link").symlink_to(self.outside, target_is_directory=True)
            raise OSError("simulated write failure")
        with patch.object(fanin, "_write_new", side_effect=failed):
            with self.assertRaises(OSError): self.case.create()
        self.assertEqual(b"untouched", self.sentinel.read_bytes())
        self.assertEqual([], list(self.parent.glob(".aggregate.building-*")))
        self.assertFalse(self.case.output.exists())

    def test_parent_replacement_immediately_after_rename_rolls_back_only_owned_output(self):
        rename = fanin.atomic_directory._exclusive_directory_rename()
        def substituted(parent, source, destination):
            rename(parent, source, destination)
            self.parent.rename(self.held)
            self.parent.symlink_to(self.outside, target_is_directory=True)
            impostor = self.outside / "aggregate"
            impostor.mkdir()
            (impostor / "unrelated.txt").write_bytes(b"keep external output")
        with patch.object(fanin.atomic_directory, "_exclusive_directory_rename", return_value=substituted):
            with self.assertRaises(fanin.FanInError): self.case.create()
        self.assertFalse((self.held / "aggregate").exists())
        self.assertEqual(b"keep external output", (self.outside / "aggregate/unrelated.txt").read_bytes())
        self.assertEqual(b"untouched", self.sentinel.read_bytes())

    def test_missing_descriptor_primitives_fail_before_creating_output_parent(self):
        with patch.object(fanin.os, "supports_dir_fd", set()):
            with self.assertRaises(fanin.FanInError): self.case.create()
        self.assertFalse(self.parent.exists())


if __name__ == "__main__":
    unittest.main()
