from __future__ import annotations

import copy
import json
import os
import stat
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

from e2e import packaged_runtime
from scripts.release.artifact_manifest import (
    ArtifactError,
    inspect_zip,
    verify_harness_jar,
    verify_production_jar,
)
from scripts.release.matrix import load_matrix_bytes


REPOSITORY = Path(__file__).resolve().parents[1]
MATRIX = load_matrix_bytes((REPOSITORY / "release" / "release-matrix.json").read_bytes())
FABRIC_ARTIFACT = next(row for row in MATRIX["artifacts"] if row["loader"] == "fabric")


def _write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


def _fabric_production_entries() -> dict[str, bytes]:
    metadata = {
        "id": "blockpops",
        "depends": {
            "minecraft": FABRIC_ARTIFACT["metadata"]["minecraft"],
            "fabricloader": FABRIC_ARTIFACT["metadata"]["loader"],
            "architectury": FABRIC_ARTIFACT["metadata"]["architectury"],
            "geckolib": FABRIC_ARTIFACT["metadata"]["geckolib"],
        },
    }
    return {
        "com/theplumteam/BlockPopsMod.class": b"class",
        "com/theplumteam/fabric/BlockPopsFabric.class": b"class",
        "blockpops.mixins.json": b"{}",
        "fabric.mod.json": json.dumps(metadata).encode(),
    }


def _fabric_harness_entries() -> dict[str, bytes]:
    metadata = {
        "id": "blockpops-e2e",
        "version": "0.0.0",
        "environment": "client",
        "depends": {"blockpops": "*"},
    }
    return {
        "com/theplumteam/e2e/E2EHarness.class": b"class",
        "com/theplumteam/e2e/generated/ScenarioContract.class": b"class",
        "com/theplumteam/e2e/fabric/BlockPopsE2EFabric.class": b"class",
        "fabric.mod.json": json.dumps(metadata).encode(),
    }


class JarValidationTests(unittest.TestCase):
    def test_minimal_production_and_harness_jars_preserve_physical_separation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = root / "BlockPops - Fabric - 1.20.1-test.jar"
            harness = root / "BlockPops E2E - Fabric - 1.20.1-0.0.0.jar"
            _write_zip(production, _fabric_production_entries())
            _write_zip(harness, _fabric_harness_entries())

            verify_production_jar(production, FABRIC_ARTIFACT)
            verify_harness_jar(harness, FABRIC_ARTIFACT)

    def test_production_rejects_harness_leak_and_stale_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaked = _fabric_production_entries()
            leaked["com/theplumteam/e2e/E2EHarness.class"] = b"class"
            leaked_path = root / "BlockPops - Fabric - 1.20.1-leaked.jar"
            _write_zip(leaked_path, leaked)
            with self.assertRaisesRegex(ArtifactError, "leaks the packaged E2E harness"):
                verify_production_jar(leaked_path, FABRIC_ARTIFACT)

            stale = _fabric_production_entries()
            metadata = json.loads(stale["fabric.mod.json"])
            metadata["depends"]["minecraft"] = "*"
            stale["fabric.mod.json"] = json.dumps(metadata).encode()
            stale_path = root / "BlockPops - Fabric - 1.20.1-stale.jar"
            _write_zip(stale_path, stale)
            with self.assertRaisesRegex(ArtifactError, "disagrees with the release matrix"):
                verify_production_jar(stale_path, FABRIC_ARTIFACT)

    def test_harness_rejects_production_or_foreign_classes(self) -> None:
        mutations = {
            "production": "com/theplumteam/BlockPopsMod.class",
            "foreign": "org/example/Foreign.class",
        }
        for label, class_name in mutations.items():
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "BlockPops E2E - Fabric - 1.20.1-0.0.0.jar"
                entries = _fabric_harness_entries()
                entries[class_name] = b"class"
                _write_zip(path, entries)
                with self.subTest(label=label), self.assertRaises(ArtifactError):
                    verify_harness_jar(path, FABRIC_ARTIFACT)

    def test_zip_entries_reject_traversal_absolute_backslash_and_drive_paths(self) -> None:
        unsafe_names = ("../escape.class", "/absolute.class", "dir\\entry.class", "C:drive.class")
        for name in unsafe_names:
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "unsafe.jar"
                _write_zip(path, {name: b"bad"})
                with self.subTest(name=name), self.assertRaisesRegex(
                    ArtifactError, "unsafe entry"
                ):
                    inspect_zip(path)

    def test_zip_rejects_duplicate_and_symlink_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            duplicate = root / "duplicate.jar"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(duplicate, "w") as archive:
                    archive.writestr("entry", b"one")
                    archive.writestr("entry", b"two")
            with self.assertRaisesRegex(ArtifactError, "duplicate ZIP entries"):
                inspect_zip(duplicate)

            symlink = root / "symlink.jar"
            info = zipfile.ZipInfo("link")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(symlink, "w") as archive:
                archive.writestr(info, b"target")
            with self.assertRaisesRegex(ArtifactError, "special/symlink entry"):
                inspect_zip(symlink)

    def test_zip_entry_and_uncompressed_size_limits_are_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bounded.jar"
            _write_zip(path, {"one": b"1234", "two": b"5678"})
            with mock.patch("scripts.release.artifact_manifest.MAX_ZIP_ENTRIES", 1):
                with self.assertRaisesRegex(ArtifactError, "entry count"):
                    inspect_zip(path)
            with mock.patch("scripts.release.artifact_manifest.MAX_UNCOMPRESSED_BYTES", 7):
                with self.assertRaisesRegex(ArtifactError, "uncompressed size"):
                    inspect_zip(path)


