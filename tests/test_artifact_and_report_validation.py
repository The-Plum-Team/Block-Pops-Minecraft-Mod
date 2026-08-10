from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import stat
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from PIL import Image, ImageDraw

from e2e import packaged_runtime
from scripts.release.artifact_manifest import (
    ArtifactError,
    git_commit,
    git_tree,
    inspect_zip,
    verify_harness_jar,
    verify_production_jar,
)
from scripts.release.matrix import load_matrix_bytes


REPOSITORY = Path(__file__).resolve().parents[1]
MATRIX = load_matrix_bytes((REPOSITORY / "release" / "release-matrix.json").read_bytes())
FABRIC_ARTIFACT = next(row for row in MATRIX["artifacts"] if row["loader"] == "fabric")
FABRIC_MINECRAFT = FABRIC_ARTIFACT["minecraft"]
FML_ARTIFACT = next(
    row for row in MATRIX["artifacts"] if row["loader"] in {"forge", "neoforge"}
)


def _write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


class _DownloadResponse:
    def __init__(self, payload: bytes, content_length: str | None = None) -> None:
        self.stream = io.BytesIO(payload)
        self.headers = {}
        if content_length is not None:
            self.headers["Content-Length"] = content_length

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        self.stream.close()

    def read(self, size: int = -1) -> bytes:
        return self.stream.read(size)


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
        "depends": {
            "blockpops": "*",
            "fabricloader": FABRIC_ARTIFACT["metadata"]["loader"],
            "minecraft": FABRIC_ARTIFACT["metadata"]["minecraft"],
        },
    }
    return {
        "com/theplumteam/e2e/E2EHarness.class": b"class",
        "com/theplumteam/e2e/generated/ScenarioContract.class": b"class",
        "com/theplumteam/e2e/fabric/BlockPopsE2EFabric.class": b"class",
        "fabric.mod.json": json.dumps(metadata).encode(),
    }


def _fml_harness_entries(*, include_pack: bool) -> dict[str, bytes]:
    metadata = f'''modLoader = "javafml"
loaderVersion = "{FML_ARTIFACT["metadata"]["loader"]}"
license = "All Rights Reserved"
[[mods]]
modId = "blockpops_e2e"
version = "0.0.0"
displayTest = "IGNORE_ALL_VERSION"
[[dependencies.blockpops_e2e]]
modId = "blockpops"
mandatory = true
versionRange = "*"
ordering = "AFTER"
side = "CLIENT"
[[dependencies.blockpops_e2e]]
modId = "minecraft"
mandatory = true
versionRange = "{FML_ARTIFACT["metadata"]["minecraft"]}"
ordering = "NONE"
side = "CLIENT"
'''.encode()
    loader = FML_ARTIFACT["loader"]
    entrypoint = {
        "forge": "com/theplumteam/e2e/forge/BlockPopsE2EForge.class",
        "neoforge": "com/theplumteam/e2e/neoforge/BlockPopsE2ENeoForge.class",
    }[loader]
    entries = {
        "com/theplumteam/e2e/E2EHarness.class": b"class",
        "com/theplumteam/e2e/generated/ScenarioContract.class": b"class",
        entrypoint: b"class",
        FML_ARTIFACT["metadata"]["file"]: metadata,
    }
    if include_pack:
        entries["pack.mcmeta"] = b'{"pack":{"description":"BlockPops","pack_format":15}}'
    return entries


