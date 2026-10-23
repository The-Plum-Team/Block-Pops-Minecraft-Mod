package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldCollectionEditPacket;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
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
 * weights, then each changed figure's PutFigure, SetFigureExtras and SetFigureEnabled
 * together, ReorderFigures when the order differs from what the server would keep, and
 * Publish last when asked. A collection that does not exist yet is created first, as a
 * draft. A figure that misses a file is not saved and stays unsaved in the draft.
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
     */
    public record Submission(List<FilePicker.LocalFile> files, WorldCollectionEdit edit, int edits) {
        public Submission {
            files = List.copyOf(files);
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

    /** A unit of edits that must land in the same batch, with the files they name. */
    private record Unit(List<WorldCollectionEdit> edits, List<CreatorDraft.FileRef> files) {
    }

    // ---- Planning

    /** Plans saving everything that changed, and publishing the draft collection when asked. */
    public static Plan plan(CreatorDraft draft, boolean publish) {
        return plan(draft, publish, null, Integer.MAX_VALUE);
    }

    /**
     * Plans saving one figure: its own edits only. A collection that does not exist yet is
     * saved whole, because the figure needs it.
     */
    public static Plan planFigure(CreatorDraft draft, String figureId) {
        return plan(draft, false, draft.isNew() ? null : Set.of(figureId), Integer.MAX_VALUE);
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
            units.add(new Unit(List.of(new WorldCollectionEdit.CreateDraft(draft.slug(), draft.name())), List.of()));
        }
        if (whole && draft.identityChanged()) {
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
            units.add(new Unit(List.of(new WorldCollectionEdit.SetCollection(id, draft.name(), draft.author(),
                    draft.red(), draft.green(), draft.blue(), boxMode, boxValue, logo != null ? logo.sha() : "")),
                    files(box, logo)));
        }
        if (whole && draft.extrasChanged()) {
            WorldCollectionRecord.RarityWeights weights = draft.rarityWeights();
            units.add(new Unit(List.of(new WorldCollectionEdit.SetCollectionExtras(id, draft.authorUrl(),
                    draft.effectiveLogoLayout(), draft.plush(),
                    WorldCollectionRecord.RarityWeights.DEFAULT.equals(weights) ? null : weights)), List.of()));
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
                WorldCollectionEdit edit = base.draft() ? new WorldCollectionEdit.RemoveFigure(id, saved.id())
                        : saved.enabled() ? new WorldCollectionEdit.SetFigureEnabled(id, saved.id(), false) : null;
                if (edit != null) {
                    units.add(new Unit(List.of(edit), List.of()));
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
                Unit unit = figureUnit(id, figure, base.figure(figure.id()).orElse(null));
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        if (whole) {
            if (!base.draft()) {
                // A published collection keeps, retired, the figures the draft has no more
                for (WorldCollectionRecord.Figure saved : base.figures()) {
                    if (!inDraft.contains(saved.id())) {
                        kept.add(saved.id());
                    }
                }
            }
            if (!kept.equals(CreatorDraft.serverOrder(base, kept))) {
                units.add(new Unit(List.of(new WorldCollectionEdit.ReorderFigures(id, kept)), List.of()));
            }
            if (draft.archived() != base.archived() && !base.draft()) {
                units.add(new Unit(List.of(new WorldCollectionEdit.SetArchived(id, draft.archived())), List.of()));
            }
            if (publish && base.draft()) {
                units.add(new Unit(List.of(new WorldCollectionEdit.Publish(id)), List.of()));
            }
        }
        return new Plan(id, pack(units, unitsPerBatch), draft.isNew(), skipped);
    }

    /** The edits that save one complete figure, or null when it did not change. */
    @Nullable
    private static Unit figureUnit(String id, CreatorDraft.FigureDraft figure,
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
        return new Unit(edits, files);
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
        int unitsInBatch = 0;
        for (Unit unit : units) {
            if (!edits.isEmpty() && (unitsInBatch >= unitsPerBatch || !fits(edits, unit.edits()))) {
                submissions.add(submission(edits, files));
                edits = new ArrayList<>();
                files = new LinkedHashMap<>();
                unitsInBatch = 0;
            }
            edits.addAll(unit.edits());
            unitsInBatch++;
            for (CreatorDraft.FileRef ref : unit.files()) {
                if (ref != null && ref.needsUpload() && sent.add(ref.sha())) {
                    files.put(ref.sha(), ref.local());
                }
            }
        }
        if (!edits.isEmpty()) {
            submissions.add(submission(edits, files));
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

    private static Submission submission(List<WorldCollectionEdit> edits, Map<String, FilePicker.LocalFile> files) {
        WorldCollectionEdit edit = edits.size() == 1 ? edits.get(0) : new WorldCollectionEdit.Batch(edits);
        return new Submission(List.copyOf(files.values()), edit, edits.size());
    }

    // ---- Sending

    /**
     * Saves everything that changed in the draft, and publishes it when asked.
     *
     * @param receiver told how it ended, on the render thread, even after the screen closed
     */
    public static Run save(CreatorDraft draft, boolean publish, Consumer<Outcome> receiver) {
        Run run = new Run(draft, () -> plan(draft, publish), receiver);
        run.start();
        return run;
    }

    /** Saves one figure of the draft; see {@link #planFigure}. */
    public static Run saveFigure(CreatorDraft draft, String figureId, Consumer<Outcome> receiver) {
        Run run = new Run(draft, () -> planFigure(draft, figureId), receiver);
        run.start();
        return run;
    }

    /** Saves some figures of the draft in small batches; see {@link #planFigures}. */
    public static Run saveFigures(CreatorDraft draft, Collection<String> figureIds, int figuresPerBatch,
                                  Consumer<Outcome> receiver) {
        List<String> ids = List.copyOf(figureIds);
        Run run = new Run(draft, () -> planFigures(draft, ids, figuresPerBatch), receiver);
        run.start();
        return run;
    }

    /** Saves the figure at a position of the draft. */
    public static Run saveFigure(CreatorDraft draft, int index, Consumer<Outcome> receiver) {
        return saveFigure(draft, draft.figures().get(index).id(), receiver);
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
     * its receiver before the method that started it returns.
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

        private Run(@Nullable CreatorDraft draft, Supplier<Plan> planner,
                    Consumer<Outcome> receiver) {
            this.draft = draft;
            this.planner = planner;
            this.receiver = receiver;
        }

        private void start() {
            plan = planner.get();
            next = 0;
            if (plan.isEmpty()) {
                finish(Ending.SAVED, WorldContentStatus.of(Result.OK));
                return;
            }
            send();
        }

        private void send() {
            Submission submission = plan.submissions().get(next);
            boolean accepted = ClientWorldUploads.submit(submission.files(), submission.edit(), this, this::answered);
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
