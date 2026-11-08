package com.theplumteam.server.worldcollections;

import com.google.gson.JsonParser;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldContentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional collection-wide keys of a datapack's collection.json (ADR 0004, ADR 0006): a pack
 * may write plush and holographic at either value, unlike a stored record, and every key is strict
 * about its type.
 */
class WorldCollectionImporterTest {
    private static WorldCollectionImporter.Options options(String json) throws WorldContentException {
        return WorldCollectionImporter.options(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void aPackThatLeavesTheKeysOutGetsTheirDefaults() throws WorldContentException {
        WorldCollectionImporter.Options options = options("{}");
        assertNull(options.authorUrl());
        assertNull(options.logoLayout());
        assertFalse(options.plush());
        assertNull(options.rarityWeights());
        assertTrue(options.holographic());
    }

    @Test
    void holographicDrawsMayBeWrittenAtEitherValueButOnlyAsABoolean() throws WorldContentException {
        assertTrue(options("{\"holographic\": true}").holographic());
        assertFalse(options("{\"holographic\": false}").holographic());
        assertThrows(WorldContentException.class, () -> options("{\"holographic\": \"false\"}"));
        assertThrows(WorldContentException.class, () -> options("{\"holographic\": 0}"));
        assertThrows(WorldContentException.class, () -> options("{\"holographic\": null}"));
    }

    @Test
    void aPlushMayBeWrittenAtEitherValueButOnlyAsABoolean() throws WorldContentException {
        assertTrue(options("{\"plush\": true}").plush());
        assertFalse(options("{\"plush\": false}").plush());
        assertThrows(WorldContentException.class, () -> options("{\"plush\": \"true\"}"));
        assertThrows(WorldContentException.class, () -> options("{\"plush\": 1}"));
    }

    @Test
    void theOtherKeysHaveTheRecordsForm() throws WorldContentException {
        WorldCollectionImporter.Options options = options("{\"author_url\": \"https://example.com/me\","
                + " \"logo_layout\": {\"scale_pct\": 120, \"offset_x\": 4, \"offset_y\": -2},"
                + " \"rarity_weights\": [10, 5, 2, 1]}");
        assertEquals("https://example.com/me", options.authorUrl());
        assertEquals(new LogoLayout(120, 4, -2), options.logoLayout());
        assertEquals(new RarityWeights(10, 5, 2, 1), options.rarityWeights());
        // Each key alone leaves the others at their defaults
        assertFalse(options.plush());
        assertTrue(options.holographic());

        assertThrows(WorldContentException.class, () -> options("{\"author_url\": \"ftp://example.com\"}"));
        assertThrows(WorldContentException.class, () -> options("{\"logo_layout\": {\"scale_pct\": 120}}"));
        assertThrows(WorldContentException.class, () -> options("{\"rarity_weights\": [10, 5, 2]}"));
    }
}
