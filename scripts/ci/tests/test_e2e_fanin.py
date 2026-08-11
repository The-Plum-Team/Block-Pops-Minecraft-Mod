from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

from e2e.scenario_contract import load_contract
from scripts.ci.e2e_fanin import (
    AGGREGATE_ARTIFACT,
    AGGREGATE_RECEIPT,
    FanInError,
    SourceIdentity,
    _canonical_path,
    aggregate_artifact_name,
    create_aggregate,
    expected_lanes,
    run_artifact_prefix,
    validate_aggregate,
    validate_lane,
)
from scripts.release.matrix import load_matrix


REPO = Path(__file__).resolve().parents[3]
MATRIX_PATH = REPO / "release/release-matrix.json"
CONTRACT_PATH = REPO / "e2e/scenario-contract.json"
COMMIT = "1" * 40
TREE = "2" * 40


def _json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def _png(width: int, height: int, variant: int = 0) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    palette = (
        (14, 26, 48),
        (58, 24, 86),
        (28, 104, 72),
        (178, 132, 38),
        (42, 92, 154),
        (150, 54, 74),
        (58, 142, 148),
        (196, 174, 92),
    )
    rotated = palette[variant % len(palette) :] + palette[: variant % len(palette)]
    widths = [width // len(rotated)] * len(rotated)
    widths[-1] += width - sum(widths)
    pixels = b"".join(bytes(color) * count for color, count in zip(rotated, widths, strict=True))
    scanline = b"\0" + pixels
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr) + _chunk(
        b"IDAT", zlib.compress(scanline * height, 9)
    ) + _chunk(b"IEND", b"")


