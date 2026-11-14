package com.theplumteam.worldcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.worldcontent.WorldCollectionRecord.BoxFace;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldCollectionRecord.Uv;
import com.theplumteam.worldcontent.WorldCollectionRecord.Variant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.theplumteam.worldcontent.WorldCollectionRecordTest.BLOBS;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.LOGO;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.SKIN;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.plain;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.sha;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content core stands apart from the game without changing what the game does: its own list
 * of box colours is the game's, its writer gives the bytes Gson gave, a refusal names its rule,
 * and the parts it leaves to the host (hashing, reading a link) behave as they did inside it.
 */
class ContentCoreTest {
    // What wrote a record before the core had its own writer
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String GEO = sha("geo");
    private static final String TEXTURE = sha("texture");
    private static final String ANIMATION = sha("animation");

    private static List<WorldCollectionRecord> records() {
        List<WorldCollectionRecord> records = new ArrayList<>();
        records.add(plain());
        records.add(WorldCollectionRecord.created("w-new", "New", true));
        records.add(plain().withExtras("https://example.com/me?a=1&b=<2>", new LogoLayout(120, 4, -2), true,
                new RarityWeights(10, 5, 2, 1)).withHolographic(false).withEditedAt(1_700_000_000L));
        // Every character class a writer treats on its own: quotes, a backslash, what HTML escaping
        // would touch, the two separators JSON text escapes, an accent, and a character outside the BMP
        String awkward = "\"quoted\" \\ <b>&amp;='x'    café 😀 /";
        Figure skin = new Figure("zombi", awkward, Kind.SKIN, SKIN, null, null, 0.75f, false, Rarity.LEGENDARY,
                List.of(new Variant(awkward, SKIN), new Variant("Two", SKIN)), new BoxFace(false, null));
        Figure model = new Figure("golem", "Golem", Kind.MODEL, TEXTURE, GEO, ANIMATION, 2.0f, true, Rarity.RARE,
                List.of(), new BoxFace(true, new Uv(8, 8, 8, 8)));
        records.add(new WorldCollectionRecord("w-full", awkward, awkward, 0, 128, 255, sha("box"), null, LOGO, true, 99,
                List.of(skin, model)));
        for (Float scale : WorldCollectionRecord.SCALES) {
            records.add(plain().withFigures(List.of(new Figure("f", "F", Kind.SKIN, SKIN, null, null, scale, true))));
        }
        return records;
    }

    @Test
    void theCoresBoxColoursAreTheBlocksColours() {
        List<String> names = Arrays.stream(PopBlockColor.values()).map(PopBlockColor::getSerializedName).toList();
        assertEquals(names, BoxColors.NAMES);
        for (String name : names) {
            assertTrue(WorldCollectionRecord.isBoxColor(name), name);
        }
        assertFalse(WorldCollectionRecord.isBoxColor("white"));
        assertFalse(WorldCollectionRecord.isBoxColor("Red"));
    }

    @Test
    void aRecordKeepsTheBytesGsonGaveIt() {
        for (WorldCollectionRecord record : records()) {
            byte[] before = GSON.toJson(record.toJson()).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(before, record.toUtf8(), record.id());
        }
    }

    @Test
    void theWriterGivesGsonsTextForAnyParsedValue() throws IOException {
        // Numbers as a parsed document holds them, which the writer must pass through untouched
        String document = "{\"a\":[1,-0,2.50,1e3,1E-7,123456789012345678901234567890,0.1],\"b\":{\"c\":null,\"d\":true,"
                + "\"e\":\"\\u0000\\u001f\\t\\n\\\"\\\\/\\u00e9\\ud83d\\ude00\"},\"\":[],\"f\":{}}";
        JsonObject parsed = StrictJson.parseObject(document.getBytes(StandardCharsets.UTF_8), 4096);
        assertEquals(GSON.toJson(parsed), JsonOut.write(parsed));

        JsonObject built = new JsonObject();
        built.addProperty("int", 7);
        built.addProperty("long", Long.MIN_VALUE);
        built.addProperty("double", 0.75d);
        built.addProperty("float", 1.25f);
        built.addProperty("whole", 2.0d);
        built.add("null", JsonNull.INSTANCE);
        JsonArray nested = new JsonArray();
        nested.add(new JsonArray());
        nested.add((JsonElement) null);
        built.add("nested", nested);
        assertEquals(GSON.toJson(built), JsonOut.write(built));
        assertEquals("null", JsonOut.write(JsonNull.INSTANCE));
    }

