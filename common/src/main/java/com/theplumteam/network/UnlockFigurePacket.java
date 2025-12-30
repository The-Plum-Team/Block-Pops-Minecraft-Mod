package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that notifies the client of a newly discovered figure.
 * Sent when a player receives a new figure from the claw machine for the first time.
 */
public class UnlockFigurePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnlockFigurePacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "unlock_figure");

    private final String figureId;
    private final String figureName;
    @Nullable
    private final String skinSnapshot;

    public UnlockFigurePacket(String figureId, String figureName) {
        this(figureId, figureName, null);
    }

    public UnlockFigurePacket(String figureId, String figureName, @Nullable String skinSnapshot) {
        this.figureId = figureId;
        this.figureName = figureName;
        this.skinSnapshot = skinSnapshot;
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeUtf(figureId);
        buffer.writeUtf(figureName);
        buffer.writeBoolean(skinSnapshot != null);
        if (skinSnapshot != null) {
            buffer.writeUtf(skinSnapshot);
        }
        return buffer;
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

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UnlockFigurePacket packet = decode(buf);

        context.queue(() -> {
            LOGGER.info("Unlocked new figure: {} ({})", packet.figureName, packet.figureId);
            ClientDiscoveryManager.unlock(packet.figureId);

            if (packet.skinSnapshot != null) {
                ClientDiscoveryManager.saveFigureSkin(packet.figureId, packet.skinSnapshot);
                LOGGER.info("Saved skin snapshot for unlocked figure: {}", packet.figureId);
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, String figureId, String figureName, @Nullable String skinSnapshot) {
        UnlockFigurePacket packet = new UnlockFigurePacket(figureId, figureName, skinSnapshot);
        NetworkManager.sendToPlayer(player, ID, packet.encode());
    }

    public String getFigureId() {
        return figureId;
    }

    public String getFigureName() {
        return figureName;
    }

    @Nullable
    public String getSkinSnapshot() {
        return skinSnapshot;
    }
}
