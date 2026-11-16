package com.theplumteam.worldcontent;

import com.theplumteam.remote.player.ContentError;

import java.util.Arrays;

/**
 * The one spelling of a model or animation file that players publish: the same JSON with every
 * space, tab and line break between its tokens taken out, and each token left exactly as it was
 * written. Numbers are never written again and keys never reordered, so every host gives the
 * same bytes, and a file that is its own canonical form has no room for anything but its JSON.
 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    /**
     * The canonical form of a strict JSON object of at most maxBytes, whose numbers keep to the
     * bounds of what players publish. The result is the input itself when it is canonical already.
     */
    public static byte[] minify(byte[] utf8, int maxBytes) throws WorldContentException {
        JsonFields.parsePlayer(utf8, maxBytes);
        return strip(utf8);
    }

    /** Throws unless bytes already known to be strict JSON are their own canonical form. */
    public static void requireCanonical(byte[] strictJson) throws WorldContentException {
        if (strip(strictJson) != strictJson) {
            throw new WorldContentException(ContentError.JSON_NOT_CANONICAL,
                    "JSON is not canonical: it has spaces or line breaks between its tokens");
        }
    }

    // In strict JSON only tokens and these four bytes occur outside a string
    private static byte[] strip(byte[] strictJson) {
        int kept = 0;
        boolean inString = false;
        boolean escaped = false;
        byte[] out = new byte[strictJson.length];
        for (byte b : strictJson) {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
            } else if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            } else if (b == '"') {
                inString = true;
            }
            out[kept++] = b;
        }
        return kept == strictJson.length ? strictJson : Arrays.copyOf(out, kept);
    }
}
