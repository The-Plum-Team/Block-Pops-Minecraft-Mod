package com.theplumteam.client.worldcontent;

import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.theplumteam.client.worldcontent.Fixtures.COLLECTION;
import static com.theplumteam.client.worldcontent.Fixtures.blobsOf;
import static com.theplumteam.client.worldcontent.Fixtures.draftRecord;
import static com.theplumteam.client.worldcontent.Fixtures.edits;
import static com.theplumteam.client.worldcontent.Fixtures.logo;
import static com.theplumteam.client.worldcontent.Fixtures.ofType;
import static com.theplumteam.client.worldcontent.Fixtures.publishedRecord;
import static com.theplumteam.client.worldcontent.Fixtures.skin;
import static com.theplumteam.client.worldcontent.Fixtures.storedSkin;
import static com.theplumteam.client.worldcontent.Fixtures.unit;
import static com.theplumteam.client.worldcontent.Fixtures.unitKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CreatorSave#planAvailable}: held units, the logo placement, isolation and staging (SPEC §3.4, §3.8). */
class CreatorSavePlanTest extends CreatorTestBase {
    /** What the saver tells the planner, set by each test. */
    private static final class TestHold implements CreatorSave.Hold {
        boolean files = true;
        final Set<String> typing = new HashSet<>();
        int unitsPerBatch = Integer.MAX_VALUE;
        final Map<String, List<WorldCollectionEdit>> refusedEdits = new HashMap<>();

        @Override
        public boolean filesReleased() {
            return files;
        }

        @Override
        public boolean typing(String figureId) {
            return typing.contains(figureId);
        }

        @Override
        @Nullable
        public Component refused(String unitKey, List<WorldCollectionEdit> edits) {
            return edits.equals(refusedEdits.get(unitKey)) ? Component.literal("refused " + unitKey) : null;
        }

        @Override
        public int unitsPerBatch() {
            return unitsPerBatch;
        }
    }

    private final TestHold hold = new TestHold();

    private CreatorDraft synced(WorldCollectionRecord record) {
        ClientWorldState.sync(record);
        return CreatorDraft.of(record);
    }

    private static List<String> shas(CreatorSave.Submission submission) {
        return submission.files().stream().map(FilePicker.LocalFile::sha).toList();
    }

    private static List<String> held(CreatorSave.Available available) {
        return available.held().stream().map(unit -> unit.unitKey() + ":" + unit.reason()).toList();
    }

    @Test
    void aHeldUnitHoldsOnlyItself() {
        CreatorDraft draft = synced(draftRecord(storedSkin("a", "A")));
        draft.setName(" ");
        draft.setPlush(true);
        draft.setHolographic(false);
        draft.addFigure("Bee");

        CreatorSave.Available available = CreatorSave.planAvailable(draft, false, hold);

        assertEquals(List.of("identity:NAME", "figure/bee:SKIN"), held(available));
        assertEquals("bee", available.held().get(1).figureId());
        assertEquals(List.of(List.of("extras", "holographic")), unitKeys(available.plan()));
        List<WorldCollectionEdit> sent = edits(available.plan());
        assertTrue(ofType(sent, WorldCollectionEdit.SetCollectionExtras.class).get(0).plush());
        assertEquals(List.of(new WorldCollectionEdit.SetHolographic(COLLECTION, false)),
                ofType(sent, WorldCollectionEdit.SetHolographic.class));
        assertTrue(ofType(sent, WorldCollectionEdit.ReorderFigures.class).isEmpty());
    }

    @Test
    void whileTheIdentityIsHeldTheExtrasGoWithoutALogoPlacementTheServerCannotTake() {
        CreatorDraft draft = synced(draftRecord());
        FilePicker.LocalFile logo = logo("logo");
        LogoLayout layout = new LogoLayout(120, 4, -2);
        draft.setLogo(FileRef.of(logo));
        draft.setLogoLayout(layout);
        draft.setPlush(true);
        draft.setName("");

        CreatorSave.Available held = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of("identity:NAME"), held(held));
        assertEquals(List.of(List.of("extras")), unitKeys(held.plan()));
        WorldCollectionEdit.SetCollectionExtras extras = ofType(edits(held.plan()),
                WorldCollectionEdit.SetCollectionExtras.class).get(0);
        assertNull(extras.logoLayout());
        assertTrue(extras.plush());
        // The placement is not remembered as sent, so it goes again once the logo has landed
        assertFalse(unit(held.plan(), "extras").values().containsKey(CreatorDraft.LOGO_LAYOUT));

