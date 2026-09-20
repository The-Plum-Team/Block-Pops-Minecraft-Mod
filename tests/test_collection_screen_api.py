"""Opt-in full screen/helper compilation against actual APIs; no GL/client is initialized."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from scripts.release.build_matrix import _finish_owned_group
from tests.test_block_entity_persistence import java_tokens

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("common/src/main/java/com/theplumteam")
CLASSES = ("client/gui/CollectionSelectionScreen", "client/gui/widget/CollectionListWidget",
    "client/gui/widget/FigureListWidget", "client/gui/widget/BoundedSelectionList", "client/gui/util/GuiScaleManager",
    "client/gui/widget/LinkButton", "util/ResourceLocations")
SOURCES = tuple(PACKAGE / (name + ".java") for name in CLASSES)
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS", "BLOCKPOPS_TEST_DECLARATIONS",
    "BLOCKPOPS_TEST_COLLECTION_BASELINE", "BLOCKPOPS_TEST_COLLECTION_HISTORICAL")


def modern_quad(source):
    start = source.index("int starHeight = GuiScaleManager.isUsingInverseScale() ?")
    end = source.index("BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());", start)
    return source[start:end + len("BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());")]


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/baseline inputs required")
class CollectionScreenApiProbe(unittest.TestCase):
    def test_complete_screen_preserves_quad_geometry_and_legacy_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs"))); logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            baseline = Path(os.environ["BLOCKPOPS_TEST_COLLECTION_BASELINE"]).resolve()
            historical = [Path(path).resolve() for path in json.loads(os.environ["BLOCKPOPS_TEST_COLLECTION_HISTORICAL"])]
            self.assertEqual(2, len(historical))
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
                      [baseline, *historical, *symbols, *(p for paths in classpaths.values() for p in paths)]}
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
rootProject.name = 'collection-screen-api-api-probe'
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
                for path in generated: (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                active = generated[0].read_text()
                if major == 17:
                    self.assertEqual(java_tokens(baseline.read_text()), java_tokens(active))
                else:
                    quad = modern_quad(active)
                    for path in historical: self.assertEqual(java_tokens(modern_quad(path.read_text())), java_tokens(quad))
                    legacy = baseline.read_text()
                    start = legacy.index("Tesselator tesselator = Tesselator.getInstance();")
                    end = legacy.index("tesselator.end();", start) + len("tesselator.end();")
                    reverted = active.replace(quad, legacy[start:end]).replace("import com.mojang.blaze3d.vertex.BufferUploader;", "")
                    reverted = reverted.replace("double mouseX, double mouseY, double horizontal, double delta", "double mouseX, double mouseY, double delta")
                    reverted = reverted.replace("super.mouseScrolled(mouseX, mouseY, horizontal, delta)", "super.mouseScrolled(mouseX, mouseY, delta)")
                    self.assertEqual(java_tokens(legacy), java_tokens(reverted))
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [copied_symbols, *classpaths[version]]))
                code, output = invoke(f"compile-complete-classes-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, *generated])
                self.assertEqual(0, code, output); bytecodes = {}
                for name in CLASSES:
                    bytecode = destination / f"com/theplumteam/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    bytecodes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                code, output = invoke(f"screen-bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c", "-classpath",
                    destination, "com.theplumteam.client.gui.CollectionSelectionScreen"])
                self.assertEqual(0, code, output)
                self.assertEqual(4, output.count("addVertex:" if major == 21 else "vertex:"))
                self.assertEqual(4, output.count("setUv:" if major == 21 else "uv:"))
                self.assertIn("BufferUploader.drawWithShader:" if major == 21 else "Tesselator.end:", output)
                if major == 21:
                    code, output = invoke("reject-legacy-api-on-modern", [homes[21] / "bin/javac", "-proc:none", "--release", "21",
                        "-cp", str(destination) + os.pathsep + cp, "-d", root / "wrong-api",
                        root / "common/versions/1.20.1/build/generated/stonecutter/main/java/com/theplumteam/client/gui/CollectionSelectionScreen.java"])
                    self.assertNotEqual(0, code); self.assertIn("method getBuilder()", output)
                    self.assertIn("method end()", output); self.assertIn("9 errors", output)
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "class_major": major + 44, "complete_class_sha256": bytecodes}
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            for path, raw in sources.items():
                self.assertEqual(raw, (ROOT / path).read_bytes()); self.assertEqual(raw, (root / path).read_bytes())
            for path in symbols:
                self.assertEqual(path.read_bytes(), (copied_symbols / path.relative_to(declarations)).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "collection-screen-api-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "complete_screen_and_current_helpers_compiled": True,
                "legacy_active_tokens_identical": True, "modern_quad_historical_tokens_identical": True,
                "baseline_symbols_executed": False, "GL_HTTP_world_or_gameplay_executed": False}, indent=2) + "\n")
