package com.theplumteam.client.upload;

import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.ContentError;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * The game's own words for each way a request to the upload service fails. The service sends a
 * code and a few numbers, never text, so nothing a player reads here was written anywhere but
 * in the game's language files.
 */
public final class UploadErrors {
    private static final String ERRORS = "blockpops.upload.error.";
    private static final String RULES = "blockpops.upload.rule.";
    private static final long MIB = 1024 * 1024;

    private UploadErrors() {
    }

    /** The language key of an error's text. */
    public static String key(ApiError error) {
        return ERRORS + error.id();
    }

    /**
     * The language key of the kind of rule a file or a record broke. Rules are told by kind,
     * not one by one: the creator checks every file with the same rules before anything is
     * sent, so the service refusing one is rare, and the kind says where to look.
     */
    public static String ruleKey(ContentError rule) {
        String name = rule.name();
        String kind = name.startsWith("JSON_") ? "json" : name.startsWith("TEXT_") ? "text"
                : name.startsWith("PNG_") ? "png" : name.startsWith("GEO_") ? "model"
                : name.startsWith("ANIMATION_") ? "animation" : name.startsWith("RECORD_") ? "record"
                : name.startsWith("BUDGET_") ? "budget" : "file";
        return RULES + kind;
    }

    /** What goes in the placeholders of an error's text, in their order. */
    static Object[] arguments(UploadException e) {
        return switch (e.error()) {
            case CONFLICT -> new Object[] {known(e.detail())};
            case QUOTA_COLLECTIONS -> new Object[] {known(e.limit())};
            case QUOTA_BYTES -> new Object[] {megabytes(e.value()), megabytes(e.limit())};
            case QUOTA_GLOBAL, RATE_LIMITED -> new Object[] {
                e.retryAfter() < 0 ? "?" : String.valueOf(Math.max(1, (e.retryAfter() + 59) / 60))};
            default -> new Object[0];
        };
    }

    private static String known(long number) {
        return number < 0 ? "?" : String.valueOf(number);
    }

    private static String megabytes(long bytes) {
        return bytes < 0 ? "?" : String.format(Locale.ROOT, "%.1f", bytes / (double) MIB);
    }

    /** The text of an error, for a dialog or a toast. */
    public static MutableComponent message(UploadException e) {
        if (e.error() == ApiError.INVALID_FILE) {
            return Component.translatable(key(e.error()),
                    Component.translatable(ruleKey(e.rule() != null ? e.rule() : ContentError.UNSPECIFIED)));
        }
        return Component.translatable(key(e.error()), arguments(e));
    }
}
