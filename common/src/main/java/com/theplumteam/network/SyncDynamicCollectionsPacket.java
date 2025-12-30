package com.theplumteam.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet that syncs dynamic collections (like World Players).
 * Sent when a player joins to ensure they can see dynamically generated collections.
 */
public class SyncDynamicCollectionsPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncDynamicCollectionsPacket.class);
    private static final Gson GSON = new Gson();
    public static final ResourceLocation ID = new ResourceLocation(BlockPopsMod.MOD_ID, "sync_dynamic_collections");

    private final List<String> collectionsJson;

    public SyncDynamicCollectionsPacket(List<FigureCollection> collections) {
        this.collectionsJson = new ArrayList<>();
        for (FigureCollection collection : collections) {
            JsonObject json = collection.toJson();
            this.collectionsJson.add(GSON.toJson(json));
        }
    }

    private SyncDynamicCollectionsPacket(ArrayList<String> collectionsJson) {
        this.collectionsJson = collectionsJson;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(collectionsJson.size());
        for (String json : collectionsJson) {
            buffer.writeUtf(json, 1048576); // 1MB limit for large collections
        }
        return buffer;
    }

    public static SyncDynamicCollectionsPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        ArrayList<String> collectionsJson = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            collectionsJson.add(buffer.readUtf(1048576));
        }
        return new SyncDynamicCollectionsPacket(collectionsJson);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncDynamicCollectionsPacket packet = decode(buf);

        context.queue(() -> {
            LOGGER.info("Received {} dynamic collections from server", packet.collectionsJson.size());

            for (String json : packet.collectionsJson) {
                try {
                    JsonObject jsonObject = GSON.fromJson(json, JsonObject.class);
                    FigureCollection collection = FigureCollection.fromJson(jsonObject);
                    CollectionRegistry.registerDynamicCollection(collection);
                    LOGGER.info("Registered dynamic collection: {} with {} figures",
                            collection.getName(), collection.getFigures().size());
                } catch (Exception e) {
                    LOGGER.error("Failed to deserialize dynamic collection", e);
                }
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, List<FigureCollection> collections) {
        SyncDynamicCollectionsPacket packet = new SyncDynamicCollectionsPacket(collections);
        NetworkManager.sendToPlayer(player, ID, packet.encode());
    }

    public static void sendToAllPlayers(net.minecraft.server.MinecraftServer server, List<FigureCollection> collections) {
        SyncDynamicCollectionsPacket packet = new SyncDynamicCollectionsPacket(collections);
        FriendlyByteBuf buf = packet.encode();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkManager.sendToPlayer(player, ID, buf);
        }
    }

    public List<String> getCollectionsJson() {
        return collectionsJson;
    }
}
