package com.theplumteam.remote;

/**
 * Text handling the content core does itself, so that every host of it reads text the same way:
 * a host's own UTF-8 decoder and its idea of a blank string are not the JDK's everywhere.
 */
public final class CoreText {
    private CoreText() {
    }

    /**
     * Decodes UTF-8 as the JDK's decoder does when told to report every error: overlong forms,
     * lead bytes that start no sequence, encoded surrogates, code points above U+10FFFF and
     * truncated sequences are refused; noncharacters such as U+FFFE are text like any other.
     *
     * @return the text, or null when the bytes are not valid UTF-8
     */
    public static String decodeUtf8(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length);
        int i = 0;
        int n = bytes.length;
        while (i < n) {
            int b0 = bytes[i] & 0xFF;
            if (b0 < 0x80) {
                out.append((char) b0);
                i++;
                continue;
            }
            int need;
            int cp;
            int min;
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                need = 1;
                cp = b0 & 0x1F;
                min = 0x80;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                need = 2;
                cp = b0 & 0x0F;
                min = 0x800;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                need = 3;
                cp = b0 & 0x07;
                min = 0x10000;
            } else {
                return null;
            }
            if (i + need >= n) {
                return null;
            }
            for (int k = 1; k <= need; k++) {
                int b = bytes[i + k] & 0xFF;
                if ((b & 0xC0) != 0x80) {
                    return null;
                }
                cp = (cp << 6) | (b & 0x3F);
            }
            if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                return null;
            }
            out.appendCodePoint(cp);
            i += need + 1;
        }
        return out.toString();
    }

    /** Whether the text is empty or holds only white space, as {@link String#isBlank} defines it. */
    public static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (!Character.isWhitespace(c)) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }
}
