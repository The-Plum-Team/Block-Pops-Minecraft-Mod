package com.theplumteam.client.remote;

import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.runtime.RuntimeAssets;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.CustomCollectionCheck;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.SyncCustomCollectionsPacket;
import com.theplumteam.remote.CdnSource;
import com.theplumteam.remote.ManifestEntry;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.RemoteLimits;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.server.CustomCollections;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Custom collections of the server this client is connected to.
 *
 * <p>When the server sends its list, the previous one is forgotten, every file of the new
 * one is announced as coming, the definitions are registered, and the files are fetched
 * in the background from the local verified cache or the CDN, one by one, so a file that
 * fails only leaves its own asset missing. Leaving the server forgets everything again.
 */
public final class ClientCustomCollections {
    private static final List<String> REGISTERED = new ArrayList<>();
    private static Consumer<CustomCollections.Status> statusListener;
    // Whether this server sends its Custom collections at all, and how often it has
    private static boolean synced;
    private static int revision;

    private ClientCustomCollections() {
    }

    /** Applies the server's list. Client thread. */
    public static void apply(List<SyncCustomCollectionsPacket.Entry> entries) {
        forget();
        synced = true;
        revision++;
        int generation = RuntimeAssets.generation();
        Map<String, ManifestEntry> files = new LinkedHashMap<>();
        for (SyncCustomCollectionsPacket.Entry entry : entries) {
            FigureCollection collection = parse(entry);
            if (collection == null) {
                continue;
            }
            List<String> paths = new ArrayList<>();
            for (ManifestEntry file : entry.files()) {
                if (RemotePaths.isAllowedAsset(collection.getId(), file.path())) {
                    paths.add(file.path());
                    files.put(file.path(), file);
                }
            }
            RuntimeAssets.expect(paths);
            CollectionRegistry.registerDynamicCollection(collection);
            REGISTERED.add(collection.getId());
        }
        if (!files.isEmpty()) {
            RemoteExecutor.get().execute(() -> RuntimeAssets.load(fetch(files.values()), generation));
        }
    }

    private static FigureCollection parse(SyncCustomCollectionsPacket.Entry entry) {
        try {
            JsonObject json = StrictJson.parseObject(entry.collectionJson().getBytes(StandardCharsets.UTF_8),
                    RemoteLimits.COLLECTION_JSON_BYTES);
            FigureCollection collection = FigureCollection.fromJson(json);
            String problem = CustomCollectionCheck.problem(collection, collection.getId(), entry.files());
            if (!RemotePaths.isValidCollectionId(collection.getId()) || problem != null) {
                BlockPopsMod.LOGGER.warn("Ignoring Custom collection {} from the server: it {}", collection.getId(),
                        problem != null ? problem : "has an invalid id");
                return null;
            }
            return collection;
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Ignoring a Custom collection the server sent: {}", e.getMessage());
            return null;
        }
    }

    /** Fetches each file from the cache or the CDN. Runs on the remote executor. */
    private static Map<String, Path> fetch(Iterable<ManifestEntry> entries) {
        VerifiedStore store = new VerifiedStore(CustomCollections.cacheRoot());
        RemoteSource source = new CdnSource();
        Map<String, Path> files = new LinkedHashMap<>();
        int failed = 0;
        for (ManifestEntry entry : entries) {
            try {
                Path file = store.cached(entry).orElse(null);
                files.put(entry.path(), file != null ? file : store.fetch(source, entry));
            } catch (Exception e) {
                failed++;
                BlockPopsMod.LOGGER.warn("Could not fetch Custom collection file {}: {}", entry.path(), e.getMessage());
            }
        }
        if (failed > 0) {
            BlockPopsMod.LOGGER.warn("{} Custom collection files could not be fetched; their assets stay missing", failed);
        }
        return files;
    }

    /** Forgets every Custom collection, as when leaving a server. Client thread. */
    public static void forget() {
        REGISTERED.forEach(CollectionRegistry::unregisterDynamicCollection);
        REGISTERED.clear();
        RuntimeAssets.clear();
        synced = false;
        revision++;
    }

    /** Whether the server this client is connected to offers Custom collections. */
    public static boolean isSynced() {
        return synced;
    }

    /** Changes every time the list changes, so a screen knows to rebuild. */
    public static int revision() {
        return revision;
    }

    /** The Custom collections of this server, in the order it sent them. */
    public static List<FigureCollection> collections() {
        List<FigureCollection> collections = new ArrayList<>();
        for (String collectionId : REGISTERED) {
            CollectionRegistry.getCollection(collectionId).ifPresent(collections::add);
        }
        return collections;
    }

    /** Shows an operator what their request came to: in an open screen that listens, else in chat. */
    public static void showStatus(CustomCollections.Status status) {
        if (statusListener != null) {
            statusListener.accept(status);
            return;
        }
        if (Minecraft.getInstance().player != null) {
            // 26.1 removed displayClientMessage; its chat line is sendSystemMessage there
            //? if >=26 {
            /*Minecraft.getInstance().player.sendSystemMessage(message(status));
            *///? } else {
            Minecraft.getInstance().player.displayClientMessage(message(status), false);
            //? }
        }
    }

    public static void setStatusListener(Consumer<CustomCollections.Status> listener) {
        statusListener = listener;
    }

    public static Component message(CustomCollections.Status status) {
        return Component.translatable("blockpops.custom_collections." + status.result().name().toLowerCase(java.util.Locale.ROOT),
                status.name());
    }
}
