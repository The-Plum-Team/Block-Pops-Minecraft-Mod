package com.theplumteam.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * What the server says about player collections in chat. Each line is translated by a client
 * that has the text; a client from before player collections has not, so each carries its
 * English text to fall back on, the same as in {@code en_us.json}.
 */
public enum PlayerMessages {
    UPDATE_AVAILABLE("update_available", "There is a new version of “%s” (v%s). This world still uses v%s."),
    PIN_GONE("pin_gone", "The version of “%s” this world used is no longer available. Use v%s to show it again."),
    REMOVED_POLICY("removed.policy",
            "“%s” was removed for breaking the BlockPops rules and is no longer shown in this world."),
    REMOVED_OWNER("removed.owner", "“%s” is no longer available: its author deleted it."),
    REMOVED_REVIEW("removed.review", "“%s” is under review and is not shown for now."),
    REMOVED_OFF("removed.off", "Player collections have been turned off for now."),
    REMOVED_UNAVAILABLE("removed.unavailable", "“%s” is no longer available and is not shown in this world."),
    UPDATED("updated", "“%s” now uses v%s in this world."),
    UP_TO_DATE("up_to_date", "“%s” is already on its newest version (v%s)."),
    FOLLOWING("following", "“%s” will move to each new version by itself."),
    PINNED("pinned", "“%s” stays on v%s until you update it."),
    REFRESHED("refreshed", "Player collections checked."),
    REFRESH_WAIT("refresh_wait", "Player collections were checked a moment ago. Try again in a minute."),
    LIST_LOADED("list.loaded", "%s (%s, v%s)"),
    LIST_NEWER("list.newer", "%s (%s, v%s; v%s is available)"),
    LIST_NOT_SHOWN("list.not_shown", "%s (%s, not shown)");

    private final String key;
    private final String english;

    PlayerMessages(String key, String english) {
        this.key = "blockpops.player." + key;
        this.english = english;
    }

    public String key() {
        return key;
    }

    public String english() {
        return english;
    }

    public MutableComponent with(Object... arguments) {
        return Component.translatableWithFallback(key, english, arguments);
    }
}
