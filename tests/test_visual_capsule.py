"""Synthetic visual capsule fixtures and mutation tests discovered by CI."""

from __future__ import annotations

import copy
import hashlib
import json
import stat
import tempfile
import unittest
import zipfile
from dataclasses import replace
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, PngImagePlugin

from e2e.scenario_contract import OpaqueStarsProbe, RequiredGuiTextProbe, load_contract
from e2e.visual_capsule import (
    CAPSULE_MANIFEST,
    build_capsule_manifest,
    read_only_capsule_image,
    read_only_review_records,
    validate_capsule,
    write_capsule,
)
from e2e.visual_evidence import (
    SCREENSHOT_METRIC_KEYS,
    MAX_SCREENSHOT_BYTES,
    SourceExpectation,
    VisualEvidenceError,
    canonicalize_png,
    collect_evidence,
    extract_authenticated_artifact,
    load_archived_evidence,
    validate_attestation,
    validate_contract_probes,
    _validate_metrics,
)
from e2e.visual_review_output import (
    VisualReviewOutputError,
    advisory_markdown,
    read_and_validate_review,
    validate_review_output,
    write_normalized_review,
)
from scripts.release.matrix import load_matrix, matrix_sha256
from tests.matrix_fixtures import schema1_source_matrix
from scripts.ci.tests.matrix_fixtures import (
    canonical_integration_matrix,
    reference_runtime,
    write_matrix_fixture,
)


REPO = Path(__file__).resolve().parents[1]
MATRIX_PATH = schema1_source_matrix()
CONTRACT_PATH = REPO / "e2e" / "scenario-contract.json"
PRODUCTION_SHA = "1" * 64
HARNESS_SHA = "2" * 64
BRANCH_MATRIX = load_matrix(MATRIX_PATH)
ACTIVE_NODES = sorted(row["artifact_node"] for row in BRANCH_MATRIX["runtimes"])
REFERENCE_ROW = reference_runtime(BRANCH_MATRIX)
REFERENCE_NODE = REFERENCE_ROW["artifact_node"]
CANONICAL_BRANCH = BRANCH_MATRIX["branch"]["canonical"]
ACTIVE_BRANCH = BRANCH_MATRIX["branch"]["name"]


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _image(path: Path, node: str, step_index: int, capture, *, metadata: str) -> None:
    width, height = 1600, 900
    image = Image.new("RGB", (width, height), (18, 22, 34))
    draw = ImageDraw.Draw(image)
    loader = node.partition("-")[0]
    node_shift = sum(loader.encode("utf-8")) % 20
    colors = [
        (35 + node_shift, 60, 110),
        (110, 45 + node_shift, 80),
        (45, 115, 75 + node_shift),
        (145, 105, 35 + node_shift),
        (65, 55, 135 + node_shift),
    ]
    stripe = width // len(colors)
    for index, color in enumerate(colors):
        draw.rectangle((index * stripe, 0, (index + 1) * stripe, height), fill=color)
    offset = 35 + step_index * 91
    draw.rectangle((offset, 120, offset + 420, 390), fill=(225, 225 - step_index * 12, 210))
    draw.rectangle((310, 470 + step_index * 9, 1280, 690), fill=(20, 25, 42))
    draw.ellipse((690 + step_index * 23, 290, 890 + step_index * 23, 490), fill=(170, 70, 215))
    # Derive synthetic fixtures from the active branch contract. This keeps the mutation
    # suite portable when a release branch owns different probe regions or thresholds.
    for probe in capture.probes:
        if isinstance(probe, RequiredGuiTextProbe):
            left, top, right, bottom = probe.box
            for y in range(top + 4, bottom - 3, 10):
                draw.rectangle(
                    (left + 8, y, min(right - 8, left + 280), y + 4),
                    fill=(245, 245, 245),
                )
        elif isinstance(probe, OpaqueStarsProbe):
            left, top, right, bottom = (
                int(probe.region[0] * width),
                int(probe.region[1] * height),
                int(probe.region[2] * width),
                int(probe.region[3] * height),
            )
            draw.rectangle((left, top, right, bottom), fill=(4, 5, 7))
    info = PngImagePlugin.PngInfo()
    info.add_text("untrusted-comment", metadata)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", pnginfo=info, compress_level=1 if metadata == "candidate" else 6)