class JarValidationTests(unittest.TestCase):
    def test_minimal_production_and_harness_jars_preserve_physical_separation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = root / f"BlockPops - Fabric - {FABRIC_MINECRAFT}-test.jar"
            harness = root / f"BlockPops E2E - Fabric - {FABRIC_MINECRAFT}-0.0.0.jar"
            _write_zip(production, _fabric_production_entries())
            _write_zip(harness, _fabric_harness_entries())

            verify_production_jar(production, FABRIC_ARTIFACT)
            verify_harness_jar(harness, FABRIC_ARTIFACT)

    def test_production_rejects_harness_leak_and_stale_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaked = _fabric_production_entries()
            leaked["com/theplumteam/e2e/E2EHarness.class"] = b"class"
            leaked_path = root / f"BlockPops - Fabric - {FABRIC_MINECRAFT}-leaked.jar"
            _write_zip(leaked_path, leaked)
            with self.assertRaisesRegex(ArtifactError, "leaks the packaged E2E harness"):
                verify_production_jar(leaked_path, FABRIC_ARTIFACT)

            stale = _fabric_production_entries()
            metadata = json.loads(stale["fabric.mod.json"])
            metadata["depends"]["minecraft"] = "*"
            stale["fabric.mod.json"] = json.dumps(metadata).encode()
            stale_path = root / f"BlockPops - Fabric - {FABRIC_MINECRAFT}-stale.jar"
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
                path = Path(temporary) / (
                    f"BlockPops E2E - Fabric - {FABRIC_MINECRAFT}-0.0.0.jar"
                )
                entries = _fabric_harness_entries()
                entries[class_name] = b"class"
                _write_zip(path, entries)
                with self.subTest(label=label), self.assertRaises(ArtifactError):
                    verify_harness_jar(path, FABRIC_ARTIFACT)

    def test_active_fml_harness_requires_resource_pack_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            loader_label = {"forge": "Forge", "neoforge": "NeoForge"}[
                FML_ARTIFACT["loader"]
            ]
            prefix = f"BlockPops E2E - {loader_label} - {FML_ARTIFACT['minecraft']}"
            valid = root / f"{prefix}-0.0.0.jar"
            _write_zip(valid, _fml_harness_entries(include_pack=True))
            verify_harness_jar(valid, FML_ARTIFACT)

            missing = root / f"{prefix}-missing.jar"
            _write_zip(missing, _fml_harness_entries(include_pack=False))
            with self.assertRaisesRegex(ArtifactError, "pack.mcmeta"):
                verify_harness_jar(missing, FML_ARTIFACT)

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


class RuntimeDownloadBoundaryTests(unittest.TestCase):
    def test_verified_download_streams_into_one_fresh_file(self) -> None:
        payload = b"verified runtime installer"
        expected = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "installer.jar"
            response = _DownloadResponse(payload, str(len(payload)))
            with mock.patch(
                "e2e.packaged_runtime.urllib.request.urlopen", return_value=response
            ):
                actual = packaged_runtime.download(
                    "https://downloads.example.invalid/installer.jar",
                    destination,
                    expected,
                )
            self.assertEqual(destination, actual)
            self.assertEqual(payload, destination.read_bytes())
            self.assertEqual([], list(destination.parent.glob(".*.part")))

    def test_declared_and_streamed_oversize_downloads_fail_before_admission(self) -> None:
        expected = hashlib.sha256(b"never admitted").hexdigest()
        cases = (
            (b"12345", "5", "before transfer"),
            (b"12345", None, "per-blob byte limit"),
        )
        for payload, length, message in cases:
            with self.subTest(length=length), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary) / "installer.jar"
                response = _DownloadResponse(payload, length)
                with mock.patch(
                    "e2e.packaged_runtime.MAX_RUNTIME_DOWNLOAD_BYTES", 4
                ), mock.patch(
                    "e2e.packaged_runtime.RUNTIME_DOWNLOAD_CHUNK_BYTES", 2
                ), mock.patch(
                    "e2e.packaged_runtime.urllib.request.urlopen", return_value=response
                ), self.assertRaisesRegex(packaged_runtime.RuntimeFailure, message):
                    packaged_runtime.download(
                        "https://downloads.example.invalid/installer.jar",
                        destination,
                        expected,
                    )
                self.assertFalse(destination.exists())
                self.assertEqual([], list(destination.parent.glob(".*.part")))

    def test_invalid_or_truncated_content_length_fails_closed(self) -> None:
        expected = hashlib.sha256(b"abc").hexdigest()
        for payload, length, message in (
            (b"abc", "3.0", "invalid Content-Length"),
            (b"abc", "4", "disagrees with Content-Length"),
        ):
            with self.subTest(length=length), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary) / "installer.jar"
                with mock.patch(
                    "e2e.packaged_runtime.urllib.request.urlopen",
                    return_value=_DownloadResponse(payload, length),
                ), self.assertRaisesRegex(packaged_runtime.RuntimeFailure, message):
                    packaged_runtime.download(
                        "https://downloads.example.invalid/installer.jar",
                        destination,
                        expected,
                    )
                self.assertFalse(destination.exists())

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_destination_and_fresh_file_collisions_never_follow_symlinks(self) -> None:
        expected = hashlib.sha256(b"payload").hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / "outside"
            outside.write_bytes(b"owner data")
            destination = root / "installer.jar"
            destination.symlink_to(outside)
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "unsafe.*destination"):
                packaged_runtime.download(
                    "https://downloads.example.invalid/installer.jar",
                    destination,
                    expected,
                )
            self.assertEqual(b"owner data", outside.read_bytes())

            destination.unlink()
            collision = root / ".installer.jar.fixed.part"
            collision.symlink_to(outside)
            with mock.patch(
                "e2e.packaged_runtime.uuid.uuid4",
                return_value=SimpleNamespace(hex="fixed"),
            ), self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure, "collision-free"
            ):
                packaged_runtime.download(
                    "https://downloads.example.invalid/installer.jar",
                    destination,
                    expected,
                )
            self.assertTrue(collision.is_symlink())
            self.assertEqual(b"owner data", outside.read_bytes())


