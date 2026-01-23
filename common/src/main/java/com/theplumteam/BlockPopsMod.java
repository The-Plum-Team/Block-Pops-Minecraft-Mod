package com.theplumteam;

import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import com.theplumteam.server.ServerTickHandler;
import com.theplumteam.server.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockPopsMod {
    public static final String MOD_ID = "blockpops";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Check if debug logging is enabled in the server config.
     * Returns false if config hasn't been loaded yet.
     */
    public static boolean isDebugLogging() {
        try {
            return ServerConfig.getInstance().isDebugLogging();
        } catch (Exception e) {
            // Config not loaded yet, default to false
            return false;
        }
    }

    /**
     * Log a debug info message only if debug logging is enabled.
     * Use this for diagnostic/troubleshooting messages.
     */
    public static void logDebug(String message) {
        if (isDebugLogging()) {
            LOGGER.info(message);
        }
    }

    /**
     * Log a debug info message with arguments only if debug logging is enabled.
     * Use this for diagnostic/troubleshooting messages.
     */
    public static void logDebug(String message, Object... args) {
        if (isDebugLogging()) {
            LOGGER.info(message, args);
        }
    }

    public static void init() {
        LOGGER.info("Initializing BlockPops mod");

        // Initialize cross-platform networking
        ModNetworking.init();

        // Initialize server tick handler for token management
        ServerTickHandler.init();

        LOGGER.info("BlockPops mod initialization complete");
    }
}
