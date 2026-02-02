package com.theplumteam.client.config;

/**
 * Client-side cache of server configuration values.
 * Updated via SyncServerConfigPacket when joining a server or when an admin changes settings.
 * Read by SettingsScreen and CollectionSelectionScreen for display.
 */
public class ClientServerConfig {
    private static int regularTokenCooldownHours = 3;
    private static int maxRegularTokens = 3;
    private static int guaranteedTokenResetHour = 18;

    /**
     * Update all cached values from a server config sync.
     */
    public static void update(int regularTokenCooldownHours, int maxRegularTokens,
                              int guaranteedTokenResetHour) {
        ClientServerConfig.regularTokenCooldownHours = regularTokenCooldownHours;
        ClientServerConfig.maxRegularTokens = maxRegularTokens;
        ClientServerConfig.guaranteedTokenResetHour = guaranteedTokenResetHour;
    }

    public static int getRegularTokenCooldownHours() {
        return regularTokenCooldownHours;
    }

    public static int getMaxRegularTokens() {
        return maxRegularTokens;
    }

    public static int getGuaranteedTokenResetHour() {
        return guaranteedTokenResetHour;
    }

    /**
     * Reset to defaults (used when disconnecting from a server).
     */
    public static void reset() {
        regularTokenCooldownHours = 3;
        maxRegularTokens = 3;
        guaranteedTokenResetHour = 18;
    }
}
