"""Visual curation follows a schema2 branch matrix through the scope its gate ran."""

import hashlib
import json
import shutil
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from e2e.scenario_contract import load_contract
from e2e.visual_capsule import _gated_nodes
from scripts.ci.tests.matrix_fixtures import schema2_configuration
from scripts.release.artifact_manifest import stage_release
from scripts.release.matrix import load_matrix
from scripts.visual.curate import (
    ArtifactIdentity,
    CurationError,
    RunIdentity,
    TestedIdentity,
    _bundle,
    _projected_identity,
    _verified_input_bundle,
    branch_matrix,
    exact_input_bundle_artifact,
)
from tests import test_scoped_artifact_manifest as scoped_fixture
from tests.matrix_fixtures import schema1_source_matrix
from tests.test_visual_capsule import CONTRACT_PATH


REPO = Path(__file__).resolve().parents[1]
LEGACY_NODES = ("fabric-1.20.1", "forge-1.20.1")
TESTED = "c" * 40


def _artifact(artifact_id, name, *, run_id=41, digest="d" * 64):
    return {"id": artifact_id, "name": name, "expired": False, "workflow_run": {"id": run_id},
            "digest": f"sha256:{digest}", "size_in_bytes": 1024}


class BranchMatrixScopeTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.contract = load_contract(CONTRACT_PATH)

    def write(self, name, matrix):
        # Curation reads API-fetched bytes without a source tree to inspect.
        path = self.root / name / "release-matrix.json"
        path.parent.mkdir()
        path.write_text(json.dumps(matrix), encoding="utf-8")
        return path

    def test_schema1_keeps_its_unscoped_reader(self):
        path = schema1_source_matrix()
        matrix, scope = branch_matrix(path)
        self.assertIsNone(scope)
        self.assertEqual(load_matrix(path, validate_sources=False), matrix)
        nodes, _ = _projected_identity(matrix, self.contract, "pr-anchors")
        self.assertEqual(tuple(sorted(row["artifact_node"] for row in matrix["runtimes"])), nodes)
        with self.assertRaises(CurationError):
            _projected_identity(matrix, self.contract, "pr-anchors", scope="legacy")

    def test_schema2_takes_the_scope_its_own_matrix_gates(self):
        for shared, expected in ((False, "legacy"), (True, "full")):
            with self.subTest(shared=shared):
                source = schema2_configuration(shared=shared)
                matrix, scope = branch_matrix(self.write(f"shared-{shared}", source))
                self.assertEqual(expected, scope)
                self.assertEqual(source, matrix)

    def test_the_live_matrix_projects_only_its_legacy_gate(self):
        matrix, scope = branch_matrix(REPO / "release/release-matrix.json")
        self.assertEqual("legacy", scope)
        contract = load_contract(REPO / "e2e/scenario-contract.json")
        for projection in ("pr-anchors", "scheduled-anchors"):
            nodes, scenarios = _projected_identity(matrix, contract, projection, scope=scope)
            self.assertEqual(LEGACY_NODES, nodes)
            self.assertEqual(tuple(sorted(contract.scenarios_for_profile("release"))), scenarios)
        self.assertEqual(set(LEGACY_NODES), _gated_nodes(matrix))

    def test_schema2_is_never_projected_without_the_gate_scope(self):
        matrix = schema2_configuration()
        # Unscoped, every declared target would be demanded of the evidence.
        with self.assertRaisesRegex(CurationError, "explicit scope"):
            _projected_identity(matrix, self.contract, "pr-anchors")
        with self.assertRaises(Exception):
            _projected_identity(matrix, self.contract, "pr-anchors", scope="shared")
        nodes, _ = _projected_identity(matrix, self.contract, "pr-anchors", scope="legacy")
        self.assertEqual(LEGACY_NODES, nodes)

    def test_capsule_requires_the_gated_lanes_not_every_declared_target(self):
        schema1 = load_matrix(schema1_source_matrix(), validate_sources=False)
        self.assertEqual({row["artifact_node"] for row in schema1["runtimes"]}, _gated_nodes(schema1))
        schema2 = schema2_configuration()
        self.assertEqual(set(LEGACY_NODES), _gated_nodes(schema2))
        self.assertLess(len(_gated_nodes(schema2)), len(schema2["runtimes"]))

    def test_a_scoped_aggregate_is_curated_only_with_its_input_bundle(self):
        run = RunIdentity(repository="owner/repo", head_repository="owner/repo", head_branch="master",
                          head_sha="b" * 40, run_id=41, run_attempt=1, event="workflow_dispatch",
                          created_at=datetime(2026, 9, 1, tzinfo=timezone.utc))
        tested = TestedIdentity(repository="owner/repo", source_head_repository="owner/repo",
                                source_head_branch="master", source_head_commit=TESTED,
                                tested_commit=TESTED, tested_tree="e" * 40, base_branch_hint=None)
        aggregate = ArtifactIdentity(artifact_id=7, name="aggregate", digest="d" * 64, size=1)
        cases = ((self.write("scoped", schema2_configuration()), None),
                 (schema1_source_matrix(), aggregate))
        for index, (matrix_path, input_bundle) in enumerate(cases):
            with self.subTest(matrix=matrix_path.name, bundle=input_bundle), \
                    self.assertRaisesRegex(CurationError, "runtime input bundle"):
                # Rejected before any API call: the API is deliberately absent.
                _bundle(api=None, run=run, tested=tested, artifact=aggregate, matrix_path=matrix_path,
                        contract_path=CONTRACT_PATH, workflow=b"", work=self.root / f"work-{index}",
                        input_bundle=input_bundle)


