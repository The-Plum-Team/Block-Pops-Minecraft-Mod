"""Walking to an output passes through ancestors without reading them, and never follows a link."""

import os
import tempfile
import unittest
from pathlib import Path

from scripts.lib.atomic_directory import _directory_fd, atomic_directory, write_new


class AtomicDirectoryTraversalTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()

    @unittest.skipUnless(hasattr(os, "O_PATH"), "only Linux can hold a directory by path alone")
    @unittest.skipIf(hasattr(os, "geteuid") and os.geteuid() == 0, "root reads a search-only directory anyway")
    def test_search_only_ancestor_is_passed_through(self):
        # The CI sandbox makes its boundary 0711: other users may pass through it
        # but not open it for reading, which is what the walker used to do.
        boundary = self.base / "boundary"
        inner = boundary / "inner"
        inner.mkdir(parents=True)
        boundary.chmod(0o111)
        self.addCleanup(boundary.chmod, 0o755)
        with self.assertRaises(PermissionError):
            os.open(boundary, os.O_RDONLY | os.O_DIRECTORY)

        output = inner / "published"
        def writer(path, stage):
            write_new(stage, "nested/file.txt", b"written")
            return "done"

        self.assertEqual("done", atomic_directory(output, writer))
        self.assertEqual(b"written", (output / "nested/file.txt").read_bytes())

    def test_link_in_the_middle_of_the_path_is_still_refused(self):
        real = self.base / "real"
        (real / "child").mkdir(parents=True)
        (self.base / "link").symlink_to(real, target_is_directory=True)
        with self.assertRaises(OSError):
            descriptor = _directory_fd(self.base / "link" / "child")
            os.close(descriptor)


if __name__ == "__main__":
    unittest.main()