class ScreenshotNormalizationTests(unittest.TestCase):
    @staticmethod
    def _write_frame(path: Path, size: tuple[int, int]) -> None:
        image = Image.new("RGB", size, (8, 12, 20))
        draw = ImageDraw.Draw(image)
        width, height = size
        colors = (
            (16, 32, 64),
            (80, 32, 96),
            (32, 112, 80),
            (180, 140, 40),
        )
        for index, color in enumerate(colors):
            left = index * width // len(colors)
            right = (index + 1) * width // len(colors)
            draw.rectangle((left, 0, right, height), fill=color)
        image.save(path, format="PNG")

    def test_exact_integer_density_frame_is_canonicalized_before_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            screenshot = Path(temporary) / "retina.png"
            self._write_frame(screenshot, (3200, 1800))

            metrics = packaged_runtime.inspect_screenshot(screenshot)

            self.assertEqual((1600, 900), (metrics["width"], metrics["height"]))
            with Image.open(screenshot) as normalized:
                self.assertEqual((1600, 900), normalized.size)
                self.assertEqual("RGB", normalized.mode)

    def test_non_integer_or_asymmetric_dimensions_fail_closed(self) -> None:
        for dimensions in ((1920, 1080), (3200, 900), (800, 450)):
            with self.subTest(
                dimensions=dimensions
            ), tempfile.TemporaryDirectory() as temporary:
                screenshot = Path(temporary) / "incompatible.png"
                self._write_frame(screenshot, dimensions)
                with self.assertRaisesRegex(
                    packaged_runtime.RuntimeFailure, "dimensions must be"
                ):
                    packaged_runtime.inspect_screenshot(screenshot)


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
            "minecraft": FABRIC_MINECRAFT,
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
        self.row = {"minecraft": FABRIC_MINECRAFT}

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
            "wrong version": lambda report: report.__setitem__("minecraft", "0.0.0"),
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


class RuntimeLogIdentityTests(unittest.TestCase):
    def test_client_log_requires_blockpops_harness_completion_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            client_log = Path(temporary) / "client_a.log"
            client_log.write_text(
                "[BlockPops-E2E] finished; passed=true\n", encoding="utf-8"
            )
            packaged_runtime.scan_runtime_logs([client_log])

            client_log.write_text("[QS-E2E] FINISHED status=pass\n", encoding="utf-8")
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure, "missing.*BlockPops-E2E"
            ):
                packaged_runtime.scan_runtime_logs([client_log])

    def test_fatal_loader_linkage_still_fails_with_completion_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            client_log = Path(temporary) / "client_a.log"
            client_log.write_text(
                "[BlockPops-E2E] finished; passed=true\nNoClassDefFoundError: geckolib\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "fatal runtime"):
                packaged_runtime.scan_runtime_logs([client_log])


class ArtifactCommitIdentityTests(unittest.TestCase):
    def test_release_provenance_rejects_tracked_worktree_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            for arguments in (
                ("init", "-q", "-b", "master"),
                ("config", "user.name", "BlockPops Tests"),
                ("config", "user.email", "tests@blockpops.invalid"),
            ):
                subprocess.run(
                    ["git", "-C", str(repository), *arguments], check=True
                )
            tracked = repository / "tracked.txt"
            tracked.write_text("clean\n", encoding="utf-8")
            subprocess.run(
                ["git", "-C", str(repository), "add", "tracked.txt"], check=True
            )
            subprocess.run(
                ["git", "-C", str(repository), "commit", "-q", "-m", "initial"],
                check=True,
            )
            temporary_commit = subprocess.run(
                ["git", "-C", str(repository), "rev-parse", "HEAD"],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            ).stdout.strip()
            with mock.patch.dict(os.environ, {"BLOCKPOPS_TESTED_SHA": "0" * 40}):
                with self.assertRaisesRegex(ArtifactError, "does not equal checkout HEAD"):
                    git_commit(repository)
            with mock.patch.dict(
                os.environ, {"BLOCKPOPS_TESTED_SHA": temporary_commit}
            ):
                self.assertRegex(git_commit(repository), r"^[0-9a-f]{40}$")
                self.assertRegex(
                    git_tree(repository, git_commit(repository)), r"^[0-9a-f]{40}$"
                )

                tracked.write_text("dirty\n", encoding="utf-8")
                with self.assertRaisesRegex(ArtifactError, "tracked changes"):
                    git_commit(repository)

if __name__ == "__main__":
    unittest.main()
