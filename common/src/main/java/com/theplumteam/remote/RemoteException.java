package com.theplumteam.remote;

import java.io.IOException;

/**
 * A Custom collection file that could not be fetched, read or trusted.
 *
 * The message is always short and safe to log or show: anything that came from the
 * network passes through {@link #excerpt} first, so no remote text reaches it raw.
 */
public class RemoteException extends IOException {
    private static final int MAX_MESSAGE = 200;
    private static final int MAX_EXCERPT = 64;

    public RemoteException(String message) {
        super(bound(message, MAX_MESSAGE));
    }

    public RemoteException(String message, Throwable cause) {
        super(bound(message, MAX_MESSAGE), cause);
    }

    /** Reduces untrusted text to a short excerpt with every control character replaced. */
    public static String excerpt(String untrusted) {
        return untrusted == null ? "null" : bound(untrusted, MAX_EXCERPT);
    }

    private static String bound(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max + 3));
        text.codePoints().limit(max).forEach(c -> out.appendCodePoint(
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c));
        if (text.codePointCount(0, text.length()) > max) {
            out.append("...");
        }
        return out.toString();
    }
}
