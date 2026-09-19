"""Production boundaries include harness resources and bounded nested archives."""

import io
import json
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.release.artifact_manifest import ArtifactError, verify_harness_jar, verify_production_jar
from tests.test_artifact_and_report_validation import (
    FABRIC_ARTIFACT, _fabric_harness_entries, _fabric_production_entries, _write_zip,
)


def nested(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)
    return output.getvalue()


class NestedArtifactBoundaryTests(unittest.TestCase):
    def verify(self, payloads):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "production.jar"
            _write_zip(path, {**_fabric_production_entries(), **payloads})
            verify_production_jar(path, FABRIC_ARTIFACT)

    def test_ordinary_nested_library_and_non_archive_resources_are_allowed(self):
        self.verify({"assets/example.txt": b"plain resource", "META-INF/jars/library.jar":
                     nested({"library/Main.class": b"class", "fabric.mod.json": b'{"id":"library"}'})})

    def test_nested_harness_classes_resources_and_both_metadata_forms_fail(self):
        contents = (
            {"com/theplumteam/e2e/E2EHarness.class": b"class"},
            {"blockpops-e2e.properties": b"enabled=true"},
            {"fabric.mod.json": json.dumps({"id": "blockpops-e2e"}).encode()},
            {"META-INF/mods.toml": b'[[mods]]\nmodId="blockpops_e2e"'},
            {"META-INF/neoforge.mods.toml": b'[[mods]]\nmodId = "blockpops_e2e"'},
        )
        for content in contents:
            for name in ("META-INF/jars/nested.jar", "assets/renamed.bin"):
                with self.subTest(content=list(content), name=name), self.assertRaisesRegex(ArtifactError, "E2E"):
                    self.verify({name: nested({"deeper.jar": nested(content)})})
        with self.assertRaisesRegex(ArtifactError, "E2E"):
            self.verify({"blockpops-e2e.properties": b"resource without classes"})

    def test_prefixed_renamed_archives_cannot_hide_opposite_artifact_bytes(self):
        for prefix in (b"prefix", b"#!/bin/sh\nexit 0\n"):
            payload = prefix + nested({"com/theplumteam/e2e/E2EHarness.class": b"class"})
            self.assertTrue(zipfile.is_zipfile(io.BytesIO(payload)))
            with self.subTest(prefix=prefix), self.assertRaisesRegex(ArtifactError, "E2E"):
                self.verify({"assets/renamed.bin": payload})
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "harness.jar"
            payload = b"prefix" + nested({"com/theplumteam/BlockPopsMod.class": b"class"})
            _write_zip(path, {**_fabric_harness_entries(), "payload.bin": payload})
            with self.assertRaisesRegex(ArtifactError, "non-harness"):
                verify_harness_jar(path, FABRIC_ARTIFACT)
        self.verify({"assets/library.bin": b"prefix" + nested({"library/Main.class": b"class"})})

    def test_multi_release_effective_paths_enforce_boundary_without_manifest_claims(self):
        for enabled in ("true", "false"):
            manifest = f"Manifest-Version: 1.0\nMulti-Release: {enabled}\n\n".encode()
            for resource in ("com/theplumteam/e2e/E2EHarness.class", "fabric.mod.json"):
                payload = b'{"id":"blockpops-e2e"}' if resource.endswith(".json") else b"class"
                content = {"META-INF/MANIFEST.MF": manifest, f"META-INF/versions/21/{resource}": payload}
                for entries in (content, {"nested.jar": nested(content)}):
                    with self.subTest(enabled=enabled, resource=resource), self.assertRaisesRegex(ArtifactError, "E2E"):
                        self.verify(entries)

    def test_nested_unsafe_paths_special_files_and_broken_archives_fail(self):
        linked = zipfile.ZipInfo("link.class")
        linked.create_system = 3
        linked.external_attr = (stat.S_IFLNK | 0o777) << 16
        bad = (nested({"../outside": b"data"}), nested({linked: b"target"}), b"broken zip")
        for payload in bad:
            with self.subTest(payload=payload[:8]), self.assertRaises(ArtifactError):
                self.verify({"nested.jar": payload})

    def test_harness_cannot_hide_production_or_third_party_code_in_nested_content(self):
        contents = (
            {"com/theplumteam/BlockPopsMod.class": b"class"},
            {"library/Main.class": b"class"},
            {"blockpops.mixins.json": b"{}"},
            {"fabric.mod.json": b'{"id":"blockpops"}'},
            {"META-INF/mods.toml": b'[[mods]]\nmodId="blockpops"'},
        )
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "harness.jar"
            for content in contents:
                entries = _fabric_harness_entries()
                entries["payload.bin"] = nested(content)
                _write_zip(path, entries)
                with self.subTest(content=list(content)), self.assertRaises(ArtifactError):
                    verify_harness_jar(path, FABRIC_ARTIFACT)

    def test_total_size_count_and_depth_limits_apply_across_archive_boundaries(self):
        archive = nested({"library/Main.class": b"class"})
        for limit, value, files in (
            ("MAX_NESTED_ARCHIVES", 1, {"nested.jar": archive}),
            ("MAX_ARCHIVE_DEPTH", 0, {"nested.jar": archive}),
            ("MAX_ZIP_ENTRIES", 6, {"nested.jar": nested({"one": b"1", "two": b"2"})}),
            ("MAX_UNCOMPRESSED_BYTES", 1000, {"nested.jar": nested({"compressed": b"x" * 500})}),
        ):
            with self.subTest(limit=limit), patch(f"scripts.release.artifact_manifest.{limit}", value), self.assertRaises(ArtifactError):
                self.verify(files)

    def test_opaque_entries_are_bounded_before_inspecting_a_possible_archive(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "production.jar"
            _write_zip(path, {**_fabric_production_entries(), "assets/opaque.bin": b"x" * 4000})
            with patch("scripts.release.artifact_manifest.MAX_JAR_BYTES", 2000), self.assertRaisesRegex(ArtifactError, "inspection size"):
                verify_production_jar(path, FABRIC_ARTIFACT)


if __name__ == "__main__":
    unittest.main()
