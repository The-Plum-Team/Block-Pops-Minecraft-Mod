package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that triggers the favorite color selection UI.
 * Sent when a player joins a world for the first time without having chosen their favorite color.
 */
public class OpenFavoriteColorScreenPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenFavoriteColorScreenPacket.class);
    public static final ResourceLocation ID = new ResourceLocation(BlockPopsMod.MOD_ID, "open_favorite_color_screen");

    public OpenFavoriteColorScreenPacket() {
        // No data needed - the packet's arrival is the trigger
    }

    /**
     * Encode the packet to a buffer
     */
    public FriendlyByteBuf encode() {
        // No data to encode
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /**
     * Decode a packet from a buffer
     */
    public static OpenFavoriteColorScreenPacket decode(FriendlyByteBuf buffer) {
        return new OpenFavoriteColorScreenPacket();
    }

    /**
     * Handle the packet on the client side
     */
    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        OpenFavoriteColorScreenPacket packet = decode(buf);

        context.queue(() -> {
            // Use EnvExecutor to safely run client-side code only on the client
            EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
                LOGGER.info("Opening favorite color selection screen");
                Minecraft.getInstance().setScreen(new FavoriteColorSelectionScreen());
            });
        });
    }

    /**
     * Send this packet to a specific player
     */
    public static void sendToPlayer(ServerPlayer player) {
        OpenFavoriteColorScreenPacket packet = new OpenFavoriteColorScreenPacket();
        NetworkManager.sendToPlayer(player, ID, packet.encode());
    }
}