def _comparison(
    first: Path,
    second: Path,
    region: tuple[float, float, float, float] | None,
) -> tuple[float, float]:
    with Image.open(first) as first_image, Image.open(second) as second_image:
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
        changed = sum(histogram[8:]) / pixels
        rms = (
            sum(value * value * count for value, count in enumerate(histogram)) / pixels
        ) ** 0.5
        return round(changed, 7), round(rms, 3)


def _build_evidence(
    root: Path,
    nodes: list[str],
    *,
    metadata: str,
    matrix_path: Path = MATRIX_PATH,
) -> list[dict[str, object]]:
    matrix = load_matrix(matrix_path)
    contract = load_contract(CONTRACT_PATH)
    rows = {row["artifact_node"]: row for row in matrix["runtimes"]}
    results: list[dict[str, object]] = []
    for node in sorted(nodes):
        row = rows[node]
        for scenario_id in sorted(contract.scenarios_for_profile("release")):
            scenario = contract.scenario(scenario_id)
            profile = root / "profiles" / f"{node}--{row['minecraft']}--{scenario_id}"
            reports: dict[str, object] = {}
            installed = [
                {"path": "server/mods/BlockPops.jar", "sha256": PRODUCTION_SHA}
            ]
            for role_contract in scenario.roles:
                role = role_contract.role
                installed.append(
                    {"path": f"{role}/mods/BlockPops.jar", "sha256": PRODUCTION_SHA}
                )
                steps: list[dict[str, object]] = []
                screenshot_metrics: dict[str, object] = {}
                screenshot_paths: dict[str, Path] = {}
                for step_index, step_contract in enumerate(role_contract.steps):
                    capture = step_contract.capture
                    capture_id = capture.capture_id if capture else None
                    screenshot = f"{capture_id}.png" if capture else None
                    steps.append(
                        {
                            "id": step_contract.id,
                            "status": "pass",
                            "message": "assertion passed",
                            "capture_id": capture_id,
                            "screenshot": screenshot,
                        }
                    )
                    if capture is None:
                        continue
                    screenshot_path = profile / role / "screenshots" / screenshot
                    _image(
                        screenshot_path,
                        node,
                        step_index,
                        capture,
                        metadata=metadata,
                    )
                    *_, metrics = canonicalize_png(
                        screenshot_path,
                        expected_size=contract.gui_text_reference_size,
                    )
                    screenshot_metrics[step_contract.id] = {
                        key: metrics[key] for key in SCREENSHOT_METRIC_KEYS
                    }
                    screenshot_paths[step_contract.id] = screenshot_path
                comparisons: dict[str, object] = {}
                for comparison in role_contract.comparisons:
                    changed, rms = _comparison(
                        screenshot_paths[comparison.first_step],
                        screenshot_paths[comparison.second_step],
                        comparison.region,
                    )
                    comparison_record: dict[str, object] = {
                        "changed_fraction": changed,
                        "rms_difference": rms,
                        "required_changed_fraction": comparison.minimum_changed_fraction,
                    }
                    if comparison.region is not None:
                        comparison_record["region"] = list(comparison.region)
                    comparisons[
                        f"{comparison.first_step}->{comparison.second_step}"
                    ] = comparison_record
                raw_report = {
                    "schema_version": 1,
                    "minecraft": row["minecraft"],
                    "role": role,
                    "scenario": scenario_id,
                    "contract_sha256": contract.sha256,
                    "status": "pass",
                    "steps": steps,
                }
                report = dict(raw_report)
                report["pixel_validation"] = {
                    "screenshots": screenshot_metrics,
                    "comparisons": comparisons,
                }
                reports[role] = report
                _write_json(profile / role / "e2e-report" / "report.json", raw_report)
                (profile / role / "e2e-report" / "done.marker").write_text(
                    "pass\n", encoding="utf-8"
                )
                (profile / "logs").mkdir(parents=True, exist_ok=True)
                (profile / "logs" / f"{role}.log").write_text(
                    "packaged client passed\n", encoding="utf-8"
                )
            result = {
                "schema_version": 1,
                "artifact_node": node,
                "minecraft": row["minecraft"],
                "loader": row["loader"],
                "scenario": scenario_id,
                "contract_sha256": contract.sha256,
                "production_jar_sha256": PRODUCTION_SHA,
                "harness_jar_sha256": HARNESS_SHA,
                "port": 25565,
                "status": "pass",
                "profile": profile.relative_to(root).as_posix(),
                "installed_blockpops": installed,
                "reports": reports,
                "elapsed_s": 12.5,
                "error": None,
            }
            _write_json(profile / "result.json", result)
            results.append(result)
    metrics = {
        "hits": 0,
        "misses": 1,
        "pruned_entries": 0,
        "pruned_bytes": 0,
        "total_bytes": 123,
    }
    resolved = [
        {
            "artifact_node": result["artifact_node"],
            "minecraft": result["minecraft"],
            "loader": result["loader"],
            "scenario": result["scenario"],
            "production_jar_sha256": result["production_jar_sha256"],
        }
        for result in results
    ]
    _write_json(root / "resolved-matrix.json", {"schema_version": 1, "rows": resolved})
    _write_json(
        root / "summary.json",
        {
            "schema_version": 1,
            "contract_sha256": contract.sha256,
            "results": results,
            "runtime_store": metrics,
        },
    )
    _write_json(root / "runtime-store.json", {"schema_version": 1, "metrics": metrics})
    return results


