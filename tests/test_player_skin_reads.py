"""Real API probe: modern model registration remains an explicit compilation boundary."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group
from tests.test_block_entity_persistence import java_tokens

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam")
MODELS = ("client/model/FigureModel", "client/model/FigureBlockModel")
DETECTOR = "util/SkinModelDetector"
TARGETS = (*MODELS, DETECTOR)
DEPENDENCIES = ("blockentity/BoxBlockEntity", "blockentity/FigureBlockEntity",
                "item/BlockEntityItemData", "util/ResourceLocations")
CLASSES = (*TARGETS, *DEPENDENCIES)
SOURCES = tuple(PACKAGE / (name + ".java") for name in CLASSES)
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS",
    "BLOCKPOPS_TEST_DECLARATIONS", "BLOCKPOPS_TEST_SKIN_BASELINES")


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/baseline inputs required")
class PlayerSkinReadApiTests(unittest.TestCase):
    def test_complete_detector_and_legacy_models_with_four_explicit_modern_registration_errors(self):
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
                         json.loads(os.environ["BLOCKPOPS_TEST_SKIN_BASELINES"]).items()}
            self.assertEqual(set(TARGETS), set(baselines))
            declarations = Path(os.environ["BLOCKPOPS_TEST_DECLARATIONS"]).resolve()
            symbols = sorted(declarations.rglob("*.class")); self.assertTrue(symbols)
            forbidden = {name.rsplit("/", 1)[-1] for name in CLASSES}
            self.assertFalse({path.stem.split("$")[0] for path in symbols} & forbidden)
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                      [*baselines.values(), *symbols, *(p for paths in classpaths.values() for p in paths)]}
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True); (root / path).write_bytes(raw)
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
rootProject.name = 'player-skin-read-api-probe'
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
            observations = {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = {name: generated_root / "com/theplumteam" / (name + ".java") for name in CLASSES}
                for name, path in generated.items():
                    self.assertTrue(path.is_file()); (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if major == 17 and name in baselines:
                        self.assertEqual(java_tokens(baselines[name].read_text()), java_tokens(path.read_text()), name)
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [copied_symbols, *classpaths[version]]))
                compiled = CLASSES if major == 17 else (DETECTOR, *DEPENDENCIES)
                code, output = invoke(f"compile-complete-classes-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, *(generated[name] for name in compiled)])
                self.assertEqual(0, code, output)
                bytecodes = {}
                for name in compiled:
                    bytecode = destination / f"com/theplumteam/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    bytecodes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                code, output = invoke(f"detector-bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c",
                    "-classpath", destination, "com.theplumteam.util.SkinModelDetector"])
                self.assertEqual(0, code, output)
                if major == 21:
                    self.assertEqual(1, output.count("PlayerInfo.getSkin:"))
                    self.assertIn("PlayerSkin.texture:", output); self.assertIn("PlayerSkin.model:", output)
                    self.assertIn("PlayerSkin$Model.SLIM:", output)
                    self.assertNotIn("PlayerInfo.getSkinLocation:", output); self.assertNotIn("PlayerInfo.getModelName:", output)
                    code, output = invoke("complete-modern-models-registration-boundary", [homes[21] / "bin/javac",
                        "-proc:none", "-Xmaxerrs", "1000", "--release", "21", "-cp", str(destination) + os.pathsep + cp,
                        "-d", root / "modern-models", *(generated[name] for name in MODELS)])
                    self.assertNotEqual(0, code)
                    errors = re.findall(r"(?m)^(.+\.java):\d+: error: (.+)$", output)
                    self.assertCountEqual([(str(generated[name]), "cannot find symbol") for name in MODELS for _ in range(2)], errors)
                    self.assertEqual(4, len(re.findall(r"symbol:\s+method registerSkins\(GameProfile,", output)))
                    self.assertFalse(list((root / "modern-models").rglob("*.class")))
                observations[version] = {"generated_sha256": {name: hashlib.sha256(p.read_bytes()).hexdigest() for name, p in generated.items()},
                    "class_major": major + 44, "complete_class_sha256": bytecodes,
                    "models_compiled": major == 17, "pending_registration_errors": 4 if major == 21 else 0}
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            for path, raw in sources.items(): self.assertEqual(raw, (ROOT / path).read_bytes())
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "player-skin-read-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "baseline_symbols_executed": False,
                "legacy_active_sources_identical": True, "modern_models_compiled": False,
                "rendering_or_network_executed": False}, indent=2) + "\n")
