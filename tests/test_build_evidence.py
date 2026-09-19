"""Durable records are evidence bindings, never freshness or release authority."""

import copy
import hashlib
import json
import os
import subprocess
import unittest
from unittest.mock import patch

from scripts.ci.tests.matrix_fixtures import schema1_matrix, schema2_configuration
from scripts.release.artifact_manifest import lane_build_identity
from scripts.release.build_evidence import BuildEvidenceError, read_lane_build_evidence
from scripts.release.build_matrix import plan_build
from scripts.release.matrix import load_matrix_document
from tests import test_build_matrix as runner_tests


class BuildEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.runner = runner_tests.BuildMatrixExecutionTests(methodName="runTest")
        self.runner.setUp()
        self.addCleanup(self.runner.doCleanups)
        self.matrix, self.path = self.runner.path, self.runner.report_path
        matrix = schema2_configuration()
        for artifact in matrix["artifacts"][:2]:
            artifact.update(next(row for row in schema1_matrix()["artifacts"] if row["artifact_node"] == artifact["artifact_node"]))
        self.matrix.write_text(json.dumps(matrix))
        contract = self.runner.root / "e2e/scenario-contract.json"
        contract.parent.mkdir(exist_ok=True); contract.write_text("{}")
        for args in (("add", "."), ("-c", "user.name=Test", "-c", "user.email=t@example.test", "commit", "-qm", "inputs")):
            subprocess.run(["git", "-C", str(self.runner.root), *args], check=True, capture_output=True)
        self.runner.plan = plan_build(self.matrix, scope="legacy")
        self.report = self.runner.execute(scope="legacy")  # Mock subprocess; real producer/observation validation.
        self.assertEqual("success", self.report["status"], self.report.get("error"))
        self.node = "fabric-1.20.1"
        document = load_matrix_document(self.matrix)
        self.manifest = {"schema_version": 3, "git_commit": self.report["source"]["commit"],
            "git_tree": self.report["source"]["tree"], "matrix": {"path": "release/release-matrix.json",
            "sha256": self.report["plan"]["matrix"]["sha256"]}, "scenario_contract": {
            "path": "e2e/scenario-contract.json", "sha256": hashlib.sha256(contract.read_bytes()).hexdigest()}, "artifacts": []}
        # Models the caller's already verified bundle; archive verification has separate integration tests.
        for result in self.report["lanes"]:
            row = {"artifact_node": result["artifact_node"], "build_identity": lane_build_identity(document,
                result["artifact_node"], matrix_digest=self.manifest["matrix"]["sha256"],
                contract_digest=self.manifest["scenario_contract"]["sha256"],
                commit=self.manifest["git_commit"], tree=self.manifest["git_tree"])}
            for kind, record in result["outputs"].items():
                row[kind] = {"path": kind + "/archive.jar", "filename": "archive.jar",
                             "bytes": record["size"], "sha256": record["sha256"]}
            self.manifest["artifacts"].append(row)

    def read(self, report=None, **kwargs):
        if report is not None:
            self.path.write_text(json.dumps(report))
        return read_lane_build_evidence(self.path, matrix_path=self.matrix, artifact_node=self.node,
            artifact_manifest=self.manifest, expected_sha256=kwargs.pop("expected_sha256", hashlib.sha256(self.path.read_bytes()).hexdigest()), **kwargs)

    def test_real_report_binds_each_legacy_lane_without_claiming_freshness(self):
        for self.node in ("fabric-1.20.1", "forge-1.20.1"):
            result = self.read()
            self.assertEqual(self.node, result["artifact_node"])
            self.assertEqual(self.report["run_id"], result["run_id"])
            self.assertEqual({"artifact_node", "build_identity", "run_id", "report_sha256", "production", "harness"}, set(result))
        self.assertGreater(len(load_matrix_document(self.matrix).inventory.targets), len(self.report["lanes"]))

    def test_rehashed_mutations_do_not_bypass_content_bindings(self):
        mutations = (("schema_version", True), ("schema_version", 1.0), ("status", "running"), ("status", "failed"),
            ("error", "new failure"), ("source.dirty", True), ("source.commit", "a" * 40),
            ("source.tree", "b" * 40), ("source.fingerprint", "0" * 64), ("source.files", []),
            ("plan.scope", "full"), ("plan.matrix.sha256", "0" * 64), ("plan.selected_nodes", [self.node]),
            ("lanes.0.exit_code", False), ("lanes.0.exit_code", 0.0), ("lanes.0.archive_validation", "unchecked"),
            ("lanes.0.observation.status", "unverified"), ("lanes.0.observation.compilers", {}),
            ("lanes.0.outputs.production.sha256", "0" * 64), ("lanes.0.outputs.harness.path", "other.jar"),
            ("lanes.0.outputs.production.size", True), ("toolchains.status", "unverified"),
            ("toolchains.gradle_jvm", "observed"), ("toolchains.gradle_jvm", True),
            ("toolchains.compiler_selection", "observed"), ("toolchains.compiler_selection", {}),
            ("toolchains.homes.17.extra", "unsupported"), ("toolchains.homes.17.home", "/jdk,other"),
            ("toolchains.homes.17.home", "/jdk\nother"), ("toolchains.homes.17.home", "/jdk\rother"),
            ("toolchains.property_overrides", []), ("lanes.0.command", ["./gradlew", "check"]))
        for path, value in mutations:
            report = copy.deepcopy(self.report)
            parts, target = path.split("."), report
            for part in parts[:-1]: target = target[int(part)] if isinstance(target, list) else target[part]
            target[parts[-1]] = value
            with self.subTest(path=path, value=value), self.assertRaises(BuildEvidenceError): self.read(report)

    def test_external_digest_and_verified_bundle_must_match(self):
        for digest in (None, "", "A" * 64, "0" * 64):
            with self.subTest(digest=digest), self.assertRaises(BuildEvidenceError): self.read(expected_sha256=digest)
        for key in ("mod_version", "matrix_sha256", "scenario_contract_sha256", "artifact_node"):
            old = self.manifest["artifacts"][0]["build_identity"][key]
            self.manifest["artifacts"][0]["build_identity"][key] = "stale"
            with self.subTest(key=key), self.assertRaises(BuildEvidenceError): self.read()
            self.manifest["artifacts"][0]["build_identity"][key] = old
        self.node = "neoforge-1.21.1"
        with self.assertRaises(BuildEvidenceError): self.read()

    def test_durable_report_does_not_open_historical_absolute_paths(self):
        moved = json.loads(json.dumps(self.report).replace(str(self.runner.root), "/sealed/old-checkout"))
        self.assertEqual(self.node, self.read(moved)["artifact_node"])

    def test_actual_single_lane_producer_report_is_accepted(self):
        self.runner.plan = plan_build(self.matrix, artifact_node=self.node)
        report = self.runner.execute(artifact_node=self.node)
        self.assertEqual("success", report["status"], report.get("error"))
        for kind, record in report["lanes"][0]["outputs"].items():
            self.manifest["artifacts"][0][kind].update(bytes=record["size"], sha256=record["sha256"])
        self.assertEqual(self.node, self.read()["artifact_node"])
        self.assertEqual([self.node], report["plan"]["selected_nodes"])

    def test_compiler_scope_destination_outcome_and_types_are_strict(self):
        for key, value in (("major", 17.0), ("release", True), ("home", "/other"),
                           ("destination", "/other/classes"), ("executable", "/other/javac"), ("version", "21.0.1")):
            changed = copy.deepcopy(self.report)
            compiler = next(iter(changed["lanes"][0]["observation"]["compilers"].values()))
            compiler["selected"][key] = value
            with self.subTest(key=key), self.assertRaises(BuildEvidenceError): self.read(changed)
        for key, value in (("failed", True), ("skipped", True), ("did_work", 1), ("no_source", True)):
            changed = copy.deepcopy(self.report)
            compiler = next(iter(changed["lanes"][0]["observation"]["compilers"].values()))
            compiler["outcome"][key] = value
            with self.subTest(key=key), self.assertRaises(BuildEvidenceError): self.read(changed)

    def test_links_duplicate_keys_and_changed_reads_fail_closed(self):
        original = self.path.read_bytes()
        other = self.path.with_name("copy.json"); other.write_bytes(original)
        self.path.unlink(); self.path.symlink_to(other)
        with self.assertRaises(BuildEvidenceError): self.read()
        self.path.unlink(); os.link(other, self.path)
        with self.assertRaises(BuildEvidenceError): self.read()
        self.path.unlink(); self.path.write_bytes(original[:-2] + b', "status": "success"}\n')
        with self.assertRaises(BuildEvidenceError): self.read()
        self.path.write_bytes(original)
        linked = self.runner.root / "linked-parent"; linked.symlink_to(self.path.parent)
        with self.assertRaises(BuildEvidenceError):
            read_lane_build_evidence(linked / self.path.name, matrix_path=self.matrix, artifact_node=self.node,
                artifact_manifest=self.manifest, expected_sha256=hashlib.sha256(original).hexdigest())
        from scripts.release import build_evidence
        reader = build_evidence._report_bytes
        def changed(path):
            value = reader(path)
            self.path.write_text('{"status":"failed"}')
            return value
        with patch.object(build_evidence, "_report_bytes", side_effect=changed), self.assertRaises(BuildEvidenceError): self.read()
