package com.theplumteam.client.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side cache of server configuration values.
 * Updated via SyncServerConfigPacket when joining a server or when an admin changes settings.
 * Read by SettingsScreen and CollectionSelectionScreen for display.
 */
public class ClientServerConfig {
    private static int regularTokenCooldownHours = 3;
    private static int maxRegularTokens = 3;
    private static int guaranteedTokenResetHour = 18;
    private static Set<String> hiddenCollections = new HashSet<>();

    /**
     * Update all cached values from a server config sync.
     */
    public static void update(int regularTokenCooldownHours, int maxRegularTokens,
                              int guaranteedTokenResetHour) {
        ClientServerConfig.regularTokenCooldownHours = regularTokenCooldownHours;
        ClientServerConfig.maxRegularTokens = maxRegularTokens;
        ClientServerConfig.guaranteedTokenResetHour = guaranteedTokenResetHour;
    }

    /**
     * Update hidden collections from server sync.
     */
    public static void updateHiddenCollections(List<String> hidden) {
        ClientServerConfig.hiddenCollections = hidden != null ? new HashSet<>(hidden) : new HashSet<>();
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
     * Check if a collection is hidden by the server admin.
     */
    public static boolean isCollectionHidden(String collectionId) {
        return hiddenCollections.contains(collectionId);
    }

    /**
     * Get all hidden collection IDs (unmodifiable).
     */
    public static Set<String> getHiddenCollections() {
        return Collections.unmodifiableSet(hiddenCollections);
    }

    /**
     * Reset to defaults (used when disconnecting from a server).
     */
    public static void reset() {
        regularTokenCooldownHours = 3;
        maxRegularTokens = 3;
        guaranteedTokenResetHour = 18;
        hiddenCollections = new HashSet<>();
    }
}
