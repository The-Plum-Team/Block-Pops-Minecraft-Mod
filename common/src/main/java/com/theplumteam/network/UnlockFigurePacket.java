package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
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
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "unlock_figure");

    private final String figureId;
    private final String figureName;
    @Nullable
    private final String skinSnapshot;
    @Nullable
    private final String quickSkinId;

    public UnlockFigurePacket(String figureId, String figureName) {
        this(figureId, figureName, null, null);
    }

    public UnlockFigurePacket(String figureId, String figureName, @Nullable String skinSnapshot) {
        this(figureId, figureName, skinSnapshot, null);
    }

    public UnlockFigurePacket(String figureId, String figureName, @Nullable String skinSnapshot, @Nullable String quickSkinId) {
        this.figureId = figureId;
        this.figureName = figureName;
        this.skinSnapshot = skinSnapshot;
        this.quickSkinId = quickSkinId;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(figureId);
        buffer.writeUtf(figureName);
        buffer.writeBoolean(skinSnapshot != null);
        if (skinSnapshot != null) {
            buffer.writeUtf(skinSnapshot);
        }
        buffer.writeBoolean(quickSkinId != null);
        if (quickSkinId != null) {
            buffer.writeUtf(quickSkinId);
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
        String quickSkinId = null;
        if (buffer.readBoolean()) {
            quickSkinId = buffer.readUtf();
        }
        return new UnlockFigurePacket(figureId, figureName, skinSnapshot, quickSkinId);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UnlockFigurePacket packet = decode(buf);

        context.queue(() -> {
            BlockPopsMod.logDebug("Unlocked new figure: {} ({})", packet.figureName, packet.figureId);
            ClientDiscoveryManager.unlock(packet.figureId);

            if (packet.skinSnapshot != null) {
                ClientDiscoveryManager.saveFigureSkin(packet.figureId, packet.skinSnapshot);
                BlockPopsMod.logDebug("Saved skin snapshot for unlocked figure: {}", packet.figureId);
            }
            if (packet.quickSkinId != null) {
                ClientDiscoveryManager.saveFigureQuickSkin(packet.figureId, packet.quickSkinId);
                BlockPopsMod.logDebug("Saved Quick Skin ID for unlocked figure: {}", packet.figureId);
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, String figureId, String figureName, @Nullable String skinSnapshot) {
        sendToPlayer(player, figureId, figureName, skinSnapshot, null);
    }

    public static void sendToPlayer(ServerPlayer player, String figureId, String figureName, @Nullable String skinSnapshot, @Nullable String quickSkinId) {
        UnlockFigurePacket packet = new UnlockFigurePacket(figureId, figureName, skinSnapshot, quickSkinId);
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
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

    @Nullable
    public String getQuickSkinId() {
        return quickSkinId;
    }
}
