package com.theplumteam.client.worldcontent;

import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.worldcontent.Fixtures.COLLECTION;
import static com.theplumteam.client.worldcontent.Fixtures.draftRecord;
import static com.theplumteam.client.worldcontent.Fixtures.named;
import static com.theplumteam.client.worldcontent.Fixtures.ofType;
import static com.theplumteam.client.worldcontent.Fixtures.skin;
import static com.theplumteam.client.worldcontent.Fixtures.storedSkin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saver of a collection being edited (SPEC §3.2, §3.3, §3.7, §3.8), against a {@link FakeServer}
 * whose clock each test moves.
 */
class AutoSaverTest extends CreatorTestBase {
    private WorldCollectionRecord base;
    private AutoSaver saver;
    private CreatorDraft draft;

    private void open(WorldCollectionRecord record) {
        base = record;
        ClientWorldState.sync(record);
        saver = CreatorAutosave.attach(COLLECTION);
        draft = saver.draft();
    }

    @Test
    void aTypedTextSettlesBeforeItIsSentAndOneAutosaveFollowsAnotherOnlyAfterTheInterval() {
        open(draftRecord(storedSkin("a", "A")));
        draft.setName("Alpha");
        tick();
        server.advance(AutoSaver.TEXT_MS - 1);
        tick();
        assertEquals(List.of(), server.edits);

        server.advance(1);
        tick();
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());
        server.answer(Result.OK);

        // A discrete change settles in 400 ms, but the run waits for the sync that shows the save
        draft.setPlush(true);
        server.advance(AutoSaver.DISCRETE_MS);
        tick();
        assertEquals(1, server.edits.size());
        ClientWorldState.sync(named(base, "Alpha").withRevision(2));
        tick();
        assertEquals(1, server.edits.size());