        draft.setName("Named");
        CreatorSave.Available named = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of(List.of("identity", "extras")), unitKeys(named.plan()));
        List<WorldCollectionEdit> sent = edits(named.plan());
        assertEquals(logo.sha(), ofType(sent, WorldCollectionEdit.SetCollection.class).get(0).logo());
        assertEquals(layout, ofType(sent, WorldCollectionEdit.SetCollectionExtras.class).get(0).logoLayout());
        assertEquals(List.of(logo.sha()), shas(named.plan().submissions().get(0)));
        assertEquals(layout, unit(named.plan(), "extras").values().get(CreatorDraft.LOGO_LAYOUT));

        // The logo waits for the operator to move on, and the extras do not wait with it
        hold.files = false;
        CreatorSave.Available waiting = CreatorSave.planAvailable(draft, false, hold);
        assertTrue(waiting.filesWaiting());
        assertEquals(List.of(List.of("extras")), unitKeys(waiting.plan()));
        assertNull(ofType(edits(waiting.plan()), WorldCollectionEdit.SetCollectionExtras.class).get(0).logoLayout());
    }

    @Test
    void isolatingSendsOneUnitABatchAndTheReorderNamesOnlyFiguresThatLand() {
        CreatorDraft draft = synced(draftRecord(storedSkin("a", "A"), storedSkin("b", "B")));
        draft.moveFigure(0, 1);
        draft.addFigure("Cat").setTexture(FileRef.of(skin("cat")));
        draft.addFigure("Dog");
        draft.addFigure("Eel").setTexture(FileRef.of(skin("eel")));
        hold.typing.add("eel");
        hold.unitsPerBatch = 1;

        CreatorSave.Available isolated = CreatorSave.planAvailable(draft, false, hold);

        assertEquals(List.of("figure/dog:SKIN"), held(isolated));
        assertTrue(isolated.typingWaits());
        assertEquals(List.of(List.of("figure/cat"), List.of("order")), unitKeys(isolated.plan()));
        assertEquals(List.of(new WorldCollectionEdit.ReorderFigures(COLLECTION, List.of("b", "a", "cat"))),
                ofType(edits(isolated.plan()), WorldCollectionEdit.ReorderFigures.class));

        hold.unitsPerBatch = Integer.MAX_VALUE;
        assertEquals(List.of(List.of("figure/cat", "order")),
                unitKeys(CreatorSave.planAvailable(draft, false, hold).plan()));
    }

    @Test
    void aUnitRefusedAloneIsHeldUntilItChangesAndLeavesTheReorder() {
        CreatorDraft draft = synced(draftRecord(storedSkin("a", "A"), storedSkin("b", "B")));
        draft.moveFigure(0, 1);
        FigureDraft cat = draft.addFigure("Cat");
        cat.setTexture(FileRef.of(skin("cat")));
        CreatorSave.Available first = CreatorSave.planAvailable(draft, false, hold);
        hold.refusedEdits.put("figure/cat", unit(first.plan(), "figure/cat").edits());

        CreatorSave.Available refused = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of("figure/cat:REFUSED"), held(refused));
        assertEquals("cat", refused.held().get(0).figureId());
        assertEquals(List.of(new WorldCollectionEdit.ReorderFigures(COLLECTION, List.of("b", "a"))),
                edits(refused.plan()));

        cat.setScale(1.5f);
        assertEquals(List.of(List.of("figure/cat", "order")),
                unitKeys(CreatorSave.planAvailable(draft, false, hold).plan()));
    }

    @Test
    void holographicDrawsGoAloneWhileTheIdentityAndTheExtrasAreHeld() {
        CreatorDraft draft = synced(draftRecord());
        draft.setName("");
        draft.setAuthorUrl("ftp://example.com");
        draft.setHolographic(false);

        CreatorSave.Available available = CreatorSave.planAvailable(draft, false, hold);

        assertEquals(List.of("identity:NAME", "extras:LINK"), held(available));
        assertEquals(List.of(new WorldCollectionEdit.SetHolographic(COLLECTION, false)), edits(available.plan()));
        assertEquals(Map.of(CreatorDraft.HOLOGRAPHIC, Boolean.FALSE), unit(available.plan(), "holographic").values());

        hold.refusedEdits.put("holographic", List.of(new WorldCollectionEdit.SetHolographic(COLLECTION, false)));
        CreatorSave.Available refused = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of("identity:NAME", "extras:LINK", "holographic:REFUSED"), held(refused));
        assertTrue(refused.isEmpty());
    }

    @Test
    void aPublishedCollectionsChangeOverSeveralBatchesBringsItsNewFiguresInTogetherAtTheEnd() {
        CreatorDraft draft = synced(publishedRecord(storedSkin("a", "A")));
        draft.setName("Renamed");
        draft.addFigure("Cat").setTexture(FileRef.of(skin("cat")));
        draft.addFigure("Dog").setTexture(FileRef.of(skin("dog")));
        hold.unitsPerBatch = 1;

        CreatorSave.Plan staged = CreatorSave.planAvailable(draft, false, hold).plan();

        assertEquals(List.of(List.of("identity"), List.of("figure/cat"), List.of("figure/dog"), List.of("enable")),
                unitKeys(staged));
        List<WorldCollectionEdit.SetFigureEnabled> enabled = ofType(edits(staged),
                WorldCollectionEdit.SetFigureEnabled.class);
        assertEquals(List.of(
                new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "cat", false),
                new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "dog", false),
                new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "cat", true),
                new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "dog", true)), enabled);

        // In one batch the change lands whole, so nothing is staged
        hold.unitsPerBatch = Integer.MAX_VALUE;
        CreatorSave.Plan whole = CreatorSave.planAvailable(draft, false, hold).plan();
        assertEquals(1, whole.submissions().size());
        assertTrue(ofType(edits(whole), WorldCollectionEdit.SetFigureEnabled.class).isEmpty());
    }

    @Test
    void aCollectionOverTheServersBudgetHoldsEverything() {
        CreatorDraft draft = synced(draftRecord());
        byte[] bytes = "png huge".getBytes(StandardCharsets.UTF_8);
        // What the picker would say of a file over the collection's 16 MiB
        FilePicker.LocalFile huge = new FilePicker.LocalFile("huge.png", BlobType.PNG, bytes, Sha256.of(bytes),
                BlobInfo.png(17 * 1024 * 1024, 64, 64));
        draft.addFigure("Huge").setTexture(FileRef.of(huge));
        draft.setPlush(true);

        CreatorSave.Available available = CreatorSave.planAvailable(draft, false, hold);

        assertEquals(List.of("draft:TOO_BIG"), held(available));
        assertTrue(available.isEmpty());
    }

    @Test
    void aFileTheServerLostIsHeldUnlessThisComputerStillHasItsBytes() {
        WorldCollectionRecord record = draftRecord(storedSkin("a", "A"));
        ClientWorldState.sync(blobsOf(record), Set.of(skin("a").sha()), record);
        CreatorDraft draft = CreatorDraft.of(record);
        FigureDraft a = draft.figure("a");
        a.setName("Ay");

        CreatorSave.Available lost = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of("figure/a:LOST_FILE"), held(lost));

        a.setTexture(FileRef.of(skin("a")));
        CreatorSave.Available reupload = CreatorSave.planAvailable(draft, false, hold);
        assertEquals(List.of(), held(reupload));
        assertEquals(List.of(List.of("figure/a")), unitKeys(reupload.plan()));
        assertEquals(List.of(skin("a").sha()), shas(reupload.plan().submissions().get(0)));
    }

    @Test
    void aDraftsFigureIsDeletedAndAPublishedOnesIsRetired() {
        CreatorDraft draft = synced(draftRecord(storedSkin("a", "A")));
        assertTrue(draft.removeFigure("a"));
        assertEquals(List.of(new WorldCollectionEdit.RemoveFigure(COLLECTION, "a")),
                edits(CreatorSave.planAvailable(draft, false, hold).plan()));

        CreatorDraft published = synced(publishedRecord(storedSkin("a", "A")));
        assertFalse(published.removeFigure("a"));
        assertEquals(List.of(new WorldCollectionEdit.SetFigureEnabled(COLLECTION, "a", false)),
                edits(CreatorSave.planAvailable(published, false, hold).plan()));
    }
}
