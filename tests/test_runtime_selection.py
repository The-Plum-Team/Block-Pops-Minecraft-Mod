"""Packaged runtime selection is an exact normalized matrix projection."""

from __future__ import annotations

import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from zipfile import ZipFile

from e2e.orchestrator import CONTRACT, main, parse_args, select_rows
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.matrix import MatrixDocument, MatrixError, gha_matrix, normalize_matrix_inventory
from tests.test_release_matrix_portability import arbitrary_named_1211_release_matrix
from tests import test_scoped_artifact_manifest as scoped_fixture


def document(matrix: dict) -> MatrixDocument:
    return MatrixDocument(normalize_matrix_inventory(matrix), json.dumps(matrix))


class RuntimeSelectionTests(unittest.TestCase):
    def test_schema_one_preserves_runtime_and_exact_pr_row_selection(self):
        matrix = arbitrary_named_1211_release_matrix()
        source = document(matrix)
        self.assertEqual(matrix["runtimes"], select_rows(source, parse_args([])))
        for expected in gha_matrix(matrix, "pr-anchors", contract=CONTRACT)["include"]:
            rows = select_rows(source, parse_args(["--row-json", json.dumps(expected)]))
            self.assertEqual([expected["artifact_node"]], [row["artifact_node"] for row in rows])

    def test_preparation_defaults_to_legacy_and_modern_selection_is_explicit(self):
        source = document(schema2_configuration())
        self.assertEqual(["fabric-1.20.1", "forge-1.20.1"],
                         [row["artifact_node"] for row in select_rows(source, parse_args([]))])
        args = parse_args(["--artifact-node", "neoforge-1.21.1"])
        self.assertEqual([source.inventory.lane("neoforge-1.21.1").runtime], select_rows(source, args))
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--scope", "full"]))
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--artifact-node", "fabric-1.21.7"]))
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--artifact-node", "fabric-1.21.1", "--loader", "neoforge"]))

    def test_shared_default_covers_every_target(self):
        source = document(schema2_configuration(shared=True))
        self.assertEqual(set(source.inventory.target_nodes),
                         {row["artifact_node"] for row in select_rows(source, parse_args([]))})
        with self.assertRaises(MatrixError):
            select_rows(source, parse_args(["--scope", "legacy"]))
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--loader", "fabric"]))

    def test_caller_runtime_overrides_never_replace_the_authoritative_row(self):
        source = document(schema2_configuration())
        expected = source.projection("pr-anchors", contract=CONTRACT)["include"][0]
        for key, value in (("java", 21), ("artifact_node", "fabric-1.21.1"),
                           ("scenarios", "skip"), ("id", "wrong"), ("loader_version", "latest")):
            changed = copy.deepcopy(expected)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                select_rows(source, parse_args(["--row-json", json.dumps(changed)]))
        modern = source.projection("pr-anchors", scope="lane", artifact_node="neoforge-1.21.1",
                                   contract=CONTRACT)["include"][0]
        with self.assertRaises(ValueError):
            select_rows(source, parse_args(["--row-json", json.dumps(modern)]))
        args = parse_args(["--row-json", json.dumps(modern), "--artifact-node", "neoforge-1.21.1"])
        self.assertEqual("neoforge-1.21.1", select_rows(source, args)[0]["artifact_node"])

    def test_schema_two_listing_exposes_partial_scope_and_packaged_requires_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix = schema2_configuration()
            for route in matrix["source_routing"].values():
                for key in ("canonical", "e2e"):
                    (root / route[key]).mkdir(parents=True)
            path = root / "release/matrix.json"
            path.parent.mkdir()
            path.write_text(json.dumps(matrix))
            arguments = ["--matrix", str(path), "--artifacts-manifest", str(root / "absent.json")]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, main(arguments + ["--list"]))
            report = json.loads(output.getvalue())
            self.assertEqual(("legacy", "preparing", 12, 4),
                             tuple(report[key] for key in ("scope", "migration_mode", "target_count", "configured_lane_count")))
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(2, main(arguments + ["--packaged"]))
            self.assertIn("requires", error.getvalue())


