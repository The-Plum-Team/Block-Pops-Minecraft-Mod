"""Opt-in complete list widgets: actual APIs and wheel callbacks without a client/window."""

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
WIDGETS = ("client/gui/widget/CollectionListWidget", "client/gui/widget/FigureListWidget")
CLASSES = (*WIDGETS, "client/gui/widget/BoundedSelectionList", "client/gui/util/GuiScaleManager")
SOURCES = tuple(PACKAGE / (name + ".java") for name in CLASSES)
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
    "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS", "BLOCKPOPS_TEST_DECLARATIONS",
    "BLOCKPOPS_TEST_LIST_BASELINES")
PROBE = """
import com.theplumteam.client.gui.widget.CollectionListWidget;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.client.gui.util.GuiScaleManager;
import net.minecraft.client.gui.GuiGraphics;

public class ListWidgetProbe {
    static final RuntimeException STOP = new RuntimeException("before rendering");
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static class Collections extends CollectionListWidget {
        Collections() { super(null, null, 101, 101, 7, 17); setLeftPos(13); }
        protected int getMaxPosition() { return 1000; }
        protected void renderBackground(GuiGraphics graphics) { throw STOP; }
        void verify() {
            require(getRowWidth() == 93 && getScrollbarPosition() == 108);
            require(getRectangle().left() == 13 && getRectangle().right() == 114);
            require(getRectangle().top() == 7 && getRectangle().bottom() == 108);
            setScrollAmount(100);
            //? if >=1.21 {
            /*require(mouseScrolled(20, 30, 77.25, 2.5));
            *///? } else {
            require(mouseScrolled(20, 30, 2.5));
            //? }
            require(getScrollAmount() == 62.5);
            try {
                //? if >=1.21 {
                /*renderWidget(null, 20, 30, 0);
                *///? } else {
                render(null, 20, 30, 0);
                //? }
                throw new AssertionError();
            } catch (RuntimeException error) { require(error == STOP && height == 101); }
            require(getScrollAmount() == 62.5);
            System.out.println("collections: row93, edges13/114/7/108, vertical wheel15, render delegate");
        }
    }
    static class Figures extends FigureListWidget {
        Figures() { super(null, 101, 101, 7, 17); setLeftPos(13); }
        protected int getMaxPosition() { return 1000; }
        protected void renderBackground(GuiGraphics graphics) { throw STOP; }
        void verify() {
            require(getXOffset() == 14 && getRowWidth() == 93 && getScrollbarPosition() == 108);
            setScrollAmount(100);
            //? if >=1.21 {
            /*require(mouseScrolled(20, 30, -80, 2.5));
            *///? } else {
            require(mouseScrolled(20, 30, 2.5));
            //? }
            require(getScrollAmount() == 50);
            //? if >=1.21 {
            /*require(mouseScrolled(20, 30, 90, 0) && getScrollAmount() == 50);
            *///? }
            updateConfiguration(1.25f, 10, 20, 30, 40, 50, 60);
            require(getModelScale() == 1.25f && getXRotation() == 10 && getYRotation() == 20 && getZRotation() == 30);
            require(getXOffset() == 40 && getYOffset() == 50 && getZOffset() == 60);
            try {
                //? if >=1.21 {
                /*renderWidget(null, 20, 30, 0);
                *///? } else {
                render(null, 20, 30, 0);
                //? }
                throw new AssertionError();
            } catch (RuntimeException error) { require(error == STOP && height == 101); }
            require(getScrollAmount() == 50);
            setCollection(null); require(children().isEmpty() && getCurrentCollection() == null && getScrollAmount() == 0);
            System.out.println("figures: default xOffset14, vertical wheel20, configuration, render delegate, reset");
        }
    }
    public static void main(String[] arguments) {
        require(!GuiScaleManager.isUsingInverseScale());
        new Collections().verify(); new Figures().verify();
        System.out.println("actual widget callbacks passed; inverse-scale bodies compared, no window or GL initialized");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/API/baseline inputs required")
class ListWidgetWiringApiTests(unittest.TestCase):
    def test_complete_widgets_and_native_wheel_callbacks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs"))); logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PERSISTENCE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            baselines = {name: Path(path).resolve() for name, path in json.loads(os.environ["BLOCKPOPS_TEST_LIST_BASELINES"]).items()}
            self.assertEqual(set(WIDGETS), set(baselines))
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
                      [*baselines.values(), *symbols, *(p for paths in classpaths.values() for p in paths)]}
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True); (root / path).write_bytes(raw)
            (root / "common/src/main/java/ListWidgetProbe.java").write_text(PROBE)
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
rootProject.name = 'list-widget-wiring-api-probe'
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
            self.assertEqual(0, code, output); observations = {}; native_results = []
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = [generated_root / path.relative_to("common/src/main/java") for path in SOURCES]
                for name, path in zip(CLASSES, generated, strict=True):
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if name not in WIDGETS: continue
                    reverted = path.read_text().replace("extends BoundedSelectionList<", "extends ObjectSelectionList<")
                    if major == 21:
                        reverted = reverted.replace("renderWidget(", "render(").replace("double horizontalAmount, ", "")
                    self.assertEqual(java_tokens(baselines[name].read_text()), java_tokens(reverted), name)
                probe = generated_root / "ListWidgetProbe.java"; (logs / f"{version}-ListWidgetProbe.java").write_bytes(probe.read_bytes())
                destination = root / f"classes-{version}"; cp = os.pathsep.join(map(str, [copied_symbols, *classpaths[version]]))
                code, output = invoke(f"compile-complete-classes-{version}", [homes[major] / "bin/javac", "-proc:none",
                    "--release", str(major), "-cp", cp, "-d", destination, *generated, probe])
                self.assertEqual(0, code, output); bytecodes = {}
                for name in CLASSES:
                    bytecode = destination / f"com/theplumteam/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    bytecodes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                code, output = invoke(f"native-widgets-{version}", [homes[major] / "bin/java", "-Djava.awt.headless=true",
                    "-cp", str(destination) + os.pathsep + cp, "ListWidgetProbe"])
                self.assertEqual(0, code, output); self.assertIn("actual widget callbacks passed", output); native_results.append(output.strip())
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "class_major": major + 44, "complete_class_sha256": bytecodes, "native_output": output.strip()}
            self.assertEqual(native_results[0], native_results[1])
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            for path, raw in sources.items(): self.assertEqual(raw, (ROOT / path).read_bytes()); self.assertEqual(raw, (root / path).read_bytes())
            for path in symbols: self.assertEqual(path.read_bytes(), (copied_symbols / path.relative_to(declarations)).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "list-widget-wiring-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(), "observations": observations,
                "commands": commands, "inverse_scale_bodies_tokens_identical": True, "current_gui_scale_manager_compiled": True,
                "native_widget_callbacks_executed": True, "baseline_domain_methods_executed": False,
                "inverse_scale_window_or_GL_or_HTTP_or_gameplay_executed": False}, indent=2) + "\n")
