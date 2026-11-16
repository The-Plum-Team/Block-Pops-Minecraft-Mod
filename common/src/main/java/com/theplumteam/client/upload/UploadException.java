package com.theplumteam.client.upload;

import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.ContentError;
import org.jetbrains.annotations.Nullable;

/**
 * A request to the upload service that did not succeed: one error of the closed list and the
 * few bounded numbers that may come with it. The service never sends text to show, so nothing
 * here is text either; the game finds its own words for each error.
 */
public final class UploadException extends Exception {
    private final ApiError error;
    private final long detail;
    private final long retryAfter;
    private final long limit;
    private final long value;
    private final int figure;
    @Nullable
    private final String field;
    @Nullable
    private final ContentError rule;

    public UploadException(ApiError error) {
        this(error, -1, -1, -1, -1, -1, null, null);
    }

    public UploadException(ApiError error, long detail, long retryAfter, long limit, long value, int figure,
                           @Nullable String field, @Nullable ContentError rule) {
        super(error.id(), null, false, false);
        this.error = error;
        this.detail = detail;
        this.retryAfter = retryAfter;
        this.limit = limit;
        this.value = value;
        this.figure = figure;
        this.field = field;
        this.rule = rule;
    }

    public ApiError error() {
        return error;
    }

    /** A number that goes with the error, such as the head revision of a conflict; -1 when there is none. */
    public long detail() {
        return detail;
    }

    /** How many seconds to wait before asking again; -1 when the service did not say. */
    public long retryAfter() {
        return retryAfter;
    }

    public long limit() {
        return limit;
    }

    public long value() {
        return value;
    }

    /** Which figure of the record a refusal is about, counted from 0; -1 when it is about none. */
    public int figure() {
        return figure;
    }

    /** Where in the record a refusal is, as a path such as {@code /figures/3/name}; null when nowhere. */
    @Nullable
    public String field() {
        return field;
    }

    /** Which rule of the content core a file or a record broke; null when none. */
    @Nullable
    public ContentError rule() {
        return rule;
    }
}
