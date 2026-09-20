"""Opt-in native Authlib 6 policy probe with controlled executors; no HTTP or Minecraft client."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.release.build_matrix import _finish_owned_group

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("common/src/main/java/com/theplumteam/client/SkinProfilePreparation.java")
REQUIRED = ("BLOCKPOPS_TEST_GRADLE", "BLOCKPOPS_TEST_GRADLE_HOME", "BLOCKPOPS_TEST_JAVA17",
            "BLOCKPOPS_TEST_JAVA21", "BLOCKPOPS_TEST_PROFILE_CLASSPATHS")
PROBE = """
import com.mojang.authlib.*;
import com.mojang.authlib.minecraft.*;
import com.mojang.authlib.properties.*;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.theplumteam.client.SkinProfilePreparation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public class SkinPreparationProbe {
    static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID REMOTE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static GameProfile profile(UUID id, String texture) {
        GameProfile profile = new GameProfile(id, "original-name");
        if (texture != null) profile.getProperties().put("textures", new Property("textures", texture));
        profile.getProperties().put("unrelated", new Property("unrelated", "original"));
        return profile;
    }
    static class Fixture {
        final ArrayDeque<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
        final PropertyMap localProperties = new PropertyMap();
        final List<GameProfile> registered = new ArrayList<>();
        String phase;
        int decodeCalls, fetchCalls, failDecode;
        RuntimeException failure = new InsecurePublicKeyException("fixture");
        SignatureState signature = SignatureState.UNSIGNED;
        boolean failFetch, nullTextures;
        ProfileResult answer = new ProfileResult(profile(UUID.randomUUID(), "skin"));
        UUID expectedFetch;
        final MinecraftSessionService session = (MinecraftSessionService) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{MinecraftSessionService.class}, (proxy, method, args) -> {
                require("worker".equals(phase));
                return switch (method.getName()) {
                    case "getTextures" -> {
                        if (++decodeCalls == failDecode) throw failure;
                        yield nullTextures ? null : InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    case "getPackedTextures" -> ((GameProfile) args[0]).getProperties().get("textures")
                        .stream().findFirst().orElse(null);
                    case "unpackTextures" -> {
                        String kind = ((Property) args[0]).value();
                        MinecraftProfileTexture texture = new MinecraftProfileTexture("https://textures.minecraft.net/texture/fixture", Map.of());
                        yield new MinecraftProfileTextures(kind.equals("skin") ? texture : null,
                            kind.equals("cape") ? texture : null, kind.equals("elytra") ? texture : null, signature);
                    }
                    case "fetchProfile" -> {
                        fetchCalls++;
                        require(args[0].equals(expectedFetch) && args[1].equals(Boolean.FALSE));
                        if (failFetch) throw failure;
                        yield answer;
                    }
                    default -> throw new AssertionError(method);
                };
            });
        void schedule(GameProfile target) {
            int calls = decodeCalls + fetchCalls;
            expectedFetch = target.getId();
            SkinProfilePreparation.schedule(session, target, LOCAL, localProperties, worker::add, main::add, value -> {
                require("main".equals(phase) && value == target);
                registered.add(value);
            });
            require(worker.size() == 1 && main.isEmpty() && calls == decodeCalls + fetchCalls);
        }
        void runWorker() {
            phase = "worker";
            try { worker.remove().run(); } finally { phase = null; }
        }
        void finish(GameProfile target) {
            int count = registered.size();
            runWorker();
            require(registered.size() == count && main.size() == 1);
            phase = "main";
            try { main.remove().run(); } finally { phase = null; }
            require(registered.size() == count + 1 && registered.get(count) == target);
        }
        void expectFailure(Class<? extends RuntimeException> type) {
            try { runWorker(); throw new AssertionError(); }
            catch (RuntimeException caught) { require(type.isInstance(caught)); }
            require(main.isEmpty() && registered.isEmpty());
        }
    }
    public static void main(String[] args) {
        for (String kind : List.of("skin", "cape", "elytra")) {
            Fixture f = new Fixture(); GameProfile target = profile(REMOTE, kind);
            if (kind.equals("elytra")) f.signature = SignatureState.INVALID;
            f.schedule(target); f.finish(target);
            require(f.decodeCalls == 1 && f.fetchCalls == 0 && target.getProperties().containsKey("unrelated"));
        }
        Fixture remote = new Fixture(); GameProfile target = profile(REMOTE, null);
        remote.answer = new ProfileResult(new GameProfile(LOCAL, "different-name"));
        remote.answer.profile().getProperties().put("textures", new Property("textures", "skin"));
        remote.schedule(target); remote.finish(target);
        require(remote.fetchCalls == 1 && remote.decodeCalls == 2 && target.getId().equals(REMOTE));
        require(target.getName().equals("original-name") && !target.getProperties().containsKey("unrelated"));
        GameProfile equalIdentity = profile(REMOTE, null);
        remote.schedule(equalIdentity); remote.finish(equalIdentity);
        require(equalIdentity != target && equalIdentity.getProperties().equals(target.getProperties()));
        require(remote.localProperties.isEmpty());
        for (String property : List.of("textures", "unrelated")) {
            Fixture f = new Fixture(); target = profile(LOCAL, null);
            f.localProperties.put(property, new Property(property, "skin"));
            f.schedule(target); f.finish(target);
            require(f.fetchCalls == 0 && f.decodeCalls == 2 && target.getProperties().equals(f.localProperties));
        }
        Fixture cached = new Fixture(); target = profile(LOCAL, null);
        PropertyMap capturedAlias = cached.localProperties;
        cached.schedule(target); cached.finish(target);
        require(cached.fetchCalls == 1 && capturedAlias.containsKey("textures"));
        GameProfile second = profile(LOCAL, null);
        cached.schedule(second); cached.finish(second);
        require(cached.fetchCalls == 1 && second.getProperties().equals(capturedAlias));
        for (UUID id : List.of(LOCAL, REMOTE)) {
            Fixture f = new Fixture(); target = profile(id, null); f.answer = null;
            f.schedule(target); f.finish(target);
            require(f.fetchCalls == 1 && target.getProperties().isEmpty() && f.localProperties.isEmpty());
        }
        Fixture insecureInitial = new Fixture(); target = profile(REMOTE, "skin");
        insecureInitial.failDecode = 1; insecureInitial.schedule(target); insecureInitial.finish(target);
        require(insecureInitial.fetchCalls == 1 && insecureInitial.decodeCalls == 2);
        Fixture insecureRemote = new Fixture(); target = profile(REMOTE, null);
        insecureRemote.failDecode = 2; insecureRemote.schedule(target); insecureRemote.finish(target);
        Fixture insecureLocal = new Fixture(); target = profile(LOCAL, null);
        insecureLocal.failDecode = 2; insecureLocal.schedule(target); insecureLocal.expectFailure(InsecurePublicKeyException.class);
        for (int stage = 0; stage < 3; stage++) {
            Fixture f = new Fixture(); target = profile(REMOTE, null);
            f.failure = new IllegalStateException("unexpected");
            f.failDecode = stage == 0 ? 1 : stage == 2 ? 2 : 0; f.failFetch = stage == 1;
            f.schedule(target); f.expectFailure(IllegalStateException.class);
        }
        Fixture nullDecode = new Fixture(); nullDecode.nullTextures = true;
        nullDecode.schedule(profile(REMOTE, null)); nullDecode.expectFailure(NullPointerException.class);
        Fixture nullProfile = new Fixture(); nullProfile.answer = new ProfileResult(null);
        nullProfile.schedule(profile(REMOTE, null)); nullProfile.expectFailure(NullPointerException.class);
        Fixture rejected = new Fixture();
        try {
            SkinProfilePreparation.schedule(rejected.session, profile(REMOTE, null), LOCAL, rejected.localProperties,
                task -> { throw new RejectedExecutionException(); }, rejected.main::add, rejected.registered::add);
            throw new AssertionError();
        } catch (RejectedExecutionException expected) { require(rejected.fetchCalls == 0 && rejected.main.isEmpty()); }
        System.out.println("NATIVE_AUTHLIB6: texture types, same profile, local alias/cache, false fetch/null, queue order, exact exceptions");
    }
}
"""


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit Gradle/JDK/native Authlib classpaths required")
class SkinProfilePreparationTests(unittest.TestCase):
    def test_native_authlib_policy_and_legacy_empty_class(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = Path(os.environ.get("BLOCKPOPS_TEST_LOGS", str(root / "logs")))
            logs.mkdir(parents=True, exist_ok=True)
            classpaths = {version: [Path(p).resolve() for p in paths] for version, paths in
                          json.loads(os.environ["BLOCKPOPS_TEST_PROFILE_CLASSPATHS"]).items()}
            self.assertEqual({"1.20.1", "1.21.1"}, set(classpaths))
            homes = {major: Path(os.environ[f"BLOCKPOPS_TEST_JAVA{major}"]).resolve() for major in (17, 21)}
            paths = {p for cp in classpaths.values() for p in cp} | {ROOT / SOURCE, Path(__file__).resolve(),
                ROOT / "gradle/verification-metadata.xml"}
            paths.update(home / "bin" / tool for home in homes.values() for tool in ("java", "javac", "javap"))
            inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
            source = (ROOT / SOURCE).read_bytes()
            (root / SOURCE).parent.mkdir(parents=True)
            (root / SOURCE).write_bytes(source)
            (root / "gradle").mkdir()
            metadata = (ROOT / "gradle/verification-metadata.xml").read_bytes()
            (root / "gradle/verification-metadata.xml").write_bytes(metadata)
            (logs / "verification-metadata-used.xml").write_bytes(metadata)
            (root / "settings.gradle").write_text("""
