package com.theplumteam.client.remote;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.ClientPacketNetworking;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.PlayerCollectionsHelloPacket;
import com.theplumteam.network.SyncPlayerCollectionLinesPacket;
import com.theplumteam.network.SyncPlayerCollectionsPacket;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.player.PlayerBudgets;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.remote.player.PlayerFetcher.Refused;
import com.theplumteam.remote.player.PlayerFetcher.Revision;
import com.theplumteam.remote.player.PlayerState;
import com.theplumteam.remote.player.PlayerStatus;
import com.theplumteam.server.ClientCapabilities;
import com.theplumteam.server.PlayerCollections;
import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.PlayerAssetIds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player collections of the server this client is connected to.
 *
 * <p>The server sends pointers, and nothing here is believed because the server said it. For
 * each pointer the head handed over with it must be genuine, live and not behind the revision
 * the pointer names; the index is loaded by its SHA-256 from what this game keeps or from the
 * CDN at a path computed here, and must be signed and of that collection and revision; the
 * collection is built from the record that index names. This is the only place on a client
 * that registers a player collection.
 *
 * <p>A collection's files are fetched when something of it is first drawn, and each is checked
 * whole before it is used. While any is shown, the status and the revocation list are checked
 * again now and then, and what they take out of use stops being shown whatever the server
 * says. Leaving the server forgets everything again; what was verified stays in the cache.
 *
 * <p>Verifying and fetching run on {@link RemoteExecutor}; the registry and the assets are
 * only touched on the client thread.
 */
public final class ClientPlayerCollections {
    /** However short a wait a status asks for, the origin is not asked more often than this. */
    private static final int MIN_POLL_SECONDS = 60;

    /** A collection this client shows. */
    private record Shown(SyncPlayerCollectionsPacket.Entry entry, Revision revision, PlayerAssetIds ids) {
    }

    private static final Map<String, Shown> SHOWN = new LinkedHashMap<>();
    /** The assets of collections whose files nobody has asked for yet. Read from any thread. */
    private static final Set<ResourceLocation> UNREQUESTED = ConcurrentHashMap.newKeySet();
    // Which server this is, and which of its lists: results for an earlier one are dropped
    private static int session;
    private static int applied;
    private static int revision;
    private static long nextPoll = Long.MAX_VALUE;
    private static boolean polling;
    /** Every collection added to this world, for whoever manages its codes; empty for anybody else. */
    private static List<SyncPlayerCollectionLinesPacket.Line> managed = List.of();

    private ClientPlayerCollections() {
    }

    /**
     * Tells the server what this client can do, once after joining: it loads player collections and
     * links a Ko-fi payment in its own dialog. Client thread.
     */
    public static void hello() {
        if (Minecraft.getInstance().getConnection() != null
                && ClientPacketNetworking.canServerReceive(PlayerCollectionsHelloPacket.ID)) {
            new PlayerCollectionsHelloPacket(ClientCapabilities.KOFI_LINK).sendToServer();
        }
    }

    /** Applies the server's list of pointers. Client thread. */
    public static void apply(List<SyncPlayerCollectionsPacket.Entry> entries) {
        int list = ++applied;
        int server = session;
        RemoteExecutor.get().execute(() -> {
            List<Shown> verified = verify(entries);
            long poll = pollMillis();
            Minecraft.getInstance().execute(() -> {
                if (list == applied && server == session) {
                    show(verified);
                    nextPoll = SHOWN.isEmpty() ? Long.MAX_VALUE : System.currentTimeMillis() + poll;
                }
            });
        });
    }

