package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldAssetSender;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet that tells how many bytes of World collection files the
 * client has received on this connection, so the server never has more than a window
 * of them sent and unacknowledged.
 */
public class WorldAssetAckPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_asset_ack");

    private final int bytesReceived;

    /** @param bytesReceived every file byte received on this connection so far */
    public WorldAssetAckPacket(int bytesReceived) {
        this.bytesReceived = bytesReceived;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(bytesReceived);
        return buffer;
    }

    public static WorldAssetAckPacket decode(FriendlyByteBuf buffer) {
        return new WorldAssetAckPacket(WorldPacketFields.bounded(buffer.readVarInt(), 0, Integer.MAX_VALUE,
                "acknowledged bytes"));
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldAssetAckPacket packet = decode(buf);
        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                WorldAssetSender.acknowledge(player, packet.bytesReceived);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
