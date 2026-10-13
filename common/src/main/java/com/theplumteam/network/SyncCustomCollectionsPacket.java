package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.ManifestEntry;
import com.theplumteam.remote.RemoteLimits;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet with the Custom collections enabled in this world: each one's
 * definition and the files its assets are downloaded from, pinned to their hashes.
 *
 * <p>Custom collections travel only in this packet, never in
 * {@link SyncDynamicCollectionsPacket}, so a client without this feature never learns of
 * models it cannot load. The client announces every file before it registers the
 * collections, so nothing is drawn from a model that is still downloading.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class SyncCustomCollectionsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_custom_collections");
    private static final int MAX_JSON = (int) RemoteLimits.COLLECTION_JSON_BYTES;
    private static final int MAX_PATH = 256;

    /** One collection: its definition as JSON and the files of its assets. */
    public record Entry(String collectionJson, List<ManifestEntry> files) {
    }

    private final List<Entry> entries;

    public SyncCustomCollectionsPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() {
        return entries;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeUtf(entry.collectionJson(), MAX_JSON);
            buffer.writeVarInt(entry.files().size());
            for (ManifestEntry file : entry.files()) {
                buffer.writeUtf(file.path(), MAX_PATH);
                buffer.writeUtf(file.sha256(), 64);
            }
        }
        return buffer;
    }

    public static SyncCustomCollectionsPacket decode(FriendlyByteBuf buffer) {
        int count = bounded(buffer.readVarInt(), com.theplumteam.server.CustomCollectionSettings.MAX_ENABLED);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String json = buffer.readUtf(MAX_JSON);
            int fileCount = bounded(buffer.readVarInt(), RemoteLimits.MAX_FILES_PER_COLLECTION);
            List<ManifestEntry> files = new ArrayList<>(fileCount);
            for (int j = 0; j < fileCount; j++) {
                files.add(new ManifestEntry(buffer.readUtf(MAX_PATH), buffer.readUtf(64)));
            }
            entries.add(new Entry(json, List.copyOf(files)));
        }
        return new SyncCustomCollectionsPacket(List.copyOf(entries));
    }

    private static int bounded(int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Custom collections packet count out of range: " + count);
        }
        return count;
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncCustomCollectionsPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.remote.ClientCustomCollections.apply(packet.entries));
    }

    /** Whether the player's client can receive this packet, so Custom collections are kept from it otherwise. */
    public static boolean canReceive(ServerPlayer player) {
        return NetworkManager.canPlayerReceive(player, ID);
    }

    public static void sendToPlayer(ServerPlayer player, List<Entry> entries) {
        if (!canReceive(player)) {
            return;
        }
        PacketNetworking.sendToPlayer(player, ID, new SyncCustomCollectionsPacket(entries).encode());
    }
}