    @Test
    void aRefusalNamesItsRule() {
        assertEquals("png_size_role", ContentError.PNG_SIZE_ROLE.id());
        Set<String> ids = new HashSet<>();
        for (ContentError error : ContentError.values()) {
            assertTrue(error.id().matches("[a-z][a-z0-9_]{1,31}"), error.id());
            assertTrue(ids.add(error.id()), error.id());
        }

        assertEquals(ContentError.PNG_SIGNATURE, refusal(() -> PngValidator.canonicalize(new byte[64])));
        assertEquals(ContentError.JSON_SYNTAX, refusal(() -> GeoJsonCheck.validate("{".getBytes(StandardCharsets.UTF_8))));
        assertEquals(ContentError.SHA256, refusal(() -> Sha256.require("abc", "Texture")));

        JsonObject unknown = plain().toJson();
        unknown.addProperty("extra", 1);
        assertEquals(ContentError.JSON_UNKNOWN_KEY, refusal(() -> parse(unknown)));
        JsonObject noFile = plain().toJson();
        noFile.addProperty("logo", sha("absent"));
        assertEquals(ContentError.FILE_MISSING, refusal(() -> parse(noFile)));
        JsonObject blank = plain().toJson();
        blank.addProperty("name", "   ");
        assertEquals(ContentError.TEXT_EMPTY, refusal(() -> parse(blank)));
        JsonObject colour = plain().toJson();
        colour.getAsJsonObject("box").addProperty("color", "white");
        assertEquals(ContentError.RECORD_BOX, refusal(() -> parse(colour)));

        // A refusal outside the core names no rule of it, and its message is still bounded
        WorldContentException outside = new WorldContentException("x".repeat(500));
        assertEquals(ContentError.UNSPECIFIED, outside.code());
        assertEquals(WorldContentException.MAX_MESSAGE + 3, outside.getMessage().length());
    }

    @Test
    void aRecordsLinkIsReadByTheRuleItIsGiven() throws WorldContentException {
        WorldCollectionRecord linked = plain().withExtras("https://example.com/me", null, false, null);
        assertEquals(linked, WorldCollectionRecord.parse(linked.toUtf8(), BLOBS, AuthorLinks.RULE));
        assertEquals(linked, linked.validated(BLOBS, AuthorLinks.RULE));

        WorldContentException refused = assertThrows(WorldContentException.class,
                () -> WorldCollectionRecord.parse(linked.toUtf8(), BLOBS, url -> false));
        assertEquals(ContentError.RECORD_AUTHOR_URL, refused.code());
        assertEquals(WorldCollectionRecord.AUTHOR_URL_RULE, refused.getMessage());
        // A record without a link never asks the rule
        assertEquals(plain(), WorldCollectionRecord.parse(plain().toUtf8(), BLOBS, url -> false));

        JsonObject ftp = linked.toJson();
        ftp.addProperty("author_url", "ftp://example.com");
        assertEquals(ContentError.RECORD_AUTHOR_URL, refusal(() -> parse(ftp)));
        JsonObject tooLong = linked.toJson();
        tooLong.addProperty("author_url", "https://example.com/" + "a".repeat(WorldContentLimits.MAX_AUTHOR_URL_LENGTH));
        assertEquals(ContentError.JSON_STRING_LENGTH, refusal(() -> parse(tooLong)));
    }

    @Test
    void anAuthorLinkIsAPlainWebAddress() {
        for (String good : List.of("https://example.com", "http://example.com/a?b=c#d", "HTTPS://Example.COM:8443/x",
                "https://127.0.0.1/", "https://[::1]/", "https://example.com/%20")) {
            assertTrue(AuthorLinks.isValid(good), good);
        }
        for (String bad : List.of("", "example.com", "ftp://example.com", "https:example.com", "https:///path",
                "https://user@example.com", "https://user:pw@example.com", "https://exam ple.com",
                "https://example.com/a b", "https://exämple.com", "https://example.com/\u0007", "mailto:me@example.com",
                "javascript:alert(1)", "https://example.com/%zz", "https://", "//example.com")) {
            assertFalse(AuthorLinks.isValid(bad), bad);
        }
        assertFalse(AuthorLinks.isValid(null));
        assertFalse(AuthorLinks.isValid("https://example.com/" + "a".repeat(WorldContentLimits.MAX_AUTHOR_URL_LENGTH)));
    }

    @Test
    void aShaIsSixtyFourLowercaseHexDigitsAndTheGameHashesWithTheJdk() {
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertEquals(empty, JdkSha256.of(new byte[0]));
        assertEquals(empty, JdkSha256.INSTANCE.hex(new byte[0]));
        assertNotEquals(empty, JdkSha256.of(new byte[1]));
        assertTrue(Sha256.isValidHex(empty));
        assertFalse(Sha256.isValidHex(null));
        assertFalse(Sha256.isValidHex(empty.substring(1)));
        assertFalse(Sha256.isValidHex(empty + "0"));
        assertFalse(Sha256.isValidHex(empty.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(Sha256.isValidHex(empty.substring(0, 63) + "g"));
        assertFalse(Sha256.isValidHex(empty.substring(0, 63) + "１"));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static ContentError refusal(Action action) {
        return assertThrows(WorldContentException.class, action::run).code();
    }

    private static WorldCollectionRecord parse(JsonObject json) throws WorldContentException {
        Map<String, BlobInfo> blobs = BLOBS;
        return WorldCollectionRecord.parse(json.toString().getBytes(StandardCharsets.UTF_8), blobs, AuthorLinks.RULE);
    }
}
