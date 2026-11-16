package com.theplumteam.remote.player;

import com.theplumteam.remote.RemotePaths;

/**
 * The names of player collections: the ones players publish themselves.
 *
 * <p>An id is {@code p_} and twelve characters of {@code a-z} and {@code 2-7}. It has the shape
 * of a Custom collection id, so everything that holds a collection id holds it as it is, and
 * the prefix is reserved both ways: no official collection has a {@code p_} id (the official
 * tree refuses one, see {@link RemotePaths#isValidCollectionId}), and no player collection has
 * any other.
 *
 * <p>A code is two words and two digits, such as {@code maple-orbit-42}. It fits the grammar of
 * a collection code, and no official code takes that shape, so the shape alone says where a code
 * is looked up.
 */
public final class PlayerIds {
    public static final String PREFIX = RemotePaths.PLAYER_PREFIX;
    private static final int ID_LENGTH = 14;
    private static final int WORD_MIN = 3;
    private static final int WORD_MAX = 8;

    private PlayerIds() {
    }

    /** Whether the id names a player collection: exactly {@code p_[a-z2-7]{12}}. */
    public static boolean isPlayerId(String id) {
        if (id == null || id.length() != ID_LENGTH || !id.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < ID_LENGTH; i++) {
            char c = id.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '2' || c > '7')) {
                return false;
            }
        }
        return true;
    }

    /** Whether the id is in the player family's reserved space, valid or not. */
    public static boolean isReserved(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** Whether the code has the player shape: exactly {@code [a-z]{3,8}-[a-z]{3,8}-[1-9][0-9]}. */
    public static boolean isPlayerCode(String code) {
        if (code == null) {
            return false;
        }
        int first = word(code, 0);
        if (first < 0) {
            return false;
        }
        int second = word(code, first + 1);
        if (second < 0 || code.length() != second + 3) {
            return false;
        }
        char tens = code.charAt(second + 1);
        char units = code.charAt(second + 2);
        return tens >= '1' && tens <= '9' && units >= '0' && units <= '9';
    }

    // The index of the hyphen that ends a word of 3 to 8 letters starting at from, or -1
    private static int word(String code, int from) {
        int at = from;
        while (at < code.length() && code.charAt(at) >= 'a' && code.charAt(at) <= 'z') {
            at++;
        }
        int length = at - from;
        return length >= WORD_MIN && length <= WORD_MAX && at < code.length() && code.charAt(at) == '-' ? at : -1;
    }
}
