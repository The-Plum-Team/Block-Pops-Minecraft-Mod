"""Opt-in actual ItemStack/component codec probe; no world or mod gameplay is run."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam")
SOURCES = tuple(PACKAGE / name for name in ("block/BoxBlock.java", "block/FigureBlock.java",
    "item/GeoBlockItem.java", "item/BoxBlockItem.java", "item/BlockEntityItemData.java"))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_GAME_CLASSPATHS")
PROBE = """
import com.theplumteam.item.BlockEntityItemData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
*///? }
public class DataProbe {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] arguments) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemStack stack = new ItemStack(Items.CHEST, 7);
        require(BlockEntityItemData.read(stack) == null);
        CompoundTag unrelated = new CompoundTag(); unrelated.putString("keep", "value");
        //? if >=1.21 {
        /*stack.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        *///? } else {
        stack.getOrCreateTag().put("unrelated", unrelated);
        //? }
        CompoundTag input = new CompoundTag();
        input.putString("CollectionId", "world_players"); input.putString("FigureId", "figure");
        input.putString("QuickSkinId", ""); input.putString("SkinSnapshot", "{fixture}");
        input.putBoolean("IsOpen", false); input.putBoolean("IsFigureExtracted", false);
        input.putInt("PoseIndex", 3); input.putDouble("FigureScale", 1.25);
        CompoundTag nested = new CompoundTag(); nested.putInt("value", 7); input.put("extra", nested);
        CompoundTag original = input.copy();
        BlockEntityItemData.write(stack, input, "blockpops:box_block");
        require(input.equals(original));
        CompoundTag expected = original.copy();
        //? if >=1.21 {
        /*expected.putString("id", "blockpops:box_block");
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        var codec = DataComponents.BLOCK_ENTITY_DATA.codecOrThrow();
        var encoded = codec.encodeStart(NbtOps.INSTANCE, data).result().orElseThrow();
        require(codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow().copyTag().equals(expected));
        require(codec.encodeStart(NbtOps.INSTANCE, CustomData.of(original)).error().isPresent());
        CompoundTag wrongId = original.copy(); wrongId.putInt("id", 12);
        require(codec.encodeStart(NbtOps.INSTANCE, CustomData.of(wrongId)).error().isPresent());
        *///? } else {
        ItemStack decoded = ItemStack.of(stack.save(new CompoundTag()));
        require(BlockEntityItemData.read(decoded).equals(expected));
        //? }
        require(BlockEntityItemData.read(stack).equals(expected));
        require(stack.getCount() == 7 && stack.getItem() == Items.CHEST);
        input.getCompound("extra").putInt("value", 8);
        CompoundTag read = BlockEntityItemData.read(stack); read.putString("FigureId", "mutated");
        //? if >=1.21 {
        /*require(BlockEntityItemData.read(stack).equals(expected));
        require(stack.get(DataComponents.CUSTOM_DATA).copyTag().equals(unrelated));
        *///? } else {
        require(BlockEntityItemData.read(stack).getCompound("extra").getInt("value") == 8);
        require(BlockEntityItemData.read(stack).getString("FigureId").equals("mutated"));
        require(stack.getTagElement("unrelated").equals(unrelated));
        //? }
        CompoundTag replacement = new CompoundTag(); replacement.putString("FigureId", "");
        BlockEntityItemData.write(stack, replacement, "blockpops:figure_block");
        CompoundTag replaced = BlockEntityItemData.read(stack);
        require(replaced.getString("FigureId").isEmpty() && replaced.contains("FigureId"));
        require(!replaced.contains("CollectionId") && !replaced.contains("extra"));
        //? if >=1.21 {
        /*require(replaced.getString("id").equals("blockpops:figure_block"));
        require(replaced.getAllKeys().size() == 2 && !replacement.contains("id"));
        *///? } else {
        require(replaced.getAllKeys().size() == 1);
        //? }
        BlockEntityItemData.write(stack, new CompoundTag(), "blockpops:box_block");
        require(BlockEntityItemData.read(stack) != null);
        //? if >=1.21 {
        /*require(BlockEntityItemData.read(stack).getAllKeys().size() == 1);
        *///? } else {
        require(BlockEntityItemData.read(stack).isEmpty());
        //? }
        System.out.println("real ItemStack: nullable, keys, replacement, ownership, unrelated data and codec preserved");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/game classpaths required")
