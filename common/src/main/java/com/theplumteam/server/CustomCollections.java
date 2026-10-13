package com.theplumteam.server;

import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.CustomCollectionCheck;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.SyncCustomCollectionsPacket;
import com.theplumteam.remote.CdnSource;
import com.theplumteam.remote.CodeEntry;
import com.theplumteam.remote.Manifest;
import com.theplumteam.remote.ManifestEntry;
import com.theplumteam.remote.RemoteCollections;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.RemoteLimits;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.VerifiedStore;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Custom collections a server offers: collections published on the BlockPops CDN
 * that an operator enabled in this world by their access code.
 *
 * <p>The server only needs each collection's definition, to validate drops and unlocks;
 * players download the assets themselves. At start the definitions come from the local
 * verified cache, so a world opens with its collections even offline, and are then
 * refreshed from the CDN in the background. Network and disk work runs on
 * {@link RemoteExecutor}; the registry and players are only touched on the server thread.
 */
public final class CustomCollections {
    /** What an operator's request came to. */
    public enum Result { ADDED, REMOVED, UNKNOWN_CODE, ALREADY_ENABLED, NOT_ENABLED, CLASHES, FULL, FAILED }

    /** A result, with the collection's name or id when there is one. */
    public record Status(Result result, String name) {
    }

    /** A registered Custom collection, as sent to players. */
    private record Loaded(FigureCollection collection, String json, List<ManifestEntry> files) {
    }

    private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();
    private static RemoteCollections remote;

    private CustomCollections() {
    }

    /** The CDN client and the cache in the game directory, shared with the player's side in single player. */
    public static synchronized RemoteCollections remote() {
        if (remote == null) {
            remote = new RemoteCollections(new CdnSource(), new VerifiedStore(cacheRoot()));
        }
        return remote;
    }

    public static Path cacheRoot() {
        return Platform.getGameFolder().resolve("blockpops-cache");
    }

    /** Whether a collection is a Custom one, which is never sent in the shared collection sync. */
    public static boolean isCustom(String collectionId) {
        return LOADED.containsKey(collectionId);
    }

    /** Registers the enabled collections from the cache, then refreshes them from the CDN. */
    public static void onServerStarting(MinecraftServer server) {
        clear();
        Set<String> enabled = CustomCollectionSettings.get(server).enabled();
        if (enabled.isEmpty()) {
            return;
        }
        Manifest stored = storedManifest();
        if (stored != null) {
            for (String collectionId : enabled) {
                try {
                    publish(loadCached(stored, collectionId));
                } catch (Exception e) {
                    BlockPopsMod.LOGGER.info("Custom collection {} is not cached yet: {}", collectionId, e.getMessage());
                }
            }
        }
        RemoteExecutor.get().execute(() -> refresh(server, enabled));
    }

    public static void onServerStopped() {
        clear();
    }

    private static void clear() {
        LOADED.keySet().forEach(CollectionRegistry::unregisterDynamicCollection);
        LOADED.clear();
    }

    private static Manifest storedManifest() {
        try {
            Optional<byte[]> bytes = new VerifiedStore(cacheRoot()).loadManifest();
            return bytes.isPresent() ? Manifest.parse(bytes.get()) : null;
        } catch (IOException e) {
            BlockPopsMod.LOGGER.warn("Could not read the cached Custom collections manifest: {}", e.getMessage());
            return null;
        }
    }

    /** Downloads every enabled definition again and publishes what changed. Runs on the remote executor. */
    private static void refresh(MinecraftServer server, Set<String> enabled) {
        Manifest manifest;
        try {
            manifest = remote().manifest();
        } catch (IOException e) {
            BlockPopsMod.LOGGER.warn("Could not refresh Custom collections: {}", e.getMessage());
            return;
        }
        List<Loaded> loaded = new ArrayList<>();
        for (String collectionId : enabled) {
            try {
                loaded.add(download(manifest, collectionId));
            } catch (Exception e) {
                BlockPopsMod.LOGGER.warn("Could not refresh Custom collection {}: {}", collectionId, e.getMessage());
            }
        }
        server.execute(() -> {
            // Keep only what is still enabled; an operator may have changed the list meanwhile
            Set<String> stillEnabled = CustomCollectionSettings.get(server).enabled();
            loaded.stream().filter(entry -> stillEnabled.contains(entry.collection().getId()))
                    .forEach(CustomCollections::publish);
            sendToAll(server);
        });
    }

    private static Loaded loadCached(Manifest manifest, String collectionId) throws IOException {
        ManifestEntry definition = manifest.collectionJson(collectionId)
                .orElseThrow(() -> new RemoteNotFoundException("not in the manifest"));
        Path file = new VerifiedStore(cacheRoot()).cached(definition)
                .orElseThrow(() -> new RemoteNotFoundException("not cached"));
        return check(collectionId, Files.readAllBytes(file), manifest.filesFor(collectionId));
    }

