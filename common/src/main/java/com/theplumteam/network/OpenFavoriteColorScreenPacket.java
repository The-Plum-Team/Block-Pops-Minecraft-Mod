package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that triggers the favorite color selection UI.
 * Sent when a player joins a world for the first time without having chosen their favorite color.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class OpenFavoriteColorScreenPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenFavoriteColorScreenPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "open_favorite_color_screen");

    public OpenFavoriteColorScreenPacket() {
        // No data needed - the packet's arrival is the trigger
    }

    /**
     * Encode the packet to a buffer
     */
    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        // Write a dummy byte - empty packets can cause issues with Architectury networking
        buffer.writeBoolean(true);
        return buffer;
    }

    /**
     * Decode a packet from a buffer
     */
    public static OpenFavoriteColorScreenPacket decode(FriendlyByteBuf buffer) {
        // Read the dummy byte
        buffer.readBoolean();
        return new OpenFavoriteColorScreenPacket();
    }

    /**
     * Handle the packet on the client side
     */
    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        // Just decode the packet to advance the buffer, even if empty
        OpenFavoriteColorScreenPacket packet = decode(buf);

        context.queue(() -> {
            // Use EnvExecutor to safely run client-side code only on the client
            // We use a Supplier<Runnable> (() -> () -> ...) so the inner class (lambda)
            // containing the client reference is only loaded when executed on the client.
            EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
                BlockPopsMod.logDebug("Opening favorite color selection screen");
                // Use fully qualified name to avoid importing ClientHelpers,
                // which would trigger class loading of client classes on the server.
                com.theplumteam.client.ClientHelpers.openFavoriteColorScreen();
            });
        });
    }

    /**
     * Send this packet to a specific player
     */
    public static void sendToPlayer(ServerPlayer player) {
        OpenFavoriteColorScreenPacket packet = new OpenFavoriteColorScreenPacket();
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
    }
}