class ReportValidationTests(unittest.TestCase):
    scenario = "ui-regression"
    role = "client_a"

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.game_dir = Path(self.temporary.name)
        (self.game_dir / "e2e-report").mkdir()
        (self.game_dir / "screenshots").mkdir()
        role_contract = packaged_runtime.SCENARIO_CONTRACT.role(self.scenario, self.role)
        self.report = {
            "schema_version": 1,
            "minecraft": "1.20.1",
            "role": self.role,
            "scenario": self.scenario,
            "contract_sha256": packaged_runtime.SCENARIO_CONTRACT.sha256,
            "status": "pass",
            "steps": [],
        }
        for step in role_contract.steps:
            capture_id = (
                f"{self.scenario}.{self.role}.{step.id}" if step.capture is not None else None
            )
            screenshot = f"{capture_id}.png" if capture_id is not None else None
            self.report["steps"].append(
                {
                    "id": step.id,
                    "status": "pass",
                    "message": "assertion passed",
                    "capture_id": capture_id,
                    "screenshot": screenshot,
                }
            )
            if screenshot is not None:
                (self.game_dir / "screenshots" / screenshot).write_bytes(b"png evidence")
        self.row = {"minecraft": "1.20.1"}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_report(self) -> None:
        (self.game_dir / "e2e-report" / "report.json").write_text(
            json.dumps(self.report), encoding="utf-8"
        )

    def validate(self):
        self.write_report()
        with mock.patch.object(
            packaged_runtime,
            "inspect_screenshot_for_step",
            return_value={"width": 1600, "height": 900},
        ) as inspect, mock.patch.object(
            packaged_runtime,
            "compare_screenshots",
            return_value={"changed_fraction": 0.5},
        ) as compare:
            result = packaged_runtime.validate_report(
                self.game_dir, self.row, self.scenario, self.role
            )
        return result, inspect.call_count, compare.call_count

    def test_complete_report_is_bound_to_contract_and_exact_screenshot_inventory(self) -> None:
        result, inspected, compared = self.validate()

        role_contract = packaged_runtime.SCENARIO_CONTRACT.role(self.scenario, self.role)
        self.assertEqual(len(role_contract.steps), inspected)
        self.assertEqual(len(role_contract.comparisons), compared)
        self.assertEqual(
            set(role_contract.step_ids),
            set(result["pixel_validation"]["screenshots"]),
        )

    def test_report_identity_order_and_exact_schema_mutations_fail_closed(self) -> None:
        mutations = {
            "unknown key": lambda report: report.__setitem__("trusted", True),
            "stale contract": lambda report: report.__setitem__("contract_sha256", "0" * 64),
            "wrong version": lambda report: report.__setitem__("minecraft", "1.21.1"),
            "wrong role": lambda report: report.__setitem__("role", "client_b"),
            "failed report": lambda report: report.__setitem__("status", "fail"),
            "step order": lambda report: report["steps"].reverse(),
            "stale capture": lambda report: report["steps"][0].__setitem__("capture_id", "stale"),
            "oversized message": lambda report: report["steps"][0].__setitem__("message", "x" * 1025),
        }
        for label, mutation in mutations.items():
            original = copy.deepcopy(self.report)
            mutation(self.report)
            with self.subTest(label=label), self.assertRaises(packaged_runtime.RuntimeFailure):
                self.validate()
            self.report = original

    def test_extra_screenshot_is_rejected_even_when_all_contract_captures_exist(self) -> None:
        (self.game_dir / "screenshots" / "uncontracted.png").write_bytes(b"extra")
        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "inventory mismatch"):
            self.validate()

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_screenshot_symlink_is_rejected_before_pixel_inspection(self) -> None:
        first = self.report["steps"][0]["screenshot"]
        path = self.game_dir / "screenshots" / first
        path.unlink()
        outside = self.game_dir / "outside.png"
        outside.write_bytes(b"outside")
        path.symlink_to(outside)

        with self.assertRaises(packaged_runtime.RuntimeFailure):
            self.validate()


if __name__ == "__main__":
    unittest.main()
