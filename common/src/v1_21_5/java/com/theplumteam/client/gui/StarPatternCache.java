package com.theplumteam.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a pre-generated tiled star pattern for optimal rendering performance.
 * Instead of rendering 700+ tiles per frame, we render from one large pre-tiled texture.
 * The texture was pre-generated externally to eliminate runtime generation overhead.
 */
public class StarPatternCache {
    private static final ResourceLocation STAR_PATTERN_CACHE = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "textures/gui/background/star_pattern_cache_generated.png");
    private static final int TILE_SIZE = 55; // Match the original tile size
    private static final int CACHE_TILES_WIDTH = 64; // Pre-generated texture has 64 tiles width
    private static final int CACHE_TILES_HEIGHT = 32; // Pre-generated texture has 32 tiles height

    private static DynamicTexture cachedTexture = null;
    private static ResourceLocation cachedTextureLocation = null;
    private static int cachedTextureWidth = 0;
    private static int cachedTextureHeight = 0;

    /**
     * Initialize by loading the pre-generated cached texture
     */
    public static void initialize() {
        if (cachedTexture != null) {
            return; // Already initialized
        }

        try {
            Minecraft mc = Minecraft.getInstance();

            // Load the pre-generated star pattern cache texture
            Resource resource = mc.getResourceManager().getResource(STAR_PATTERN_CACHE).orElseThrow();
            NativeImage cachedImage;
            try (InputStream stream = resource.open()) {
                cachedImage = NativeImage.read(stream);
            }

            // Store dimensions
            cachedTextureWidth = cachedImage.getWidth();
            cachedTextureHeight = cachedImage.getHeight();

            // Upload to GPU
            // In 1.21.5+, DynamicTexture constructor requires a name parameter
            cachedTextureLocation = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "star_cache");
            cachedTexture = new DynamicTexture(() -> cachedTextureLocation.toString(), cachedImage);
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);

            // In 1.21.5+, use AbstractTexture.setFilter() instead of direct OpenGL calls
            // true = bilinear filtering (GL_LINEAR), false = mipmap disabled
            cachedTexture.setFilter(true, false);

            BlockPopsMod.logDebug("Star pattern cache loaded: {}x{} (pre-generated texture with linear filtering)",
                cachedTextureWidth, cachedTextureHeight);

        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to load star pattern cache", e);
        }
    }

    /**
     * Get the cached texture location
     */
    public static ResourceLocation getTextureLocation() {
        if (cachedTextureLocation == null) {
            initialize();
        }
        return cachedTextureLocation;
    }

    /**
     * Get the width of the cached texture
     */
    public static int getTextureWidth() {
        if (cachedTexture == null) {
            initialize();
        }
        return cachedTextureWidth;
    }

    /**
     * Get the height of the cached texture
     */
    public static int getTextureHeight() {
        if (cachedTexture == null) {
            initialize();
        }
        return cachedTextureHeight;
    }

    /**
     * Get the tile size used for the pattern
     */
    public static int getTileSize() {
        return TILE_SIZE;
    }

    /**
     * Clean up resources
     */
    public static void cleanup() {
        if (cachedTexture != null) {
            cachedTexture.close();
            cachedTexture = null;
            cachedTextureLocation = null;
        }
    }
}