    // What each pointer comes to, in the server's order. Runs on the remote executor.
    private static List<Shown> verify(List<SyncPlayerCollectionsPacket.Entry> entries) {
        PlayerFetcher fetcher = PlayerCollections.fetcher();
        PlayerBudgets budgets = new PlayerBudgets();
        List<Shown> verified = new ArrayList<>();
        for (SyncPlayerCollectionsPacket.Entry entry : entries) {
            try {
                Revision followed = fetcher.follow(entry.code(), entry.id(), entry.rev(), entry.indexSha256(),
                        entry.head());
                if (!budgets.admit(entry.id(), followed.index().blobs())) {
                    BlockPopsMod.LOGGER.warn("Leaving out player collection {}: with the ones before it, it is more"
                            + " than a game holds at a time", entry.code());
                    continue;
                }
                verified.add(new Shown(entry, followed, new PlayerAssetIds(entry.id())));
            } catch (Refused refused) {
                BlockPopsMod.LOGGER.info("Player collection {} is not shown: {}", entry.code(), refused.why());
            } catch (IOException | RuntimeException e) {
                BlockPopsMod.LOGGER.warn("Ignoring player collection {} from the server: {}", entry.code(),
                        e.getMessage());
            }
        }
        return verified;
    }

    // How long to wait between two checks of the status, as the last status asks. Remote executor.
    private static long pollMillis() {
        int seconds = 3600;
        try {
            PlayerStatus status = PlayerCollections.fetcher().state().status();
            if (status != null) {
                seconds = status.clientPollSeconds();
            }
        } catch (IOException e) {
            // The default stands
        }
        return Math.max(MIN_POLL_SECONDS, seconds) * 1000L;
    }

    // Brings what is registered in line with the verified list. Client thread.
    private static void show(List<Shown> verified) {
        Map<String, Shown> next = new LinkedHashMap<>();
        verified.forEach(shown -> next.put(shown.entry().id(), shown));
        for (String id : new ArrayList<>(SHOWN.keySet())) {
            Shown now = next.get(id);
            if (now == null || !now.entry().indexSha256().equals(SHOWN.get(id).entry().indexSha256())) {
                drop(id);
            }
        }
        for (Shown shown : next.values()) {
            if (SHOWN.containsKey(shown.entry().id())) {
                continue;
            }
            Map<String, BlobInfo> blobs = shown.revision().index().blobs();
            // Announced before the collection is registered, so nothing draws a file that is still to come
            ClientPlayerAssets.expect(shown.ids(), blobs);
            UNREQUESTED.addAll(ClientPlayerAssets.ids(shown.ids(), blobs));
            CollectionRegistry.registerDynamicCollection(shown.revision().collection());
        }
        // In the server's order, which is the order they are listed in
        Map<String, Shown> ordered = new LinkedHashMap<>();
        next.forEach((id, shown) -> ordered.put(id, SHOWN.getOrDefault(id, shown)));
        SHOWN.clear();
        SHOWN.putAll(ordered);
        revision++;
    }

    private static void drop(String id) {
        Shown shown = SHOWN.remove(id);
        if (shown == null) {
            return;
        }
        CollectionRegistry.unregisterDynamicCollection(id);
        UNREQUESTED.removeIf(shown.ids()::owns);
        ClientPlayerAssets.release(shown.ids());
    }

    /**
     * Something is about to draw an asset that is still to come. If it is of a player collection
     * whose files were not asked for yet, they are fetched now. Cheap enough to call every frame.
     */
    public static void wanted(ResourceLocation asset) {
        if (!UNREQUESTED.isEmpty() && UNREQUESTED.contains(asset)) {
            Minecraft.getInstance().execute(() -> request(asset));
        }
    }

    private static void request(ResourceLocation asset) {
        Shown shown = SHOWN.get(PlayerAssetIds.collectionOf(asset));
        if (shown == null || !UNREQUESTED.remove(asset)) {
            return;
        }
        UNREQUESTED.removeIf(shown.ids()::owns);
        int server = session;
        String id = shown.entry().id();
        String index = shown.entry().indexSha256();
        RemoteExecutor.get().execute(() -> {
            PlayerFetcher fetcher = PlayerCollections.fetcher();
            int failed = 0;
            for (Map.Entry<String, BlobInfo> blob : shown.revision().index().blobs().entrySet()) {
                try {
                    byte[] bytes = fetcher.file(id, blob.getKey(), blob.getValue());
                    ClientPlayerAssets.load(shown.ids(), blob.getKey(), blob.getValue(), bytes,
                            () -> server == session && shows(id, index));
                } catch (IOException | RuntimeException e) {
                    failed++;
                    BlockPopsMod.LOGGER.warn("Could not fetch a file of player collection {}: {}", shown.entry().code(),
                            e.getMessage());
                }
            }
            if (failed > 0) {
                BlockPopsMod.LOGGER.warn("{} files of player collection {} could not be fetched; their assets stay"
                        + " missing", failed, shown.entry().code());
            }
        });
    }

