"""Opt-in native list geometry probe; no Minecraft client, window, or GL context."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("common/src/main/java/com/theplumteam/client/gui/widget/BoundedSelectionList.java")
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_GAME_CLASSPATHS")
PROBE = """
import com.theplumteam.client.gui.widget.BoundedSelectionList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class BoundsProbe {
    static final RuntimeException STOP = new RuntimeException("before GL");
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static class ProbeEntry extends ObjectSelectionList.Entry<ProbeEntry> {
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) { throw STOP; }
        public void updateNarration(NarrationElementOutput output) { throw STOP; }
        public Component getNarration() { return Component.empty(); }
    }
    static class ListProbe extends BoundedSelectionList<ProbeEntry> {
        int seenHeight;
        boolean failScroll;
        ListProbe() {
            super(null, 101, 137, 7, 108, 17);
            setRenderHeader(true, 5);
            for (int index = 0; index < 30; index++) addEntry(new ProbeEntry());
        }
        public int getRowWidth() { return width - 8; }
        protected int getScrollbarPosition() { return x1 - 6; }
        public int getMaxScroll() { if (failScroll) throw STOP; return super.getMaxScroll(); }
        //? if >=1.21 {
        /*protected void renderListBackground(GuiGraphics graphics) { seenHeight = height; throw STOP; }
        void enterRender() { super.renderWidget(null, -100, -100, 0); }
        void disabledDecorations() {
            setRenderBackground(false); setRenderTopAndBottom(false);
            super.renderListBackground(null); super.renderListSeparators(null);
        }
        *///? } else {
        protected void renderBackground(GuiGraphics graphics) { seenHeight = height; throw STOP; }
        void enterRender() { super.render(null, -100, -100, 0); }
        void disabledDecorations() { setRenderBackground(false); setRenderTopAndBottom(false); }
        //? }
        void verify(float scale) {
            setLeftPos(13);
            require(x0 == 13 && x1 == 114 && y0 == 7 && y1 == 108 && width == 101 && height == 137);
            x0 = (int)(x0 * scale); x1 = (int)(x1 * scale);
            y0 = (int)(y0 * scale); y1 = (int)(y1 * scale);
            width = (int)(width * scale); height = (int)(height * scale);
            int savedHeight = height, viewport = y1 - y0;
            require(getRectangle().left() == x0 && getRectangle().right() == x1);
            require(getRectangle().top() == y0 && getRectangle().bottom() == y1);
            require(isMouseOver(x0, y0) && isMouseOver(x1, y1));
            require(!isMouseOver(x0 - .01, y0) && !isMouseOver(x1 + .01, y1));
            require(!isMouseOver(x0, y0 - .01) && !isMouseOver(x1, y1 + .01));
            require(getRowLeft() == x0 + width / 2 - getRowWidth() / 2 + 2);
            require(getMaxPosition() == 30 * 17 + 5);
            int max = Math.max(0, getMaxPosition() - (viewport - 4));
            require(getMaxScroll() == max);
            setScrollAmount(Double.POSITIVE_INFINITY); require(getScrollAmount() == max);
            setScrollAmount(-1); require(getScrollAmount() == 0);
            setScrollAmount(23.75);
            require(getRowTop(3) == y0 + 4 - 23 + 3 * 17 + 5);
            centerScrollOn(children().get(10));
            require(getScrollAmount() == Mth.clamp(10 * 17 + 17 / 2 - viewport / 2, 0, max));
            require(height == savedHeight);
            setScrollAmount(0); ensureVisible(children().get(10));
            require(getScrollAmount() == Mth.clamp(10 * 17 + 4 + 5 + 2 * 17 - viewport, 0, max));
            setScrollAmount(23.75);
            updateScrollingState(x1 - 5, y0 + 10, 0);
            int thumb = Mth.clamp((int)((float)(viewport * viewport) / getMaxPosition()), 32, viewport - 8);
            double expected = Mth.clamp(23.75 + 2.5 * Math.max(1, (double)Math.max(1, max) / (viewport - thumb)), 0, max);
            require(mouseDragged(x1 - 5, (y0 + y1) / 2.0, 0, 0, 2.5));
            require(getScrollAmount() == expected && height == savedHeight);
            require(mouseDragged(x1 - 5, y0 - 1, 0, 0, 2.5) && getScrollAmount() == 0);
            require(mouseDragged(x1 - 5, y1 + 1, 0, 0, 2.5) && getScrollAmount() == max);
            failScroll = true;
            try { mouseDragged(x1 - 5, y1 + 1, 0, 0, 0); throw new AssertionError(); }
            catch (RuntimeException error) { require(error == STOP && height == savedHeight); }
            try { centerScrollOn(children().get(10)); throw new AssertionError(); }
            catch (RuntimeException error) { require(error == STOP && height == savedHeight); }
            failScroll = false;
            disabledDecorations();
            try { enterRender(); throw new AssertionError(); }
            catch (RuntimeException error) { require(error == STOP && height == savedHeight); }
            //? if >=1.21 {
            /*require(seenHeight == viewport);
            require(getX() == x0 && getRight() == x1 && getY() == y0 && getBottom() == y1);
            *///? } else {
            require(seenHeight == savedHeight);
            //? }
            System.out.println(scale + ":" + x0 + ":" + x1 + ":" + y0 + ":" + y1 + ":" + max + ":" + expected);
        }
    }
    public static void main(String[] arguments) {
        for (float scale : new float[]{.5f, .65f, 1, 2}) new ListProbe().verify(scale);
        System.out.println("native bounds, scroll, drag, height restoration and render sentinel passed");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/game classpaths required")
class BoundedSelectionListApiTests(unittest.TestCase):
    def test_native_geometry_and_exception_restoration_without_gl(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs"))); logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_GAME_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            raw = (ROOT / SOURCE).read_bytes()
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for paths in classpaths.values() for path in paths}
            (root / SOURCE).parent.mkdir(parents=True, exist_ok=True); (root / SOURCE).write_bytes(raw)
            (root / "common/src/main/java/BoundsProbe.java").write_text(PROBE)
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
rootProject.name = 'bounded-selection-list-api-probe'
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
            self.assertEqual(0, code, output); observations = {}; results = []
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                source = generated_root / SOURCE.relative_to("common/src/main/java"); probe = generated_root / "BoundsProbe.java"
                for path in (source, probe): (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                destination = root / f"classes-{version}"; cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, source, probe])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/client/gui/widget/BoundedSelectionList.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, output = invoke(f"native-bounds-{version}", [homes[major] / "bin/java", "-Djava.awt.headless=true",
                    "-cp", str(destination) + os.pathsep + cp, "BoundsProbe"])
                self.assertEqual(0, code, output); self.assertIn("native bounds, scroll, drag", output); results.append(output.strip())
                observations[version] = {"generated_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                    "class_major": major + 44, "class_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(), "native_output": output.strip()}
            self.assertEqual(results[0], results[1])
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            self.assertEqual(raw, (ROOT / SOURCE).read_bytes()); self.assertEqual(raw, (root / SOURCE).read_bytes())
            self.assertEqual(metadata, (ROOT / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "bounded-selection-list-api-probe", "qualified": False,
                "source_sha256": hashlib.sha256(raw).hexdigest(), "inputs_sha256": inputs,
                "metadata_sha256": hashlib.sha256(metadata).hexdigest(), "observations": observations, "commands": commands,
                "native_outputs_identical": True, "widgets_connected": False, "GL_client_world_or_gameplay_executed": False}, indent=2) + "\n")
