package com.theplumteam.worldcontent;

/**
 * A World collection file or definition that was refused.
 *
 * The message is a short reason meant for the operator who sent it, and is safe to log,
 * show or put in a status packet: it is bounded to {@link #MAX_MESSAGE} code points, and
 * anything that came from a file passes through {@link #excerpt} first.
 */
public class WorldContentException extends Exception {
    /** The longest message, so a reason always fits a status packet's detail. */
    public static final int MAX_MESSAGE = 128;
    private static final int MAX_EXCERPT = 32;

    public WorldContentException(String message) {
        super(bound(message, MAX_MESSAGE));
    }

    public WorldContentException(String message, Throwable cause) {
        super(bound(message, MAX_MESSAGE), cause);
    }

    /** Reduces untrusted text to a short excerpt with every control character replaced. */
    public static String excerpt(String untrusted) {
        return untrusted == null ? "null" : bound(untrusted, MAX_EXCERPT);
    }

    private static String bound(String text, int max) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), max + 3));
        text.codePoints().limit(max).forEach(c -> out.appendCodePoint(
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                        || Character.getType(c) == Character.SURROGATE ? '?' : c));
        if (text.codePointCount(0, text.length()) > max) {
            out.append("...");
        }
        return out.toString();
    }
}
