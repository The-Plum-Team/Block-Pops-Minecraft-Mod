package com.theplumteam.client.remote;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Registers cached remote textures as DynamicTextures in Minecraft's TextureManager.
 * Must be called on the main/render thread.
 */
public class RemoteTextureManager {
    private static final Set<ResourceLocation> registeredTextures = new HashSet<>();

    /**
     * Scans the cache directory for PNG textures and registers them with Minecraft's TextureManager.
     * The cache directory mirrors the Minecraft resource structure:
     *   cache/assets/blockpops/textures/block/figure/willowmedia/thomas.png
     *   -> registered as ResourceLocation("blockpops", "textures/block/figure/willowmedia/thomas.png")
     *
     * Must be called on the main thread (Minecraft.getInstance().execute()).
     */
    public static void registerCachedTextures(Path cacheDir) {
        Path assetsDir = cacheDir.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return;

        try {
            // Walk the assets directory looking for PNG files
            Files.walk(assetsDir)
                .filter(p -> p.toString().endsWith(".png"))
                .forEach(pngFile -> {
                    try {
                        // Compute the ResourceLocation from the file path
                        // assets/blockpops/textures/block/figure/willowmedia/thomas.png
                        //        ^namespace ^--- path ----------------------------------^
                        Path relative = assetsDir.resolve("").relativize(pngFile);
                        String relStr = relative.toString().replace('\\', '/');

                        // Split namespace and path
                        int firstSlash = relStr.indexOf('/');
                        if (firstSlash < 0) return;

                        String namespace = relStr.substring(0, firstSlash);
                        String path = relStr.substring(firstSlash + 1);

                        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, path);

                        // Skip if already registered
                        if (registeredTextures.contains(location)) return;

                        // Load the PNG as a NativeImage
                        try (InputStream is = Files.newInputStream(pngFile)) {
                            NativeImage image = NativeImage.read(is);
                            DynamicTexture texture = new DynamicTexture(image);

                            // Register with Minecraft's TextureManager
                            Minecraft.getInstance().getTextureManager().register(location, texture);
                            registeredTextures.add(location);

                            BlockPopsMod.logDebug("Registered remote texture: {}", location);
                        }
                    } catch (Exception e) {
                        BlockPopsMod.LOGGER.warn("Failed to register texture {}: {}", pngFile, e.getMessage());
                    }
                });

            BlockPopsMod.LOGGER.info("Registered {} remote textures", registeredTextures.size());
        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to scan cache for textures: {}", e.getMessage());
        }
    }

    /**
     * Checks if a ResourceLocation is a remotely-loaded texture.
     */
    public static boolean isRemoteTexture(ResourceLocation location) {
        return registeredTextures.contains(location);
    }

    /**
     * Clears all registered remote textures (e.g., on disconnect or resource reload).
     */
    public static void clearRegisteredTextures() {
        for (ResourceLocation loc : registeredTextures) {
            try {
                Minecraft.getInstance().getTextureManager().release(loc);
            } catch (Exception ignored) {}
        }
        registeredTextures.clear();
    }
}
