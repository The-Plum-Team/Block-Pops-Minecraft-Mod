package com.theplumteam.client.worldcontent;

import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.theplumteam.client.worldcontent.Fixtures.COLLECTION;
import static com.theplumteam.client.worldcontent.Fixtures.draftRecord;
import static com.theplumteam.client.worldcontent.Fixtures.edits;
import static com.theplumteam.client.worldcontent.Fixtures.named;
import static com.theplumteam.client.worldcontent.Fixtures.ofType;
import static com.theplumteam.client.worldcontent.Fixtures.skin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The draft's sent-tracking rebase (SPEC §3.5), its figure ids (§3.4) and its undo steps. */
class CreatorDraftTest extends CreatorTestBase {
    @Test
    void aValueChangedBackBeforeTheSyncThatShowsItsSaveIsKeptAndSentAgain() {
        WorldCollectionRecord base = named(draftRecord(), "Alpha");
        CreatorDraft draft = CreatorDraft.of(base);
        draft.setName("Beta");
        int answeredAt = ClientWorldCollections.revision();
        draft.markSent(Map.of(CreatorDraft.NAME, "Beta"), List.of());
        draft.setName("Alpha");

        draft.rebase(named(base, "Beta").withRevision(2), answeredAt + 1);

        assertEquals("Alpha", draft.name());
        assertEquals(List.of(CreatorDraft.NAME), draft.changes());
        assertFalse(draft.hasUnconfirmedSends());
        List<WorldCollectionEdit.SetCollection> sets = ofType(edits(CreatorSave.plan(draft, false)),
                WorldCollectionEdit.SetCollection.class);
        assertEquals(1, sets.size());
        assertEquals("Alpha", sets.get(0).name());
    }

    @Test
    void aSentValueFollowsTheServerOnceASyncShowsIt() {
        WorldCollectionRecord base = named(draftRecord(), "Alpha");
        CreatorDraft draft = CreatorDraft.of(base);
        draft.setName("Beta");
        int answeredAt = ClientWorldCollections.revision();
        draft.markSent(Map.of(CreatorDraft.NAME, "Beta"), List.of());

        WorldCollectionRecord saved = named(base, "Beta").withRevision(2);
        draft.rebase(saved, answeredAt + 1);
        assertEquals(List.of(), draft.changes());

        // Another operator renames it afterwards: the field is untouched here, so it follows
        draft.rebase(named(saved, "Gamma").withRevision(3), answeredAt + 2);
        assertEquals("Gamma", draft.name());
        assertEquals(List.of(), draft.changes());
    }

    @Test
    void aSyncMadeBeforeTheAnswerLeavesTheSentValueAndTakesTheUntouchedOnes() {
        WorldCollectionRecord base = named(draftRecord(), "Alpha");
        CreatorDraft draft = CreatorDraft.of(base);
        draft.setName("Beta");
        int answeredAt = ClientWorldCollections.revision();
        draft.markSent(Map.of(CreatorDraft.NAME, "Beta"), List.of());

        // Applied before the answer: it cannot hold the name yet, but holds another operator's plush
        draft.rebase(base.withExtras(null, null, true, null).withRevision(2), answeredAt);

        assertEquals("Beta", draft.name());
        assertTrue(draft.plush());
        assertTrue(draft.hasUnconfirmedSends());
    }

    @Test
    void aFigurePutAndThenDeletedHereIsNotBroughtBackByTheSyncThatHoldsIt() {
        WorldCollectionRecord base = draftRecord();
        CreatorDraft draft = CreatorDraft.of(base);
        FigureDraft zombi = draft.addFigure("Zombi");
        zombi.setTexture(FileRef.of(skin("zombi")));
        int answeredAt = ClientWorldCollections.revision();
        draft.markSent(Map.of("figure/zombi/new", Boolean.TRUE), List.of("zombi"));
        assertFalse(zombi.isNew());
        assertTrue(draft.removeFigure("zombi"));

        Figure stored = new Figure("zombi", "Zombi", Kind.SKIN, skin("zombi").sha(), null, null, 1.0f, true);
        draft.rebase(base.withFigures(List.of(stored)).withRevision(2), answeredAt + 1);

        assertNull(draft.figure("zombi"));
        assertEquals(List.of("figure/zombi/removed"), draft.changes());
        assertEquals(List.of(new WorldCollectionEdit.RemoveFigure(COLLECTION, "zombi")),
                edits(CreatorSave.plan(draft, false)));
    }

