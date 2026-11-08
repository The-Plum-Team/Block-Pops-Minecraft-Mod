package com.theplumteam.worldcontent;

import com.google.gson.JsonObject;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record's optional keys (ADR 0004, ADR 0006): left out at their default, strict when present. */
class WorldCollectionRecordTest {
    static final String SKIN = sha("skin");
    static final String LOGO = sha("logo");
    static final Map<String, BlobInfo> BLOBS = Map.of(
            SKIN, BlobInfo.png(1_000, 64, 64),
            LOGO, BlobInfo.png(500, 32, 32));

    static String sha(String tag) {
        return Sha256.of(tag.getBytes(StandardCharsets.UTF_8));
    }

    static WorldCollectionRecord plain() {
        return new WorldCollectionRecord("w-col", "Col", "Me", 10, 20, 30, null, "red", LOGO, false, 3,
                List.of(new Figure("zombi", "Zombi", Kind.SKIN, SKIN, null, null, 1.0f, true)));
    }

    private static WorldCollectionRecord parse(JsonObject json) throws WorldContentException {
        return WorldCollectionRecord.parse(json.toString().getBytes(StandardCharsets.UTF_8), BLOBS);
    }

    @Test
    void aRecordThatUsesNoOptionalFeatureWritesNoOptionalKey() {
        assertEquals(Set.of("id", "name", "author", "background_color", "box", "logo", "archived", "revision",
                "figures"), plain().toJson().keySet());
    }

    @Test
    void holographicDrawsAreWrittenOnlyWhileTurnedOff() throws WorldContentException {
        WorldCollectionRecord off = plain().withHolographic(false);
        assertFalse(off.toJson().get("holographic").getAsBoolean());
        WorldCollectionRecord parsed = WorldCollectionRecord.parse(off.toUtf8(), BLOBS);
        assertEquals(off, parsed);
        assertFalse(parsed.holographic());

        assertTrue(plain().holographic());
        assertArrayEquals(plain().toUtf8(), off.withHolographic(true).toUtf8());
    }

    @Test
    void holographicTrueOrAnotherTypeIsRefused() {
        JsonObject on = plain().toJson();
        on.addProperty("holographic", true);
        WorldContentException written = assertThrows(WorldContentException.class, () -> parse(on));
        assertTrue(written.getMessage().contains("only when false"), written.getMessage());

        JsonObject text = plain().toJson();
        text.addProperty("holographic", "false");
        assertThrows(WorldContentException.class, () -> parse(text));
        JsonObject number = plain().toJson();
        number.addProperty("holographic", 0);
        assertThrows(WorldContentException.class, () -> parse(number));
    }

    @Test
    void everyOptionalKeyRoundTrips() throws WorldContentException {
        WorldCollectionRecord full = new WorldCollectionRecord("w-col", "Col", "Me", 10, 20, 30, null, null, LOGO,
                false, 3, plain().figures(), true, 1_700_000_000L, "https://example.com/me",
                new LogoLayout(120, 4, -2), true, new RarityWeights(10, 5, 2, 1), false);
        assertEquals(Set.of("draft", "edited_at", "author_url", "logo_layout", "plush", "rarity_weights",
                "holographic"), keysBeyond(plain().toJson(), full.toJson()));
        assertEquals(full, WorldCollectionRecord.parse(full.toUtf8(), BLOBS));
    }

    @Test
    void defaultsAreNormalizedAwaySoEqualRecordsHaveEqualBytes() {
        WorldCollectionRecord base = plain();
        WorldCollectionRecord defaults = new WorldCollectionRecord(base.id(), base.name(), base.author(), base.red(),
                base.green(), base.blue(), base.boxTexture(), base.boxColor(), base.logo(), base.archived(),
                base.revision(), base.figures(), false, 0L, "", LogoLayout.DEFAULT, false, RarityWeights.DEFAULT,
                true);
        assertNull(defaults.authorUrl());
        assertNull(defaults.logoLayout());
        assertNull(defaults.rarityWeights());
        assertEquals(base, defaults);
        assertArrayEquals(base.toUtf8(), defaults.toUtf8());
    }

    @Test
    void aDraftIsNeverArchivedAndAPlacementNeedsALogo() {
        JsonObject archivedDraft = plain().withArchived(true).toJson();
        archivedDraft.addProperty("draft", true);
        assertThrows(WorldContentException.class, () -> parse(archivedDraft));

        WorldCollectionRecord withLayout = plain().withExtras(null, new LogoLayout(120, 0, 0), false, null);
        JsonObject noLogo = withLayout.toJson();
        noLogo.add("logo", com.google.gson.JsonNull.INSTANCE);
        assertThrows(WorldContentException.class, () -> parse(noLogo));
        // Taking the logo away takes its placement with it
        assertNull(withLayout.withIdentity("Col", "Me", 10, 20, 30, null, "red", null).logoLayout());
    }

    @Test
    void anUnknownKeyIsRefused() {
        JsonObject json = plain().toJson();
        json.addProperty("sparkly", true);
        assertThrows(WorldContentException.class, () -> parse(json));
    }

    private static Set<String> keysBeyond(JsonObject base, JsonObject more) {
        Set<String> keys = new java.util.HashSet<>(more.keySet());
        keys.removeAll(base.keySet());
        return keys;
    }
}
