package com.theplumteam.remote.player;

import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.ModelBounds;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The names, paths and signed documents of player collections: one strict spelling each. */
class PlayerDocumentsTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String CODE = "maple-orbit-42";
    private static final String SHA = JdkSha256.of("a".getBytes(StandardCharsets.UTF_8));
    private static final String SHA2 = JdkSha256.of("b".getBytes(StandardCharsets.UTF_8));
    private static final long NOW = 1_794_050_000L;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject json(byte[] payload) throws RemoteException {
        return StrictJson.parseObject(payload, 1 << 20);
    }

    private interface Edit {
        void apply(JsonObject json);
    }

    // The payload with one thing changed, written the way a signer would write it
    private static byte[] edited(byte[] payload, Edit edit) throws RemoteException {
        JsonObject json = json(payload);
        edit.apply(json);
        return utf8(JsonOut.write(json));
    }

    @Test
    void aPlayerIdAndAPlayerCodeHaveOneShapeEach() throws RemoteException {
        assertTrue(PlayerIds.isPlayerId(ID));
        assertTrue(PlayerIds.isPlayerId("p_abcdefghijkl"));
        assertTrue(PlayerIds.isPlayerId("p_234567234567"));
        for (String bad : new String[] {null, "", "p_", "p_k3v7q2m5x4a", "p_k3v7q2m5x4abc", "P_k3v9q2m7x4ab",
            "p-k3v9q2m7x4ab", "p_k3v7q2m5x4a1", "p_k3v7q2m5x4a8", "p_k3v7q2m5x4aB", "w-k3v9q2m7x4ab", "e2e_fixture",
            "p_k3v7q2m5x4aé"}) {
            assertFalse(PlayerIds.isPlayerId(bad), String.valueOf(bad));
        }
        // It has the shape of a Custom id, and the official tree refuses the whole reserved space
        assertFalse(RemotePaths.isValidCollectionId(ID));
        assertFalse(RemotePaths.isValidCollectionId("p_anything"));
        assertFalse(RemotePaths.isValidCollectionId("p_"));
        assertTrue(RemotePaths.isValidCollectionId("willow_media"));
        assertTrue(RemotePaths.isValidCollectionId("pokemon"));
        assertTrue(RemotePaths.isValidCollectionId("p2_x"));
        assertThrows(RemoteException.class, () -> com.theplumteam.remote.CodeEntry.parse(
                ("{\"id\":\"" + ID + "\",\"name\":\"Mine\"}").getBytes(StandardCharsets.UTF_8)));
        assertEquals("willow_media", com.theplumteam.remote.CodeEntry.parse(
                "{\"id\":\"willow_media\",\"name\":\"WillowMedia\"}".getBytes(StandardCharsets.UTF_8)).id());
        assertTrue(PlayerIds.isReserved("p_anything"));
        assertFalse(PlayerIds.isReserved("willow_media"));

        for (String good : new String[] {CODE, "abc-def-10", "abcdefgh-abcdefgh-99"}) {
            assertTrue(PlayerIds.isPlayerCode(good), good);
            assertEquals(good, RemotePaths.normalizeCode(good));
        }
        // Official codes, and every near miss of the player shape
        for (String bad : new String[] {null, "", "willow-media", "willow-media-2", "maple-orbit-09", "maple-orbit-4",
            "maple-orbit-420", "ma-orbit-42", "maple-or-42", "maplemaple-orbit-42", "maple-orbitorbit-42",
            "Maple-orbit-42", "maple-orbit-4a", "maple_orbit-42", "maple-orbit--42", "maple-orbit-42-", "-maple-orbit-42",
            "maple-orb1t-42", "maple-orbit-42 ", "maple-orbit-٤٢"}) {
            assertFalse(PlayerIds.isPlayerCode(bad), String.valueOf(bad));
        }
    }

    @Test
    void everyPathIsComputedFromCheckedParts() {
        assertEquals("p/codes/maple-orbit-42.json", PlayerPaths.head(CODE));
        assertEquals("p/c/" + ID + "/i/" + SHA + ".json", PlayerPaths.index(ID, SHA));
        assertEquals("p/c/" + ID + "/r/" + SHA + ".json", PlayerPaths.record(ID, SHA));
        assertEquals("p/c/" + ID + "/a/" + SHA + ".png", PlayerPaths.asset(ID, SHA, BlobType.PNG));
        assertEquals("p/c/" + ID + "/a/" + SHA + ".geo.json", PlayerPaths.asset(ID, SHA, BlobType.GEO));
        assertEquals("p/c/" + ID + "/a/" + SHA + ".animation.json", PlayerPaths.asset(ID, SHA, BlobType.ANIMATION));
        for (String path : new String[] {PlayerPaths.STATUS, PlayerPaths.REVOCATIONS, PlayerPaths.ROOT_STATUS,
            PlayerPaths.head(CODE), PlayerPaths.asset(ID, SHA, BlobType.ANIMATION)}) {
            assertTrue(RemotePaths.isSafeRelative(path), path);
        }
        assertFalse(PlayerPaths.ROOT_STATUS.startsWith(PlayerPaths.ROOT));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.head("willow-media"));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.head("../maple-orbit-42"));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.index("e2e_fixture", SHA));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.index(ID, "../" + SHA.substring(3)));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.record(ID, SHA.toUpperCase(java.util.Locale.ROOT)));
        assertThrows(IllegalArgumentException.class, () -> PlayerPaths.asset("p_../../../../", SHA, BlobType.PNG));
    }

    @Test
    void base64HasOneSpelling() {
        Random random = new Random(5);
        for (int length = 0; length < 70; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            String text = CoreBase64.encode(bytes);
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), text);
            assertEquals(text.length(), CoreBase64.encodedLength(length));
            assertArrayEquals(bytes, CoreBase64.decode(text));
        }
        assertArrayEquals(new byte[0], CoreBase64.decode(""));
        // Padding, the other alphabet, white space, a length no bytes give, and stray last bits
        for (String bad : new String[] {null, "QQ==", "QQ=", "+/+/", "QUJD\n", " QUJD", "QUJD ", "Q", "QUJDR", "QR", "QUJ",
            "QUIé", "QUJDＡ"}) {
            assertNull(CoreBase64.decode(bad), String.valueOf(bad));
        }
        assertNotNull(CoreBase64.decode("QQ"));
        assertNotNull(CoreBase64.decode("QUI"));
    }

    @Test
    void anEnvelopeCarriesExactlyAKeyIdAPayloadAndASignature() throws RemoteException {
        byte[] payload = utf8("{\"kind\":\"x\"}");
        byte[] signature = new byte[64];
        new Random(9).nextBytes(signature);
        SignedEnvelope envelope = new SignedEnvelope("o1", payload, signature);
        SignedEnvelope read = SignedEnvelope.parse(envelope.toUtf8(), 4096);
        assertEquals("o1", read.kid());
        assertArrayEquals(payload, read.payload());
        assertArrayEquals(signature, read.signature());
        assertArrayEquals(envelope.toUtf8(), read.toUtf8());

        String p = CoreBase64.encode(payload);
        String s = CoreBase64.encode(signature);
        for (String bad : new String[] {
            "{\"kid\":\"o1\",\"payload\":\"" + p + "\"}",
            "{\"kid\":\"o1\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\",\"alg\":\"none\"}",
            "{\"kid\":\"O1\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"1o\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"onlinekey\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"}",
            "{\"kid\":1,\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"o1\",\"payload\":\"\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"o1\",\"payload\":\"" + p + "=\",\"sig\":\"" + s + "\"}",
            "{\"kid\":\"o1\",\"payload\":\"" + p + "\",\"sig\":\"" + s.substring(2) + "\"}",
            "{\"kid\":\"o1\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "AA\"}",
            "{\"kid\":\"o1\",\"payload\":\"" + p + "\",\"sig\":\"" + s + "\"} ",
            "{\"sig\":\"" + s + "\",\"kid\":\"o1\",\"payload\":\"" + p + "\"}",
            "[\"o1\"]"}) {
            assertThrows(RemoteException.class, () -> SignedEnvelope.parse(utf8(bad), 4096), bad);
        }
        assertThrows(RemoteException.class, () -> SignedEnvelope.parse(envelope.toUtf8(), 64));
        assertThrows(IllegalArgumentException.class, () -> new SignedEnvelope("o1", payload, new byte[63]));
    }

    @Test
    void aHeadIsLiveWithAnIndexOrNotLiveWithAReason() throws RemoteException {
        PlayerHead live = new PlayerHead(7, NOW, CODE, ID, PlayerHead.State.LIVE, 5, SHA, null);
        assertEquals("{\"kind\":\"blockpops.player.head\",\"schema\":1,\"seq\":7,\"issued_at\":1794050000,"
                + "\"code\":\"maple-orbit-42\",\"id\":\"p_k3v7q2m5x4ab\",\"state\":\"live\",\"rev\":5,\"index\":\""
                + SHA + "\"}", new String(live.toPayload(), StandardCharsets.UTF_8));
        assertEquals(live, PlayerHead.parse(live.toPayload()));
        PlayerHead removed = new PlayerHead(8, NOW, CODE, ID, PlayerHead.State.REMOVED, 5, null, PlayerHead.Reason.POLICY);
        assertEquals(removed, PlayerHead.parse(removed.toPayload()));
        assertTrue(new String(removed.toPayload(), StandardCharsets.UTF_8).endsWith("\"index\":null,\"reason\":\"policy\"}"));

        byte[] payload = live.toPayload();
        refused(edited(payload, json -> json.addProperty("reason", "owner")));
        refused(edited(payload, json -> json.add("index", com.google.gson.JsonNull.INSTANCE)));
        refused(edited(payload, json -> json.addProperty("state", "quarantined")));
        refused(edited(payload, json -> json.addProperty("state", "LIVE")));
        refused(edited(payload, json -> json.addProperty("kind", "blockpops.player.index")));
        refused(edited(payload, json -> json.addProperty("schema", 2)));
        refused(edited(payload, json -> json.addProperty("seq", 0)));
        refused(edited(payload, json -> json.addProperty("seq", (1L << 40) + 1)));
        refused(edited(payload, json -> json.addProperty("seq", 7.5)));
        refused(edited(payload, json -> json.addProperty("seq", "7")));
        refused(edited(payload, json -> json.addProperty("rev", 0)));
        refused(edited(payload, json -> json.addProperty("code", "willow-media")));
        refused(edited(payload, json -> json.addProperty("id", "e2e_fixture")));
        refused(edited(payload, json -> json.addProperty("index", SHA.substring(1))));
        refused(edited(payload, json -> json.addProperty("extra", 1)));
        refused(edited(payload, json -> json.remove("issued_at")));
        refused(edited(removed.toPayload(), json -> json.addProperty("index", SHA)));
        refused(edited(removed.toPayload(), json -> json.addProperty("reason", "spite")));
        refused(edited(removed.toPayload(), json -> json.remove("reason")));
        // One spelling: a whole number is plain digits, and nothing stands between the tokens
        for (String respelled : new String[] {"7.0", "70e-1", "0.7e1", "7e0", "7e1", "07", " 7"}) {
            assertThrows(RemoteException.class, () -> PlayerHead.parse(utf8(
                    new String(payload, StandardCharsets.UTF_8).replace("\"seq\":7", "\"seq\":" + respelled))),
                    respelled);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerHead(7, NOW, CODE, ID, PlayerHead.State.LIVE, 5, null, null));
    }

    private static void refused(byte[] payload) {
        assertThrows(RemoteException.class, () -> PlayerHead.parse(payload), new String(payload, StandardCharsets.UTF_8));
    }

    @Test
    void aStatusExpiresAndNamesItsRevocationList() throws RemoteException {
        PlayerStatus status = new PlayerStatus(12, NOW, NOW + 259_200, true, 42, SHA, 900, 1800, 3600);
        assertEquals("{\"kind\":\"blockpops.player.status\",\"schema\":1,\"seq\":12,\"issued_at\":1794050000,"
                + "\"expires_at\":1794309200,\"loading\":\"on\",\"revocations_seq\":42,\"revocations_sha256\":\"" + SHA
                + "\",\"server_poll_seconds\":900,\"head_poll_seconds\":1800,\"client_poll_seconds\":3600}",
                new String(status.toPayload(), StandardCharsets.UTF_8));
        assertEquals(status, PlayerStatus.parse(status.toPayload()));
        assertTrue(status.isFreshAt(NOW + 259_199));
        assertFalse(status.isFreshAt(NOW + 259_200));
        byte[] payload = status.toPayload();
        for (Edit edit : List.<Edit>of(json -> json.addProperty("expires_at", NOW),
                json -> json.addProperty("expires_at", NOW + PlayerStatus.MAX_VALIDITY_SECONDS + 1),
                json -> json.addProperty("loading", "yes"), json -> json.addProperty("loading", true),
                json -> json.addProperty("revocations_seq", 0), json -> json.addProperty("revocations_sha256", "x"),
                json -> json.addProperty("server_poll_seconds", 0), json -> json.remove("client_poll_seconds"),
                json -> json.addProperty("kind", PlayerHead.KIND))) {
            byte[] bad = edited(payload, edit);
            assertThrows(RemoteException.class, () -> PlayerStatus.parse(bad), new String(bad, StandardCharsets.UTF_8));
        }
        byte[] longest = edited(payload, json -> json.addProperty("expires_at", NOW + PlayerStatus.MAX_VALIDITY_SECONDS));
        assertEquals(NOW + PlayerStatus.MAX_VALIDITY_SECONDS, PlayerStatus.parse(longest).expiresAt());
    }

    @Test
    void aRevocationListNamesEachCollectionAndRevisionOnce() throws RemoteException {
        String other = "p_aaaaaaaaaaaa";
        PlayerRevocations list = new PlayerRevocations(42, NOW, NOW + 259_200,
                List.of(new PlayerRevocations.Collection(ID, true), new PlayerRevocations.Collection(other, false)),
                List.of(new PlayerRevocations.Revision("p_bbbbbbbbbbbb", 2, false),
                        new PlayerRevocations.Revision("p_bbbbbbbbbbbb", 3, true)));
        assertEquals("{\"kind\":\"blockpops.player.revocations\",\"schema\":1,\"seq\":42,\"issued_at\":1794050000,"
                + "\"expires_at\":1794309200,\"collections\":[{\"id\":\"p_k3v7q2m5x4ab\",\"state\":\"removed\"},"
                + "{\"id\":\"p_aaaaaaaaaaaa\",\"state\":\"quarantined\"}],\"revisions\":[{\"id\":\"p_bbbbbbbbbbbb\","
                + "\"rev\":2,\"by\":\"owner\"},{\"id\":\"p_bbbbbbbbbbbb\",\"rev\":3,\"by\":\"moderator\"}]}",
                new String(list.toPayload(), StandardCharsets.UTF_8));
        assertEquals(list, PlayerRevocations.parse(list.toPayload()));
        assertTrue(list.revokes(ID));
        assertTrue(list.revokes(ID, 9));
        assertFalse(list.revokes("p_bbbbbbbbbbbb"));
        assertTrue(list.revokes("p_bbbbbbbbbbbb", 3));
        assertFalse(list.revokes("p_bbbbbbbbbbbb", 4));

        PlayerRevocations empty = new PlayerRevocations(1, NOW, NOW + 60, List.of(), List.of());
        assertEquals(empty, PlayerRevocations.parse(empty.toPayload()));
        byte[] payload = list.toPayload();
        for (Edit edit : List.<Edit>of(
                json -> json.getAsJsonArray("collections").add(json.getAsJsonArray("collections").get(0)),
                json -> json.getAsJsonArray("revisions").add(json.getAsJsonArray("revisions").get(1)),
                json -> json.getAsJsonArray("collections").get(0).getAsJsonObject().addProperty("state", "live"),
                json -> json.getAsJsonArray("collections").get(0).getAsJsonObject().addProperty("id", "w-col"),
                json -> json.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("by", "anyone"),
                json -> json.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("rev", 0),
                json -> json.getAsJsonArray("revisions").get(0).getAsJsonObject().addProperty("note", "x"),
                json -> json.remove("revisions"), json -> json.addProperty("expires_at", NOW - 1))) {
            byte[] bad = edited(payload, edit);
            assertThrows(RemoteException.class, () -> PlayerRevocations.parse(bad),
                    new String(bad, StandardCharsets.UTF_8));
        }
    }

    @Test
    void aRootSwitchesLoadingAndRevokesKeys() throws RemoteException {
        PlayerRoot root = new PlayerRoot(1, NOW, false, List.of("o1"));
        assertEquals("{\"kind\":\"blockpops.player.root\",\"schema\":1,\"seq\":1,\"issued_at\":1794050000,"
                + "\"loading\":\"off\",\"revoked_kids\":[\"o1\"]}", new String(root.toPayload(), StandardCharsets.UTF_8));
        assertEquals(root, PlayerRoot.parse(root.toPayload()));
        assertEquals(new PlayerRoot(2, NOW, true, List.of()),
                PlayerRoot.parse(new PlayerRoot(2, NOW, true, List.of()).toPayload()));
        byte[] payload = root.toPayload();
        for (Edit edit : List.<Edit>of(json -> json.getAsJsonArray("revoked_kids").add("o1"),
                json -> json.getAsJsonArray("revoked_kids").add("O2"), json -> json.getAsJsonArray("revoked_kids").add(2),
                json -> json.addProperty("revoked_kids", "o1"), json -> json.addProperty("loading", "paused"),
                json -> json.addProperty("expires_at", NOW + 1))) {
            byte[] bad = edited(payload, edit);
            assertThrows(RemoteException.class, () -> PlayerRoot.parse(bad), new String(bad, StandardCharsets.UTF_8));
        }
    }

    @Test
    void anIndexListsARevisionsRecordAndFilesWithWhatCheckingFound() throws RemoteException {
        Map<String, BlobInfo> blobs = new LinkedHashMap<>();
        blobs.put(SHA, BlobInfo.png(2311, 64, 64));
        blobs.put(SHA2, BlobInfo.geo(40_512, 12, 64, 32, new ModelBounds(-64, 0, -32, 64, 256, 32)));
        blobs.put(JdkSha256.of(utf8("c")), BlobInfo.animation(8044, 20, BlobInfo.POSE_STAND | BlobInfo.POSE_SIT));
        PlayerIndex index = new PlayerIndex(ID, 5, NOW, "Steve", SHA2, 20_480, blobs, 1, SHA);
        PlayerIndex read = PlayerIndex.parse(index.toPayload());
        assertEquals(index, read);
        assertEquals(List.copyOf(blobs.keySet()), List.copyOf(read.blobs().keySet()));
        assertArrayEquals(index.toPayload(), read.toPayload());
        assertTrue(new String(index.toPayload(), StandardCharsets.UTF_8).startsWith(
                "{\"kind\":\"blockpops.player.index\",\"schema\":1,\"id\":\"p_k3v7q2m5x4ab\",\"rev\":5,"
                + "\"issued_at\":1794050000,\"author\":\"Steve\",\"record\":{\"sha256\":\"" + SHA2 + "\",\"size\":20480},"
                + "\"blobs\":[{\"sha256\":\"" + SHA + "\",\"info\":{\"type\":\"png\",\"size\":2311,\"width\":64,"
                + "\"height\":64}},"));
        PlayerIndex anonymous = new PlayerIndex(ID, 1, NOW, null, SHA2, 100, Map.of(), 1, SHA);
        assertNull(PlayerIndex.parse(anonymous.toPayload()).author());

        byte[] payload = index.toPayload();
        for (Edit edit : List.<Edit>of(json -> json.addProperty("author", "Not a name"),
                json -> json.addProperty("author", "SeventeenLetters_"), json -> json.addProperty("author", ""),
                json -> json.remove("author"), json -> json.addProperty("id", "w-col"),
                json -> json.getAsJsonObject("record").addProperty("size", 0),
                json -> json.getAsJsonObject("record").addProperty("size", 98_305),
                json -> json.getAsJsonObject("record").addProperty("path", "x"),
                json -> json.getAsJsonArray("blobs").add(json.getAsJsonArray("blobs").get(0)),
                json -> json.getAsJsonArray("blobs").get(0).getAsJsonObject().getAsJsonObject("info")
                        .addProperty("width", 8),
                json -> json.getAsJsonArray("blobs").get(0).getAsJsonObject().getAsJsonObject("info")
                        .addProperty("cubes", 1),
                json -> json.getAsJsonArray("blobs").get(0).getAsJsonObject().addProperty("path", "p/x"),
                json -> json.addProperty("rules", 0), json -> json.addProperty("validator", "none"))) {
            byte[] bad = edited(payload, edit);
            assertThrows(RemoteException.class, () -> PlayerIndex.parse(bad), new String(bad, StandardCharsets.UTF_8));
        }
        assertTrue(PlayerIndex.isAccountName("a"));
        assertTrue(PlayerIndex.isAccountName("Sixteen_Letters1"));
        assertFalse(PlayerIndex.isAccountName("Steve&serverId=H"));
    }
}
