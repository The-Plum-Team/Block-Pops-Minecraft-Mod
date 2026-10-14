package com.theplumteam.client.token;

import com.theplumteam.network.SyncTokenDataPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side manager for tracking token status and cooldowns.
 * This is a cache of the server-side token data, synced via network packets.
 */
public class ClientTokenManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTokenManager.class);

    // Token counts
    private static int regularTokens = 0;
    private static int guaranteedTokens = 0;

    // Cooldown tracking
    private static long ticksUntilNextRegular = 0;
    private static long millisUntilNextSpecialReset = 0;

    // Last update time for calculating remaining cooldowns
    private static long lastUpdateTime = System.currentTimeMillis();

    /**
     * Update all token data from a sync packet.
     * Called when the SyncTokenDataPacket is received from the server.
     *
     * @param packet The packet containing updated token information
     */
    public static void update(SyncTokenDataPacket packet) {
        regularTokens = packet.getRegularTokens();
        ticksUntilNextRegular = packet.getTicksUntilNextRegular();
        guaranteedTokens = packet.getGuaranteedTokens();
        millisUntilNextSpecialReset = packet.getMillisUntilNextSpecialReset();
        lastUpdateTime = System.currentTimeMillis();

        LOGGER.trace("Token data updated: {} regular, {} guaranteed, next regular in {} ticks",
                regularTokens, guaranteedTokens, ticksUntilNextRegular);
    }

    /**
     * Get the number of regular tokens the player currently has.
     *
     * @return Number of regular tokens (up to 3 on their own, more from blue tickets)
     */
    public static int getRegularTokens() {
        return regularTokens;
    }

    /**
     * Check if the player has a guaranteed token available.
     *
     * @return true if at least one guaranteed token is available
     */
    public static boolean hasSpecialToken() {
        return guaranteedTokens > 0;
    }

    /**
     * Get the number of guaranteed tokens the player has: today's, if unused, plus those
     * added by gold tickets.
     *
     * @return Number of guaranteed tokens
     */
    public static int getGuaranteedTokens() {
        return guaranteedTokens;
    }

    /**
     * Get the number of ticks until the next regular token is generated.
     * Note: This value decreases over time and should be updated by the server periodically.
     *
     * @return Ticks until next regular token
     */
    public static long getTicksUntilNextRegular() {
        return Math.max(0, ticksUntilNextRegular);
    }

    /**
     * Get the number of milliseconds until the next special token reset.
     * Calculates the remaining time based on the last update from the server.
     *
     * @return Milliseconds until next special token reset
     */
    public static long getMillisUntilNextSpecialReset() {
        long timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime;
        return Math.max(0, millisUntilNextSpecialReset - timeSinceUpdate);
    }

    /**
     * Format the time remaining for the next regular token as a human-readable string.
     *
     * @return Formatted time string (e.g., "1h 23m" or "45m 12s")
     */
    public static String formatNextRegularTime() {
        long ticks = getTicksUntilNextRegular();
        if (ticks <= 0) {
            return "Ready!";
        }

        // Convert ticks to seconds (20 ticks = 1 second)
        long totalSeconds = ticks / 20;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Format the time remaining for the next special token reset as a human-readable string.
     *
     * @return Formatted time string (e.g., "12h 34m" or "3h 45m")
     */
    public static String formatNextSpecialResetTime() {
        long millis = getMillisUntilNextSpecialReset();
        if (millis <= 0) {
            return "Resetting...";
        }

        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    /**
     * Clear all token data.
     * Should be called when the player logs out to prevent data leakage between sessions.
     */
    public static void clear() {
        regularTokens = 0;
        guaranteedTokens = 0;
        ticksUntilNextRegular = 0;
        millisUntilNextSpecialReset = 0;
        lastUpdateTime = System.currentTimeMillis();
        LOGGER.debug("Token data cleared");
    }
}
