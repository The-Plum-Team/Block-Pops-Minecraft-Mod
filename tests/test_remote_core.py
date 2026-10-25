"""Opt-in compilation of the Custom collections' pure remote core, run against hostile inputs.

``com.theplumteam.remote`` needs only the JDK and Gson, so it is compiled and exercised here
without a game: strict JSON, the path allowlist, the verified store's digest, size and PNG
checks, and the manifest kept for when the CDN cannot be reached. The packaged E2E covers the
same code end to end (e2e/custom_fixture.py) but only the paths a valid collection takes.

Set ``BLOCKPOPS_TEST_JAVA17`` to a JDK 17 or later and ``BLOCKPOPS_TEST_GSON`` to a Gson JAR.
"""

from __future__ import annotations

import os
import struct
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "common/src/main/java"
SOURCES = (*sorted((MAIN / "com/theplumteam/remote").glob("*.java")),
           MAIN / "com/theplumteam/util/E2EDeterminism.java")
REQUIRED = ("BLOCKPOPS_TEST_JAVA17", "BLOCKPOPS_TEST_GSON")

PROBE = r"""
import com.theplumteam.remote.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class RemoteCoreProbe {
    interface Action { void run() throws Exception; }

    static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static void refused(String what, Action action) {
        try {
            action.run();
        } catch (RemoteException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError(what + " failed with " + other);
        }
        throw new AssertionError(what + " was accepted");
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // A 1x1 PNG the test writes with valid chunk checksums
    static final byte[] PNG = HexFormat.of().parseHex("__PNG__");

    public static void main(String[] args) throws Exception {
        int checks = 0;
        // Strict JSON
        require(StrictJson.parseObject(utf8("{\"a\":{\"b\":[1,2]}}"), 1024).has("a"), "valid object"); checks++;
        refused("a duplicate key", () -> StrictJson.parseObject(utf8("{\"a\":1,\"a\":2}"), 1024)); checks++;
        refused("a nested duplicate key", () -> StrictJson.parseObject(utf8("{\"a\":{\"b\":1,\"b\":1}}"), 1024)); checks++;
        refused("NaN", () -> StrictJson.parseObject(utf8("{\"a\":NaN}"), 1024)); checks++;
        refused("a comment", () -> StrictJson.parseObject(utf8("{/*x*/\"a\":1}"), 1024)); checks++;
        refused("data after the object", () -> StrictJson.parseObject(utf8("{} {}"), 1024)); checks++;
        refused("a byte order mark", () -> StrictJson.parseObject(utf8("﻿{}"), 1024)); checks++;
        refused("invalid UTF-8", () -> StrictJson.parseObject(new byte[] {'{', '"', (byte) 0xff, '"', ':', '1', '}'}, 1024)); checks++;
        refused("a document over its limit", () -> StrictJson.parseObject(utf8("{\"a\":\"" + "x".repeat(64) + "\"}"), 16)); checks++;

        // The path allowlist
        String box = "assets/blockpops/textures/block/box/e2e_fixture.png";
        require(RemotePaths.isAllowedAsset("e2e_fixture", box), "a collection's own box"); checks++;
        require(!RemotePaths.isAllowedAsset("other", box), "another collection's box"); checks++;
        require(!RemotePaths.isAllowedAsset("e2e_fixture", "assets/minecraft/textures/block/stone.png"), "vanilla's files"); checks++;
        require(!RemotePaths.isAllowedAsset("e2e_fixture",
                "assets/blockpops/geckolib/animations/figure/figure_poses_e2e_fixtureother.animation.json"),
                "poses of a collection whose id only starts with this one"); checks++;
        require(RemotePaths.isAllowedAsset("e2e_fixture",
                "assets/blockpops/geckolib/animations/figure/figure_poses_e2e_fixture_goku.animation.json"),
                "a figure's own poses"); checks++;
        for (String path : new String[] {"../manifest.json", "a/../b.json", "/etc/passwd", "a//b.png", ".hidden",
                "con.png", "A.png", "a\\b.png"}) {
            require(!RemotePaths.isSafeRelative(path), "unsafe path " + path); checks++;
        }
        require(!RemotePaths.isValidCollectionId("world_players"), "a reserved id"); checks++;
        require(!RemotePaths.isValidCollectionId("Bad-Id"), "a malformed id"); checks++;

        // The verified store
        Path root = Files.createTempDirectory("verified-store");
        VerifiedStore store = new VerifiedStore(root);
        ManifestEntry entry = new ManifestEntry(box, sha256(PNG));
        refused("bytes that do not match the manifest", () -> store.fetch((path, max) -> utf8("not the PNG"), entry)); checks++;
        require(store.cached(entry).isEmpty(), "nothing kept after a mismatch"); checks++;
        byte[] notPng = utf8("not a PNG at all");
        refused("a texture that is not a PNG",
                () -> store.fetch((path, max) -> notPng, new ManifestEntry(box, sha256(notPng)))); checks++;
        byte[] oversized = new byte[(int) RemoteLimits.PNG_BYTES + 1];
        refused("a file over its limit",
                () -> store.fetch((path, max) -> oversized, new ManifestEntry(box, sha256(oversized)))); checks++;
        Path kept = store.fetch((path, max) -> PNG, entry);
        require(kept.startsWith(root) && store.cached(entry).isPresent(), "a verified file is kept"); checks++;
        Files.write(kept, utf8("tampered"));
        require(store.cached(entry).isEmpty(), "a tampered file is not trusted"); checks++;

        // The manifest kept for when the CDN cannot be reached
        RemoteSource unreachable = (path, max) -> { throw new IOException("offline"); };
        try {
            new RemoteCollections(unreachable, new VerifiedStore(Files.createTempDirectory("empty"))).manifest();
            throw new AssertionError("a manifest came from nowhere");
        } catch (IOException expected) {
            checks++;
        }
        byte[] manifest = utf8("{\"version\":1,\"collections\":[{\"id\":\"e2e_fixture\",\"name\":\"E2E\"}],"
                + "\"files\":[{\"path\":\"data/blockpops/collections/e2e_fixture.json\",\"sha256\":\""
                + "0".repeat(64) + "\"}]}");
        new RemoteCollections((path, max) -> manifest, store).manifest();
        Manifest fallback = new RemoteCollections(unreachable, store).manifest();
        require(fallback.collectionIds().contains("e2e_fixture"), "the kept manifest when offline"); checks++;
        refused("a manifest listing a path twice", () -> Manifest.parse(utf8("{\"version\":1,\"collections\":[],"
                + "\"files\":[{\"path\":\"manifest.json\",\"sha256\":\"" + "0".repeat(64) + "\"},"
                + "{\"path\":\"manifest.json\",\"sha256\":\"" + "0".repeat(64) + "\"}]}"))); checks++;
        refused("a manifest with an unknown key", () -> Manifest.parse(utf8("{\"version\":1,\"collections\":[],"
                + "\"files\":[],\"extra\":true}"))); checks++;

        // Outside the packaged E2E the game reads the CDN; the switch is off here
        require(RemoteSource.forGame() instanceof CdnSource, "the game's source is the CDN"); checks++;
        refused("the E2E source", () -> RemoteSource.NONE.fetch("manifest.json", 1024)); checks++;
        System.out.println("remote core checks passed: " + checks);
    }
}
"""


