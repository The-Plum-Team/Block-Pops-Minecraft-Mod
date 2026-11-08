package com.theplumteam.client.worldcontent;

import org.junit.jupiter.api.Test;

import static com.theplumteam.client.worldcontent.Fixtures.COLLECTION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One flight per collection: a run counts until the sync that shows it, or ten seconds (SPEC §3.3). */
class CreatorRunsTest extends CreatorTestBase {
    @Test
    void aRunThatChangedTheServerCountsUntilTheNextSync() {
        CreatorRuns.Entry entry = CreatorRuns.begin(COLLECTION, null);
        assertTrue(CreatorRuns.busy(COLLECTION));
        CreatorRuns.finish(entry, true);
        server.advance(5_000);
        assertTrue(CreatorRuns.busy(COLLECTION));

        ClientWorldState.sync();
        assertFalse(CreatorRuns.busy(COLLECTION));
    }

    @Test
    void aRunWhoseSyncDoesNotComeStopsCountingAfterTheWait() {
        CreatorRuns.finish(CreatorRuns.begin(COLLECTION, null), true);
        server.advance(CreatorRuns.SYNC_WAIT_MS - 1);
        assertTrue(CreatorRuns.busy(COLLECTION));
        server.advance(1);
        assertFalse(CreatorRuns.busy(COLLECTION));
    }

    @Test
    void aRunThatChangedNothingStopsCountingAtOnce() {
        CreatorRuns.finish(CreatorRuns.begin(COLLECTION, null), false);
        assertFalse(CreatorRuns.busy(COLLECTION));
    }

    @Test
    void anOwnersRunsCanBeLeftOutAndACreateMovesWithItsSlug() {
        Object saver = new Object();
        CreatorRuns.Entry entry = CreatorRuns.begin(COLLECTION, saver);
        assertTrue(CreatorRuns.busyOther(COLLECTION, new Object()));
        assertFalse(CreatorRuns.busyOther(COLLECTION, saver));
        assertTrue(CreatorRuns.inFlight(COLLECTION, saver));

        CreatorRuns.move(entry, "w-col_2");
        assertFalse(CreatorRuns.busy(COLLECTION));
        assertTrue(CreatorRuns.busy("w-col_2"));

        CreatorRuns.finish(entry, true);
        assertFalse(CreatorRuns.inFlight("w-col_2", saver));
        assertTrue(CreatorRuns.busy("w-col_2"));
        CreatorRuns.forget(entry);
        assertFalse(CreatorRuns.busy("w-col_2"));
    }
}
