package com.theplumteam.server;

import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.CustomCollectionCheck;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.SyncCustomCollectionsPacket;
import com.theplumteam.remote.CodeEntry;
import com.theplumteam.remote.Manifest;
import com.theplumteam.remote.ManifestEntry;
import com.theplumteam.remote.RemoteCollections;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.RemoteLimits;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.remote.player.PlayerIds;
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
    /**
     * What a request came to. The last three are of player collections alone, and are written
     * after the others so a client from before them still reads the first eight.
     */
    public enum Result {
        ADDED, REMOVED, UNKNOWN_CODE, ALREADY_ENABLED, NOT_ENABLED, CLASHES, FULL, FAILED,
        PLAYER_ADDED, UNAVAILABLE, PLAYER_OFF;

        /** The result a client from before player collections is told instead. */
        public Result forOlderClient() {
            return switch (this) {
                case PLAYER_ADDED -> ADDED;
                case UNAVAILABLE, PLAYER_OFF -> FAILED;
                default -> this;
            };
        }
    }

    /** The most bytes the packet that carries every Custom collection to a client may have. */
    public static final int PACKET_BUDGET_BYTES = 960 * 1024;

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
            remote = new RemoteCollections(RemoteSource.forGame(), new VerifiedStore(cacheRoot()));
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
            for (Loaded entry : loaded) {
                String collectionId = entry.collection().getId();
                if (!stillEnabled.contains(collectionId)) {
                    continue;
                }
                if (fits(entry)) {
                    publish(entry);
                } else {
                    BlockPopsMod.LOGGER.warn("Custom collection {} grew past what one packet carries; keeping what"
                            + " was loaded before", collectionId);
                }
            }
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

    /** Whether every Custom collection still fits in the one packet that carries them, with this one in. */
    private static boolean fits(Loaded candidate) {
        List<SyncCustomCollectionsPacket.Entry> entries = new ArrayList<>();
        for (Loaded loaded : LOADED.values()) {
            if (!loaded.collection().getId().equals(candidate.collection().getId())) {
                entries.add(new SyncCustomCollectionsPacket.Entry(loaded.json(), loaded.files()));
            }
        }
        entries.add(new SyncCustomCollectionsPacket.Entry(candidate.json(), candidate.files()));
        return SyncCustomCollectionsPacket.encodedBytes(entries) <= PACKET_BUDGET_BYTES;
    }

    private static void publish(Loaded loaded) {
        LOADED.put(loaded.collection().getId(), loaded);
        CollectionRegistry.registerDynamicCollection(loaded.collection());
    }

    /**
     * Enables the collection an access code names. Looks it up and downloads its
     * definition in the background; {@code reply} runs on the server thread.
     *
     * <p>A code of the player shape names a player collection and goes to
     * {@link PlayerCollections}; nothing of the official tree is asked for it. Any other code
     * is an official one, and nothing below {@code p/} is asked for it.
     */
    public static void enableByCode(MinecraftServer server, String rawCode, Consumer<Status> reply) {
        String code = RemotePaths.normalizeCode(rawCode);
        if (code == null) {
            reply.accept(new Status(Result.UNKNOWN_CODE, ""));
            return;
        }
        if (PlayerIds.isPlayerCode(code)) {
            PlayerCollections.add(server, code, reply);
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
        if (!fits(loaded)) {
            return new Status(Result.FULL, collection.getName());
        }
        if (!settings.setEnabled(collection.getId(), true)) {
            return new Status(settings.enabled().size() >= CustomCollectionSettings.MAX_ENABLED
                    ? Result.FULL : Result.FAILED, collection.getName());
        }
        publish(loaded);
        sendToAll(server);
        return new Status(Result.ADDED, collection.getName());
    }

    /**
     * Disables a collection by its id and tells every player. Server thread. A player
     * collection is named by its id or its code, and {@code reply} then runs later.
     */
    public static void disable(MinecraftServer server, String collectionId, Consumer<Status> reply) {
        if (PlayerIds.isReserved(collectionId) || PlayerIds.isPlayerCode(collectionId)) {
            PlayerCollections.remove(server, collectionId, reply);
            return;
        }
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

    /** Reads the enabled collections from the CDN again and sends what changed. */
    public static void refreshNow(MinecraftServer server) {
        Set<String> enabled = CustomCollectionSettings.get(server).enabled();
        if (!enabled.isEmpty()) {
            RemoteExecutor.get().execute(() -> refresh(server, enabled));
        }
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
