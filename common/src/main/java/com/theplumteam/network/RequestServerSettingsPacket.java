package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet that asks for the server settings shown in the Settings screen.
 * Any player may ask; the server answers the sender with a SyncServerSettingsPacket.
 */
public class RequestServerSettingsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "request_server_settings");

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        // Write a dummy byte - empty packets can cause issues with Architectury networking
        buffer.writeBoolean(true);
        return buffer;
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        // Read the dummy byte
        buf.readBoolean();

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                SyncServerSettingsPacket.sendToPlayer(player);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
