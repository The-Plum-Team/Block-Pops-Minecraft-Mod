package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Saves one World collection's working copy ({@link CreatorDraft}) while the operator edits it,
 * and owns that draft and its undo steps for as long as anything of it is not saved, whether an
 * editor shows it or not ({@link CreatorAutosave}).
 *
 * <p>A draft collection ({@link Mode#AUTO}) saves itself. Every change the draft hears is timed by
 * its kind: a typed text {@value #TEXT_MS} ms after the last key, or at once when its field loses
 * the keyboard; a number {@value #NUMBER_MS} ms after the last value, or {@value #RELEASED_MS} ms
 * after a slider is let go; anything else {@value #DISCRETE_MS} ms after it. A file goes only when
 * the operator moves on ({@link #flushNow}): to another figure or tab, a screen of the editor's
 * own, Ctrl+S, publishing or leaving, so trying ten skins on one figure uploads one. A save starts
 * only when no other run of the collection is on its way or waits for its sync
 * ({@link CreatorRuns}), at least {@link #MIN_FLUSH_INTERVAL_MS} after this client's last
 * autosave of any collection, and when the budget has room for it and its reserve
 * ({@link ClientWorldUploads}). It sends what {@link CreatorSave#planAvailable} says can go and
 * holds the rest, which the {@link #status} names. A published or archived collection
 * ({@link Mode#HOLD}) keeps its changes until {@link #publishChanges}.
 *
 * <p>When the server is busy or does not answer, the save waits {@value #BUSY_BACKOFF_MS} ms and
 * tries again, up to {@value #MAX_BUSY_TRIES} times in a row; then it has failed. When it refuses
 * a save of several units as invalid or over a limit, the next saves send one unit to a batch until
 * the refused one is found, which is then held with the server's message until it changes; any
 * other refusal fails the save. A failed save sends nothing more, and ends a publish that waits for
 * it, until a new change or {@link #retry}; left in the background, it is reported once. An
 * accepted save is remembered in the draft ({@link CreatorDraft#markSent}); a new figure the save
 * put keeps its id, and one whose name changed while its put was on its way takes the id its name
 * makes once the save is over.
 *
 * <p>The sync that shows a collection published switches it to {@link Mode#HOLD}, and the undo
 * steps and what saves sent start again; a publish asked for here and not sent yet, when another
 * operator or a data pack published it first, ends as done. A collection that is no longer synced,
 * as one deleted for good, ends its saver.
 *
 * Client thread only.
 */
public final class AutoSaver {
    /** The least time between two autosaves of this client, of any collection. */
    public static final long MIN_FLUSH_INTERVAL_MS = 5_000;
    /** How long a typed text settles before it is saved. */
    public static final long TEXT_MS = 1_500;
    /** How long a number settles before it is saved. */
    public static final long NUMBER_MS = 1_500;
    /** How long a number settles after its slider was let go. */
    public static final long RELEASED_MS = 600;
    /** How long any other change settles before it is saved. */
    public static final long DISCRETE_MS = 400;
    /** How long to wait after the server was busy or did not answer. */
    public static final long BUSY_BACKOFF_MS = 15_000;
    /** How many busy answers in a row make a save fail. */
    public static final int MAX_BUSY_TRIES = 8;
    // How long to wait when too many sends are queued, and how often a name being typed is asked about
    private static final long QUEUE_RETRY_MS = 1_000;
    private static final long TYPING_RETRY_MS = 300;
    // How long a worked-out status lasts when nothing changed
    private static final long ANALYSIS_MS = 500;
    private static final String SAVING_KEY = "blockpops.world_creator.editor.saving";
    // The refusals a save of several units is narrowed down for, one unit to a batch
    private static final Set<Result> UNIT_REFUSALS = EnumSet.of(Result.INVALID, Result.TOO_LARGE, Result.QUOTA,
            Result.UNKNOWN_FIGURE, Result.FULL, Result.MISSING_ASSET);

    /** Whether the collection saves itself. */
    public enum Mode {
        /** A draft: its changes are saved a moment after they settle. */
        AUTO,
        /** Published or archived: its changes wait for {@link #publishChanges}. */
        HOLD
    }

    /** How long a change settles before it is saved, by its change key. */
    public enum KeyClass {
        TEXT, NUMBER, DISCRETE, FILE
    }

    /** The save state, highest priority first. */
    public enum State {
        /** Not connected: the changes wait. */
        OFFLINE,
        /** The server refused a save, or was busy too many times in a row. */
        FAILED,
        /** Changes that cannot be saved as they are. */
        HELD,
        /** A bulk add of figures is on its way, which the saver waits for. */
        BULK,
        /** A save is uploading a file. */
        UPLOADING,
        /** A save is on its way, or waits for the sync that shows it. */
        SAVING,
        /** The server was busy, or the budget has no room yet: the save goes in a while. */
        BUSY,
        /** Too many sends wait: the save goes in a moment. */
        QUEUED,
        /** Files wait until the operator moves on. */
        FILES_WAITING,
        /** Changes are saved in a moment. */
        PENDING,
        /** Every change of the draft is saved. */
        SAVED,
        /** A published collection with nothing waiting. */
        PUBLISHED,
        /** An archived collection with nothing waiting. */
        ARCHIVED,
        /** A published or archived collection with changes that wait for Publish changes. */
        UNPUBLISHED
    }

    /**
     * What the saver is doing, for the editor's status.
     *
     * @param held       the units held back, refusals included
     * @param failures   what the server said when it refused, or why the save failed
     * @param upload     the upload of the save on its way, or null
     * @param waitMillis how long until a busy save goes again
     * @param pending    how many changes wait, as the draft counts them
     */
    public record Status(State state, List<CreatorSave.Held> held, List<Component> failures,
                         @Nullable ClientWorldUploads.Progress upload, long waitMillis, int pending) {
    }

    // A unit the server refused alone, and what it said
    private record Refusal(List<WorldCollectionEdit> edits, Component message) {
    }

    // A publish the operator asked for, waiting for the saves before it, and who hears how it ended
    private static final class PublishRequest {
        final Function<CreatorDraft, Component> blocker;
        final Consumer<Component> done;
        boolean sent;

        PublishRequest(Function<CreatorDraft, Component> blocker, Consumer<Component> done) {
            this.blocker = blocker;
            this.done = done;
        }
    }

    private final String collectionId;
    private final CreatorDraft draft;
    private Mode mode;
    private int attached;
    private int seenRevision;
    private boolean cancelled;

    // When each kind of change was last made and not saved since; 0 for none
    private long textAt;
    private long numberAt;
    private boolean numberReleased;
    private long discreteAt;
    // The operator moved on, so the next save may upload files
    private boolean triggered;
    // Something is left to save after a save or a sync
    private boolean followUp;
    private long retryAt;
    private long budgetUntil;
    private boolean queueFull;
    private int busyTries;

    // The new figure whose name has the keyboard, and when each figure's name last changed
    @Nullable
    private String typingFigure;
    private final Map<String, Long> nameChangedAt = new HashMap<>();
    // New figures renamed while their put was on its way, whose ids follow their names afterwards
    private final Set<String> frozenRenames = new HashSet<>();

    // The save on its way, and the figures it puts or changes
    @Nullable
    private CreatorSave.Run flight;
    private Set<String> flightFigures = Set.of();
    // The bulk add on its way, and the figures it puts
    @Nullable
    private CreatorSave.Run bulk;
    private Set<String> bulkFigures = Set.of();
    private final Set<Object> pausers = new HashSet<>();
    @Nullable
    private PublishRequest publishing;

    // Narrowing a refusal down, the units refused alone, and why the last save failed
    private boolean isolating;
    private final Map<String, Refusal> refused = new HashMap<>();
    @Nullable
    private List<Component> failures;
    // Whether the operator was told that a save left in the background could not finish
    private boolean reported;

    // What can go now and what is held, worked out on a change and at most every ANALYSIS_MS
    @Nullable
    private CreatorSave.Available analysis;
    private int pendingCount;
    private long analyzedAt;
    private boolean stale = true;

    private final CreatorSave.Hold hold = new CreatorSave.Hold() {
        @Override
        public boolean filesReleased() {
            return triggered || publishing != null;
        }

        @Override
        public boolean typing(String figureId) {
            if (publishing != null) {
                return false;
            }
            Long changed = nameChangedAt.get(figureId);
            return figureId.equals(typingFigure) || changed != null && Util.getMillis() - changed < TEXT_MS;
        }

        @Override
        @Nullable
        public Component refused(String unitKey, List<WorldCollectionEdit> edits) {
            Refusal refusal = refused.get(unitKey);
            if (refusal == null) {
                return null;
            }
            if (!refusal.edits().equals(edits)) {
                // It changed since the server refused it, so it is tried again
                refused.remove(unitKey);
                return null;
            }
            return refusal.message();
        }

        @Override
        public int unitsPerBatch() {
            return isolating ? 1 : Integer.MAX_VALUE;
        }
    };

    AutoSaver(WorldCollectionRecord record) {
        this.collectionId = record.id();
        this.draft = CreatorDraft.of(record);
        this.mode = record.draft() ? Mode.AUTO : Mode.HOLD;
        this.seenRevision = ClientWorldCollections.revision();
        this.draft.listen(this::changed);
    }

    // ---- Reading

    public String collectionId() {
        return this.collectionId;
    }

    /** The working copy this saver owns, which every editor of the collection edits. */
    public CreatorDraft draft() {
        return this.draft;
    }

    public Mode mode() {
        return this.mode;
    }

    /** Whether the collection is a draft, whose changes save themselves. */
    public boolean isAuto() {
        return this.mode == Mode.AUTO;
    }

    /** Whether a save of this saver's is on its way. */
    public boolean hasFlight() {
        return this.flight != null;
    }

    /**
     * Whether a save or a bulk add is on its way that puts or changes the figure, whose id then
     * holds still.
     */
    public boolean isSaving(String figureId) {
        return this.flight != null && this.flightFigures.contains(figureId)
                || this.bulk != null && this.bulkFigures.contains(figureId);
    }

    /**
     * Whether a save failed for good, or the server refused it as a whole: nothing is sent until a
     * new change or {@link #retry}.
     */
    public boolean hasFailed() {
        return this.failures != null;
    }

    /** Why the last save failed for good, or an empty list while none did. */
    public List<Component> failures() {
        return this.failures != null ? this.failures : List.of();
    }

    /**
     * Whether any run of the collection is on its way or waits for the sync that shows it: a
     * save of this saver's, a bulk add or another of the creator's runs.
     */
    public boolean isBusy() {
        return CreatorRuns.busy(this.collectionId);
    }

    /** The bulk add of figures this saver sends, while it is on its way; null otherwise. */
    @Nullable
    public CreatorSave.Run bulkRun() {
        return this.bulk;
    }

    /** Whether a publish the operator asked for is waiting or on its way. */
    public boolean isPublishing() {
        return this.publishing != null;
    }

    /**
     * Whether the saver still sends something of a draft collection, or waits for the sync of
     * what it sent; changes that cannot be saved as they are, or whose save failed, do not count.
     */
    public boolean isSaving() {
        if (this.flight != null || this.bulk != null || isBusy()) {
            return true;
        }
        if (this.mode != Mode.AUTO || this.failures != null) {
            return false;
        }
        CreatorSave.Available available = analysis();
        return !available.isEmpty() || available.filesWaiting() || available.typingWaits();
    }

    /** Whether nothing of the collection waits here: no change, no save on its way, no sync awaited. */
    public boolean isIdle() {
        return this.flight == null && this.bulk == null && this.publishing == null && !isBusy()
                && this.draft.changes().isEmpty();
    }

    /** What can go now and what is held, as last worked out. */
    public CreatorSave.Available analysis() {
        long now = Util.getMillis();
        if (this.analysis == null || this.stale || now - this.analyzedAt >= ANALYSIS_MS) {
            this.analysis = CreatorSave.planAvailable(this.draft, false, this.hold);
            this.pendingCount = this.draft.changes().size();
            this.analyzedAt = now;
            this.stale = false;
        }
        return this.analysis;
    }

    /** The units held back, refusals included. */
    public List<CreatorSave.Held> held() {
        return analysis().held();
    }

    /** What the saver is doing now, highest priority first; see {@link State}. */
    public Status status() {
        long now = Util.getMillis();
        CreatorSave.Available available = analysis();
        int pending = this.pendingCount;
        List<CreatorSave.Held> held = available.held();
        if (Minecraft.getInstance().getConnection() == null) {
            return new Status(State.OFFLINE, held, List.of(), null, 0, pending);
        }
        List<Component> messages = new ArrayList<>();
        if (this.failures != null) {
            messages.addAll(this.failures);
        }
        List<CreatorSave.Held> local = new ArrayList<>();
        for (CreatorSave.Held unit : held) {
            if (unit.reason() == CreatorSave.Reason.REFUSED) {
                messages.add(unit.detail());
            } else {
                local.add(unit);
            }
        }
        if (!messages.isEmpty()) {
            return new Status(State.FAILED, held, messages, null, 0, pending);
        }
        if (!local.isEmpty()) {
            return new Status(State.HELD, held, List.of(), null, 0, pending);
        }
        if (this.bulk != null) {
            return new Status(State.BULK, held, List.of(), null, 0, pending);
        }
        CreatorSave.Run sending = this.flight;
        if (sending != null) {
            long wait = ClientWorldUploads.waitMillis(sending);
            if (wait > 0) {
                return new Status(State.BUSY, held, List.of(), null, wait, pending);
            }
            ClientWorldUploads.Progress upload = sending.progress().upload();
            if (upload != null && !upload.file().isEmpty() && upload.total() > 0) {
                return new Status(State.UPLOADING, held, List.of(), upload, 0, pending);
            }
            return new Status(State.SAVING, held, List.of(), null, 0, pending);
        }
        if (this.mode == Mode.HOLD) {
            State state = pending > 0 ? State.UNPUBLISHED : this.draft.archived() ? State.ARCHIVED : State.PUBLISHED;
            return new Status(state, held, List.of(), null, 0, pending);
        }
        if (isBusy()) {
            return new Status(State.SAVING, held, List.of(), null, 0, pending);
        }
        if (this.busyTries > 0 && this.retryAt > now) {
            return new Status(State.BUSY, held, List.of(), null, this.retryAt - now, pending);
        }
        if (this.budgetUntil > now) {
            return new Status(State.BUSY, held, List.of(), null, this.budgetUntil - now, pending);
        }
        if (this.queueFull && this.retryAt > now) {
            return new Status(State.QUEUED, held, List.of(), null, 0, pending);
        }
        if (available.filesWaiting()) {
            return new Status(State.FILES_WAITING, held, List.of(), null, 0, pending);
        }
        if (!available.isEmpty() || available.typingWaits()) {
            return new Status(State.PENDING, held, List.of(), null, 0, pending);
        }
        return new Status(State.SAVED, held, List.of(), null, 0, pending);
    }

    // ---- What the editor tells the saver

    /**
     * The operator moved on, or asked for a save: what waits is due now, files included, though
     * the save still waits for the interval, the budget and the runs before it.
     */
    public void flushNow() {
        this.triggered = true;
        this.stale = true;
    }

    /** A text field lost the keyboard: what was typed in it is due now. */
    public void textLeft() {
        if (this.textAt != 0) {
            this.textAt = Util.getMillis() - TEXT_MS;
        }
        this.stale = true;
    }

    /** A slider or pad was let go: the number it set settles sooner. */
    public void numberReleased() {
        this.numberReleased = true;
    }

    /** The new figure whose name field has the keyboard now, or null for none. */
    public void setTyping(@Nullable String figureId) {
        if (figureId == null ? this.typingFigure != null : !figureId.equals(this.typingFigure)) {
            this.typingFigure = figureId;
            this.stale = true;
        }
    }

    /**
     * A new figure's name changed while its put was on its way, so its id held still; once the
     * save is over, its id follows its name again.
     */
    public void renamedWhileSaving(String figureId) {
        this.frozenRenames.add(figureId);
    }

    /** Holds the saves back while a token, such as an open bulk add, says so. */
    public void pause(Object token) {
        this.pausers.add(token);
    }

    /** Lets the saves go again once no token holds them. */
    public void resume(Object token) {
        if (this.pausers.remove(token)) {
            this.followUp = true;
            this.stale = true;
        }
    }

    /** Tries again what failed or the server refused, at once. */
    public void retry() {
        this.failures = null;
        this.refused.clear();
        this.isolating = false;
        this.busyTries = 0;
        this.retryAt = 0;
        this.reported = false;
        flushNow();
    }

    /**
     * Puts every held unit back as the server has it, so nothing of it is sent later, as leaving
     * without the changes that cannot be saved does.
     */
    public void revertHeld() {
        for (CreatorSave.Held unit : analysis().held()) {
            switch (unit.reason()) {
                case NAME, BOX -> this.draft.revertIdentity();
                case LINK -> this.draft.revertExtras();
                case SKIN, FIGURE, LOST_FILE -> {
                    if (unit.figureId() != null) {
                        this.draft.revertFigure(unit.figureId());
                    }
                }
                case REFUSED -> revertRefused(unit);
                case TOO_BIG -> this.draft.discardPending();
            }
        }
        this.refused.clear();
        this.failures = null;
        this.stale = true;
    }

    // Only the unit the server refused goes back; the draft's other changes stay and are sent
    private void revertRefused(CreatorSave.Held unit) {
        String key = unit.unitKey();
        if (unit.figureId() != null) {
            this.draft.revertFigure(unit.figureId());
            return;
        }
        switch (key) {
            case "identity" -> this.draft.revertIdentity();
            case "extras" -> this.draft.revertExtras();
            case "holographic" -> this.draft.revertHolographic();
            case "order" -> this.draft.revertOrder();
            case "archive" -> this.draft.revertArchived();
            default -> {
                // Nothing of the draft's own is that unit alone; it is tried again with the rest
            }
        }
    }

    /** Drops every change of a published or archived collection, as leaving with Discard does. */
    public void discard() {
        this.draft.discardPending();
        this.stale = true;
    }

    // ---- Publishing

    /**
     * Publishes a draft collection: saves what waits first, files included, then, once nothing is
     * on its way, checks the collection again and sends the last changes with the publish.
     *
     * @param blocker why the collection cannot be published as it is, or null when it can
     * @param done    told how it ended: null once published, else what holds it back
     */
    public void publish(Function<CreatorDraft, Component> blocker, Consumer<Component> done) {
        if (this.mode != Mode.AUTO || this.publishing != null) {
            return;
        }
        if (this.failures != null) {
            // The saves before the publish failed, and nothing goes until a change or a retry
            done.accept(this.failures.get(0));
            return;
        }
        this.publishing = new PublishRequest(blocker, done);
        flushNow();
    }

    // Ends a publish that waits for the saves before it, telling how: null once the collection is
    // published, else why it was not
    private void endPublish(@Nullable Component failure) {
        PublishRequest request = this.publishing;
        if (request == null || request.sent) {
            return;
        }
        this.publishing = null;
        this.stale = true;
        request.done.accept(failure);
    }

    /** Forgets a publish that has not been sent yet, as when its editor closes. */
    public void cancelPublish() {
        if (this.publishing != null && !this.publishing.sent) {
            this.publishing = null;
            this.stale = true;
        }
    }

    /**
     * Sends a published or archived collection's changes, all of them or none: a held unit holds
     * the whole change back.
     *
     * @param done told how it ended: null once saved, else what holds it back or the refusal
     */
    public void publishChanges(Consumer<Component> done) {
        if (this.mode != Mode.HOLD || this.publishing != null) {
            return;
        }
        if (this.flight != null || isBusy()) {
            done.accept(Component.translatable(SAVING_KEY));
            return;
        }
        this.publishing = new PublishRequest(current -> null, done);
        CreatorSave.Available available = CreatorSave.planAvailable(this.draft, false, this.hold);
        if (!available.held().isEmpty()) {
            PublishRequest request = this.publishing;
            this.publishing = null;
            request.done.accept(available.held().get(0).detail());
            return;
        }
        if (available.isEmpty()) {
            PublishRequest request = this.publishing;
            this.publishing = null;
            request.done.accept(null);
            return;
        }
        this.publishing.sent = true;
        start(available.plan(), false);
    }

    // ---- The bulk add

    /**
     * Sends figures a bulk add just put in the draft, in small batches, while the saver waits;
     * what the server took is remembered as a save's is.
     *
     * @param receiver told how it ended, after the saver
     */
    public CreatorSave.Run saveFigures(List<String> figureIds, Consumer<CreatorSave.Outcome> receiver) {
        CreatorSave.Run[] started = new CreatorSave.Run[1];
        CreatorSave.Run run = CreatorSave.saveFigures(this.draft, figureIds, CreatorSave.BULK_FIGURES_PER_BATCH, true,
                outcome -> {
                    if (started[0] != null && this.bulk == started[0]) {
                        this.bulk = null;
                        this.bulkFigures = Set.of();
                    }
                    if (this.cancelled) {
                        return;
                    }
                    if (started[0] != null) {
                        remember(started[0].plan(), outcome.saved());
                    }
                    followFrozenNames();
                    this.followUp = true;
                    this.stale = true;
                    receiver.accept(outcome);
                });
        started[0] = run;
        if (!run.isFinished()) {
            this.bulk = run;
            // Their ids hold still until the bulk add is over, as a save's figures do
            this.bulkFigures = figuresOf(run.plan());
        }
        return run;
    }

    // ---- Ticking

    /** Follows the syncs and starts a save when one is due. Every client tick, while the saver lives. */
    public void tick() {
        if (this.cancelled) {
            return;
        }
        syncNow();
        if (this.cancelled) {
            return;
        }
        long now = Util.getMillis();
        if (this.mode != Mode.AUTO || this.flight != null || this.bulk != null || !this.pausers.isEmpty()) {
            return;
        }
        if (this.failures != null) {
            // Failed: the same refused save is not sent again until a new change or a retry
            endPublish(this.failures.get(0));
            return;
        }
        boolean wantsPublish = this.publishing != null && !this.publishing.sent;
        if (!wantsPublish && !due(now)) {
            return;
        }
        if (Minecraft.getInstance().getConnection() == null || isBusy() || this.budgetUntil > now
                || this.retryAt > now) {
            return;
        }
        if (!wantsPublish && now - CreatorAutosave.lastFlushAt() < MIN_FLUSH_INTERVAL_MS) {
            return;
        }
        CreatorSave.Available available = CreatorSave.planAvailable(this.draft, false, this.hold);
        this.analysis = available;
        this.pendingCount = this.draft.changes().size();
        this.analyzedAt = now;
        this.stale = false;
        if (available.isEmpty()) {
            this.textAt = 0;
            this.numberAt = 0;
            this.discreteAt = 0;
            this.followUp = false;
            this.triggered = false;
            this.retryAt = 0;
            if (available.typingWaits()) {
                // Asked again shortly, since a name stops counting as typed by itself
                this.retryAt = now + TYPING_RETRY_MS;
            }
            if (wantsPublish) {
                sendPublish();
            }
            return;
        }
        ClientWorldUploads.Cost first = cost(available.plan().submissions().get(0));
        long room = ClientWorldUploads.roomInMillis(first, true);
        if (room > 0) {
            this.budgetUntil = now + room;
            return;
        }
        start(available.plan(), true);
    }

    // Whether a change has settled, or the operator moved on, or a save is to be tried again
    private boolean due(long now) {
        return this.triggered || this.followUp || this.retryAt != 0 && now >= this.retryAt
                || this.textAt != 0 && now - this.textAt >= TEXT_MS
                || this.numberAt != 0 && now - this.numberAt >= (this.numberReleased ? RELEASED_MS : NUMBER_MS)
                || this.discreteAt != 0 && now - this.discreteAt >= DISCRETE_MS;
    }

    // Every figure a plan puts or changes
    private static Set<String> figuresOf(CreatorSave.Plan plan) {
        Set<String> figures = new HashSet<>();
        for (CreatorSave.Submission submission : plan.submissions()) {
            for (CreatorSave.UnitInfo unit : submission.units()) {
                figures.addAll(unit.figures());
            }
        }
        return Set.copyOf(figures);
    }

    private static ClientWorldUploads.Cost cost(CreatorSave.Submission submission) {
        return ClientWorldUploads.Cost.of(submission.files(), submission.edit());
    }

    // The draft is as the operator wants it and nothing is on its way: the collection is checked once
    // more and published with what changed meanwhile
    private void sendPublish() {
        PublishRequest request = this.publishing;
        if (request == null) {
            return;
        }
        Component blocked = request.blocker.apply(this.draft);
        if (blocked != null) {
            this.publishing = null;
            request.done.accept(blocked);
            return;
        }
        CreatorSave.Available available = CreatorSave.planAvailable(this.draft, true, this.hold);
        request.sent = true;
        start(available.plan(), false);
    }

    // Sends a plan as this saver's save
    private void start(CreatorSave.Plan plan, boolean background) {
        long now = Util.getMillis();
        this.textAt = 0;
        this.numberAt = 0;
        this.numberReleased = false;
        this.discreteAt = 0;
        this.triggered = false;
        this.followUp = false;
        this.retryAt = 0;
        this.budgetUntil = 0;
        this.queueFull = false;
        if (background) {
            CreatorAutosave.markFlush(now);
        }
        this.flightFigures = figuresOf(plan);
        CreatorSave.Run[] started = new CreatorSave.Run[1];
        CreatorSave.Run run = CreatorSave.send(this.draft, plan, background, outcome -> landed(started[0], plan, outcome));
        started[0] = run;
        this.flight = run.isFinished() ? null : run;
        this.stale = true;
    }

    // A save of this saver's ended
    private void landed(@Nullable CreatorSave.Run run, CreatorSave.Plan plan, CreatorSave.Outcome outcome) {
        if (run == null || this.flight == run) {
            this.flight = null;
        }
        this.flightFigures = Set.of();
        if (this.cancelled) {
            // Its collection is gone, or the client left: nothing is said
            return;
        }
        remember(plan, outcome.saved());
        followFrozenNames();
        this.stale = true;
        PublishRequest request = this.publishing;
        boolean published = request != null && request.sent;
        long now = Util.getMillis();
        switch (outcome.ending()) {
            case SAVED -> {
                this.busyTries = 0;
                this.isolating = false;
                this.failures = null;
                this.followUp = true;
            }
            case REFUSED -> refusedSave(plan, outcome, now);
            case NOT_SENT -> {
                this.queueFull = ClientWorldUploads.isFull();
                this.retryAt = now + QUEUE_RETRY_MS;
            }
            case DISCONNECTED, CANCELLED -> {
                // A disconnect ends every saver; a cancel was asked for
            }
        }
        if (published) {
            this.publishing = null;
            request.done.accept(outcome.ok() ? null : outcome.message());
        } else if (this.failures != null) {
            // A publish that waited for this save cannot go now: it ends with what went wrong,
            // rather than waiting for a save that is not sent again until a change or a retry
            endPublish(this.failures.get(0));
        }
    }

    // The names typed while the ids held still decide the ids of figures still new
    private void followFrozenNames() {
        for (String figureId : this.frozenRenames) {
            CreatorDraft.FigureDraft figure = this.draft.figure(figureId);
            if (figure != null && figure.isNew()) {
                figure.followName();
            }
        }
        this.frozenRenames.clear();
    }

    /**
     * Takes a refusal: busy or not answered goes again after a while, until it has happened too
     * often; invalid or over a limit is narrowed down to the unit refused alone, which is then
     * held; anything else, or a busy server too often, fails the save, which sends nothing more
     * until a change or {@link #retry}.
     */
    private void refusedSave(CreatorSave.Plan plan, CreatorSave.Outcome outcome, long now) {
        Result result = outcome.status().result();
        if (result == Result.BUSY || result == Result.TIMED_OUT) {
            this.busyTries++;
            if (this.busyTries >= MAX_BUSY_TRIES) {
                this.failures = List.of(outcome.message());
            } else {
                this.retryAt = now + BUSY_BACKOFF_MS;
            }
            return;
        }
        if (UNIT_REFUSALS.contains(result) && outcome.saved() < plan.submissions().size()) {
            List<CreatorSave.UnitInfo> units = plan.submissions().get(outcome.saved()).units();
            if (units.size() == 1) {
                // Refused alone: held with what the server said until it changes, and the rest goes on
                CreatorSave.UnitInfo unit = units.get(0);
                this.refused.put(unit.key(), new Refusal(unit.edits(), outcome.message()));
            } else {
                this.isolating = true;
            }
            this.followUp = true;
            return;
        }
        this.failures = List.of(outcome.message());
    }

    // Remembers in the draft what the batches the server took sent
    private void remember(CreatorSave.Plan plan, int saved) {
        List<CreatorSave.Submission> submissions = plan.submissions();
        for (int i = 0; i < Math.min(saved, submissions.size()); i++) {
            for (CreatorSave.UnitInfo unit : submissions.get(i).units()) {
                this.draft.markSent(unit.values(), unit.putFigures());
            }
        }
    }

    /**
     * Takes the newest sync of the collection, if one came since the last: the draft takes the
     * newer record ({@link CreatorDraft#rebase}), the mode follows whether it is a draft, and a
     * collection no longer synced ends the saver.
     */
    public void syncNow() {
        int revision = ClientWorldCollections.revision();
        if (revision == this.seenRevision || this.cancelled) {
            return;
        }
        this.seenRevision = revision;
        WorldCollectionRecord record = ClientWorldCollections.record(this.collectionId);
        if (record == null) {
            if (ClientWorldCollections.isSynced()) {
                // Deleted for good, or no longer sent to this player
                BlockPopsMod.logDebug("World collection {} is gone; its saver ends", this.collectionId);
                cancel();
                CreatorAutosave.remove(this);
            }
            return;
        }
        if (!record.equals(this.draft.base())) {
            this.draft.rebase(record, revision);
        }
        Mode next = record.draft() ? Mode.AUTO : Mode.HOLD;
        if (this.mode == Mode.AUTO && next == Mode.HOLD) {
            // Published: undo would bring back draft-era states, and saves start again
            this.draft.forgetSent();
            this.draft.clearUndo();
            this.refused.clear();
            this.isolating = false;
            this.failures = null;
            this.textAt = 0;
            this.numberAt = 0;
            this.discreteAt = 0;
            this.triggered = false;
            this.busyTries = 0;
            this.retryAt = 0;
            // Published by another operator or a data pack before this client's publish went: what
            // it asked for is done, and its changes not sent yet wait for Publish changes
            this.mode = next;
            endPublish(null);
        }
        this.mode = next;
        if (this.mode == Mode.AUTO && this.draft.isDirty() && this.failures == null) {
            // A change made while a save was on its way, or changed back since, is sent next; a
            // failed save waits for a change or a retry instead
            this.followUp = true;
        }
        this.stale = true;
    }

    // ---- Changes the draft heard

    private void changed(String key) {
        long now = Util.getMillis();
        switch (classOf(key)) {
            case TEXT -> this.textAt = now;
            case NUMBER -> {
                this.numberAt = now;
                this.numberReleased = false;
            }
            case DISCRETE, FILE -> this.discreteAt = now;
        }
        if (key.startsWith(CreatorDraft.FIGURE) && key.endsWith("/name")) {
            this.nameChangedAt.put(key.substring(CreatorDraft.FIGURE.length(), key.length() - "/name".length()), now);
        }
        // A new change tries again what failed
        this.failures = null;
        this.reported = false;
        this.stale = true;
    }

    /** How long a change of the key settles before it is saved. */
    public static KeyClass classOf(String key) {
        if (key.startsWith(CreatorDraft.FIGURE)) {
            String field = key.substring(key.lastIndexOf('/') + 1);
            return switch (field) {
                case "name", "variants" -> KeyClass.TEXT;
                case "texture", "model", "animation" -> KeyClass.FILE;
                default -> KeyClass.DISCRETE;
            };
        }
        return switch (key) {
            case CreatorDraft.NAME, CreatorDraft.AUTHOR, CreatorDraft.AUTHOR_URL -> KeyClass.TEXT;
            case CreatorDraft.RARITY_WEIGHTS, CreatorDraft.LOGO_LAYOUT -> KeyClass.NUMBER;
            case CreatorDraft.LOGO -> KeyClass.FILE;
            default -> KeyClass.DISCRETE;
        };
    }

    // ---- Life

    void attach() {
        this.attached++;
    }

    /** The editor showing the draft closed: what waits is due, files included, and goes in the background. */
    void detach() {
        this.attached = Math.max(0, this.attached - 1);
        if (this.attached == 0) {
            this.typingFigure = null;
            cancelPublish();
            flushNow();
        }
    }

    /** Whether an editor shows the draft. */
    public boolean isAttached() {
        return this.attached > 0;
    }

    /**
     * Whether a save left in the background cannot finish by itself, and nothing else is on its
     * way or waits: it failed, which stops it whatever is left to send, or everything left is
     * held. True once until a change or a retry.
     */
    boolean needsReport() {
        if (this.reported || this.attached > 0 || this.mode != Mode.AUTO || this.flight != null || this.bulk != null
                || isBusy()) {
            return false;
        }
        boolean stuck = this.failures != null;
        if (!stuck) {
            CreatorSave.Available available = analysis();
            stuck = !available.held().isEmpty() && available.isEmpty() && !available.filesWaiting()
                    && !available.typingWaits();
        }
        if (!stuck) {
            return false;
        }
        this.reported = true;
        return true;
    }

    /** Ends the saver: nothing more is sent, and what is on its way is no longer heard. */
    void cancel() {
        this.cancelled = true;
        CreatorSave.Run sending = this.flight;
        if (sending != null) {
            // The batches not started are dropped; the one being sent finishes unheard
            sending.cancel();
        }
        this.flight = null;
        this.publishing = null;
        this.draft.listen(null);
    }

    public boolean isCancelled() {
        return this.cancelled;
    }
}
