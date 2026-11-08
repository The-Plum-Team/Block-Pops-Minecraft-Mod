package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.network.WorldCollectionEditPacket;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns a {@link CreatorDraft} into the edits that save it and sends them.
 *
 * <p>A save sends only what changed: SetCollection when the name, author, colour, box or
 * logo did, SetCollectionExtras for the author link, logo placement, plush flag and rarity
 * weights, SetHolographic when holographic draws were turned on or off, then each changed
 * figure's PutFigure, SetFigureExtras and SetFigureEnabled together, ReorderFigures when the
 * order differs from what the server would keep, and Publish last when asked. A collection that
 * does not exist yet is created first, as a draft. A figure that misses a file is not saved and
 * stays unsaved in the draft.
 *
 * <p>The edits are packed into batches of at most {@link WorldCollectionEdit#MAX_BATCH}
 * edits and {@link WorldCollectionEditPacket#MAX_CLIENT_BYTES} bytes; each batch is one
 * submission carrying the local files its edits name first, and the server applies it
 * whole or not at all. A bulk add of figures also puts at most
 * {@value #BULK_FIGURES_PER_BATCH} figures in a batch. Submissions are sent one after another,
 * each once the one before it was saved, so a refusal stops the save with the earlier batches
 * kept. A create the server refuses because the slug is taken (it may belong to a purged
 * collection the client never sees) tries the next slug from {@link Slugs#next}, at most
 * {@value #MAX_SLUG_RETRIES} times.
 *
 * <p>{@link #planAvailable} plans what can be sent now and holds the rest: a unit the server would
 * refuse as it is (a blank name, a box chosen without its image, a figure missing a file or failing
 * a check of its files), a unit with a file to upload until the operator moves on, a new figure's
 * first put while its name is being typed, since it fixes the figure's id, and a unit the server
 * refused alone until it changes. What is held stays in the draft; one held unit never keeps the
 * others from being saved, and a reorder names only the figures the server will have. A published
 * collection's changes that need more than one batch put their new figures retired and bring them
 * into the draw together in the last batch, so players never see part of them.
 *
 * <p>Each submission says what its units change ({@link UnitInfo}), so the draft can remember what
 * the server took ({@link CreatorDraft#markSent}). Every run counts in {@link CreatorRuns} from the
 * moment it starts until the sync after it.
 *
 * Client thread only.
 */
public final class CreatorSave {
    /** How many other slugs a create tries after ID_TAKEN. */
    public static final int MAX_SLUG_RETRIES = 5;
    /**
     * How many figures one batch of a bulk add holds, so the save can be paused or stopped
     * between batches and each batch's figures arrive in a sync of their own.
     */
    public static final int BULK_FIGURES_PER_BATCH = 8;
    private static final String KEY = "blockpops.world_creator.";

    private CreatorSave() {
    }

    /**
     * One batch to send: the files it needs that the server does not have, then the edit.
     *
     * @param edit  a {@link WorldCollectionEdit.Batch}, or the edit alone when there is one
     * @param edits how many edits it holds
     * @param units what the batch changes, unit by unit, in order
     */
    public record Submission(List<FilePicker.LocalFile> files, WorldCollectionEdit edit, int edits,
                             List<UnitInfo> units) {
        public Submission {
            files = List.copyOf(files);
            units = List.copyOf(units);
        }

        /** A batch that says nothing of its units, as a create's or a duplicate's. */
        public Submission(List<FilePicker.LocalFile> files, WorldCollectionEdit edit, int edits) {
            this(files, edit, edits, List.of());
        }
    }

    /**
     * One unit of a batch: edits that must land together, such as a figure's put and extras.
     *
     * @param key        what the unit saves: {@code identity}, {@code extras}, {@code holographic},
     *                   {@code figure/<id>}, {@code remove/<id>}, {@code order}, {@code archive},
     *                   {@code enable}, {@code publish} or {@code create}
     * @param values     each change key the unit carries, as {@link CreatorDraft#changes()} names
     *                   it, with the value it sends (null values allowed)
     * @param putFigures the new figures the unit puts, whose ids the put fixes
     * @param figures    every figure the unit changes
     * @param edits      the unit's edits, which tell whether it changed since the server refused it
     */
    public record UnitInfo(String key, Map<String, Object> values, List<String> putFigures, List<String> figures,
                           List<WorldCollectionEdit> edits) {
        public UnitInfo {
            values = Collections.unmodifiableMap(new HashMap<>(values));
            putFigures = List.copyOf(putFigures);
            figures = List.copyOf(figures);
            edits = List.copyOf(edits);
        }
    }

    /** Why a unit is held back. */
    public enum Reason {
        /** The name is blank or holds control characters. */
        NAME,
        /** The author holds control characters. */
        AUTHOR,
        /** A box colour or image was chosen and not given. */
        BOX,
        /** The author link is not one the server takes. */
        LINK,
        /** A skin figure has no skin yet. */
        SKIN,
        /** A figure misses a file, or a file or its name fails a check. */
        FIGURE,
        /** A figure names a file the server no longer has and this computer has no copy of. */
        LOST_FILE,
        /** The server refused the unit sent alone, and it has not changed since. */
        REFUSED,
        /** The whole collection would be over a cap or budget of the server's. */
        TOO_BIG
    }

    /**
     * A unit held back: what it is, why, and what to tell the operator.
     *
     * @param figureId the figure it is about, or null for the collection's own units
     * @param detail   the reason in a sentence, such as «Zombi»: Skin
     */
    public record Held(String unitKey, Reason reason, @Nullable String figureId, Component detail) {
    }

    /** What the saver tells {@link #planAvailable} of the operator's work in progress. */
    public interface Hold {
        /** Whether units with a file to upload may go now, because the operator moved on or asked. */
        boolean filesReleased();

        /**
         * Whether a new figure's name is being typed, or was moments ago: its first put, which
         * fixes its id, then waits for the name.
         */
        boolean typing(String figureId);

        /** The server's refusal of exactly these edits of a unit, sent alone, or null. */
        @Nullable
        Component refused(String unitKey, List<WorldCollectionEdit> edits);

        /** How many units one batch holds at most: 1 while a refusal is narrowed down. */
        int unitsPerBatch();
    }

    /**
     * What can be sent now, and what is held back.
     *
     * @param filesWaiting whether some unit waits only for its files to be released
     * @param typingWaits  whether some new figure waits only for its name to be typed
     */
    public record Available(Plan plan, List<Held> held, boolean filesWaiting, boolean typingWaits) {
        public Available {
            held = List.copyOf(held);
        }

        public boolean isEmpty() {
            return plan.isEmpty();
        }
    }

    /**
     * What saving a draft takes.
     *
     * @param collectionId the collection the edits change
     * @param creates      whether the first submission creates the collection
     * @param skipped      the ids of figures with changes that are not saved because they
     *                     miss a file
     */
    public record Plan(String collectionId, List<Submission> submissions, boolean creates, List<String> skipped) {
        public Plan {
            submissions = List.copyOf(submissions);
            skipped = List.copyOf(skipped);
        }

        public boolean isEmpty() {
            return submissions.isEmpty();
        }

        /** How many files the save uploads. */
        public int fileCount() {
            int count = 0;
            for (Submission submission : submissions) {
                count += submission.files().size();
            }
            return count;
        }
    }

    /** How a save ended. */
    public enum Ending {
        /** Every batch was saved, or there was nothing to save. */
        SAVED,
        /** The server refused a batch; the ones before it were saved. */
        REFUSED,
        /** A batch could not be handed over: not connected, or too many saves wait. */
        NOT_SENT,
        /** The client left or joined a server. */
        DISCONNECTED,
        /** {@link Run#cancel()} dropped the batches that had not started. */
        CANCELLED
    }

    /**
     * How a save ended.
     *
     * @param status       the server's last answer
     * @param saved        how many batches the server saved
     * @param total        how many batches the save had
     * @param collectionId the collection's id, which a retried create may have changed
     */
    public record Outcome(Ending ending, WorldContentStatus status, int saved, int total, String collectionId) {
        public boolean ok() {
            return ending == Ending.SAVED;
        }

        /** What to tell the operator when it did not work; empty when it did. */
        public Component message() {
            return switch (ending) {
                case SAVED -> Component.empty();
                case REFUSED -> CreatorSave.message(status);
                case NOT_SENT -> Component.translatable(KEY + "not_sent");
                case DISCONNECTED -> Component.translatable(KEY + "disconnected");
                case CANCELLED -> Component.translatable(KEY + "save.cancelled", saved, total);
            };
        }
    }

    /**
     * How far a save is.
     *
     * @param batch  the batch being sent, from 0
     * @param upload the upload of that batch, or null while nothing of it is being sent
     */
    public record Progress(int batch, int batches, @Nullable ClientWorldUploads.Progress upload) {
    }

    /** A unit of edits that must land in the same batch, with the files they name and what it changes. */
    private record Unit(String key, List<WorldCollectionEdit> edits, List<CreatorDraft.FileRef> files,
                        Map<String, Object> values, List<String> putFigures, List<String> figures) {
        Unit(String key, List<WorldCollectionEdit> edits) {
            this(key, edits, List.of(), Map.of(), List.of(), List.of());
        }

        UnitInfo info() {
            return new UnitInfo(key, values, putFigures, figures, edits);
        }
    }

    // ---- Planning

    /** Plans saving everything that changed, and publishing the draft collection when asked. */
    public static Plan plan(CreatorDraft draft, boolean publish) {
        return plan(draft, publish, null, Integer.MAX_VALUE);
    }

    /**
     * Plans saving some figures, such as the ones a bulk add just put in the draft: their own
     * edits only, with at most {@code figuresPerBatch} figures in a batch. A collection that does
     * not exist yet is saved whole, because the figures need it.
     */
    public static Plan planFigures(CreatorDraft draft, Collection<String> figureIds, int figuresPerBatch) {
        return plan(draft, false, draft.isNew() ? null : Set.copyOf(figureIds), Math.max(1, figuresPerBatch));
    }

    private static Plan plan(CreatorDraft draft, boolean publish, @Nullable Set<String> onlyFigures,
                             int unitsPerBatch) {
        String id = draft.collectionId();
        WorldCollectionRecord base = draft.base();
        List<Unit> units = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean whole = onlyFigures == null;
        if (draft.isNew()) {
            units.add(createUnit(draft));
        }
        if (whole && draft.identityChanged()) {
            units.add(identityUnit(draft));
        }
        if (whole && draft.extrasChanged()) {
            units.add(extrasUnit(draft, draft.effectiveLogoLayout()));
        }
        if (whole && draft.holographic() != base.holographic()) {
            units.add(holographicUnit(draft));
        }
        List<CreatorDraft.FigureDraft> figures = draft.figures();
        Set<String> inDraft = new HashSet<>();
        for (CreatorDraft.FigureDraft figure : figures) {
            inDraft.add(figure.id());
        }
        if (whole) {
            // Deleting first leaves room for the new figures under the figure cap
            for (WorldCollectionRecord.Figure saved : base.figures()) {
                if (inDraft.contains(saved.id())) {
                    continue;
                }
                Unit removal = removalUnit(id, base, saved);
                if (removal != null) {
                    units.add(removal);
                }
            }
        }
        // The ids the server holds after the save, in the draft's order
        List<String> kept = new ArrayList<>();
        for (CreatorDraft.FigureDraft figure : figures) {
            boolean saved = base.figure(figure.id()).isPresent();
            boolean asked = whole || onlyFigures.contains(figure.id());
            if (!figure.isComplete()) {
                if (asked && figure.isDirty()) {
                    skipped.add(figure.id());
                }
                // A saved figure that misses a file now stays as the server has it
                if (saved) {
                    kept.add(figure.id());
                }
                continue;
            }
            if (saved || asked) {
                kept.add(figure.id());
            }
            if (asked) {
                Unit unit = figureUnit(draft, id, figure, base.figure(figure.id()).orElse(null));
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        if (whole) {
            addTail(units, draft, kept, inDraft, publish);
        }
        return new Plan(id, pack(units, unitsPerBatch), draft.isNew(), skipped);
    }

    /**
     * Adds what follows the figures: the order the server would not keep by itself, archiving
     * or restoring a published collection, and publishing a draft collection when asked.
     *
     * @param kept the figures the server holds after the units before, in the draft's order
     */
    private static void addTail(List<Unit> units, CreatorDraft draft, List<String> kept, Set<String> inDraft,
                                boolean publish) {
        String id = draft.collectionId();
        WorldCollectionRecord base = draft.base();
        if (!base.draft()) {
            // A published collection keeps, retired, the figures the draft has no more
            for (WorldCollectionRecord.Figure saved : base.figures()) {
                if (!inDraft.contains(saved.id())) {
                    kept.add(saved.id());
                }
            }
        }
        if (!kept.equals(CreatorDraft.serverOrder(base, kept))) {
            units.add(new Unit("order", List.of(new WorldCollectionEdit.ReorderFigures(id, kept)), List.of(),
                    single(CreatorDraft.ORDER, List.copyOf(kept)), List.of(), List.of()));
        }
        if (draft.archived() != base.archived() && !base.draft()) {
            units.add(new Unit("archive", List.of(new WorldCollectionEdit.SetArchived(id, draft.archived())), List.of(),
                    single(CreatorDraft.ARCHIVED, draft.archived()), List.of(), List.of()));
        }
        if (publish && base.draft()) {
            units.add(new Unit("publish", List.of(new WorldCollectionEdit.Publish(id))));
        }
    }

    private static Map<String, Object> single(String key, @Nullable Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(key, value);
        return values;
    }

    private static Unit createUnit(CreatorDraft draft) {
        return new Unit("create", List.of(new WorldCollectionEdit.CreateDraft(draft.slug(), draft.name())));
    }

    /** SetCollection, which carries the name, author, colour, box and logo together. */
    private static Unit identityUnit(CreatorDraft draft) {
        CreatorDraft.FileRef box = draft.boxMode() == CreatorDraft.BoxMode.TEXTURE ? draft.boxTexture() : null;
        int boxMode;
        String boxValue;
        if (draft.boxTextureSha() != null) {
            boxMode = WorldCollectionEdit.BOX_TEXTURE;
            boxValue = draft.boxTextureSha();
        } else if (draft.boxColorName() != null) {
            boxMode = WorldCollectionEdit.BOX_COLOR;
            boxValue = draft.boxColorName();
        } else {
            boxMode = WorldCollectionEdit.BOX_NONE;
            boxValue = "";
        }
        CreatorDraft.FileRef logo = draft.logo();
        Map<String, Object> values = new HashMap<>();
        for (String key : List.of(CreatorDraft.NAME, CreatorDraft.AUTHOR, CreatorDraft.COLOUR, CreatorDraft.BOX,
                CreatorDraft.LOGO)) {
            values.put(key, draft.value(key));
        }
        return new Unit("identity", List.of(new WorldCollectionEdit.SetCollection(draft.collectionId(), draft.name(),
                draft.author(), draft.red(), draft.green(), draft.blue(), boxMode, boxValue,
                logo != null ? logo.sha() : "")), files(box, logo), values, List.of(), List.of());
    }

    /**
     * SetCollectionExtras with a logo placement, which carries the author link, the placement, the
     * plush flag and the rarity weights together. The placement it sends is remembered as sent
     * only when it is the draft's own.
     */
    private static Unit extrasUnit(CreatorDraft draft, @Nullable WorldCollectionRecord.LogoLayout layout) {
        WorldCollectionRecord.RarityWeights weights = draft.rarityWeights();
        Map<String, Object> values = new HashMap<>();
        for (String key : List.of(CreatorDraft.AUTHOR_URL, CreatorDraft.PLUSH, CreatorDraft.RARITY_WEIGHTS)) {
            values.put(key, draft.value(key));
        }
        if (Objects.equals(layout, draft.effectiveLogoLayout())) {
            values.put(CreatorDraft.LOGO_LAYOUT, layout);
        }
        return new Unit("extras", List.of(new WorldCollectionEdit.SetCollectionExtras(draft.collectionId(),
                draft.authorUrl(), layout, draft.plush(),
                WorldCollectionRecord.RarityWeights.DEFAULT.equals(weights) ? null : weights)), List.of(), values,
                List.of(), List.of());
    }

    /**
     * SetHolographic, a unit of its own: whether the collection's draws may come out holographic
     * depends on nothing else, so it goes even while the other extras are held.
     */
    private static Unit holographicUnit(CreatorDraft draft) {
        return new Unit("holographic", List.of(new WorldCollectionEdit.SetHolographic(draft.collectionId(),
                draft.holographic())), List.of(), single(CreatorDraft.HOLOGRAPHIC, draft.holographic()), List.of(),
                List.of());
    }

    /**
     * What takes a saved figure the draft has no more off the server: a draft collection deletes
     * it, a published one retires it, and one already retired needs nothing.
     */
    @Nullable
    private static Unit removalUnit(String id, WorldCollectionRecord base, WorldCollectionRecord.Figure saved) {
        if (base.draft()) {
            return new Unit("remove/" + saved.id(), List.of(new WorldCollectionEdit.RemoveFigure(id, saved.id())),
                    List.of(), single(CreatorDraft.FIGURE + saved.id() + "/removed", Boolean.TRUE), List.of(),
                    List.of(saved.id()));
        }
        if (saved.enabled()) {
            return new Unit("remove/" + saved.id(),
                    List.of(new WorldCollectionEdit.SetFigureEnabled(id, saved.id(), false)), List.of(), Map.of(),
                    List.of(), List.of(saved.id()));
        }
        return null;
    }

    /** The edits that save one complete figure, or null when it did not change. */
    @Nullable
    private static Unit figureUnit(CreatorDraft draft, String id, CreatorDraft.FigureDraft figure,
                                   @Nullable WorldCollectionRecord.Figure saved) {
        WorldCollectionRecord.Figure wanted = figure.toFigure();
        boolean basics = saved == null || !wanted.name().equals(saved.name()) || wanted.kind() != saved.kind()
                || !wanted.texture().equals(saved.texture()) || !Objects.equals(wanted.model(), saved.model())
                || !Objects.equals(wanted.animation(), saved.animation()) || wanted.scale() != saved.scale();
        // What the server holds after the put: a new figure is common and drawn, and a
        // changed kind drops the variants and the face, as WorldCollections does
        WorldCollectionRecord.Figure after;
        if (saved == null) {
            after = new WorldCollectionRecord.Figure(wanted.id(), wanted.name(), wanted.kind(), wanted.texture(),
                    wanted.model(), wanted.animation(), wanted.scale(), true);
        } else if (basics) {
            after = saved.withBasics(wanted.name(), wanted.kind(), wanted.texture(), wanted.model(),
                    wanted.animation(), wanted.scale());
        } else {
            after = saved;
        }
        List<WorldCollectionEdit> edits = new ArrayList<>();
        if (basics) {
            edits.add(new WorldCollectionEdit.PutFigure(id, wanted.id(), wanted.name(),
                    wanted.kind() == Kind.SKIN ? WorldCollectionEdit.KIND_SKIN : WorldCollectionEdit.KIND_MODEL,
                    wanted.texture(), orEmpty(wanted.model()), orEmpty(wanted.animation()),
                    WorldCollectionRecord.scaleIndex(wanted.scale())));
        }
        if (wanted.rarity() != after.rarity() || !wanted.variants().equals(after.variants())
                || !Objects.equals(wanted.boxFace(), after.boxFace())) {
            edits.add(new WorldCollectionEdit.SetFigureExtras(id, wanted.id(), wanted.rarity(), wanted.variants(),
                    faceMode(wanted), wanted.boxFace() != null ? wanted.boxFace().uv() : null));
        }
        if (wanted.enabled() != after.enabled()) {
            edits.add(new WorldCollectionEdit.SetFigureEnabled(id, wanted.id(), wanted.enabled()));
        }
        if (edits.isEmpty()) {
            return null;
        }
        List<CreatorDraft.FileRef> files = new ArrayList<>();
        files.add(figure.texture());
        if (figure.kind() == Kind.MODEL) {
            files.add(figure.model());
            files.add(figure.animation());
        }
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            files.add(variant.texture());
        }
        // What the server holds of the figure once these edits land, as the draft names it
        String prefix = CreatorDraft.FIGURE + wanted.id() + "/";
        Map<String, Object> values = new HashMap<>();
        for (WorldCollectionEdit edit : edits) {
            List<String> fields = edit instanceof WorldCollectionEdit.PutFigure
                    ? List.of("name", "kind", "texture", "model", "animation", "scale")
                    : edit instanceof WorldCollectionEdit.SetFigureExtras ? List.of("rarity", "variants", "box_face")
                    : List.of("enabled");
            for (String field : fields) {
                values.put(prefix + field, draft.value(prefix + field));
            }
        }
        List<String> put = saved == null ? List.of(wanted.id()) : List.of();
        if (saved == null) {
            values.put(prefix + CreatorDraft.NEW, Boolean.TRUE);
        }
        return new Unit("figure/" + wanted.id(), edits, files, values, put, List.of(wanted.id()));
    }

    // ---- Planning what can go now

    /**
     * Plans what of a draft can be sent now, as {@link #plan(CreatorDraft, boolean)} would, but
     * holding back each unit that cannot or should not go yet instead of the whole draft:
     *
     * <ul>
     *   <li>SetCollection while the name is blank or not plain text, the author is not plain text,
     *       or a box colour or image was chosen and not given; a colour or logo changed meanwhile
     *       waits with it, since the edit carries them together;</li>
     *   <li>SetCollectionExtras while the author link is not one the server takes. It never waits
     *       for SetCollection: while the server has no logo yet it carries the default placement,
     *       and the draft's own placement goes once the logo has landed. SetHolographic waits for
     *       neither;</li>
     *   <li>a figure's edits while it misses a file, a file or a name fails its check, its box face
     *       lies outside its texture, or it names a file the server no longer has and this
     *       computer has no copy of; and a new figure's first put while its name is being typed
     *       ({@link Hold#typing}), since that put fixes its id;</li>
     *   <li>any unit with a file to upload until {@link Hold#filesReleased}, and a unit the server
     *       refused alone until it changes ({@link Hold#refused});</li>
     *   <li>everything while the collection would be over a cap or budget of the server's.</li>
     * </ul>
     *
     * <p>The reorder names only the figures the server will have once the units before it land, so
     * a held new figure is never in it. A published collection's changes that need more than one
     * batch put each new figure retired, in the batch of its put, and bring them all into the draw
     * in the last batches, so players never see a new figure before the rest of the change.
     *
     * @param publish whether a draft collection is published in the last batch
     */
    public static Available planAvailable(CreatorDraft draft, boolean publish, Hold hold) {
        String id = draft.collectionId();
        WorldCollectionRecord base = draft.base();
        CreatorDraft.Forecast forecast = draft.forecast();
        if (!forecast.fits()) {
            Component detail = forecast.budgetProblem() != null ? Component.literal(forecast.budgetProblem())
                    : Component.translatable(KEY + "editor.too_big", (forecast.recordBytes() + 1023) / 1024,
                    WorldContentLimits.RECORD_BYTES / 1024);
            return new Available(new Plan(id, List.of(), draft.isNew(), List.of()),
                    List.of(new Held("draft", Reason.TOO_BIG, null, detail)), false, false);
        }
        List<Held> held = new ArrayList<>();
        // Whether a unit waits for its files, and whether a new figure waits for its name
        boolean[] waits = new boolean[2];
        List<Unit> units = new ArrayList<>();
        if (draft.isNew()) {
            units.add(createUnit(draft));
        }
        boolean identityGoes = false;
        if (draft.identityChanged()) {
            Held problem = identityHeld(draft);
            Unit unit = identityUnit(draft);
            if (problem != null) {
                held.add(problem);
            } else if (allowed(unit, hold, held, waits, null)) {
                units.add(unit);
                identityGoes = true;
            }
        }
        // The placement the server can take: none while it has no logo, the draft's once it has
        WorldCollectionRecord.LogoLayout layout;
        if (identityGoes || base.logo() == null && draft.logo() == null) {
            layout = draft.effectiveLogoLayout();
        } else if (base.logo() == null) {
            layout = null;
        } else {
            layout = draft.logo() != null ? draft.effectiveLogoLayout() : base.logoLayout();
        }
        if (!Objects.equals(draft.authorUrlOrNull(), base.authorUrl()) || !Objects.equals(layout, base.logoLayout())
                || draft.plush() != base.plush() || !Objects.equals(draft.value(CreatorDraft.RARITY_WEIGHTS),
                base.rarityWeights())) {
            if (!draft.authorUrl().isEmpty() && !WorldCollectionRecord.isValidAuthorUrl(draft.authorUrl())) {
                held.add(new Held("extras", Reason.LINK, null, Component.translatable(KEY + "identity.url_rule")));
            } else {
                Unit unit = extrasUnit(draft, layout);
                if (allowed(unit, hold, held, waits, null)) {
                    units.add(unit);
                }
            }
        }
        if (draft.holographic() != base.holographic()) {
            Unit unit = holographicUnit(draft);
            if (allowed(unit, hold, held, waits, null)) {
                units.add(unit);
            }
        }
        List<CreatorDraft.FigureDraft> figures = draft.figures();
        Set<String> inDraft = new HashSet<>();
        for (CreatorDraft.FigureDraft figure : figures) {
            inDraft.add(figure.id());
        }
        // Deleting first leaves room for the new figures under the figure cap
        for (WorldCollectionRecord.Figure saved : base.figures()) {
            if (!inDraft.contains(saved.id())) {
                Unit removal = removalUnit(id, base, saved);
                if (removal != null && allowed(removal, hold, held, waits, saved.id())) {
                    units.add(removal);
                }
            }
        }
        // The ids the server holds after the units before the reorder, in the draft's order
        List<String> kept = new ArrayList<>();
        for (CreatorDraft.FigureDraft figure : figures) {
            WorldCollectionRecord.Figure saved = base.figure(figure.id()).orElse(null);
            boolean goes = false;
            if (figure.isDirty()) {
                Held problem = figureHeld(figure);
                if (problem != null) {
                    held.add(problem);
                } else if (figure.isNew() && hold.typing(figure.id())) {
                    waits[1] = true;
                } else {
                    Unit unit = figureUnit(draft, id, figure, saved);
                    if (unit != null && allowed(unit, hold, held, waits, figure.id())) {
                        units.add(unit);
                        goes = true;
                    }
                }
            }
            if (saved != null || goes) {
                kept.add(figure.id());
            }
        }
        addTail(units, draft, kept, inDraft, publish);
        // The reorder and the archive may have been refused alone too
        units.removeIf(unit -> (unit.key().equals("order") || unit.key().equals("archive"))
                && !allowed(unit, hold, held, waits, null));
        List<Submission> submissions = pack(units, hold.unitsPerBatch());
        if (!base.draft() && submissions.size() > 1 && units.stream().anyMatch(unit -> !unit.putFigures().isEmpty())) {
            submissions = pack(staged(draft, units), hold.unitsPerBatch());
        }
        return new Available(new Plan(id, submissions, draft.isNew(), List.of()), held, waits[0], waits[1]);
    }

    /**
     * Whether a unit may go now: not refused alone as it is, and with no file to upload unless the
     * files are released. A refused unit is added to the held ones.
     */
    private static boolean allowed(Unit unit, Hold hold, List<Held> held, boolean[] waits, @Nullable String figureId) {
        Component refused = hold.refused(unit.key(), unit.edits());
        if (refused != null) {
            held.add(new Held(unit.key(), Reason.REFUSED, figureId, refused));
            return false;
        }
        if (!hold.filesReleased()) {
            for (CreatorDraft.FileRef ref : unit.files()) {
                if (ref != null && ref.needsUpload()) {
                    waits[0] = true;
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The units of a published collection's change with each new figure put retired in the batch
     * of its put, then brought into the draw, all together, in batches of their own at the end.
     */
    private static List<Unit> staged(CreatorDraft draft, List<Unit> units) {
        String id = draft.collectionId();
        List<Unit> staged = new ArrayList<>();
        List<WorldCollectionEdit> enable = new ArrayList<>();
        Map<String, Object> enableValues = new HashMap<>();
        List<String> enableFigures = new ArrayList<>();
        for (Unit unit : units) {
            if (unit.putFigures().isEmpty()) {
                staged.add(unit);
                continue;
            }
            String figureId = unit.putFigures().get(0);
            String key = CreatorDraft.FIGURE + figureId + "/enabled";
            List<WorldCollectionEdit> edits = new ArrayList<>(unit.edits());
            edits.removeIf(edit -> edit instanceof WorldCollectionEdit.SetFigureEnabled);
            edits.add(new WorldCollectionEdit.SetFigureEnabled(id, figureId, false));
            Map<String, Object> values = new HashMap<>(unit.values());
            values.put(key, Boolean.FALSE);
            staged.add(new Unit(unit.key(), edits, unit.files(), values, unit.putFigures(), unit.figures()));
            CreatorDraft.FigureDraft figure = draft.figure(figureId);
            if (figure != null && figure.enabled()) {
                enable.add(new WorldCollectionEdit.SetFigureEnabled(id, figureId, true));
                enableValues.put(key, Boolean.TRUE);
                enableFigures.add(figureId);
            }
        }
        for (int from = 0; from < enable.size(); from += WorldCollectionEdit.MAX_BATCH) {
            int to = Math.min(enable.size(), from + WorldCollectionEdit.MAX_BATCH);
            Map<String, Object> values = new HashMap<>();
            for (String figureId : enableFigures.subList(from, to)) {
                String key = CreatorDraft.FIGURE + figureId + "/enabled";
                values.put(key, enableValues.get(key));
            }
            staged.add(new Unit("enable", enable.subList(from, to), List.of(), values, List.of(),
                    enableFigures.subList(from, to)));
        }
        return staged;
    }

    /** Why SetCollection is held: the name, the author or the box; or null when it can go. */
    @Nullable
    private static Held identityHeld(CreatorDraft draft) {
        if (draft.name().isBlank()) {
            return new Held("identity", Reason.NAME, null, Component.translatable(KEY + "wizard.name_empty"));
        }
        if (!WorldCollectionRecord.isPlainText(draft.name())) {
            return new Held("identity", Reason.NAME, null, Component.translatable(KEY + "name_rule"));
        }
        if (!WorldCollectionRecord.isPlainText(draft.author())) {
            return new Held("identity", Reason.AUTHOR, null, Component.translatable(KEY + "wizard.author_rule"));
        }
        if (draft.boxMode() == CreatorDraft.BoxMode.COLOR && draft.boxColor() == null) {
            return new Held("identity", Reason.BOX, null, Component.translatable(KEY + "identity.box_colour_missing"));
        }
        if (draft.boxMode() == CreatorDraft.BoxMode.TEXTURE && draft.boxTexture() == null) {
            return new Held("identity", Reason.BOX, null, Component.translatable(KEY + "identity.box_image_missing"));
        }
        return null;
    }

    /**
     * Why a figure's edits are held, or null when they can go: a file the server lost that this
     * computer has no copy of, or what is missing or wrong, each in a phrase such as
     * «Zombi»: no skin yet, or a failing file check in its own words.
     */
    @Nullable
    public static Held figureHeld(CreatorDraft.FigureDraft figure) {
        String key = "figure/" + figure.id();
        String name = figure.name().isBlank() ? figure.id() : figure.name();
        boolean skin = figure.kind() == Kind.SKIN;
        List<CreatorDraft.FileRef> refs = new ArrayList<>();
        refs.add(figure.texture());
        if (!skin) {
            refs.add(figure.model());
            refs.add(figure.animation());
        }
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            refs.add(variant.texture());
        }
        for (CreatorDraft.FileRef ref : refs) {
            if (ref != null && ref.local() == null && isLost(ref.sha())) {
                return new Held(key, Reason.LOST_FILE, figure.id(), Component.translatable(KEY + "autosave.held_figure",
                        name, Component.translatable(KEY + "autosave.held.lost_file")));
            }
        }
        List<Component> wrong = new ArrayList<>();
        boolean noSkin = skin && figure.texture() == null;
        if (figure.name().isBlank()) {
            wrong.add(Component.translatable(KEY + "autosave.held.no_name"));
        } else if (!WorldCollectionRecord.isPlainText(figure.name())) {
            wrong.add(Component.translatable(KEY + "name_rule"));
        }
        BlobInfo model = figure.model() != null ? figure.model().info() : null;
        Function<BlobInfo, List<FileChecks.Check>> textureChecks = skin ? FileChecks::skin
                : info -> FileChecks.modelTexture(info, model);
        addFileProblem(wrong, figure.texture(), textureChecks, skin ? "skin" : "texture");
        if (!skin) {
            addFileProblem(wrong, figure.model(), FileChecks::geo, "model");
            if (figure.animation() != null) {
                addFileProblem(wrong, figure.animation(), FileChecks::animation, "poses");
            }
        }
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            if (variant.name().isBlank() || !WorldCollectionRecord.isPlainText(variant.name())) {
                wrong.add(Component.translatable(KEY + "extras.variant_name_rule"));
                break;
            }
            int before = wrong.size();
            addFileProblem(wrong, variant.texture(), textureChecks, "variants");
            if (wrong.size() > before) {
                break;
            }
        }
        WorldCollectionRecord.BoxFace face = figure.boxFace();
        BlobInfo texture = figure.texture() != null ? figure.texture().info() : null;
        if (!skin && face != null && face.show() && face.uv() != null && texture != null && !fits(face.uv(), texture)) {
            wrong.add(Component.translatable(KEY + "extras.face_outside", texture.width(), texture.height()));
        }
        if (wrong.isEmpty()) {
            return null;
        }
        StringBuilder parts = new StringBuilder();
        for (Component part : wrong) {
            if (parts.length() > 0) {
                parts.append(" · ");
            }
            parts.append(part.getString());
        }
        return new Held(key, noSkin ? Reason.SKIN : Reason.FIGURE, figure.id(),
                Component.translatable(KEY + "autosave.held_figure", name, parts.toString()));
    }

    /**
     * Whether the server no longer has a stored file: it says it lost it, or a record it syncs
     * still names it while its file table does not list it. A file no record names any more, as
     * one of a figure deleted moments ago, is left out of that table though the server keeps it
     * for a day; it goes by its sha, and the server's refusal says when it is gone.
     */
    private static boolean isLost(String sha) {
        if (ClientWorldCollections.isMissingOnServer(sha)) {
            return true;
        }
        if (ClientWorldCollections.blob(sha) != null) {
            return false;
        }
        for (WorldCollectionRecord record : ClientWorldCollections.records()) {
            if (record.referencedShas().contains(sha)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds what is wrong with one of a figure's files: "no skin yet" while it has none, or the
     * part's name and its first failing check, such as "Skin: 32×32; a skin is 64×64". A stored
     * file the server did not describe is its own to check.
     */
    private static void addFileProblem(List<Component> wrong, @Nullable CreatorDraft.FileRef file,
                                       Function<BlobInfo, List<FileChecks.Check>> checks, String part) {
        if (file == null) {
            wrong.add(Component.translatable(KEY + (part.equals("variants") ? "inspector.need.variants"
                    : "autosave.held.no_" + part)));
            return;
        }
        BlobInfo info = file.info();
        if (info == null) {
            return;
        }
        for (FileChecks.Check check : checks.apply(info)) {
            if (!check.ok()) {
                wrong.add(Component.translatable(KEY + "autosave.held.file_check",
                        Component.translatable(KEY + "inspector.need." + part), check.text()));
                return;
            }
        }
    }

    // Whether a face region lies within the texture, as the server checks it
    private static boolean fits(WorldCollectionRecord.Uv uv, BlobInfo texture) {
        return uv.u() >= 0 && uv.v() >= 0 && uv.w() >= 1 && uv.h() >= 1
                && (long) uv.u() + uv.w() <= texture.width() && (long) uv.v() + uv.h() <= texture.height();
    }

    /** The face mode SetFigureExtras sends for a figure's box face in its record form. */
    private static int faceMode(WorldCollectionRecord.Figure figure) {
        WorldCollectionRecord.BoxFace face = figure.boxFace();
        if (face == null) {
            return WorldCollectionEdit.FACE_DEFAULT;
        }
        if (!face.show()) {
            return WorldCollectionEdit.FACE_HIDE;
        }
        return face.uv() != null ? WorldCollectionEdit.FACE_SHOW_UV : WorldCollectionEdit.FACE_SHOW;
    }

    private static String orEmpty(@Nullable String sha) {
        return sha != null ? sha : "";
    }

    private static List<CreatorDraft.FileRef> files(@Nullable CreatorDraft.FileRef... refs) {
        List<CreatorDraft.FileRef> files = new ArrayList<>();
        for (CreatorDraft.FileRef ref : refs) {
            if (ref != null) {
                files.add(ref);
            }
        }
        return files;
    }

    /**
     * Packs the units in order into batches under the edit and byte caps, and at most
     * {@code unitsPerBatch} units to a batch. Each file goes with the first batch that names
     * it, and only when it has to be uploaded.
     */
    private static List<Submission> pack(List<Unit> units, int unitsPerBatch) {
        List<Submission> submissions = new ArrayList<>();
        Set<String> sent = new HashSet<>();
        List<WorldCollectionEdit> edits = new ArrayList<>();
        Map<String, FilePicker.LocalFile> files = new LinkedHashMap<>();
        List<UnitInfo> infos = new ArrayList<>();
        int unitsInBatch = 0;
        for (Unit unit : units) {
            if (!edits.isEmpty() && (unitsInBatch >= unitsPerBatch || !fits(edits, unit.edits()))) {
                submissions.add(submission(edits, files, infos));
                edits = new ArrayList<>();
                files = new LinkedHashMap<>();
                infos = new ArrayList<>();
                unitsInBatch = 0;
            }
            edits.addAll(unit.edits());
            infos.add(unit.info());
            unitsInBatch++;
            for (CreatorDraft.FileRef ref : unit.files()) {
                if (ref != null && ref.needsUpload() && sent.add(ref.sha())) {
                    files.put(ref.sha(), ref.local());
                }
            }
        }
        if (!edits.isEmpty()) {
            submissions.add(submission(edits, files, infos));
        }
        return submissions;
    }

    private static boolean fits(List<WorldCollectionEdit> edits, List<WorldCollectionEdit> more) {
        if (edits.size() + more.size() > WorldCollectionEdit.MAX_BATCH) {
            return false;
        }
        List<WorldCollectionEdit> together = new ArrayList<>(edits);
        together.addAll(more);
        return WorldCollectionEditPacket.encodedSize(new WorldCollectionEdit.Batch(together))
                <= WorldCollectionEditPacket.MAX_CLIENT_BYTES;
    }

    private static Submission submission(List<WorldCollectionEdit> edits, Map<String, FilePicker.LocalFile> files,
                                         List<UnitInfo> infos) {
        WorldCollectionEdit edit = edits.size() == 1 ? edits.get(0) : new WorldCollectionEdit.Batch(edits);
        return new Submission(List.copyOf(files.values()), edit, edits.size(), infos);
    }

    // ---- Sending

    /** Saves some figures of the draft in small batches; see {@link #planFigures}. */
    public static Run saveFigures(CreatorDraft draft, Collection<String> figureIds, int figuresPerBatch,
                                  Consumer<Outcome> receiver) {
        return saveFigures(draft, figureIds, figuresPerBatch, false, receiver);
    }

    /**
     * Saves some figures of the draft in small batches; see {@link #planFigures}.
     *
     * @param background whether the batches are sent on the operator's behalf, as a bulk add's
     *                   are, and so leave the budget's reserve ({@link ClientWorldUploads})
     */
    public static Run saveFigures(CreatorDraft draft, Collection<String> figureIds, int figuresPerBatch,
                                  boolean background, Consumer<Outcome> receiver) {
        List<String> ids = List.copyOf(figureIds);
        Run run = new Run(draft, () -> planFigures(draft, ids, figuresPerBatch), receiver);
        run.background = background;
        run.start();
        return run;
    }

    /**
     * Sends a plan made already, such as one of {@link #planAvailable}.
     *
     * @param background whether it is sent on the operator's behalf, as an autosave is
     */
    public static Run send(CreatorDraft draft, Plan plan, boolean background, Consumer<Outcome> receiver) {
        Run run = new Run(draft, () -> plan, receiver);
        run.background = background;
        run.start();
        return run;
    }

    /**
     * Sends an edit that creates a collection with a slug, such as a duplicate, after its
     * files. When the server answers that the slug is taken, it is sent again with the next
     * slug from {@link Slugs#next}, at most {@value #MAX_SLUG_RETRIES} times.
     *
     * @param editFor the edit for a slug
     */
    public static Run create(String slug, List<FilePicker.LocalFile> files,
                             Function<String, WorldCollectionEdit> editFor, Consumer<Outcome> receiver) {
        String[] current = {slug};
        Run run = new Run(null, () -> new Plan(WorldContentIds.collectionId(current[0]),
                List.of(new Submission(files, editFor.apply(current[0]), 1)), true, List.of()), receiver);
        run.retrySlug = () -> current[0] = Slugs.unique(Slugs.next(current[0]), Slugs::isCollectionTaken);
        run.start();
        return run;
    }

    /**
     * One save in progress: its batches go out one after another, each after the server saved
     * the one before. A save with nothing to send, or one that cannot be handed over, tells
     * its receiver before the method that started it returns. It counts in {@link CreatorRuns}
     * under its collection from its start until the sync after it.
     */
    public static final class Run {
        @Nullable
        private final CreatorDraft draft;
        private final Supplier<Plan> planner;
        private final Consumer<Outcome> receiver;
        @Nullable
        private Runnable retrySlug;
        private Plan plan;
        private int next;
        private int retries;
        private boolean cancelled;
        private boolean finished;
        private boolean background;
        @Nullable
        private CreatorRuns.Entry entry;

        private Run(@Nullable CreatorDraft draft, Supplier<Plan> planner,
                    Consumer<Outcome> receiver) {
            this.draft = draft;
            this.planner = planner;
            this.receiver = receiver;
        }

        private void start() {
            plan = planner.get();
            next = 0;
            if (entry == null) {
                entry = CreatorRuns.begin(plan.collectionId(), this);
            } else {
                // A create retried under the next slug
                CreatorRuns.move(entry, plan.collectionId());
            }
            if (plan.isEmpty()) {
                finish(Ending.SAVED, WorldContentStatus.of(Result.OK));
                return;
            }
            send();
        }

        private void send() {
            Submission submission = plan.submissions().get(next);
            boolean accepted = ClientWorldUploads.submit(submission.files(), submission.edit(), this, background,
                    this::answered);
            if (!accepted) {
                finish(Ending.NOT_SENT, WorldContentStatus.of(Result.BUSY));
            }
        }

        private void answered(ClientWorldUploads.Outcome outcome) {
            if (finished) {
                return;
            }
            if (outcome.disconnected()) {
                finish(Ending.DISCONNECTED, outcome.status());
            } else if (outcome.cancelled()) {
                finish(Ending.CANCELLED, outcome.status());
            } else if (!outcome.ok()) {
                if (next == 0 && plan.creates() && outcome.status().result() == Result.ID_TAKEN
                        && retries < MAX_SLUG_RETRIES && !cancelled) {
                    retries++;
                    if (retryWithNextSlug()) {
                        return;
                    }
                }
                finish(Ending.REFUSED, outcome.status());
            } else {
                if (next == 0 && plan.creates() && draft != null) {
                    draft.markCreated(plan.collectionId());
                }
                next++;
                if (next >= plan.submissions().size()) {
                    finish(Ending.SAVED, outcome.status());
                } else if (cancelled) {
                    finish(Ending.CANCELLED, outcome.status());
                } else {
                    send();
                }
            }
        }

        // The slug may belong to a purged collection, which the client never sees
        private boolean retryWithNextSlug() {
            if (draft != null) {
                if (!draft.isNew() || !draft.retrySlug(Slugs.unique(Slugs.next(draft.slug()), Slugs::isCollectionTaken))) {
                    return false;
                }
            } else if (retrySlug != null) {
                retrySlug.run();
            } else {
                return false;
            }
            BlockPopsMod.logDebug("World collection slug taken, trying another");
            start();
            return true;
        }

        private void finish(Ending ending, WorldContentStatus status) {
            if (finished) {
                return;
            }
            finished = true;
            int saved = ending == Ending.SAVED ? plan.submissions().size() : next;
            if (entry != null) {
                CreatorRuns.finish(entry, saved > 0);
            }
            Outcome outcome = new Outcome(ending, status, saved, plan.submissions().size(), plan.collectionId());
            try {
                receiver.accept(outcome);
            } catch (RuntimeException e) {
                BlockPopsMod.LOGGER.error("A World collection save receiver failed", e);
            }
        }

        /** The plan being sent; a retried create replans with the next slug. */
        public Plan plan() {
            return plan;
        }

        /** The collection the run changes; a retried create moves to the next slug's. */
        public String collectionId() {
            return plan.collectionId();
        }

        public boolean isFinished() {
            return finished;
        }

        /** How far it is: the batch being sent and the upload of its files. */
        public Progress progress() {
            ClientWorldUploads.Progress upload = ClientWorldUploads.progress();
            return new Progress(Math.min(next, plan.submissions().size()), plan.submissions().size(),
                    upload != null && upload.tag() == this ? upload : null);
        }

        /** Holds the batches that have not started; the one being sent finishes. */
        public void pause() {
            ClientWorldUploads.pause();
        }

        public void resume() {
            ClientWorldUploads.resume();
        }

        public boolean isPaused() {
            return ClientWorldUploads.isPaused();
        }

        /**
         * Sends no more batches. The one being sent still finishes, because the server may
         * already hold it; the save then ends as cancelled with the earlier batches kept.
         */
        public void cancel() {
            if (finished || cancelled) {
                return;
            }
            cancelled = true;
            // A batch that has not started is dropped, which ends the save at once
            ClientWorldUploads.cancelQueued(this);
        }
    }

    // ---- Messages

    /** The server's answer in the words the commands use. */
    public static Component message(WorldContentStatus status) {
        if (status.result() == Result.TIMED_OUT && !status.detail().isEmpty()) {
            return Component.translatable(KEY + "timed_out", status.detail());
        }
        return Component.translatable("blockpops.world_collections." + status.result().name().toLowerCase(Locale.ROOT),
                status.detail());
    }
}
