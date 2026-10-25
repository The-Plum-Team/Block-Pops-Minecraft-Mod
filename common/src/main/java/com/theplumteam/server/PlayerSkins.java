package com.theplumteam.server;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.UnlockFigurePacket;
import com.theplumteam.util.AuthlibProfiles;
import com.theplumteam.util.ServerLevels;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The skins of World Players figures: the textures property of each player's signed Mojang
 * profile. For a player who is not on the server only the session service has it, and asking is
 * a network call, so it never runs on the server thread. A figure is handed out with the skin
 * known at once (its player's own profile while they are online, else the one fetched last), and
 * a fresh one is fetched on a thread of its own and saved to the collector's figure when it comes.
 */
public final class PlayerSkins {
    // A fetched skin is fetched again after this long
    private static final long FRESH_MILLIS = 10 * 60_000L;
    // A player the service gave no skin for is not asked about again before this
    private static final long RETRY_MILLIS = 10 * 60_000L;

    private record Fetched(@Nullable String textures, long at) {}

    private static final Map<UUID, Fetched> FETCHED = new ConcurrentHashMap<>();
    // What waits for each fetch under way
    private static final Map<UUID, List<Consumer<String>>> WAITING = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops-Skins-IO");
        thread.setDaemon(true);
        return thread;
    });

    private PlayerSkins() {
    }

    /**
     * The skin of a player figure for a collector, without waiting: saved to the collector's
     * figure when one is known, and fetched afresh in the background when it is missing or old.
     * Call on the server thread.
     *
     * @return the skin known now, or null
     */
    @Nullable
    public static String forFigure(ServerPlayer collector, String uniqueFigureId, FigureDefinition figure,
                                   IPlayerDiscovery discovery) {
        UUID playerId = figure.getPlayerUUID();
        if (playerId == null) {
            return null;
        }
        MinecraftServer server = ServerLevels.serverOf(collector);
        String now = known(server, playerId);
        if (now != null) {
            discovery.saveFigureSkin(uniqueFigureId, now);
        }
        UUID collectorId = collector.getUUID();
        refresh(server, playerId, figure.getName(),
                fresh -> remember(server, collectorId, uniqueFigureId, figure.getName(), fresh));
        return now;
    }

    /** The skin known for a player now: their own while they are online, else the one fetched last. */
    @Nullable
    public static String known(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            String own = textures(online.getGameProfile());
            if (own != null) {
                return own;
            }
        }
        Fetched fetched = FETCHED.get(playerId);
        return fetched == null ? null : fetched.textures();
    }

    /**
     * Fetches a player's skin on a thread of its own when the one fetched last is missing or old,
     * and hands it to the callback on the server thread. The callback is not called when the
     * service gives no skin.
     */
    public static void refresh(MinecraftServer server, UUID playerId, String name, Consumer<String> onFetched) {
        Fetched fetched = FETCHED.get(playerId);
        long age = fetched == null ? Long.MAX_VALUE : System.currentTimeMillis() - fetched.at();
        if (age < (fetched != null && fetched.textures() != null ? FRESH_MILLIS : RETRY_MILLIS)) {
            return;
        }
        boolean[] first = {false};
        WAITING.compute(playerId, (id, waiting) -> {
            if (waiting == null) {
                waiting = new ArrayList<>();
                first[0] = true;
            }
            waiting.add(onFetched);
            return waiting;
        });
        if (!first[0]) {
            return;
        }
        MinecraftSessionService session = ServerLevels.sessionServiceOf(server);
        IO.execute(() -> {
            String textures = null;
            try {
                GameProfile profile = AuthlibProfiles.fetch(session, new GameProfile(playerId, name));
                textures = profile == null ? null : textures(profile);
            } catch (Exception e) {
                BlockPopsMod.LOGGER.debug("Could not fetch the skin of {}: {}", name, e.getMessage());
            }
            FETCHED.put(playerId, new Fetched(textures, System.currentTimeMillis()));
            List<Consumer<String>> callbacks = WAITING.remove(playerId);
            String result = textures;
            if (result != null && callbacks != null && server.isRunning()) {
                server.execute(() -> callbacks.forEach(callback -> callback.accept(result)));
            }
        });
    }

    // A fresh skin replaces the one the collector's figure had, here and in their game
    private static void remember(MinecraftServer server, UUID collectorId, String uniqueFigureId, String name,
                                 String fresh) {
        ServerPlayer collector = server.getPlayerList().getPlayer(collectorId);
        if (collector == null) {
            return;
        }
        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(collector);
        if (fresh.equals(discovery.getFigureSkin(uniqueFigureId))) {
            return;
        }
        discovery.saveFigureSkin(uniqueFigureId, fresh);
        PlayerDataManager.markDirty(collector, discovery);
        // The unlock packet also carries the skin; a figure the collector has not discovered stays
        // undiscovered in their game
        if (discovery.isDiscovered(uniqueFigureId)) {
            UnlockFigurePacket.sendToPlayer(collector, uniqueFigureId, name, fresh);
        }
    }

    @Nullable
    private static String textures(GameProfile profile) {
        var textures = AuthlibProfiles.properties(profile).get("textures");
        return textures.isEmpty() ? null : AuthlibProfiles.value(textures.iterator().next());
    }
}
