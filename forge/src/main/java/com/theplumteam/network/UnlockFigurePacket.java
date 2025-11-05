package com.theplumteam.network;

import com.theplumteam.client.discovery.ClientDiscoveryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Server-to-client packet that notifies the client of a newly discovered figure.
 * Sent when a player receives a new figure from the claw machine for the first time.
 */
public class UnlockFigurePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnlockFigurePacket.class);

    private final String figureId;
    private final String figureName;
    @Nullable
    private final String skinSnapshot; // New: Holds the Base64 texture property

    public UnlockFigurePacket(String figureId, String figureName) {
        this(figureId, figureName, null);
    }

    public UnlockFigurePacket(String figureId, String figureName, @Nullable String skinSnapshot) {
        this.figureId = figureId;
        this.figureName = figureName;
        this.skinSnapshot = skinSnapshot;
    }

    public static void encode(UnlockFigurePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.figureId);
        buffer.writeUtf(packet.figureName);
        // Encode the nullable skin snapshot
        buffer.writeBoolean(packet.skinSnapshot != null);
        if (packet.skinSnapshot != null) {
            buffer.writeUtf(packet.skinSnapshot);
        }
    }

    public static UnlockFigurePacket decode(FriendlyByteBuf buffer) {
        String figureId = buffer.readUtf();
        String figureName = buffer.readUtf();
        String skinSnapshot = null;
        if (buffer.readBoolean()) {
            skinSnapshot = buffer.readUtf();
        }
        return new UnlockFigurePacket(figureId, figureName, skinSnapshot);
    }

    public static void handle(UnlockFigurePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
            LOGGER.info("Unlocked new figure: {} ({})", packet.figureName, packet.figureId);
            ClientDiscoveryManager.unlock(packet.figureId);

            // If a skin snapshot was sent, save it on the client
            if (packet.skinSnapshot != null) {
                ClientDiscoveryManager.saveFigureSkin(packet.figureId, packet.skinSnapshot);
                LOGGER.info("Saved skin snapshot for unlocked figure: {}", packet.figureId);
            }

            // TODO: Optional - Play sound effect and/or show toast notification
            // Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            //     SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F
            // ));
        });
        context.setPacketHandled(true);
    }

    public String getFigureId() {
        return figureId;
    }

    public String getFigureName() {
        return figureName;
    }
}
