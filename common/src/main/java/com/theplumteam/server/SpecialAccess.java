package com.theplumteam.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.SyncSpecialAccessPacket;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.util.ServerLevels;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells which players are supporters, who receive the special plushes. It asks the service the
 * BlockPops-Hytale project asks, by Minecraft player name (the service keeps Minecraft and Hytale
 * usernames apart), and follows that project's rules:
 * - Specials stay locked until the service says otherwise: an unset or unreachable service,
 *   or a player it has not answered for yet, gives no access.
 * - A kept answer that has expired is still used while the service is asked again.
 * - A supporter whose subscription lapsed keeps the specials released while it was running.
 */
public final class SpecialAccess {
    /** Where a player becomes a supporter. */
    public static final String SUPPORT_URL = "https://ko-fi.com/theplumteam";

    private static final Gson GSON = new Gson();
    // How long a payment keeps a subscription running
    private static final int SUBSCRIPTION_DAYS = 35;
    private static final int MAX_ANSWER_BYTES = 8192;

    private static final Map<UUID, Answer> ANSWERS = new ConcurrentHashMap<>();
    private static final Set<UUID> ASKING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops-SpecialAccess-IO");
        thread.setDaemon(true);
        return thread;
    });

    private record Answer(boolean authorized, @Nullable String lastPaymentDate, long expiresAt) {}

    private SpecialAccess() {}

    /** Whether the player is a supporter now. */
    public static boolean isSupporter(ServerPlayer player) {
        Answer answer = ANSWERS.get(player.getUUID());
        if (answer == null || System.currentTimeMillis() > answer.expiresAt()) {
            refresh(player);
        }
        return answer != null && answer.authorized();
    }

    /** Whether the player receives this collection's specials. */
    public static boolean has(ServerPlayer player, FigureCollection collection) {
        if (!collection.hasSpecials()) {
            return false;
        }
        if (isSupporter(player)) {
            return true;
        }
        Answer answer = ANSWERS.get(player.getUUID());
        return answer != null
                && releasedWhileSubscribed(collection.getSpecialsReleasedAt(), answer.lastPaymentDate());
    }

    /** The figures a token spent on this collection can give the player. */
    public static List<FigureDefinition> figuresFor(ServerPlayer player, FigureCollection collection) {
        return has(player, collection) ? collection.getEnabledFigures() : collection.getRegularFigures();
    }

    /** The plush collections whose specials the player receives. */
    public static List<String> collectionsFor(ServerPlayer player) {
        List<String> collectionIds = new ArrayList<>();
        for (FigureCollection collection : CollectionRegistry.getPlushCollections()) {
            if (has(player, collection)) {
                collectionIds.add(collection.getId());
            }
        }
        return collectionIds;
    }

    /**
     * Asks the service about the player, off the server thread, and sends the player the
     * answer once it arrives. A player already being asked about is not asked about twice.
     */
    public static void refresh(ServerPlayer player) {
        ServerConfig config = ServerConfig.getInstance();
        String apiUrl = config.getSpecialAccessApiUrl();
        String apiKey = config.getSpecialAccessApiKey();
        // The packaged E2E stays inside its sandbox
        if (apiUrl.isEmpty() || apiKey.isEmpty() || E2EDeterminism.ENABLED) {
            return;
        }

        UUID playerId = player.getUUID();
        if (!ASKING.add(playerId)) {
            return;
        }

        String playerName = player.getScoreboardName();
        String url = (apiUrl.endsWith("/") ? apiUrl : apiUrl + "/") + "api/check/minecraft/"
                + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        long expiresAt = System.currentTimeMillis() + config.getSpecialAccessCacheMinutes() * 60_000L;
        MinecraftServer server = ServerLevels.serverOf(player);

        IO.execute(() -> {
            Answer answer;
            try {
                answer = ask(url, apiKey, expiresAt);
            } catch (IOException | RuntimeException e) {
                // The kept answer, if any, stands
                BlockPopsMod.logDebug("Special access check failed for {}: {}", playerName, e.toString());
                return;
            } finally {
                ASKING.remove(playerId);
            }

            Answer previous = ANSWERS.put(playerId, answer);
            if (previous == null || previous.authorized() != answer.authorized()) {
                BlockPopsMod.LOGGER.info(describe(playerName, answer));
            }
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null) {
                    SyncSpecialAccessPacket.sendToPlayer(online);
                }
            });
        });
    }

    private static Answer ask(String url, String apiKey, long expiresAt) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return new Answer(false, null, expiresAt);
            }
            String body;
            try (InputStream stream = connection.getInputStream()) {
                body = new String(stream.readNBytes(MAX_ANSWER_BYTES), StandardCharsets.UTF_8);
            }
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            boolean authorized = json.has("authorized") && json.get("authorized").getAsBoolean();
            JsonElement lastPayment = json.get("lastPaymentDate");
            return new Answer(authorized,
                    lastPayment != null && lastPayment.isJsonPrimitive() ? lastPayment.getAsString() : null,
                    expiresAt);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean releasedWhileSubscribed(@Nullable String releasedAt, @Nullable String lastPaymentDate) {
        LocalDate subscriptionEnd = subscriptionEnd(lastPaymentDate);
        if (subscriptionEnd == null || releasedAt == null || releasedAt.isEmpty()) {
            return false;
        }
        try {
            return !LocalDate.parse(releasedAt).isAfter(subscriptionEnd);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    @Nullable
    private static LocalDate subscriptionEnd(@Nullable String lastPaymentDate) {
        if (lastPaymentDate == null || lastPaymentDate.isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(lastPaymentDate, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
                    .plusDays(SUBSCRIPTION_DAYS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String describe(String playerName, Answer answer) {
        if (answer.authorized()) {
            return playerName + " is an active supporter: all specials unlocked.";
        }
        LocalDate subscriptionEnd = subscriptionEnd(answer.lastPaymentDate());
        if (subscriptionEnd != null) {
            return playerName + " is not an active supporter; specials released on or before "
                    + subscriptionEnd + " stay unlocked.";
        }
        return playerName + " is not a supporter: no special access.";
    }
}
