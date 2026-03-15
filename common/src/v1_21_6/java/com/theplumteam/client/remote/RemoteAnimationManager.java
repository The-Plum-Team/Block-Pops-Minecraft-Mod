package com.theplumteam.client.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theplumteam.BlockPopsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import software.bernie.geckolib.cache.GeckoLibResources;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages loading and registering cached remote animation files into GeckoLib's animation cache.
 * Similar to RemoteModelManager but for animation (.animation.json) files.
 */
public class RemoteAnimationManager {
    private static final Set<ResourceLocation> registeredAnimations = new HashSet<>();

    /**
     * Scans the cache directory for .animation.json files and bakes them into
     * GeckoLib's animation cache so they can be referenced by remote collections.
     * Must be called on the main thread after GeckoLib has initialized its cache.
     *
     * Expected path structure:
     *   cacheDir/assets/blockpops/geckolib/animations/figure/name.animation.json
     *   -> ResourceLocation key: blockpops:figure/name
     */
    public static void registerCachedAnimations(Path cacheDir) {
        Path animationsDir = cacheDir.resolve("assets/blockpops/geckolib/animations");
        if (!Files.isDirectory(animationsDir)) {
            BlockPopsMod.logDebug("No cached animations directory found at {}", animationsDir);
            return;
        }

        Map<ResourceLocation, BakedAnimations> animationCache = GeckoLibResources.getBakedAnimations();
        int registered = 0;

        try {
            var animFiles = Files.walk(animationsDir)
                    .filter(p -> p.toString().endsWith(".animation.json"))
                    .toList();

            for (Path animFile : animFiles) {
                try {
                    // Compute ResourceLocation from file path
                    // e.g. animationsDir/figure/figure_poses_willowmedia_skin.animation.json
                    //   -> relative: figure/figure_poses_willowmedia_skin.animation.json
                    //   -> stripped: figure/figure_poses_willowmedia_skin
                    Path relative = animationsDir.relativize(animFile);
                    String relPath = relative.toString().replace('\\', '/');

                    // Strip .animation.json suffix
                    if (relPath.endsWith(".animation.json")) {
                        relPath = relPath.substring(0, relPath.length() - 15);
                    }

                    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                            BlockPopsMod.MOD_ID, relPath);

                    // Read and parse the animation JSON
                    String jsonStr = Files.readString(animFile, StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

                    // Bake the animations using GeckoLib's pipeline
                    BakedAnimations bakedAnimations = KeyFramesAdapter.GEO_GSON.fromJson(
                            GsonHelper.getAsJsonObject(json, "animations"), BakedAnimations.class);

                    // Inject into GeckoLib's animation cache
                    animationCache.put(location, bakedAnimations);
                    registeredAnimations.add(location);
                    registered++;

                    BlockPopsMod.logDebug("Registered remote animation: {}", location);

                } catch (Exception e) {
                    BlockPopsMod.LOGGER.warn("Failed to load cached animation {}: {}",
                            animFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to scan cached animations directory: {}", e.getMessage());
        }

        if (registered > 0) {
            BlockPopsMod.LOGGER.info("Registered {} remote animation(s)", registered);
        }
    }

    /**
     * Removes all previously registered remote animations from GeckoLib's cache.
     */
    public static void clearRegisteredAnimations() {
        if (registeredAnimations.isEmpty()) return;

        Map<ResourceLocation, BakedAnimations> animationCache = GeckoLibResources.getBakedAnimations();
        for (ResourceLocation location : registeredAnimations) {
            animationCache.remove(location);
        }

        BlockPopsMod.logDebug("Cleared {} remote animation(s)", registeredAnimations.size());
        registeredAnimations.clear();
    }
}
