package com.theplumteam.client.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side manager for tracking which figures the player has discovered.
 * This is a cache of the server-side capability data, synced via network packets.
 */
public class ClientDiscoveryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientDiscoveryManager.class);
    private static final Set<String> discoveredFigures = new HashSet<>();
    private static final Map<String, String> figureSkins = new HashMap<>();

    /**
     * Replace the entire discovered set with new data from the server.
     * Called when the SyncDiscoveryDataPacket is received on login.
     *
     * @param figures The complete set of discovered figure IDs
     * @param skins Map of figure IDs to skin URLs
     */
    public static void setData(Set<String> figures, Map<String, String> skins) {
        discoveredFigures.clear();
        discoveredFigures.addAll(figures);
        figureSkins.clear();
        figureSkins.putAll(skins);
        LOGGER.debug("Discovery data synced: {} figures, {} skins", discoveredFigures.size(), figureSkins.size());
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public static void setData(Set<String> figures) {
        setData(figures, Collections.emptyMap());
    }

    /**
     * Add a newly discovered figure to the local cache.
     * Called when the UnlockFigurePacket is received.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     */
    public static void unlock(String figureId) {
        if (discoveredFigures.add(figureId)) {
            LOGGER.info("Figure unlocked: {}", figureId);
        }
    }

    /**
     * Check if a figure has been discovered by the player.
     * This is the main method used by the UI to determine what to display.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return true if the figure has been discovered, false otherwise
     */
    public static boolean isDiscovered(String figureId) {
        return discoveredFigures.contains(figureId);
    }

    /**
     * Get an unmodifiable view of all discovered figures.
     * Useful for debugging or displaying collection statistics.
     *
     * @return An unmodifiable set of discovered figure IDs
     */
    public static Set<String> getAllDiscovered() {
        return Collections.unmodifiableSet(discoveredFigures);
    }

    /**
     * Clear all discovery data.
     * Should be called when the player logs out to prevent data leakage between sessions.
     */
    public static void clear() {
        discoveredFigures.clear();
        figureSkins.clear();
        LOGGER.debug("Discovery data cleared");
    }

    /**
     * Save a skin URL for a figure.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @param skinUrl The skin URL to save
     */
    public static void saveFigureSkin(String figureId, String skinUrl) {
        figureSkins.put(figureId, skinUrl);
    }

    /**
     * Get the saved skin URL for a figure.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return The saved skin URL, or null if not saved
     */
    @Nullable
    public static String getFigureSkin(String figureId) {
        return figureSkins.get(figureId);
    }
}