class BlockEntityItemDataApiTests(unittest.TestCase):
    def test_real_item_data_and_complete_source_preprocessing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_GAME_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                      for paths in classpaths.values() for path in paths}
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(raw)
            (root / "common/src/main/java/DataProbe.java").write_text(PROBE)
            (root / "gradle").mkdir()
            metadata = (ROOT / "gradle/verification-metadata.xml").read_bytes()
            (root / "gradle/verification-metadata.xml").write_bytes(metadata)
            (logs / "verification-metadata-used.xml").write_bytes(metadata)
            (root / "settings.gradle").write_text("""
pluginManagement { repositories { maven {
    url = 'https://maven.kikugie.dev/releases'
    mavenContent { releasesOnly() }
    content {
        includeModule('dev.kikugie.stonecutter', 'dev.kikugie.stonecutter.gradle.plugin')
        includeModule('dev.kikugie', 'stonecutter')
    }
} } }
plugins { id 'dev.kikugie.stonecutter' version '0.7.11' }
rootProject.name = 'item-block-data-api-probe'
stonecutter.kotlinController.set(false)
stonecutter.centralScript.set('build.gradle')
stonecutter.create(rootProject, { tree ->
    tree.branch('common') { branch -> branch.version('1.20.1'); branch.version('1.21.1') }
} as org.gradle.api.Action)
""")
            (root / "stonecutter.gradle").write_text("plugins { id 'dev.kikugie.stonecutter' }\nstonecutter.active(null)\n")
            (root / "common/build.gradle").write_text("plugins { id 'java' }\n")
            env = {key: value for key, value in os.environ.items() if key not in
                   {"JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
                   and not key.startswith("ORG_GRADLE_PROJECT_")}
            env["JAVA_HOME"] = str(homes[21])
            commands = {}

            def invoke(name, command):
                commands[name] = [str(value) for value in command]
                child = subprocess.Popen(commands[name], cwd=root, env=env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
                try:
                    output, _ = child.communicate(timeout=90)
                finally:
                    _finish_owned_group(child)
                (logs / f"{name}.log").write_text(output)
                return child.returncode, output

            code, output = invoke("preprocess", [os.environ["BLOCKPOPS_TEST_GRADLE"], "--offline", "--no-daemon",
                "--no-parallel", "--max-workers=1", "--no-configuration-cache", "--no-build-cache",
                "--dependency-verification", "strict", "--gradle-user-home", os.environ["BLOCKPOPS_TEST_GRADLE_HOME"],
                f"-Dorg.gradle.java.home={homes[21]}", ":common:1.20.1:stonecutterGenerate", ":common:1.21.1:stonecutterGenerate"])
            self.assertEqual(0, code, output)
            observations = {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = [generated_root / path.relative_to("common/src/main/java") for path in SOURCES]
                for path in generated:
                    self.assertTrue(path.is_file())
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                probe = generated_root / "DataProbe.java"
                (logs / f"{version}-DataProbe.java").write_bytes(probe.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-helper-probe-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, generated[-1], probe])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/item/BlockEntityItemData.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, output = invoke(f"real-item-data-{version}", [homes[major] / "bin/java", "-Djava.awt.headless=true",
                    "-cp", str(destination) + os.pathsep + cp, "DataProbe"])
                self.assertEqual(0, code, output); self.assertIn("real ItemStack:", output)
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "helper_class_major": major + 44, "helper_class_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(),
                    "runtime_result": output.strip()}
                other = "1.21.1" if major == 17 else "1.20.1"
                code, output = invoke(f"reject-{version}-on-{other}", [homes[21] / "bin/javac", "-proc:none", "--release", "21",
                    "-cp", os.pathsep.join(map(str, classpaths[other])), "-d", root / f"wrong-{version}", generated[-1]])
                self.assertNotEqual(0, code); self.assertRegex(output, r"cannot find symbol|package .* does not exist")
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            for path, raw in sources.items():
                self.assertEqual(raw, (ROOT / path).read_bytes()); self.assertEqual(raw, (root / path).read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "item-block-data-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "complete_modern_consumers_compiled": False,
                "vanilla_bootstrap": True, "world_or_gameplay_executed": False}, indent=2) + "\n")
