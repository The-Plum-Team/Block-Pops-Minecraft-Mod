"""The in-world scenario's collection walls follow the packaged collections exactly."""

from __future__ import annotations

import json
import re
import tempfile
import unittest
from pathlib import Path

from e2e import packaged_runtime as runtime
from e2e.scenario_contract import load_contract

ROOT = Path(__file__).resolve().parents[1]
COLLECTIONS = ROOT / runtime.COLLECTIONS_PATH
CONTRACT = ROOT / "e2e" / "scenario-contract.json"
SCENARIO = ROOT / "common/src/e2e/java/com/theplumteam/e2e/scenario/InWorldScenario.java"
SETBLOCK = re.compile(
    r'^setblock (-?\d+) (-?\d+) (-?\d+) blockpops:box_block\[facing=south\]'
    r'\{CollectionId:"([a-z0-9_]+)",FigureId:"([a-z0-9_]+)"\}$'
)


class InWorldShowcaseTests(unittest.TestCase):
    def test_contract_photographs_one_wall_per_packaged_collection_in_id_order(self) -> None:
        contract = load_contract(CONTRACT)
        steps = contract.expected_capture_steps("in-world", "client_a")
        walls = [step.removeprefix("collection_") for step in steps if step.startswith("collection_")]
        self.assertEqual([collection for collection, _ in runtime.showcase_collections(COLLECTIONS)], walls)

    def test_every_figure_stands_in_its_own_box_on_its_wall(self) -> None:
        collections = runtime.showcase_collections(COLLECTIONS)
        lines = runtime.showcase_function(collections).splitlines()
        self.assertTrue(lines[0].startswith("#"))
        self.assertTrue(lines[1].startswith("forceload add "))
        placed = [SETBLOCK.fullmatch(line) for line in lines[2:]]
        self.assertTrue(all(placed), [line for line, match in zip(lines[2:], placed) if not match])
        expected = []
        for index, (collection, figures) in enumerate(collections):
            origin = runtime.SHOWCASE_WALL_ORIGIN_X + index * runtime.SHOWCASE_WALL_SPACING
            for position, figure in enumerate(figures):
                expected.append((
                    str(origin + position % runtime.SHOWCASE_COLUMNS),
                    str(runtime.SHOWCASE_GROUND_Y + position // runtime.SHOWCASE_COLUMNS),
                    str(runtime.SHOWCASE_WALL_Z),
                    collection,
                    figure,
                ))
        self.assertEqual(expected, [match.groups() for match in placed])
        positions = [groups[:3] for groups in expected]
        self.assertEqual(len(positions), len(set(positions)))
        self.assertEqual(
            sum(len(json.loads(path.read_text()).get("figures", [])) for path in COLLECTIONS.glob("*.json")),
            len(expected),
        )

    def test_the_harness_walks_to_the_layout_the_data_pack_builds(self) -> None:
        source = SCENARIO.read_text(encoding="utf-8")
        for name, value in (
            ("WALL_COLUMNS", runtime.SHOWCASE_COLUMNS),
            ("WALL_ORIGIN_X", runtime.SHOWCASE_WALL_ORIGIN_X),
            ("WALL_SPACING", runtime.SHOWCASE_WALL_SPACING),
            ("WALL_Z", runtime.SHOWCASE_WALL_Z),
        ):
            self.assertIn(f"private static final int {name} = {value};", source)
        self.assertIn(f"private static final double GROUND_Y = {float(runtime.SHOWCASE_GROUND_Y)};", source)

    def test_the_server_files_carry_the_walls_for_both_function_layouts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            runtime.write_server_files(server, 25565, ROOT / "e2e" / "server-template", "1.20.1")
            datapack = server / "world" / "datapacks" / "blockpops_e2e_time" / "data" / "blockpops_e2e"
            expected = runtime.showcase_function(runtime.showcase_collections(COLLECTIONS))
            for functions in ("function", "functions"):
                self.assertEqual(expected, (datapack / functions / "showcase.mcfunction").read_text())
                load = (datapack / functions / "load.mcfunction").read_text()
                self.assertIn("function blockpops_e2e:showcase", load)

    def test_the_server_reads_the_e2e_switch(self) -> None:
        self.assertEqual("-Dblockpops.e2e.enabled=true", runtime.SERVER_E2E_PROPERTY)
        source = (ROOT / "e2e" / "packaged_runtime.py").read_text(encoding="utf-8")
        self.assertIn('SERVER_E2E_PROPERTY, "-jar"', source)
        self.assertIn('f"-Xms512M\\n-Xmx1024M\\n{SERVER_E2E_PROPERTY}\\n"', source)


if __name__ == "__main__":
    unittest.main()
