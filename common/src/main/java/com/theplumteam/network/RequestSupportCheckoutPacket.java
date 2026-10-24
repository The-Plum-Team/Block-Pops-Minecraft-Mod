package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.SupportCheckout;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet sent by the machine menu's supporter button. The server asks the
 * supporter service for a checkout made for the sender and answers with an
 * OpenSupportCheckoutPacket.
 */
public class RequestSupportCheckoutPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "request_support_checkout");

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
                SupportCheckout.request(player, true);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
