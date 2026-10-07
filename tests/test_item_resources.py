"""Modern model linkage, old resource compatibility and generated-tree safety."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.release.item_resources import generate, resource_plan


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/resources"
RECIPE = "data/blockpops/recipe/claw_machine_block.json"


class ItemResourceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.output = self.root / "generated"
        self.source = self.root / "source"
        self.items = self.source / "assets/blockpops/models/item"
        self.items.mkdir(parents=True)
        (self.source / "assets/blockpops/models/block").mkdir()
        self.item = self.items / "box_block_purple.json"
        self.item.write_text('{"parent":"builtin/entity","gui_light":"front"}')

    def test_every_authored_entity_item_links_a_registered_renderer_and_resolvable_base(self):
        authored = {path.name for path in (SOURCE / "assets/blockpops/models/item").iterdir()
                    if json.loads(path.read_text()).get("parent") == "builtin/entity"}
        for version in ("1.21.4", "1.21.11", "26.1.2", "26.2", "26.3"):
            with self.subTest(version=version):
                plan = resource_plan(SOURCE, version)
                definitions = {path.rsplit("/", 1)[-1]: json.loads(raw)["model"]
                               for path, raw in plan.items() if "/items/" in path}
                self.assertEqual(authored, set(definitions))
                self.assertEqual({"blockpops:box_block", "blockpops:figure_block",
                                  "blockpops:claw_machine_block"},
                                 {value["model"]["type"] for value in definitions.values()})
                for name, definition in definitions.items():
                    self.assertEqual("minecraft:special", definition["type"])
                    self.assertEqual("blockpops:item/" + name[:-5], definition["base"])
                    model = json.loads(plan[f"assets/blockpops/models/item/{name}"])
                    self.assertEqual("minecraft:item/template_shulker_box", model["parent"])
                    self.assertEqual("front", model["gui_light"])
                self.assertTrue(all(b'builtin/entity' not in raw for raw in plan.values()))

    def test_legacy_versions_produce_no_overrides_and_leave_authored_bytes_untouched(self):
        before = {path: path.read_bytes() for path in (SOURCE / "assets/blockpops/models/item").iterdir()}
        self.assertEqual({}, resource_plan(SOURCE, "1.20.1"))
        for version in ("1.21.1", "1.21.3"):
            self.assertEqual({RECIPE}, set(resource_plan(SOURCE, version)))
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_the_claw_machine_recipe_follows_each_version_s_directory_and_format(self):
        authored = json.loads((SOURCE / "data/blockpops/recipes/claw_machine_block.json").read_text())
        self.assertEqual({"item": "blockpops:claw_machine_block"}, authored["result"])
        self.assertEqual({"item": "minecraft:chain"}, authored["key"]["C"])
        for version, chain in (("1.21.1", {"item": "minecraft:chain"}), ("1.21.3", "minecraft:chain"),
                               ("1.21.8", "minecraft:chain"), ("1.21.9", "minecraft:iron_chain"),
                               ("26.1.2", "minecraft:iron_chain"), ("26.3", "minecraft:iron_chain")):
            with self.subTest(version=version):
                recipe = json.loads(resource_plan(SOURCE, version)[RECIPE])
                self.assertEqual("minecraft:crafting_shaped", recipe["type"])
                self.assertEqual(authored["pattern"], recipe["pattern"])
                self.assertEqual({"id": "blockpops:claw_machine_block", "count": 1}, recipe["result"])
                self.assertEqual(set(authored["key"]), set(recipe["key"]))
                self.assertEqual(chain, recipe["key"]["C"])
                self.assertEqual({"item": "minecraft:glass"} if version == "1.21.1" else "minecraft:glass",
                                 recipe["key"]["G"])

    def test_tags_counts_and_unsupported_recipes(self):
        recipes = self.source / "data/blockpops/recipes"
        recipes.mkdir(parents=True)
        recipe = recipes / "sample.json"
        recipe.write_text(json.dumps({
            "type": "minecraft:crafting_shaped", "pattern": ["PP"],
            "key": {"P": {"tag": "minecraft:planks"}}, "result": {"item": "blockpops:sample", "count": 2}}))
        path = "data/blockpops/recipe/sample.json"
        self.assertEqual({"tag": "minecraft:planks"}, json.loads(resource_plan(self.source, "1.21.1")[path])["key"]["P"])
        modern = json.loads(resource_plan(self.source, "26.2")[path])
        self.assertEqual("#minecraft:planks", modern["key"]["P"])
        self.assertEqual({"id": "blockpops:sample", "count": 2}, modern["result"])
        generate(self.source, self.output, "26.2")
        self.assertTrue((self.output / path).is_file())
        generate(self.source, self.output, "1.20.1")
        self.assertFalse((self.output / path).exists())
        for broken in ({"type": "minecraft:crafting_shapeless", "pattern": [], "key": {}, "result": {"item": "a:b"}},
                       {"type": "minecraft:crafting_shaped", "pattern": ["P"],
                        "key": {"P": [{"item": "a:b"}]}, "result": {"item": "a:b"}},
                       {"type": "minecraft:crafting_shaped", "pattern": ["P"],
                        "key": {"P": {"item": "a:b"}}, "result": {"item": "a:b", "nbt": "{}"}}):
            recipe.write_text(json.dumps(broken))
            with self.assertRaises(ValueError):
                resource_plan(self.source, "26.2")

    def test_rerun_removes_stale_generated_resources_without_touching_authored_models(self):
        generate(self.source, self.output, "26.2")
        definition = self.output / "assets/blockpops/items/box_block_purple.json"
        self.assertEqual("blockpops:box_block", json.loads(definition.read_text())["model"]["model"]["type"])
        generate(self.source, self.output, "1.20.1")
        self.assertFalse(definition.exists())
        self.assertTrue(self.item.exists())

    def test_unknown_renderer_duplicate_json_and_model_symlink_fail_closed(self):
        self.item.rename(self.items / "unknown.json")
        with self.assertRaisesRegex(ValueError, "no registered"):
            resource_plan(self.source, "26.2")
        (self.items / "unknown.json").unlink()
        self.item.write_text('{"parent":"builtin/entity","parent":"x"}')
        with self.assertRaisesRegex(ValueError, "duplicate"):
            resource_plan(self.source, "26.2")
        self.item.unlink()
        self.item.symlink_to(SOURCE / "assets/blockpops/models/item/box_block.json")
        with self.assertRaises(OSError):
            resource_plan(self.source, "26.2")

    def test_directory_and_output_links_or_hardlinks_cannot_modify_an_outside_file(self):
        outside = self.root / "outside.json"
        outside.write_bytes(b"canary")
        generate(self.source, self.output, "26.2")
        generated = self.output / "assets/blockpops/items/box_block_purple.json"
        generated.unlink()
        generated.symlink_to(outside)
        with self.assertRaises(OSError):
            generate(self.source, self.output, "26.2")
        generated.unlink()
        generated.hardlink_to(outside)
        with self.assertRaisesRegex(ValueError, "owned regular"):
            generate(self.source, self.output, "26.2")
        self.assertEqual(b"canary", outside.read_bytes())
        alias = self.root / "alias"
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(OSError):
            resource_plan(alias, "26.2")

    def test_output_fifo_fails_without_waiting_for_a_reader(self):
        generate(self.source, self.output, "26.2")
        generated = self.output / "assets/blockpops/items/box_block_purple.json"
        generated.unlink()
        os.mkfifo(generated)
        result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/release/item_resources.py"),
            "--source", str(self.source), "--output", str(self.output), "--minecraft", "26.2"],
            capture_output=True, timeout=10)
        self.assertEqual(1, result.returncode)
        self.assertIn(b"item resources:", result.stderr)
