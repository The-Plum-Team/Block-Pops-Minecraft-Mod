"""Opt-in real API/byte-buffer probes, without a game client or network sends."""

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
HELPER = Path("common/src/main/java/com/theplumteam/network/PacketNetworking.java")
CLIENT_HELPER = Path("common/src/main/java/com/theplumteam/client/ClientPacketNetworking.java")
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_NETWORK_CLASSPATHS")
BUFFER_PROBE = """
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
REGISTRY_IMPORTS
import java.util.Arrays;
import java.util.HexFormat;

public class BufferProbe {
    enum Token { REGULAR, GUARANTEED }
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] arguments) {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        payload.writeByte(0x55);
        payload.writeInt(-1234);
        payload.writeLong(Long.MAX_VALUE);
        payload.writeBoolean(true);
        payload.writeUtf("año 🧱");
        payload.writeBlockPos(new BlockPos(-30, -64, 2048));
        payload.writeDouble(0.125);
        payload.writeEnum(Token.GUARANTEED);
        payload.writeBoolean(false);
        payload.writeUtf("x".repeat(32768), 1048576);
        payload.readerIndex(1);
        byte[] bytes = ByteBufUtil.getBytes(payload);
        int reader = payload.readerIndex(), writer = payload.writerIndex(), refs = payload.refCnt();
        FriendlyByteBuf outgoing = BUFFER_EXPRESSION;
        REGISTRY_ASSERTION
        require(Arrays.equals(bytes, ByteBufUtil.getBytes(outgoing)));
        require(outgoing.readerIndex() == reader && outgoing.writerIndex() == writer);
        require(outgoing.refCnt() == refs && refs == 1);
        require(outgoing.readInt() == -1234);
        require(outgoing.readLong() == Long.MAX_VALUE);
        require(outgoing.readBoolean());
        require(outgoing.readUtf().equals("año 🧱"));
        require(outgoing.readBlockPos().equals(new BlockPos(-30, -64, 2048)));
        require(outgoing.readDouble() == 0.125);
        require(outgoing.readEnum(Token.class) == Token.GUARANTEED);
        require(!outgoing.readBoolean());
        require(outgoing.readUtf(1048576).equals("x".repeat(32768)));
        require(!outgoing.isReadable() && payload.readerIndex() == payload.writerIndex());
        require(outgoing.release() && payload.refCnt() == 0);
        FriendlyByteBuf tooLong = new FriendlyByteBuf(Unpooled.buffer());
        try {
            tooLong.writeUtf("four", 3);
            throw new AssertionError("UTF limit was not enforced");
        } catch (io.netty.handler.codec.EncoderException expected) {
        } finally { tooLong.release(); }
        System.out.println(HexFormat.of().formatHex(bytes));
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/real API classpaths required")
class PacketNetworkingApiTests(unittest.TestCase):
    def test_real_preprocessed_sender_and_buffer_wire_compatibility(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_NETWORK_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            source = (ROOT / HELPER).read_bytes()
            client_source = (ROOT / CLIENT_HELPER).read_bytes()
            for path, data in ((HELPER, source), (CLIENT_HELPER, client_source)):
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
rootProject.name = 's2c-networking-api-probe'
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
            observations, wires, generated_sources = {}, {}, {}
            for version, major in (("1.20.1", 17), ("1.21.1", 21)):
                generated = root / f"common/versions/{version}/build/generated/stonecutter/main/java/com/theplumteam/network/PacketNetworking.java"
                generated_client = generated.parent.parent / "client/ClientPacketNetworking.java"
                generated_sources[version] = [generated, generated_client]
                self.assertTrue(generated.is_file())
                (logs / f"PacketNetworking-{version}.java").write_bytes(generated.read_bytes())
                (logs / f"ClientPacketNetworking-{version}.java").write_bytes(generated_client.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, generated, generated_client])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/network/PacketNetworking.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, disassembly = invoke(f"bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c", bytecode])
                self.assertEqual(0, code, disassembly)
                self.assertNotIn("net/minecraft/client/", disassembly)
                self.assertIn("NetworkManager.sendToPlayer", disassembly)
                self.assertEqual(major == 21, 'RegistryFriendlyByteBuf."<init>"' in disassembly)
                self.assertEqual(major == 21, "ServerPlayer.registryAccess" in disassembly)
                probe = BUFFER_PROBE.replace("REGISTRY_IMPORTS", "" if major == 17 else
                    "import net.minecraft.core.RegistryAccess;\nimport net.minecraft.network.RegistryFriendlyByteBuf;")
                probe = probe.replace("BUFFER_EXPRESSION", "payload" if major == 17 else
                    "new RegistryFriendlyByteBuf(payload, RegistryAccess.EMPTY)")
                probe = probe.replace("REGISTRY_ASSERTION", "" if major == 17 else
                    "require(((RegistryFriendlyByteBuf) outgoing).registryAccess() == RegistryAccess.EMPTY);")
                probe_path = root / "BufferProbe.java"
                probe_path.write_text(probe)
                code, output = invoke(f"compile-buffer-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, probe_path])
                self.assertEqual(0, code, output)
                code, output = invoke(f"buffer-{version}", [homes[major] / "bin/java", "-cp",
                    str(destination) + os.pathsep + cp, "BufferProbe"])
                self.assertEqual(0, code, output)
                wires[version] = bytes.fromhex(output.strip().splitlines()[-1])
                observations[version] = {"jdk_home": str(homes[major]), "class_major": major + 44,
                    "inputs": [{"kind": ("minecraft-mojang-mapped" if index == 0 else
                                          "architectury-remapped" if index == 1 else "mojang-library"),
                                "path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                               for index, path in enumerate(classpaths[version])],
                    "generated_sha256": hashlib.sha256(generated.read_bytes()).hexdigest(),
                    "helper_bytecode_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(),
                    "wire_sha256": hashlib.sha256(wires[version]).hexdigest()}
            self.assertEqual(wires["1.20.1"], wires["1.21.1"])
            for version, major, other in (("1.20.1", 21, "1.21.1"), ("1.21.1", 17, "1.20.1")):
                code, output = invoke(f"reject-crossed-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", os.pathsep.join(map(str, classpaths[other])), "-d", root / f"wrong-{version}", *generated_sources[version]])
                self.assertNotEqual(0, code, "wrong API unexpectedly accepted the branch")
                self.assertIn("cannot be converted to RegistryFriendlyByteBuf" if version == "1.20.1" else "cannot find symbol", output)
            code, output = invoke("compile-unprocessed-legacy", [homes[17] / "bin/javac", "-proc:none", "--release", "17",
                "-cp", os.pathsep.join(map(str, classpaths["1.20.1"])), "-d", root / "raw-legacy", root / HELPER, root / CLIENT_HELPER])
            self.assertEqual(0, code, output)
            self.assertEqual(source, (root / HELPER).read_bytes())
            self.assertEqual(source, (ROOT / HELPER).read_bytes())
            self.assertEqual(client_source, (root / CLIENT_HELPER).read_bytes())
            self.assertEqual(client_source, (ROOT / CLIENT_HELPER).read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "api-and-buffer-compatibility-probe", "qualified": False,
                "source_sha256": hashlib.sha256(source).hexdigest(), "client_source_sha256": hashlib.sha256(client_source).hexdigest(),
                "observations": observations}, indent=2) + "\n")
