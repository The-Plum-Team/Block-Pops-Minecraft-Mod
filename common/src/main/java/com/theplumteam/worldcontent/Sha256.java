package com.theplumteam.worldcontent;

import com.theplumteam.remote.player.ContentError;

/**
 * The content address of a file: the lowercase hex SHA-256 of its bytes.
 *
 * A sha names files on disk and runtime asset ids, so every sha that arrives from a
 * packet, the store or a record passes {@link #isValidHex} before it is used.
 *
 * <p>Hashing itself is the host's: the game hashes with the JDK ({@link JdkSha256}), and the
 * content core, which also runs where the JDK's digests are not there, takes an instance of
 * this where it needs one.
 */
public interface Sha256 {
    /** The lowercase hex SHA-256 of the bytes. */
    String hex(byte[] bytes);

    /** Whether the text is exactly 64 lowercase hex digits. */
    static boolean isValidHex(String sha) {
        if (sha == null || sha.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = sha.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** Throws unless the text is a valid sha; what names the field in the message. */
    static String require(String sha, String what) throws WorldContentException {
        if (!isValidHex(sha)) {
            throw new WorldContentException(ContentError.SHA256, what + " is not a SHA-256");
        }
        return sha;
    }
}
