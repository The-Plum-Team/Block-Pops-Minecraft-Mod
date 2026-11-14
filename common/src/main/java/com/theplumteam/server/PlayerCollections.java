package com.theplumteam.server;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.SyncPlayerCollectionLinesPacket;
import com.theplumteam.network.SyncPlayerCollectionsPacket;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.remote.player.PlayerCdn;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.remote.player.PlayerKeys;
import com.theplumteam.remote.player.PlayerStore;
import com.theplumteam.server.PlayerCollectionEngine.Line;
import com.theplumteam.server.PlayerCollectionEngine.Loaded;
import com.theplumteam.server.PlayerCollectionEngine.Notice;
import com.theplumteam.server.PlayerCollectionEngine.Outcome;
import com.theplumteam.server.PlayerCollectionEngine.Result;
import com.theplumteam.util.E2EDeterminism;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The player collections a server shows: collections players published on the BlockPops CDN
 * that whoever manages this world's codes added by their code.
 *
 * <p>{@link PlayerCollectionEngine} decides what is shown; this class is its place in the game.
 * It registers what the engine shows, sends each client that loads player collections the
 * pointers to them, asks the engine to check for changes now and then, and tells whoever
 * manages codes what changed. The engine's work runs on {@link RemoteExecutor}; the registry
 * and players are only touched on the server thread.
 */
public final class PlayerCollections {
    /** However short a wait a status asks for, the origin is not asked more often than this. */
    private static final int MIN_POLL_SECONDS = 60;
    private static final long REFRESH_COOLDOWN_MILLIS = 60_000;
    private static final long START_WAIT_SECONDS = 10;

    private static PlayerFetcher fetcher;
    private static volatile PlayerCollectionEngine engine;
    /** What is registered, by id, in the order of the world's list. Server thread only. */
    private static final Map<String, Loaded> SHOWN = new LinkedHashMap<>();
    private static final AtomicBoolean POLLING = new AtomicBoolean();
    private static long nextStatusPoll;
    private static long nextHeadPoll;
    private static long lastRefresh;

    private PlayerCollections() {
    }

    /**
     * What this game believes and keeps of player collections. There is one for the game, so
     * the player's side and the integrated server share it as they share the cache; it is only
     * ever used on {@link RemoteExecutor}'s thread.
     */
    public static synchronized PlayerFetcher fetcher() {
        if (fetcher == null) {
            fetcher = new PlayerFetcher(PlayerCdn.forGame(),
                    new PlayerStore(new VerifiedStore(CustomCollections.cacheRoot())), PlayerKeys.builtIn(),
                    () -> System.currentTimeMillis() / 1000);
        }
        return fetcher;
    }

    /** Whether a collection is a player collection this server shows. Server thread. */
    public static boolean isShown(String collectionId) {
        return SHOWN.containsKey(collectionId);
    }

