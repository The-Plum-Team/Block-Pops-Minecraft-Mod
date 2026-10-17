package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.BlobType;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client packet with part of a World collection file the client asked for.
 * The parts of one file arrive in order; the client checks the whole file against its
 * sha and its type before loading it.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class WorldAssetChunkPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_asset_chunk");
    /** The most file bytes one packet carries. */
    public static final int MAX_DATA = 256 * 1024;

    private final String sha;
    private final BlobType type;
    private final int total;
    private final int offset;
    private final byte[] data;

    /**
     * @param total  the size of the whole file
     * @param offset where data starts in the file
     */
    public WorldAssetChunkPacket(String sha, BlobType type, int total, int offset, byte[] data) {
        this.sha = sha;
        this.type = type;
        this.total = total;
        this.offset = offset;
        this.data = data;
    }

    public String sha() {
        return sha;
    }

    public BlobType type() {
        return type;
    }

    public int total() {
        return total;
    }

    public int offset() {
        return offset;
    }

    public byte[] data() {
        return data;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(sha, WorldPacketFields.MAX_SHA);
        buffer.writeByte(type.id());
        buffer.writeVarInt(total);
        buffer.writeVarInt(offset);
        buffer.writeByteArray(data);
        return buffer;
    }

    public static WorldAssetChunkPacket decode(FriendlyByteBuf buffer) {
        String sha = WorldPacketFields.readSha(buffer);
        BlobType type = BlobType.byId(buffer.readUnsignedByte());
        if (type == null) {
            throw new IllegalArgumentException("World collection file of unknown type");
        }
        int total = WorldPacketFields.bounded(buffer.readVarInt(), 1, type.maxBytes(), "file size");
        int offset = WorldPacketFields.bounded(buffer.readVarInt(), 0, total - 1, "file offset");
        byte[] data = buffer.readByteArray(MAX_DATA);
        if (data.length == 0 || data.length > total - offset) {
            throw new IllegalArgumentException("World collection file part does not fit its file");
        }
        return new WorldAssetChunkPacket(sha, type, total, offset, data);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldAssetChunkPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.worldcontent.ClientWorldCollections.onAssetChunk(packet));
    }

    public static boolean canReceive(ServerPlayer player) {
        return WorldPacketFields.canReceive(player, ID);
    }

    public void sendToPlayer(ServerPlayer player) {
        if (canReceive(player)) {
            PacketNetworking.sendToPlayer(player, ID, encode());
        }
    }
}
