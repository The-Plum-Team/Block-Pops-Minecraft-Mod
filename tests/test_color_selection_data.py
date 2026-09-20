"""Opt-in complete widget compilation and native item-data bodies, without a client or world."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from scripts.release.build_matrix import _finish_owned_group
from tests.test_item_renderer_data import method_body

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam")
CLASSES = ("client/gui/widget/ColorSelectionButton", "item/BlockEntityItemData", "block/PopBlockColor")
SOURCES = tuple(PACKAGE / (name + ".java") for name in CLASSES)
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS",
    "BLOCKPOPS_TEST_DECLARATIONS", "BLOCKPOPS_TEST_COLOR_BASELINE")
PROBE = """
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.item.BlockEntityItemData;
import java.util.UUID;
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
public class ColorDataProbe {
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    __ACTUAL_BODIES__
    static CompoundTag checked(ItemStack stack) {
        CompoundTag result = BlockEntityItemData.read(stack);
        require(result != null);
        //? if >=1.21 {
        /*require(result.getString("id").equals("blockpops:box_block"));
        var codec = DataComponents.BLOCK_ENTITY_DATA.codecOrThrow();
        var encoded = codec.encodeStart(NbtOps.INSTANCE, stack.get(DataComponents.BLOCK_ENTITY_DATA)).result().orElseThrow();
        require(codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow().copyTag().equals(result));
        *///? } else {
        require(BlockEntityItemData.read(ItemStack.of(stack.save(new CompoundTag()))).equals(result));
        //? }
        return result;
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        UUID first = UUID.fromString("12345678-1234-1234-1234-123456789012");
        UUID second = UUID.fromString("87654321-4321-4321-4321-210987654321");
        for (PopBlockColor color : PopBlockColor.values()) {
            ItemStack empty = new ItemStack(Items.CHEST);
            configure(empty, color, null);
            CompoundTag tag = checked(empty);
            require(tag.getBoolean("HideLogo") && tag.getString("Color").equals(color.name()));
            require(!tag.contains("CollectionId") && !tag.contains("FigureId"));
        }
        ItemStack stack = new ItemStack(Items.CHEST, 7);
        CompoundTag unrelated = new CompoundTag(); unrelated.putString("keep", "unrelated");
        //? if >=1.21 {
        /*stack.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        *///? } else {
        stack.getOrCreateTag().put("unrelated", unrelated);
        //? }
        configure(stack, PopBlockColor.CYAN, first);
        CompoundTag tag = checked(stack);
        require(tag.getString("CollectionId").equals("world_players") && tag.getString("FigureId").equals(first.toString()));
        require(tag.contains("IsFigureExtracted") && !tag.getBoolean("IsFigureExtracted"));
        require(tag.getDouble("FigureOffsetX") == -0.53 && tag.getDouble("FigureOffsetY") == 0.01);
        require(tag.getDouble("FigureOffsetZ") == -0.55 && tag.getDouble("FigureScale") == 1.0);
        CompoundTag nested = new CompoundTag(); nested.putInt("cache", 42); tag.put("extra", nested);
        BlockEntityItemData.write(stack, tag, "blockpops:box_block");
        CompoundTag cached = BlockEntityItemData.read(stack), expected = cached.copy();
        show(stack, true, null); require(checked(stack).equals(expected));
        show(stack, false, second);
        expected.putString("CollectionId", ""); expected.putString("FigureId", "");
        require(checked(stack).equals(expected));
        require(checked(stack).contains("CollectionId") && checked(stack).contains("FigureId"));
        //? if >=1.21 {
        /*require(cached.getString("FigureId").equals(first.toString()));
        require(stack.get(DataComponents.CUSTOM_DATA).copyTag().equals(unrelated));
        *///? } else {
        require(cached == BlockEntityItemData.read(stack) && cached.equals(expected));
        require(stack.getTagElement("unrelated").equals(unrelated));
        //? }
        show(stack, true, null); require(checked(stack).equals(expected));
        show(stack, true, second);
        expected.putString("CollectionId", "world_players"); expected.putString("FigureId", second.toString());
        require(checked(stack).equals(expected));
        require(stack.getCount() == 7 && stack.getItem() == Items.CHEST);
        for (boolean visible : new boolean[]{true, false}) {
            ItemStack absent = new ItemStack(Items.CHEST);
            show(absent, visible, null);
            CompoundTag created = checked(absent);
            require(created.contains("CollectionId") == !visible && created.contains("FigureId") == !visible);
            if (!visible) require(created.getString("CollectionId").isEmpty() && created.getString("FigureId").isEmpty());
        }
        //? if <1.21 {
        ItemStack wrongType = new ItemStack(Items.CHEST);
        wrongType.getOrCreateTag().putString("BlockEntityTag", "not-a-compound");
        configure(wrongType, PopBlockColor.RED, null);
        require(checked(wrongType).getString("Color").equals("RED"));
        //? }
        System.out.println("native color data: creation, all colors, UUID, nullable player, show/hide, cache, extras and codec preserved");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/baseline inputs required")
class ColorSelectionDataApiTests(unittest.TestCase):
    def test_complete_widget_and_actual_mutation_bodies(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs"))); logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            baseline = Path(os.environ["BLOCKPOPS_TEST_COLOR_BASELINE"]).resolve()
            raw = sources[SOURCES[0]]; newline = b"\r\n" if b"\r\n" in raw else b"\n"
            added = b"import com.theplumteam.item.BlockEntityItemData;" + newline
            read = newline.join((b"        CompoundTag blockEntityTag = BlockEntityItemData.read(this.boxItem);",
                b"        if (blockEntityTag == null) {", b"            blockEntityTag = new CompoundTag();", b"        }"))
            write = b'        BlockEntityItemData.write(this.boxItem, blockEntityTag, "blockpops:box_block");' + newline
            self.assertEqual(1, raw.count(added)); self.assertEqual(2, raw.count(read)); self.assertEqual(2, raw.count(write))
            reverted = raw.replace(added, b"", 1).replace(read,
                b'        CompoundTag blockEntityTag = this.boxItem.getOrCreateTagElement("BlockEntityTag");').replace(write, b"")
            self.assertEqual(baseline.read_bytes(), reverted)
            bodies = []
            source = raw.decode().replace("\r\n", "\n")
            for signature, declaration in (("public ColorSelectionButton(", "configure(ItemStack stack, PopBlockColor color, UUID playerId)"),
                                           ("public void setShowFigure(", "show(ItemStack stack, boolean showFigure, UUID playerId)")):
                body = method_body(source, signature)
                if signature.startswith("public ColorSelectionButton"):
                    body = body[body.index("CompoundTag blockEntityTag"):]
                for old, new in (("this.boxItem", "stack"), ("Minecraft mc = Minecraft.getInstance();", ""),
                                 ("mc.player != null", "playerId != null"), ("mc.player.getUUID()", "playerId")):
                    self.assertIn(old, body); body = body.replace(old, new)
                bodies.append("static void " + declaration + " {" + body + "}")
            declarations = Path(os.environ["BLOCKPOPS_TEST_DECLARATIONS"]).resolve()
            symbols = sorted(declarations.rglob("*.class")); self.assertTrue(symbols)
            owned = {"com/theplumteam/" + name for name in CLASSES}
            for path in symbols:
                self.assertNotIn(path.relative_to(declarations).as_posix().removesuffix(".class").split("$")[0], owned)
            for paths in classpaths.values():
                for path in paths:
                    with zipfile.ZipFile(path) as archive:
                        self.assertFalse({name.removesuffix(".class").split("$")[0]
                            for name in archive.namelist() if name.endswith(".class")} & owned)
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                      [baseline, *symbols, *(p for paths in classpaths.values() for p in paths)]}
            for path, contents in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True); (root / path).write_bytes(contents)
            (root / "common/src/main/java/ColorDataProbe.java").write_text(PROBE.replace("__ACTUAL_BODIES__", "\n".join(bodies)))
            copied_symbols = root / "symbols"
            for path in symbols:
                target = copied_symbols / path.relative_to(declarations)
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(path.read_bytes())
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
rootProject.name = 'color-selection-data-api-probe'
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
                try: output, _ = child.communicate(timeout=90)
                finally: _finish_owned_group(child)
                (logs / f"{name}.log").write_text(output)
                return child.returncode, output
            code, output = invoke("preprocess", [os.environ["BLOCKPOPS_TEST_GRADLE"], "--offline", "--no-daemon",
                "--no-parallel", "--max-workers=1", "--no-configuration-cache", "--no-build-cache",
                "--dependency-verification", "strict", "--gradle-user-home", os.environ["BLOCKPOPS_TEST_GRADLE_HOME"],
                f"-Dorg.gradle.java.home={homes[21]}", ":common:1.20.1:stonecutterGenerate", ":common:1.21.1:stonecutterGenerate"])
            self.assertEqual(0, code, output); observations = {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = [generated_root / path.relative_to("common/src/main/java") for path in SOURCES]
                for name, path, original in zip(CLASSES, generated, SOURCES, strict=True):
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if name != "item/BlockEntityItemData":
                        self.assertEqual(sources[original].decode().replace("\r\n", "\n"), path.read_text())
                probe = generated_root / "ColorDataProbe.java"; (logs / f"{version}-ColorDataProbe.java").write_bytes(probe.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [copied_symbols, *classpaths[version]]))
                code, output = invoke(f"compile-complete-classes-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, *generated, probe])
                self.assertEqual(0, code, output); bytecodes = {}
                for name in CLASSES:
                    bytecode = destination / f"com/theplumteam/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    bytecodes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                code, output = invoke(f"widget-bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c", "-classpath",
                    destination, "com.theplumteam.client.gui.widget.ColorSelectionButton"])
                self.assertEqual(0, code, output)
                self.assertEqual(2, output.count("BlockEntityItemData.read:")); self.assertEqual(2, output.count("BlockEntityItemData.write:"))
                self.assertNotIn("getOrCreateTagElement:", output)
                # Native data bodies use the actual helper, never baseline declarations or GUI/client initialization.
                runtime_cp = os.pathsep.join(map(str, [destination, *classpaths[version]]))
                code, output = invoke(f"native-color-data-{version}", [homes[major] / "bin/java", "-Djava.awt.headless=true",
                    "-cp", runtime_cp, "ColorDataProbe"])
                self.assertEqual(0, code, output); self.assertIn("native color data:", output)
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "class_major": major + 44, "complete_class_sha256": bytecodes, "runtime_output": output.strip()}
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            for path, contents in sources.items():
                self.assertEqual(contents, (ROOT / path).read_bytes()); self.assertEqual(contents, (root / path).read_bytes())
            for path in symbols:
                self.assertEqual(path.read_bytes(), (copied_symbols / path.relative_to(declarations)).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "color-selection-data-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(contents).hexdigest() for path, contents in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "complete_widget_and_current_helper_compiled": True,
                "actual_mutation_bodies_executed": True, "baseline_symbols_executed": False,
                "http_world_gui_or_gameplay_executed": False, "vanilla_bootstrap": True}, indent=2) + "\n")
