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

/** The client-wide budget's sliding windows and the reserve background senders leave (SPEC §3.2). */
class ClientWorldUploadsBudgetTest extends CreatorTestBase {
    private static final WorldCollectionEdit EDIT = new WorldCollectionEdit.SetHolographic(COLLECTION, false);
    private static final ClientWorldUploads.Cost ONE_EDIT = ClientWorldUploads.Cost.of(List.of(), EDIT);

    // Sends an edit at the operator's request, which the server takes at once
    private void sendEdit(WorldCollectionEdit edit) {
        assertTrue(ClientWorldUploads.edit(edit, outcome -> {
        }));
        server.answer(Result.OK);
    }

    @Test
    void aBackgroundSenderLeavesTheReservePerSecond() {
        for (int i = 0; i < 3; i++) {
            sendEdit(EDIT);
        }
        assertEquals(1_000, ClientWorldUploads.roomInMillis(ONE_EDIT, true));
        assertEquals(0, ClientWorldUploads.roomInMillis(ONE_EDIT, false));

        sendEdit(EDIT);
        assertEquals(1_000, ClientWorldUploads.roomInMillis(ONE_EDIT, false));

        server.advance(1_000);
        assertEquals(0, ClientWorldUploads.roomInMillis(ONE_EDIT, true));
        assertEquals(0, ClientWorldUploads.roomInMillis(ONE_EDIT, false));
    }

    @Test
    void aBackgroundSenderLeavesTheReservePerMinute() {
        long start = server.now;
        for (int i = 0; i < 45; i++) {
            sendEdit(EDIT);
            server.advance(400);
        }
        // Background senders have 45 a minute, the operator 55
        assertEquals(start + 60_000 - server.now, ClientWorldUploads.roomInMillis(ONE_EDIT, true));
        assertEquals(0, ClientWorldUploads.roomInMillis(ONE_EDIT, false));
    }

    @Test
    void aSubmissionTheBudgetHasNoRoomForWaitsAtTheHeadOfTheQueueInsteadOfBeingRefused() {
        for (int i = 0; i < 4; i++) {
            sendEdit(EDIT);
        }
        Object autosave = new Object();
        List<ClientWorldUploads.Outcome> outcomes = new ArrayList<>();
        assertTrue(ClientWorldUploads.submit(List.of(), EDIT, autosave, true, outcomes::add));
        assertEquals(4, server.edits.size());
        assertEquals(1_000, ClientWorldUploads.waitMillis(autosave));

        server.advance(1_000);
        ClientWorldUploads.tick();
        assertEquals(5, server.edits.size());
        server.answer(Result.OK);
        assertTrue(outcomes.get(0).ok());
    }

    @Test
    void aBatchCostsOneEditPerSixteenAndAnImportSpendsItsOwnWindow() {
        List<WorldCollectionEdit> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "f" + i, true));
        }
        assertEquals(2, ClientWorldUploads.Cost.of(List.of(), new WorldCollectionEdit.Batch(seventeen)).editCost());
        assertEquals(1, ONE_EDIT.editCost());
        ClientWorldUploads.Cost upload = ClientWorldUploads.Cost.of(List.of(skin("a"), skin("b")), EDIT);
        assertEquals(2, upload.begins());
        assertEquals(skin("a").bytes().length + skin("b").bytes().length, upload.bytes());

        WorldCollectionEdit importPack = new WorldCollectionEdit.ImportPack("col", "pack", "col", true);
        ClientWorldUploads.Cost importCost = ClientWorldUploads.Cost.of(List.of(), importPack);
        assertEquals(0, importCost.editCost());
        assertEquals(1, importCost.imports());
        sendEdit(importPack);
        sendEdit(importPack);
        assertEquals(60_000, ClientWorldUploads.roomInMillis(importCost, false));
        assertEquals(0, ClientWorldUploads.roomInMillis(ONE_EDIT, true));
    }

    @Test
    void aNeedLargerThanAWholeWindowIsNeverHeldForEver() {
        ClientWorldUploads.Cost huge = new ClientWorldUploads.Cost(0, 0, 1, 40L * 1024 * 1024);
        assertEquals(0, ClientWorldUploads.roomInMillis(huge, true));
    }

    @Test
    void aFullQueueAndAnOfflineClientAreToldApart() {
        assertTrue(ClientWorldUploads.edit(EDIT, outcome -> {
        }));
        for (int i = 0; i < 8; i++) {
            assertTrue(ClientWorldUploads.edit(EDIT, outcome -> {
            }));
        }
        assertFalse(ClientWorldUploads.edit(EDIT, outcome -> {
        }));
        assertTrue(ClientWorldUploads.isFull());

        ClientWorldUploads.reset();
        server.connected = false;
        assertFalse(ClientWorldUploads.edit(EDIT, outcome -> {
        }));
        assertFalse(ClientWorldUploads.isFull());
    }
}
