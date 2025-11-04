package com.theplumteam.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server-to-client packet that syncs dynamic collections (like World Players).
 * Sent when a player joins to ensure they can see dynamically generated collections.
 */
public class SyncDynamicCollectionsPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncDynamicCollectionsPacket.class);
    private static final Gson GSON = new Gson();

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

    public static void encode(SyncDynamicCollectionsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.collectionsJson.size());
        for (String json : packet.collectionsJson) {
            buffer.writeUtf(json, 1048576); // 1MB limit for large collections
        }
    }

    public static SyncDynamicCollectionsPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        ArrayList<String> collectionsJson = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            collectionsJson.add(buffer.readUtf(1048576));
        }
        return new SyncDynamicCollectionsPacket(collectionsJson);
    }

    public static void handle(SyncDynamicCollectionsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
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
        context.setPacketHandled(true);
    }

    public List<String> getCollectionsJson() {
        return collectionsJson;
    }
}