        // ... and then for five seconds after the last autosave
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS - AutoSaver.DISCRETE_MS - 1);
        tick();
        assertEquals(1, server.edits.size());
        server.advance(1);
        tick();
        assertTrue(assertInstanceOf(WorldCollectionEdit.SetCollectionExtras.class, server.lastEdit()).plush());
    }

    @Test
    void aTextFieldThatLosesTheKeyboardIsDueAtOnce() {
        open(draftRecord());
        draft.setName("Alpha");
        saver.textLeft();
        tick();
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());
    }

    @Test
    void filesWaitUntilTheOperatorMovesOnAndOnlyTheLastPickIsUploaded() {
        open(draftRecord(storedSkin("a", "A")));
        FigureDraft a = draft.figure("a");
        for (int i = 1; i <= 10; i++) {
            a.setTexture(FileRef.of(skin("try" + i)));
        }
        server.advance(10_000);
        tick();
        assertEquals(List.of(), server.uploads);
        assertEquals(AutoSaver.State.FILES_WAITING, saver.status().state());

        saver.flushNow();
        tick();
        server.answer(Result.OK);

        assertEquals(List.of(skin("try10").sha()), server.uploads);
        assertEquals(skin("try10").sha(),
                assertInstanceOf(WorldCollectionEdit.PutFigure.class, server.lastEdit()).texture());
    }

    @Test
    void aBusyServerIsAskedAgainAfterTheBackoff() {
        open(draftRecord());
        draft.setName("Alpha");
        saver.textLeft();
        tick();
        server.answer(Result.BUSY);
        assertEquals(AutoSaver.State.BUSY, saver.status().state());

        server.advance(AutoSaver.BUSY_BACKOFF_MS - 1);
        tick();
        assertEquals(1, server.edits.size());
        server.advance(1);
        tick();
        assertEquals(2, server.edits.size());
    }

    @Test
    void aSaveTheServerNeverAnswersTimesOutAndIsSentAgainAfterTheBackoff() {
        open(draftRecord());
        draft.setName("Alpha");
        saver.textLeft();
        tick();
        assertEquals(1, server.edits.size());

        server.advance(ClientWorldUploads.ANSWER_TIMEOUT_MS);
        tick();
        assertEquals(AutoSaver.State.SAVING, saver.status().state());
        server.advance(1);
        tick();
        assertEquals(AutoSaver.State.BUSY, saver.status().state());

        server.advance(AutoSaver.BUSY_BACKOFF_MS - 1);
        tick();
        assertEquals(1, server.edits.size());
        server.advance(1);
        tick();
        assertEquals(2, server.edits.size());
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());

        // The answer to the save that timed out comes late and is not taken for the one sent again
        server.answer(Result.OK);
        assertEquals(AutoSaver.State.SAVING, saver.status().state());
        server.answer(Result.OK);
        ClientWorldState.sync(named(base, "Alpha").withRevision(2));
        tick();
        assertEquals(AutoSaver.State.SAVED, saver.status().state());
        assertEquals(2, server.edits.size());
    }

    @Test
    void changesWaitWhileTheClientIsOffline() {
        open(draftRecord());
        server.connected = false;
        draft.setName("Alpha");
        saver.textLeft();
        tick();
        assertEquals(AutoSaver.State.OFFLINE, saver.status().state());
        assertEquals(List.of(), server.edits);

        server.connected = true;
        tick();
        assertEquals(1, server.edits.size());
    }

    @Test
    void aPublishSendsWhatWaitsFirstAndThePublishedSyncTurnsTheSaverToHold() {
        open(draftRecord(storedSkin("a", "A")));
        draft.setName("Alpha");
        List<Component> published = new ArrayList<>();
        saver.publish(current -> null, published::add);

        tick();
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());
        server.answer(Result.OK);
        tick();
        assertEquals(1, server.edits.size());

        WorldCollectionRecord saved = named(base, "Alpha").withRevision(2);
        ClientWorldState.sync(saved);
        tick();
        assertEquals(new WorldCollectionEdit.Publish(COLLECTION), server.lastEdit());
        server.answer(Result.OK);
        assertEquals(1, published.size());
        assertNull(published.get(0));

        ClientWorldState.sync(saved.withDraft(false).withRevision(3));
        tick();
        assertEquals(AutoSaver.Mode.HOLD, saver.mode());
        assertFalse(draft.canUndo());
        assertFalse(draft.hasUnconfirmedSends());

        // A published collection's changes wait for Publish changes
        draft.setPlush(true);
        server.advance(60_000);
        tick();
        assertEquals(2, server.edits.size());
        assertEquals(AutoSaver.State.UNPUBLISHED, saver.status().state());

        List<Component> changes = new ArrayList<>();
        saver.publishChanges(changes::add);
        assertTrue(assertInstanceOf(WorldCollectionEdit.SetCollectionExtras.class, server.lastEdit()).plush());
        server.answer(Result.OK);
        assertEquals(1, changes.size());
        assertNull(changes.get(0));
    }

    @Test
    void aSaveTheServerRefusesIsNarrowedDownToTheUnitItRefusesWhichIsHeldUntilItChanges() {
        open(draftRecord(storedSkin("a", "A")));
        draft.setName("Alpha");
        FigureDraft cat = draft.addFigure("Cat");
        cat.setTexture(FileRef.of(skin("cat")));
        saver.flushNow();
        tick();
        WorldCollectionEdit.Batch both = assertInstanceOf(WorldCollectionEdit.Batch.class,
                server.answer(Result.INVALID, "Figure cat is wrong"));
        assertEquals(2, both.edits().size());

        // One unit a batch: the identity lands, the figure is refused alone. Its file goes again only
        // once the operator moves on, as every file does
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS);
        saver.flushNow();
        tick();
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());
        server.answer(Result.OK);
        WorldCollectionEdit refused = server.answer(Result.INVALID, "Figure cat is wrong");
        assertEquals("cat", assertInstanceOf(WorldCollectionEdit.PutFigure.class, refused).figure());

        assertEquals(AutoSaver.State.FAILED, saver.status().state());
        assertEquals(List.of("figure/cat:REFUSED"),
                saver.held().stream().map(unit -> unit.unitKey() + ":" + unit.reason()).toList());

        // Nothing is sent again while the figure is as the server refused it
        ClientWorldState.sync(named(base, "Alpha").withRevision(2));
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS);
        tick();
        assertEquals(3, server.edits.size());

        cat.setScale(1.5f);
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS);
        tick();
        assertEquals(3, server.edits.size());
        saver.flushNow();
        tick();
        WorldCollectionEdit.PutFigure changed = assertInstanceOf(WorldCollectionEdit.PutFigure.class,
                server.answer(Result.OK));
        assertEquals("cat", changed.figure());
        assertEquals(WorldCollectionRecord.scaleIndex(1.5f), changed.scaleIndex());
        assertEquals(4, server.edits.size());
    }

    @Test
    void aFiguresIdHoldsStillOnlyWhileItsOwnPutIsOnItsWayAndThenStaysOnceTheServerTookIt() {
        open(draftRecord(storedSkin("a", "A")));
        FigureDraft zom = draft.addFigure("Zom");
        zom.setTexture(FileRef.of(skin("zom")));
        saver.flushNow();
        tick();
        assertTrue(saver.isSaving("zom"));
        assertFalse(saver.isSaving("a"));

        // The inspector renames it without moving its id while the put is on its way
        zom.setName("Zombie");
        saver.renamedWhileSaving("zom");
        server.answer(Result.OK);

        assertFalse(saver.isSaving("zom"));
        assertEquals("zom", zom.id());
        assertFalse(zom.isNew());
    }

    @Test
    void aNameTypedWhileAPutWasOnItsWayDecidesTheIdWhenThePutDidNotLand() {
        open(draftRecord());
        FigureDraft zom = draft.addFigure("Zom");
        zom.setTexture(FileRef.of(skin("zom")));
        saver.flushNow();
        tick();
        zom.setName("Zombie");
        saver.renamedWhileSaving("zom");
        server.answer(Result.NOT_ALLOWED);

        assertTrue(zom.isNew());
        assertEquals("zombie", zom.id());
        assertTrue(saver.hasFailed());
    }

    @Test
    void theRowsAStoppedBulkAddDidNotSendStayOutUntilUndoBringsThemBackToAutosave() {
        open(draftRecord());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            FigureDraft figure = draft.addFigure("Fig " + i);
            figure.setTexture(FileRef.of(skin("fig_" + i)));
            ids.add(figure.id());
        }
        Object screen = new Object();
        saver.pause(screen);
        List<CreatorSave.Outcome> outcomes = new ArrayList<>();
        CreatorSave.Run run = saver.saveFigures(ids, outcomes::add);
        assertEquals(2, run.plan().submissions().size());

        // Stopped while its first batch is on its way: that batch lands, the second is never sent
        run.cancel();
        server.answer(Result.OK);
        assertEquals(1, outcomes.size());
        assertEquals(CreatorSave.Ending.CANCELLED, outcomes.get(0).ending());
        assertEquals(1, outcomes.get(0).saved());
        assertEquals(1, server.edits.size());
        assertEquals(CreatorSave.BULK_FIGURES_PER_BATCH,
                ofType(List.of(server.lastEdit()), WorldCollectionEdit.Batch.class).get(0).edits().size());

        // The screen takes the rows not sent out of the draft, in one undo step
        List<String> unsent = ids.stream().filter(id -> draft.figure(id).isNew()).toList();
        assertEquals(ids.subList(8, 10), unsent);
        draft.change("bulk/left", () -> unsent.forEach(draft::removeFigure));
        saver.resume(screen);

        List<Figure> landed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            landed.add(storedSkin("fig_" + i, "Fig " + i));
        }
        ClientWorldState.sync(base.withFigures(landed).withRevision(2));
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS);
        tick();
        assertEquals(1, server.edits.size());
        assertEquals(List.of(), draft.changes());

        // Back as ordinary new figures, whose files go once the operator moves on
        assertTrue(draft.undo());
        server.advance(AutoSaver.DISCRETE_MS);
        tick();
        assertEquals(1, server.edits.size());
        saver.flushNow();
        tick();
        WorldCollectionEdit.Batch rest = assertInstanceOf(WorldCollectionEdit.Batch.class, server.answer(Result.OK));
        assertEquals(unsent, ofType(rest.edits(), WorldCollectionEdit.PutFigure.class).stream()
                .map(WorldCollectionEdit.PutFigure::figure).toList());
    }

    @Test
    void suspendingForAPurgeDropsTheQueuedSaveAndARefusedPurgeLetsItGoAgain() {
        open(draftRecord());
        // An action of the home's is on its way, so the save queues behind it
        ClientWorldUploads.edit(new WorldCollectionEdit.SetHolographic("w-other", false), outcome -> {
        });
        draft.setName("Alpha");
        saver.textLeft();
        tick();
        assertTrue(saver.hasFlight());
        assertEquals(1, server.edits.size());

        Object purge = new Object();
        saver.suspend(purge);
        assertFalse(saver.hasFlight());
        server.answer(Result.OK);
        assertEquals(1, server.edits.size());
        assertEquals(List.of(CreatorDraft.NAME), draft.changes());

        saver.resume(purge);
        server.advance(AutoSaver.MIN_FLUSH_INTERVAL_MS);
        tick();
        assertEquals("Alpha", assertInstanceOf(WorldCollectionEdit.SetCollection.class, server.lastEdit()).name());
    }

    @Test
    void aPurgedCollectionsSaverEndsAndWhatItHadOnItsWayIsNoLongerHeard() {
        open(draftRecord());
        draft.setName("Alpha");
        List<Component> published = new ArrayList<>();
        saver.publish(current -> null, published::add);
        tick();
        assertTrue(saver.hasFlight());

        CreatorAutosave.cancel(COLLECTION);
        assertTrue(saver.isCancelled());
        assertNull(CreatorAutosave.get(COLLECTION));

        server.answer(Result.OK);
        assertFalse(draft.hasUnconfirmedSends());
        assertEquals(List.of(), published);
        ClientWorldState.sync();
        server.advance(60_000);
        tick();
        assertEquals(1, server.edits.size());
    }

    @Test
    void eachChangeKeySettlesByItsKind() {
        assertEquals(AutoSaver.KeyClass.TEXT, AutoSaver.classOf(CreatorDraft.NAME));
        assertEquals(AutoSaver.KeyClass.TEXT, AutoSaver.classOf(CreatorDraft.AUTHOR_URL));
        assertEquals(AutoSaver.KeyClass.TEXT, AutoSaver.classOf("figure/zom/name"));
        assertEquals(AutoSaver.KeyClass.TEXT, AutoSaver.classOf("figure/zom/variants"));
        assertEquals(AutoSaver.KeyClass.NUMBER, AutoSaver.classOf(CreatorDraft.RARITY_WEIGHTS));
        assertEquals(AutoSaver.KeyClass.NUMBER, AutoSaver.classOf(CreatorDraft.LOGO_LAYOUT));
        assertEquals(AutoSaver.KeyClass.FILE, AutoSaver.classOf(CreatorDraft.LOGO));
        assertEquals(AutoSaver.KeyClass.FILE, AutoSaver.classOf("figure/zom/texture"));
        assertEquals(AutoSaver.KeyClass.FILE, AutoSaver.classOf("figure/zom/model"));
        assertEquals(AutoSaver.KeyClass.DISCRETE, AutoSaver.classOf(CreatorDraft.HOLOGRAPHIC));
        assertEquals(AutoSaver.KeyClass.DISCRETE, AutoSaver.classOf(CreatorDraft.ORDER));
        assertEquals(AutoSaver.KeyClass.DISCRETE, AutoSaver.classOf("figure/zom/rarity"));
        assertEquals(AutoSaver.KeyClass.DISCRETE, AutoSaver.classOf("bulk/left"));
    }
}