    @Test
    void aFigureTheServerTookKeepsItsIdWhileANewOneFollowsItsName() {
        CreatorDraft draft = CreatorDraft.of(draftRecord());
        FigureDraft zombi = draft.addFigure("Zom");
        assertEquals("zom", zombi.id());
        zombi.rename("Zombi");
        assertEquals("zombi", zombi.id());
        FigureDraft bat = draft.addFigure("Bat");

        draft.markSent(Map.of("figure/zombi/new", Boolean.TRUE), List.of("zombi"));
        zombi.rename("Zombie king");
        bat.rename("Bats");

        assertEquals("zombi", zombi.id());
        assertEquals("Zombie king", zombi.name());
        assertEquals("bats", bat.id());
        // The id the server holds stays taken even before a sync shows it
        assertTrue(draft.isFigureIdTaken("zombi"));
    }

    @Test
    void aNameTypedWhileTheFiguresPutWasOnItsWayDecidesTheIdAfterwards() {
        CreatorDraft draft = CreatorDraft.of(draftRecord());
        FigureDraft cat = draft.addFigure("Cat");
        // While its put is on its way the inspector renames it without moving the id
        cat.setName("Cow");
        assertEquals("cat", cat.id());

        cat.followName();
        assertEquals("cow", cat.id());

        draft.markSent(Map.of(), List.of("cow"));
        cat.setName("Calf");
        cat.followName();
        assertEquals("cow", cat.id());
    }

    @Test
    void theRowsABulkAddLeftOutGoInOneUndoStepAndComeBackAsNewFigures() {
        CreatorDraft draft = CreatorDraft.of(draftRecord());
        List<String> ids = new ArrayList<>();
        for (String name : List.of("One", "Two", "Three")) {
            FigureDraft figure = draft.addFigure(name);
            figure.setTexture(FileRef.of(skin(name)));
            ids.add(figure.id());
        }
        draft.checkpoint();
        draft.markSent(Map.of(), List.of(ids.get(0)));
        List<String> heard = new ArrayList<>();
        draft.listen(heard::add);

        draft.change("bulk/left", () -> {
            draft.removeFigure(ids.get(1));
            draft.removeFigure(ids.get(2));
        });
        assertEquals(List.of(ids.get(0)), draft.figures().stream().map(FigureDraft::id).toList());
        assertEquals("bulk/left", heard.get(0));

        assertTrue(draft.undo());
        assertEquals(ids, draft.figures().stream().map(FigureDraft::id).toList());
        assertFalse(draft.figure(ids.get(0)).isNew());
        assertTrue(draft.figure(ids.get(1)).isNew());
        assertTrue(draft.figure(ids.get(2)).isNew());
        assertNotNull(draft.figure(ids.get(2)).texture().local());
    }

    @Test
    void holographicDrawsAreOneUndoStepAndRevertToTheServers() {
        WorldCollectionRecord base = draftRecord();
        CreatorDraft draft = CreatorDraft.of(base);
        assertTrue(draft.holographic());

        draft.setHolographic(false);
        assertEquals(List.of(CreatorDraft.HOLOGRAPHIC), draft.changes());
        assertFalse(draft.toRecord().holographic());
        assertTrue(draft.undo());
        assertTrue(draft.holographic());
        assertEquals(List.of(), draft.changes());
        assertFalse(draft.canUndo());

        draft.setHolographic(false);
        draft.revertHolographic();
        assertTrue(draft.holographic());
        assertEquals(List.of(), draft.changes());
    }

    @Test
    void holographicDrawsFollowTheSentTrackingRebase() {
        WorldCollectionRecord base = draftRecord();
        CreatorDraft draft = CreatorDraft.of(base);
        draft.setHolographic(false);
        int answeredAt = ClientWorldCollections.revision();
        draft.markSent(Map.of(CreatorDraft.HOLOGRAPHIC, Boolean.FALSE), List.of());

        WorldCollectionRecord off = base.withHolographic(false).withRevision(2);
        draft.rebase(off, answeredAt + 1);
        assertEquals(List.of(), draft.changes());
        assertFalse(draft.hasUnconfirmedSends());

        // Turned on again by another operator: untouched here, so the draft follows
        draft.rebase(off.withHolographic(true).withRevision(3), answeredAt + 2);
        assertTrue(draft.holographic());
        assertEquals(List.of(), draft.changes());
    }
}
