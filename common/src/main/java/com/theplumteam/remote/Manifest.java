package com.theplumteam.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The CDN's list of every Custom collection file and its SHA-256.
 *
 * A file whose path or hash fails validation, or that belongs to no listed collection, is
 * left out and counted in {@link #rejectedEntries()} rather than failing the whole manifest,
 * so one bad entry cannot take every collection offline. A malformed document still fails.
 */
public final class Manifest {
    private static final Pattern TEXT_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final BigDecimal MAX_VERSION = BigDecimal.valueOf(Long.MAX_VALUE);

    private final String version;
    private final Map<String, ManifestEntry> collectionJsons;
    private final List<ManifestEntry> assets;
    private final int rejectedEntries;

    private Manifest(String version, Map<String, ManifestEntry> collectionJsons, List<ManifestEntry> assets,
                     int rejectedEntries) {
        this.version = version;
        this.collectionJsons = Collections.unmodifiableMap(collectionJsons);
        this.assets = List.copyOf(assets);
        this.rejectedEntries = rejectedEntries;
    }

    /**
     * Parses `{"version": ..., "collections": [{"id", "name"}], "files": [{"path", "sha256"}]}`
     * with no other keys. A path listed twice fails the manifest.
     */
    public static Manifest parse(byte[] utf8) throws RemoteException {
        JsonObject root = StrictJson.parseObject(utf8, RemoteLimits.MANIFEST_BYTES);
        StrictJson.requireKeys(root, "Manifest", "version", "collections", "files");
        String version = version(root.get("version"));
        // The display list is not trusted for anything; it only has to be well formed
        for (JsonElement element : StrictJson.array(root, "collections")) {
            JsonObject collection = StrictJson.object(element, "Manifest collection");
            StrictJson.requireKeys(collection, "Manifest collection", "id", "name");
            StrictJson.string(collection, "id");
            StrictJson.string(collection, "name");
        }

        int rejected = 0;
        Set<String> paths = new HashSet<>();
        List<ManifestEntry> listed = new ArrayList<>();
        for (JsonElement element : StrictJson.array(root, "files")) {
            JsonObject file = StrictJson.object(element, "Manifest file");
            StrictJson.requireKeys(file, "Manifest file", "path", "sha256");
            String path = StrictJson.string(file, "path");
            String sha256 = StrictJson.string(file, "sha256");
            if (!paths.add(path)) {
                throw new RemoteException("Manifest lists " + RemoteException.excerpt(path) + " twice");
            }
            if (ManifestEntry.isValidPath(path) && ManifestEntry.isValidSha256(sha256)) {
                listed.add(new ManifestEntry(path, sha256));
            } else {
                rejected++;
            }
        }

        Map<String, ManifestEntry> collectionJsons = new TreeMap<>();
        List<ManifestEntry> candidates = new ArrayList<>();
        for (ManifestEntry entry : listed) {
            String id = collectionIdOf(entry.path());
            if (id != null) {
                collectionJsons.put(id, entry);
            } else {
                candidates.add(entry);
            }
        }
        List<ManifestEntry> assets = new ArrayList<>();
        for (ManifestEntry entry : candidates) {
            if (collectionJsons.keySet().stream().anyMatch(id -> RemotePaths.isAllowedAsset(id, entry.path()))) {
                assets.add(entry);
            } else {
                rejected++;
            }
        }
        return new Manifest(version, collectionJsons, assets, rejected);
    }

    /** The manifest version, as the integer or short text the CDN wrote. */
    public String version() {
        return version;
    }

    /** Every valid collection id that has a collection JSON, sorted. */
    public Set<String> collectionIds() {
        return collectionJsons.keySet();
    }

    public Optional<ManifestEntry> collectionJson(String id) {
        return Optional.ofNullable(collectionJsons.get(id));
    }

    /**
     * Every allowlisted asset file of the collection, its collection JSON excluded.
     *
     * @throws RemoteException if the collection lists more files than {@link RemoteLimits#MAX_FILES_PER_COLLECTION}
     */
    public List<ManifestEntry> filesFor(String id) throws RemoteException {
        if (!collectionJsons.containsKey(id)) {
            return List.of();
        }
        List<ManifestEntry> files = new ArrayList<>();
        for (ManifestEntry entry : assets) {
            if (RemotePaths.isAllowedAsset(id, entry.path())) {
                files.add(entry);
            }
        }
        if (files.size() > RemoteLimits.MAX_FILES_PER_COLLECTION) {
            throw new RemoteException("Collection " + id + " lists more than "
                    + RemoteLimits.MAX_FILES_PER_COLLECTION + " files");
        }
        return files;
    }

    /** How many listed files were left out because they failed validation or the allowlist. */
    public int rejectedEntries() {
        return rejectedEntries;
    }

    private static String collectionIdOf(String path) {
        if (!path.startsWith(RemotePaths.COLLECTIONS_DIRECTORY) || !path.endsWith(".json")) {
            return null;
        }
        String id = path.substring(RemotePaths.COLLECTIONS_DIRECTORY.length(), path.length() - ".json".length());
        return RemotePaths.isValidCollectionId(id) ? id : null;
    }

    private static String version(JsonElement element) throws RemoteException {
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString() && TEXT_VERSION.matcher(primitive.getAsString()).matches()) {
                return primitive.getAsString();
            }
            if (primitive.isNumber()) {
                BigDecimal number = primitive.getAsBigDecimal();
                // Compare before converting, so a huge exponent is never expanded
                if (number.signum() >= 0 && number.compareTo(MAX_VERSION) <= 0
                        && number.stripTrailingZeros().scale() <= 0) {
                    return Long.toString(number.longValueExact());
                }
            }
        }
        throw new RemoteException("Manifest version must be a non-negative integer or a short text");
    }
}
