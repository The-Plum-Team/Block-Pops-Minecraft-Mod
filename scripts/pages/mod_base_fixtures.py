"""Test-only conformance fixtures of the Block Pops adapter (never loaded by the Pages host).

``synthesize`` writes the packaged output the adapter's ``collect`` reads, in exactly the shape the
packaged harness and ``scripts/ci/e2e_fanin.py`` produce: one
``profiles/<node>--<minecraft>--<scenario>/result.json`` per expectation lane (the schema
``e2e.packaged_runtime.validate_packaged_result`` accepts, with one embedded role report whose
steps are every contract step) and ``<role>/screenshots/<capture_id>.png`` per capture.

Every screenshot starts from the kit's deterministic pattern (``image_factory``), which passes the
generic blank checks, and then satisfies the capture's own contract probes the way a real frame
does: the ``OPAQUE_STARS`` background region is dark and every required GUI text box holds bright
glyph pixels. Its recorded ``pixel_validation`` is measured by ``e2e.packaged_runtime`` itself, so
the conformance run proves that the kit's independent metrics agree with Block Pops' own.
"""

from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
from typing import Any

from e2e.scenario_contract import OpaqueStarsProbe, RequiredGuiTextProbe
from scripts.pages.mod_base_adapter import profile_path, read_contract

#: The fill of a satisfied ``OPAQUE_STARS`` background and of required GUI glyph pixels.
DARK_BACKGROUND = (3, 4, 8)
BRIGHT_GLYPHS = (250, 250, 250)
PORT = 25565
ELAPSED_SECONDS = 42.5


def _digest(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def frame(pattern: bytes, probes: tuple[Any, ...]) -> bytes:
    """The pattern PNG ``pattern`` with every probe of its capture satisfied."""

    from PIL import Image, ImageDraw

    with Image.open(io.BytesIO(pattern)) as image:
        canvas = image.convert("RGB")
    width, height = canvas.size
    draw = ImageDraw.Draw(canvas)
    for probe in probes:
        if isinstance(probe, OpaqueStarsProbe):
            left, top, right, bottom = probe.region
            box = (int(left * width), int(top * height), int(right * width), int(bottom * height))
            draw.rectangle((box[0], box[1], box[2] - 1, box[3] - 1), fill=DARK_BACKGROUND)
    for probe in probes:
        if isinstance(probe, RequiredGuiTextProbe):
            left, top, right, bottom = probe.box
            draw.rectangle((left, top, right - 1, bottom - 1), fill=BRIGHT_GLYPHS)
    output = io.BytesIO()
    canvas.save(output, format="PNG", optimize=False, compress_level=6)
    return output.getvalue()


def synthesize(ctx: Any, target: dict[str, Any], expectation: dict[str, Any], out_root: str,
               image_factory: Any) -> None:
    """Write a passing packaged-output tree for every lane of ``expectation`` under ``out_root``."""

    from e2e import packaged_runtime

    contract, _ = read_contract(ctx, target["subject"]["commit"])
    width, height = expectation["image_policy"]["source_size"]
    root = Path(out_root)
    for index, lane in enumerate(expectation["lanes"]):
        profile = profile_path(lane)
        node = lane["artifact_node"]
        reports = {}
        for role in lane["roles"]:
            screenshots = root / profile / role / "screenshots"
            steps, metrics, paths, ordinal = [], {}, {}, 0
            for step in contract.role(lane["scenario"], role).steps:
                capture = step.capture
                screenshot = None
                if capture is not None:
                    screenshot = f"{capture.capture_id}.png"
                    screenshots.mkdir(parents=True, exist_ok=True)
                    path = screenshots / screenshot
                    # Consecutive seeds rotate the palette, so every compared pair differs.
                    path.write_bytes(frame(image_factory(width, height, 5 * index + ordinal), capture.probes))
                    metrics[step.id] = packaged_runtime.inspect_screenshot_for_step(
                        path, lane["scenario"], role, step.id)
                    paths[step.id] = path
                    ordinal += 1
                steps.append({"id": step.id, "status": "pass", "message": f"{step.id} passed on {node}",
                              "capture_id": None if capture is None else capture.capture_id,
                              "screenshot": screenshot})
            comparisons = {
                f"{comparison.first_step}->{comparison.second_step}": packaged_runtime.compare_screenshots(
                    paths[comparison.first_step], paths[comparison.second_step],
                    comparison.minimum_changed_fraction, comparison.region)
                for comparison in contract.comparisons_for(lane["scenario"], role)
            }
            reports[role] = {"schema_version": 1, "minecraft": lane["minecraft"], "role": role,
                             "scenario": lane["scenario"], "contract_sha256": expectation["contract_sha256"],
                             "status": "pass", "steps": steps,
                             "pixel_validation": {"screenshots": metrics, "comparisons": comparisons}}
        production = _digest(f"production:{node}")
        result = {"schema_version": 1, "artifact_node": node, "minecraft": lane["minecraft"],
                  "loader": lane["loader"], "scenario": lane["scenario"],
                  "contract_sha256": expectation["contract_sha256"], "production_jar_sha256": production,
                  "harness_jar_sha256": _digest(f"harness:{node}"), "port": PORT, "status": "pass",
                  "profile": profile, "installed_blockpops": [{"path": f"mods/blockpops-{node}.jar",
                                                               "sha256": production}],
                  "reports": reports, "elapsed_s": ELAPSED_SECONDS, "error": None}
        directory = root / profile
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
