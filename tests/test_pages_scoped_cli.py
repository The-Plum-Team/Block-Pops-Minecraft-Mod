"""Real CLI pixel round trips; synthetic captures are not game qualification."""

import contextlib
import hashlib
import io
import json
import unittest

from scripts.ci.tests.matrix_fixtures import SCHEMA1_MATRIX_PATH
from scripts.pages import evidence
from tests import test_pages_raw_scope as reader


COMMANDS = ("curate", "validate-raw", "compact", "validate-compact", "copy-compact")
PRODUCERS = {"curate", "compact", "copy-compact"}


class ScopedPagesCliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        reader.ScopedRawPagesTests.setUpClass()
        cls.addClassCleanup(reader.ScopedRawPagesTests.doClassCleanups)

    def setUp(self):
        self.fixture = reader.ScopedRawPagesTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def prepare(self, scope="legacy", node=None, *, shared=False,
                projection="pr-anchors", schema1=False):
        f = self.fixture
        f.prepare(scope, node, shared=shared, projection=projection)
        self.selection = (["--artifact-node", node] if node else ["--scope", scope])
        self.selection += ["--projection", projection]
        if schema1:
            f.matrix_path.write_bytes(SCHEMA1_MATRIX_PATH.read_bytes())
            f.expected["matrix_sha256"] = hashlib.sha256(f.matrix_path.read_bytes()).hexdigest()
            self.selection = []

    def arguments(self, command, *, source=None, output=None, selection=None):
        f = self.fixture
        if source is None:
            source = f.root / "compact" if command in {"validate-compact", "copy-compact"} else f.raw
        args = [command, "--input", str(source), "--matrix", str(f.matrix_path)]
        for key in ("repository", "branch", "commit", "tree"):
            args += ["--" + key, f.expected[key]]
        if command != "curate":
            args += ["--matrix_sha256", f.expected["matrix_sha256"]]
        if command in {"curate", "validate-raw", "compact"}:
            for key in ("run_id", "run_attempt", "controller_branch", "controller_sha"):
                args += ["--" + key.replace("_", "-"), str(f.expected["handoff"][key])]
        if command in PRODUCERS:
            args += ["--output", str(output or f.root / (command + "-output"))]
        if command == "compact":
            args += ["--source-artifact-id", "123", "--source-artifact-name",
                     evidence.raw_artifact_name(f.expected["branch"], 2),
                     "--source-artifact-digest", "sha256:" + "a" * 64]
        return args + (self.selection if selection is None else selection)

    def invoke(self, command, **kwargs):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = evidence.main(self.arguments(command, **kwargs))
        return status, stdout.getvalue(), stderr.getvalue()

    def round_trip(self, *, schema1=False):
        f = self.fixture
        curated, compact, copied = (f.root / name for name in ("curated", "compact", "copied"))
        calls = (("curate", f.raw, curated), ("validate-raw", curated, None),
                 ("compact", curated, compact), ("validate-compact", compact, None),
                 ("copy-compact", compact, copied))
        for command, source, output in calls:
            with self.subTest(command=command):
                status, stdout, stderr = self.invoke(command, source=source, output=output)
                self.assertEqual((0, ""), (status, stderr))
                manifest_root = output or source
                name = "pages-evidence.json" if command in {"curate", "validate-raw"} else "manifest.json"
                manifest = json.loads((manifest_root / name).read_bytes())
                expected = {"kind": manifest["kind"], "branch": f.expected["branch"],
                            "commit": f.expected["commit"], "lanes": len(manifest["lanes"]),
                            "frames": len(manifest["frames"])}
                if not schema1:
                    expected["aggregate_scope"] = f.manifest["aggregate_scope"]
                    self.assertEqual(expected["aggregate_scope"], manifest["aggregate_scope"])
                    self.assertEqual(12, len(expected["aggregate_scope"]["target_nodes"]))
                self.assertEqual(json.dumps(expected, sort_keys=True) + "\n", stdout)
        self.assertEqual((compact / "manifest.json").read_bytes(), (copied / "manifest.json").read_bytes())

    def test_five_commands_preserve_legacy_lane_and_full_scoped_coverage(self):
        root = self.fixture.root
        for index, (scope, node, shared, projection) in enumerate((
            ("legacy", None, False, "pr-anchors"),
            ("lane", "neoforge-1.21.1", False, "scheduled-anchors"),
            ("full", None, True, "pr-anchors"),
        )):
            with self.subTest(scope=scope):
                self.fixture.root = root / str(index)
                self.fixture.root.mkdir()
                self.prepare(scope, node, shared=shared, projection=projection)
                self.round_trip()

    def test_schema1_arguments_and_summary_bytes_remain_unchanged(self):
        self.prepare(schema1=True)
        self.round_trip(schema1=True)
        for command in COMMANDS:
            output = self.fixture.root / ("schema1-scoped-" + command)
            with self.subTest(command=command):
                status, stdout, stderr = self.invoke(command, output=output,
                    selection=["--scope", "legacy", "--projection", "pr-anchors"])
                self.assertEqual((2, ""), (status, stdout))
                self.assertIn("requires a schema2 matrix", stderr)
                self.assertFalse(output.exists())

    def test_missing_crossed_or_unresolved_selection_never_publishes(self):
        self.prepare()
        self.assertEqual(0, self.invoke("compact", output=self.fixture.root / "compact")[0])
        selections = ([], ["--projection", "pr-anchors"], ["--scope", "legacy"],
                      ["--scope", "full", "--projection", "pr-anchors"],
                      ["--artifact-node", "fabric-1.20.1", "--projection", "pr-anchors"],
                      ["--artifact-node", "fabric-1.21.7", "--projection", "pr-anchors"])
        for command in COMMANDS:
            for index, selection in enumerate(selections):
                output = self.fixture.root / f"rejected-{command}-{index}"
                with self.subTest(command=command, selection=selection):
                    status, stdout, stderr = self.invoke(command, output=output, selection=selection)
                    self.assertEqual(2, status)
                    self.assertEqual("", stdout)
                    self.assertIn("Pages evidence error:", stderr)
                    self.assertFalse(output.exists())

    def test_consumers_reject_a_different_valid_projection(self):
        self.prepare()
        self.assertEqual(0, self.invoke("compact", output=self.fixture.root / "compact")[0])
        for command in COMMANDS[1:]:
            output = self.fixture.root / ("crossed-" + command)
            with self.subTest(command=command):
                status, stdout, stderr = self.invoke(command, output=output,
                    selection=["--scope", "legacy", "--projection", "scheduled-anchors"])
                self.assertEqual((2, ""), (status, stdout))
                self.assertIn("scope/projection", stderr)
                self.assertFalse(output.exists())

    def test_parser_rejects_conflicting_selectors_and_unknown_projection(self):
        self.prepare()
        for command in COMMANDS:
            for selection in (["--scope", "legacy", "--artifact-node", "fabric-1.20.1"],
                              ["--scope", "legacy", "--projection", "release"]):
                output = self.fixture.root / ("invalid-" + command)
                with self.subTest(command=command, selection=selection):
                    with self.assertRaises(SystemExit) as raised:
                        self.invoke(command, output=output, selection=selection)
                    self.assertEqual(2, raised.exception.code)
                    self.assertFalse(output.exists())

    def test_curate_rejects_extra_and_missing_profiles_before_publication(self):
        self.prepare()
        profiles = self.fixture.raw / "profiles"
        extra = profiles / "unknown"
        extra.mkdir()
        output = self.fixture.root / "invalid-profiles"
        status, _, stderr = self.invoke("curate", output=output)
        self.assertEqual(2, status)
        self.assertIn("profile inventory mismatch", stderr)
        extra.rmdir()
        next(profiles.iterdir()).rename(self.fixture.root / "removed")
        status, _, stderr = self.invoke("curate", output=output)
        self.assertEqual(2, status)
        self.assertIn("profile inventory mismatch", stderr)
        self.assertFalse(output.exists())
