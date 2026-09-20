"""Opt-in real Knot/Mixin transformation; Minecraft is loaded, never initialized or launched."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from scripts.release.build_matrix import _finish_owned_group


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = Path("common/src/main/java/com/theplumteam")
SOURCES = (JAVA_ROOT / "client/LocalProfileProperties.java",
           JAVA_ROOT / "mixin/client/MinecraftProfilePropertiesMixin.java")
CONFIG = Path("common/src/main/resources/blockpops.mixins.json")
REQUIRED = ("BLOCKPOPS_TEST_JAVA17", "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PROFILE_CLASSPATHS")
TRANSFORM = """
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import sun.misc.Unsafe;

public class CaptureTransformProbe {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[0]);
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        require(minecraft.getClassLoader() == loader);
        Class<?> contract = Class.forName("com.theplumteam.client.LocalProfileProperties", false, loader);
        require(contract.isAssignableFrom(minecraft));
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        require(unsafe.shouldBeInitialized(minecraft));
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(".mixin.out/class/net/minecraft/client/Minecraft.class")))
            .accept(node, 0);
        MethodNode capture = node.methods.stream().filter(m -> m.name.endsWith("$captureInitialProfileProperties"))
            .findFirst().orElseThrow();
        List<AbstractInsnNode> body = instructions(capture);
        require(body.stream().map(AbstractInsnNode::getOpcode).toList().equals(List.of(
            Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.RETURN)));
        require(((VarInsnNode) body.get(0)).var == 0 && ((VarInsnNode) body.get(1)).var == 1);
        FieldInsnNode user = (FieldInsnNode) body.get(2), properties = (FieldInsnNode) body.get(3);
        require(user.owner.equals("net/minecraft/client/main/GameConfig") && user.name.equals("user"));
        require(properties.owner.equals("net/minecraft/client/main/GameConfig$UserData"));
        require(properties.name.equals("profileProperties"));
        FieldInsnNode stored = (FieldInsnNode) body.get(4);
        require(stored.owner.equals(node.name));
        var field = node.fields.stream().filter(f -> f.name.equals(stored.name)).findFirst().orElseThrow();
        require((field.access & Opcodes.ACC_STATIC) == 0);
        require(field.desc.equals("Lcom/mojang/authlib/properties/PropertyMap;"));
        MethodNode getter = node.methods.stream().filter(m -> m.name.equals("blockpops$getInitialProfileProperties"))
            .findFirst().orElseThrow();
        List<AbstractInsnNode> reads = instructions(getter);
        require(reads.stream().map(AbstractInsnNode::getOpcode).toList().equals(
            List.of(Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.ARETURN)));
        require(((FieldInsnNode) reads.get(1)).name.equals(stored.name));
        int returns = 0, captures = 0;
        for (MethodNode method : node.methods) if (method.name.equals("<init>")) {
            List<AbstractInsnNode> steps = instructions(method);
            for (int i = 0; i < steps.size(); i++) {
                AbstractInsnNode step = steps.get(i);
                if (step instanceof MethodInsnNode call && call.name.equals(capture.name)) captures++;
                if (step.getOpcode() == Opcodes.RETURN) {
                    returns++;
                    require(i > 0 && steps.get(i - 1) instanceof MethodInsnNode call
                        && call.owner.equals(node.name) && call.name.equals(capture.name));
                }
            }
        }
        require(returns > 0 && captures == returns);
        require(unsafe.shouldBeInitialized(minecraft));
        System.out.println("REAL_TRANSFORM: constructor returns=" + returns + "; instance alias; Minecraft uninitialized");
    }
}
"""
ALIAS = """
import com.mojang.authlib.properties.*;
import com.theplumteam.mixin.client.MinecraftProfilePropertiesMixin;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class CaptureAliasProbe {
    static class Instance extends MinecraftProfilePropertiesMixin { }
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var capture = MinecraftProfilePropertiesMixin.class.getDeclaredMethod(
            "blockpops$captureInitialProfileProperties", GameConfig.class, CallbackInfo.class);
        capture.setAccessible(true);
        Instance previous = null;
        for (boolean populated : new boolean[] {false, true}) {
            PropertyMap original = new PropertyMap();
            if (populated) original.put("textures", new Property("textures", "initial"));
            var data = new GameConfig.UserData(null, new PropertyMap(), original, java.net.Proxy.NO_PROXY);
            var constructor = GameConfig.class.getConstructors()[0];
            Object[] arguments = new Object[constructor.getParameterCount()];
            arguments[0] = data;
            GameConfig config = (GameConfig) constructor.newInstance(arguments);
            require(config.user.profileProperties == original);
            Instance instance = new Instance();
            capture.invoke(instance, config, new CallbackInfo("<init>", false));
            require(instance.blockpops$getInitialProfileProperties() == original);
            require(original.size() == (populated ? 1 : 0));
            if (previous != null) require(previous.blockpops$getInitialProfileProperties() != original);
            original.put("unrelated", new Property("unrelated", "later"));
            require(instance.blockpops$getInitialProfileProperties().containsKey("unrelated"));
            previous = instance;
        }
        System.out.println("REAL_TYPES_ALIAS: empty/populated; two instances; mutations visible; no fetch");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit JDKs and verified real API/loader paths required")
class LocalProfilePropertiesTests(unittest.TestCase):
    def test_real_required_mixin_and_instanced_alias(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(p).resolve() for p in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PROFILE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            inputs = {ROOT / path for path in (*SOURCES, CONFIG, Path("release/release-matrix.json"),
                      Path("gradle/verification-metadata.xml"))}
            inputs.add(Path(__file__).resolve())
            inputs.update(p for paths in classpaths.values() for p in paths)
            inputs.update(home / "bin" / tool for home in homes.values() for tool in ("java", "javac"))
            before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs)}
            matrix = json.loads((ROOT / "release/release-matrix.json").read_text())
            metadata = ET.parse(ROOT / "gradle/verification-metadata.xml").getroot()
            for element in metadata.iter():
                element.tag = element.tag.split("}")[-1]
            config = json.loads((ROOT / CONFIG).read_text())
            self.assertTrue(config["required"])
            self.assertEqual(1, config["injectors"]["defaultRequire"])
            self.assertEqual(["client.MinecraftProfilePropertiesMixin"], config["client"])
            self.assertEqual([], config["mixins"])
            env = {key: value for key, value in os.environ.items() if key not in
                   {"JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
            commands = {}

            def invoke(name, command, cwd):
                commands[name] = list(map(str, command))
                child = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
                try:
                    output, _ = child.communicate(timeout=45)
                finally:
                    _finish_owned_group(child)
                (logs / f"{name}.log").write_text(output)
                return child.returncode, output

            observations = {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                paths = classpaths[version]
                row = next(r for r in matrix["runtimes"] if r["artifact_node"] == f"fabric-{version}")
                loader = next(p for p in paths if p.name == f"fabric-loader-{row['loader_version']}.jar")
                component = metadata.find(f".//component[@group='net.fabricmc'][@name='fabric-loader'][@version='{row['loader_version']}']")
                self.assertIn(before[str(loader)], [s.attrib["value"] for s in component.findall(f"artifact[@name='{loader.name}']/sha256")])
                with zipfile.ZipFile(loader) as archive:
                    libraries = json.loads(archive.read("fabric-installer.json"))["libraries"]["common"]
                for library in libraries:
                    _, artifact, revision = library["name"].split(":")
                    dependency = next(p for p in paths if p.name == f"{artifact}-{revision}.jar")
                    self.assertEqual(library["sha256"], before[str(dependency)])
                work = root / version
                work.mkdir()
                classes = work / "classes"
                classes.mkdir()
                probes = [work / f"{name}.java" for name in ("CaptureTransformProbe", "CaptureAliasProbe")]
                for path, source in zip(probes, (TRANSFORM, ALIAS)):
                    path.write_text(source)
                cp = os.pathsep.join(map(str, paths))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", major,
                    "-cp", cp, "-d", classes, *(ROOT / p for p in SOURCES), *probes], work)
                self.assertEqual(0, code, output)
                shutil.copyfile(ROOT / CONFIG, classes / CONFIG.name)
                (classes / "fabric.mod.json").write_text(json.dumps({"schemaVersion": 1, "id": "capture_probe",
                    "version": "1", "environment": "client", "mixins": [CONFIG.name]}))
                command = [homes[major] / "bin/java", "-Djava.awt.headless=true", "-Dfabric.development=true",
                    "-Dmixin.debug.export=true", "-cp", str(classes) + os.pathsep + cp]
                for probe, marker in (("CaptureTransformProbe", "REAL_TRANSFORM:"), ("CaptureAliasProbe", "REAL_TYPES_ALIAS:")):
                    code, output = invoke(f"{probe}-{version}", [*command, probe], work)
                    self.assertEqual(0, code, output)
                    self.assertIn(marker, output)
                transformed = work / ".mixin.out/class/net/minecraft/client/Minecraft.class"
                shutil.copyfile(transformed, logs / f"Minecraft-{version}.class")
                observations[version] = {"loader": row["loader_version"], "java_major": major,
                    "transformed_sha256": hashlib.sha256(transformed.read_bytes()).hexdigest(),
                    "minecraft_initialized": False, "game_launched": False}
                # A required injection with no native target must fail in the actual transformer.
                broken = work / "MinecraftProfilePropertiesMixin.java"
                broken.write_text((ROOT / SOURCES[1]).read_text().replace('method = "<init>"', 'method = "blockpops$missingTarget"'))
                code, output = invoke(f"compile-negative-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", major,
                    "-cp", str(classes) + os.pathsep + cp, "-d", classes, broken], work)
                self.assertEqual(0, code, output)
                code, output = invoke(f"required-negative-{version}", [*command, "CaptureTransformProbe"], work)
                self.assertNotEqual(0, code, output)
                self.assertIn("InvalidInjectionException", output)
                self.assertIn("blockpops$missingTarget", output)
            self.assertEqual(before, {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs)})
            (logs / "result.json").write_text(json.dumps({"inputs_sha256": before, "observations": observations,
                "commands": commands, "qualified": False, "network_or_rendering_tested": False}, indent=2) + "\n")
