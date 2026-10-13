package com.theplumteam.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which codes, collection ids and CDN paths are acceptable, and where they land on disk.
 *
 * Every Custom collection keeps the layout the CDN has always used, parameterised by its id;
 * a path outside that layout, or in another namespace, is never downloaded.
 */
public final class RemotePaths {
    public static final String MANIFEST = "manifest.json";
    public static final String CODES_DIRECTORY = "codes/";
    public static final String COLLECTIONS_DIRECTORY = "data/blockpops/collections/";

    private static final Pattern CODE = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern COLLECTION_ID = Pattern.compile("[a-z0-9_]{1,32}");
    /** Lowercase dot-separated words per segment: no empty, `.` or `..` segment, no leading dot. */
    private static final Pattern SAFE_RELATIVE = Pattern.compile("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:/[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*)*");
    private static final int MAX_PATH_LENGTH = 255;
    private static final Set<String> RESERVED_IDS = Set.of("default", "world_players");
    /** Names Windows maps to devices whatever their extension. */
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?");

    // Each group captures the collection id the file belongs to
    private static final Pattern MODEL = Pattern.compile(
            "assets/blockpops/geckolib/models/figure/([a-z0-9_]{1,32})/[a-z0-9_]{1,64}\\.geo\\.json");
    private static final Pattern TEXTURE = Pattern.compile(
            "assets/blockpops/textures/block/figure/([a-z0-9_]{1,32})/[a-z0-9_]{1,64}\\.png");
    private static final Pattern BOX = Pattern.compile(
            "assets/blockpops/textures/block/box/([a-z0-9_]{1,32})\\.png");
    private static final Pattern LOGO = Pattern.compile(
            "assets/blockpops/textures/block/box/logo/logo_([a-z0-9_]{1,32})\\.png");
    // Poses are figure_poses_<id> or figure_poses_<id>_<figure>, so this group is checked by prefix
    private static final Pattern POSES = Pattern.compile(
            "assets/blockpops/geckolib/animations/figure/figure_poses_([a-z0-9_]{1,97})\\.animation\\.json");

    private RemotePaths() {
    }

    /** @return the code trimmed and lowercased, or null if it can never be a valid code */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toLowerCase(Locale.ROOT);
        return CODE.matcher(code).matches() ? code : null;
    }

    /** Whether the id may name a Custom collection; the built-in pseudo-collections are refused. */
    public static boolean isValidCollectionId(String id) {
        return id != null && COLLECTION_ID.matcher(id).matches() && !RESERVED_IDS.contains(id);
    }

    /** Where the collection JSON of a valid collection id lives on the CDN and on disk. */
    public static String collectionJsonPath(String id) {
        if (!isValidCollectionId(id)) {
            throw new IllegalArgumentException("Invalid collection id");
        }
        return COLLECTIONS_DIRECTORY + id + ".json";
    }

    /** Whether the path is one of the asset files the collection may ship, its collection JSON excluded. */
    public static boolean isAllowedAsset(String collectionId, String path) {
        if (!isValidCollectionId(collectionId) || path == null || !isSafeRelative(path)) {
            return false;
        }
        for (Pattern exact : new Pattern[] {MODEL, TEXTURE, BOX, LOGO}) {
            Matcher matcher = exact.matcher(path);
            if (matcher.matches()) {
                return matcher.group(1).equals(collectionId);
            }
        }
        Matcher poses = POSES.matcher(path);
        if (poses.matches()) {
            String suffix = poses.group(1);
            return suffix.equals(collectionId)
                    || suffix.startsWith(collectionId + "_") && suffix.length() > collectionId.length() + 1;
        }
        return false;
    }

    /**
     * Whether the path is a conservative relative path: lowercase words, `/` separators,
     * dots only between words, no Windows device name and at most 255 characters.
     */
    public static boolean isSafeRelative(String path) {
        if (path == null || path.length() > MAX_PATH_LENGTH || !SAFE_RELATIVE.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (WINDOWS_DEVICE.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a relative path below root.
     * Fails if the result would leave root or if any existing component below root is a
     * symbolic link, junction or other special file, so a planted link cannot redirect a write.
     */
    public static Path resolveInside(Path root, String relativePath) throws IOException {
        if (!isSafeRelative(relativePath)) {
            throw new RemoteException("Unsafe path " + RemoteException.excerpt(relativePath));
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new RemoteException("Path leaves the store: " + RemoteException.excerpt(relativePath));
        }
        Path current = base;
        for (Path part : base.relativize(resolved)) {
            current = current.resolve(part);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                break;
            }
            boolean last = current.equals(resolved);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !(last ? attributes.isRegularFile() : attributes.isDirectory())) {
                throw new RemoteException("Refusing link or special file at " + RemoteException.excerpt(relativePath));
            }
        }
        return resolved;
    }
}
