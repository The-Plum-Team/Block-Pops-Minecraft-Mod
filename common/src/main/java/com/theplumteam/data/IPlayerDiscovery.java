package com.theplumteam.data;

import com.theplumteam.block.PopBlockColor;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Interface for tracking which figures a player has discovered from the claw machine.
 * This data is attached to each player to maintain their personal collection progress.
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

    // Token System Methods

    /**
     * Get the number of regular tokens the player currently has.
     * @return Number of regular tokens (0-3)
     */
    int getRegularTokens();

    /**
     * Set the number of regular tokens the player has.
     * @param count Number of tokens (should be 0-3)
     */
    void setRegularTokens(int count);

    /**
     * Get the world time (in ticks) when the next regular token will be granted.
     * @return World time in ticks for next token generation
     */
    long getNextRegularTokenTime();

    /**
     * Set the world time when the next regular token should be granted.
     * @param worldTimeTicks World time in ticks
     */
    void setNextRegularTokenTime(long worldTimeTicks);

    /**
     * Get the timestamp (System.currentTimeMillis) of the last special token reset.
     * @return Timestamp in milliseconds
     */
    long getLastSpecialTokenResetTimestamp();

    /**
     * Set the timestamp of the last special token reset.
     * @param timestamp Timestamp in milliseconds from System.currentTimeMillis()
     */
    void setLastSpecialTokenResetTimestamp(long timestamp);

    /**
     * Check if the player has used their guaranteed token today.
     * @return true if used, false if available
     */
    boolean hasUsedTodaySpecialToken();

    /**
     * Set whether the player has used their guaranteed token today.
     * @param used true if used, false if available
     */
    void setUsedTodaySpecialToken(boolean used);

    // Favorite Color Methods

    /**
     * Checks if the player has chosen their favorite color.
     * @return true if a color has been chosen, false otherwise
     */
    boolean hasChosenFavoriteColor();

    /**
     * Sets whether the player has chosen their favorite color.
     * @param hasChosen true to mark as chosen
     */
    void setHasChosenFavoriteColor(boolean hasChosen);

    /**
     * Gets the player's chosen favorite color.
     * @return The PopBlockColor enum, or null if not chosen.
     */
    @Nullable
    PopBlockColor getFavoriteColor();

    /**
     * Sets the player's favorite color.
     * @param color The chosen color
     */
    void setFavoriteColor(@Nullable PopBlockColor color);

    // Player Figure Skin Snapshot Methods

    /**
     * Saves a snapshot of a player figure's skin URL when discovered.
     * This preserves the skin as it was when the figure was obtained.
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @param skinUrl The skin texture URL to save
     */
    void saveFigureSkin(String figureId, String skinUrl);

    /**
     * Gets the saved skin URL for a player figure.
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return The saved skin URL, or null if not saved
     */
    @Nullable
    String getFigureSkin(String figureId);

    /**
     * Gets all saved figure skins.
     * @return An unmodifiable map of figure IDs to skin URLs
     */
    Map<String, String> getAllFigureSkins();
}
