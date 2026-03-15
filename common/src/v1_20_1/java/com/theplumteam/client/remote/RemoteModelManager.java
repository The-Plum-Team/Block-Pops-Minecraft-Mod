package com.theplumteam.client.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theplumteam.BlockPopsMod;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.util.JsonUtil;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages loading and registering cached remote geo models into GeckoLib's model cache.
 * Similar to RemoteTextureManager but for geometry models (.geo.json files).
 */
public class RemoteModelManager {
    private static final Set<ResourceLocation> registeredModels = new HashSet<>();

    /**
     * Scans the cache directory for .geo.json model files and bakes them into
     * GeckoLib's model cache so they can be referenced by remote collections.
     * Must be called on the main thread after GeckoLib has initialized its cache.
     *
     * Expected path structure:
     *   cacheDir/assets/blockpops/geckolib/models/figure/collection/name.geo.json
     *   -> ResourceLocation key: blockpops:geo/figure/collection/name.geo.json (GeckoLib 4 format)
     */
    public static void registerCachedModels(Path cacheDir) {
        Path modelsDir = cacheDir.resolve("assets/blockpops/geckolib/models");
        if (!Files.isDirectory(modelsDir)) {
            BlockPopsMod.logDebug("No cached models directory found at {}", modelsDir);
            return;
        }

        Map<ResourceLocation, BakedGeoModel> modelCache = GeckoLibCache.getBakedModels();
        int registered = 0;

        try {
            List<Path> modelFiles = Files.walk(modelsDir)
                    .filter(p -> p.toString().endsWith(".geo.json"))
                    .collect(Collectors.toList());

            for (Path modelFile : modelFiles) {
                try {
                    // Compute ResourceLocation from file path
                    // GeckoLib 4 uses "geo/" prefix and keeps ".geo.json" extension
                    // e.g. modelsDir/figure/willowmedia/alexander.geo.json
                    //   -> relative: figure/willowmedia/alexander.geo.json
                    //   -> key: geo/figure/willowmedia/alexander.geo.json
                    Path relative = modelsDir.relativize(modelFile);
                    String relPath = relative.toString().replace('\\', '/');

                    // GeckoLib 4 cache key: "geo/" + relative path WITH .geo.json extension
                    ResourceLocation location = new ResourceLocation(
                            BlockPopsMod.MOD_ID, "geo/" + relPath);

                    // Read and parse the geo JSON
                    String jsonStr = new String(Files.readAllBytes(modelFile), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

                    // Bake the model using GeckoLib's pipeline
                    Model model = JsonUtil.GEO_GSON.fromJson(json, Model.class);
                    GeometryTree tree = GeometryTree.fromModel(model);
                    BakedGeoModel bakedModel = BakedModelFactory.getForNamespace(
                            location.getNamespace()).constructGeoModel(tree);

                    // Inject into GeckoLib's model cache
                    modelCache.put(location, bakedModel);
                    registeredModels.add(location);
                    registered++;

                    BlockPopsMod.logDebug("Registered remote model: {}", location);

                } catch (Exception e) {
                    BlockPopsMod.LOGGER.warn("Failed to load cached model {}: {}",
                            modelFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to scan cached models directory: {}", e.getMessage());
        }

        if (registered > 0) {
            BlockPopsMod.LOGGER.info("Registered {} remote geo model(s)", registered);
        }
    }

    /**
     * Removes all previously registered remote models from GeckoLib's cache.
     */
    public static void clearRegisteredModels() {
        if (registeredModels.isEmpty()) return;

        Map<ResourceLocation, BakedGeoModel> modelCache = GeckoLibCache.getBakedModels();
        for (ResourceLocation location : registeredModels) {
            modelCache.remove(location);
        }

        BlockPopsMod.logDebug("Cleared {} remote model(s)", registeredModels.size());
        registeredModels.clear();
    }
}
