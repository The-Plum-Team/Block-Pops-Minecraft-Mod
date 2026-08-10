from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from scripts.lib.secure_json import (
    SecureJsonError,
    canonical_json,
    loads,
    read,
    require_object,
)


class SecureJsonLoadsTests(unittest.TestCase):
    def test_accepts_utf8_and_produces_deterministic_canonical_bytes(self) -> None:
        value = loads(
            '{"z":1,"label":"café","items":[true,null]}'.encode(),
            label="fixture",
            max_bytes=1024,
        )

        self.assertEqual(
            canonical_json(value),
            '{"items":[true,null],"label":"café","z":1}'.encode(),
        )

    def test_rejects_duplicate_keys_at_any_depth(self) -> None:
        for raw in (b'{"lane":1,"lane":2}', b'{"outer":{"id":1,"id":2}}'):
            with self.subTest(raw=raw), self.assertRaisesRegex(
                SecureJsonError, "duplicate JSON object key"
            ):
                loads(raw, label="candidate", max_bytes=1024)

    def test_rejects_non_finite_numbers_invalid_utf8_and_non_bytes(self) -> None:
        for raw in (b'{"number":NaN}', b'{"number":Infinity}', b'{"number":-Infinity}'):
            with self.subTest(raw=raw), self.assertRaisesRegex(
                SecureJsonError, "non-finite JSON number"
            ):
                loads(raw, label="candidate", max_bytes=1024)
        with self.assertRaisesRegex(SecureJsonError, "not valid UTF-8"):
            loads(b'\xff', label="candidate", max_bytes=1024)
        with self.assertRaisesRegex(SecureJsonError, "must be bytes"):
            loads("{}", label="candidate", max_bytes=1024)  # type: ignore[arg-type]

    def test_rejects_empty_and_oversized_inputs(self) -> None:
        with self.assertRaisesRegex(SecureJsonError, "is empty"):
            loads(b"", label="candidate", max_bytes=4)
        with self.assertRaisesRegex(SecureJsonError, "exceeds the 2-byte input limit"):
            loads(b"{}\n", label="candidate", max_bytes=2)

    def test_require_object_is_exact_key_fail_closed(self) -> None:
        self.assertEqual(
            require_object(
                {"required": 1, "optional": 2},
                label="record",
                required={"required"},
                optional={"optional"},
            ),
            {"required": 1, "optional": 2},
        )
        with self.assertRaisesRegex(SecureJsonError, "missing.*unknown"):
            require_object(
                {"surprise": True}, label="record", required={"required"}
            )


class SecureJsonFileTests(unittest.TestCase):
    def test_reads_one_bounded_regular_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "input.json"
            path.write_bytes(b'{"ok":true}')

            value, raw = read(path, label="input", max_bytes=64)

            self.assertEqual(value, {"ok": True})
            self.assertEqual(raw, b'{"ok":true}')

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_rejects_final_component_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = root / "input.json"
            link.symlink_to(target)

            with self.assertRaisesRegex(SecureJsonError, "must not be a symlink"):
                read(link, label="input", max_bytes=64)

    def test_rejects_directories_empty_files_and_oversized_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            empty = root / "empty.json"
            empty.touch()
            large = root / "large.json"
            large.write_bytes(b"{} ")
            with self.assertRaisesRegex(SecureJsonError, "regular file"):
                read(root, label="input", max_bytes=64)
            with self.assertRaisesRegex(SecureJsonError, "size must be between"):
                read(empty, label="input", max_bytes=64)
            with self.assertRaisesRegex(SecureJsonError, "size must be between"):
                read(large, label="input", max_bytes=2)


if __name__ == "__main__":
    unittest.main()
