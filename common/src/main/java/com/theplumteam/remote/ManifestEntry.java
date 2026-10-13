package com.theplumteam.remote;

import java.util.regex.Pattern;

/**
 * One file the manifest lists: its CDN path and the SHA-256 its bytes must have.
 * The path always lies under `assets/blockpops/` or `data/blockpops/`.
 */
public record ManifestEntry(String path, String sha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ManifestEntry {
        if (!isValidPath(path)) {
            throw new IllegalArgumentException("Invalid manifest path");
        }
        if (!isValidSha256(sha256)) {
            throw new IllegalArgumentException("Invalid manifest sha256");
        }
    }

    static boolean isValidPath(String path) {
        return RemotePaths.isSafeRelative(path)
                && (path.startsWith("assets/blockpops/") || path.startsWith("data/blockpops/"));
    }

    /** Whether the text is a lowercase hexadecimal SHA-256. */
    static boolean isValidSha256(String sha256) {
        return sha256 != null && SHA256.matcher(sha256).matches();
    }
}
