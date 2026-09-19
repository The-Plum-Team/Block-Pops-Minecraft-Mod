"""The renderer must seal actual output bytes after all input validation."""

import json
import os
import unittest
from unittest.mock import patch

from scripts.pages import build_site as site
from tests import test_pages_site_atomic as legacy, test_pages_site_scope as scoped
from tests import test_pages_compact_selection as bindings


class SiteOutputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        legacy.AtomicSiteTests.setUpClass()
        cls.addClassCleanup(legacy.AtomicSiteTests.doClassCleanups)
        bindings.CompactSelectionTests.setUpClass()
        cls.addClassCleanup(bindings.CompactSelectionTests.doClassCleanups)
        fixture = bindings.CompactSelectionTests()
        fixture.prepare(); cls.addClassCleanup(fixture.doCleanups)
        cls.scoped_template = fixture, fixture.bind()

    def fixture(self, *, scoped_input=False):
        fixture = scoped.ScopedSiteTests() if scoped_input else legacy.AtomicSiteTests()
        if scoped_input: fixture.templates = {False: self.scoped_template}
        fixture.setUp(); self.addCleanup(fixture.doCleanups)
        return fixture

    def corrupt(self, stage, relative):
        descriptor = os.open(relative, os.O_RDWR | os.O_NOFOLLOW, dir_fd=stage)
        try:
            before = os.fstat(descriptor)
            data = bytearray(os.read(descriptor, before.st_size))
            if data: data[-1] ^= 1
            else: data.append(1)
            os.lseek(descriptor, 0, os.SEEK_SET); os.write(descriptor, data)
            after = os.fstat(descriptor)
            self.assertEqual(before.st_ino, after.st_ino)
            if before.st_size: self.assertEqual(before.st_size, after.st_size)
        finally:
            os.close(descriptor)

    def test_every_output_kind_is_bound_to_the_bytes_actually_supplied(self):
        write = site.write_new
        for selected in ("image", "index.html", "assets/site.css", "assets/gallery.js", "gallery-data.json", ".nojekyll"):
            fixture = self.fixture()
            def changed(stage, relative, data):
                write(stage, relative, data)
                if relative == "gallery-data.json":
                    target = json.loads(data)["frames"][0]["image"] if selected == "image" else selected
                    self.corrupt(stage, target)
            with patch.object(site, "write_new", side_effect=changed):
                with self.subTest(selected=selected), self.assertRaises(site.SiteError): fixture.build()
            self.assertFalse(fixture.output.exists())

    def test_extra_files_directories_links_specials_and_replaced_files_are_rejected(self):
        write = site.write_new
        for kind in ("file", "directory", "symlink", "fifo", "replacement-directory", "replacement-link"):
            fixture = self.fixture()
            def changed(stage, relative, data):
                write(stage, relative, data)
                if relative != "gallery-data.json": return
                if kind == "file": write(stage, "unreferenced", b"unvalidated")
                elif kind == "directory": os.mkdir("unused", dir_fd=stage)
                elif kind == "symlink": os.symlink(fixture.sentinel, "foreign", dir_fd=stage)
                elif kind == "fifo": os.mkfifo("fifo", dir_fd=stage)
                elif kind == "replacement-directory":
                    os.unlink("index.html", dir_fd=stage); os.mkdir("index.html", dir_fd=stage)
                else:
                    os.unlink("index.html", dir_fd=stage); os.symlink(fixture.sentinel, "index.html", dir_fd=stage)
            with patch.object(site, "write_new", side_effect=changed):
                with self.subTest(kind=kind), self.assertRaises(site.SiteError): fixture.build()
            self.assertFalse(fixture.output.exists())
            self.assertEqual(b"preserve", fixture.sentinel.read_bytes())

    def test_stamp_pass_detects_an_earlier_file_changed_while_hashing_a_later_file(self):
        fixture = self.fixture()
        seal, read = site._seal_output, os.read
        changed = []
        def during_seal(stage, expected):
            last = os.stat("index.html", dir_fd=stage).st_ino
            def during_read(descriptor, maximum):
                data = read(descriptor, maximum)
                if data and not changed and os.fstat(descriptor).st_ino == last:
                    changed.append(True)
                    self.corrupt(stage, "assets/gallery.js")
                return data
            with patch.object(site.os, "read", side_effect=during_read):
                return seal(stage, expected)
        with patch.object(site, "_seal_output", side_effect=during_seal):
            with self.assertRaisesRegex(site.SiteError, "changed after byte verification"): fixture.build()
        self.assertEqual([True], changed)
        self.assertFalse(fixture.output.exists())

    def test_scoped_output_is_checked_after_final_source_files_and_input_snapshots(self):
        write, files, read = site.write_new, site._validate_file_records, site.read_secure_json
        for phase in ("files", "snapshots"):
            fixture = self.fixture(scoped_input=True)
            captured = {}
            def written(stage, relative, data):
                write(stage, relative, data)
                if relative == "gallery-data.json":
                    captured.update(fd=stage, image=json.loads(data)["frames"][0]["image"])
            def checked_files(*args, **kwargs):
                result = files(*args, **kwargs)
                if phase == "files": self.corrupt(captured["fd"], captured["image"])
                return result
            def checked_snapshot(*args, **kwargs):
                result = read(*args, **kwargs)
                if phase == "snapshots" and kwargs.get("label") == "final Pages input" and not captured.get("changed"):
                    self.corrupt(captured["fd"], captured["image"])
                    captured["changed"] = True
                return result
            with patch.object(site, "write_new", side_effect=written), \
                 patch.object(site, "_validate_file_records", side_effect=checked_files), \
                 patch.object(site, "read_secure_json", side_effect=checked_snapshot):
                with self.subTest(phase=phase), self.assertRaises(site.SiteError): fixture.build()
            self.assertFalse(fixture.output.exists())