    private static boolean shows(String id, String indexSha256) {
        Shown shown = SHOWN.get(id);
        return shown != null && shown.entry().indexSha256().equals(indexSha256);
    }

    /**
     * Checks the status and the revocation list again when it is time to, and stops showing
     * what they take out of use. Client thread, every tick.
     */
    public static void tick() {
        if (SHOWN.isEmpty() || polling || System.currentTimeMillis() < nextPoll || E2EDeterminism.ENABLED) {
            return;
        }
        polling = true;
        int server = session;
        Map<String, Integer> shown = new LinkedHashMap<>();
        SHOWN.forEach((id, entry) -> shown.put(id, entry.entry().rev()));
        RemoteExecutor.get().execute(() -> {
            PlayerFetcher fetcher = PlayerCollections.fetcher();
            Set<String> gone = new HashSet<>();
            try {
                if (fetcher.hasKeys()) {
                    fetcher.refresh();
                }
            } catch (IOException e) {
                BlockPopsMod.LOGGER.warn("Could not check the state of player collections: {}", e.getMessage());
            }
            try {
                PlayerState held = fetcher.state();
                for (Map.Entry<String, Integer> entry : shown.entrySet()) {
                    if (!held.keeps(entry.getKey(), entry.getValue())) {
                        gone.add(entry.getKey());
                        fetcher.discard(entry.getKey());
                    }
                }
            } catch (IOException e) {
                BlockPopsMod.LOGGER.warn("Could not read what is believed of player collections: {}", e.getMessage());
            }
            long poll = pollMillis();
            Minecraft.getInstance().execute(() -> {
                polling = false;
                if (server != session) {
                    return;
                }
                nextPoll = System.currentTimeMillis() + poll;
                if (!gone.isEmpty()) {
                    gone.forEach(ClientPlayerCollections::drop);
                    revision++;
                }
            });
        });
    }

    /** Takes the server's list of every added collection and where it stands. Client thread. */
    public static void managed(List<SyncPlayerCollectionLinesPacket.Line> lines) {
        managed = List.copyOf(lines);
        revision++;
    }

    /** Every collection added to this world, shown or not; empty unless this player manages its codes. */
    public static List<SyncPlayerCollectionLinesPacket.Line> managed() {
        return managed;
    }

    /** Forgets every player collection, as when leaving a server. Client thread. */
    public static void forget() {
        session++;
        managed = List.of();
        SHOWN.keySet().forEach(CollectionRegistry::unregisterDynamicCollection);
        SHOWN.clear();
        UNREQUESTED.clear();
        ClientPlayerAssets.forget();
        nextPoll = Long.MAX_VALUE;
        polling = false;
        revision++;
    }

    /** Whether the id is a player collection this server showed, which only this class registers. */
    public static boolean owns(String collectionId) {
        return SHOWN.containsKey(collectionId);
    }

    /** Where a player collection comes in the server's list, or -1. */
    public static int order(String collectionId) {
        int index = 0;
        for (String id : SHOWN.keySet()) {
            if (id.equals(collectionId)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** Who published a collection, by its signed index; null when nobody is named or it is not shown. */
    @Nullable
    public static String author(String collectionId) {
        Shown shown = SHOWN.get(collectionId);
        return shown != null ? shown.revision().index().author() : null;
    }

    /** The code a shown collection was added by, or null. */
    @Nullable
    public static String code(String collectionId) {
        Shown shown = SHOWN.get(collectionId);
        return shown != null ? shown.entry().code() : null;
    }

    /** Changes every time the list changes, so a screen knows to rebuild. */
    public static int revision() {
        return revision;
    }

    /** The player collections of this server, in the order it sent them. */
    public static List<FigureCollection> collections() {
        List<FigureCollection> collections = new ArrayList<>();
        SHOWN.values().forEach(shown -> collections.add(shown.revision().collection()));
        return collections;
    }
}
