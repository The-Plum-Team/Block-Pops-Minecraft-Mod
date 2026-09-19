"""Opt-in real enum/API and complete-source preprocessing probe, without game qualification."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam/block")
BLOCKS = ("BoxBlock", "ClawMachineBlock", "FigureBlock")
SOURCES = tuple(PACKAGE / (name + ".java") for name in (*BLOCKS, "BlockInteractionResults"))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_MINECRAFT_JARS")
PROBE = """
import com.theplumteam.block.BlockInteractionResults;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;

public class InteractionProbe {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] arguments) {
        int preserved = 0;
        for (InteractionResult original : InteractionResult.values()) {
            if (original == InteractionResult.SUCCESS_NO_ITEM_USED) {
                try {
                    BlockInteractionResults.forItem(original);
                    throw new AssertionError("modern-only semantics silently accepted");
                } catch (IllegalArgumentException expected) { }
                continue;
            }
            ItemInteractionResult converted = BlockInteractionResults.forItem(original);
            require(converted.result() == original);
            require(converted.consumesAction() == original.consumesAction());
            require(converted.result().shouldSwing() == original.shouldSwing());
            require(converted != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
            preserved++;
        }
        require(preserved == 5);
        require(BlockInteractionResults.forItem(InteractionResult.PASS)
                == ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION);
        for (boolean client : new boolean[]{false, true})
            require(BlockInteractionResults.forItem(InteractionResult.sidedSuccess(client))
                    == ItemInteractionResult.sidedSuccess(client));
        System.out.println("five legacy results preserved; PASS skips fallback; modern-only result rejected");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/mapped Minecraft paths required")
class BlockInteractionApiTests(unittest.TestCase):
    def test_real_result_conversion_and_both_complete_preprocessed_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            jars = {version: Path(path).resolve() for version, path in
                    json.loads(os.environ["BLOCKPOPS_TEST_MINECRAFT_JARS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(jars))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in jars.values()}
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(raw)
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
rootProject.name = 'block-interaction-api-probe'
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

            def invoke(name, command):
                child = subprocess.Popen([str(value) for value in command], cwd=root, env=env,
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, start_new_session=True)
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
                for path, original in zip(generated, SOURCES, strict=True):
                    self.assertTrue(path.is_file())
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if path.stem in BLOCKS:
                        pattern = r"private InteractionResult interact\(.*?\) \{(.*?)\n    \}"
                        before = re.search(pattern, sources[original].decode().replace("\r\n", "\n"), re.S)[1]
                        self.assertEqual(before, re.search(pattern, path.read_text(), re.S)[1])
                        active = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(), flags=re.S)
                        self.assertNotIn("useWithoutItem(", active)
                        if major == 21:
                            self.assertEqual(1, active.count("return BlockInteractionResults.forItem(interact("))
                        else:
                            self.assertNotIn("useItemOn(", active)
                helper = generated[-1]
                destination = root / f"classes-{version}"
                code, output = invoke(f"compile-helper-{version}", [homes[major] / "bin/javac", "--release", str(major),
                    "-cp", jars[version], "-d", destination, helper])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/block/BlockInteractionResults.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "helper_class_major": major + 44, "helper_class_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest()}
            modern = root / "common/versions/1.21.1/build/generated/stonecutter/main/java/com/theplumteam/block/BlockInteractionResults.java"
            code, output = invoke("reject-modern-on-legacy", [homes[21] / "bin/javac", "--release", "21",
                "-cp", jars["1.20.1"], "-d", root / "wrong-api", modern])
            self.assertNotEqual(0, code); self.assertIn("cannot find symbol", output)
            destination = root / "classes-1.21.1"
            probe = root / "InteractionProbe.java"; probe.write_text(PROBE)
            cp = os.pathsep.join(map(str, (destination, jars["1.21.1"])))
            code, output = invoke("compile-enum-probe", [homes[21] / "bin/javac", "--release", "21",
                "-cp", cp, "-d", destination, probe])
            self.assertEqual(0, code, output)
            code, output = invoke("real-enum-roundtrip", [homes[21] / "bin/java", "-cp", cp, "InteractionProbe"])
            self.assertEqual(0, code, output); self.assertIn("five legacy results preserved", output)
            self.assertEqual(inputs, {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in jars.values()})
            for path, raw in sources.items():
                self.assertEqual(raw, (ROOT / path).read_bytes()); self.assertEqual(raw, (root / path).read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "block-interaction-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "mapped_game_inputs": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "real_enum_roundtrip": output.strip(),
                "complete_modern_blocks_compiled": False, "game_interactions_executed": False}, indent=2) + "\n")
