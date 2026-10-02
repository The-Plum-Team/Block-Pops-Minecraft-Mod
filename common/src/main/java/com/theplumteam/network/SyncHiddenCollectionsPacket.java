package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet that syncs the collections the server hides from the collection list.
 * Sent when a player joins, and to every player when an operator changes the list.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class SyncHiddenCollectionsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_hidden_collections");

    private final List<String> collectionIds;

    public SyncHiddenCollectionsPacket(List<String> collectionIds) {
        this.collectionIds = collectionIds;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(collectionIds.size());
        for (String collectionId : collectionIds) {
            buffer.writeUtf(collectionId);
        }
        return buffer;
    }

    public static SyncHiddenCollectionsPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        List<String> collectionIds = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            collectionIds.add(buffer.readUtf());
        }
        return new SyncHiddenCollectionsPacket(collectionIds);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncHiddenCollectionsPacket packet = decode(buf);

        context.queue(() -> {
            BlockPopsMod.logDebug("Received {} hidden collections from server", packet.collectionIds.size());
            // Use fully qualified name to avoid importing a class that reads the client connection
            com.theplumteam.client.ClientHiddenCollections.set(packet.collectionIds);
        });
    }

    /**
     * Send the server's hidden collections to a specific player
     */
    public static void sendToPlayer(ServerPlayer player) {
        //? if >=1.21 {
        /*// A client on an older build never registered this payload, and NeoForge refuses to send it
        if (!NetworkManager.canPlayerReceive(player, ID)) {
            return;
        }
        *///? }
        SyncHiddenCollectionsPacket packet = new SyncHiddenCollectionsPacket(
                ServerConfig.getInstance().getHiddenCollections());
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
    }

    public static void sendToAllPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(player);
        }
    }
}
