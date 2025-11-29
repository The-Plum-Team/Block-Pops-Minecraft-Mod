// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\network\OpenFavoriteColorScreenPacket.java ==========
package com.theplumteam.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Server-to-client packet that triggers the favorite color selection UI.
 * Sent when a player joins a world for the first time without having chosen their favorite color.
 */
public class OpenFavoriteColorScreenPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenFavoriteColorScreenPacket.class);

    public OpenFavoriteColorScreenPacket() {
        // No data needed - the packet's arrival is the trigger
    }

    public static void encode(OpenFavoriteColorScreenPacket packet, FriendlyByteBuf buffer) {
        // No data to encode
    }

    public static OpenFavoriteColorScreenPacket decode(FriendlyByteBuf buffer) {
        return new OpenFavoriteColorScreenPacket();
    }

    public static void handle(OpenFavoriteColorScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Use DistExecutor to safely run client-side code only on the client
            // This prevents class loading errors on the dedicated server
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHandler::handle);
        });
        context.setPacketHandled(true);
    }

    // Inner class to isolate client-side logic and imports.
    // This class will not be loaded on the server, preventing the crash.
    private static class ClientHandler {
        public static void handle() {
            LOGGER.info("Opening favorite color selection screen");
            // Use fully qualified names or ensure imports are only used within this isolated class
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.theplumteam.client.gui.FavoriteColorSelectionScreen());
        }
    }
}