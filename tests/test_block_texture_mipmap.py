"""Every block texture keeps the game's block atlas at the default mipmap level.

Minecraft stitches every texture under ``textures/block/`` into the shared block atlas and lowers
the mipmap level of that whole atlas, for every block in the game, to the smallest level any one
sprite allows. A sprite allows level n only if both of its sides are multiples of 2**n, and the
default Mipmap Levels setting is 4, so both sides must be multiples of 16 (#62).
"""

from __future__ import annotations

import struct
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
MIP_LEVELS = 4
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SKIPPED_PARTS = frozenset({"build", ".gradle", "run", "out"})


def block_textures():
    for root in sorted(REPO.glob("*/src/*/resources/assets/*/textures/block")):
        for path in sorted(root.rglob("*.png")):
            if not SKIPPED_PARTS.intersection(path.relative_to(REPO).parts):
                yield path


def png_size(path):
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != PNG_SIGNATURE or header[12:16] != b"IHDR":
        raise ValueError(f"{path.relative_to(REPO).as_posix()} is not a PNG")
    return struct.unpack(">II", header[16:24])


class BlockTextureMipmapTests(unittest.TestCase):
    def test_block_textures_exist(self):
        self.assertTrue(any(block_textures()))

    def test_every_block_texture_side_is_a_multiple_of_16(self):
        step = 1 << MIP_LEVELS
        offenders = []
        for path in block_textures():
            if path.is_symlink():
                offenders.append(f"{path.relative_to(REPO).as_posix()} is a symbolic link")
                continue
            width, height = png_size(path)
            if width % step or height % step:
                offenders.append(f"{path.relative_to(REPO).as_posix()} is {width}x{height}")
        self.assertEqual([], offenders, f"block textures need both sides to be multiples of {step}")


if __name__ == "__main__":
    unittest.main()
