package com.theplumteam.client.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

/**
 * Manages downloading and caching of remote BlockPops assets from Cloudflare R2.
 * Only downloads collections that the server admin has explicitly enabled.
 */
public class RemoteAssetManager {
    private static final Gson GSON = new Gson();
    private static final String CDN_BASE_URL = "https://f003.backblazeb2.com/file/blockpops-assets";
    private static final String MANIFEST_PATH = "manifest.json";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;

    private static Path cacheDir;
    private static boolean initialized = false;
    private static JsonObject cachedManifest = null;
    private static final AtomicBoolean syncing = new AtomicBoolean(false);
    private static String lastSyncError = null;

    /**
     * Initialize the cache directory under the game directory.
     */
    public static void init() {
        if (initialized) return;

        cacheDir = Minecraft.getInstance().gameDirectory.toPath().resolve("blockpops-cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to create blockpops-cache directory: {}", e.getMessage());
        }

        initialized = true;
        BlockPopsMod.logDebug("RemoteAssetManager initialized, cache at: {}", cacheDir);
    }

    /**
     * Result of a code lookup. Contains the collection ID and display name, or an error.
     */
    public record CodeResult(String id, String name, @Nullable String error) {
        public CodeResult(String id, String name) {
            this(id, name, null);
        }
        public static CodeResult error(String error) {
            return new CodeResult(null, null, error);
        }
        public boolean isError() {
            return error != null;
        }
        public boolean isSuccess() {
            return id != null && error == null;
        }
    }

    /**
     * Looks up a collection by its access code on the CDN.
     * Fetches {@code CDN/codes/<code>.json} which contains {@code {"id": "...", "name": "..."}}.
     * The code is case-insensitive (lowercased before lookup).
     * Runs on a background thread.
     *
     * @return CodeResult with collection info, error result if CDN unreachable, or null if code not found
     */
    public static CompletableFuture<CodeResult> fetchCollectionByCode(String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String normalizedCode = code.trim().toLowerCase(Locale.ROOT);
                if (normalizedCode.isEmpty()) return null;

                String url = CDN_BASE_URL + "/codes/" + normalizedCode + ".json";
                String json = downloadString(url);
                if (json == null) return null;

                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                if (obj.has("id") && obj.has("name")) {
                    return new CodeResult(obj.get("id").getAsString(), obj.get("name").getAsString());
                }
                return null;
            } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
                BlockPopsMod.LOGGER.warn("CDN unreachable looking up code '{}': {}", code, e.getMessage());
                return CodeResult.error("CDN unreachable - check your connection");
            } catch (Exception e) {
                BlockPopsMod.LOGGER.warn("Failed to look up collection code '{}': {}", code, e.getMessage());
                return CodeResult.error("Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Fetches the remote manifest and returns a list of available collection IDs.
     * Used internally for downloading files. The Settings UI uses code-based lookup instead.
     * Runs on a background thread.
     */
    public static CompletableFuture<List<String>> fetchAvailableCollections() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String manifestJson = downloadString(CDN_BASE_URL + "/" + MANIFEST_PATH);
                if (manifestJson == null) {
                    // Try cached manifest
                    if (cachedManifest != null) {
                        return extractCollectionIds(cachedManifest);
                    }
                    return Collections.emptyList();
                }

                cachedManifest = GSON.fromJson(manifestJson, JsonObject.class);

                // Also save to disk for offline access
                if (cacheDir != null) {
                    try {
                        Files.writeString(cacheDir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }

                return extractCollectionIds(cachedManifest);
            } catch (Exception e) {
                BlockPopsMod.LOGGER.warn("Failed to fetch remote manifest: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    /**
     * Downloads and registers only the collections that the server has enabled.
     */
    public static void syncEnabledCollections(Set<String> enabledIds) {
        syncEnabledCollections(enabledIds, null);
    }

    /**
     * Downloads and registers only the collections that the server has enabled.
     * @param onComplete optional callback run on main thread when sync finishes (success or failure)
     */
    public static void syncEnabledCollections(Set<String> enabledIds, @Nullable Runnable onComplete) {
        if (!initialized || enabledIds.isEmpty()) {
            if (onComplete != null) Minecraft.getInstance().execute(onComplete);
            return;
        }
        if (!syncing.compareAndSet(false, true)) {
            BlockPopsMod.LOGGER.debug("Remote sync already in progress, skipping duplicate call");
            if (onComplete != null) Minecraft.getInstance().execute(onComplete);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                lastSyncError = null;
                BlockPopsMod.LOGGER.info("Syncing {} enabled remote collection(s)...", enabledIds.size());

                // 1. Get manifest (from cache or remote)
                JsonObject manifest = getManifest();
                if (manifest == null) {
                    BlockPopsMod.LOGGER.warn("Could not get remote manifest");
                    lastSyncError = "Could not reach CDN - using cached data";
                    // Try loading from cache anyway
                    loadCachedCollections(enabledIds);
                    return;
                }

                // 2. Download files for enabled collections only
                JsonArray files = manifest.getAsJsonArray("files");
                int downloaded = 0;
                int skipped = 0;
                int failed = 0;

                for (JsonElement fileElement : files) {
                    JsonObject fileInfo = fileElement.getAsJsonObject();
                    String filePath = fileInfo.get("path").getAsString();
                    String expectedSha256 = fileInfo.get("sha256").getAsString();

                    // Only download files belonging to enabled collections
                    if (!isFileForEnabledCollection(filePath, enabledIds)) {
                        continue;
                    }

                    Path localFile = cacheDir.resolve(filePath);

                    // Check if file already exists with correct hash
                    if (Files.exists(localFile) && sha256(localFile).equals(expectedSha256)) {
                        skipped++;
                        continue;
                    }

                    // Download file
                    if (downloadFile(CDN_BASE_URL + "/" + filePath, localFile)) {
                        String actualHash = sha256(localFile);
                        if (!actualHash.equals(expectedSha256)) {
                            BlockPopsMod.LOGGER.warn("Hash mismatch for {} (expected={}, actual={}), keeping file",
                                    filePath, expectedSha256.substring(0, 8), actualHash.substring(0, 8));
                        }
                        downloaded++;
                    } else {
                        failed++;
                    }
                }

                BlockPopsMod.LOGGER.info("Remote sync complete: {} downloaded, {} cached, {} failed", downloaded, skipped, failed);

                if (failed > 0) {
                    lastSyncError = failed + " file(s) failed to download";
                }

                // 3. Load and register the enabled collections
                loadCachedCollections(enabledIds);

            } catch (Exception e) {
                BlockPopsMod.LOGGER.error("Failed to sync remote collections: {}", e.getMessage());
                lastSyncError = "Sync failed: " + e.getMessage();
            } finally {
                syncing.set(false);
                if (onComplete != null) {
                    Minecraft.getInstance().execute(onComplete);
                }
            }
        });
    }

    /**
     * Check if a file path belongs to one of the enabled collections.
     * Files are considered belonging to a collection if their path contains the collection ID.
     */
    private static boolean isFileForEnabledCollection(String filePath, Set<String> enabledIds) {
        // Collection JSON files: data/blockpops/collections/<id>.json
        if (filePath.startsWith("data/blockpops/collections/")) {
            String filename = filePath.substring(filePath.lastIndexOf('/') + 1);
            String id = filename.replace(".json", "");
            return enabledIds.contains(id);
        }
        // Asset files: assets/blockpops/textures/block/figure/<id>/ or box/<id>.png or box/logo/logo_<id>.png etc.
        for (String id : enabledIds) {
            if (filePath.contains("/" + id + "/") || filePath.contains("/" + id + ".")
                    || filePath.contains("_" + id + ".") || filePath.contains("_" + id + "_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the manifest, first trying cache, then downloading.
     */
    private static JsonObject getManifest() {
        if (cachedManifest != null) return cachedManifest;

        // Try disk cache
        if (cacheDir != null) {
            Path manifestFile = cacheDir.resolve("manifest.json");
            if (Files.exists(manifestFile)) {
                try {
                    String content = Files.readString(manifestFile, StandardCharsets.UTF_8);
                    cachedManifest = GSON.fromJson(content, JsonObject.class);
                    return cachedManifest;
                } catch (Exception ignored) {}
            }
        }

        // Download
        try {
            String manifestJson = downloadString(CDN_BASE_URL + "/" + MANIFEST_PATH);
            if (manifestJson != null) {
                cachedManifest = GSON.fromJson(manifestJson, JsonObject.class);
                if (cacheDir != null) {
                    try {
                        Files.writeString(cacheDir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }
                return cachedManifest;
            }
        } catch (IOException e) {
            BlockPopsMod.LOGGER.warn("Failed to download {}: {}", MANIFEST_PATH, e.getMessage());
        }

        return null;
    }

    /**
     * Extracts collection IDs from a manifest JSON.
     */
    private static List<String> extractCollectionIds(JsonObject manifest) {
        List<String> ids = new ArrayList<>();
        if (manifest.has("collections")) {
            JsonArray collections = manifest.getAsJsonArray("collections");
            for (JsonElement el : collections) {
                JsonObject col = el.getAsJsonObject();
                if (col.has("id")) {
                    ids.add(col.get("id").getAsString());
                }
            }
        }
        return ids;
    }

    /**
     * Loads cached collection JSONs for the given enabled IDs and registers them.
     */
    private static void loadCachedCollections(Set<String> enabledIds) {
        if (cacheDir == null) return;

        Path collectionsDir = cacheDir.resolve("data/blockpops/collections");
        if (!Files.isDirectory(collectionsDir)) return;

        try {
            List<FigureCollection> collections = new ArrayList<>();
            File[] jsonFiles = collectionsDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles == null) return;

            for (File jsonFile : jsonFiles) {
                String id = jsonFile.getName().replace(".json", "");
                if (!enabledIds.contains(id)) continue;

                try {
                    String content = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
                    JsonObject json = GSON.fromJson(content, JsonObject.class);
                    FigureCollection collection = FigureCollection.fromJson(json);
                    collections.add(collection);
                    BlockPopsMod.LOGGER.info("Loaded remote collection '{}' with {} figures",
                            collection.getName(), collection.getFigures().size());
                } catch (Exception e) {
                    BlockPopsMod.LOGGER.error("Failed to load cached collection {}: {}", jsonFile.getName(), e.getMessage());
                }
            }

            // Clear old GeckoLib caches and re-register from disk
            Minecraft.getInstance().execute(() -> {
                RemoteModelManager.clearRegisteredModels();
                RemoteAnimationManager.clearRegisteredAnimations();
                RemoteTextureManager.clearRegisteredTextures();

                RemoteModelManager.registerCachedModels(cacheDir);
                RemoteAnimationManager.registerCachedAnimations(cacheDir);
                RemoteTextureManager.registerCachedTextures(cacheDir);

                for (FigureCollection collection : collections) {
                    CollectionRegistry.registerDynamicCollection(collection);
                    BlockPopsMod.LOGGER.info("Registered remote collection: {}", collection.getId());
                }
            });
        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to load cached collections: {}", e.getMessage());
        }
    }

    /**
     * Clears the cached manifest and all downloaded files, forcing a fresh download on next sync.
     */
    public static void clearCache() {
        cachedManifest = null;

        if (cacheDir != null && Files.isDirectory(cacheDir)) {
            try {
                Files.walk(cacheDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                        });
                Files.createDirectories(cacheDir);
                BlockPopsMod.LOGGER.info("Cleared remote asset cache");
            } catch (Exception e) {
                BlockPopsMod.LOGGER.error("Failed to clear cache: {}", e.getMessage());
            }
        }

        RemoteModelManager.clearRegisteredModels();
        RemoteAnimationManager.clearRegisteredAnimations();
        RemoteTextureManager.clearRegisteredTextures();
    }

    /**
     * Invalidates the in-memory manifest without deleting cached files.
     * Forces a fresh manifest download on next sync while keeping existing assets intact.
     */
    public static void invalidateManifest() {
        cachedManifest = null;
        // Also clear the disk-cached manifest so getManifest() fetches from CDN
        if (cacheDir != null) {
            try {
                Files.deleteIfExists(cacheDir.resolve("manifest.json"));
            } catch (IOException ignored) {}
        }
        BlockPopsMod.LOGGER.info("Invalidated manifest cache (files preserved)");
    }

    /**
     * Returns the error message from the last sync attempt, or null if it succeeded.
     */
    @Nullable
    public static String getLastSyncError() {
        return lastSyncError;
    }

    public static Path getCacheDir() {
        return cacheDir;
    }

    // --- HTTP helpers ---

    private static String downloadString(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "BlockPops-Mod/1.0");

        if (conn.getResponseCode() != 200) {
            BlockPopsMod.LOGGER.warn("HTTP {} for {}", conn.getResponseCode(), url);
            return null;
        }

        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static boolean downloadFile(String url, Path target) {
        try {
            Files.createDirectories(target.getParent());

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "BlockPops-Mod/1.0");

            if (conn.getResponseCode() != 200) {
                BlockPopsMod.LOGGER.warn("HTTP {} downloading {}", conn.getResponseCode(), url);
                return false;
            }

            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Failed to download file {}: {}", url, e.getMessage());
            return false;
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
