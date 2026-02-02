package com.theplumteam.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    // Token settings (configurable via Settings Screen)
    public int regularTokenCooldownHours = 3;       // Hours between earning each regular token (1-168)
    public int maxRegularTokens = 3;                 // Maximum stackable regular tokens (1-99)
    public int guaranteedTokenCooldownHours = 24;    // Hours between guaranteed token resets (1-168)

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
     * Get the regular token cooldown in hours (1-168)
     */
    public int getRegularTokenCooldownHours() {
        return regularTokenCooldownHours;
    }

    /**
     * Set the regular token cooldown in hours (clamped to 1-168)
     */
    public void setRegularTokenCooldownHours(int hours) {
        this.regularTokenCooldownHours = Math.max(1, Math.min(168, hours));
        save();
    }

    /**
     * Get the maximum number of regular tokens (1-99)
     */
    public int getMaxRegularTokens() {
        return maxRegularTokens;
    }

    /**
     * Set the maximum number of regular tokens (clamped to 1-99)
     */
    public void setMaxRegularTokens(int max) {
        this.maxRegularTokens = Math.max(1, Math.min(99, max));
        save();
    }

    /**
     * Get the guaranteed token cooldown in hours (1-168)
     */
    public int getGuaranteedTokenCooldownHours() {
        return guaranteedTokenCooldownHours;
    }

    /**
     * Set the guaranteed token cooldown in hours (clamped to 1-168)
     */
    public void setGuaranteedTokenCooldownHours(int hours) {
        this.guaranteedTokenCooldownHours = Math.max(1, Math.min(168, hours));
        save();
    }

    /**
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ServerConfig config = GSON.fromJson(json, ServerConfig.class);
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
        Path configPath = getConfigPath();

        try {
            // Ensure config directory exists
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            BlockPopsMod.LOGGER.debug("Saved server configuration");
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to save server configuration", e);
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
