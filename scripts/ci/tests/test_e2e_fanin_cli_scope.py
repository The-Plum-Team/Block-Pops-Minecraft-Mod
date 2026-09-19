"""CLI scope forwarding with real evidence payloads and an isolated bundle verifier."""

import contextlib
import copy
import io
import json
import unittest
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests import test_e2e_fanin as legacy_fixture
from scripts.ci.tests import test_e2e_fanin_scoped_producer as producer_fixture
from scripts.ci.tests.test_e2e_fanin import _json


class ScopedFanInCLITests(unittest.TestCase):
    def setUp(self):
        self.fixture = producer_fixture.ScopedAggregateProducerTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.inputs = self.fixture.inputs
        self.stage = self.inputs.root / "build/release"
        self.manifest_path = self.stage / "artifacts.json"

    def prepare(self, scope="legacy", node=None, projection="pr-anchors"):
        self.fixture.prepare(scope, node, projection=projection)
        _json(self.manifest_path, self.inputs.manifest)
        self.selection = ["--artifact-node", node] if node else ["--scope", scope]

    def invoke(self, command, *, extra=(), select=True):
        args = [command, "--matrix", str(self.inputs.matrix_path),
                "--contract", str(self.fixture.contract_path), "--projection", self.fixture.identity.projection]
        if select: args.extend(self.selection)
        if command in {"create", "validate"}:
            args.extend(["--input", str(self.inputs.fixture.root if command == "create" else self.fixture.output)])
            for key in ("repository", "source_branch", "commit", "tree", "run_id", "run_attempt"):
                args.extend(["--" + key.replace("_", "-"), str(getattr(self.fixture.identity, key))])
            if command == "create": args.extend(["--output", str(self.fixture.output)])
            args.extend(["--artifact-repository", str(self.inputs.root)])
        else:
            lane = self.inputs.fixture.lanes[0]
            row = self.inputs.document.projection(self.fixture.identity.projection,
                scope=self.inputs.scope, artifact_node=self.inputs.node)["include"][0]
            args.extend(["--input", str(self.inputs.fixture.root / lane.artifact_name),
                "--row-json", json.dumps(row), "--repository", str(self.inputs.root)])
        args.extend(["--stage", str(self.stage), "--artifact-manifest", str(self.manifest_path), *extra])
        output, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            status = fanin.main(args)
        return status, output.getvalue(), error.getvalue()

    def test_create_and_validate_forward_external_legacy_scope_and_local_bundle_paths(self):
        self.prepare()
        for command in ("create", "validate"):
            with self.subTest(command=command), patch.object(fanin, "verify_staged", return_value=self.inputs.manifest) as verify:
                status, output, error = self.invoke(command)
                self.assertEqual((0, ""), (status, error))
                self.assertEqual("legacy", json.loads(output)["aggregate_scope"]["kind"])
                verify.assert_called_once_with(repository=self.inputs.root.resolve(), matrix_path=self.inputs.matrix_path,
                    stage=self.stage, manifest_path=self.manifest_path, scope="legacy", artifact_node=None)

    def test_modern_scheduled_lane_passes_exact_node_to_bundle_and_evidence_validators(self):
        self.prepare("lane", "neoforge-1.21.1", "scheduled-anchors")
        for command in ("validate-lane", "create", "validate"):
            with self.subTest(command=command), patch.object(fanin, "verify_staged", return_value=self.inputs.manifest) as verify:
                status, output, error = self.invoke(command)
                self.assertEqual((0, ""), (status, error))
                coverage = json.loads(output)["execution_scope" if command == "validate-lane" else "aggregate_scope"]
                self.assertEqual(["neoforge-1.21.1"], coverage["selected_nodes"])
                self.assertEqual("lane", verify.call_args.kwargs["scope"])
                self.assertEqual("neoforge-1.21.1", verify.call_args.kwargs["artifact_node"])

    def test_fresh_validator_resolves_relative_bundle_inputs_against_explicit_checkout(self):
        self.prepare()
        self.fixture.create()
        output = io.StringIO()
        with patch.object(fanin, "verify_staged", return_value=self.inputs.manifest) as verify, contextlib.redirect_stdout(output):
            status = fanin.main(["validate", "--input", str(self.fixture.output), "--scope", "legacy",
                "--projection", "pr-anchors", "--matrix", "matrix.json", "--contract", "contract.json",
                "--artifact-repository", str(self.inputs.root)])
        self.assertEqual(0, status)
        root = self.inputs.root.resolve()
        verify.assert_called_once_with(repository=root, matrix_path=root / "matrix.json",
            stage=root / "build/release", manifest_path=root / "build/release/artifacts.json",
            scope="legacy", artifact_node=None)
        self.assertEqual("legacy", json.loads(output.getvalue())["aggregate_scope"]["kind"])

    def test_absent_scope_wrong_scope_or_failed_bundle_never_emit_evidence(self):
        self.prepare()
        for command in ("create", "validate-lane"):
            for options in ({"select": False}, {"extra": ["--scope", "full"]}):
                with self.subTest(command=command, options=options), patch.object(fanin, "verify_staged", return_value=self.inputs.manifest):
                    status, output, _ = self.invoke(command, **options)
                    self.assertEqual((2, ""), (status, output))
                    self.fixture.assert_unpublished()
        with patch.object(fanin, "verify_staged", side_effect=fanin.ArtifactError("invalid sealed JAR")):
            status, output, error = self.invoke("create")
        self.assertEqual((2, ""), (status, output))
        self.assertIn("invalid sealed JAR", error)
        self.fixture.assert_unpublished()

    def test_manifest_reread_rejects_equal_numeric_alias_after_verification(self):
        self.prepare()
        def changed(**kwargs):
            stale = copy.deepcopy(self.inputs.manifest)
            stale["schema_version"] = 3.0
            _json(self.manifest_path, stale)
            return self.inputs.manifest
        with patch.object(fanin, "verify_staged", side_effect=changed):
            status, output, error = self.invoke("create")
        self.assertEqual((2, ""), (status, output))
        self.assertIn("changed after verification", error)
        self.fixture.assert_unpublished()

    def test_selection_options_are_mutually_exclusive(self):
        self.prepare()
        with self.assertRaises(SystemExit) as exit:
            self.invoke("create", extra=["--artifact-node", "fabric-1.20.1"])
        self.assertEqual(2, exit.exception.code)

    def test_legacy_validate_preserves_structural_cli_and_rejects_ignored_bundle_options(self):
        fixture = legacy_fixture.Fixture(self.inputs.root / "legacy-lanes")
        fixture.populate()
        expected = fixture.create(self.fixture.output)
        args = ["validate", "--input", str(self.fixture.output), "--matrix", str(legacy_fixture.MATRIX_PATH),
                "--contract", str(legacy_fixture.CONTRACT_PATH), "--projection", "pr-anchors"]
        output = io.StringIO()
        with patch.object(fanin, "verify_staged") as verify, contextlib.redirect_stdout(output):
            self.assertEqual(0, fanin.main(args))
        verify.assert_not_called()
        self.assertEqual(json.dumps(expected, sort_keys=True, separators=(",", ":")) + "\n", output.getvalue())
        for option in ("--stage", "--artifact-manifest", "--artifact-repository"):
            with self.subTest(option=option), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, fanin.main([*args, option, str(self.inputs.root)]))


if __name__ == "__main__":
    unittest.main()
