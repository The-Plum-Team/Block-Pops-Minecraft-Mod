"""Opt-in native Authlib probe: proxy session services never perform HTTP."""

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
PACKAGE = Path("common/src/main/java/com/theplumteam")
SOURCES = tuple(PACKAGE / name for name in ("network/DropBoxPacket.java", "network/UnlockCollectionPacket.java",
    "command/GetBoxCommand.java", "util/AuthlibProfiles.java"))
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_GAME_CLASSPATHS")
PROBE = """
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.theplumteam.util.AuthlibProfiles;
import java.lang.reflect.Proxy;
import java.util.UUID;
//? if >=1.21 {
/*import com.mojang.authlib.yggdrasil.ProfileResult;
*///? }
public class ProfileProbe {
    static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    static final String NAME = "input-name";
    static GameProfile answer = new GameProfile(ID, "server-name");
    static RuntimeException failure;
    static int calls;
    static final LogCapture LOGGER = new LogCapture();
    static class LogCapture {
        int count;
        void error(String message, Object name, Object detail) {
            require(message.equals("Failed to fetch fresh GameProfile for {}: {}"));
            require(name.equals(NAME) && detail.equals("fixture failure"));
            count++;
        }
    }
    interface Caller { GameProfile fetch(UUID id, String name, MinecraftSessionService session); }
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    __CALLER_METHODS__
    public static void main(String[] arguments) {
        MinecraftSessionService service = (MinecraftSessionService) Proxy.newProxyInstance(
            ProfileProbe.class.getClassLoader(), new Class<?>[]{MinecraftSessionService.class}, (proxy, method, args) -> {
                calls++;
                require(args.length == 2 && args[1].equals(Boolean.TRUE));
                //? if >=1.21 {
                /*require(method.getName().equals("fetchProfile") && args[0].equals(ID));
                *///? } else {
                require(method.getName().equals("fillProfileProperties"));
                GameProfile input = (GameProfile) args[0];
                require(input.getId().equals(ID) && input.getName().equals(NAME));
                //? }
                if (failure != null) throw failure;
                //? if >=1.21 {
                /*return answer == null ? null : new ProfileResult(answer);
                *///? } else {
                return answer;
                //? }
            });
        Caller[] callers = {ProfileProbe::DropBoxPacket, ProfileProbe::UnlockCollectionPacket, ProfileProbe::GetBoxCommand};
        for (Caller caller : callers) {
            calls = 0; LOGGER.count = 0;
            require(caller.fetch(null, NAME, null) == null && calls == 0 && LOGGER.count == 0);
            answer = new GameProfile(ID, "server-name");
            require(caller.fetch(ID, NAME, service) == answer && calls == 1 && LOGGER.count == 0);
            answer = null;
            require(caller.fetch(ID, NAME, service) == null && calls == 2 && LOGGER.count == 0);
            failure = new IllegalStateException("fixture failure");
            require(caller.fetch(ID, NAME, service) == null && calls == 3 && LOGGER.count == 1);
            try { AuthlibProfiles.fetch(service, new GameProfile(ID, NAME)); throw new AssertionError(); }
            catch (RuntimeException caught) { require(caught == failure); }
            failure = null;
        }
        for (String raw : new String[]{"signed-base64-fixture\\nraw", ""}) {
            Property property = new Property("textures", raw, "signature-bytes");
            require(AuthlibProfiles.value(property).equals(raw));
            //? if >=1.21 {
            /*require(property.signature().equals("signature-bytes"));
            *///? } else {
            require(property.getSignature().equals("signature-bytes"));
            //? }
        }
        require(AuthlibProfiles.value(new Property("other", "unsigned-value")).equals("unsigned-value"));
        System.out.println("real Authlib: three callers preserve UUID/name/true, nullable result, errors and raw property");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/Authlib classpaths required")
class AuthlibProfilesApiTests(unittest.TestCase):
    def test_native_authlib_and_complete_consumer_preprocessing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(path).resolve() for path in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_GAME_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            sources = {path: (ROOT / path).read_bytes() for path in SOURCES}
            inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                      for paths in classpaths.values() for path in paths}
            methods = []
            for path, raw in sources.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(raw)
                if path == SOURCES[-1]: continue
                body = re.search(r"private static GameProfile getFreshGameProfile[^\{]*\{(.*?)^    }",
                                 raw.decode().replace("\r\n", "\n"), re.S | re.M).group(1)
                # Execute the actual consumer's null/try/catch/log body with its
                # player/figure access supplied as parameters; no fake Authlib.
                for old, new in (("figure.getPlayerUUID()", "id"), ("figure.getName()", "name"),
                                 ("player.getServer().getSessionService()", "session")):
                    self.assertIn(old, body); body = body.replace(old, new)
                methods.append(f"static GameProfile {path.stem}(UUID id, String name, MinecraftSessionService session) {{" + body + "}")
            probe = PROBE.replace("__CALLER_METHODS__", "\n".join(methods))
            (root / "common/src/main/java/ProfileProbe.java").write_text(probe)
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
rootProject.name = 'authlib-profiles-api-probe'
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
                try: output, _ = child.communicate(timeout=90)
                finally: _finish_owned_group(child)
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
                for path, source in zip(generated, SOURCES, strict=True):
                    (logs / f"{version}-{path.name}").write_bytes(path.read_bytes())
                    if source != SOURCES[-1]:
                        self.assertEqual(sources[source].decode().replace("\r\n", "\n"), path.read_text())
                probe = generated_root / "ProfileProbe.java"
                (logs / f"{version}-ProfileProbe.java").write_bytes(probe.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", str(major),
                    "-cp", cp, "-d", destination, generated[-1], probe])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/util/AuthlibProfiles.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, output = invoke(f"real-authlib-{version}", [homes[major] / "bin/java",
                    "-cp", str(destination) + os.pathsep + cp, "ProfileProbe"])
                self.assertEqual(0, code, output); self.assertIn("real Authlib:", output)
                observations[version] = {"generated_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in generated},
                    "helper_class_major": major + 44, "runtime_result": output.strip()}
                other = "1.21.1" if major == 17 else "1.20.1"
                code, output = invoke(f"reject-{version}-on-{other}", [homes[21] / "bin/javac", "-proc:none", "--release", "21",
                    "-cp", os.pathsep.join(map(str, classpaths[other])), "-d", root / f"wrong-{version}", generated[-1]])
                self.assertNotEqual(0, code); self.assertIn("cannot find symbol", output)
            self.assertEqual(inputs, {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in inputs})
            for path, raw in sources.items():
                self.assertEqual(raw, (ROOT / path).read_bytes()); self.assertEqual(raw, (root / path).read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "authlib-profiles-api-probe", "qualified": False,
                "sources_sha256": {str(path): hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
                "inputs_sha256": inputs, "metadata_sha256": hashlib.sha256(metadata).hexdigest(),
                "observations": observations, "commands": commands, "complete_consumers_compiled": False,
                "actual_consumer_profile_bodies_compiled": True, "http_or_gameplay_executed": False}, indent=2) + "\n")
