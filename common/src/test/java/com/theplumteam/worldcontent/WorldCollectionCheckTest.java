package com.theplumteam.worldcontent;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import org.junit.jupiter.api.Test;

import static com.theplumteam.worldcontent.WorldCollectionRecordTest.BLOBS;
import static com.theplumteam.worldcontent.WorldCollectionRecordTest.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A materialized World collection must carry exactly what its record says, holographic draws included. */
class WorldCollectionCheckTest {
    private static WorldCollectionRecord record() {
        return plain().withExtras("https://example.com/me", new LogoLayout(120, 4, -2), false, null)
                .withHolographic(false);
    }

    @Test
    void aCollectionMaterializedFromItsRecordPasses() {
        WorldCollectionRecord record = record();
        FigureCollection collection = WorldCollectionMaterializer.materialize(record, BLOBS);
        assertNull(WorldCollectionCheck.problem(collection, record));
        assertFalse(collection.allowsHolographic());
        assertTrue(WorldCollectionMaterializer.materialize(plain(), BLOBS).allowsHolographic());
    }

    @Test
    void holographicDrawsApartFromTheRecordAreRefused() {
        WorldCollectionRecord record = record();
        FigureCollection allowing = WorldCollectionMaterializer.materialize(record.withHolographic(true), BLOBS);
        assertEquals("is plush, linked, special or holographic apart from its record",
                WorldCollectionCheck.problem(allowing, record));
    }

    @Test
    void aPlushFlagOrALinkApartFromTheRecordIsRefused() {
        WorldCollectionRecord record = record();
        FigureCollection plush = WorldCollectionMaterializer.materialize(
                record.withExtras(record.authorUrl(), record.logoLayout(), true, null), BLOBS);
        assertEquals("is plush, linked, special or holographic apart from its record",
                WorldCollectionCheck.problem(plush, record));
        FigureCollection unlinked = WorldCollectionMaterializer.materialize(
                record.withExtras(null, record.logoLayout(), false, null), BLOBS);
        assertEquals("is plush, linked, special or holographic apart from its record",
                WorldCollectionCheck.problem(unlinked, record));
    }

    @Test
    void anotherIdIsRefused() {
        WorldCollectionRecord record = record();
        WorldCollectionRecord other = new WorldCollectionRecord("w-other", record.name(), record.author(),
                record.red(), record.green(), record.blue(), record.boxTexture(), record.boxColor(), record.logo(),
                record.archived(), record.revision(), record.figures(), record.draft(), record.editedAt(),
                record.authorUrl(), record.logoLayout(), record.plush(), record.rarityWeights(),
                record.holographic());
        assertEquals("declares id w-other",
                WorldCollectionCheck.problem(WorldCollectionMaterializer.materialize(other, BLOBS), record));
    }
}
