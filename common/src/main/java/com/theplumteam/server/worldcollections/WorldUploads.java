package com.theplumteam.server.worldcollections;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldCollectionStatusPacket;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Files operators are uploading for World collections, from the begin packet to the
 * answer to the commit.
 *
 * <p>An upload declares its size, which bounds its buffer, and the server holds at most
 * {@link #MAX_PER_SERVER} of them at once, one per player. Chunks must arrive in order
 * from the player that began it; anything else aborts it. An upload that stalls for
 * {@link #IDLE_TIMEOUT_NANOS} or runs longer than {@link #TOTAL_TIMEOUT_NANOS} is
 * dropped by the server tick, and a player that leaves drops its own.
 *
 * Server thread only.
 */
public final class WorldUploads {
    /** The most bytes one chunk carries, well inside the client-to-server payload cap. */
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final int MAX_PER_PLAYER = 1;
    public static final int MAX_PER_SERVER = 4;
    private static final long IDLE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long TOTAL_TIMEOUT_NANOS = 120_000_000_000L;

    private static final Map<Integer, Upload> UPLOADS = new LinkedHashMap<>();
    private static int lastId;

    private static final class Upload {
        final int id;
        final UUID owner;
        final int requestId;
        final String sha;
        final BlobType type;
        final byte[] bytes;
        final long started;
        int received;
        long lastChunk;
        /** Whether the file is being checked and stored, which no chunk or timeout may interrupt. */
        boolean committing;

        Upload(int id, UUID owner, int requestId, String sha, BlobType type, int size, long now) {
            this.id = id;
            this.owner = owner;
            this.requestId = requestId;
            this.sha = sha;
            this.type = type;
            this.bytes = new byte[size];
            this.started = now;
            this.lastChunk = now;
        }
    }

    private WorldUploads() {
    }

    /**
     * Starts an upload, or answers why not, or that the server already has the file. Only
     * an upload that starts counts towards the player's upload limits, so asking about a
     * file the server already stores costs nothing.
     */
    public static void begin(ServerPlayer player, int requestId, @Nullable BlobType type, int size, String sha) {
        UUID owner = player.getUUID();
        WorldContentStatus refused = WorldCollections.checkUpload(sha, type, size);
        if (refused != null) {
            reply(player, requestId, 0, sha, refused);
            return;
        }
        if (countOwnedBy(owner) >= MAX_PER_PLAYER || UPLOADS.size() >= MAX_PER_SERVER) {
            reply(player, requestId, 0, sha, WorldContentStatus.of(Result.BUSY, "Another upload is in progress"));
            return;
        }
        if (!WorldRateLimits.tryUploadBegin(owner, size)) {
            reply(player, requestId, 0, sha, WorldContentStatus.of(Result.BUSY, "At most "
                    + WorldContentLimits.UPLOAD_BEGINS_PER_MINUTE + " files or "
                    + WorldContentLimits.UPLOAD_BYTES_PER_MINUTE / (1024 * 1024) + " MiB a minute; wait a moment"));
            return;
        }
        int id = nextId();
        UPLOADS.put(id, new Upload(id, owner, requestId, sha, type, size, System.nanoTime()));
        reply(player, requestId, id, sha, WorldContentStatus.of(Result.READY));
    }

    /** Adds the next bytes of the player's upload. */
    public static void chunk(ServerPlayer player, int uploadId, int offset, byte[] data) {
        Upload upload = owned(player, uploadId);
        if (upload == null) {
            return;
        }
        if (upload.committing) {
            BlockPopsMod.logDebug("Ignoring a chunk of World collection upload {} sent after its commit", uploadId);
            return;
        }
        if (offset != upload.received) {
            abort(player, upload, WorldContentStatus.of(Result.INVALID, "Upload parts arrived out of order"));
            return;
        }
        if (data.length == 0 || data.length > upload.bytes.length - upload.received) {
            abort(player, upload, WorldContentStatus.of(Result.TOO_LARGE, "The upload sent more bytes than it declared"));
            return;
        }
        System.arraycopy(data, 0, upload.bytes, upload.received, data.length);
        upload.received += data.length;
        upload.lastChunk = System.nanoTime();
    }

    /** Checks and stores the player's complete upload, answering STORED or why not. */
    public static void commit(MinecraftServer server, ServerPlayer player, int uploadId) {
        Upload upload = owned(player, uploadId);
        if (upload == null || upload.committing) {
            return;
        }
        if (upload.received != upload.bytes.length) {
            abort(player, upload, WorldContentStatus.of(Result.INVALID, "The upload is incomplete"));
            return;
        }
        upload.committing = true;
        WorldCollections.storeBlob(server, upload.sha, upload.type, upload.bytes, upload.owner, status -> {
            UPLOADS.remove(upload.id, upload);
            ServerPlayer online = server.getPlayerList().getPlayer(upload.owner);
            // A player that left or joined again since has forgotten this upload. Respawning
            // makes another player object on the same connection, which still waits.
            if (online != null && online.connection == player.connection) {
                reply(online, upload.requestId, upload.id, upload.sha, status);
            }
        });
    }

    /** Answers a player without permission, dropping the upload it began while it still had it. */
    public static void refuse(ServerPlayer player, int uploadId) {
        Upload upload = UPLOADS.get(uploadId);
        if (upload != null && upload.owner.equals(player.getUUID())) {
            if (!upload.committing) {
                UPLOADS.remove(uploadId);
            }
            reply(player, upload.requestId, uploadId, upload.sha, WorldContentStatus.of(Result.NOT_ALLOWED));
        } else {
            reply(player, 0, uploadId, "", WorldContentStatus.of(Result.NOT_ALLOWED));
        }
    }

    /** Drops the uploads that stalled or ran too long, telling their players. Server tick. */
    public static void sweep(MinecraftServer server) {
        if (UPLOADS.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        List<Upload> expired = new ArrayList<>();
        for (Iterator<Upload> iterator = UPLOADS.values().iterator(); iterator.hasNext(); ) {
            Upload upload = iterator.next();
            if (!upload.committing && (now - upload.lastChunk > IDLE_TIMEOUT_NANOS
                    || now - upload.started > TOTAL_TIMEOUT_NANOS)) {
                iterator.remove();
                expired.add(upload);
            }
        }
        for (Upload upload : expired) {
            ServerPlayer player = server.getPlayerList().getPlayer(upload.owner);
            if (player != null) {
                reply(player, upload.requestId, upload.id, upload.sha, WorldContentStatus.of(Result.TIMED_OUT));
            }
        }
    }

    /** Drops every upload of a player that left. */
    public static void dropPlayer(UUID player) {
        UPLOADS.values().removeIf(upload -> upload.owner.equals(player));
    }

    public static void clear() {
        UPLOADS.clear();
    }

    @Nullable
    private static Upload owned(ServerPlayer player, int uploadId) {
        Upload upload = UPLOADS.get(uploadId);
        if (upload == null) {
            // Most often one that timed out or was aborted, which the player was told about
            BlockPopsMod.logDebug("Ignoring World collection upload {} that is not in progress", uploadId);
            return null;
        }
        if (!upload.owner.equals(player.getUUID())) {
            BlockPopsMod.LOGGER.warn("Player {} sent to a World collection upload another player began",
                    player.getName().getString());
            return null;
        }
        return upload;
    }

    private static void abort(ServerPlayer player, Upload upload, WorldContentStatus status) {
        UPLOADS.remove(upload.id);
        reply(player, upload.requestId, upload.id, upload.sha, status);
    }

    private static int countOwnedBy(UUID owner) {
        int count = 0;
        for (Upload upload : UPLOADS.values()) {
            if (upload.owner.equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /** The next upload id: positive, and not one in progress. */
    private static int nextId() {
        do {
            lastId = lastId == Integer.MAX_VALUE ? 1 : lastId + 1;
        } while (UPLOADS.containsKey(lastId));
        return lastId;
    }

    private static void reply(ServerPlayer player, int requestId, int uploadId, String sha, WorldContentStatus status) {
        WorldCollectionStatusPacket.send(player, requestId, status, uploadId, sha);
    }
}
