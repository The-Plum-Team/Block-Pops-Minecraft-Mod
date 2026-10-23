package com.theplumteam.server.worldcollections;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldAssetChunkPacket;
import com.theplumteam.network.WorldAssetRequestPacket;
import com.theplumteam.worldcontent.BlobType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends World collection files to the players that ask for them.
 *
 * <p>Each player has a queue of the files it asked for, served in order. A file is read
 * from disk on the World content executor only once it reaches the head of the queue,
 * and at most {@link #MAX_BUFFERED} are held per player. The server tick sends at most
 * {@link #PLAYER_TICK_BYTES} to each player and {@link #SERVER_TICK_BYTES} in all, and
 * never lets more than {@link #WINDOW_BYTES} a player has not acknowledged be in flight,
 * so a slow client only slows itself.
 *
 * <p>A player may ask only after it received a sync, only for files a registered
 * collection of its view names (a draft's files only while it is an operator, though a
 * copy already under way is finished), and for each file once per connection, plus once
 * more when it could not verify the copy it received.
 *
 * <p>A file dropped before it was sent whole does not count against that: one no sync
 * names any more is sent after the next sync that names it again, and one that could not
 * be read is marked missing, which the next sync tells the player. A retry for a file
 * whose copy is partly sent, as when the player released it and named it again meanwhile,
 * sends it whole once more after that copy.
 *
 * Server thread only, except the reads.
 */
public final class WorldAssetSender {
    public static final int PLAYER_TICK_BYTES = 256 * 1024;
    public static final int SERVER_TICK_BYTES = 2 * 1024 * 1024;
    public static final int WINDOW_BYTES = 1024 * 1024;
    public static final int MAX_BUFFERED = 2;
    /** Each file once, and once more on a retry. */
    private static final int MAX_SENDS = 2;

    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static int rotation;

    /** What one connection asked for and was sent. */
    private static final class Session {
        final Deque<String> queue = new ArrayDeque<>();
        final Deque<Buffered> buffered = new ArrayDeque<>();
        /** Files queued or buffered, so a file is never queued twice. */
        final Set<String> pending = new HashSet<>();
        final Map<String, Integer> sends = new HashMap<>();
        /** Files to send whole again once their copy in progress ends. */
        final Set<String> resend = new HashSet<>();
        /** Files dropped unsent since the last sync, sent once a sync names them again. */
        final Set<String> dropped = new HashSet<>();
        long sent;
        long acknowledged;
    }

    /** A file being read or sent. */
    private static final class Buffered {
        final String sha;
        final BlobType type;
        final CompletableFuture<byte[]> bytes;
        int offset;

        Buffered(String sha, BlobType type, CompletableFuture<byte[]> bytes) {
            this.sha = sha;
            this.type = type;
            this.bytes = bytes;
        }
    }

    private WorldAssetSender() {
    }

    /**
     * Lets a player ask for files once it has been sent a sync, and queues again the files
     * dropped unsent that this sync names, which the player still waits for.
     */
    public static void onSyncSent(ServerPlayer player) {
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), key -> new Session());
        if (session.dropped.isEmpty()) {
            return;
        }
        Set<String> named = WorldCollections.sentShasFor(player);
        for (String sha : session.dropped) {
            if (named.contains(sha) && !WorldCollections.isMissing(sha) && !session.pending.contains(sha)) {
                session.sends.merge(sha, 1, Integer::sum);
                session.queue.addLast(sha);
                session.pending.add(sha);
            }
        }
        session.dropped.clear();
    }

    /** Queues the files a player asked for, ignoring every request it may not make. */
    public static void request(ServerPlayer player, List<WorldAssetRequestPacket.Request> requests) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !WorldAssetChunkPacket.canReceive(player)) {
            BlockPopsMod.LOGGER.warn("Player {} asked for World collection files before it was sent any",
                    player.getName().getString());
            return;
        }
        Set<String> named = WorldCollections.sentShasFor(player);
        int foreign = 0;
        for (WorldAssetRequestPacket.Request request : requests) {
            String sha = request.sha();
            if (!named.contains(sha)) {
                foreign++;
                continue;
            }
            if (WorldCollections.isMissing(sha)) {
                continue;
            }
            int sends = session.sends.getOrDefault(sha, 0);
            if (session.pending.contains(sha)) {
                // The player dropped the first parts of a copy it released and named again
                if (request.retry() && sends < MAX_SENDS && isPartlySent(session, sha) && session.resend.add(sha)) {
                    session.sends.put(sha, sends + 1);
                }
                continue;
            }
            if (sends >= MAX_SENDS || (sends > 0 && !request.retry())) {
                BlockPopsMod.logDebug("Ignoring a repeated request for World collection file {}", sha);
                continue;
            }
            session.sends.put(sha, sends + 1);
            session.queue.addLast(sha);
            session.pending.add(sha);
        }
        if (foreign > 0) {
            BlockPopsMod.LOGGER.warn("Player {} asked for {} files no World collection names",
                    player.getName().getString(), foreign);
        }
    }

    /** Records how many bytes a player has received on this connection. */
    public static void acknowledge(ServerPlayer player, int bytesReceived) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (bytesReceived < session.acknowledged || bytesReceived > session.sent) {
            BlockPopsMod.LOGGER.warn("Player {} acknowledged {} World collection bytes, but {} were sent and {} acknowledged",
                    player.getName().getString(), bytesReceived, session.sent, session.acknowledged);
            return;
        }
        session.acknowledged = bytesReceived;
    }

    /** Reads and sends what the budgets allow. Server tick. */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        WorldCollectionStore store = WorldCollections.store();
        ExecutorService executor = WorldCollections.executor();
        if (store == null || executor == null) {
            return;
        }
        List<UUID> players = new ArrayList<>(SESSIONS.keySet());
        // Start with another player each tick, so no player always takes the whole budget first
        int start = Math.floorMod(rotation++, players.size());
        int serverBudget = SERVER_TICK_BYTES;
        for (int i = 0; i < players.size() && serverBudget > 0; i++) {
            UUID uuid = players.get((start + i) % players.size());
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                SESSIONS.remove(uuid);
                continue;
            }
            serverBudget -= send(player, SESSIONS.get(uuid), store, executor, Math.min(PLAYER_TICK_BYTES, serverBudget));
        }
    }

    /** Sends the player at most budget bytes. @return how many were sent */
    private static int send(ServerPlayer player, Session session, WorldCollectionStore store, ExecutorService executor,
                            int budget) {
        int sentNow = 0;
        while (sentNow < budget) {
            fill(player, session, store, executor);
            Buffered head = session.buffered.peekFirst();
            if (head == null || !head.bytes.isDone()) {
                break;
            }
            if (head.bytes.isCompletedExceptionally()) {
                session.buffered.pollFirst();
                session.resend.remove(head.sha);
                forgo(session, head.sha);
                WorldCollections.markLost(store, head.sha);
                continue;
            }
            // A file read while the player could still see it, as a draft's before it was deopped,
            // is not started once its view lost it; a copy already under way is finished
            if (head.offset == 0 && !WorldCollections.sentShasFor(player).contains(head.sha)) {
                session.buffered.pollFirst();
                session.resend.remove(head.sha);
                forgo(session, head.sha);
                continue;
            }
            byte[] bytes = head.bytes.join();
            long window = WINDOW_BYTES - (session.sent - session.acknowledged);
            if (window <= 0) {
                break;
            }
            int length = (int) Math.min(Math.min(WorldAssetChunkPacket.MAX_DATA, bytes.length - head.offset),
                    Math.min(budget - sentNow, window));
            new WorldAssetChunkPacket(head.sha, head.type, bytes.length, head.offset,
                    Arrays.copyOfRange(bytes, head.offset, head.offset + length)).sendToPlayer(player);
            head.offset += length;
            session.sent += length;
            sentNow += length;
            if (head.offset >= bytes.length) {
                session.buffered.pollFirst();
                if (session.resend.remove(head.sha)) {
                    session.queue.addLast(head.sha);
                } else {
                    session.pending.remove(head.sha);
                }
            }
        }
        return sentNow;
    }

    /** Starts reading the files at the head of the queue, while fewer than the maximum are held. */
    private static void fill(ServerPlayer player, Session session, WorldCollectionStore store,
                             ExecutorService executor) {
        while (session.buffered.size() < MAX_BUFFERED && !session.queue.isEmpty()) {
            String sha = session.queue.pollFirst();
            WorldCollectionStore.StoredBlob blob = store.state().blobs().get(sha);
            // The file may have been dropped or lost since it was asked for, or the player's
            // view may have lost it, as a draft's files when the player stops being an operator
            if (blob == null || store.isMissing(sha) || !WorldCollections.sentShasFor(player).contains(sha)) {
                forgo(session, sha);
                continue;
            }
            BlobType type = blob.info().type();
            int size = blob.info().size();
            CompletableFuture<byte[]> bytes;
            try {
                bytes = CompletableFuture.supplyAsync(() -> read(store, sha, type, size), executor);
            } catch (RejectedExecutionException e) {
                session.pending.remove(sha);
                continue;
            }
            session.buffered.addLast(new Buffered(sha, type, bytes));
        }
    }

    /**
     * Drops a file before it was sent whole, without spending the send, so the next sync
     * that names it can send it.
     */
    private static void forgo(Session session, String sha) {
        session.pending.remove(sha);
        session.sends.computeIfPresent(sha, (key, sends) -> sends > 1 ? sends - 1 : null);
        session.dropped.add(sha);
    }

    private static boolean isPartlySent(Session session, String sha) {
        for (Buffered buffered : session.buffered) {
            if (buffered.sha.equals(sha)) {
                return buffered.offset > 0;
            }
        }
        return false;
    }

    private static byte[] read(WorldCollectionStore store, String sha, BlobType type, int size) {
        try {
            byte[] bytes = store.readBlob(sha, type.maxBytes());
            if (bytes.length != size) {
                throw new IOException("Stored file " + sha + " is " + bytes.length + " bytes, not " + size);
            }
            return bytes;
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Could not send the World collection file {}: {}", sha, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Forgets a player that left, with its queue and the files held for it. */
    public static void dropPlayer(UUID player) {
        SESSIONS.remove(player);
    }

    public static void clear() {
        SESSIONS.clear();
        rotation = 0;
    }
}
