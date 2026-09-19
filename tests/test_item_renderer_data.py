"""Opt-in complete renderer compilation and native item-data presence semantics."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group
from tests.test_block_entity_persistence import java_tokens

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam")
NAMES = ("Box", "Figure", "ClawMachine")
CLASSES = tuple(f"blockentity/{name}BlockEntity" for name in NAMES) + tuple(
    f"client/renderer/{name}BlockItemRenderer" for name in NAMES) + ("item/GeoBlockItem",)
HELPER = "item/BlockEntityItemData"
SOURCES = tuple(PACKAGE / (name + ".java") for name in (*CLASSES, HELPER))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS",
    "BLOCKPOPS_TEST_DECLARATIONS", "BLOCKPOPS_TEST_RENDER_BASELINES")
PROBE = """
import com.theplumteam.item.BlockEntityItemData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21 {
/*import net.minecraft.core.component.DataComponents;
*///? }
public class RenderDataProbe {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static boolean unmodified(ItemStack stack) {
        //? if >=1.21 {
        /*return stack.getComponentsPatch().isEmpty();
        *///? } else {
        return !stack.hasTag();
        //? }
    }
    public static void main(String[] arguments) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ItemStack stack = new ItemStack(Items.CHEST);
        require(unmodified(stack) && BlockEntityItemData.read(stack) == null);
        // Defaults are not an explicit item-data patch.
        //? if >=1.21 {
        /*require(!stack.getComponents().isEmpty());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        *///? } else {
        stack.setHoverName(Component.literal("named"));
        //? }
        require(!unmodified(stack) && BlockEntityItemData.read(stack) == null);
        CompoundTag tag = new CompoundTag(); tag.putBoolean("IsOpen", true);
        BlockEntityItemData.write(stack, tag, "blockpops:box_block");
        require(!unmodified(stack) && BlockEntityItemData.read(stack).getBoolean("IsOpen"));
        BlockEntityItemData.write(stack, new CompoundTag(), "blockpops:box_block");
        require(BlockEntityItemData.read(stack) != null && !BlockEntityItemData.read(stack).getBoolean("IsOpen"));
        System.out.println("real item states: unmodified reset, unrelated data no-op, present data load");
    }
}
"""


def method_body(source, signature):
    start = source.index("{", source.index(signature)) + 1
    depth = 1
    for index in range(start, len(source)):
        depth += (source[index] == "{") - (source[index] == "}")
        if depth == 0:
            return source[start:index]
    raise AssertionError("method body did not close")


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/baseline inputs required")
class ItemRendererDataApiTests(unittest.TestCase):
    def test_complete_renderers_preserve_legacy_and_read_all_fields_without_a_level(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            baselines = {name: Path(path).resolve() for name, path in
                         json.loads(os.environ["BLOCKPOPS_TEST_RENDER_BASELINES"]).items()}
            self.assertEqual(set(CLASSES), set(baselines))
            declarations = Path(os.environ["BLOCKPOPS_TEST_DECLARATIONS"]).resolve()
            symbols = sorted(declarations.rglob("*.class")); self.assertTrue(symbols)
            forbidden = {name.rsplit("/", 1)[-1] for name in (*CLASSES, HELPER)}
            self.assertFalse({path.stem.split("$")[0] for path in symbols} & forbidden)
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                      [*baselines.values(), *symbols, *(p for paths in classpaths.values() for p in paths)]}
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True); (root / path).write_bytes(raw)
            (root / "common/src/main/java/RenderDataProbe.java").write_text(PROBE)
            copied_symbols = root / "symbols"
            for path in symbols:
                target = copied_symbols / path.relative_to(declarations)
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(path.read_bytes())
            (root / "gradle").mkdir()
            metadata = (ROOT / "gradle/verification-metadata.xml").read_bytes()
            (root / "gradle/verification-metadata.xml").write_bytes(metadata)
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
rootProject.name = 'item-renderer-data-api-probe'
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
            env["JAVA_HOME"] = str(homes[21]); commands = {}

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
            observations, legacy_bodies = {}, {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = [generated_root / path.relative_to("common/src/main/java") for path in SOURCES]
                for name, path in zip((*CLASSES, HELPER), generated, strict=True):
                    self.assertTrue(path.is_file()); (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if major == 17 and name in baselines:
                        self.assertEqual(java_tokens(baselines[name].read_text()), java_tokens(path.read_text()), name)
                    if name.startswith("blockentity/"):
                        signature = "public void load(" if major == 17 else "public void loadForItemRendering("
                        body = method_body(path.read_text(), signature)
                        if major == 17: legacy_bodies[name] = java_tokens(body.replace("super.load(tag);", ""))
                        else: self.assertEqual(legacy_bodies[name], java_tokens(body), name)
                probe = generated_root / "RenderDataProbe.java"
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [copied_symbols, *classpaths[version]]))
                code, output = invoke(f"compile-complete-classes-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, *generated, probe])
                self.assertEqual(0, code, output)
                bytecodes = {}
                for name in (*CLASSES, HELPER):
                    bytecode = destination / f"com/theplumteam/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    bytecodes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                    code, output = invoke(f"bytecode-{version}-{name.rsplit('/', 1)[-1]}", [homes[major] / "bin/javap", "-p", "-c",
                        "-classpath", destination, "com.theplumteam." + name.replace("/", ".")])
                    self.assertEqual(0, code, output)
                    if major == 21 and name.startswith("client/"):
                        self.assertIn("BlockEntityItemData.read:", output)
                        self.assertEqual(2 if name.endswith("/BoxBlockItemRenderer") else 1, output.count("loadForItemRendering:"))
                        self.assertEqual(name.endswith("/BoxBlockItemRenderer"), "getComponentsPatch:" in output)
                        self.assertRegex(output, r"iconst_1\s+\d+: invokeinterface[^\n]+getGameTimeDeltaPartialTick:\(Z\)F")
                        self.assertNotIn("getFrameTime:", output)
                    elif major == 21 and name.startswith("blockentity/"):
                        section = output.split("protected void loadAdditional(", 1)[1].split("public void loadForItemRendering(", 1)[0]
                        self.assertIn("BlockEntity.loadAdditional:", section)
                        self.assertIn("Method loadForItemRendering:", section)
                    elif name == "item/GeoBlockItem":
                        self.assertIn("Item$TooltipContext" if major == 21 else "net.minecraft.world.level.Level", output)
                # Only vanilla ItemStack/helper execute: baseline declarations and mod renderers do not.
                runtime_cp = os.pathsep.join(map(str, [destination, *classpaths[version]]))
                code, output = invoke(f"native-item-states-{version}", [homes[major] / "bin/java", "-Djava.awt.headless=true",
                    "-cp", runtime_cp, "RenderDataProbe"])
                self.assertEqual(0, code, output); self.assertIn("real item states:", output)
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "class_major": major + 44, "complete_class_sha256": bytecodes, "runtime_output": output.strip()}
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            for path, raw in sources.items(): self.assertEqual(raw, (ROOT / path).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "item-renderer-data-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "baseline_symbols_executed": False,
                "legacy_active_sources_identical": True, "world_or_gameplay_executed": False,
                "rendering_executed": False}, indent=2) + "\n")
