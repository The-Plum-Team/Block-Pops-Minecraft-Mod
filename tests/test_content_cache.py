"""Content-hash memoization never replaces a file check or a changed parameter."""

from __future__ import annotations

import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image, ImageDraw

from e2e import packaged_runtime, visual_evidence
from scripts.lib.content_cache import ContentCache
from scripts.pages import evidence


def _frame(size: tuple[int, int] = (1600, 900), shift: int = 0) -> bytes:
    image = Image.new("RGB", size, (8, 12, 20))
    draw = ImageDraw.Draw(image)
    colors = ((16, 32, 64), (80, 32, 96), (32, 112, 80), (180, 140, 40 + shift))
    width, height = size
    for index, color in enumerate(colors):
        draw.rectangle((index * width // 4, 0, (index + 1) * width // 4, height), fill=color)
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


class ContentCacheTests(unittest.TestCase):
    def test_failures_are_recomputed_and_parameters_are_part_of_the_key(self) -> None:
        cache = ContentCache(entries=8)
        calls = []

        def failing():
            calls.append("fail")
            raise ValueError("rejected")

        for _ in range(2):
            with self.assertRaises(ValueError):
                cache.get_or_compute(b"bytes", ("a",), failing)
        self.assertEqual(["fail", "fail"], calls)
        self.assertEqual(0, len(cache))

        self.assertEqual(1, cache.get_or_compute(b"bytes", ("a",), lambda: 1))
        self.assertEqual(1, cache.get_or_compute(b"bytes", ("a",), lambda: 2))
        self.assertEqual(3, cache.get_or_compute(b"bytes", ("b",), lambda: 3))
        self.assertEqual(4, cache.get_or_compute(b"other", ("a",), lambda: 4))

    def test_callers_cannot_alter_a_stored_result(self) -> None:
        cache = ContentCache(entries=8)
        first = cache.get_or_compute(b"bytes", (), lambda: {"width": 1600, "nested": [1]})
        first["width"] = 1
        first["nested"].append(2)
        second = cache.get_or_compute(b"bytes", (), lambda: {"width": 0})
        self.assertEqual({"width": 1600, "nested": [1]}, second)

    def test_entries_and_bytes_stay_bounded_least_recent_first(self) -> None:
        cache = ContentCache(entries=2, max_bytes=10)
        cache.get_or_compute(b"a", (), lambda: b"1234")
        cache.get_or_compute(b"b", (), lambda: b"1234")
        cache.get_or_compute(b"a", (), lambda: b"miss")  # refresh a
        cache.get_or_compute(b"c", (), lambda: b"1234")  # evicts b
        self.assertEqual(2, len(cache))
        self.assertEqual(b"1234", cache.get_or_compute(b"a", (), lambda: b"miss"))
        self.assertEqual(b"miss", cache.get_or_compute(b"b", (), lambda: b"miss"))

        cache.get_or_compute(b"big", (), lambda: b"x" * 9, size=len)
        self.assertLessEqual(cache.stored_bytes, 10)
        self.assertEqual(b"y" * 11, cache.get_or_compute(b"huge", (), lambda: b"y" * 11, size=len))
        self.assertLessEqual(cache.stored_bytes, 10)
        with self.assertRaises(ValueError):
            ContentCache(entries=0)


class ScreenshotCacheBoundaryTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / "frame.png"
        self.path.write_bytes(_frame())

    def test_a_cached_frame_still_passes_every_file_check(self) -> None:
        metrics = packaged_runtime.inspect_screenshot(self.path)
        self.assertEqual(metrics, packaged_runtime.inspect_screenshot(self.path))
        link = self.root / "link.png"
        os.symlink(self.path, link)
        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "bounded regular file"):
            packaged_runtime.inspect_screenshot(link)
        with self.assertRaisesRegex(visual_evidence.VisualEvidenceError, "."):
            visual_evidence.canonicalize_png(link, expected_size=(1600, 900))
        empty = self.root / "empty.png"
        empty.write_bytes(b"")
        with self.assertRaises(packaged_runtime.RuntimeFailure):
            packaged_runtime.inspect_screenshot(empty)

    def test_changed_bytes_are_measured_again(self) -> None:
        before = packaged_runtime.inspect_screenshot(self.path)
        self.path.write_bytes(_frame(shift=80))
        after = packaged_runtime.inspect_screenshot(self.path)
        self.assertNotEqual(before["file_sha256"], after["file_sha256"])
        self.assertNotEqual(before["pixel_sha256"], after["pixel_sha256"])
        self.path.write_bytes(_blank())
        for _ in range(2):
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "effectively blank"):
                packaged_runtime.inspect_screenshot(self.path)

    def test_a_tightened_probe_is_never_answered_from_an_earlier_verdict(self) -> None:
        key = ("ui-regression", "client_a", "favorite_color_prompt")
        with mock.patch.dict(packaged_runtime.OPAQUE_STARS_PROBES, clear=True), mock.patch.dict(
            packaged_runtime.REQUIRED_GUI_TEXT_PROBES, clear=True
        ):
            packaged_runtime.inspect_screenshot_for_step(self.path, *key)
            packaged_runtime.REQUIRED_GUI_TEXT_PROBES[key] = (
                ("impossible text", (0, 0, 100, 100), 250, 1),
            )
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "impossible text"):
                packaged_runtime.inspect_screenshot_for_step(self.path, *key)

    def test_pages_decode_verdict_is_bound_to_the_expected_format(self) -> None:
        data = self.path.read_bytes()
        self.assertEqual(1600, evidence._image(data, expected_format="PNG", label="frame")["width"])
        with self.assertRaisesRegex(evidence.EvidenceError, "must decode as WEBP"):
            evidence._image(data, expected_format="WEBP", label="frame")
        with self.assertRaisesRegex(evidence.EvidenceError, "cannot decode"):
            evidence._image(data[:100], expected_format="PNG", label="frame")

    def test_canonical_png_is_bound_to_the_expected_size(self) -> None:
        first = visual_evidence.canonicalize_png(self.path, expected_size=(1600, 900))
        self.assertEqual(first, visual_evidence.canonicalize_png(self.path, expected_size=(1600, 900)))
        with self.assertRaisesRegex(visual_evidence.VisualEvidenceError, "dimensions must be"):
            visual_evidence.canonicalize_png(self.path, expected_size=(800, 450))


def _blank() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (1600, 900), (0, 0, 0)).save(output, format="PNG")
    return output.getvalue()


if __name__ == "__main__":
    unittest.main()
