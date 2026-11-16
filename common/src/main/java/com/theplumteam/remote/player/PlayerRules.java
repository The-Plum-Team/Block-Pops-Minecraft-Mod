package com.theplumteam.remote.player;

/**
 * The version of the rules player content is checked under, and the limits that are a player
 * collection's own. {@link #VERSION} changes whenever any rule that accepts content changes, so
 * content checked under older rules can be told apart and checked again.
 */
public final class PlayerRules {
    public static final int VERSION = 1;

    /** The most distinct files one collection references. */
    public static final int MAX_BLOBS = 512;

    /** The highest sequence number any signed document carries. */
    public static final long MAX_SEQ = 1L << 40;
    /** How far above the last accepted one a sequence number may be within one epoch. */
    public static final long MAX_SEQ_JUMP = 1L << 20;
    /** How far ahead of the game's clock a document may be issued, in seconds: one day. */
    public static final long MAX_CLOCK_AHEAD_SECONDS = 24L * 60 * 60;

    private PlayerRules() {
    }
}
