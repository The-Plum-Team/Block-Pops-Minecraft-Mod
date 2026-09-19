"""Opt-in real API, disconnected client and server class-loading probes."""

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
SOURCES = (Path("common/src/main/java/com/theplumteam/network/PacketNetworking.java"),
           Path("common/src/main/java/com/theplumteam/client/ClientPacketNetworking.java"))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_NETWORK_CLASSPATHS")
NULL_PROBE = """
package com.theplumteam.client;
public class DisconnectedProbe {
    public static void main(String[] arguments) {
        int[] calls = {0};
        try {
            ClientPacketNetworking.sendToServer(null, null, () -> {
                calls[0]++;
                throw new AssertionError("encoder must not allocate a buffer while disconnected");
            });
            throw new AssertionError("missing connection was accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("Unable to send packet to the server while not in game!"))
                throw new AssertionError(expected);
        }
        if (calls[0] != 0) throw new AssertionError("encoder called while disconnected");
        System.out.println("disconnected: encoder calls=0; no payload allocated");
    }
}
"""
LOAD_PROBE = """
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
public class GatewayLoadProbe {
    public static void main(String[] arguments) throws Exception {
        URL[] urls = Arrays.stream(arguments).map(path -> {
            try { return Path.of(path).toUri().toURL(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("com.theplumteam.client."))
                    throw new AssertionError("common gateway resolved a client class: " + name);
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> gateway = Class.forName("com.theplumteam.network.PacketNetworking", true, loader);
            if (gateway.getClassLoader() != loader || gateway.getDeclaredMethods().length < 2)
                throw new AssertionError("common gateway was not verified by the filtering loader");
            System.out.println("common gateway loads and resolves signatures without client classes");
        }
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/real API classpaths required")
class ClientPacketNetworkingApiTests(unittest.TestCase):
    def test_real_preprocessed_gateway_and_disconnected_sender(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_NETWORK_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
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
rootProject.name = 'c2s-networking-api-probe'
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
            observations, generated_clients = {}, {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated_root = root / f"common/versions/{version}/build/generated/stonecutter/main/java"
                generated = [generated_root / path.relative_to("common/src/main/java") for path in SOURCES]
                generated_clients[version] = generated[1]
                for path in generated:
                    self.assertTrue(path.is_file())
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, *generated])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/client/ClientPacketNetworking.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, disassembly = invoke(f"bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c", bytecode])
                self.assertEqual(0, code, disassembly)
                self.assertEqual(1, disassembly.count("Minecraft.getConnection:"))
                self.assertIn("NetworkManager.collectPackets:", disassembly)
                for forbidden in ("Minecraft.level:", "RegistryAccess.EMPTY", ".retain:", ".release:"):
                    self.assertNotIn(forbidden, disassembly)
                self.assertEqual(major == 21, 'RegistryFriendlyByteBuf."<init>"' in disassembly)
                if major == 21:
                    self.assertLess(disassembly.index("ClientPacketListener.registryAccess:"), disassembly.index("Supplier.get:"))
                for name, java, main in (("DisconnectedProbe", NULL_PROBE, "com.theplumteam.client.DisconnectedProbe"),
                                         ("GatewayLoadProbe", LOAD_PROBE, "GatewayLoadProbe")):
                    probe = root / f"{name}.java"
                    probe.write_text(java)
                    runtime_cp = str(destination) + os.pathsep + cp
                    code, output = invoke(f"compile-{name}-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                        "-cp", runtime_cp, "-d", destination, probe])
                    self.assertEqual(0, code, output)
                    command = [homes[major] / "bin/java", "-cp", runtime_cp, main]
                    if name == "GatewayLoadProbe":
                        command.extend([destination, *classpaths[version]])
                    code, output = invoke(f"{name}-{version}", command)
                    self.assertEqual(0, code, output)
                observations[version] = {"jdk_home": str(homes[major]), "class_major": major + 44,
                    "inputs": [{"kind": ("minecraft-mojang-mapped" if index == 0 else
                                          "architectury-remapped" if index == 1 else "mojang-library"),
                                "path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                               for index, path in enumerate(classpaths[version])],
                    "generated_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in generated},
                    "helper_bytecode_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(),
                    "disconnected_encoder_calls": 0, "common_gateway_loaded_without_client_classes": True}
            for version, major, other in (("1.20.1", 21, "1.21.1"), ("1.21.1", 17, "1.20.1")):
                code, output = invoke(f"reject-crossed-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", os.pathsep.join(map(str, classpaths[other])), "-d", root / f"wrong-{version}", generated_clients[version]])
                self.assertNotEqual(0, code, "wrong API unexpectedly accepted the client branch")
                self.assertIn("cannot be converted to RegistryFriendlyByteBuf" if version == "1.20.1" else "cannot find symbol", output)
            code, output = invoke("compile-unprocessed-legacy", [homes[17] / "bin/javac", "-proc:none", "--release", "17",
                "-cp", os.pathsep.join(map(str, classpaths["1.20.1"])), "-d", root / "raw-legacy", *[root / path for path in SOURCES]])
            self.assertEqual(0, code, output)
            for path, data in sources.items():
                self.assertEqual(data, (root / path).read_bytes())
                self.assertEqual(data, (ROOT / path).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "c2s-api-and-lifecycle-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(data).hexdigest() for path, data in sources.items()},
                "observations": observations}, indent=2) + "\n")
