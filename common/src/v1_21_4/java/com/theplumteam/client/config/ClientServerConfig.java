package com.theplumteam.client.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static Set<String> localHiddenCollections = new HashSet<>();
    private static Set<String> enabledRemoteCollections = new HashSet<>();

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
     * Check if a collection is hidden by the server admin or locally by the user.
     */
    public static boolean isCollectionHidden(String collectionId) {
        return hiddenCollections.contains(collectionId) || localHiddenCollections.contains(collectionId);
    }

    /**
     * Get all hidden collection IDs (unmodifiable).
     */
    public static Set<String> getHiddenCollections() {
        return Collections.unmodifiableSet(hiddenCollections);
    }

    /**
     * Update enabled remote collections from server sync.
     */
    public static void updateEnabledRemoteCollections(List<String> enabled) {
        ClientServerConfig.enabledRemoteCollections = enabled != null ? new HashSet<>(enabled) : new HashSet<>();
    }

    /**
     * Get all enabled remote collection IDs (unmodifiable).
     */
    public static Set<String> getEnabledRemoteCollections() {
        return Collections.unmodifiableSet(enabledRemoteCollections);
    }

    /**
     * Check if a remote collection is enabled by the server admin.
     */
    public static boolean isRemoteCollectionEnabled(String collectionId) {
        return enabledRemoteCollections.contains(collectionId);
    }

    /**
     * Get all locally hidden collection IDs (unmodifiable).
     */
    public static Set<String> getLocalHiddenCollections() {
        return Collections.unmodifiableSet(localHiddenCollections);
    }

    /**
     * Update locally hidden collections and save to disk.
     */
    public static void updateLocalHiddenCollections(Set<String> hidden) {
        localHiddenCollections = hidden != null ? new HashSet<>(hidden) : new HashSet<>();
        saveLocalHiddenCollections();
    }

    /**
     * Check if a collection is hidden locally by the user.
     */
    public static boolean isLocalCollectionHidden(String collectionId) {
        return localHiddenCollections.contains(collectionId);
    }

    private static Path getLocalHiddenPath() {
        return com.theplumteam.platform.PlatformHelper.getConfigDirectory().resolve("blockpops-hidden.json");
    }

    /**
     * Load locally hidden collections from disk.
     */
    public static void loadLocalHiddenCollections() {
        try {
            Path path = getLocalHiddenPath();
            if (Files.exists(path)) {
                String json = Files.readString(path);
                JsonObject obj = new Gson().fromJson(json, JsonObject.class);
                if (obj.has("hidden")) {
                    Set<String> loaded = new HashSet<>();
                    JsonArray arr = obj.getAsJsonArray("hidden");
                    for (int i = 0; i < arr.size(); i++) {
                        loaded.add(arr.get(i).getAsString());
                    }
                    localHiddenCollections = loaded;
                }
            }
        } catch (Exception e) {
            // Silently ignore load errors
        }
    }

    private static void saveLocalHiddenCollections() {
        try {
            Path path = getLocalHiddenPath();
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String id : localHiddenCollections) {
                arr.add(id);
            }
            obj.add("hidden", arr);
            Files.writeString(path, new Gson().toJson(obj));
        } catch (Exception e) {
            // Silently ignore save errors
        }
    }

    /**
     * Reset to defaults (used when disconnecting from a server).
     */
    public static void reset() {
        regularTokenCooldownHours = 3;
        maxRegularTokens = 3;
        guaranteedTokenResetHour = 18;
        hiddenCollections = new HashSet<>();
        enabledRemoteCollections = new HashSet<>();
    }
}