class ScopedRuntimeExecutionTests(unittest.TestCase):
    """Real staged verification with a simulated game process; no gameplay qualification."""

    def setUp(self):
        self.fixture = scoped_fixture.ScopedManifestTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        # Bind the game process's actual imported contract, then regenerate fixture identities.
        self.fixture.contract.write_bytes((Path(__file__).resolve().parents[1] / "e2e/scenario-contract.json").read_bytes())
        self.fixture.git("add", "e2e/scenario-contract.json")
        self.fixture.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                         "commit", "-qm", "runtime contract fixture")
        _, header, expected = self.fixture.context()
        row = expected[0]
        for kind in ("production", "harness"):
            record = self.fixture.manifest["artifacts"][0][kind]
            path = self.fixture.stage / record["path"]
            with ZipFile(path) as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            entries[scoped_fixture.BUILD_IDENTITY_PATH] = json.dumps(row["build_identity"]).encode()
            scoped_fixture._write_zip(path, entries)
            row[kind] = scoped_fixture._file_record(path, relative=record["path"])
        self.fixture.manifest = dict(header, artifacts=[row])
        self.fixture.write(self.fixture.manifest)
        self.output = self.fixture.repo / "build/e2e-out"
        self.scenario = CONTRACT.scenario_ids[0]
        self.arguments = ["--matrix", str(self.fixture.matrix_path),
                          "--artifacts-manifest", str(self.fixture.manifest_path),
                          "--output-root", str(self.output), "--scenarios", self.scenario]
        self.repo_patch = patch("e2e.orchestrator.REPO", self.fixture.repo)
        self.repo_patch.start()
        self.addCleanup(self.repo_patch.stop)

    def run_main(self, *args):
        output, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            code = main(self.arguments + list(args))
        return code, output.getvalue(), error.getvalue()

    def game_result(self, _repo, _data, row, scenario, manifest, *_args):
        return {"artifact_node": row["artifact_node"], "minecraft": row["minecraft"],
                "loader": row["loader"], "scenario": scenario, "status": "pass", "elapsed_s": 0,
                "production_jar_sha256": manifest["artifacts"][0]["production"]["sha256"]}

    def test_materialized_manifest_requires_external_scope_and_rejects_wrong_lane_or_full(self):
        with patch("e2e.orchestrator.run_packaged_row") as run:
            for arguments in (("--packaged",), ("--list",),
                              ("--packaged", "--artifact-node", "forge-1.20.1"),
                              ("--packaged", "--scope", "legacy"),
                              ("--packaged", "--scope", "full")):
                with self.subTest(arguments=arguments):
                    self.assertEqual(2, self.run_main(*arguments)[0])
            run.assert_not_called()

    def test_lane_listing_verifies_real_bundle_and_records_partial_coverage(self):
        code, output, error = self.run_main("--list", "--artifact-node", self.fixture.node)
        self.assertEqual((0, ""), (code, error))
        value = json.loads(output)
        scope = value["execution_scope"]
        self.assertEqual([self.fixture.node], scope["selected_nodes"])
        self.assertEqual([self.scenario], scope["scenarios"])
        self.assertTrue(scope["partial"])
        self.assertEqual(self.fixture.manifest["scope"], scope["artifact_scope"])
        self.assertEqual(self.fixture.manifest["artifacts"][0]["production"]["sha256"],
                         value["rows"][0]["production_jar_sha256"])

    def test_packaged_execution_records_same_scope_and_rechecks_before_promotion(self):
        with patch("e2e.orchestrator.PackagedRuntimeSession.from_environment",
                   return_value=SimpleNamespace(gc=lambda: {})), patch(
                       "e2e.orchestrator.run_packaged_row", side_effect=self.game_result) as run:
            code, _, error = self.run_main("--packaged", "--artifact-node", self.fixture.node)
        self.assertEqual((0, ""), (code, error))
        self.assertEqual(1, run.call_count)
        summary = json.loads((self.output / "current/summary.json").read_bytes())
        resolved = json.loads((self.output / "current/resolved-matrix.json").read_bytes())
        self.assertEqual(summary["execution_scope"], resolved["execution_scope"])
        self.assertEqual([self.fixture.node], summary["execution_scope"]["selected_nodes"])
        self.assertEqual(self.fixture.manifest["scope"], summary["execution_scope"]["artifact_scope"])

    def test_source_or_manifest_mutation_during_game_does_not_replace_prior_evidence(self):
        self.output.joinpath("current").mkdir(parents=True)
        sentinel = self.output / "current/old-evidence"
        sentinel.write_text("previous")
        def mutate(*args):
            result = self.game_result(*args)
            self.fixture.contract.write_text('{"changed":true}')
            return result
        with patch("e2e.orchestrator.PackagedRuntimeSession.from_environment",
                   return_value=SimpleNamespace(gc=lambda: {})), patch(
                       "e2e.orchestrator.run_packaged_row", side_effect=mutate):
            self.assertEqual(2, self.run_main("--packaged", "--artifact-node", self.fixture.node)[0])
        self.assertEqual("previous", sentinel.read_text())
        self.assertFalse(self.output.joinpath("current/summary.json").exists())

    def test_linked_stage_parent_is_not_resolved_away_by_cli(self):
        moved = self.fixture.stage.with_name("moved-release")
        self.fixture.stage.rename(moved)
        self.fixture.stage.symlink_to(moved, target_is_directory=True)
        code, _, error = self.run_main("--list", "--artifact-node", self.fixture.node)
        self.assertEqual(2, code)
        self.assertIn("symlink", error)

    def test_loaded_matrix_and_cached_contract_must_match_the_verified_stage(self):
        from e2e import orchestrator
        read = orchestrator.read_secure_json
        def old_matrix(*args, **kwargs):
            value, _ = read(*args, **kwargs)
            value["runtimes"][0]["loader_version"] = "0.17.4"
            return value, json.dumps(value).encode()
        for replacement in (patch("e2e.orchestrator.read_secure_json", side_effect=old_matrix),
                            patch("e2e.orchestrator.CONTRACT", SimpleNamespace(
                                scenario_ids=CONTRACT.scenario_ids, sha256="f" * 64))):
            with replacement, patch("e2e.orchestrator.run_packaged_row") as run:
                code, _, error = self.run_main("--packaged", "--artifact-node", self.fixture.node)
            self.assertEqual(2, code)
            self.assertIn("loaded execution inputs", error)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
