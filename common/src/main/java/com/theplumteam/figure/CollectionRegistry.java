package com.theplumteam.figure;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Central registry for all figure collections.
 * Loads collection definitions from JSON files at startup.
 */
public class CollectionRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<String, FigureCollection> collections = new LinkedHashMap<>();
    private static final Map<String, FigureDefinition> figuresById = new HashMap<>();
    private static final Map<String, FigureCollection> dynamicCollections = new LinkedHashMap<>();
    private static boolean initialized = false;

    /**
     * Loads all collections from data/blockpops/collections/*.json
     * Note: Dynamic collections are preserved across reloads
     */
    public static void loadCollections(ResourceManager resourceManager) {
        // Clear only static collections, preserve dynamic ones
        collections.clear();
        figuresById.clear();

        // Re-add dynamic collections after clearing
        for (Map.Entry<String, FigureCollection> entry : dynamicCollections.entrySet()) {
            registerCollectionInternal(entry.getValue(), false);
        }

        try {
            // Find all collection JSON files
            Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                    "collections",
                    location -> location.getPath().endsWith(".json")
            );

            BlockPopsMod.logDebug("Loading figure collections...");

            for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
                ResourceLocation location = entry.getKey();

                try (InputStream stream = entry.getValue().open();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    FigureCollection collection = FigureCollection.fromJson(json);

                    // Register collection
                    collections.put(collection.getId(), collection);

                    // Register all figures with collectionId:figureId key
                    for (FigureDefinition figure : collection.getFigures()) {
                        String fullId = collection.getId() + ":" + figure.getId();
                        figuresById.put(fullId, figure);
                    }

                    BlockPopsMod.logDebug("Loaded collection '{}' with {} figures from {}",
                            collection.getName(), collection.getFigures().size(), location);

                } catch (Exception e) {
                    BlockPopsMod.LOGGER.error("Failed to load collection from {}: {}", location, e.getMessage());
                }
            }

            initialized = true;
            BlockPopsMod.logDebug("Loaded {} collections with {} total figures",
                    collections.size(), figuresById.size());

        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to load collections: {}", e.getMessage());
        }
    }

    /**
     * Gets a collection by ID
     */
    public static Optional<FigureCollection> getCollection(String collectionId) {
        return Optional.ofNullable(collections.get(collectionId));
    }

    /**
     * Gets a figure using the format "collectionId:figureId"
     */
    public static Optional<FigureDefinition> getFigure(String collectionId, String figureId) {
        String fullId = collectionId + ":" + figureId;
        return Optional.ofNullable(figuresById.get(fullId));
    }

    /**
     * Gets all registered collections
     */
    public static Collection<FigureCollection> getAllCollections() {
        return Collections.unmodifiableCollection(collections.values());
    }

    /**
     * Gets all collection IDs
     */
    public static Set<String> getCollectionIds() {
        return Collections.unmodifiableSet(collections.keySet());
    }

    /**
     * Checks if a collection exists
     */
    public static boolean hasCollection(String collectionId) {
        return collections.containsKey(collectionId);
    }

    /**
     * Checks if collections have been loaded
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the default collection (first one loaded, or empty if none)
     */
    public static Optional<FigureCollection> getDefaultCollection() {
        // Try to get "default" collection first
        if (collections.containsKey("default")) {
            return Optional.of(collections.get("default"));
        }
        // Otherwise return first collection
        return collections.values().stream().findFirst();
    }

    /**
     * Registers a dynamic collection (e.g., world players collection).
     * Dynamic collections are preserved across resource reloads.
     *
     * @param collection The collection to register
     */
    public static void registerDynamicCollection(FigureCollection collection) {
        dynamicCollections.put(collection.getId(), collection);
        registerCollectionInternal(collection, true);
    }

    /**
     * Internal method to register a collection to the main maps
     *
     * @param collection The collection to register
     * @param isDynamic Whether this is a dynamic collection
     */
    private static void registerCollectionInternal(FigureCollection collection, boolean isDynamic) {
        collections.put(collection.getId(), collection);

        // Register all figures with collectionId:figureId key
        for (FigureDefinition figure : collection.getFigures()) {
            String fullId = collection.getId() + ":" + figure.getId();
            figuresById.put(fullId, figure);
        }

        if (isDynamic) {
            BlockPopsMod.logDebug("Registered dynamic collection '{}' with {} figures",
                    collection.getName(), collection.getFigures().size());
        }
    }
}