def _zip_tree(source: Path, destination: Path) -> str:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def _source(
    root: Path,
    *,
    name: str,
    nodes: list[str],
    artifact_id: int,
    source_head_branch: str,
    base_branch: str,
    event: str,
    metadata: str,
    matrix_path: Path = MATRIX_PATH,
) -> tuple[Path, Path, SourceExpectation]:
    evidence = root / f"{name}-evidence"
    _build_evidence(evidence, nodes, metadata=metadata, matrix_path=matrix_path)
    archive = root / f"{name}.zip"
    archive_sha = _zip_tree(evidence, archive)
    contract = load_contract(CONTRACT_PATH)
    fields = {
        "repository": "AkaNebur/BlockPops",
        "source_head_repository": "AkaNebur/BlockPops",
        "source_head_branch": source_head_branch,
        "base_branch": base_branch,
        "source_head_commit": ("a" if name == "candidate" else "b") * 40,
        "tested_commit": (
            ("9" * 40) if event == "pull_request_target" else (("a" if name == "candidate" else "b") * 40)
        ),
        "tested_tree": ("c" if name == "candidate" else "d") * 40,
        "workflow_path": ".github/workflows/packaged-e2e.yml",
        "workflow_sha256": "e" * 64,
        "job_graph_sha256": "f" * 64,
        "event": event,
        "run_id": 100 + artifact_id,
        "run_attempt": 1,
        "artifact_id": artifact_id,
        "artifact_name": f"packaged-e2e-{name}",
        "artifact_sha256": archive_sha,
    }
    attestation = {
        "schema_version": 1,
        **fields,
        "status": "completed",
        "conclusion": "success",
        "matrix_sha256": matrix_sha256(matrix_path),
        "contract_sha256": contract.sha256,
        "artifact_nodes": sorted(nodes),
        "scenarios": sorted(contract.scenarios_for_profile("release")),
    }
    attestation_path = root / f"{name}-attestation.json"
    _write_json(attestation_path, attestation)
    return archive, attestation_path, SourceExpectation(**fields)


class VisualCapsuleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.shared = tempfile.TemporaryDirectory(prefix="blockpops-visual-tests-")
        root = Path(cls.shared.name)
        cls.reference_matrix_path = write_matrix_fixture(
            root, canonical_integration_matrix(BRANCH_MATRIX)
        )
        candidate = _source(
            root,
            name="candidate",
            nodes=ACTIVE_NODES,
            artifact_id=31,
            source_head_branch="feature/visual-candidate",
            base_branch=ACTIVE_BRANCH,
            event="pull_request_target",
            metadata="candidate",
        )
        reference = _source(
            root,
            name="reference",
            nodes=[REFERENCE_NODE],
            artifact_id=41,
            source_head_branch=CANONICAL_BRANCH,
            base_branch=CANONICAL_BRANCH,
            event="push",
            metadata="reference",
            matrix_path=cls.reference_matrix_path,
        )
        cls.candidate = load_archived_evidence(
            archive=candidate[0],
            attestation_path=candidate[1],
            expectation=candidate[2],
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "candidate-extracted",
        )
        cls.reference = load_archived_evidence(
            archive=reference[0],
            attestation_path=reference[1],
            expectation=reference[2],
            matrix_path=cls.reference_matrix_path,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "reference-extracted",
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.cleanup()

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="blockpops-capsule-case-")
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _capsule(self) -> Path:
        capsule = self.root / "capsule"
        digest = write_capsule(capsule, self.candidate, self.reference)
        self.assertRegex(digest, r"^[0-9a-f]{64}$")
        return capsule

    def test_pairs_every_lane_by_semantic_capture_against_fabric_baseline(self) -> None:
        capsule = self._capsule()
        manifest, pairs = validate_capsule(capsule)
        captures = load_contract(CONTRACT_PATH).capture_ids
        self.assertEqual(len(ACTIVE_NODES) * len(captures), len(pairs))
        self.assertEqual(
            set(ACTIVE_NODES),
            {pair["candidate"]["artifact_node"] for pair in pairs},
        )
        self.assertEqual(
            {REFERENCE_NODE}, {pair["reference"]["artifact_node"] for pair in pairs}
        )
        self.assertEqual(5, len({pair["capture_id"] for pair in pairs}))
        self.assertTrue(manifest["advisory"])
        records = read_only_review_records(capsule)
        self.assertEqual(len(pairs), len(records))
        self.assertEqual(
            pairs[0]["candidate"]["path"], records[0]["candidate_path"]
        )
        image = read_only_capsule_image(capsule, records[0]["candidate_path"])
        self.assertEqual(
            hashlib.sha256(image).hexdigest(),
            records[0]["candidate_path"].removeprefix("images/").removesuffix(".png"),
        )

    def test_normalization_strips_metadata_and_deduplicates_same_pixels(self) -> None:
        manifest, _images = build_capsule_manifest(self.candidate, self.reference)
        same_loader_pairs = [
            pair
            for pair in manifest["pairs"]
            if pair["candidate"]["loader"] == REFERENCE_ROW["loader"]
        ]
        self.assertEqual(5, len(same_loader_pairs))
        for pair in same_loader_pairs:
            self.assertNotEqual(
                pair["candidate"]["source_file_sha256"],
                pair["reference"]["source_file_sha256"],
            )
            self.assertEqual(pair["candidate"]["path"], pair["reference"]["path"])
            self.assertEqual(
                pair["candidate"]["pixel_sha256"], pair["reference"]["pixel_sha256"]
            )

    def test_missing_baseline_capture_fails_closed(self) -> None:
        broken = replace(self.reference, frames=self.reference.frames[:-1])
        with self.assertRaisesRegex(VisualEvidenceError, "baseline semantic coverage"):
            build_capsule_manifest(self.candidate, broken)

    def test_reference_must_be_current_protected_head(self) -> None:
        provenance = dict(self.reference.provenance)
        provenance["source_head_branch"] = "stale-baseline"
        broken = replace(self.reference, provenance=provenance)
        with self.assertRaisesRegex(VisualEvidenceError, "current-head"):
            build_capsule_manifest(self.candidate, broken)

    def test_capsule_rejects_extra_mutated_and_symlink_images(self) -> None:
        for mutation in ("extra", "mutated", "symlink"):
            with self.subTest(mutation=mutation):
                destination = self.root / mutation
                write_capsule(destination, self.candidate, self.reference)
                if mutation == "extra":
                    (destination / "unexpected.txt").write_text("x", encoding="utf-8")
                else:
                    image = next((destination / "images").iterdir())
                    image.chmod(0o644)
                    if mutation == "mutated":
                        payload = image.read_bytes()
                        image.write_bytes(payload[:-1] + bytes([payload[-1] ^ 1]))
                    else:
                        image.unlink()
                        image.symlink_to(destination / CAPSULE_MANIFEST)
                with self.assertRaises(VisualEvidenceError):
                    validate_capsule(destination)

    def test_capsule_rejects_manifest_digest_skew(self) -> None:
        capsule = self._capsule()
        manifest = capsule / CAPSULE_MANIFEST
        manifest.chmod(0o644)
        manifest.write_bytes(manifest.read_bytes() + b" ")
        with self.assertRaisesRegex(VisualEvidenceError, "digest"):
            validate_capsule(capsule)


class VisualBoundaryMutationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="blockpops-visual-boundary-")
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_attestation_rejects_unknown_stale_and_self_asserted_fields(self) -> None:
        archive, attestation_path, expectation = _source(
            self.root,
            name="candidate",
            nodes=[ACTIVE_NODES[0]],
            artifact_id=51,
            source_head_branch="candidate",
            base_branch=ACTIVE_BRANCH,
            event="pull_request_target",
            metadata="candidate",
        )
        value = json.loads(attestation_path.read_text(encoding="utf-8"))
        contract = load_contract(CONTRACT_PATH)
        for mutation in ("unknown", "head", "matrix", "legacy-event"):
            with self.subTest(mutation=mutation):
                broken = copy.deepcopy(value)
                if mutation == "unknown":
                    broken["token"] = "must-not-pass"
                elif mutation == "head":
                    broken["tested_commit"] = "f" * 40
                elif mutation == "legacy-event":
                    broken["event"] = "pull_request"
                else:
                    broken["matrix_sha256"] = "f" * 64
                with self.assertRaises(VisualEvidenceError):
                    validate_attestation(
                        broken,
                        expectation,
                        expected_matrix_sha256=matrix_sha256(MATRIX_PATH),
                        expected_contract_sha256=contract.sha256,
                    )

    def test_archive_rejects_traversal_symlink_and_case_collision(self) -> None:
        for mutation in ("traversal", "symlink", "collision"):
            with self.subTest(mutation=mutation):
                archive_path = self.root / f"{mutation}.zip"
                with zipfile.ZipFile(archive_path, "w") as archive:
                    if mutation == "traversal":
                        archive.writestr("../escape", b"x")
                    elif mutation == "symlink":
                        entry = zipfile.ZipInfo("profiles/link")
                        entry.create_system = 3
                        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                        archive.writestr(entry, b"/etc/passwd")
                    else:
                        archive.writestr("profiles/A/one", b"x")
                        archive.writestr("profiles/a/two", b"y")
                digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
                with self.assertRaises(VisualEvidenceError):
                    extract_authenticated_artifact(
                        archive_path,
                        self.root / f"extracted-{mutation}",
                        expected_sha256=digest,
                    )

    def test_archive_digest_is_mandatory(self) -> None:
        archive_path = self.root / "simple.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("profiles/result.json", b"{}")
        with self.assertRaisesRegex(VisualEvidenceError, "digest"):
            extract_authenticated_artifact(
                archive_path,
                self.root / "extracted",
                expected_sha256="0" * 64,
            )

    def test_sparse_oversized_screenshot_is_rejected_before_decode(self) -> None:
        screenshot = self.root / "oversized.png"
        with screenshot.open("wb") as stream:
            stream.seek(MAX_SCREENSHOT_BYTES)
            stream.write(b"x")
        with self.assertRaisesRegex(VisualEvidenceError, "between 1 and"):
            canonicalize_png(screenshot, expected_size=(1600, 900))

    def test_evidence_rejects_symlink_and_dimension_skew(self) -> None:
        evidence = self.root / "evidence"
        _build_evidence(evidence, [ACTIVE_NODES[0]], metadata="candidate")
        matrix = load_matrix(MATRIX_PATH)
        contract = load_contract(CONTRACT_PATH)
        provenance = {
            "artifact_nodes": [ACTIVE_NODES[0]],
            "scenarios": sorted(contract.scenarios_for_profile("release")),
            "artifact_id": 1,
        }
        screenshot = next(evidence.glob("profiles/*/client_a/screenshots/*.png"))
        original = screenshot.read_bytes()
        screenshot.unlink()
        screenshot.symlink_to(evidence / "summary.json")
        with self.assertRaisesRegex(VisualEvidenceError, "symbolic link"):
            collect_evidence(
                evidence, matrix=matrix, contract=contract, provenance=provenance
            )
        screenshot.unlink()
        with Image.open(__import__("io").BytesIO(original)) as source:
            source.resize((800, 900)).save(screenshot, format="PNG")
        with self.assertRaisesRegex(VisualEvidenceError, "dimensions"):
            collect_evidence(
                evidence, matrix=matrix, contract=contract, provenance=provenance
            )

    def test_contract_probe_rejects_washed_out_modal_background(self) -> None:
        contract = load_contract(CONTRACT_PATH)
        capture = contract.capture("ui-regression", "client_a", "favorite_color_prompt")
        path = self.root / "washed-out.png"
        _image(path, REFERENCE_NODE, 0, capture, metadata="candidate")
        with Image.open(path) as source:
            image = source.convert("RGB")
        ImageDraw.Draw(image).rectangle((32, 135, 320, 765), fill=(250, 250, 250))
        image.save(path, format="PNG")
        *_, canonical, _metrics = canonicalize_png(path, expected_size=(1600, 900))
        with self.assertRaisesRegex(VisualEvidenceError, "opaque-stars-background"):
            validate_contract_probes(canonical, capture, "washed-out favorite color modal")

    def test_probe_calibration_accepts_every_matrix_lane_and_capture(self) -> None:
        contract = load_contract(CONTRACT_PATH)
        scenario = contract.scenario("ui-regression")
        for node in ACTIVE_NODES:
            for role in scenario.roles:
                for index, step in enumerate(role.steps):
                    if step.capture is None:
                        continue
                    with self.subTest(node=node, capture=step.capture.capture_id):
                        path = self.root / "calibration" / node / f"{step.id}.png"
                        _image(path, node, index, step.capture, metadata="candidate")
                        *_, canonical, _metrics = canonicalize_png(
                            path, expected_size=contract.gui_text_reference_size
                        )
                        validate_contract_probes(
                            canonical,
                            step.capture,
                            f"{node} {step.capture.capture_id} calibration canary",
                        )

    def test_probe_calibration_rejects_empty_frame(self) -> None:
        path = self.root / "empty-frame.png"
        Image.new("RGB", (1600, 900), (0, 0, 0)).save(path, format="PNG")
        *_, metrics = canonicalize_png(path, expected_size=(1600, 900))
        reported = {key: metrics[key] for key in SCREENSHOT_METRIC_KEYS}
        with self.assertRaisesRegex(VisualEvidenceError, "effectively blank"):
            _validate_metrics(reported, metrics, "empty calibration canary")

    def test_probe_calibration_rejects_missing_and_blurred_required_text(self) -> None:
        contract = load_contract(CONTRACT_PATH)
        capture = contract.capture("ui-regression", "client_a", "favorite_color_prompt")
        text_probe = next(
            probe for probe in capture.probes if isinstance(probe, RequiredGuiTextProbe)
        )
        source = self.root / "text-canary.png"
        _image(source, REFERENCE_NODE, 0, capture, metadata="candidate")
        with Image.open(source) as opened:
            original = opened.convert("RGB")

        missing = original.copy()
        ImageDraw.Draw(missing).rectangle(text_probe.box, fill=(32, 32, 32))
        missing_path = self.root / "missing-text.png"
        missing.save(missing_path, format="PNG")
        *_, missing_png, _metrics = canonicalize_png(
            missing_path, expected_size=contract.gui_text_reference_size
        )
        with self.assertRaisesRegex(VisualEvidenceError, "required-gui-text"):
            validate_contract_probes(missing_png, capture, "missing text calibration canary")

        crisp = original.copy()
        crisp_draw = ImageDraw.Draw(crisp)
        crisp_draw.rectangle(text_probe.box, fill=(32, 32, 32))
        left, top, right, _bottom = text_probe.box
        crisp_draw.line((left + 8, top + 8, right - 8, top + 8), fill=(255, 255, 255))
        crisp_draw.line((left + 8, top + 18, right - 8, top + 18), fill=(255, 255, 255))
        crisp_path = self.root / "crisp-text.png"
        crisp.save(crisp_path, format="PNG")
        *_, crisp_png, _metrics = canonicalize_png(
            crisp_path, expected_size=contract.gui_text_reference_size
        )
        validate_contract_probes(crisp_png, capture, "crisp text calibration canary")

        blurred = crisp.filter(ImageFilter.GaussianBlur(radius=6))
        blurred_path = self.root / "blurred-text.png"
        blurred.save(blurred_path, format="PNG")
        *_, blurred_png, _metrics = canonicalize_png(
            blurred_path, expected_size=contract.gui_text_reference_size
        )
        with self.assertRaisesRegex(VisualEvidenceError, "required-gui-text"):
            validate_contract_probes(blurred_png, capture, "blurred text calibration canary")


class VisualReviewOutputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        shared = tempfile.TemporaryDirectory(prefix="blockpops-review-output-")
        cls.shared = shared
        root = Path(shared.name)
        cls.reference_matrix_path = write_matrix_fixture(
            root, canonical_integration_matrix(BRANCH_MATRIX)
        )
        candidate_input = _source(
            root,
            name="candidate",
            nodes=ACTIVE_NODES,
            artifact_id=61,
            source_head_branch="candidate",
            base_branch=ACTIVE_BRANCH,
            event="pull_request_target",
            metadata="candidate",
        )
        reference_input = _source(
            root,
            name="reference",
            nodes=[REFERENCE_NODE],
            artifact_id=62,
            source_head_branch=CANONICAL_BRANCH,
            base_branch=CANONICAL_BRANCH,
            event="push",
            metadata="reference",
            matrix_path=cls.reference_matrix_path,
        )
        candidate = load_archived_evidence(
            archive=candidate_input[0],
            attestation_path=candidate_input[1],
            expectation=candidate_input[2],
            matrix_path=MATRIX_PATH,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "candidate-extracted",
        )
        reference = load_archived_evidence(
            archive=reference_input[0],
            attestation_path=reference_input[1],
            expectation=reference_input[2],
            matrix_path=cls.reference_matrix_path,
            contract_path=CONTRACT_PATH,
            extraction_destination=root / "reference-extracted",
        )
        cls.capsule = root / "capsule"
        write_capsule(cls.capsule, candidate, reference)
        _manifest, cls.pairs = validate_capsule(cls.capsule)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.cleanup()

    def _valid(self) -> dict[str, object]:
        changed = [
            pair for pair in self.pairs if pair["triage"]["byte_identical"] is False
        ]
        defect_label = changed[-1]["label"] if changed else None
        verdicts = []
        for pair in reversed(self.pairs):
            identical = pair["triage"]["byte_identical"] is True
            defect = pair["label"] == defect_label
            if identical:
                findings = []
                visible = "Candidate and canonical reference are byte-identical."
                route = "identical"
            elif defect:
                findings = [
                    {
                        "category": "clipping",
                        "severity": "defect",
                        "detail": "The settings label is visibly clipped.",
                    }
                ]
                visible = "The settings label is visibly clipped."
                route = "fable"
            else:
                findings = [
                    {
                        "category": "rendering",
                        "severity": "note",
                        "detail": "Harmless antialiasing differs from the reference.",
                    }
                ]
                visible = "The expected packaged UI state is visible."
                route = "sonnet"
            verdicts.append(
                {
                    "label": pair["label"],
                    "capture_id": pair["capture_id"],
                    "route": route,
                    "matches_expectation": not defect,
                    "semantic_regression": defect,
                    "visible": visible,
                    "findings": findings,
                }
            )
        identical_count = len(self.pairs) - len(changed)
        escalated_count = int(defect_label is not None)
        sonnet_calls = (len(changed) + 4) // 5
        fable_calls = (escalated_count + 3) // 4
        calls = sonnet_calls + fable_calls
        sonnet_usage = {
            "input_tokens": 100 if sonnet_calls else 0,
            "output_tokens": 20 if sonnet_calls else 0,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
        }
        fable_usage = {
            "input_tokens": 40 if fable_calls else 0,
            "output_tokens": 10 if fable_calls else 0,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0,
        }
        cost = 12_500 * calls
        return {
            "schema_version": 2,
            "advisory": True,
            "telemetry": {
                "provider": "anthropic",
                "auth_mode": "claude-code-oauth",
                "triage_model": "claude-sonnet-5",
                "verification_model": "claude-opus-5",
                "client_sha256": "a" * 64,
                "sonnet_prompt_sha256": "b" * 64,
                "fable_prompt_sha256": "c" * 64,
                "pair_count": len(self.pairs),
                "identical_pairs": identical_count,
                "triaged_pairs": len(changed),
                "escalated_pairs": escalated_count,
                "sonnet_calls": sonnet_calls,
                "fable_calls": fable_calls,
                "provider_attempts": calls,
                "retries": 0,
                "sonnet_usage": sonnet_usage,
                "fable_usage": fable_usage,
                "estimated_cost_micro_usd": cost,
                "duration_ms": 1234,
                "session_ids": [
                    f"00000000-0000-4000-8000-{index:012x}" for index in range(calls)
                ],
            },
            "verdicts": verdicts,
        }

    def test_valid_output_is_normalized_to_capsule_order_and_remains_advisory(self) -> None:
        normalized = validate_review_output(self.pairs, self._valid())
        self.assertEqual(
            [pair["label"] for pair in self.pairs],
            [verdict["label"] for verdict in normalized["verdicts"]],
        )
        summary = advisory_markdown(normalized)
        self.assertIn("advisory regression", summary)
        self.assertIn("Build and packaged E2E remain authoritative", summary)
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "normalized.json"
            write_normalized_review(destination, normalized, pairs=self.pairs)
            self.assertEqual(
                normalized, json.loads(destination.read_text(encoding="utf-8"))
            )

    def test_output_mutations_fail_closed(self) -> None:
        for mutation in (
            "stale-schema",
            "unknown-root",
            "missing",
            "duplicate",
            "identity",
            "route",
            "boolean",
            "defect-without-finding",
            "note-marked-defect",
            "unknown-category",
            "cost",
            "session",
            "control",
            "surrogate",
        ):
            with self.subTest(mutation=mutation):
                report = self._valid()
                verdicts = report["verdicts"]
                defect = next(
                    verdict
                    for verdict in verdicts
                    if verdict["semantic_regression"] is True
                )
                if mutation == "stale-schema":
                    report["schema_version"] = 1
                elif mutation == "unknown-root":
                    report["model"] = "secret-bearing detail"
                elif mutation == "missing":
                    verdicts.pop()
                elif mutation == "duplicate":
                    verdicts[-1] = copy.deepcopy(verdicts[0])
                elif mutation == "identity":
                    verdicts[0]["capture_id"] = "wrong.capture.id"
                elif mutation == "route":
                    verdicts[0]["route"] = "whole-pixel"
                elif mutation == "boolean":
                    defect["matches_expectation"] = True
                elif mutation == "defect-without-finding":
                    defect["findings"] = []
                elif mutation == "note-marked-defect":
                    defect["findings"][0]["severity"] = "note"
                elif mutation == "unknown-category":
                    defect["findings"][0]["category"] = "pixels-differ"
                elif mutation == "cost":
                    report["telemetry"]["estimated_cost_micro_usd"] = -1
                elif mutation == "session":
                    report["telemetry"]["session_ids"][0] = "req_not_a_claude_code_session"
                elif mutation == "control":
                    verdicts[0]["visible"] = "bad\x00text"
                else:
                    verdicts[0]["visible"] = "bad\ud800text"
                with self.assertRaises(VisualReviewOutputError):
                    validate_review_output(self.pairs, report)

    def test_output_reader_rejects_duplicate_json_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            duplicate = root / "duplicate.json"
            duplicate.write_text(
                '{"schema_version":2,"schema_version":2,"advisory":true,"telemetry":{},"verdicts":[]}',
                encoding="utf-8",
            )
            with self.assertRaises(VisualReviewOutputError):
                read_and_validate_review(self.capsule, duplicate)
            link = root / "linked.json"
            link.symlink_to(duplicate)
            with self.assertRaises(VisualReviewOutputError):
                read_and_validate_review(self.capsule, link)


if __name__ == "__main__":
    unittest.main()