    /** Registers what is kept of the world's player collections, then checks them in the background. */
    public static void onServerStarting(MinecraftServer server) {
        clear();
        PlayerCollectionEngine opened = new PlayerCollectionEngine(fetcher(),
                new PlayerCollectionSettings(server.getWorldPath(LevelResource.ROOT).resolve("data"),
                        BlockPopsMod.LOGGER::error),
                () -> System.currentTimeMillis() / 1000, !E2EDeterminism.ENABLED, BlockPopsMod.LOGGER::warn);
        engine = opened;
        try {
            // The cache is read on its own thread; a world opens with its collections all the same
            CompletableFuture.runAsync(opened::start, RemoteExecutor.get()).get(START_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("The player collections of this world are not loaded yet: {}", e.toString());
        }
        apply(server, opened, List.of());
        nextStatusPoll = 0;
        nextHeadPoll = 0;
    }

    public static void onServerStopped() {
        clear();
    }

    private static void clear() {
        engine = null;
        SHOWN.keySet().forEach(CollectionRegistry::unregisterDynamicCollection);
        SHOWN.clear();
        ClientCapabilities.clear();
        lastRefresh = 0;
    }

    public static void onPlayerQuit(ServerPlayer player) {
        ClientCapabilities.forget(player);
    }

    /** Checks for changes when it is time to. */
    public static void onServerTick(MinecraftServer server) {
        PlayerCollectionEngine current = engine;
        if (current == null || server.getTickCount() % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean status = now >= nextStatusPoll;
        boolean heads = now >= nextHeadPoll;
        if ((status || heads) && POLLING.compareAndSet(false, true)) {
            poll(server, current, status, heads, null);
        }
    }

    private static void poll(MinecraftServer server, PlayerCollectionEngine current, boolean status, boolean heads,
                             Runnable then) {
        RemoteExecutor.get().execute(() -> {
            try {
                if (status) {
                    current.pollStatus();
                }
                if (heads) {
                    current.pollHeads();
                }
            } catch (RuntimeException e) {
                BlockPopsMod.LOGGER.error("Could not check the player collections of this world", e);
            } finally {
                POLLING.set(false);
            }
            List<Notice> notices = current.takeNotices();
            server.execute(() -> {
                long now = System.currentTimeMillis();
                if (status) {
                    nextStatusPoll = now + wait(current.statusPollSeconds());
                }
                if (heads) {
                    nextHeadPoll = now + wait(current.headPollSeconds());
                }
                apply(server, current, notices);
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    // The wait a status asks for, a fifth more or less, so servers do not all ask at once
    private static long wait(int seconds) {
        long millis = Math.max(MIN_POLL_SECONDS, seconds) * 1000L;
        return millis + ThreadLocalRandom.current().nextLong(-millis / 5, millis / 5 + 1);
    }

    /**
     * Adds the collection a player code names. {@code reply} runs on the server thread.
     */
    public static void add(MinecraftServer server, String code, Consumer<CustomCollections.Status> reply) {
        run(server, current -> current.add(code), result -> reply.accept(status(result)));
    }

    /** Takes a player collection out of this world, by its code or its id. */
    public static void remove(MinecraftServer server, String codeOrId, Consumer<CustomCollections.Status> reply) {
        run(server, current -> current.remove(codeOrId), result -> reply.accept(status(result)));
    }

    /** Moves a collection to the revision its code names now. */
    public static void update(MinecraftServer server, String code, Consumer<Result> reply) {
        run(server, current -> current.update(code), reply);
    }

    /** Sets whether a collection moves to each new revision by itself. */
    public static void follow(MinecraftServer server, String code, boolean on, Consumer<Result> reply) {
        run(server, current -> current.follow(code, on), reply);
    }

    /**
     * Checks for changes now, at most once a minute for a world.
     *
     * @param reply told on the server thread whether the check ran
     */
    public static void refresh(MinecraftServer server, Consumer<Boolean> reply) {
        PlayerCollectionEngine current = engine;
        long now = System.currentTimeMillis();
        if (current == null || now - lastRefresh < REFRESH_COOLDOWN_MILLIS || !POLLING.compareAndSet(false, true)) {
            reply.accept(false);
            return;
        }
        lastRefresh = now;
        poll(server, current, true, true, () -> reply.accept(true));
    }

    /** Every player collection added to this world and where it stands. */
    public static List<Line> lines() {
        PlayerCollectionEngine current = engine;
        return current != null ? current.lines() : List.of();
    }

    private static void run(MinecraftServer server, Function<PlayerCollectionEngine, Result> work,
                            Consumer<Result> reply) {
        PlayerCollectionEngine current = engine;
        if (current == null) {
            reply.accept(new Result(Outcome.FAILED, "", 0));
            return;
        }
        RemoteExecutor.get().execute(() -> {
            Result result;
            try {
                result = work.apply(current);
            } catch (RuntimeException e) {
                BlockPopsMod.LOGGER.error("A request about the player collections of this world failed", e);
                result = new Result(Outcome.FAILED, "", 0);
            }
            List<Notice> notices = current.takeNotices();
            Result outcome = result;
            server.execute(() -> {
                apply(server, current, notices);
                reply.accept(outcome);
            });
        });
    }

    private static CustomCollections.Status status(Result result) {
        CustomCollections.Result mapped = switch (result.outcome()) {
            case ADDED -> CustomCollections.Result.PLAYER_ADDED;
            case REMOVED -> CustomCollections.Result.REMOVED;
            case ALREADY_ENABLED -> CustomCollections.Result.ALREADY_ENABLED;
            case NOT_ENABLED -> CustomCollections.Result.NOT_ENABLED;
            case UNKNOWN_CODE -> CustomCollections.Result.UNKNOWN_CODE;
            case FULL -> CustomCollections.Result.FULL;
            case UNAVAILABLE -> CustomCollections.Result.UNAVAILABLE;
            case OFF -> CustomCollections.Result.PLAYER_OFF;
            default -> CustomCollections.Result.FAILED;
        };
        return new CustomCollections.Status(mapped, result.name());
    }

    // Brings the registry in line with what the engine shows, and tells of what changed. Server thread.
    private static void apply(MinecraftServer server, PlayerCollectionEngine from, List<Notice> notices) {
        if (engine != from) {
            // The world closed while this was on its way
            return;
        }
        Map<String, Loaded> next = new LinkedHashMap<>();
        from.view().forEach(loaded -> next.put(loaded.entry().id(), loaded));
        boolean changed = false;
        for (String id : new ArrayList<>(SHOWN.keySet())) {
            Loaded now = next.get(id);
            if (now == null || now.revision() != SHOWN.get(id).revision()) {
                CollectionRegistry.unregisterDynamicCollection(id);
                SHOWN.remove(id);
                changed = true;
            }
        }
        for (Loaded loaded : next.values()) {
            Loaded before = SHOWN.get(loaded.entry().id());
            if (before == null) {
                CollectionRegistry.registerDynamicCollection(loaded.revision().collection());
                changed = true;
            } else if (!Arrays.equals(before.head(), loaded.head())) {
                // Clients keep up with the head even while the world stays on its revision
                changed = true;
            }
        }
        SHOWN.clear();
        SHOWN.putAll(next);
        // There is nobody to send to while the server is still starting
        if (changed && server.getPlayerList() != null) {
            server.getPlayerList().getPlayers().forEach(PlayerCollections::sendTo);
        }
        for (Notice notice : notices) {
            tell(server, notice);
        }
        // Whoever manages codes sees where every added collection stands, shown or not
        if (server.getPlayerList() != null) {
            server.getPlayerList().getPlayers().forEach(PlayerCollections::sendLinesTo);
        }
    }

    private static void sendLinesTo(ServerPlayer player) {
        PlayerCollectionEngine current = engine;
        if (current == null || !ClientCapabilities.playerCollections(player) || !ServerProfiles.managesCodes(player)) {
            return;
        }
        List<SyncPlayerCollectionLinesPacket.Line> lines = new ArrayList<>();
        for (Line line : current.lines()) {
            lines.add(new SyncPlayerCollectionLinesPacket.Line(line.entry().id(), line.entry().code(), line.entry().rev(),
                    line.entry().follow(), line.condition(), line.reason(), line.newest(), line.name()));
        }
        SyncPlayerCollectionLinesPacket.sendToPlayer(player, lines);
    }

    private static void tell(MinecraftServer server, Notice notice) {
        Component message = switch (notice.kind()) {
            case NEW_VERSION -> PlayerMessages.UPDATE_AVAILABLE.with(notice.name(), notice.rev(), notice.pinned());
            case PIN_GONE -> notice.rev() > notice.pinned() ? PlayerMessages.PIN_GONE.with(notice.name(), notice.rev())
                    : PlayerMessages.REMOVED_UNAVAILABLE.with(notice.name());
            case ALL_OFF -> PlayerMessages.REMOVED_OFF.with();
            case RETIRED -> switch (notice.reason() != null ? notice.reason() : PlayerCollectionEngine.Reason.UNAVAILABLE) {
                case POLICY -> PlayerMessages.REMOVED_POLICY.with(notice.name());
                case OWNER -> PlayerMessages.REMOVED_OWNER.with(notice.name());
                case REVIEW -> PlayerMessages.REMOVED_REVIEW.with(notice.name());
                default -> PlayerMessages.REMOVED_UNAVAILABLE.with(notice.name());
            };
        };
        BlockPopsMod.LOGGER.info("Player collections: {}", message.getString());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerProfiles.managesCodes(player)) {
                player.sendSystemMessage(message);
            }
        }
    }

    /** Sends a player the pointers to what this world shows, if their client loads player collections. */
    public static void sendTo(ServerPlayer player) {
        if (engine == null || !ClientCapabilities.playerCollections(player)) {
            return;
        }
        List<SyncPlayerCollectionsPacket.Entry> entries = new ArrayList<>();
        for (Loaded loaded : SHOWN.values()) {
            entries.add(new SyncPlayerCollectionsPacket.Entry(loaded.entry().id(), loaded.entry().code(),
                    loaded.entry().rev(), loaded.entry().index(), loaded.head()));
        }
        SyncPlayerCollectionsPacket.sendToPlayer(player, entries);
        sendLinesTo(player);
    }
}
