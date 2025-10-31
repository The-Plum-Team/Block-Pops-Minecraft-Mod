package com.theplumteam.capability;

import java.util.Set;

/**
 * Interface for tracking which figures a player has discovered from the claw machine.
 * This capability is attached to each player to maintain their personal collection progress.
 */
public interface IPlayerDiscovery {
    /**
     * Check if a figure has been discovered by the player.
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return true if the figure has been discovered, false otherwise
     */
    boolean isDiscovered(String figureId);

    /**
     * Mark a figure as discovered for the player.
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     */
    void discover(String figureId);

    /**
     * Get the complete set of discovered figure IDs.
     * @return An unmodifiable set of discovered figure IDs
     */
    Set<String> getDiscoveredSet();

    /**
     * Replace the entire discovered set with a new one.
     * Used for syncing data from the server to the client.
     * @param discovered The new set of discovered figure IDs
     */
    void syncFrom(Set<String> discovered);
}
