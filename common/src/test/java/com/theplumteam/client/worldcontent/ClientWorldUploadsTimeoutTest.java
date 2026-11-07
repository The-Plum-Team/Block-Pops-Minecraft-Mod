package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.worldcontent.Fixtures.COLLECTION;
import static com.theplumteam.client.worldcontent.Fixtures.skin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An answer the server owes and never sends, timed by the host's clock. */
class ClientWorldUploadsTimeoutTest extends CreatorTestBase {
    private static final WorldCollectionEdit FIRST = new WorldCollectionEdit.SetHolographic(COLLECTION, false);
    private static final WorldCollectionEdit SECOND = new WorldCollectionEdit.SetHolographic(COLLECTION, true);

    @Test
    void anEditNotAnsweredInTimeEndsAsTimedOutTheNextSubmissionGoesAndTheLateAnswerIsIgnored() {
        List<ClientWorldUploads.Outcome> first = new ArrayList<>();
        List<ClientWorldUploads.Outcome> second = new ArrayList<>();
        assertTrue(ClientWorldUploads.edit(FIRST, first::add));
        assertTrue(ClientWorldUploads.edit(SECOND, second::add));
        assertEquals(List.of(FIRST), server.edits);

        // The deadline itself is still in time
        server.advance(ClientWorldUploads.ANSWER_TIMEOUT_MS);
        ClientWorldUploads.tick();
        assertEquals(List.of(), first);

        server.advance(1);
        ClientWorldUploads.tick();
        assertEquals(1, first.size());
        assertEquals(Result.TIMED_OUT, first.get(0).status().result());
        assertFalse(first.get(0).disconnected());
        assertEquals(List.of(FIRST, SECOND), server.edits);

        // The first one's answer comes too late and is not taken for the second's
        assertEquals(FIRST, server.answer(Result.OK));
        assertEquals(List.of(), second);
        assertEquals(SECOND, server.answer(Result.OK));
        assertEquals(1, second.size());
        assertTrue(second.get(0).ok());
        assertEquals(1, first.size());
    }

    @Test
    void aFileNotAnsweredInTimeEndsTheSubmissionBeforeItsEditIsSent() {
        List<ClientWorldUploads.Outcome> outcomes = new ArrayList<>();
        assertTrue(ClientWorldUploads.submit(List.of(skin("a")), FIRST, outcomes::add));
        assertEquals(List.of(skin("a").sha()), server.uploads);

        server.advance(ClientWorldUploads.ANSWER_TIMEOUT_MS + 1);
        ClientWorldUploads.tick();
        assertEquals(1, outcomes.size());
        assertEquals(Result.TIMED_OUT, outcomes.get(0).status().result());
        assertEquals(List.of(), server.edits);
    }
}
