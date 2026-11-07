package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldCollectionEditPacket;
import com.theplumteam.network.WorldCollectionStatusPacket;
import com.theplumteam.network.WorldUploadBeginPacket;
import com.theplumteam.network.WorldUploadChunkPacket;
import com.theplumteam.network.WorldUploadCommitPacket;
import com.theplumteam.server.worldcollections.WorldRateLimits;
import com.theplumteam.server.worldcollections.WorldUploads;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Files and edits this client sends to the server's World collections.
 *
 * <p>A submission is a list of checked files followed by an optional edit that names
 * them, as a creator save produces. Submissions run one at a time, because the server
 * takes one upload per player. Each file is announced with its type, size and sha; once
 * the server answers READY its bytes follow in {@link WorldUploads#CHUNK_BYTES} chunks,
 * at most {@link #CHUNKS_PER_TICK} per client tick, and a commit asks the server to check
 * and store it. A file the server already has (ALREADY_STORED) is skipped. The edit is
 * sent only after every file is stored, and the first refusal ends the submission.
 *
 * <p>Answers are routed by the request id each step carries. Waiting
 * {@link #ANSWER_TIMEOUT_MS} for one counts as a failure. {@link #reset()} cancels
 * everything when the client leaves or joins a server, and tells each submission it was
 * disconnected.
 *
 * <p>A submission may carry a tag, such as the save it belongs to, so its owner can tell
 * its progress apart and drop what it still has waiting ({@link #cancelQueued}). While
 * paused ({@link #pause()}), the submission in flight finishes and the next one waits.
 *
 * <p>Receivers run on the render thread even when the screen that submitted has closed,
 * so a save completes in the background; a receiver that updates a screen checks that it
 * is still shown.
 *
 * <p>Every submission is charged to one budget of this client's, a sliding-window ledger that
 * mirrors the server's per-player limits ({@link WorldRateLimits}, the upload limits of
 * {@link WorldContentLimits}) a little under them: edits per second and per minute, where a batch
 * costs one edit per {@link WorldRateLimits#EDITS_PER_COST} it holds, upload begins and bytes per
 * minute, and imports per minute, which an import spends instead of an edit. Each part is charged
 * when it is sent. A submission at the head of the queue that the budget has no room for waits
 * until it has ({@link #waitMillis}) rather than being refused as busy by the server. A
 * {@linkplain #submit(List, WorldCollectionEdit, Object, boolean, Consumer) background} submission,
 * such as an autosave or a bulk add, also leaves a reserve, so what the operator does by hand still
 * finds room.
 *
 * Client thread only.
 */
public final class ClientWorldUploads {
    /** Chunks sent per client tick: 64 KiB, so a 512 KiB file takes eight ticks. */
    public static final int CHUNKS_PER_TICK = 4;
    /** How long to wait for any answer the server owes before giving up, by {@link Host#millis()}. */
    static final long ANSWER_TIMEOUT_MS = 30_000;
    /** Submissions waiting behind the current one. */
    private static final int MAX_QUEUED = 8;
    private static final long SECOND_MS = 1_000;
    private static final long MINUTE_MS = 60_000;
    // The budget, a margin under the server's limits
    private static final int EDITS_PER_SECOND = WorldRateLimits.EDITS_PER_SECOND - 1;
    private static final int EDITS_PER_MINUTE = WorldRateLimits.EDITS_PER_MINUTE - 5;
    private static final int BEGINS_PER_MINUTE = WorldContentLimits.UPLOAD_BEGINS_PER_MINUTE - 10;
    private static final long BYTES_PER_MINUTE = WorldContentLimits.UPLOAD_BYTES_PER_MINUTE - 2L * 1024 * 1024;
    private static final int IMPORTS_PER_MINUTE = WorldContentLimits.IMPORTS_PER_MINUTE;
    // What a background submission leaves for the operator's own actions
    private static final int RESERVE_EDITS_PER_SECOND = 1;
    private static final int RESERVE_EDITS_PER_MINUTE = 10;
    private static final int RESERVE_BEGINS = 20;
    private static final long RESERVE_BYTES = 4L * 1024 * 1024;

    /**
     * How a submission ended.
     *
     * @param status       the server's last answer, or the reason the client gave up
     * @param disconnected whether it was cancelled because the client left or joined a server
     * @param cancelled    whether its owner dropped it before it started
     */
    public record Outcome(WorldContentStatus status, boolean disconnected, boolean cancelled) {
        public Outcome(WorldContentStatus status, boolean disconnected) {
            this(status, disconnected, false);
        }

        public boolean ok() {
            return !disconnected && !cancelled && status.isSuccess();
        }
    }

    /**
     * How far the current submission is.
     *
     * @param file  the name of the file being sent, or empty while the edit is sent
     * @param sent  bytes of that file sent so far
     * @param total its size
     * @param tag   the tag the submission was given, or null
     */
    public record Progress(String file, int sent, int total, int filesDone, int filesTotal, @Nullable Object tag) {
    }

    private enum Phase {
        /** Waiting for the answer to a begin. */
        BEGIN,
        /** Sending chunks; the server answers only to refuse one. */
        SENDING,
        /** Waiting for the answer to a commit. */
        COMMIT,
        /** Waiting for the answer to the edit. */
        EDIT
    }

    /**
     * What sending something takes of the budget.
     *
     * @param editCost what its edit costs, as the server counts it; 0 for none or an import
     * @param imports  1 for an import, which spends an import instead of an edit
     * @param begins   how many uploads it begins
     * @param bytes    how many bytes those uploads hold
     */
    public record Cost(int editCost, int imports, int begins, long bytes) {
        /** What a submission of these files and this edit takes. */
        public static Cost of(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit) {
            long bytes = 0;
            for (FilePicker.LocalFile file : files) {
                bytes += file.bytes().length;
            }
            boolean imports = edit instanceof WorldCollectionEdit.ImportPack;
            int cost = edit == null || imports ? 0 : WorldRateLimits.editCost(edit);
            return new Cost(cost, imports ? 1 : 0, files.size(), bytes);
        }
    }

    // One charge of the budget: when, and how much of its kind
    private record Charge(long at, long amount) {
    }

    private static final class Submission {
        final List<FilePicker.LocalFile> files;
        @Nullable
        final WorldCollectionEdit edit;
        final Consumer<Outcome> receiver;
        @Nullable
        final Object tag;
        final boolean background;
        final Cost cost;
        int index;
        Phase phase;
        int requestId;
        int uploadId;
        int offset;
        long deadline;

        Submission(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit, Consumer<Outcome> receiver,
                   @Nullable Object tag, boolean background) {
            this.files = files;
            this.edit = edit;
            this.receiver = receiver;
            this.tag = tag;
            this.background = background;
            this.cost = Cost.of(files, edit);
        }

        FilePicker.LocalFile file() {
            return files.get(index);
        }
    }

    private static final ArrayDeque<Submission> QUEUE = new ArrayDeque<>();
    @Nullable
    private static Submission current;
    private static int lastRequestId;
    private static boolean paused;
    // The budget's ledger, oldest first
    private static final ArrayDeque<Charge> EDITS = new ArrayDeque<>();
    private static final ArrayDeque<Charge> BEGINS = new ArrayDeque<>();
    private static final ArrayDeque<Charge> BYTES = new ArrayDeque<>();
    private static final ArrayDeque<Charge> IMPORTS = new ArrayDeque<>();

    /**
     * The game's clock, its connection and the server at the other end, which the uploads (their
     * budget and the deadlines of the answers they wait for), the creator's runs ({@link CreatorRuns})
     * and its savers ({@link AutoSaver}) go by. The game always uses {@link #GAME}; only the unit
     * tests put a stand-in in its place.
     */
    interface Host {
        /** Milliseconds, as {@link Util#getMillis()} counts them. */
        long millis();

        /** Whether the client is connected to a server. */
        boolean connected();

        /** Announces a file to upload ({@link WorldUploadBeginPacket}). */
        void begin(int requestId, BlobType type, int size, String sha);

        /** Sends part of a file being uploaded ({@link WorldUploadChunkPacket}). */
        void chunk(int uploadId, int offset, byte[] bytes);

        /** Asks the server to check and store an uploaded file ({@link WorldUploadCommitPacket}). */
        void commit(int uploadId);

        /** Sends an edit ({@link WorldCollectionEditPacket}). */
        void edit(int requestId, WorldCollectionEdit edit);
    }

    static final Host GAME = new Host() {
        @Override
        public long millis() {
            return Util.getMillis();
        }

        @Override
        public boolean connected() {
            return Minecraft.getInstance().getConnection() != null;
        }

        @Override
        public void begin(int requestId, BlobType type, int size, String sha) {
            new WorldUploadBeginPacket(requestId, type, size, sha).sendToServer();
        }

        @Override
        public void chunk(int uploadId, int offset, byte[] bytes) {
            new WorldUploadChunkPacket(uploadId, offset, bytes).sendToServer();
        }

        @Override
        public void commit(int uploadId) {
            new WorldUploadCommitPacket(uploadId).sendToServer();
        }

        @Override
        public void edit(int requestId, WorldCollectionEdit edit) {
            new WorldCollectionEditPacket(requestId, edit).sendToServer();
        }
    };

    static Host host = GAME;

    private ClientWorldUploads() {
    }

    /** The time the creator's saving goes by: {@link Util#getMillis()}. */
    static long millis() {
        return host.millis();
    }

    /** Whether the client is connected to a server, so a submission can be sent. */
    static boolean connected() {
        return host.connected();
    }

    /**
     * Uploads the files the server does not have yet, then sends the edit, if any.
     *
     * @param files    checked files; the same sha twice is sent once
     * @param receiver told how it ended, on the render thread
     * @return whether it was accepted; false while not connected or when too many wait
     */
    public static boolean submit(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit,
                                 Consumer<Outcome> receiver) {
        return submit(files, edit, null, receiver);
    }

    /**
     * Uploads the files the server does not have yet, then sends the edit, if any, tagged
     * so that {@link #progress()} names the tag and {@link #cancelQueued} can drop it.
     *
     * @param tag      compared by identity; null for none
     * @param receiver told how it ended, on the render thread
     * @return whether it was accepted; false while not connected or when too many wait
     */
    public static boolean submit(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit,
                                 @Nullable Object tag, Consumer<Outcome> receiver) {
        return submit(files, edit, tag, false, receiver);
    }

    /**
     * Uploads the files the server does not have yet, then sends the edit, if any, tagged as
     * {@link #submit(List, WorldCollectionEdit, Object, Consumer)} does.
     *
     * @param background whether it is sent on the operator's behalf rather than at their request,
     *                   as an autosave or a bulk add is: it then waits until the budget has room
     *                   for it and the reserve
     * @return whether it was accepted; false while not connected or when too many wait, which
     *         {@link #isFull()} tells apart
     */
    public static boolean submit(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit,
                                 @Nullable Object tag, boolean background, Consumer<Outcome> receiver) {
        if (files.isEmpty() && edit == null) {
            throw new IllegalArgumentException("Nothing to submit");
        }
        Map<String, FilePicker.LocalFile> unique = new LinkedHashMap<>();
        for (FilePicker.LocalFile file : files) {
            if (!Sha256.isValidHex(file.sha()) || file.bytes().length == 0
                    || file.bytes().length > file.type().maxBytes()) {
                throw new IllegalArgumentException("Not a checked World collection file");
            }
            unique.putIfAbsent(file.sha(), file);
        }
        if (!host.connected() || QUEUE.size() >= MAX_QUEUED) {
            return false;
        }
        QUEUE.add(new Submission(List.copyOf(unique.values()), edit, receiver, tag, background));
        if (current == null && !paused) {
            startNext();
        }
        return true;
    }

    /** Whether as many submissions wait as may, so another is refused until one is sent. */
    public static boolean isFull() {
        return QUEUE.size() >= MAX_QUEUED;
    }

    /** Sends one edit, after anything submitted before it. */
    public static boolean edit(WorldCollectionEdit edit, Consumer<Outcome> receiver) {
        return submit(List.of(), edit, receiver);
    }

    /**
     * Holds every submission that has not started yet. The one in flight still finishes,
     * because the server takes an upload or an edit only whole.
     */
    public static void pause() {
        paused = true;
    }

    /** Starts the next waiting submission again after {@link #pause()}. */
    public static void resume() {
        paused = false;
        if (current == null) {
            startNext();
        }
    }

    public static boolean isPaused() {
        return paused;
    }

    /**
     * Drops every waiting submission with the tag, telling each one it was cancelled. The
     * one in flight is not stopped: its files and edit may already be on the server.
     *
     * @return how many were dropped
     */
    public static int cancelQueued(Object tag) {
        List<Submission> cancelled = new ArrayList<>();
        QUEUE.removeIf(submission -> {
            if (submission.tag == tag) {
                cancelled.add(submission);
                return true;
            }
            return false;
        });
        Outcome outcome = new Outcome(WorldContentStatus.of(Result.TIMED_OUT, "Cancelled"), false, true);
        for (Submission submission : cancelled) {
            notify(submission, outcome);
        }
        return cancelled.size();
    }

    /** How many submissions with the tag are in flight or waiting. */
    public static int pending(Object tag) {
        int count = current != null && current.tag == tag ? 1 : 0;
        for (Submission submission : QUEUE) {
            if (submission.tag == tag) {
                count++;
            }
        }
        return count;
    }

    /** Whether anything is being sent or waits to be. */
    public static boolean isBusy() {
        return current != null || !QUEUE.isEmpty();
    }

    // The budget

    /**
     * How long, in milliseconds, until the budget has room for a cost, with the reserve when it
     * is sent in the background; 0 when it has room now.
     */
    public static long roomInMillis(Cost cost, boolean background) {
        long now = host.millis();
        prune(now);
        int reserveSecond = background ? RESERVE_EDITS_PER_SECOND : 0;
        int reserveMinute = background ? RESERVE_EDITS_PER_MINUTE : 0;
        long wait = 0;
        wait = Math.max(wait, waitFor(EDITS, now, SECOND_MS, EDITS_PER_SECOND - reserveSecond, cost.editCost()));
        wait = Math.max(wait, waitFor(EDITS, now, MINUTE_MS, EDITS_PER_MINUTE - reserveMinute, cost.editCost()));
        wait = Math.max(wait, waitFor(BEGINS, now, MINUTE_MS, BEGINS_PER_MINUTE - (background ? RESERVE_BEGINS : 0),
                cost.begins()));
        wait = Math.max(wait, waitFor(BYTES, now, MINUTE_MS, BYTES_PER_MINUTE - (background ? RESERVE_BYTES : 0),
                cost.bytes()));
        wait = Math.max(wait, waitFor(IMPORTS, now, MINUTE_MS, IMPORTS_PER_MINUTE, cost.imports()));
        return wait;
    }

    /** Whether the budget has room for a cost now; see {@link #roomInMillis}. */
    public static boolean hasRoom(Cost cost, boolean background) {
        return roomInMillis(cost, background) == 0;
    }

    /**
     * How long the next submission with the tag waits for the budget, in milliseconds, while it
     * is the one at the head of the queue and the budget holds it; 0 otherwise.
     */
    public static long waitMillis(Object tag) {
        Submission head = QUEUE.peek();
        if (current != null || paused || head == null || head.tag != tag) {
            return 0;
        }
        return roomInMillis(head.cost, head.background);
    }

    /**
     * How long until the charges of a window leave room for more: the oldest charges within
     * the window drop out first. A need larger than the whole window waits for the window to be
     * empty, so it is never held for ever.
     */
    private static long waitFor(ArrayDeque<Charge> charges, long now, long window, long limit, long need) {
        if (need <= 0) {
            return 0;
        }
        long used = 0;
        for (Charge charge : charges) {
            if (now - charge.at() < window) {
                used += charge.amount();
            }
        }
        if (used + need <= Math.max(0, limit)) {
            return 0;
        }
        long left = used;
        for (Charge charge : charges) {
            if (now - charge.at() >= window) {
                continue;
            }
            left -= charge.amount();
            if (left + need <= limit || left <= 0) {
                return Math.max(1, charge.at() + window - now);
            }
        }
        return 0;
    }

    // Forgets the charges the longest window no longer holds
    private static void prune(long now) {
        for (ArrayDeque<Charge> charges : List.of(EDITS, BEGINS, BYTES, IMPORTS)) {
            while (!charges.isEmpty() && now - charges.peekFirst().at() >= MINUTE_MS) {
                charges.removeFirst();
            }
        }
    }

    private static void chargeEdit(WorldCollectionEdit edit) {
        long now = host.millis();
        if (edit instanceof WorldCollectionEdit.ImportPack) {
            IMPORTS.addLast(new Charge(now, 1));
        } else {
            EDITS.addLast(new Charge(now, WorldRateLimits.editCost(edit)));
        }
    }

    private static void chargeBegin(FilePicker.LocalFile file) {
        long now = host.millis();
        BEGINS.addLast(new Charge(now, 1));
        BYTES.addLast(new Charge(now, file.bytes().length));
    }

    /** How far the current submission is, or null when there is none. */
    @Nullable
    public static Progress progress() {
        Submission submission = current;
        if (submission == null) {
            return null;
        }
        if (submission.phase == Phase.EDIT) {
            return new Progress("", 0, 0, submission.files.size(), submission.files.size(), submission.tag);
        }
        FilePicker.LocalFile file = submission.file();
        return new Progress(file.name(), submission.offset, file.bytes().length, submission.index,
                submission.files.size(), submission.tag);
    }

    /** Receives the server's answer to an upload step or an edit. */
    public static void onStatus(WorldCollectionStatusPacket packet) {
        Submission submission = current;
        WorldContentStatus status = packet.status();
        if (submission == null || packet.requestId() != submission.requestId) {
            // Most often the answer to a request that timed out here or was cancelled
            BlockPopsMod.logDebug("Ignoring the answer {} to World collection request {}, which is not waiting",
                    status.result(), packet.requestId());
            return;
        }
        if (submission.phase == Phase.EDIT) {
            finish(new Outcome(status, false));
            return;
        }
        FilePicker.LocalFile file = submission.file();
        if (!packet.sha().equals(file.sha())
                || (submission.phase != Phase.BEGIN && packet.uploadId() != submission.uploadId)) {
            BlockPopsMod.LOGGER.warn("Ignoring an answer about another World collection upload");
            return;
        }
        if (!status.isSuccess()) {
            finish(new Outcome(status, false));
            return;
        }
        Result result = status.result();
        if (submission.phase == Phase.BEGIN && result == Result.READY && packet.uploadId() > 0) {
            submission.phase = Phase.SENDING;
            submission.uploadId = packet.uploadId();
            submission.offset = 0;
        } else if ((submission.phase == Phase.BEGIN && result == Result.ALREADY_STORED)
                || (submission.phase == Phase.COMMIT && result == Result.STORED)) {
            submission.index++;
            advance(submission, status);
        } else {
            BlockPopsMod.LOGGER.warn("Ignoring the unexpected answer {} to a World collection upload", result);
        }
    }

    /**
     * Sends the next chunks, gives up on answers that are overdue and starts the submission the
     * budget held once it has room. Every client tick.
     */
    public static void tick() {
        Submission submission = current;
        if (submission == null) {
            if (!paused && !QUEUE.isEmpty()) {
                startNext();
            }
            return;
        }
        if (submission.phase != Phase.SENDING) {
            if (host.millis() - submission.deadline > 0) {
                finish(new Outcome(WorldContentStatus.of(Result.TIMED_OUT, "The server did not answer in time"),
                        false));
            }
            return;
        }
        FilePicker.LocalFile file = submission.file();
        byte[] bytes = file.bytes();
        for (int i = 0; i < CHUNKS_PER_TICK && submission.offset < bytes.length; i++) {
            int end = Math.min(bytes.length, submission.offset + WorldUploads.CHUNK_BYTES);
            host.chunk(submission.uploadId, submission.offset, Arrays.copyOfRange(bytes, submission.offset, end));
            submission.offset = end;
        }
        if (submission.offset == bytes.length) {
            submission.phase = Phase.COMMIT;
            submission.deadline = host.millis() + ANSWER_TIMEOUT_MS;
            host.commit(submission.uploadId);
        }
    }

    /**
     * Cancels everything in flight and waiting, telling each submission it was
     * disconnected, and drops any file pick still on its way, the budget's ledger, the
     * creator's runs ({@link CreatorRuns}) and its savers ({@link CreatorAutosave}). Runs when
     * the client leaves or joins a server, whose limits start again for this player.
     */
    public static void reset() {
        List<Submission> cancelled = new ArrayList<>();
        if (current != null) {
            cancelled.add(current);
            current = null;
        }
        cancelled.addAll(QUEUE);
        QUEUE.clear();
        paused = false;
        FilePicker.forgetPending();
        Outcome disconnected = new Outcome(WorldContentStatus.of(Result.TIMED_OUT, "Disconnected"), true);
        for (Submission submission : cancelled) {
            notify(submission, disconnected);
        }
        EDITS.clear();
        BEGINS.clear();
        BYTES.clear();
        IMPORTS.clear();
        CreatorRuns.reset();
        CreatorAutosave.reset();
    }

    /** Begins the next file, or sends the edit after the last one, or ends with the last answer. */
    private static void advance(Submission submission, WorldContentStatus last) {
        if (submission.index < submission.files.size()) {
            FilePicker.LocalFile file = submission.file();
            submission.phase = Phase.BEGIN;
            submission.uploadId = 0;
            submission.offset = 0;
            submission.requestId = nextRequestId();
            submission.deadline = host.millis() + ANSWER_TIMEOUT_MS;
            chargeBegin(file);
            host.begin(submission.requestId, file.type(), file.bytes().length, file.sha());
        } else if (submission.edit != null) {
            submission.phase = Phase.EDIT;
            submission.requestId = nextRequestId();
            submission.deadline = host.millis() + ANSWER_TIMEOUT_MS;
            chargeEdit(submission.edit);
            host.edit(submission.requestId, submission.edit);
        } else {
            finish(new Outcome(last, false));
        }
    }

    /** Starts the submission at the head of the queue, unless the budget has no room for it yet. */
    private static void startNext() {
        Submission head = QUEUE.peek();
        if (head == null || roomInMillis(head.cost, head.background) > 0) {
            // tick() tries again once the budget has room
            return;
        }
        Submission next = QUEUE.poll();
        current = next;
        advance(next, WorldContentStatus.of(Result.OK));
    }

    private static void finish(Outcome outcome) {
        Submission done = current;
        current = null;
        if (done != null) {
            notify(done, outcome);
        }
        // A receiver may already have submitted again, which started it
        if (current == null && !paused) {
            startNext();
        }
    }

    private static void notify(Submission submission, Outcome outcome) {
        try {
            submission.receiver.accept(outcome);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.error("A World collection upload receiver failed", e);
        }
    }

    /** The next request id: positive, and never one of an earlier connection still in flight. */
    private static int nextRequestId() {
        lastRequestId = lastRequestId == Integer.MAX_VALUE ? 1 : lastRequestId + 1;
        return lastRequestId;
    }
}
