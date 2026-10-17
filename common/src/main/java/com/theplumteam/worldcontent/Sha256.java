package com.theplumteam.worldcontent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The content address of a World collection file: the lowercase hex SHA-256 of its bytes.
 *
 * A sha names files on disk and runtime asset ids, so every sha that arrives from a
 * packet, the store or a record passes {@link #isValidHex} before it is used.
 */
public final class Sha256 {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    private Sha256() {
    }

    /** Whether the text is exactly 64 lowercase hex digits. */
    public static boolean isValidHex(String sha) {
        return sha != null && HEX.matcher(sha).matches();
    }

    /** The lowercase hex SHA-256 of the bytes. */
    public static String of(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** A fresh digest, for hashing bytes that arrive in parts. */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException(e);
        }
    }

    /** The lowercase hex form of a finished digest. */
    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Throws unless the text is a valid sha; what names the field in the message. */
    public static String require(String sha, String what) throws WorldContentException {
        if (!isValidHex(sha)) {
            throw new WorldContentException(what + " is not a SHA-256");
        }
        return sha;
    }
}
