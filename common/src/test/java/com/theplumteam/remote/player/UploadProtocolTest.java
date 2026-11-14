package com.theplumteam.remote.player;

import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the game and the upload service say to each other: intents, packages, errors and names. */
class UploadProtocolTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String SHA = JdkSha256.of("a".getBytes(StandardCharsets.UTF_8));
    private static final String SHA2 = JdkSha256.of("b".getBytes(StandardCharsets.UTF_8));

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anIntentIsOneLineWithOneSpelling() {
        List<UploadIntent> intents = List.of(UploadIntent.publishNew(SHA), UploadIntent.publishChanges(ID, 4, SHA),
                UploadIntent.list(), UploadIntent.rollback(ID, 5, 3), UploadIntent.withdraw(ID, 5, 2),
                UploadIntent.delete(ID, 5), UploadIntent.link());
        assertEquals(List.of("publish|new|0|" + SHA, "publish|" + ID + "|4|" + SHA, "list", "rollback|" + ID + "|5|3",
                "withdraw|" + ID + "|5|2", "delete|" + ID + "|5", "link"),
                intents.stream().map(UploadIntent::text).toList());
        for (UploadIntent intent : intents) {
            assertEquals(intent, UploadIntent.parse(intent.text()));
            assertTrue(intent.text().length() <= UploadIntent.MAX_LENGTH);
        }
        for (String bad : new String[] {null, "", "List", "list|", "list ", "publish", "publish|new|1|" + SHA,
            "publish|new|0|" + SHA.substring(1), "publish|new|0|" + SHA + "|", "publish|" + ID + "|0|" + SHA,
            "publish|" + ID + "|04|" + SHA, "publish|" + ID + "|+4|" + SHA, "publish|" + ID + "|4.0|" + SHA,
            "publish|w-col|4|" + SHA, "publish|NEW|0|" + SHA, "rollback|" + ID + "|5|5", "rollback|" + ID + "|5|0",
            "rollback|" + ID + "|5", "withdraw|" + ID + "|5|5", "delete|" + ID, "delete|" + ID + "|5|1",
            "delete|" + ID + "|10000001", "report|" + ID + "|1|spam|" + SHA, "link|" + ID,
            "publish|new|0|" + SHA + "\n", "x".repeat(200)}) {
            assertNull(UploadIntent.parse(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> UploadIntent.publishChanges(ID, 0, SHA));
        assertThrows(IllegalArgumentException.class, () -> UploadIntent.rollback(ID, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> UploadIntent.delete("willow_media", 1));
    }

    @Test
    void aPackageListsItsRecordFirstAndEachFileOnce() throws RemoteException {
        UploadPackage fresh = new UploadPackage(1, 1, "2.2.0+1.21.8-fabric", null, 0, SHA,
                List.of(new UploadPackage.Blob(SHA, null, 2048), new UploadPackage.Blob(SHA2, BlobType.PNG, 2311)));
        assertEquals("{\"schema\":1,\"terms\":1,\"rules\":1,\"client\":\"2.2.0+1.21.8-fabric\",\"target\":\"new\","
                + "\"base_rev\":0,\"record\":\"" + SHA + "\",\"blobs\":[{\"sha256\":\"" + SHA
                + "\",\"type\":\"record\",\"size\":2048},{\"sha256\":\"" + SHA2 + "\",\"type\":\"png\",\"size\":2311}]}",
                new String(fresh.toUtf8(), StandardCharsets.UTF_8));
        assertEquals(fresh, UploadPackage.parse(fresh.toUtf8()));
        UploadPackage change = new UploadPackage(2, 3, "2.2.0+26.3-neoforge", ID, 4, SHA,
                List.of(new UploadPackage.Blob(SHA, null, 10)));
        assertEquals(change, UploadPackage.parse(change.toUtf8()));
        assertArrayEquals(change.toUtf8(), UploadPackage.parse(change.toUtf8()).toUtf8());

        String text = new String(fresh.toUtf8(), StandardCharsets.UTF_8);
        for (String bad : new String[] {
            text.replace("\"schema\":1", "\"schema\":2"),
            text.replace("\"terms\":1", "\"terms\":1.0"),
            text.replace("\"terms\":1", "\"terms\": 1"),
            text.replace("\"target\":\"new\"", "\"target\":\"" + ID + "\""),
            text.replace("\"base_rev\":0", "\"base_rev\":1"),
            text.replace("\"target\":\"new\"", "\"target\":\"w-col\""),
            text.replace("2.2.0+1.21.8-fabric", "2.2.0 fabric"),
            text.replace("\"type\":\"record\"", "\"type\":\"png\""),
            text.replace("\"type\":\"png\"", "\"type\":\"record\""),
            text.replace("\"type\":\"png\"", "\"type\":\"exe\""),
            text.replace("\"size\":2311", "\"size\":0"),
            text.replace("\"size\":2311", "\"size\":524289"),
            text.replace("\"size\":2048", "\"size\":98305"),
            text.replace(SHA2, SHA),
            text.replace("\"record\":\"" + SHA, "\"record\":\"" + SHA2),
            text.replace("\"blobs\":[", "\"blobs\":[{\"sha256\":\"" + SHA2 + "\",\"type\":\"png\",\"size\":1},"),
            text.replace(",\"blobs\":[", ",\"role\":\"skin\",\"blobs\":["),
            "{\"schema\":1,\"terms\":1,\"rules\":1,\"client\":\"c\",\"target\":\"new\",\"base_rev\":0,\"record\":\""
                    + SHA + "\",\"blobs\":[]}"}) {
            assertThrows(RemoteException.class, () -> UploadPackage.parse(utf8(bad)), bad);
        }
        assertTrue(UploadPackage.isClient("2.2.0+1.20.1-forge"));
        assertFalse(UploadPackage.isClient(""));
        assertFalse(UploadPackage.isClient("a/b"));
    }

    @Test
    void anUnknownErrorIsAnInternalOne() throws RemoteException {
        Set<String> ids = new HashSet<>();
        for (ApiError error : ApiError.values()) {
            assertTrue(error.id().matches("[a-z][a-z_]{1,31}"), error.id());
            assertTrue(ids.add(error.id()));
            assertEquals(error, ApiError.byId(error.id()));
        }
        assertEquals(ApiError.INTERNAL, ApiError.byId("made_up"));
        assertEquals(ApiError.INTERNAL, ApiError.byId(null));
        assertEquals(ApiError.INTERNAL, ApiError.byId("NOT_SUPPORTER"));
        assertNotNull(ApiError.byId("not_supporter"));
        // No error shares its name with a content rule, so a rule is never read as an error
        for (ContentError rule : ContentError.values()) {
            assertFalse(ids.contains(rule.id()), rule.id());
        }
        JsonObject answer = StrictJson.parseObject(utf8("{\"schema\":1,\"error\":\"quota_daily\"}"), 1024);
        assertEquals(ApiError.QUOTA_DAILY, ApiError.byId(answer.get("error").getAsString()));
    }

    @Test
    void aNameUsesOnlyTheListedCharactersAndSingleSpaces() {
        for (String good : new String[] {"A", "Col", "Mis figuras", "Café del Sur", "Ñandú & Co.",
            "It's (not) over: yes!", "Łódź", "ǅ", "a".repeat(48), "R2-D2, C-3PO?"}) {
            assertNull(PlayerText.nameProblem(good), good);
        }
        assertEquals(ContentError.TEXT_EMPTY, PlayerText.nameProblem(""));
        assertEquals(ContentError.TEXT_EMPTY, PlayerText.nameProblem(null));
        assertEquals(ContentError.TEXT_LENGTH, PlayerText.nameProblem("a".repeat(49)));
        for (String spaces : new String[] {" a", "a ", "a  b", " "}) {
            assertEquals(ContentError.TEXT_SPACES, PlayerText.nameProblem(spaces), spaces);
        }
        // The section sign, the no-break space, the soft hyphen, the two signs inside the Latin
        // blocks, another script, a combining accent, bidi and zero-width characters, an emoji
        for (String bad : new String[] {"§kcool", "a b", "a­b", "2×2", "6÷2", "привет",
            "café", "a‮b", "a​b", "a😀", "a\tb", "a\nb", "a_b", "a/b", "a\"b", "<b>", "a@b",
            "ɐ", "¿", "a·b"}) {
            assertEquals(ContentError.TEXT_CHARACTERS, PlayerText.nameProblem(bad), bad);
        }
        assertNull(PlayerText.problem("", 0, 200));
        assertEquals(ContentError.TEXT_LENGTH, PlayerText.problem("abc", 0, 2));
    }

    @Test
    void lookAlikeNamesReduceToTheSameSkeleton() {
        String blockpops = PlayerText.skeleton("blockpops");
        assertEquals("biockpops", blockpops);
        for (String alike : new String[] {"BlockPops", "BIockPops", "b1ockpops", "bǀockpops", "B L O C K P O P S",
            "Blöck-Póps!", "b.l.o.c.k.p.0.p.5", "bl0ckp0p$", "bıockpops", "Block_Pops"}) {
            assertEquals(blockpops, PlayerText.skeleton(alike), alike);
        }
        assertEquals("modemwar", PlayerText.skeleton("Modern VVar"));
        assertEquals("aeoethss", PlayerText.skeleton("Æœþß"));
        assertEquals("oficiai", PlayerText.skeleton("Ófícïal"));
        assertEquals("admin", PlayerText.skeleton("4dm1n"));
        assertEquals("test", PlayerText.skeleton("7357"));
        assertEquals("a2689", PlayerText.skeleton("a2689"));
        assertEquals("", PlayerText.skeleton(" -.,!?&():'"));
        assertEquals("", PlayerText.skeleton("привет 😀"));
        // Every letter a name may hold reduces to plain lowercase letters
        for (char c = 0x00c0; c <= 0x024f; c++) {
            assertTrue(PlayerText.skeleton(String.valueOf(c)).matches("[a-z]{0,2}"), Integer.toHexString(c));
        }
    }
}
