"""Archive transport binding uses real ZIPs and exclusive filesystem publication."""

import hashlib
import os
import stat
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.release import evidence_archive as archive


class ReleaseEvidenceArchiveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.zip = self.root / "input.zip"
        self.output = self.root / "output"

    def write(self, entries, **options):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.zip, "w", **options) as package:
                for name, payload in entries: package.writestr(name, payload)

    def read(self, kind="bundle", **changes):
        return archive.extract_evidence_archive(self.zip, self.output, **{**dict(kind=kind,
            expected_digest="sha256:" + hashlib.sha256(self.zip.read_bytes()).hexdigest(),
            expected_size=self.zip.stat().st_size), **changes})

    def test_each_profile_preserves_payloads_and_external_byte_bindings(self):
        for kind, required in (("bundle", "artifacts.json"), ("report", "build-matrix-report.json"),
                               ("aggregate", "aggregate.json")):
            self.output = self.root / kind
            entries = [(required, b"{}")]
            if kind == "bundle": entries += [("files/", b""), ("files/opaque.jar", b"payload")]
            if kind == "aggregate": entries += [("profiles/lane/logs/server.log", b"")]
            self.write(entries, compression=zipfile.ZIP_DEFLATED)
            result = self.read(kind)
            self.assertEqual(self.zip.stat().st_size, result["archive_bytes"])
            self.assertEqual(hashlib.sha256(self.zip.read_bytes()).hexdigest(), result["archive_sha256"])
            self.assertEqual(sorted(name for name, _ in entries if not name.endswith("/")), [row["path"] for row in result["files"]])
            for name, payload in entries:
                if not name.endswith("/"): self.assertEqual(payload, (self.output / name).read_bytes())
            self.assertNotIn("qualified", result)

    def test_external_types_digests_size_and_kind_cannot_be_inferred(self):
        self.write([("artifacts.json", b"{}")])
        for changes in ({"expected_size": True}, {"expected_size": 1.0}, {"expected_size": 1},
                {"expected_size": 257 * 1024 * 1024}, {"expected_digest": "sha256:" + "0" * 64},
                {"expected_digest": "0" * 64}, {"kind": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(archive.EvidenceArchiveError): self.read(**changes)
            self.assertFalse(self.output.exists())

    def test_directory_count_is_bounded_before_zipfile_parses_entries(self):
        self.write([("artifacts.json", b"{}"), ("payload", b"data")])
        original = self.zip.read_bytes()
        for count in (1, 10000):
            changed = bytearray(original)
            end = changed.rfind(b"PK\x05\x06")
            struct.pack_into("<HH", changed, end + 8, count, count)
            self.zip.write_bytes(changed)
            with patch.object(archive.zipfile, "ZipFile") as parser, self.assertRaises(archive.EvidenceArchiveError):
                self.read()
            parser.assert_not_called()

    def test_nul_in_original_zip_names_is_not_silently_normalized(self):
        self.write([("artifacts.jsonX", b"{}")])
        raw = self.zip.read_bytes(); self.assertEqual(2, raw.count(b"artifacts.jsonX"))
        self.zip.write_bytes(raw.replace(b"artifacts.jsonX", b"artifacts.json\0"))
        with self.assertRaises(archive.EvidenceArchiveError): self.read()
        self.assertFalse(self.output.exists())

    def test_implicit_directories_are_bounded_before_creating_any_output(self):
        self.write([("aggregate.json", b"{}"), ("a/b/c/d/logs/server.log", b"")])
        with patch.dict(archive.PROFILES, aggregate=(5, 100, 100, "aggregate.json")), \
             patch.object(archive.atomic_directory, "atomic_directory") as writer:
            with self.assertRaisesRegex(archive.EvidenceArchiveError, "physical inventory"): self.read("aggregate")
        writer.assert_not_called()

    def test_final_archive_rehash_cannot_leave_mutated_payload_or_extra_inventory(self):
        for target in ("payload", "file", "directory"):
            self.write([("artifacts.json", b"{}")])
            original = hashlib.file_digest; inode = self.zip.stat().st_ino; calls = 0
            def digest(stream, algorithm, *args, **kwargs):
                nonlocal calls
                result = original(stream, algorithm, *args, **kwargs)
                if os.fstat(stream.fileno()).st_ino == inode:
                    calls += 1
                    if calls == 2:
                        stage = next(self.root.glob(".output.building-*"))
                        if target == "payload": (stage / "artifacts.json").write_bytes(b"[]")
                        elif target == "file": (stage / "unbound.txt").write_bytes(b"extra")
                        else: (stage / "unbound").mkdir()
                return result
            with patch.object(archive.hashlib, "file_digest", side_effect=digest):
                with self.subTest(target=target), self.assertRaises(archive.EvidenceArchiveError): self.read()
            self.assertFalse(self.output.exists())

    def test_unsafe_or_oversized_inventories_never_publish(self):
        link = zipfile.ZipInfo("link"); link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        cases = [[("../escape", b"bad")], [("artifacts.json", b"{}"), ("artifacts.json", b"{}")] ,
            [("artifacts.json", b"{}"), (link, b"target")], [("other.json", b"{}")],
            [("artifacts.json", b"")], [("artifacts.json", b"{}"), ("directory/", b"data")],
            [("artifacts.json", b"{}"), ("a", b"file"), ("a/child", b"conflict")]]
        for entries in cases:
            self.write(entries)
            with self.subTest(entries=entries), self.assertRaises(archive.EvidenceArchiveError): self.read()
            self.assertFalse(self.output.exists())
            self.assertFalse(list(self.root.glob(".output.building-*")))
        self.write([("artifacts.json", b"{}"), ("bomb", b"x" * 100000)], compression=zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(archive.EvidenceArchiveError, "compression ratio"): self.read()
        self.write([("artifacts.json", b"{}"), ("data", b"1234")])
        for limits in ((1, 10, 10, "artifacts.json"), (3, 3, 10, "artifacts.json"), (3, 10, 5, "artifacts.json")):
            with patch.dict(archive.PROFILES, bundle=limits), self.assertRaises(archive.EvidenceArchiveError): self.read()

    def test_linked_archive_and_existing_or_substituted_output_are_rejected(self):
        self.write([("artifacts.json", b"{}")]); original = self.zip
        for kind in ("symlink", "hardlink"):
            self.zip = self.root / kind
            if kind == "symlink": self.zip.symlink_to(original)
            else: os.link(original, self.zip)
            with self.assertRaises(archive.EvidenceArchiveError): self.read()
            self.zip.unlink()
        self.zip = original
        linked_parent = self.root / "linked"; linked_parent.symlink_to(self.root, target_is_directory=True)
        self.zip = linked_parent / original.name
        with self.assertRaises(archive.EvidenceArchiveError): self.read()
        self.zip = original
        self.output.mkdir(); (self.output / "user.txt").write_bytes(b"keep")
        with self.assertRaises(archive.EvidenceArchiveError): self.read()
        self.assertEqual(b"keep", (self.output / "user.txt").read_bytes())

    def test_archive_and_extracted_file_mutations_are_detected_before_publication(self):
        original_write = archive.atomic_directory.write_new
        for target in ("archive", "output"):
            self.write([("artifacts.json", b"{}")])
            def mutate(stage, relative, payload):
                original_write(stage, relative, payload)
                if target == "archive":
                    with self.zip.open("r+b") as stream:
                        stream.seek(-1, os.SEEK_END); stream.write(b"x")
                else:
                    descriptor = os.open(relative, os.O_WRONLY, dir_fd=stage)
                    try: os.write(descriptor, b"[]")
                    finally: os.close(descriptor)
            with patch.object(archive.atomic_directory, "write_new", side_effect=mutate):
                with self.subTest(target=target), self.assertRaises(archive.EvidenceArchiveError): self.read()
            self.assertFalse(self.output.exists())

    def test_concurrent_output_is_preserved_without_publishing_our_stage(self):
        self.write([("artifacts.json", b"{}")])
        original_write = archive.atomic_directory.write_new
        def competing(stage, relative, payload):
            original_write(stage, relative, payload)
            self.output.mkdir(); (self.output / "owner.txt").write_bytes(b"other")
        with patch.object(archive.atomic_directory, "write_new", side_effect=competing):
            with self.assertRaises(archive.EvidenceArchiveError): self.read()
        self.assertEqual(["owner.txt"], [path.name for path in self.output.iterdir()])
        self.assertFalse(list(self.root.glob(".output.building-*")))
