package com.theplumteam.figure;

/**
 * How players earn regular tokens: one every three hours of the world's game time, up to a stock
 * of three.
 *
 * The server's token clock applies these rules. They are compile-time constants, so a client
 * screen that explains them, such as an estimate of how long a collection takes to complete,
 * reads the same numbers without loading any server class.
 */
public final class DrawRules {
    /** Game-time ticks between two regular tokens: 3 hours at 20 ticks a second. */
    public static final long REGULAR_TOKEN_COOLDOWN_TICKS = 3 * 60 * 60 * 20;

    /** The most regular tokens a player holds; a full stock earns no more until one is spent. */
    public static final int MAX_REGULAR_TOKENS = 3;

    private DrawRules() {
    }
}