def _metrics(png: bytes, width: int, height: int) -> dict[str, object]:
    with Image.open(io.BytesIO(png)) as image:
        image.load()
        rgb = image.convert("RGB")
    sample = rgb.resize((160, 90), Image.Resampling.BILINEAR)
    luma = sample.convert("L")
    palette_counts = sample.quantize(colors=32).getcolors() or []
    sample_pixels = sample.width * sample.height
    histogram = luma.histogram()
    return {
        "width": width,
        "height": height,
        "file_sha256": hashlib.sha256(png).hexdigest(),
        "pixel_sha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
        "luma_entropy": round(float(luma.entropy()), 3),
        "meaningful_colors": sum(
            count >= max(2, sample_pixels // 1000) for count, _ in palette_counts
        ),
        "dark_fraction": round(sum(histogram[:8]) / sample_pixels, 4),
        "light_fraction": round(sum(histogram[248:]) / sample_pixels, 4),
    }


def _comparison(
    first: bytes,
    second: bytes,
    minimum: float,
    region: tuple[float, float, float, float] | None,
) -> dict[str, object]:
    with Image.open(io.BytesIO(first)) as first_image, Image.open(io.BytesIO(second)) as second_image:
        first_rgb = first_image.convert("RGB")
        second_rgb = second_image.convert("RGB")
    if region is not None:
        width, height = first_rgb.size
        box = (
            int(region[0] * width),
            int(region[1] * height),
            int(region[2] * width),
            int(region[3] * height),
        )
        first_rgb = first_rgb.crop(box)
        second_rgb = second_rgb.crop(box)
    difference = ImageChops.difference(first_rgb, second_rgb).convert("L")
    histogram = difference.histogram()
    pixels = difference.width * difference.height
    value: dict[str, object] = {
        "changed_fraction": round(sum(histogram[8:]) / pixels, 7),
        "rms_difference": round(
            (sum(level * level * count for level, count in enumerate(histogram)) / pixels)
            ** 0.5,
            3,
        ),
        "required_changed_fraction": minimum,
    }
    if region is not None:
        value["region"] = list(region)
    return value


class Fixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.matrix = load_matrix(MATRIX_PATH, validate_sources=False)
        self.contract = load_contract(CONTRACT_PATH)
        self.lanes = expected_lanes(
            self.matrix,
            self.contract,
            "pr-anchors",
            artifact_prefix=run_artifact_prefix(COMMIT, 2),
        )
        self.hashes = {
            lane.artifact_node: (
                hashlib.sha256((lane.artifact_node + ":production").encode()).hexdigest(),
                hashlib.sha256((lane.artifact_node + ":harness").encode()).hexdigest(),
            )
            for lane in self.lanes
        }
        self.identity = SourceIdentity(
            repository="AkaNebur/BlockPops",
            source_branch="automation/release-sync/opaque-head",
            commit=COMMIT,
            tree=TREE,
            run_id=101,
            run_attempt=2,
            projection="pr-anchors",
        )
        self.artifact_manifest = {
            "schema_version": 2,
            "git_commit": COMMIT,
            "git_tree": TREE,
            "release_branch": self.matrix["branch"]["name"],
            "lane_count": self.matrix["lane_count"],
            "artifacts": [
                {
                    "artifact_node": lane.artifact_node,
                    "production": {"sha256": self.hashes[lane.artifact_node][0]},
                    "harness": {"sha256": self.hashes[lane.artifact_node][1]},
                }
                for lane in self.lanes
            ],
        }

    def report(self, lane, scenario: str, role: str, pngs: dict[str, bytes]) -> tuple[dict, dict]:
        steps = []
        screenshot_metrics = {}
        for step in self.contract.role(scenario, role).steps:
            capture = step.capture
            capture_id = capture.capture_id if capture is not None else None
            screenshot = f"{capture_id}.png" if capture is not None else None
            steps.append(
                {
                    "id": step.id,
                    "status": "pass",
                    "message": "contracted assertion passed",
                    "capture_id": capture_id,
                    "screenshot": screenshot,
                }
            )
            if capture is not None:
                screenshot_metrics[step.id] = _metrics(
                    pngs[capture.capture_id], *self.contract.gui_text_reference_size
                )
        comparisons = {}
        for comparison in self.contract.comparisons_for(scenario, role):
            first_capture = next(
                step.capture.capture_id
                for step in self.contract.role(scenario, role).steps
                if step.id == comparison.first_step and step.capture is not None
            )
            second_capture = next(
                step.capture.capture_id
                for step in self.contract.role(scenario, role).steps
                if step.id == comparison.second_step and step.capture is not None
            )
            comparisons[f"{comparison.first_step}->{comparison.second_step}"] = _comparison(
                pngs[first_capture],
                pngs[second_capture],
                comparison.minimum_changed_fraction,
                comparison.region,
            )
        raw = {
            "schema_version": 1,
            "minecraft": lane.minecraft,
            "role": role,
            "scenario": scenario,
            "contract_sha256": self.contract.sha256,
            "status": "pass",
            "steps": steps,
        }
        enriched = {
            **raw,
            "pixel_validation": {
                "screenshots": screenshot_metrics,
                "comparisons": comparisons,
            },
        }
        return raw, enriched

    def populate_lane(self, lane) -> None:
        lane_root = self.root / lane.artifact_name
        results = []
        resolved = []
        production, harness = self.hashes[lane.artifact_node]
        width, height = self.contract.gui_text_reference_size
        for scenario in lane.scenarios:
            profile_name = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
            profile = lane_root / "profiles" / profile_name
            roles = self.contract.expected_roles(scenario)
            reports = {}
            for role in roles:
                captures = {
                    step.capture.capture_id: _png(width, height, index)
                    for index, step in enumerate(self.contract.role(scenario, role).steps)
                    if step.capture is not None
                }
                raw, enriched = self.report(lane, scenario, role, captures)
                reports[role] = enriched
                _json(profile / role / "e2e-report/report.json", raw)
                (profile / role / "e2e-report/done.marker").write_bytes(b"pass")
                for capture_id, png in captures.items():
                    screenshot = profile / role / "screenshots" / f"{capture_id}.png"
                    screenshot.parent.mkdir(parents=True, exist_ok=True)
                    screenshot.write_bytes(png)
            logs = profile / "logs"
            logs.mkdir(parents=True, exist_ok=True)
            for name in ("server-install", "server", *roles):
                (logs / f"{name}.log").write_text(f"{name} ok\n", encoding="utf-8")
            result = {
                "schema_version": 1,
                "artifact_node": lane.artifact_node,
                "minecraft": lane.minecraft,
                "loader": lane.loader,
                "scenario": scenario,
                "contract_sha256": self.contract.sha256,
                "production_jar_sha256": production,
                "harness_jar_sha256": harness,
                "port": 25565,
                "status": "pass",
                "profile": f"profiles/{profile_name}",
                "installed_blockpops": [
                    {
                        "path": f"{owner}/mods/BlockPops-{lane.artifact_node}.jar",
                        "sha256": production,
                    }
                    for owner in ("server", *roles)
                ],
                "reports": reports,
                "elapsed_s": 42.5,
                "error": None,
            }
            _json(profile / "result.json", result)
            results.append(result)
            resolved.append(
                {
                    "artifact_node": lane.artifact_node,
                    "minecraft": lane.minecraft,
                    "loader": lane.loader,
                    "scenario": scenario,
                    "production_jar_sha256": production,
                }
            )
        metrics = {
            "hits": 1,
            "misses": 2,
            "pruned_entries": 0,
            "pruned_bytes": 0,
            "total_bytes": 1234,
        }
        _json(
            lane_root / "summary.json",
            {
                "schema_version": 1,
                "contract_sha256": self.contract.sha256,
                "results": results,
                "runtime_store": metrics,
            },
        )
        _json(lane_root / "resolved-matrix.json", {"schema_version": 1, "rows": resolved})
        _json(lane_root / "runtime-store.json", {"schema_version": 1, "metrics": metrics})

    def populate(self) -> None:
        self.root.mkdir()
        for lane in self.lanes:
            self.populate_lane(lane)

    def create(self, output: Path, *, artifact_manifest: dict | None = None) -> dict:
        return create_aggregate(
            input_root=self.root,
            output=output,
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            identity=self.identity,
            artifact_manifest=artifact_manifest or self.artifact_manifest,
            artifact_manifest_sha256="4" * 64,
        )


class E2EFanInTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.fixture = Fixture(base / "lanes")
        self.fixture.populate()
        self.output = base / "aggregate"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def validate_exact(self) -> dict:
        identity = self.fixture.identity
        return validate_aggregate(
            root=self.output,
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            projection=identity.projection,
            expected_repository=identity.repository,
            expected_source_branch=identity.source_branch,
            expected_commit=identity.commit,
            expected_tree=identity.tree,
            expected_run_id=identity.run_id,
            expected_run_attempt=identity.run_attempt,
        )

    def test_fan_in_creates_one_exact_matrix_derived_aggregate(self) -> None:
        manifest = self.fixture.create(self.output)
        self.assertEqual(
            manifest,
            json.loads((self.output / AGGREGATE_RECEIPT).read_text(encoding="utf-8")),
        )
        self.assertEqual("packaged-e2e-aggregate", manifest["kind"])
        self.assertEqual(AGGREGATE_ARTIFACT, "packaged-e2e-aggregate")
        self.assertEqual(
            aggregate_artifact_name(COMMIT, 2),
            f"packaged-e2e-{COMMIT}-2-aggregate",
        )
        self.assertEqual(
            {lane.artifact_name for lane in self.fixture.lanes},
            {lane["artifact_name"] for lane in manifest["lanes"]},
        )
        self.assertEqual(len(self.fixture.lanes), len(manifest["lanes"]))
        self.assertEqual(2, json.loads((self.output / "summary.json").read_text())["runtime_store"]["hits"])
        validated = self.validate_exact()
        self.assertEqual(
            {lane["artifact_node"] for lane in manifest["lanes"]},
            {lane["artifact_node"] for lane in validated["lanes"]},
        )
        self.assertEqual(
            {
                AGGREGATE_RECEIPT,
                "profiles",
                "summary.json",
                "resolved-matrix.json",
                "runtime-store.json",
            },
            {item.name for item in self.output.iterdir()},
        )
        self.assertNotIn(
            AGGREGATE_RECEIPT,
            {record["path"] for record in manifest["files"]},
        )

    def test_missing_or_tampered_aggregate_receipt_fails_closed(self) -> None:
        self.fixture.create(self.output)
        receipt_path = self.output / AGGREGATE_RECEIPT
        original = receipt_path.read_bytes()
        receipt_path.unlink()
        with self.assertRaisesRegex(FanInError, "filesystem inventory"):
            self.validate_exact()

        receipt_path.write_bytes(original)
        receipt = json.loads(original)
        receipt["provenance"]["repository"] = "attacker/fork"
        _json(receipt_path, receipt)
        with self.assertRaisesRegex(FanInError, "source identity is stale"):
            self.validate_exact()

    def test_commit_tree_and_attempt_skew_are_bound_to_external_identity(self) -> None:
        self.fixture.create(self.output)
        receipt_path = self.output / AGGREGATE_RECEIPT
        original = json.loads(receipt_path.read_text(encoding="utf-8"))
        mutations = (
            ("commit", "3" * 40),
            ("tree", "3" * 40),
            ("run_attempt", 3),
        )
        for key, value in mutations:
            with self.subTest(key=key):
                receipt = copy.deepcopy(original)
                receipt["provenance"][key] = value
                if key in {"commit", "tree"}:
                    receipt["artifact_manifest"][key] = value
                _json(receipt_path, receipt)
                with self.assertRaisesRegex(FanInError, "source identity is stale"):
                    self.validate_exact()
        _json(receipt_path, original)

    def test_expected_source_identity_is_strictly_all_or_none(self) -> None:
        self.fixture.create(self.output)
        with self.assertRaisesRegex(FanInError, "all-or-none"):
            validate_aggregate(
                root=self.output,
                matrix_path=MATRIX_PATH,
                contract_path=CONTRACT_PATH,
                projection="pr-anchors",
                expected_repository=self.fixture.identity.repository,
            )

    def test_receipt_inventories_every_non_self_file_by_size_and_hash(self) -> None:
        receipt = self.fixture.create(self.output)
        actual_files = {
            path.relative_to(self.output).as_posix()
            for path in self.output.rglob("*")
            if path.is_file() and path.name != AGGREGATE_RECEIPT
        }
        self.assertEqual(actual_files, {record["path"] for record in receipt["files"]})

        receipt_path = self.output / AGGREGATE_RECEIPT
        receipt["files"][0]["sha256"] = "f" * 64
        _json(receipt_path, receipt)
        with self.assertRaisesRegex(FanInError, "non-self inventory is stale"):
            self.validate_exact()

    def test_each_sealed_lane_is_revalidated_against_its_exact_projected_row(self) -> None:
        lane = self.fixture.lanes[0]
        projected = next(
            row
            for row in self.fixture.matrix["runtimes"]
            if row["artifact_node"] == lane.artifact_node
        )
        row = {
            **projected,
            "id": lane.job_id,
            "scenarios": ",".join(lane.scenarios),
        }
        result = validate_lane(
            root=self.fixture.root / lane.artifact_name,
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            projection="pr-anchors",
            row=row,
            artifact_manifest=self.fixture.artifact_manifest,
        )
        self.assertEqual(lane.artifact_node, result["artifact_node"])
        self.assertEqual(len(lane.scenarios), result["scenario_count"])

        stale = copy.deepcopy(row)
        stale["minecraft"] = "0.0"
        with self.assertRaisesRegex(FanInError, "exact authoritative projected lane"):
            validate_lane(
                root=self.fixture.root / lane.artifact_name,
                matrix_path=MATRIX_PATH,
                contract_path=CONTRACT_PATH,
                projection="pr-anchors",
                row=stale,
                artifact_manifest=self.fixture.artifact_manifest,
            )

    def test_missing_duplicate_and_unknown_lane_evidence_fail_closed(self) -> None:
        missing = self.fixture.root / self.fixture.lanes[-1].artifact_name
        renamed = missing.with_name("held-back")
        missing.rename(renamed)
        with self.assertRaisesRegex(FanInError, "exactly one isolated"):
            self.fixture.create(self.output)
        renamed.rename(missing)

        summary_path = self.fixture.root / self.fixture.lanes[0].artifact_name / "summary.json"
        summary = json.loads(summary_path.read_text())
        summary["results"].append(copy.deepcopy(summary["results"][0]))
        _json(summary_path, summary)
        with self.assertRaisesRegex(FanInError, "summary result inventory"):
            self.fixture.create(self.output)

    def test_previous_attempt_lane_artifact_cannot_enter_the_fan_in(self) -> None:
        lane = self.fixture.lanes[0]
        stale_name = lane.artifact_name.replace(f"-{self.fixture.identity.run_attempt}-", "-1-", 1)
        (self.fixture.root / lane.artifact_name).rename(self.fixture.root / stale_name)
        with self.assertRaisesRegex(FanInError, "exactly one isolated directory"):
            self.fixture.create(self.output)

    def test_unknown_files_traversal_names_and_symlinks_are_rejected(self) -> None:
        lane_root = self.fixture.root / self.fixture.lanes[0].artifact_name
        unknown = lane_root / "profiles/unknown.txt"
        unknown.write_text("untrusted", encoding="utf-8")
        with self.assertRaisesRegex(FanInError, "unknown files"):
            self.fixture.create(self.output)
        unknown.unlink()

        with self.assertRaisesRegex(FanInError, "canonical"):
            _canonical_path("../escape.png", "mutation")
        if hasattr(os, "symlink"):
            link = lane_root / "profiles/link"
            link.symlink_to(lane_root / "summary.json")
            with self.assertRaisesRegex(FanInError, "unsafe file"):
                self.fixture.create(self.output)

    def test_hardlinked_evidence_cannot_alias_two_expected_paths(self) -> None:
        lane = self.fixture.lanes[0]
        scenario = lane.scenarios[0]
        profile = (
            self.fixture.root
            / lane.artifact_name
            / "profiles"
            / f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
            / "logs"
        )
        target = profile / "server.log"
        target.unlink()
        os.link(profile / "server-install.log", target)
        with self.assertRaisesRegex(FanInError, "aliases one file inode"):
            self.fixture.create(self.output)

    def test_duplicate_json_keys_and_stale_contract_identity_are_rejected(self) -> None:
        lane = self.fixture.lanes[0]
        summary_path = self.fixture.root / lane.artifact_name / "summary.json"
        original = summary_path.read_text(encoding="utf-8")
        summary_path.write_text(original.replace("{", '{"schema_version":1,', 1), encoding="utf-8")
        with self.assertRaisesRegex(FanInError, "duplicate JSON object key"):
            self.fixture.create(self.output)

    def test_jar_hashes_are_bound_to_verified_input_manifest(self) -> None:
        stale = copy.deepcopy(self.fixture.artifact_manifest)
        stale["artifacts"][0]["production"]["sha256"] = "9" * 64
        with self.assertRaisesRegex(FanInError, "identity is stale"):
            self.fixture.create(self.output, artifact_manifest=stale)

        old_schema = copy.deepcopy(self.fixture.artifact_manifest)
        old_schema["schema_version"] = 1
        old_schema.pop("git_tree")
        with self.assertRaisesRegex(FanInError, "schema/commit/tree"):
            self.fixture.create(self.output, artifact_manifest=old_schema)

    def test_dimension_and_semantic_report_mutations_are_rejected(self) -> None:
        lane = self.fixture.lanes[0]
        scenario = lane.scenarios[0]
        role = self.fixture.contract.expected_roles(scenario)[0]
        capture = next(
            step.capture
            for step in self.fixture.contract.role(scenario, role).steps
            if step.capture is not None
        )
        profile = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
        screenshot = (
            self.fixture.root
            / lane.artifact_name
            / "profiles"
            / profile
            / role
            / "screenshots"
            / f"{capture.capture_id}.png"
        )
        screenshot.write_bytes(_png(800, 450))
        with self.assertRaisesRegex(FanInError, "dimensions disagree"):
            self.fixture.create(self.output)

    def test_corrupt_pixels_and_fabricated_pixel_claims_fail_closed(self) -> None:
        lane = self.fixture.lanes[0]
        scenario = lane.scenarios[0]
        role = self.fixture.contract.expected_roles(scenario)[0]
        step = next(
            item
            for item in self.fixture.contract.role(scenario, role).steps
            if item.capture is not None
        )
        profile = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
        lane_root = self.fixture.root / lane.artifact_name
        screenshot = lane_root / "profiles" / profile / role / "screenshots" / f"{step.capture.capture_id}.png"
        damaged = bytearray(screenshot.read_bytes())
        idat = damaged.index(b"IDAT")
        damaged[idat + 8] ^= 0xFF
        screenshot.write_bytes(damaged)
        with self.assertRaisesRegex(FanInError, "fully decoded|decode|pixel metrics"):
            self.fixture.create(self.output)

        self.temporary.cleanup()
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.fixture = Fixture(base / "lanes")
        self.fixture.populate()
        self.output = base / "aggregate"
        lane = self.fixture.lanes[0]
        scenario = lane.scenarios[0]
        role = self.fixture.contract.expected_roles(scenario)[0]
        profile = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
        result_path = self.fixture.root / lane.artifact_name / "profiles" / profile / "result.json"
        summary_path = self.fixture.root / lane.artifact_name / "summary.json"
        result = json.loads(result_path.read_text())
        result["reports"][role]["pixel_validation"]["screenshots"][step.id]["pixel_sha256"] = "f" * 64
        _json(result_path, result)
        summary = json.loads(summary_path.read_text())
        summary["results"][0] = result
        _json(summary_path, summary)
        with self.assertRaisesRegex(FanInError, "pixel metrics are stale or fabricated"):
            self.fixture.create(self.output)

    def test_fabricated_comparison_claim_fails_closed(self) -> None:
        lane = self.fixture.lanes[0]
        scenario = lane.scenarios[0]
        role = self.fixture.contract.expected_roles(scenario)[0]
        profile = f"{lane.artifact_node}--{lane.minecraft}--{scenario}"
        result_path = self.fixture.root / lane.artifact_name / "profiles" / profile / "result.json"
        summary_path = self.fixture.root / lane.artifact_name / "summary.json"
        result = json.loads(result_path.read_text())
        comparison = next(iter(result["reports"][role]["pixel_validation"]["comparisons"].values()))
        comparison["changed_fraction"] = max(
            comparison["required_changed_fraction"],
            comparison["changed_fraction"] / 2,
        )
        _json(result_path, result)
        summary = json.loads(summary_path.read_text())
        summary["results"][0] = result
        _json(summary_path, summary)
        with self.assertRaisesRegex(FanInError, "comparison .* stale or fabricated"):
            self.fixture.create(self.output)

    def test_aggregate_semantic_mutations_are_rejected(self) -> None:
        self.fixture.create(self.output)
        result = next(self.output.glob("profiles/*/result.json"))
        value = json.loads(result.read_text())
        value["status"] = "fail"
        value["error"] = "mutated"
        _json(result, value)
        with self.assertRaises(FanInError):
            validate_aggregate(
                root=self.output,
                matrix_path=MATRIX_PATH,
                contract_path=CONTRACT_PATH,
                projection="pr-anchors",
            )

    def test_existing_output_is_never_replaced(self) -> None:
        self.output.mkdir()
        sentinel = self.output / "owned.txt"
        sentinel.write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(FanInError, "refusing to replace"):
            self.fixture.create(self.output)
        self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_aggregate_rejects_unknown_inventory_without_an_internal_manifest(self) -> None:
        self.fixture.create(self.output)
        (self.output / "self-asserted-provenance.json").write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(FanInError, "unknown entries"):
            validate_aggregate(
                root=self.output,
                matrix_path=MATRIX_PATH,
                contract_path=CONTRACT_PATH,
                projection="pr-anchors",
            )


if __name__ == "__main__":
    unittest.main()
