from __future__ import annotations

import contextlib
import io
import tempfile
import textwrap
import unittest
import uuid
from pathlib import Path

from scripts.ci import parallel_unittest

PASSING = """
import unittest

class Fixture(unittest.TestCase):
    calls = 0

    @classmethod
    def setUpClass(cls):
        Fixture.calls += 1

    def test_class_fixture_runs_once_per_class(self):
        self.assertEqual(1, Fixture.calls)

    def test_second(self):
        self.assertEqual(1, Fixture.calls)

@unittest.skip("explicitly skipped")
class Skipped(unittest.TestCase):
    def test_skipped(self):
        raise AssertionError("must not run")
"""


class ParallelUnittestTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.package = f"parallel_fixture_{uuid.uuid4().hex}"
        (self.root / self.package).mkdir()
        (self.root / self.package / "__init__.py").write_text("")

    def module(self, name: str, source: str) -> None:
        (self.root / self.package / f"{name}.py").write_text(textwrap.dedent(source))

    def run_main(self) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = parallel_unittest.main(
                ["-t", str(self.root), "-j", "2", "-v", str(self.root / self.package)]
            )
        return code, stdout.getvalue(), stderr.getvalue()

    def serial_count(self) -> int:
        suite = unittest.TestLoader().discover(
            str(self.root / self.package), top_level_dir=str(self.root)
        )
        result = unittest.TestResult()
        suite.run(result)
        return result.testsRun

    def test_passing_classes_and_skips_report_the_serial_count(self) -> None:
        self.module("test_one", PASSING)
        self.module("test_two", "import unittest\nclass B(unittest.TestCase):\n    def test_b(self): pass\n")
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn(f"Ran {self.serial_count()} tests in ", stdout)
        self.assertIn("OK (skipped=1)", stdout)
        self.assertIn("test_class_fixture_runs_once_per_class", stdout)

    def test_a_reexported_class_runs_as_often_as_discovery_finds_it(self) -> None:
        self.module("test_one", PASSING)
        self.module("test_reexport", f"from {self.package}.test_one import Fixture\n")
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertEqual(5, self.serial_count())
        self.assertIn("Ran 5 tests in ", stdout)

    def test_every_kind_of_unsuccessful_run_fails_closed(self) -> None:
        # label -> (module source, the reason the run must report)
        cases = {
            "failure": (
                "import unittest\nclass A(unittest.TestCase):\n    def test_a(self): self.fail('x')\n",
                "test_bad.A.test_a: unsuccessful",
            ),
            "error": (
                "import unittest\nclass A(unittest.TestCase):\n    def test_a(self): raise RuntimeError('x')\n",
                "test_bad.A.test_a: unsuccessful",
            ),
            "class fixture": ((

                "import unittest\nclass A(unittest.TestCase):\n"
                "    @classmethod\n    def setUpClass(cls): raise RuntimeError('x')\n"
                "    def test_a(self): pass\n"
            ), "test_bad.A: ran 0 tests, discovered 1"),
            "unexpected success": ((
                "import unittest\nclass A(unittest.TestCase):\n"
                "    @unittest.expectedFailure\n    def test_a(self): pass\n"
            ), "test_bad.A.test_a: unsuccessful"),
            "import": ("import does_not_exist_anywhere\n", "discovery failed closed"),
            "dead worker": (
                "import os, unittest\nclass A(unittest.TestCase):\n    def test_a(self): os._exit(0)\n",
                "worker failed",
            ),
        }
        for label, (source, reason) in cases.items():
            with self.subTest(label=label):
                self.setUp()
                self.module("test_one", PASSING)
                self.module("test_bad", source)
                code, stdout, stderr = self.run_main()
                self.assertEqual(1, code, stdout + stderr)
                self.assertIn(reason, stderr)
                self.assertNotIn("\nOK", stdout)

    def test_only_classes_without_class_fixtures_are_split_into_single_tests(self) -> None:
        self.module("test_one", PASSING)
        self.module("test_two", (
            "import unittest\nclass Plain(unittest.TestCase):\n"
            "    def test_a(self): pass\n    def test_b(self): pass\n"
            "class Torn(unittest.TestCase):\n"
            "    @classmethod\n    def tearDownClass(cls): pass\n"
            "    def test_c(self): pass\n    def test_d(self): pass\n"
        ))
        units, errors = parallel_unittest.discover(
            [str(self.root / self.package)], pattern="test_*.py", top_level=self.root.resolve()
        )
        self.assertEqual([], errors)
        names = {unit.name.removeprefix(self.package + "."): unit.tests for unit in units}
        self.assertEqual({
            "test_one.Fixture": 2,
            "test_one.Skipped.test_skipped": 1,
            "test_two.Plain.test_a": 1,
            "test_two.Plain.test_b": 1,
            "test_two.Torn": 2,
        }, names)
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn(f"Ran {self.serial_count()} tests in ", stdout)

    def test_each_worker_has_a_private_temporary_directory(self) -> None:
        self.module("test_tmp", (
            "import os, subprocess, sys, tempfile, unittest\n"
            "class Private(unittest.TestCase):\n"
            "    def test_python_and_children_share_the_worker_directory(self):\n"
            "        directory = tempfile.gettempdir()\n"
            "        self.assertTrue(os.path.basename(directory).startswith('worker-'), directory)\n"
            "        self.assertTrue(os.path.basename(os.path.dirname(directory)).startswith('parallel-unittest-'))\n"
            "        child = subprocess.run([sys.executable, '-c', 'import tempfile; print(tempfile.gettempdir())'],\n"
            "                               capture_output=True, text=True, check=True)\n"
            "        self.assertEqual(directory, child.stdout.strip())\n"
        ))
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn("Ran 1 test in ", stdout)

    def test_no_tests_is_a_failure(self) -> None:
        code, _, stderr = self.run_main()
        self.assertEqual(1, code)
        self.assertIn("0 tests", stderr)


if __name__ == "__main__":
    unittest.main()
