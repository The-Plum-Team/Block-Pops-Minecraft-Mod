package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.ModelBounds;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentLimits;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet with one part of the World collections: every record the
 * server registered, as its exact UTF-8 bytes, and the table of the files they name.
 *
 * <p>A sync replaces everything the client holds. It may be split into up to
 * {@link #MAX_PARTS} parts that share a sync id; the client keeps the parts of the
 * newest id and applies them only once all have arrived. The session nonce changes with
 * every connection, so a client that is moved to another server without disconnecting
 * still forgets the previous one.
 *
 * <p>World collections travel only here and in {@link WorldAssetChunkPacket}, never in
 * {@link SyncDynamicCollectionsPacket}.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class SyncWorldCollectionsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_world_collections");
    public static final int MAX_PARTS = 8;
    /** The most bytes one part may take, well inside the server-to-client payload cap. */
    public static final int MAX_PART_BYTES = 512 * 1024;
    /** The most bytes all parts of one sync may take. */
    public static final int MAX_SYNC_BYTES = 4 * 1024 * 1024;
    /** Set in a file's flags when the server has lost it, so the client does not ask for it. */
    public static final int FLAG_MISSING = 0x80;
    /** Room for a part's fixed fields, and the most one file's table entry can take. */
    private static final int HEADER_BYTES = 64;
    private static final int BLOB_ENTRY_BYTES = 128;
    private static final int RECORD_OVERHEAD_BYTES = 5;

    /**
     * One entry of the file table.
     *
     * @param missing whether the server has lost the file and waits for it to be uploaded again
     */
    public record Blob(String sha, BlobInfo info, boolean missing) {
    }

    private final int syncId;
    private final long sessionNonce;
    private final int part;
    private final int parts;
    private final int quotaUsedKiB;
    private final int quotaMaxKiB;
    private final List<byte[]> records;
    private final List<Blob> blobs;

    public SyncWorldCollectionsPacket(int syncId, long sessionNonce, int part, int parts, int quotaUsedKiB,
                                      int quotaMaxKiB, List<byte[]> records, List<Blob> blobs) {
        this.syncId = syncId;
        this.sessionNonce = sessionNonce;
        this.part = part;
        this.parts = parts;
        this.quotaUsedKiB = quotaUsedKiB;
        this.quotaMaxKiB = quotaMaxKiB;
        this.records = List.copyOf(records);
        this.blobs = List.copyOf(blobs);
    }

    public int syncId() {
        return syncId;
    }

    public long sessionNonce() {
        return sessionNonce;
    }

    /** This part's index, from 0. */
    public int part() {
        return part;
    }

    public int parts() {
        return parts;
    }

    public int quotaUsedKiB() {
        return quotaUsedKiB;
    }

    public int quotaMaxKiB() {
        return quotaMaxKiB;
    }

    /** This part's records, each the UTF-8 bytes of one record's JSON. */
    public List<byte[]> records() {
        return records;
    }

    public List<Blob> blobs() {
        return blobs;
    }

    /** About how many bytes a sync of these records and files takes on the wire, over all its parts. */
    public static long estimatedBytes(List<byte[]> records, List<Blob> blobs) {
        long bytes = HEADER_BYTES + (long) blobs.size() * BLOB_ENTRY_BYTES;
        for (byte[] record : records) {
            bytes += record.length + RECORD_OVERHEAD_BYTES;
        }
        return bytes;
    }

    /**
     * Splits a sync into parts of at most {@link #MAX_PART_BYTES}: records first, then the
     * file table, each part filled before the next starts.
     *
     * @return the parts in order, or an empty list when the sync is larger than a sync may be
     */
    public static List<SyncWorldCollectionsPacket> split(int syncId, long sessionNonce, int quotaUsedKiB,
                                                         int quotaMaxKiB, List<byte[]> records, List<Blob> blobs) {
        if (records.size() > WorldContentLimits.MAX_COLLECTIONS || blobs.size() > WorldContentLimits.MAX_BLOBS
                || estimatedBytes(records, blobs) > MAX_SYNC_BYTES) {
            return List.of();
        }
        List<List<byte[]>> recordParts = new ArrayList<>();
        List<List<Blob>> blobParts = new ArrayList<>();
        recordParts.add(new ArrayList<>());
        blobParts.add(new ArrayList<>());
        int used = HEADER_BYTES;
        for (byte[] record : records) {
            int bytes = record.length + RECORD_OVERHEAD_BYTES;
            if (used + bytes > MAX_PART_BYTES) {
                recordParts.add(new ArrayList<>());
                blobParts.add(new ArrayList<>());
                used = HEADER_BYTES;
            }
            recordParts.get(recordParts.size() - 1).add(record);
            used += bytes;
        }
        for (Blob blob : blobs) {
            if (used + BLOB_ENTRY_BYTES > MAX_PART_BYTES) {
                recordParts.add(new ArrayList<>());
                blobParts.add(new ArrayList<>());
                used = HEADER_BYTES;
            }
            blobParts.get(blobParts.size() - 1).add(blob);
            used += BLOB_ENTRY_BYTES;
        }
        int parts = recordParts.size();
        if (parts > MAX_PARTS) {
            return List.of();
        }
        List<SyncWorldCollectionsPacket> packets = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            packets.add(new SyncWorldCollectionsPacket(syncId, sessionNonce, i, parts, quotaUsedKiB, quotaMaxKiB,
                    recordParts.get(i), blobParts.get(i)));
        }
        return packets;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(syncId);
        buffer.writeLong(sessionNonce);
        buffer.writeVarInt(part);
        buffer.writeVarInt(parts);
        buffer.writeVarInt(quotaUsedKiB);
        buffer.writeVarInt(quotaMaxKiB);
        buffer.writeVarInt(records.size());
        for (byte[] record : records) {
            buffer.writeByteArray(record);
        }
        buffer.writeVarInt(blobs.size());
        for (Blob blob : blobs) {
            BlobInfo info = blob.info();
            buffer.writeUtf(blob.sha(), WorldPacketFields.MAX_SHA);
            buffer.writeByte(info.type().id());
            buffer.writeVarInt(info.size());
            buffer.writeVarInt(info.width());
            buffer.writeVarInt(info.height());
            buffer.writeVarInt(info.texWidth());
            buffer.writeVarInt(info.texHeight());
            buffer.writeVarInt(info.cubes());
            buffer.writeVarInt(info.keyframes());
            buffer.writeByte(info.poses() | (blob.missing() ? FLAG_MISSING : 0));
            if (info.type() == BlobType.GEO) {
                ModelBounds bounds = info.bounds();
                buffer.writeShort(bounds.minX());
                buffer.writeShort(bounds.minY());
                buffer.writeShort(bounds.minZ());
                buffer.writeShort(bounds.maxX());
                buffer.writeShort(bounds.maxY());
                buffer.writeShort(bounds.maxZ());
            }
        }
        return buffer;
    }

    public static SyncWorldCollectionsPacket decode(FriendlyByteBuf buffer) {
        int syncId = buffer.readVarInt();
        long sessionNonce = buffer.readLong();
        int part = buffer.readVarInt();
        int parts = WorldPacketFields.bounded(buffer.readVarInt(), 1, MAX_PARTS, "part count");
        WorldPacketFields.bounded(part, 0, parts - 1, "part");
        int quotaUsedKiB = WorldPacketFields.bounded(buffer.readVarInt(), 0, Integer.MAX_VALUE, "quota");
        int quotaMaxKiB = WorldPacketFields.bounded(buffer.readVarInt(), 0, Integer.MAX_VALUE, "quota");
        int recordCount = WorldPacketFields.bounded(buffer.readVarInt(), 0, WorldContentLimits.MAX_COLLECTIONS,
                "record count");
        List<byte[]> records = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            records.add(buffer.readByteArray(WorldContentLimits.RECORD_BYTES));
        }
        int blobCount = WorldPacketFields.bounded(buffer.readVarInt(), 0, WorldContentLimits.MAX_BLOBS, "file count");
        List<Blob> blobs = new ArrayList<>(blobCount);
        for (int i = 0; i < blobCount; i++) {
            String sha = WorldPacketFields.readSha(buffer);
            BlobType type = BlobType.byId(buffer.readUnsignedByte());
            int size = buffer.readVarInt();
            int width = buffer.readVarInt();
            int height = buffer.readVarInt();
            int texWidth = buffer.readVarInt();
            int texHeight = buffer.readVarInt();
            int cubes = buffer.readVarInt();
            int keyframes = buffer.readVarInt();
            int flags = buffer.readUnsignedByte();
            // Only a model's entry carries bounds, as six shorts
            short[] bounds = new short[6];
            if (type == BlobType.GEO) {
                for (int axis = 0; axis < bounds.length; axis++) {
                    bounds[axis] = buffer.readShort();
                }
            }
            try {
                BlobInfo info = BlobInfo.checked(type, size, width, height, texWidth, texHeight, cubes, keyframes,
                        flags & ~FLAG_MISSING, ModelBounds.checked(bounds[0], bounds[1], bounds[2], bounds[3],
                                bounds[4], bounds[5]));
                blobs.add(new Blob(sha, info, (flags & FLAG_MISSING) != 0));
            } catch (WorldContentException e) {
                throw new IllegalArgumentException("World collection file table entry refused: " + e.getMessage());
            }
        }
        return new SyncWorldCollectionsPacket(syncId, sessionNonce, part, parts, quotaUsedKiB, quotaMaxKiB, records,
                blobs);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncWorldCollectionsPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.worldcontent.ClientWorldCollections.onSyncPart(packet));
    }

    /** Whether the player's client can receive World collections, so they are kept from it otherwise. */
    public static boolean canReceive(ServerPlayer player) {
        return WorldPacketFields.canReceive(player, ID);
    }

    public void sendToPlayer(ServerPlayer player) {
        if (canReceive(player)) {
            PacketNetworking.sendToPlayer(player, ID, encode());
        }
    }
}
