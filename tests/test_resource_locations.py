"""Opt-in Stonecutter/real Minecraft API probe; this does not qualify a game lane."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group


ROOT = Path(__file__).resolve().parents[1]
HELPER = Path("common/src/main/java/com/theplumteam/util/ResourceLocations.java")
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_MINECRAFT_JARS")


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/mapped Minecraft paths required")
class ResourceLocationsApiTests(unittest.TestCase):
    def test_preprocessed_helper_compiles_against_both_real_apis(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            jars = {version: Path(path).resolve() for version, path in json.loads(os.environ["BLOCKPOPS_TEST_MINECRAFT_JARS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(jars))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            source = (ROOT / HELPER).read_bytes()
            (root / HELPER).parent.mkdir(parents=True)
            (root / HELPER).write_bytes(source)
            (root / "gradle").mkdir()
            shutil.copyfile(ROOT / "gradle/verification-metadata.xml", root / "gradle/verification-metadata.xml")
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
rootProject.name = 'resource-location-api-probe'
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
                generated = root / f"common/versions/{version}/build/generated/stonecutter/main/java/com/theplumteam/util/ResourceLocations.java"
                self.assertTrue(generated.is_file())
                (logs / f"ResourceLocations-{version}.java").write_bytes(generated.read_bytes())
                destination = root / f"classes-{version}"
                command = [homes[major] / "bin/javac", "--release", str(major), "-cp", jars[version], "-d", destination, generated]
                code, output = invoke(f"compile-{version}", command)
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/util/ResourceLocations.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, disassembly = invoke(f"bytecode-{version}", [homes[major] / "bin/javap", "-c", bytecode])
                self.assertEqual(0, code, disassembly)
                operation = 'ResourceLocation."<init>"' if major == 17 else "ResourceLocation.fromNamespaceAndPath"
                self.assertIn(operation, disassembly)
                other_version, other_major = ("1.21.1", 21) if major == 17 else ("1.20.1", 17)
                code, output = invoke(f"reject-crossed-{version}", [homes[other_major] / "bin/javac", "--release", str(other_major),
                    "-cp", jars[other_version], "-d", root / f"wrong-{version}", generated])
                self.assertNotEqual(0, code, "the wrong API unexpectedly accepted this branch")
                self.assertIn("private access" if major == 17 else "cannot find symbol", output)
                observations[version] = {"jdk_home": str(homes[major]), "class_major": major + 44,
                    "minecraft_jar": str(jars[version]), "minecraft_jar_sha256": hashlib.sha256(jars[version].read_bytes()).hexdigest(),
                    "generated_sha256": hashlib.sha256(generated.read_bytes()).hexdigest(),
                    "helper_bytecode_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(), "invocation": operation}
            code, output = invoke("compile-unprocessed-legacy", [homes[17] / "bin/javac", "--release", "17",
                "-cp", jars["1.20.1"], "-d", root / "raw-legacy", root / HELPER])
            self.assertEqual(0, code, output)
            self.assertEqual(source, (root / HELPER).read_bytes())
            self.assertEqual(source, (ROOT / HELPER).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "api-compatibility-probe", "qualified": False,
                "source_sha256": hashlib.sha256(source).hexdigest(), "observations": observations}, indent=2) + "\n")