pluginManagement { repositories { maven {
    url = 'https://maven.kikugie.dev/releases'
    mavenContent { releasesOnly() }
    content { includeModule('dev.kikugie.stonecutter', 'dev.kikugie.stonecutter.gradle.plugin'); includeModule('dev.kikugie', 'stonecutter') }
} } }
plugins { id 'dev.kikugie.stonecutter' version '0.7.11' }
rootProject.name = 'skin-profile-preparation-probe'
stonecutter.kotlinController.set(false)
stonecutter.centralScript.set('build.gradle')
stonecutter.create(rootProject, { tree ->
    tree.branch('common') { branch -> branch.version('1.20.1'); branch.version('1.21.1') }
} as org.gradle.api.Action)
""")
            (root / "stonecutter.gradle").write_text("plugins { id 'dev.kikugie.stonecutter' }\nstonecutter.active(null)\n")
            (root / "common/build.gradle").write_text("plugins { id 'java' }\n")
            env = {k: v for k, v in os.environ.items() if k not in
                   {"JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
                   and not k.startswith("ORG_GRADLE_PROJECT_")}
            env["JAVA_HOME"] = str(homes[21])
            commands = {}

            def invoke(name, command):
                commands[name] = list(map(str, command))
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
                generated = root / f"common/versions/{version}/build/generated/stonecutter/main/java/com/theplumteam/client/SkinProfilePreparation.java"
                (logs / f"{version}-{SOURCE.name}").write_bytes(generated.read_bytes())
                destination = root / f"classes-{version}"
                cp = os.pathsep.join(map(str, classpaths[version]))
                code, output = invoke(f"compile-{version}", [homes[major] / "bin/javac", "-proc:none", "--release", major,
                    "-cp", cp, "-d", destination, generated])
                self.assertEqual(0, code, output)
                bytecode = destination / "com/theplumteam/client/SkinProfilePreparation.class"
                self.assertEqual(major + 44, int.from_bytes(bytecode.read_bytes()[6:8], "big"))
                code, output = invoke(f"bytecode-{version}", [homes[major] / "bin/javap", "-p", "-c", bytecode])
                self.assertEqual(0, code, output)
                self.assertEqual(major == 21, "public static void schedule(" in output)
                if major == 17:
                    self.assertNotIn(b"com/mojang/authlib", bytecode.read_bytes())
                if major == 21:
                    probe = root / "SkinPreparationProbe.java"
                    probe.write_text(PROBE)
                    runtime = str(destination) + os.pathsep + cp
                    code, output = invoke("compile-policy-probe", [homes[21] / "bin/javac", "-proc:none", "--release", 21,
                        "-cp", runtime, "-d", destination, probe])
                    self.assertEqual(0, code, output)
                    code, output = invoke("native-policy", [homes[21] / "bin/java", "-cp", runtime, "SkinPreparationProbe"])
                    self.assertEqual(0, code, output); self.assertIn("NATIVE_AUTHLIB6:", output)
                    code, output = invoke("reject-modern-on-authlib4", [homes[21] / "bin/javac", "-proc:none", "--release", 21,
                        "-cp", os.pathsep.join(map(str, classpaths["1.20.1"])), "-d", root / "wrong-api", generated])
                    self.assertNotEqual(0, code); self.assertIn("fetchProfile", output)
                observations[version] = {"generated_sha256": hashlib.sha256(generated.read_bytes()).hexdigest(),
                    "class_sha256": hashlib.sha256(bytecode.read_bytes()).hexdigest(), "legacy_empty_class": major == 17}
            self.assertEqual(inputs, {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in inputs})
            self.assertEqual(source, (root / SOURCE).read_bytes())
            self.assertEqual(metadata, (root / "gradle/verification-metadata.xml").read_bytes())
            (logs / "result.json").write_text(json.dumps({"kind": "skin-profile-preparation-native-api-probe", "qualified": False,
                "inputs_sha256": inputs, "observations": observations, "commands": commands,
                "session_responses_controlled": True, "minecraft_or_http_executed": False}, indent=2) + "\n")
