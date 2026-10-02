package com.theplumteam.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Cross-platform server-side configuration for BlockPops.
 * Stores settings like guaranteed token reset hour and default player color.
 */
public class ServerConfig {
    private static ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Guaranteed token reset hour (0-23 in UTC)
    public int guaranteedTokenResetHour = 18;  // Default: 6 PM UTC

    // Default player color for World Players collection (when player hasn't chosen a favorite)
    public String defaultPlayerColor = "original";

    // Debug logging toggle - when true, shows detailed info logs for troubleshooting
    public boolean debugLogging = false;

    // Collection IDs hidden from every player's collection list (an ID that matches no collection is kept)
    private List<String> hiddenCollections = new ArrayList<>();

    private ServerConfig() {
        // Private constructor for singleton
    }

    public static ServerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Get the guaranteed token reset hour (0-23 in UTC)
     */
    public int getGuaranteedTokenResetHour() {
        return guaranteedTokenResetHour;
    }

    /**
     * Set the guaranteed token reset hour (0-23 in UTC)
     */
    public void setGuaranteedTokenResetHour(int hour) {
        this.guaranteedTokenResetHour = Math.max(0, Math.min(23, hour));
        save();
    }

    /**
     * Get the default player color setting as an Enum.
     * Safely falls back to ORIGINAL if config is corrupted/invalid.
     */
    public PopBlockColor getDefaultPlayerColor() {
        try {
            return PopBlockColor.valueOf(defaultPlayerColor.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return PopBlockColor.ORIGINAL;
        }
    }

    /**
     * Set the default player color.
     */
    public void setDefaultPlayerColor(PopBlockColor color) {
        this.defaultPlayerColor = color.name().toLowerCase();
        save();
    }

    /**
     * Check if debug logging is enabled.
     */
    public boolean isDebugLogging() {
        return debugLogging;
    }

    /**
     * Set the debug logging toggle.
     */
    public void setDebugLogging(boolean enabled) {
        this.debugLogging = enabled;
        save();
    }

    /**
     * Get the IDs of the collections hidden from every player's collection list.
     */
    public List<String> getHiddenCollections() {
        return List.copyOf(hiddenCollections);
    }

    /**
     * Set the hidden collection IDs. Keeps the previous list and returns false if it cannot be saved.
     */
    public boolean setHiddenCollections(Collection<String> collectionIds) {
        List<String> previous = hiddenCollections;
        this.hiddenCollections = readHiddenCollections(GSON.toJsonTree(collectionIds));
        if (saveResult()) {
            return true;
        }
        this.hiddenCollections = previous;
        return false;
    }

    /**
     * Read the hidden collection IDs, skipping anything that is not a non-empty string.
     */
    private static List<String> readHiddenCollections(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return new ArrayList<>();
        }
        if (!value.isJsonArray()) {
            BlockPopsMod.LOGGER.warn("Ignoring non-array hiddenCollections in server configuration");
            return new ArrayList<>();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || entry.getAsString().isEmpty()) {
                BlockPopsMod.LOGGER.warn("Ignoring invalid hiddenCollections entry in server configuration");
                continue;
            }
            ids.add(entry.getAsString());
        }
        return new ArrayList<>(ids);
    }

    /**
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                JsonObject document = GSON.fromJson(json, JsonObject.class);
                // Read this list separately so a malformed entry cannot reset the other settings
                JsonElement hidden = document.remove("hiddenCollections");
                ServerConfig config = GSON.fromJson(document, ServerConfig.class);
                config.hiddenCollections = readHiddenCollections(hidden);
                // Use LOGGER.debug directly to avoid circular dependency with BlockPopsMod.logDebug()
                BlockPopsMod.LOGGER.debug("Loaded server configuration");
                return config;
            } catch (Exception e) {
                BlockPopsMod.LOGGER.error("Failed to load server configuration, using defaults", e);
            }
        }

        // Return default config and save it
        ServerConfig config = new ServerConfig();
        config.save();
        return config;
    }

    /**
     * Save configuration to file
     */
    public void save() {
        saveResult();
    }

    /**
     * Save configuration to file and report whether it was written
     */
    private boolean saveResult() {
        Path configPath = getConfigPath();

        try {
            // Ensure config directory exists
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            BlockPopsMod.LOGGER.debug("Saved server configuration");
            return true;
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to save server configuration", e);
            return false;
        }
    }

    /**
     * Get config file path using cross-platform PlatformHelper
     */
    private static Path getConfigPath() {
        return PlatformHelper.getConfigDirectory().resolve("blockpops-server.json");
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        instance = load();
    }
}
