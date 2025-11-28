package com.theplumteam.server;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.time.ZoneId;
import java.time.ZonedDateTime;

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
        long lastUpdateMillis = capability.getLastSpecialTokenResetTimestamp();

        // If this is the first time (timestamp is 0), initialize it to now and don't reset yet
        if (lastUpdateMillis == 0) {
            capability.setLastSpecialTokenResetTimestamp(System.currentTimeMillis());
            return false;
        }

        // 1. Get current time in UTC
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));

        // 2. Get the reset hour from config
        int resetHour = ServerConfig.getInstance().getGuaranteedTokenResetHour();

        // 3. Calculate the target reset time for TODAY
        ZonedDateTime todayReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // 4. Determine the *most recent* reset point that has occurred in the past
        ZonedDateTime mostRecentReset;
        if (now.isBefore(todayReset)) {
            // We haven't reached today's reset hour yet, so the last reset was yesterday
            mostRecentReset = todayReset.minusDays(1);
        } else {
            // We are past today's reset hour, so the last reset was today
            mostRecentReset = todayReset;
        }

        // 5. Compare the player's last update timestamp with the most recent reset point.
        // If the player hasn't been updated since the most recent reset point, we need to reset.
        if (lastUpdateMillis < mostRecentReset.toInstant().toEpochMilli()) {

            // Update the timestamp to NOW so we don't trigger this logic again until the next reset point
            capability.setLastSpecialTokenResetTimestamp(System.currentTimeMillis());

            // If the user has used their token, reset it
            if (capability.hasUsedTodaySpecialToken()) {
                capability.setUsedTodaySpecialToken(false);
                BlockPopsMod.LOGGER.debug("Daily token reset for player (Reset point was: {})", mostRecentReset);
            }

            // Always return true to trigger a sync, ensuring the client has the correct state and cooldown timer
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