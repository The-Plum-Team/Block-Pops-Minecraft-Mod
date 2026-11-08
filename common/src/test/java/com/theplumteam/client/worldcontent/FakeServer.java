package com.theplumteam.client.worldcontent;

import com.theplumteam.network.WorldCollectionStatusPacket;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for the game and the server at the other end of {@link ClientWorldUploads}: a clock
 * the test moves, a connection it can drop, and the requests the client sent, which the test
 * answers as the server would. Every file the client announces is answered as already stored.
 */
final class FakeServer implements ClientWorldUploads.Host {
    /** A request waiting for its answer: an announced file (sha) or an edit. */
    private record Request(int id, @Nullable String sha, @Nullable WorldCollectionEdit edit) {
    }

    long now = 1_000_000L;
    boolean connected = true;
    /** Every edit the client sent, in order. */
    final List<WorldCollectionEdit> edits = new ArrayList<>();
    /** The sha of every file the client announced, in order. */
    final List<String> uploads = new ArrayList<>();
    private final Deque<Request> waiting = new ArrayDeque<>();

    @Override
    public long millis() {
        return now;
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public void begin(int requestId, BlobType type, int size, String sha) {
        uploads.add(sha);
        waiting.add(new Request(requestId, sha, null));
    }

    @Override
    public void chunk(int uploadId, int offset, byte[] bytes) {
        throw new AssertionError("Every file is answered as already stored, so no chunk is sent");
    }

    @Override
    public void commit(int uploadId) {
        throw new AssertionError("Every file is answered as already stored, so nothing is committed");
    }

    @Override
    public void edit(int requestId, WorldCollectionEdit edit) {
        edits.add(edit);
        waiting.add(new Request(requestId, null, edit));
    }

    void advance(long millis) {
        now += millis;
    }

    /** Whether the client waits for an answer. */
    boolean hasWaiting() {
        return !waiting.isEmpty();
    }

    /**
     * Answers the files the client announced as already stored, then the edit after them.
     *
     * @return the edit answered
     */
    WorldCollectionEdit answer(Result result) {
        return answer(result, "");
    }

    WorldCollectionEdit answer(Result result, String detail) {
        while (true) {
            Request request = waiting.poll();
            if (request == null) {
                throw new AssertionError("No edit waits for an answer");
            }
            if (request.edit() == null) {
                ClientWorldUploads.onStatus(new WorldCollectionStatusPacket(request.id(),
                        WorldContentStatus.of(Result.ALREADY_STORED), 0, request.sha()));
                continue;
            }
            ClientWorldUploads.onStatus(new WorldCollectionStatusPacket(request.id(),
                    WorldContentStatus.of(result, detail), 0, ""));
            return request.edit();
        }
    }

    /** The last edit the client sent. */
    WorldCollectionEdit lastEdit() {
        if (edits.isEmpty()) {
            throw new AssertionError("No edit was sent");
        }
        return edits.get(edits.size() - 1);
    }
}
