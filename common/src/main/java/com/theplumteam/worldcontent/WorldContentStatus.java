package com.theplumteam.worldcontent;

/**
 * What a request about World collections came to: an upload step, an edit, an import or
 * a command. The same values answer operators in chat and in the editor.
 *
 * @param detail a short reason or the name the result is about, at most
 *               {@link #MAX_DETAIL} characters with no control character; empty when
 *               there is nothing to add
 */
public record WorldContentStatus(Result result, String detail) {
    /** The longest detail, which is what the status packet carries. */
    public static final int MAX_DETAIL = 128;

    public enum Result {
        OK, READY, STORED, ALREADY_STORED, NOT_ALLOWED, INVALID, TOO_LARGE, QUOTA, BUSY, TIMED_OUT,
        UNKNOWN_COLLECTION, UNKNOWN_FIGURE, ID_TAKEN, FULL, MISSING_ASSET, DEGRADED, SAVE_FAILED
    }

    public WorldContentStatus {
        detail = bound(detail);
    }

    public static WorldContentStatus of(Result result, String detail) {
        return new WorldContentStatus(result, detail);
    }

    public static WorldContentStatus of(Result result) {
        return new WorldContentStatus(result, "");
    }

    /** A refused file or definition, with the reason its check gave. */
    public static WorldContentStatus invalid(WorldContentException e) {
        return new WorldContentStatus(Result.INVALID, e.getMessage());
    }

    public boolean isSuccess() {
        return result == Result.OK || result == Result.READY || result == Result.STORED
                || result == Result.ALREADY_STORED;
    }

    private static String bound(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_DETAIL));
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int width = Character.charCount(c);
            if (out.length() + width > MAX_DETAIL) {
                break;
            }
            boolean plain = !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                    && Character.getType(c) != Character.SURROGATE;
            out.appendCodePoint(plain ? c : '?');
            i += width;
        }
        return out.toString();
    }
}
