package com.theplumteam.figure;

/**
 * Defines the type of a figure definition.
 * This determines how the figure's texture is resolved during rendering.
 */
public enum FigureType {
    /**
     * A static figure loaded from JSON with a predefined texture file.
     */
    STATIC,

    /**
     * A dynamic figure representing a player, using their actual Minecraft skin.
     */
    PLAYER
}
