package com.theplumteam.remote;

/** Size caps for everything read from the CDN, checked while the bytes arrive. */
public final class RemoteLimits {
    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    public static final long CODE_BYTES = 4 * KIB;
    public static final long MANIFEST_BYTES = 256 * KIB;
    public static final long COLLECTION_JSON_BYTES = MIB;
    public static final long MODEL_BYTES = 4 * MIB;
    public static final long ANIMATION_BYTES = MIB;
    public static final long PNG_BYTES = 4 * MIB;
    /** Widest and tallest texture accepted, in pixels. */
    public static final int PNG_MAX_DIMENSION = 4096;
    /** Asset files one collection may list, besides its collection JSON. */
    public static final int MAX_FILES_PER_COLLECTION = 512;
    /** Bytes the asset files of one collection may add up to. */
    public static final long MAX_BYTES_PER_COLLECTION = 64 * MIB;

    private RemoteLimits() {
    }

    /**
     * The most bytes a file at this CDN path may have.
     *
     * @return 0 for a path that is never downloaded, so any attempt fails
     */
    public static long limitFor(String path) {
        if (path.equals(RemotePaths.MANIFEST)) {
            return MANIFEST_BYTES;
        }
        if (path.startsWith(RemotePaths.CODES_DIRECTORY) && path.endsWith(".json")) {
            return CODE_BYTES;
        }
        if (path.startsWith(RemotePaths.COLLECTIONS_DIRECTORY) && path.endsWith(".json")) {
            return COLLECTION_JSON_BYTES;
        }
        if (path.endsWith(".geo.json")) {
            return MODEL_BYTES;
        }
        if (path.endsWith(".animation.json")) {
            return ANIMATION_BYTES;
        }
        if (path.endsWith(".png")) {
            return PNG_BYTES;
        }
        return 0;
    }
}
