package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-to-client packet that syncs the player's complete set of discovered figures.
 * Sent when a player logs in to ensure their collection is up-to-date.
 */
public class SyncDiscoveryDataPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncDiscoveryDataPacket.class);
    public static final ResourceLocation ID = new ResourceLocation(BlockPopsMod.MOD_ID, "sync_discovery_data");

    private final Set<String> discoveredFigures;
    private final Map<String, String> figureSkins;
    private final Map<String, String> figureQuickSkins;

    public SyncDiscoveryDataPacket(Set<String> discoveredFigures, Map<String, String> figureSkins, Map<String, String> figureQuickSkins) {
        this.discoveredFigures = new HashSet<>(discoveredFigures);
        this.figureSkins = new HashMap<>(figureSkins);
        this.figureQuickSkins = new HashMap<>(figureQuickSkins);
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(discoveredFigures.size());
        for (String figureId : discoveredFigures) {
            buffer.writeUtf(figureId);
        }

        buffer.writeInt(figureSkins.size());
        for (Map.Entry<String, String> entry : figureSkins.entrySet()) {
            buffer.writeUtf(entry.getKey());
            buffer.writeUtf(entry.getValue());
        }

        buffer.writeInt(figureQuickSkins.size());
        for (Map.Entry<String, String> entry : figureQuickSkins.entrySet()) {
            buffer.writeUtf(entry.getKey());
            buffer.writeUtf(entry.getValue());
        }
        return buffer;
    }

    public static SyncDiscoveryDataPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Set<String> discoveredFigures = new HashSet<>();
        for (int i = 0; i < size; i++) {
            discoveredFigures.add(buffer.readUtf());
        }

        int skinsSize = buffer.readInt();
        Map<String, String> figureSkins = new HashMap<>();
        for (int i = 0; i < skinsSize; i++) {
            String figureId = buffer.readUtf();
            String skinUrl = buffer.readUtf();
            figureSkins.put(figureId, skinUrl);
        }

        int qsSize = buffer.readInt();
        Map<String, String> figureQuickSkins = new HashMap<>();
        for (int i = 0; i < qsSize; i++) {
            String figureId = buffer.readUtf();
            String quickSkinId = buffer.readUtf();
            figureQuickSkins.put(figureId, quickSkinId);
        }

        return new SyncDiscoveryDataPacket(discoveredFigures, figureSkins, figureQuickSkins);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncDiscoveryDataPacket packet = decode(buf);

        context.queue(() -> {
            BlockPopsMod.logDebug("Received discovery data sync: {} figures discovered, {} skins, {} quick skins",
                    packet.discoveredFigures.size(), packet.figureSkins.size(), packet.figureQuickSkins.size());
            ClientDiscoveryManager.setData(packet.discoveredFigures, packet.figureSkins, packet.figureQuickSkins);
        });
    }

    /**
     * Legacy method for backward compatibility (without Quick Skins)
     */
    public static void sendToPlayer(ServerPlayer player, Set<String> discoveredFigures, Map<String, String> figureSkins) {
        sendToPlayer(player, discoveredFigures, figureSkins, new HashMap<>());
    }

    public static void sendToPlayer(ServerPlayer player, Set<String> discoveredFigures, Map<String, String> figureSkins, Map<String, String> figureQuickSkins) {
        SyncDiscoveryDataPacket packet = new SyncDiscoveryDataPacket(discoveredFigures, figureSkins, figureQuickSkins);
        NetworkManager.sendToPlayer(player, ID, packet.encode());
    }

    public Set<String> getDiscoveredFigures() {
        return discoveredFigures;
    }

    public Map<String, String> getFigureSkins() {
        return figureSkins;
    }

    public Map<String, String> getFigureQuickSkins() {
        return figureQuickSkins;
    }
}
