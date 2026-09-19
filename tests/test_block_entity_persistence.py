"""Opt-in complete Claw compilation; the baseline registry symbol is never executed."""

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
SOURCE = Path("common/src/main/java/com/theplumteam/blockentity/ClawMachineBlockEntity.java")
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS",
    "BLOCKPOPS_TEST_REGISTRY_SYMBOL", "BLOCKPOPS_TEST_LEGACY_CLAW_SOURCE")


def java_tokens(source):
    pattern = r'''("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|/\*.*?\*/|//[^\n]*'''
    active = re.sub(pattern, lambda match: match[1] or "", source, flags=re.S)
    return re.findall(r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[\w$]+|[^\s]''', active)


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/symbol inputs required")
class BlockEntityPersistenceApiTests(unittest.TestCase):
    def test_complete_claw_compiles_against_both_native_persistence_apis(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            source = (ROOT / SOURCE).read_bytes()
            baseline = Path(os.environ["BLOCKPOPS_TEST_LEGACY_CLAW_SOURCE"]).resolve()
            symbol = Path(os.environ["BLOCKPOPS_TEST_REGISTRY_SYMBOL"]).resolve()
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in [baseline, symbol, *(p for paths in classpaths.values() for p in paths)]}
            self.assertIn("public void load(CompoundTag tag)", baseline.read_text())
            (root / SOURCE).parent.mkdir(parents=True)
            (root / SOURCE).write_bytes(source)
            # Use only the real compiled registry declaration; no constructors/static initializers run.
            symbols = root / "symbols"
            copied_symbol = symbols / "com/theplumteam/registry/ModBlockEntities.class"
            copied_symbol.parent.mkdir(parents=True); copied_symbol.write_bytes(symbol.read_bytes())
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
rootProject.name = 'block-entity-persistence-api-probe'
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
                generated = root / f"common/versions/{version}/build/generated/stonecutter/main/java" / SOURCE.relative_to("common/src/main/java")
                self.assertTrue(generated.is_file())
                (logs / f"{version}-ClawMachineBlockEntity.java").write_bytes(generated.read_bytes())
                if major == 17:
                    self.assertEqual(java_tokens(baseline.read_text()), java_tokens(generated.read_text()))
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [symbols, *classpaths[version]]))
                code, output = invoke(f"compile-complete-claw-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, generated])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/blockentity/ClawMachineBlockEntity.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, output = invoke(f"compiled-claw-bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c",
                    "-classpath", destination, "com.theplumteam.blockentity.ClawMachineBlockEntity"])
                self.assertEqual(0, code, output)
                provider = "net.minecraft.core.HolderLookup$Provider"
                if major == 21:
                    for signature in (f"protected void saveAdditional(net.minecraft.nbt.CompoundTag, {provider})",
                        f"protected void loadAdditional(net.minecraft.nbt.CompoundTag, {provider})",
                        f"public net.minecraft.nbt.CompoundTag getUpdateTag({provider})",
                        f"public void handleUpdateTag(net.minecraft.nbt.CompoundTag, {provider})",
                        f"public void onDataPacket(net.minecraft.network.Connection, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket, {provider})"):
                        self.assertIn(signature, output)
                    self.assertNotIn("public void load(net.minecraft.nbt.CompoundTag)", output)
                    self.assertIn("BlockEntity.saveAdditional:(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", output)
                    self.assertIn("BlockEntity.loadAdditional:(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", output)
                else:
                    self.assertNotIn("HolderLookup$Provider", output)
                    self.assertIn("public void load(net.minecraft.nbt.CompoundTag)", output)
                observations[version] = {"generated_sha256": hashlib.sha256(generated.read_bytes()).hexdigest(),
                    "complete_class_major": major + 44, "complete_class_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest()}
                if major == 21:
                    code, native = invoke("native-block-entity-dispatch", [homes[21] / "bin/javap", "-p", "-c",
                        "-classpath", os.pathsep.join(map(str, classpaths[version])), "net.minecraft.world.level.block.entity.BlockEntity"])
                    self.assertEqual(0, code, native)
                    method = native.split("public final void loadCustomOnly(", 1)[1].split("protected void saveAdditional(", 1)[0]
                    self.assertIn("Method loadAdditional:(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", method)
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(source, (ROOT / SOURCE).read_bytes()); self.assertEqual(source, (root / SOURCE).read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            self.assertEqual(symbol.read_bytes(), copied_symbol.read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "claw-persistence-api-probe", "qualified": False,
                "source_sha256": hashlib.sha256(source).hexdigest(), "inputs_sha256": inputs,
                "metadata_sha256": hashlib.sha256(metadata).hexdigest(), "observations": observations,
                "commands": commands, "registry_symbol_executed": False, "world_or_gameplay_executed": False,
                "legacy_active_source_identical": True, "neoforge_runtime_tested": False}, indent=2) + "\n")