    private static Loaded download(Manifest manifest, String collectionId) throws IOException {
        Path file = remote().collectionJson(manifest, collectionId);
        return check(collectionId, Files.readAllBytes(file), manifest.filesFor(collectionId));
    }

    private static Loaded check(String collectionId, byte[] bytes, List<ManifestEntry> files) throws IOException {
        JsonObject json = StrictJson.parseObject(bytes, RemoteLimits.COLLECTION_JSON_BYTES);
        FigureCollection collection;
        try {
            collection = FigureCollection.fromJson(json);
        } catch (RuntimeException e) {
            throw new IOException("its definition does not parse", e);
        }
        String problem = CustomCollectionCheck.problem(collection, collectionId, files);
        if (problem != null) {
            throw new IOException("it " + problem);
        }
        return new Loaded(collection, new String(bytes, StandardCharsets.UTF_8), List.copyOf(files));
    }

    private static void publish(Loaded loaded) {
        LOADED.put(loaded.collection().getId(), loaded);
        CollectionRegistry.registerDynamicCollection(loaded.collection());
    }

    /**
     * Enables the collection an access code names. Looks it up and downloads its
     * definition in the background; {@code reply} runs on the server thread.
     */
    public static void enableByCode(MinecraftServer server, String rawCode, Consumer<Status> reply) {
        String code = RemotePaths.normalizeCode(rawCode);
        if (code == null) {
            reply.accept(new Status(Result.UNKNOWN_CODE, ""));
            return;
        }
        RemoteExecutor.get().execute(() -> {
            Status status;
            Loaded loaded = null;
            try {
                CodeEntry entry = remote().lookupCode(code);
                if (BuiltInCollections.COLLECTION_IDS.contains(entry.id())) {
                    status = new Status(Result.CLASHES, entry.name());
                } else {
                    loaded = download(remote().manifest(), entry.id());
                    status = new Status(Result.ADDED, loaded.collection().getName());
                }
            } catch (RemoteNotFoundException e) {
                status = new Status(Result.UNKNOWN_CODE, "");
            } catch (Exception e) {
                BlockPopsMod.LOGGER.warn("Could not enable Custom collection for code {}: {}", code, e.getMessage());
                status = new Status(Result.FAILED, "");
            }
            Loaded result = loaded;
            Status outcome = status;
            server.execute(() -> reply.accept(result == null ? outcome : enable(server, result)));
        });
    }

    private static Status enable(MinecraftServer server, Loaded loaded) {
        FigureCollection collection = loaded.collection();
        CustomCollectionSettings settings = CustomCollectionSettings.get(server);
        if (settings.enabled().contains(collection.getId())) {
            return new Status(Result.ALREADY_ENABLED, collection.getName());
        }
        if (!settings.setEnabled(collection.getId(), true)) {
            return new Status(settings.enabled().size() >= CustomCollectionSettings.MAX_ENABLED
                    ? Result.FULL : Result.FAILED, collection.getName());
        }
        publish(loaded);
        sendToAll(server);
        return new Status(Result.ADDED, collection.getName());
    }

    /** Disables a collection by its id and tells every player. Server thread. */
    public static void disable(MinecraftServer server, String collectionId, Consumer<Status> reply) {
        CustomCollectionSettings settings = CustomCollectionSettings.get(server);
        if (!settings.enabled().contains(collectionId)) {
            reply.accept(new Status(Result.NOT_ENABLED, collectionId));
            return;
        }
        if (!settings.setEnabled(collectionId, false)) {
            reply.accept(new Status(Result.FAILED, collectionId));
            return;
        }
        Loaded loaded = LOADED.remove(collectionId);
        CollectionRegistry.unregisterDynamicCollection(collectionId);
        sendToAll(server);
        reply.accept(new Status(Result.REMOVED, loaded != null ? loaded.collection().getName() : collectionId));
    }

    /** The enabled collections, with the names of those loaded. Server thread. */
    public static List<Status> list(MinecraftServer server) {
        List<Status> statuses = new ArrayList<>();
        for (String collectionId : CustomCollectionSettings.get(server).enabled()) {
            Loaded loaded = LOADED.get(collectionId);
            statuses.add(new Status(loaded != null ? Result.ADDED : Result.FAILED,
                    loaded != null ? loaded.collection().getName() + " (" + collectionId + ")" : collectionId));
        }
        return statuses;
    }

    public static void sendTo(ServerPlayer player) {
        List<SyncCustomCollectionsPacket.Entry> entries = new ArrayList<>();
        for (Loaded loaded : LOADED.values()) {
            entries.add(new SyncCustomCollectionsPacket.Entry(loaded.json(), loaded.files()));
        }
        SyncCustomCollectionsPacket.sendToPlayer(player, entries);
    }

    public static void sendToAll(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(CustomCollections::sendTo);
    }
}
