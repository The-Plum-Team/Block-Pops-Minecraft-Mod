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
 * Client-to-server packet that ends an upload once every byte is sent. The server checks
 * the file against its sha and its type, stores it, and answers STORED or why not.
 */
public class WorldUploadCommitPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldUploadCommitPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_upload_commit");

    private final int uploadId;

    public WorldUploadCommitPacket(int uploadId) {
        this.uploadId = uploadId;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(uploadId);
        return buffer;
    }

    public static WorldUploadCommitPacket decode(FriendlyByteBuf buffer) {
        return new WorldUploadCommitPacket(buffer.readVarInt());
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldUploadCommitPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!ServerLevels.hasCommandLevel(player, 2)) {
                LOGGER.warn("Player {} tried to store a World collection file without permission",
                        player.getName().getString());
                WorldUploads.refuse(player, packet.uploadId);
                return;
            }
            WorldUploads.commit(ServerLevels.serverOf(player), player, packet.uploadId);
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
