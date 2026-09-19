"""Opt-in actual Fabric registry probe; neither Knot.init nor a game is launched."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group


ROOT = Path(__file__).resolve().parents[1]
SOURCES = tuple(Path("common/src/main/java/com/theplumteam") / path for path in
                ("network/ModNetworking.java", "network/PacketNetworking.java", "client/ClientPacketNetworking.java"))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_NETWORK_CLASSPATHS",
            "BLOCKPOPS_TEST_BACKEND_CLASSPATH", "BLOCKPOPS_TEST_COMMON_SYMBOLS")
PROBE = """
import com.theplumteam.network.PacketNetworking;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.resources.ResourceLocation;

public class RegistrationProbe {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] arguments) {
        EnvType physical = EnvType.valueOf(arguments[0]);
        new Knot(physical); // Constructor sets the real loader environment; no init/launch.
        boolean server = physical == EnvType.SERVER;
        require(Platform.getEnvironment() == (server ? Env.SERVER : Env.CLIENT));
        require(arguments.length == 6);
        ResourceLocation[] ids = new ResourceLocation[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ResourceLocation.parse(arguments[i + 1]);
            require(PayloadTypeRegistryImpl.PLAY_S2C.get(ids[i]) == null);
        }
        PacketNetworking.registerServerS2CPayloads(ids);
        for (ResourceLocation id : ids)
            require((PayloadTypeRegistryImpl.PLAY_S2C.get(id) != null) == server);
        if (!server) {
            // Exercise initClient's unchanged real API route, without invoking game handlers.
            for (ResourceLocation id : ids)
                NetworkManager.registerReceiver(NetworkManager.s2c(), id, (buffer, context) -> {
                    throw new AssertionError("no game packet should be received by this probe");
                });
        }
        for (ResourceLocation id : ids) require(PayloadTypeRegistryImpl.PLAY_S2C.get(id) != null);
        try {
            NetworkManager.registerS2CPayloadType(ids[0]);
            throw new AssertionError("duplicate type was accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("already registered"));
        }
        System.out.println(physical + ": five types, no route duplication; explicit duplicate rejected");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/real API/backend/symbol paths required")
class ServerPacketRegistrationTests(unittest.TestCase):
    def test_real_registration_routes_and_preprocessed_apis(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_NETWORK_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            backend = [Path(path).resolve() for path in json.loads(os.environ["BLOCKPOPS_TEST_BACKEND_CLASSPATH"])]
            symbols = Path(os.environ["BLOCKPOPS_TEST_COMMON_SYMBOLS"]).resolve()
            symbol_hashes = {str(path.relative_to(symbols)): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in sorted(symbols.rglob("*.class"))}
            self.assertIn("com/theplumteam/BlockPopsMod.class", symbol_hashes)
            self.assertIn("com/theplumteam/network/SyncTokenDataPacket.class", symbol_hashes)
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            registry = sources[SOURCES[0]].decode()
            client = registry.split("public static void initClient()", 1)[1]
            receiver_ids = re.findall(r"NetworkManager\.s2c\(\),\s*(\w+\.ID),", client)
            server_ids = re.search(r"PacketNetworking\.registerServerS2CPayloads\((.*?)\);", registry, re.S)[1]
            self.assertEqual(receiver_ids, [value.strip() for value in server_ids.split(",")])
            self.assertEqual(5, len(set(receiver_ids)))
            namespace_path = ROOT / "common/src/main/java/com/theplumteam/BlockPopsMod.java"
            bindings = {namespace_path: namespace_path.read_bytes()}
            namespace_source = bindings[namespace_path].decode()
            namespace = re.search(r'MOD_ID\s*=\s*"([^"]+)"', namespace_source)[1]
            ids = []
            for field in receiver_ids:
                packet = ROOT / SOURCES[0].parent / (field.split(".")[0] + ".java")
                bindings[packet] = packet.read_bytes()
                value = re.search(r'ID\s*=\s*ResourceLocations\.of\(BlockPopsMod.MOD_ID,\s*"([^"]+)"\)', bindings[packet].decode())[1]
                ids.append(namespace + ":" + value)
            for path, data in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(data)
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
rootProject.name = 's2c-registration-api-probe'
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
                for path in generated:
                    self.assertTrue(path.is_file())
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, [*classpaths[version], symbols]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, *generated])
                self.assertEqual(0, code, output)
                hashes = {}
                for name in ("ModNetworking", "PacketNetworking"):
                    bytecode = destination / f"com/theplumteam/network/{name}.class"
                    self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                    code, disassembly = invoke(f"bytecode-{name}-{version}", [homes[major] / "bin/javap", "-p", "-c", bytecode])
                    self.assertEqual(0, code, disassembly)
                    call = "PacketNetworking.registerServerS2CPayloads:" if name == "ModNetworking" else "NetworkManager.registerS2CPayloadType:"
                    self.assertEqual(1 if major == 21 else 0, disassembly.count(call))
                    hashes[name] = hashlib.sha256(bytecode.read_bytes()).hexdigest()
                observations[version] = {"class_major": major + 44, "class_sha256": hashes,
                    "generated_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in generated}}
            # Baseline mod classes supply compile-time declarations only, never backend execution.
            destination = root / "classes-1.21.1"
            runtime_cp = os.pathsep.join(map(str, [destination, *backend]))
            self.assertNotIn(str(symbols), runtime_cp)
            probe = root / "RegistrationProbe.java"
            probe.write_text(PROBE)
            code, output = invoke("compile-backend-probe", [homes[21] / "bin/javac", "-proc:none", "--release", "21",
                "-cp", runtime_cp, "-d", destination, probe])
            self.assertEqual(0, code, output)
            for physical in ("SERVER", "CLIENT"):
                code, output = invoke(f"backend-{physical}", [homes[21] / "bin/java", "-cp", runtime_cp, "RegistrationProbe", physical, *ids])
                self.assertEqual(0, code, output)
                self.assertIn(physical + ": five types, no route duplication; explicit duplicate rejected", output)
            for path, data in sources.items():
                self.assertEqual(data, (root / path).read_bytes())
                self.assertEqual(data, (ROOT / path).read_bytes())
            for path, data in bindings.items():
                self.assertEqual(data, path.read_bytes())
            self.assertEqual(symbol_hashes, {str(path.relative_to(symbols)): hashlib.sha256(path.read_bytes()).hexdigest()
                                            for path in sorted(symbols.rglob("*.class"))})
            inputs = sorted(set(backend + [path for paths in classpaths.values() for path in paths]))
            (logs / "result.json").write_text(json.dumps({"kind": "s2c-registration-api-and-backend-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(data).hexdigest() for path, data in sources.items()},
                "binding_sources_sha256": {str(path): hashlib.sha256(data).hexdigest() for path, data in bindings.items()},
                "compile_only_mod_symbols": {"path": str(symbols), "classes_sha256": symbol_hashes},
                "inputs": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in inputs],
                "ids": ids, "observations": observations, "backend_environments": ["SERVER", "CLIENT"],
                "game_launched": False, "actual_initClient_invoked": False}, indent=2) + "\n")
