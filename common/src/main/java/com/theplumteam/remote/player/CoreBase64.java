package com.theplumteam.remote.player;

/**
 * Base64 in the URL-safe alphabet without padding, as the signed documents carry their bytes.
 *
 * <p>Decoding is strict, so a document has one spelling: only the 64 characters of the alphabet,
 * no padding, no line breaks, a length that a whole number of bytes gives, and no stray bits in
 * the last character. The content core has its own because a host's decoder may let some of
 * that through.
 */
public final class CoreBase64 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int[] VALUES = new int[128];

    static {
        for (int i = 0; i < VALUES.length; i++) {
            VALUES[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            VALUES[ALPHABET.charAt(i)] = i;
        }
    }

    private CoreBase64() {
    }

    /** The bytes as unpadded URL-safe Base64. */
    public static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 4 + 2) / 3);
        int at = 0;
        while (at + 3 <= bytes.length) {
            int group = (bytes[at] & 0xFF) << 16 | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF);
            out.append(ALPHABET.charAt(group >> 18)).append(ALPHABET.charAt((group >> 12) & 63))
                    .append(ALPHABET.charAt((group >> 6) & 63)).append(ALPHABET.charAt(group & 63));
            at += 3;
        }
        int left = bytes.length - at;
        if (left == 1) {
            int group = (bytes[at] & 0xFF) << 4;
            out.append(ALPHABET.charAt(group >> 6)).append(ALPHABET.charAt(group & 63));
        } else if (left == 2) {
            int group = (bytes[at] & 0xFF) << 10 | (bytes[at + 1] & 0xFF) << 2;
            out.append(ALPHABET.charAt(group >> 12)).append(ALPHABET.charAt((group >> 6) & 63))
                    .append(ALPHABET.charAt(group & 63));
        }
        return out.toString();
    }

    /** How many characters encode that many bytes. */
    public static int encodedLength(int bytes) {
        return (bytes * 4 + 2) / 3;
    }

    /**
     * The bytes the text encodes, or null when it is not the one strict spelling of any: a
     * character outside the alphabet, padding, a length no byte count gives, or bits set beyond
     * the last whole byte.
     */
    public static byte[] decode(String text) {
        if (text == null) {
            return null;
        }
        int length = text.length();
        int tail = length % 4;
        if (tail == 1) {
            return null;
        }
        byte[] out = new byte[length / 4 * 3 + (tail == 0 ? 0 : tail - 1)];
        int written = 0;
        int at = 0;
        while (at + 4 <= length) {
            int a = value(text.charAt(at));
            int b = value(text.charAt(at + 1));
            int c = value(text.charAt(at + 2));
            int d = value(text.charAt(at + 3));
            if ((a | b | c | d) < 0) {
                return null;
            }
            int group = a << 18 | b << 12 | c << 6 | d;
            out[written++] = (byte) (group >> 16);
            out[written++] = (byte) (group >> 8);
            out[written++] = (byte) group;
            at += 4;
        }
        if (tail == 2) {
            int a = value(text.charAt(at));
            int b = value(text.charAt(at + 1));
            if ((a | b) < 0 || (b & 15) != 0) {
                return null;
            }
            out[written] = (byte) (a << 2 | b >> 4);
        } else if (tail == 3) {
            int a = value(text.charAt(at));
            int b = value(text.charAt(at + 1));
            int c = value(text.charAt(at + 2));
            if ((a | b | c) < 0 || (c & 3) != 0) {
                return null;
            }
            out[written++] = (byte) (a << 2 | b >> 4);
            out[written] = (byte) (b << 4 | c >> 2);
        }
        return out;
    }

    private static int value(char c) {
        return c < 128 ? VALUES[c] : -1;
    }
}