def tiny_png() -> bytes:
    """A 1x1 greyscale PNG with valid chunk checksums."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"\x00\x80"))
            + chunk(b"IEND", b""))


@unittest.skipUnless(all(os.environ.get(key) for key in REQUIRED), "explicit JDK and Gson inputs required")
class RemoteCoreTests(unittest.TestCase):
    def test_the_remote_core_refuses_hostile_inputs(self) -> None:
        javac = Path(os.environ["BLOCKPOPS_TEST_JAVA17"]) / "bin" / "javac"
        java = Path(os.environ["BLOCKPOPS_TEST_JAVA17"]) / "bin" / "java"
        gson = os.environ["BLOCKPOPS_TEST_GSON"]
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            probe = work / "RemoteCoreProbe.java"
            probe.write_text(PROBE.replace("__PNG__", tiny_png().hex()), encoding="utf-8")
            classes = work / "classes"
            compiled = subprocess.run(
                [str(javac), "-proc:none", "-nowarn", "-cp", gson, "-d", str(classes),
                 *map(str, SOURCES), str(probe)],
                capture_output=True, text=True, timeout=300, check=False)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            ran = subprocess.run(
                [str(java), "-cp", f"{classes}{os.pathsep}{gson}", "RemoteCoreProbe"],
                capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(0, ran.returncode, ran.stdout + ran.stderr)
            self.assertIn("remote core checks passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
