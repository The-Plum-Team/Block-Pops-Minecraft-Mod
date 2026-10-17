package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldUploads;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-to-server packet with the next bytes of an upload the server accepted. Chunks
 * arrive in order, and the server answers only when one is refused, which aborts the
 * upload.
 */
public class WorldUploadChunkPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldUploadChunkPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_upload_chunk");

    private final int uploadId;
    private final int offset;
    private final byte[] data;

    /**
     * @param offset where data starts in the file, which is how many bytes were sent before
     * @param data   at most {@link WorldUploads#CHUNK_BYTES} bytes
     */
    public WorldUploadChunkPacket(int uploadId, int offset, byte[] data) {
        this.uploadId = uploadId;
        this.offset = offset;
        this.data = data;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(uploadId);
        buffer.writeVarInt(offset);
        buffer.writeByteArray(data);
        return buffer;
    }

    public static WorldUploadChunkPacket decode(FriendlyByteBuf buffer) {
        int uploadId = buffer.readVarInt();
        int offset = buffer.readVarInt();
        return new WorldUploadChunkPacket(uploadId, offset, buffer.readByteArray(WorldUploads.CHUNK_BYTES));
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldUploadChunkPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!ServerLevels.hasCommandLevel(player, 2)) {
                LOGGER.warn("Player {} tried to upload a World collection file without permission",
                        player.getName().getString());
                WorldUploads.refuse(player, packet.uploadId);
                return;
            }
            WorldUploads.chunk(player, packet.uploadId, packet.offset, packet.data);
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
