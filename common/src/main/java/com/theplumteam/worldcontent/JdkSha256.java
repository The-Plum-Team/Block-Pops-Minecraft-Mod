package com.theplumteam.worldcontent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 by the JDK's own digest, which is how the game hashes. */
public final class JdkSha256 implements Sha256 {
    public static final JdkSha256 INSTANCE = new JdkSha256();

    private JdkSha256() {
    }

    /** The lowercase hex SHA-256 of the bytes. */
    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String hex(byte[] bytes) {
        return of(bytes);
    }
}
