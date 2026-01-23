package com.theplumteam.network;

/**
 * Enum representing the type of token being used for a claw machine drop.
 */
public enum TokenType {
    /**
     * Regular token - grants a random figure from the collection
     */
    REGULAR,

    /**
     * Guaranteed token - grants an undiscovered figure from the collection (if available)
     */
    GUARANTEED
}
