package com.theplumteam.remote.player;

import com.theplumteam.remote.RemotePaths;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.Sha256;

/**
 * Where everything of a player collection lives on the CDN, below {@code p/}. Every path is
 * computed here from a code, an id, a SHA-256 and a type that were each checked first: none is
 * ever taken from a packet, a document or a file.
 */
public final class PlayerPaths {
    /** The directory players' content is published under; official content never is. */
    public static final String ROOT = "p/";
    public static final String STATUS = "p/status.json";
    public static final String REVOCATIONS = "p/revoked.json";
    /** The offline root's document, outside {@link #ROOT} so the key that writes there cannot touch it. */
    public static final String ROOT_STATUS = "root/status.json";
    private static final String CODES = "p/codes/";
    private static final String COLLECTIONS = "p/c/";

    private PlayerPaths() {
    }

    /** The signed head a code resolves to. */
    public static String head(String code) {
        if (!PlayerIds.isPlayerCode(code)) {
            throw new IllegalArgumentException("Invalid player code");
        }
        return checked(CODES + code + ".json");
    }

    /** The signed index of one revision, named by the SHA-256 of its envelope. */
    public static String index(String id, String sha) {
        return checked(directory(id) + "i/" + sha(sha) + ".json");
    }

    /** The record of one revision. */
    public static String record(String id, String sha) {
        return checked(directory(id) + "r/" + sha(sha) + ".json");
    }

    /** A file of the collection, with the extension of its type. */
    public static String asset(String id, String sha, BlobType type) {
        return checked(directory(id) + "a/" + sha(sha) + extension(type));
    }

    /** The directory that holds everything of one collection, ending in a slash. */
    public static String directory(String id) {
        if (!PlayerIds.isPlayerId(id)) {
            throw new IllegalArgumentException("Invalid player collection id");
        }
        return COLLECTIONS + id + "/";
    }

    /** The file extension a type is published with. */
    public static String extension(BlobType type) {
        return switch (type) {
            case PNG -> ".png";
            case GEO -> ".geo.json";
            case ANIMATION -> ".animation.json";
        };
    }

    private static String sha(String sha) {
        if (!Sha256.isValidHex(sha)) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return sha;
    }

    private static String checked(String path) {
        if (!RemotePaths.isSafeRelative(path)) {
            throw new IllegalArgumentException("Unsafe player path");
        }
        return path;
    }
}
