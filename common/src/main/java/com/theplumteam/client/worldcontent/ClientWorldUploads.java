package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldCollectionEditPacket;
import com.theplumteam.network.WorldCollectionStatusPacket;
import com.theplumteam.network.WorldUploadBeginPacket;
import com.theplumteam.network.WorldUploadChunkPacket;
import com.theplumteam.network.WorldUploadCommitPacket;
import com.theplumteam.server.worldcollections.WorldUploads;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
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
 * them, as an editor's Save produces. Submissions run one at a time, because the server
 * takes one upload per player. Each file is announced with its type, size and sha; once
 * the server answers READY its bytes follow in {@link WorldUploads#CHUNK_BYTES} chunks,
 * at most {@link #CHUNKS_PER_TICK} per client tick, and a commit asks the server to check
 * and store it. A file the server already has (ALREADY_STORED) is skipped. The edit is
 * sent only after every file is stored, and the first refusal ends the submission.
 *
 * <p>Answers are routed by the request id each step carries. Waiting
 * {@link #ANSWER_TIMEOUT_NANOS} for one counts as a failure. {@link #reset()} cancels
 * everything when the client leaves or joins a server, and tells each submission it was
 * disconnected.
 *
 * <p>A submission may carry a tag, such as the save it belongs to, so its owner can tell
 * its progress apart and drop what it still has waiting ({@link #cancelQueued}). While
 * paused ({@link #pause()}), the submission in flight finishes and the next one waits.
 *
 * <p>Receivers run on the render thread even when the screen that submitted has closed,
 * so a Save completes in the background; a receiver that updates a screen checks that it
 * is still shown.
 *
 * Client thread only.
 */
public final class ClientWorldUploads {
    /** Chunks sent per client tick: 64 KiB, so a 512 KiB file takes eight ticks. */
    public static final int CHUNKS_PER_TICK = 4;
    /** How long to wait for any answer the server owes before giving up. */
    private static final long ANSWER_TIMEOUT_NANOS = 30_000_000_000L;
    /** Submissions waiting behind the current one. */
    private static final int MAX_QUEUED = 8;

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

    private static final class Submission {
        final List<FilePicker.LocalFile> files;
        @Nullable
        final WorldCollectionEdit edit;
        final Consumer<Outcome> receiver;
        @Nullable
        final Object tag;
        int index;
        Phase phase;
        int requestId;
        int uploadId;
        int offset;
        long deadline;

        Submission(List<FilePicker.LocalFile> files, @Nullable WorldCollectionEdit edit, Consumer<Outcome> receiver,
                   @Nullable Object tag) {
            this.files = files;
            this.edit = edit;
            this.receiver = receiver;
            this.tag = tag;
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

    private ClientWorldUploads() {
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || QUEUE.size() >= MAX_QUEUED) {
            return false;
        }
        QUEUE.add(new Submission(List.copyOf(unique.values()), edit, receiver, tag));
        if (current == null && !paused) {
            startNext();
        }
        return true;
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

    /** Sends the next chunks and gives up on answers that are overdue. Every client tick. */
    public static void tick() {
        Submission submission = current;
        if (submission == null) {
            return;
        }
        if (submission.phase != Phase.SENDING) {
            if (System.nanoTime() - submission.deadline > 0) {
                finish(new Outcome(WorldContentStatus.of(Result.TIMED_OUT, "The server did not answer in time"),
                        false));
            }
            return;
        }
        FilePicker.LocalFile file = submission.file();
        byte[] bytes = file.bytes();
        for (int i = 0; i < CHUNKS_PER_TICK && submission.offset < bytes.length; i++) {
            int end = Math.min(bytes.length, submission.offset + WorldUploads.CHUNK_BYTES);
            new WorldUploadChunkPacket(submission.uploadId, submission.offset,
                    Arrays.copyOfRange(bytes, submission.offset, end)).sendToServer();
            submission.offset = end;
        }
        if (submission.offset == bytes.length) {
            submission.phase = Phase.COMMIT;
            submission.deadline = System.nanoTime() + ANSWER_TIMEOUT_NANOS;
            new WorldUploadCommitPacket(submission.uploadId).sendToServer();
        }
    }

    /**
     * Cancels everything in flight and waiting, telling each submission it was
     * disconnected, and drops any file pick still on its way. Runs when the client leaves
     * or joins a server.
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
    }

    /** Begins the next file, or sends the edit after the last one, or ends with the last answer. */
    private static void advance(Submission submission, WorldContentStatus last) {
        if (submission.index < submission.files.size()) {
            FilePicker.LocalFile file = submission.file();
            submission.phase = Phase.BEGIN;
            submission.uploadId = 0;
            submission.offset = 0;
            submission.requestId = nextRequestId();
            submission.deadline = System.nanoTime() + ANSWER_TIMEOUT_NANOS;
            new WorldUploadBeginPacket(submission.requestId, file.type(), file.bytes().length, file.sha())
                    .sendToServer();
        } else if (submission.edit != null) {
            submission.phase = Phase.EDIT;
            submission.requestId = nextRequestId();
            submission.deadline = System.nanoTime() + ANSWER_TIMEOUT_NANOS;
            new WorldCollectionEditPacket(submission.requestId, submission.edit).sendToServer();
        } else {
            finish(new Outcome(last, false));
        }
    }

    private static void startNext() {
        Submission next = QUEUE.poll();
        current = next;
        if (next != null) {
            advance(next, WorldContentStatus.of(Result.OK));
        }
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
