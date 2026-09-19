"""Real synthetic archives/pixels exercise composition; no game or producer authentication."""

import copy
import hashlib
import json
import os
import shutil
import subprocess
import unittest
import zipfile
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

from scripts.ci import e2e_fanin as fanin
from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.ci.tests.test_e2e_fanin import CONTRACT_PATH, Fixture, _json
from scripts.release import content_evidence as content
from scripts.release.artifact_manifest import BUILD_IDENTITY_PATH, lane_build_identity, stage_release
from scripts.release.build_matrix import plan_build
from scripts.release.matrix import load_matrix_document
from tests import test_build_matrix as runner_tests


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseContentEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.runner = runner_tests.BuildMatrixExecutionTests(methodName="runTest")
        self.runner.setUp()
        self.addCleanup(self.runner.doCleanups)
        self.root, self.matrix_path = self.runner.root, self.runner.path
        self.node = "fabric-1.20.1"
        matrix = schema2_configuration()
        for artifact in matrix["artifacts"][:2]:
            artifact.update(next(row for row in schema1_matrix()["artifacts"]
                                 if row["artifact_node"] == artifact["artifact_node"]))
        self.matrix_path.write_text(json.dumps(matrix))
        contract_path = self.root / "e2e/scenario-contract.json"
        contract_path.parent.mkdir(exist_ok=True)
        shutil.copyfile(CONTRACT_PATH, contract_path)
        for args in (("add", "."), ("-c", "user.name=Test", "-c", "user.email=t@example.test", "commit", "-qm", "inputs")):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)
        document = load_matrix_document(self.matrix_path)
        original_spawn = self.runner.spawn

        def spawn(command, **kwargs):
            result = original_spawn(command, **kwargs)
            node = next(arg.split("=", 1)[1] for arg in command if arg.startswith("-PblockpopsLane="))
            source = json.loads(self.runner.report_path.read_text())["source"]
            lane = document.inventory.lane(node)
            identity = lane_build_identity(document, node, matrix_digest=digest(self.matrix_path),
                contract_digest=digest(contract_path), commit=source["commit"], tree=source["tree"])
            for harness, relative in ((False, lane.production_jar), (True, lane.harness_jar)):
                path = self.root / relative
                with zipfile.ZipFile(path) as archive:
                    entries = {name: archive.read(name) for name in archive.namelist()}
                entries[BUILD_IDENTITY_PATH] = json.dumps(identity).encode()
                if not harness:
                    name = lane.artifact["metadata"]["file"]
                    if lane.identity.loader == "fabric":
                        metadata = json.loads(entries[name]); metadata["version"] = lane.mod_version
                        entries[name] = json.dumps(metadata).encode()
                    else:
                        entries[name] = entries[name].replace(b'modId = "blockpops"\n',
                            f'modId = "blockpops"\nversion = "{lane.mod_version}"\n'.encode(), 1)
                runner_tests._write_zip(path, entries)
                with zipfile.ZipFile(path, "a") as archive:
                    archive.comment = self.producer.encode()
            return result

        self.runner.spawn = spawn
        manifests = {}
        for self.producer, options in (("build", {"scope": "legacy"}), ("e2e", {"artifact_node": self.node})):
            self.runner.plan = plan_build(self.matrix_path, **options)
            report = self.runner.execute(**options)
            self.assertEqual("success", report["status"], report.get("error"))
            stage = self.root / "build" / (self.producer + "-bundle")
            manifests[self.producer] = stage_release(repository=self.root, matrix_path=self.matrix_path,
                manifest_path=stage / "artifacts.json", stage=stage,
                scope="legacy" if self.producer == "build" else "lane",
                artifact_node=None if self.producer == "build" else self.node)
            if self.producer == "build":
                self.report_path = self.root / "build/build-report.json"
                self.report_path.write_bytes(self.runner.report_path.read_bytes())
        self.build_manifest, self.e2e_manifest = manifests["build"], manifests["e2e"]
        self.fixture = Fixture(self.root / "build/lane-inputs")
        identity = replace(self.fixture.identity, source_branch=matrix["branch"]["name"],
            commit=report["source"]["commit"], tree=report["source"]["tree"])
        self.fixture.lanes = fanin.expected_lanes(matrix, self.fixture.contract, identity.projection,
            scope="lane", artifact_node=self.node,
            artifact_prefix=fanin.run_artifact_prefix(identity.commit, identity.run_attempt))
        self.fixture.hashes = {row["artifact_node"]: tuple(row[kind]["sha256"] for kind in ("production", "harness"))
                              for row in self.e2e_manifest["artifacts"]}
        for lane in self.fixture.lanes:
            self.fixture.populate_lane(lane)
            coverage = {"kind": "lane", "selected_nodes": [lane.artifact_node], "scenarios": list(lane.scenarios),
                "target_nodes": self.e2e_manifest["scope"]["target_nodes"], "partial": True,
                "artifact_scope": self.e2e_manifest["scope"]}
            for filename in ("summary.json", "resolved-matrix.json"):
                path = self.fixture.root / lane.artifact_name / filename
                payload = json.loads(path.read_bytes()); payload["execution_scope"] = coverage
                _json(path, payload)
        self.aggregate = self.root / "build/aggregate"
        e2e_stage, build_stage = self.root / "build/e2e-bundle", self.root / "build/build-bundle"
        fanin.create_aggregate(input_root=self.fixture.root, output=self.aggregate, matrix_path=self.matrix_path,
            contract_path=contract_path, identity=identity, artifact_manifest=self.e2e_manifest,
            artifact_manifest_sha256=digest(e2e_stage / "artifacts.json"), scope="lane", artifact_node=self.node)
        self.arguments = dict(repository=self.root, matrix_path=self.matrix_path,
            expected_matrix_sha256=digest(self.matrix_path), artifact_node=self.node,
            build_stage=build_stage, build_scope="legacy",
            expected_build_manifest_sha256=digest(build_stage / "artifacts.json"),
            build_report_path=self.report_path, expected_build_report_sha256=digest(self.report_path),
            e2e_stage=e2e_stage, e2e_scope="lane", expected_e2e_manifest_sha256=digest(e2e_stage / "artifacts.json"),
            aggregate_root=self.aggregate, expected_aggregate_sha256=digest(self.aggregate / fanin.AGGREGATE_RECEIPT),
            expected_e2e_identity=identity)

    def read(self, **changes):
        return content.read_lane_content_evidence(**{**self.arguments, **changes})

    def test_distinct_producer_bytes_select_exact_e2e_production_without_authority_claims(self):
        result = self.read()
        build, e2e = self.build_manifest["artifacts"][0], self.e2e_manifest["artifacts"][0]
        self.assertNotEqual(build["production"]["sha256"], e2e["production"]["sha256"])
        self.assertEqual(build["build_identity"], e2e["build_identity"])
        self.assertEqual({**e2e["production"], "origin": "e2e", "stage": str(self.arguments["e2e_stage"])}, result["production"])
        self.assertEqual(build["production"], result["build"]["production"])
        self.assertEqual(e2e["harness"], result["e2e"]["harness"])
        self.assertEqual(self.build_manifest["scope"], result["build"]["scope"])
        self.assertEqual(self.e2e_manifest["scope"], result["e2e"]["scope"])
        self.assertEqual(asdict(self.arguments["expected_e2e_identity"]), result["e2e"]["source_identity"])
        self.assertEqual(list(self.fixture.lanes[0].scenarios), result["e2e"]["scenarios"])
        self.assertTrue(self.e2e_manifest["scope"]["partial"])
        self.assertFalse({"qualified", "fresh", "authorized", "release_ready", "migration_complete"} & set(result))

    def test_external_hash_identity_scope_and_missing_lane_are_not_inferred(self):
        mutations = [{key: "0" * 64} for key in self.arguments if key.endswith("sha256")]
        mutations.extend(({"build_scope": "lane"}, {"e2e_scope": "legacy"}, {"artifact_node": "forge-1.20.1"}))
        mutations.extend({"expected_e2e_identity": replace(self.arguments["expected_e2e_identity"], **change)}
                         for change in ({"commit": "1" * 40}, {"tree": "2" * 40}, {"run_attempt": 3}))
        for changed in mutations:
            with self.subTest(changed=changed), self.assertRaises(content.ContentEvidenceError): self.read(**changed)

    def test_relative_stage_paths_are_anchored_before_content_validation(self):
        previous = Path.cwd()
        try:
            os.chdir(self.root)
            result = self.read(repository=Path("."),
                **{key: self.arguments[key].relative_to(self.root)
                   for key in ("build_stage", "e2e_stage", "aggregate_root")})
            self.assertEqual(str(self.arguments["e2e_stage"]), result["production"]["stage"])
        finally:
            os.chdir(previous)

    def test_rehashed_failed_report_and_other_build_lane_hash_are_rejected(self):
        original = json.loads(self.report_path.read_bytes())
        for failed in (True, False):
            report = copy.deepcopy(original)
            if failed: report["status"], report["error"] = "failed", "later failure"
            else: report["lanes"][1]["outputs"]["production"]["sha256"] = "0" * 64
            self.report_path.write_text(json.dumps(report))
            with self.subTest(failed=failed), self.assertRaises(content.ContentEvidenceError):
                self.read(expected_build_report_sha256=digest(self.report_path))

    def test_build_bundle_cannot_substitute_for_executed_e2e_bundle(self):
        with self.assertRaises(content.ContentEvidenceError):
            self.read(e2e_stage=self.arguments["build_stage"], e2e_scope="legacy",
                      expected_e2e_manifest_sha256=self.arguments["expected_build_manifest_sha256"])

    def test_resealed_corrupt_png_and_late_report_change_are_rejected(self):
        validate = content.validate_aggregate
        def drift(**kwargs):
            observed = validate(**kwargs)
            self.report_path.write_bytes(self.report_path.read_bytes() + b"\n")
            return observed
        with patch.object(content, "validate_aggregate", side_effect=drift), self.assertRaises(content.ContentEvidenceError):
            self.read()
        self.arguments["expected_build_report_sha256"] = digest(self.report_path)
        png = next(self.aggregate.rglob("*.png")); png.write_bytes(png.read_bytes()[:-8])
        receipt_path = self.aggregate / fanin.AGGREGATE_RECEIPT
        receipt = json.loads(receipt_path.read_bytes())
        record = next(row for row in receipt["files"] if row["path"] == png.relative_to(self.aggregate).as_posix())
        record.update(size=png.stat().st_size, sha256=digest(png)); _json(receipt_path, receipt)
        with self.assertRaises(content.ContentEvidenceError): self.read(expected_aggregate_sha256=digest(receipt_path))

    def test_same_inode_and_size_pixel_change_during_final_bundle_validation_is_rejected(self):
        verify, calls = content.verify_staged, []
        before = fanin._inventory(self.aggregate)
        def mutate(**kwargs):
            observed = verify(**kwargs)
            calls.append(kwargs["stage"])
            if len(calls) == 4:
                png = next(self.aggregate.rglob("*.png"))
                payload = bytearray(png.read_bytes()); payload[len(payload) // 2] ^= 1
                png.write_bytes(payload)
                self.assertEqual(before, fanin._inventory(self.aggregate))
            return observed
        with patch.object(content, "verify_staged", side_effect=mutate), self.assertRaisesRegex(
            content.ContentEvidenceError, "aggregate payload changed"
        ):
            self.read()
        self.assertEqual(4, len(calls))

    def test_build_archive_changed_during_final_e2e_bundle_validation_is_rejected(self):
        verify, calls = content.verify_staged, []
        production = self.arguments["build_stage"] / self.build_manifest["artifacts"][0]["production"]["path"]
        def mutate(**kwargs):
            observed = verify(**kwargs)
            calls.append(kwargs["stage"])
            if len(calls) == 4:
                with zipfile.ZipFile(production, "a") as archive:
                    archive.comment = b"after-validation"
            return observed
        with patch.object(content, "verify_staged", side_effect=mutate), self.assertRaises(content.ContentEvidenceError):
            self.read()
        self.assertEqual(4, len(calls))
