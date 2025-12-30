package com.theplumteam.server;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.server.config.ServerConfig;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Cross-platform server-side tick handler for managing token generation and cooldown logic.
 * Grants regular tokens every 3 hours (in-game time) and resets special tokens daily.
 */
public class ServerTickHandler {
    // Regular token cooldown: 3 hours of in-game time = 3 * 60 * 60 * 20 ticks
    private static final long REGULAR_TOKEN_COOLDOWN_TICKS = 3 * 60 * 60 * 20;

    // Maximum regular tokens a player can have
    private static final int MAX_REGULAR_TOKENS = 3;

    // Track last tick to avoid processing every tick
    private static long lastCheckTick = 0;
    private static final int CHECK_INTERVAL = 20; // Check once per second (20 ticks)

    /**
     * Initialize the server tick handler.
     * Call this during mod initialization.
     */
    public static void init() {
        TickEvent.SERVER_POST.register(ServerTickHandler::onServerTick);
        BlockPopsMod.LOGGER.info("Server tick handler initialized");
    }

    private static void onServerTick(MinecraftServer server) {
        // Don't check every tick to reduce overhead
        if (server.getTickCount() - lastCheckTick < CHECK_INTERVAL) {
            return;
        }
        lastCheckTick = server.getTickCount();

        // Process all online players
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
            boolean needsSync = false;

            // Handle regular token generation
            needsSync |= processRegularTokens(player, discovery);

            // Handle special token reset
            needsSync |= processSpecialTokenReset(discovery);

            // Save changes and sync to client if anything changed
            if (needsSync) {
                PlayerDataManager.markDirty(player, discovery);
                sendSyncPacket(player, discovery);
            }
        }
    }

    /**
     * Process regular token generation based on world time.
     * @return true if tokens were updated and sync is needed
     */
    private static boolean processRegularTokens(ServerPlayer player, IPlayerDiscovery discovery) {
        ServerLevel world = player.serverLevel();
        long gameTime = world.getGameTime();

        // If player has less than max tokens and enough time has passed
        if (discovery.getRegularTokens() < MAX_REGULAR_TOKENS
                && gameTime >= discovery.getNextRegularTokenTime()) {

            // Grant a token
            discovery.setRegularTokens(discovery.getRegularTokens() + 1);

            // Set next token time
            discovery.setNextRegularTokenTime(gameTime + REGULAR_TOKEN_COOLDOWN_TICKS);

            BlockPopsMod.LOGGER.debug("Granted regular token to {}. Total: {}/{}",
                    player.getName().getString(),
                    discovery.getRegularTokens(),
                    MAX_REGULAR_TOKENS);

            return true;
        }

        return false;
    }

    /**
     * Process special token daily reset.
     * @return true if token was reset and sync is needed
     */
    private static boolean processSpecialTokenReset(IPlayerDiscovery discovery) {
        long lastUpdateMillis = discovery.getLastSpecialTokenResetTimestamp();

        // If this is the first time (timestamp is 0), initialize it to now and don't reset yet
        if (lastUpdateMillis == 0) {
            discovery.setLastSpecialTokenResetTimestamp(System.currentTimeMillis());
            return false;
        }

        // Get current time in UTC
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));

        // Get the reset hour from config
        int resetHour = ServerConfig.getInstance().getGuaranteedTokenResetHour();

        // Calculate the target reset time for TODAY
        ZonedDateTime todayReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // Determine the *most recent* reset point that has occurred in the past
        ZonedDateTime mostRecentReset;
        if (now.isBefore(todayReset)) {
            // We haven't reached today's reset hour yet, so the last reset was yesterday
            mostRecentReset = todayReset.minusDays(1);
        } else {
            // We are past today's reset hour, so the last reset was today
            mostRecentReset = todayReset;
        }

        // Compare the player's last update timestamp with the most recent reset point.
        if (lastUpdateMillis < mostRecentReset.toInstant().toEpochMilli()) {

            // Update the timestamp to NOW so we don't trigger this logic again until the next reset point
            discovery.setLastSpecialTokenResetTimestamp(System.currentTimeMillis());

            // If the user has used their token, reset it
            if (discovery.hasUsedTodaySpecialToken()) {
                discovery.setUsedTodaySpecialToken(false);
                BlockPopsMod.LOGGER.debug("Daily token reset for player (Reset point was: {})", mostRecentReset);
            }

            // Always return true to trigger a sync
            return true;
        }

        return false;
    }

    /**
     * Send a sync packet to the client with updated token data.
     */
    private static void sendSyncPacket(ServerPlayer player, IPlayerDiscovery discovery) {
        ServerLevel world = player.serverLevel();
        long gameTime = world.getGameTime();
        long nextRegularTime = discovery.getNextRegularTokenTime();

        // Calculate ticks until next regular token
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

        // Calculate milliseconds until next special reset
        long millisUntilReset = calculateMillisUntilNextReset();

        SyncTokenDataPacket.sendToPlayer(
                player,
                discovery.getRegularTokens(),
                ticksUntilNext,
                !discovery.hasUsedTodaySpecialToken(),
                millisUntilReset
        );
    }

    /**
     * Public helper to calculate milliseconds until the next daily reset.
     * Used by this handler and various commands/packets to ensure consistency.
     */
    public static long calculateMillisUntilNextReset() {
        int resetHour = ServerConfig.getInstance().getGuaranteedTokenResetHour();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        ZonedDateTime nextReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= resetHour) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}
