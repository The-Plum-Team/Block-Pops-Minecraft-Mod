package com.theplumteam.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
//? if <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
//? }
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
//? if <1.21.5 {
import org.lwjgl.opengl.GL11;
//? }

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a pre-generated tiled star pattern for optimal rendering performance.
 * Instead of rendering 700+ tiles per frame, we render from one large pre-tiled texture.
 * The texture was pre-generated externally to eliminate runtime generation overhead.
 */
public class StarPatternCache {
    private static final ResourceLocation STAR_PATTERN_CACHE = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/gui/background/star_pattern_cache_generated.png");
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

            // Upload to GPU. 1.21.5 names the texture through a supplier and owns
            // its sampler state, where earlier versions set raw GL parameters.
            //? if >=1.21.5 {
            /*cachedTextureLocation = ResourceLocations.of("blockpops", "star_cache");
            cachedTexture = new DynamicTexture(cachedTextureLocation::toString, cachedImage);
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            cachedTexture.setFilter(true, false);
            cachedTexture.setClamp(false);
            *///? } elif >=1.21.2 {
            /*cachedTexture = new DynamicTexture(cachedImage);
            cachedTextureLocation = ResourceLocations.of("blockpops", "star_cache");
            mc.getTextureManager().register(cachedTextureLocation, cachedTexture);
            applyLinearRepeat();
            *///? } else {
            cachedTexture = new DynamicTexture(cachedImage);
            cachedTextureLocation = mc.getTextureManager().register("blockpops_star_cache", cachedTexture);
            applyLinearRepeat();
            //? }

            BlockPopsMod.logDebug("Star pattern cache loaded: {}x{} (pre-generated texture with linear filtering)",
                cachedTextureWidth, cachedTextureHeight);

        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to load star pattern cache", e);
        }
    }

    // Linear filtering keeps the sub-pixel scroll smooth and repeat wrapping keeps
    // the tiling seamless. Below 1.21.5 both are raw GL texture parameters.
    //? if <1.21.5 {
    private static void applyLinearRepeat() {
        GlStateManager._bindTexture(cachedTexture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
    }
    //? }

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
