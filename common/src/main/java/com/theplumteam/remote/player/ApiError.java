package com.theplumteam.remote.player;

import java.util.Locale;

/**
 * Every way a request to the upload service fails, as the closed list both ends share. The
 * service answers one of these and a few bounded numbers, never text to show, and the game maps
 * each to words of its own. A value is never renamed or given another meaning, only added.
 */
public enum ApiError {
    // Found by the game itself, before or instead of an answer
    SESSION_INVALID,
    MULTIPLAYER_DISABLED,
    MOJANG_BANNED,
    NAME_CHANGE_REQUIRED,
    NETWORK,

    // Who is asking
    MOJANG_UNAVAILABLE,
    NOT_VERIFIED,
    NOT_IN_BETA,
    FROZEN,
    BANNED,
    NOT_OWNER,

    // Whether the service takes requests now
    PAUSED,
    OWNER_PAUSED,
    CLIENT_OUTDATED,
    TERMS_OUTDATED,
    QUOTA_COLLECTIONS,
    QUOTA_DAILY,
    QUOTA_BYTES,
    QUOTA_GLOBAL,
    RATE_LIMITED,

    // What was asked
    UNAVAILABLE,
    CONFLICT,
    NAME_REFUSED,
    CONTENT_BLOCKED,
    ROLLBACK_OUTDATED,
    INVALID_FILE,
    INVALID_PACKAGE,
    TOO_LARGE,
    EXPIRED,
    NOT_FOUND,
    BAD_REQUEST,
    INTERNAL;

    private final String id = name().toLowerCase(Locale.ROOT);

    /** The code as an answer spells it, such as {@code not_verified}. */
    public String id() {
        return id;
    }

    /** The error an answer names; anything unknown is {@link #INTERNAL}. */
    public static ApiError byId(String id) {
        for (ApiError error : values()) {
            if (error.id.equals(id)) {
                return error;
            }
        }
        return INTERNAL;
    }
}
