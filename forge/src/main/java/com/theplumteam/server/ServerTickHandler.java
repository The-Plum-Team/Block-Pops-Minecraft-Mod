package com.theplumteam.server;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SyncTokenDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Server-side tick handler for managing token generation and cooldown logic.
 * Grants regular tokens every 3 hours (in-game time) and resets special tokens daily.
 */
@Mod.EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerTickHandler {
    // Regular token cooldown: 3 hours of in-game time = 3 * 60 * 60 * 20 ticks
    private static final long REGULAR_TOKEN_COOLDOWN_TICKS = 3 * 60 * 60 * 20;

    // Maximum regular tokens a player can have
    private static final int MAX_REGULAR_TOKENS = 3;

    // Daily reset hour in UTC (6 PM UTC)
    private static final int RESET_HOUR_UTC = 18;

    // Track last tick to avoid processing every tick
    private static long lastCheckTick = 0;
    private static final int CHECK_INTERVAL = 20; // Check once per second (20 ticks)

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Only process at the end of tick
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // Don't check every tick to reduce overhead
        if (event.getServer().getTickCount() - lastCheckTick < CHECK_INTERVAL) {
            return;
        }
        lastCheckTick = event.getServer().getTickCount();

        // Process all online players
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(capability -> {
                boolean needsSync = false;

                // Handle regular token generation
                needsSync |= processRegularTokens(player, capability);

                // Handle special token reset
                needsSync |= processSpecialTokenReset(capability);

                // Sync to client if anything changed
                if (needsSync) {
                    sendSyncPacket(player, capability);
                }
            });
        }
    }

    /**
     * Process regular token generation based on world time.
     * @return true if tokens were updated and sync is needed
     */
    private static boolean processRegularTokens(ServerPlayer player, IPlayerDiscovery capability) {
        ServerLevel world = (ServerLevel) player.level();
        long gameTime = world.getGameTime();

        // If player has less than max tokens and enough time has passed
        if (capability.getRegularTokens() < MAX_REGULAR_TOKENS
                && gameTime >= capability.getNextRegularTokenTime()) {

            // Grant a token
            capability.setRegularTokens(capability.getRegularTokens() + 1);

            // Set next token time
            capability.setNextRegularTokenTime(gameTime + REGULAR_TOKEN_COOLDOWN_TICKS);

            BlockPopsMod.LOGGER.debug("Granted regular token to {}. Total: {}/{}",
                    player.getName().getString(),
                    capability.getRegularTokens(),
                    MAX_REGULAR_TOKENS);

            return true;
        }

        return false;
    }

    /**
     * Process special token daily reset.
     * @return true if token was reset and sync is needed
     */
    private static boolean processSpecialTokenReset(IPlayerDiscovery capability) {
        long currentTimeMillis = System.currentTimeMillis();
        long lastResetMillis = capability.getLastSpecialTokenResetTimestamp();

        // If this is the first time, initialize the timestamp
        if (lastResetMillis == 0) {
            capability.setLastSpecialTokenResetTimestamp(currentTimeMillis);
            return false;
        }

        // Get current time in UTC
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        ZonedDateTime lastReset = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastResetMillis),
                ZoneId.of("UTC")
        );

        // Check if it's past reset hour and a new day
        boolean isPastResetHour = now.getHour() >= RESET_HOUR_UTC;
        boolean isDifferentDay = !now.toLocalDate().equals(lastReset.toLocalDate());
        boolean wasBeforeResetHour = lastReset.getHour() < RESET_HOUR_UTC;

        // Reset if:
        // 1. We're on a different day and past reset hour
        // 2. OR we're on the same day but last reset was before reset hour and now we're past it
        boolean shouldReset = (isDifferentDay && isPastResetHour)
                || (!isDifferentDay && wasBeforeResetHour && isPastResetHour);

        if (shouldReset && capability.hasUsedTodaySpecialToken()) {
            capability.setUsedTodaySpecialToken(false);
            capability.setLastSpecialTokenResetTimestamp(currentTimeMillis);

            BlockPopsMod.LOGGER.debug("Reset special token (daily reset at {}:00 UTC)", RESET_HOUR_UTC);

            return true;
        }

        return false;
    }

    /**
     * Send a sync packet to the client with updated token data.
     */
    private static void sendSyncPacket(ServerPlayer player, IPlayerDiscovery capability) {
        ServerLevel world = (ServerLevel) player.level();
        long gameTime = world.getGameTime();
        long nextRegularTime = capability.getNextRegularTokenTime();

        // Calculate ticks until next regular token
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

        // Calculate milliseconds until next special reset
        long millisUntilReset = calculateMillisUntilNextReset();

        SyncTokenDataPacket packet = new SyncTokenDataPacket(
                capability.getRegularTokens(),
                ticksUntilNext,
                !capability.hasUsedTodaySpecialToken(),
                millisUntilReset
        );

        BlockPopsModForge.NETWORK_CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                packet
        );
    }

    /**
     * Calculate milliseconds until the next daily reset at RESET_HOUR_UTC.
     */
    private static long calculateMillisUntilNextReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        ZonedDateTime nextReset = now.withHour(RESET_HOUR_UTC).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= RESET_HOUR_UTC) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}