class InputBundleSelectionTests(unittest.TestCase):
    def test_selects_exactly_this_attempts_bundle(self):
        name = f"e2e-input-bundle-{TESTED}-2"
        values = [_artifact(1, f"packaged-e2e-{TESTED}-2-aggregate"), _artifact(2, name),
                  _artifact(3, f"e2e-input-bundle-{TESTED}-1")]
        selected = exact_input_bundle_artifact(values, run_id=41, tested_commit=TESTED, run_attempt=2)
        self.assertEqual(ArtifactIdentity(artifact_id=2, name=name, digest="d" * 64, size=1024), selected)
        self.assertIsNone(exact_input_bundle_artifact(values[:1], run_id=41, tested_commit=TESTED, run_attempt=2))
        for broken in ([_artifact(2, name, run_id=40)], [dict(_artifact(2, name), expired=True)],
                       [_artifact(2, name), _artifact(2, name)]):
            with self.subTest(broken=broken), self.assertRaises(CurationError):
                exact_input_bundle_artifact(broken, run_id=41, tested_commit=TESTED, run_attempt=2)


class _DownloadApi:
    def __init__(self, payload):
        self.payload = payload

    def download_artifact(self, artifact_id, destination, expected_size):
        destination.write_bytes(self.payload)


class VerifiedInputBundleTests(unittest.TestCase):
    """A real legacy bundle, verified from downloaded bytes beside its commit's inputs."""

    def setUp(self):
        fixture = scoped_fixture.ScopedManifestTests("test_schema3_producer_round_trip_and_repository_relative_cli")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        document, _header, rows = fixture.context(scope="legacy")
        fixture.write_lane_sources(document, rows)
        self.report = stage_release(repository=fixture.repo, matrix_path=fixture.matrix_path,
                                    manifest_path=fixture.manifest_path, stage=fixture.stage, scope="legacy")
        self.commit = fixture.git("rev-parse", "HEAD").decode().strip()
        self.tree = fixture.git("rev-parse", "HEAD^{tree}").decode().strip()
        self.manifest_sha256 = hashlib.sha256(fixture.manifest_path.read_bytes()).hexdigest()
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        # The tested commit's exact inputs, as curation fetches them from the API.
        self.files = self.root / "candidate-files"
        self.files.mkdir()
        shutil.copyfile(fixture.matrix_path, self.files / "release-matrix.json")
        shutil.copyfile(fixture.contract, self.files / "scenario-contract.json")
        self.stage = fixture.stage
        self.cases = 0

    def archive(self, mutate=None):
        path = self.root / f"bundle-{self.cases}.zip"
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(self.stage.rglob("*")):
                if item.is_file():
                    relative = item.relative_to(self.stage).as_posix()
                    payload = item.read_bytes()
                    archive.writestr(relative, mutate(relative, payload) if mutate else payload)
        return path.read_bytes()

    def verify(self, payload, *, scope="legacy", tested_commit=None, digest=None):
        self.cases += 1
        work = self.root / f"work-{self.cases}"
        work.mkdir()
        tested = TestedIdentity(repository="owner/repo", source_head_repository="owner/repo",
                                source_head_branch="master", source_head_commit=tested_commit or self.commit,
                                tested_commit=tested_commit or self.commit, tested_tree=self.tree,
                                base_branch_hint=None)
        bundle = ArtifactIdentity(artifact_id=9, name=f"e2e-input-bundle-{self.commit}-1",
                                  digest=digest or hashlib.sha256(payload).hexdigest(), size=len(payload))
        return _verified_input_bundle(api=_DownloadApi(payload), tested=tested, bundle=bundle,
                                      matrix_path=self.files / "release-matrix.json",
                                      contract_path=self.files / "scenario-contract.json",
                                      scope=scope, work=work)

    def test_exact_bundle_is_bound_to_its_verified_manifest(self):
        manifest, digest = self.verify(self.archive())
        self.assertEqual(self.report, manifest)
        self.assertEqual(self.manifest_sha256, digest)
        self.assertEqual(list(LEGACY_NODES), manifest["scope"]["selected_nodes"])

    def test_stale_identity_scope_digest_or_bytes_are_refused(self):
        payload = self.archive()

        def tamper(relative, data):
            return data + b"\0" if relative.startswith("files/") else data

        cases = {
            "another commit": dict(payload=payload, tested_commit="0" * 40),
            "another scope": dict(payload=payload, scope="full"),
            "unauthenticated download": dict(payload=payload, digest="0" * 64),
            "rewritten jar": dict(payload=self.archive(tamper)),
        }
        for label, arguments in cases.items():
            with self.subTest(label), self.assertRaises(CurationError):
                self.verify(**arguments)


if __name__ == "__main__":
    unittest.main()
