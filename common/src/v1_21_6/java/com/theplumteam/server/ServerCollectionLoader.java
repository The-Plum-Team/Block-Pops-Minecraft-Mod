package com.theplumteam.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side loader for remote collections.
 * Downloads collection JSONs from the CDN so the server can validate
 * drop requests, unlocks, and token consumption for remote collections.
 * Does NOT download models, textures, or animations (those are client-only).
 */
public class ServerCollectionLoader {
    private static final Gson GSON = new Gson();
    private static final String CDN_BASE_URL = "https://f003.backblazeb2.com/file/blockpops-assets";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;

    /**
     * Downloads and registers collection JSONs for the given enabled IDs.
     * Runs on a background thread to avoid blocking the server.
     */
    public static void loadCollections(Set<String> enabledIds) {
        if (enabledIds.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            BlockPopsMod.LOGGER.info("[Server] Loading {} remote collection(s)...", enabledIds.size());

            for (String id : enabledIds) {
                try {
                    String url = CDN_BASE_URL + "/data/blockpops/collections/" + id + ".json";
                    String json = downloadString(url);
                    if (json == null) {
                        BlockPopsMod.LOGGER.warn("[Server] Failed to download collection '{}' from CDN", id);
                        continue;
                    }

                    JsonObject jsonObject = GSON.fromJson(json, JsonObject.class);
                    FigureCollection collection = FigureCollection.fromJson(jsonObject);
                    CollectionRegistry.registerDynamicCollection(collection);
                    BlockPopsMod.LOGGER.info("[Server] Registered remote collection '{}' with {} figures",
                            collection.getName(), collection.getFigures().size());
                } catch (Exception e) {
                    BlockPopsMod.LOGGER.error("[Server] Failed to load collection '{}': {}", id, e.getMessage());
                }
            }
        });
    }

    private static String downloadString(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "BlockPops-Server/1.0");

            if (conn.getResponseCode() != 200) {
                BlockPopsMod.LOGGER.warn("[Server] HTTP {} for {}", conn.getResponseCode(), url);
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
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("[Server] Failed to download {}: {}", url, e.getMessage());
            return null;
        }
    }
}
