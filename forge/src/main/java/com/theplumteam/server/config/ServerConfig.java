package com.theplumteam.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side configuration for BlockPops
 * Stores settings like guaranteed token reset hour
 */
public class ServerConfig {
    private static ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Guaranteed token reset hour (0-23 in UTC)
    public int guaranteedTokenResetHour = 18;  // Default: 6 PM UTC

    // Default player color for World Players collection (when player hasn't chosen a favorite)
    public String defaultPlayerColor = "original";

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
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ServerConfig config = GSON.fromJson(json, ServerConfig.class);
                BlockPopsMod.LOGGER.info("Loaded server configuration");
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
     * Get config file path
     */
    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("blockpops-server.json");
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        instance = load();
    }
}
