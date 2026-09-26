"""Mock only HTTP; exercise byte limits and actual extractors for both callers."""

import hashlib
import io
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.release import artifact_transport as transport
from scripts.release import evidence_archive as evidence


class EvidenceDownloadTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve(); self.output = self.root / "output"
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as package: package.writestr("build-matrix-report.json", b"{}")
        self.raw = stream.getvalue(); self.requests = []
        self.arguments = dict(repository="owner/repository", artifact_id=123, kind="report",
            expected_digest="sha256:" + hashlib.sha256(self.raw).hexdigest(), expected_size=len(self.raw),
            output=self.output, token="fixture-token")

    def open(self, request, timeout):
        self.requests.append(request)
        self.assertEqual(60, timeout)
        return io.BytesIO(self.raw)

    def download(self, **changes):
        class Opener:
            pass
        opener = Opener(); opener.open = self.open
        with patch.object(transport.urllib.request, "build_opener", return_value=opener):
            return evidence.download_evidence_archive(**{**self.arguments, **changes})

    def assert_clean(self):
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".pages-artifact-*")))
        self.assertFalse(list(self.root.glob(".output.building-*")))

    def test_exact_id_size_digest_reach_real_extractor_without_authority_claims(self):
        result = self.download()
        self.assertEqual(123, result["artifact_id"])
        self.assertEqual(self.arguments["expected_size"], result["archive_bytes"])
        self.assertEqual(self.arguments["expected_digest"][7:], result["archive_sha256"])
        self.assertEqual(b"{}", (self.output / "build-matrix-report.json").read_bytes())
        self.assertEqual("https://api.github.com/repos/owner/repository/actions/artifacts/123/zip", self.requests[0].full_url)
        self.assertEqual("Bearer fixture-token", self.requests[0].get_header("Authorization"))
        self.assertFalse({"qualified", "fresh", "authorized"} & set(result))
        self.assertFalse(list(self.root.glob(".pages-artifact-*")))

    def test_wrong_digest_short_and_oversized_transports_fail_without_output(self):
        for changes in ({"expected_digest": "sha256:" + "0" * 64},
                        {"expected_size": len(self.raw) - 1}, {"expected_size": len(self.raw) + 1}):
            with self.subTest(changes=changes), self.assertRaises(evidence.EvidenceArchiveError): self.download(**changes)
            self.assert_clean()
        with patch.object(evidence, "extract_evidence_archive") as extract:
            with self.assertRaisesRegex(evidence.EvidenceArchiveError, "download exceeds"):
                self.download(expected_size=len(self.raw) - 1)
            extract.assert_not_called()

    def test_invalid_external_inputs_fail_before_http(self):
        for changes in ({"artifact_id": True}, {"artifact_id": 1.5}, {"artifact_id": 0},
                {"expected_size": True}, {"expected_size": 9 * 1024 * 1024}, {"kind": "unknown"},
                {"expected_digest": "malformed"}, {"api_url": "http://api.github.com"},
                {"api_url": "https://user:secret@api.github.com"}, {"token": ""}):
            with self.subTest(changes=changes), self.assertRaises(evidence.EvidenceArchiveError): self.download(**changes)
        self.assertEqual([], self.requests); self.assert_clean()

    def test_http_failure_and_hostile_zip_cleanup_owned_temporary_files(self):
        def failed(request, timeout):
            raise urllib.error.URLError("fixture unavailable")
        self.open = failed
        with self.assertRaises(evidence.EvidenceArchiveError): self.download()
        self.assert_clean()
        self.open = EvidenceDownloadTests.open.__get__(self)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as package: package.writestr("../escape", b"bad")
        self.raw = stream.getvalue()
        with self.assertRaises(evidence.EvidenceArchiveError):
            self.download(expected_size=len(self.raw), expected_digest="sha256:" + hashlib.sha256(self.raw).hexdigest())
        self.assert_clean(); self.assertFalse((self.root.parent / "escape").exists())

    def test_standalone_transport_download_keeps_its_original_extractor_and_result(self):
        class Opener:
            pass
        opener = Opener(); opener.open = self.open
        with patch.object(transport.urllib.request, "build_opener", return_value=opener), \
             patch.object(transport, "extract_archive", wraps=transport.extract_archive) as extract:
            result = transport.download(repository="owner/repository", artifact_id=123,
                digest=self.arguments["expected_digest"], output=self.output, token="fixture-token", api_url="https://api.github.com")
        self.assertEqual({"files": 1, "bytes": 2, "archive_bytes": len(self.raw)}, result)
        self.assertEqual(1, extract.call_count)

    def test_replaced_download_file_cannot_overwrite_or_remove_foreign_file(self):
        outside = self.root / "outside"; outside.write_bytes(b"preserve")
        def swapped(request, timeout):
            temporary = next(self.root.glob(".pages-artifact-*"))
            temporary.unlink(); temporary.symlink_to(outside)
            return io.BytesIO(self.raw)
        self.open = swapped
        with self.assertRaises(evidence.EvidenceArchiveError): self.download()
        self.assertEqual(b"preserve", outside.read_bytes())
        self.assertTrue(next(self.root.glob(".pages-artifact-*")).is_symlink())
        self.assertFalse(self.output.exists())

    def test_replaced_download_parent_preserves_impostor_and_cleans_owned_file(self):
        parent = self.root / "downloads"; parent.mkdir()
        held = self.root / "held"
        def swapped(request, timeout):
            temporary = next(parent.glob(".pages-artifact-*"))
            parent.rename(held); parent.mkdir()
            (parent / temporary.name).write_bytes(b"preserve")
            return io.BytesIO(self.raw)
        self.open = swapped
        with self.assertRaises(evidence.EvidenceArchiveError): self.download(output=parent / "output")
        self.assertEqual([b"preserve"], [item.read_bytes() for item in parent.iterdir()])
        self.assertEqual([], list(held.iterdir()))
