package com.theplumteam.worldcontent;

/**
 * Every cap on World collections, checked the same way on the server and on the client.
 *
 * Byte quotas bound what is stored and sent; the decoded budgets bound what a client
 * holds once the files are loaded, which can be far larger than the files themselves.
 */
public final class WorldContentLimits {
    private static final int KIB = 1024;
    private static final int MIB = 1024 * KIB;

    public static final int MAX_COLLECTIONS = 32;
    /** Figures in one collection, retired ones included. */
    public static final int MAX_FIGURES = 128;
    /** Purged collection ids kept so they are never created again. */
    public static final int MAX_RETIRED_IDS = 256;

    /** Skin and box textures are exactly this wide and tall. */
    public static final int SKIN_SIZE = 64;
    public static final int SKIN_BYTES = 64 * KIB;
    public static final int BOX_SIZE = 64;
    public static final int BOX_BYTES = 64 * KIB;
    public static final int LOGO_MIN_SIZE = 16;
    public static final int LOGO_MAX_SIZE = 512;
    public static final int LOGO_BYTES = 256 * KIB;
    public static final int MODEL_TEXTURE_MIN_SIZE = 16;
    public static final int MODEL_TEXTURE_MAX_SIZE = 512;
    public static final int MODEL_TEXTURE_BYTES = 512 * KIB;
    /** The smallest and largest side any stored PNG may have, whatever its role. */
    public static final int PNG_MIN_SIZE = 16;
    public static final int PNG_MAX_SIZE = 512;
    /** The largest PNG of any role. */
    public static final int PNG_BYTES = 512 * KIB;

    public static final int GEO_BYTES = 512 * KIB;
    public static final int GEO_MAX_BONES = 128;
    public static final int GEO_MAX_CUBES = 2048;
    public static final int GEO_MAX_LOCATORS = 64;
    /** The texture size a model may declare, per side. */
    public static final int GEO_MAX_TEXTURE_SIZE = 1024;

    public static final int ANIMATION_BYTES = 256 * KIB;
    public static final int ANIMATION_MAX_KEYFRAMES = 4096;
    public static final int ANIMATION_MAX_ANIMATIONS = 16;
    public static final int ANIMATION_MAX_BONES = 128;
    public static final int ANIMATION_MAX_TIME_KEYS = 512;

    public static final int MAX_BLOBS = 1024;
    /** Bytes of every stored file, referenced or not. */
    public static final long MAX_STORED_BYTES = 64L * MIB;
    /** Bytes of the distinct files one collection references. */
    public static final long MAX_COLLECTION_BYTES = 16L * MIB;

    /** Pixels of the distinct PNGs any record references, about 32 MiB as RGBA. */
    public static final long MAX_DECODED_PIXELS = 8_388_608L;
    /** Cubes of the distinct models any record references. */
    public static final long MAX_DECODED_CUBES = 16_384L;
    /** Keyframes of the distinct animations any record references. */
    public static final long MAX_DECODED_KEYFRAMES = 65_536L;

    public static final int STORE_JSON_BYTES = 4 * MIB;
    /** UTF-8 bytes of one record, which is also its size on the wire. */
    public static final int RECORD_BYTES = 98_304;

    /** Characters of a collection or figure name, and of an author. */
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_AUTHOR_LENGTH = 48;
    /** The highest revision a record can reach. */
    public static final int MAX_REVISION = Integer.MAX_VALUE;

    private WorldContentLimits() {
    }
}
