package com.theplumteam.network;

import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
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
            // This runs on the client thread
            LOGGER.info("Opening favorite color selection screen");
            Minecraft.getInstance().setScreen(new FavoriteColorSelectionScreen());
        });
        context.setPacketHandled(true);
    }
}
