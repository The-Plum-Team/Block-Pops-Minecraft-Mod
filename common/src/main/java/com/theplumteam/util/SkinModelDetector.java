package com.theplumteam.util;

import com.theplumteam.BlockPopsMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for detecting skin model type (slim/Alex vs classic/Steve)
 * by analyzing arm pixel transparency.
 *
 * Based on the algorithm from quick-skin mod.
 */
public class SkinModelDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkinModelDetector.class);

    // Cache to avoid re-detecting the same texture multiple times
    private static final ConcurrentHashMap<ResourceLocation, SkinModel> DETECTION_CACHE = new ConcurrentHashMap<>();

    public enum SkinModel {
        CLASSIC,  // Steve model - 4-pixel wide arms
        SLIM      // Alex model - 3-pixel wide arms
    }

    public enum SkinResolution {
        LEGACY(64, 32, 1),      // Old skin format (pre-1.8)
        STANDARD(64, 64, 1),    // Modern standard skin
        HD_128(128, 64, 2),     // 2x HD
        HD_256(256, 128, 4),    // 4x HD
        HD_512(512, 256, 8),    // 8x HD
        HD_1024(1024, 512, 16), // 16x HD
        HD_2048(2048, 1024, 32); // 32x HD (max)

        private final int width;
        private final int height;
        private final int scale;

        SkinResolution(int width, int height, int scale) {
            this.width = width;
            this.height = height;
            this.scale = scale;
        }

        public int getScale() {
            return scale;
        }

        public static SkinResolution fromDimensions(int width, int height) {
            for (SkinResolution res : values()) {
                if (res.width == width && res.height == height) {
                    return res;
                }
            }
            return null;
        }
    }

    /**
     * Clear the detection cache (useful when resource packs are reloaded)
     */
    public static void clearCache() {
        DETECTION_CACHE.clear();
        BlockPopsMod.logDebug("Skin model detection cache cleared");
    }

    /**
     * Detect skin model from a texture ResourceLocation
     * @param textureLocation The skin texture location
     * @return SLIM or CLASSIC
     */
    public static SkinModel detectSkinModel(ResourceLocation textureLocation) {
        // Check cache first
        SkinModel cached = DETECTION_CACHE.get(textureLocation);
        if (cached != null) {
            return cached;
        }

        LOGGER.debug("Attempting to detect skin model for texture: {}", textureLocation);
        try {
            // First, check if this is a player skin by looking through online players
            if (Minecraft.getInstance().getConnection() != null) {
                var playerListEntries = Minecraft.getInstance().getConnection().getOnlinePlayers();
                for (var playerInfo : playerListEntries) {
                    PlayerSkin skin = playerInfo.getSkin();
                    if (skin.texture().equals(textureLocation)) {
                        // Found matching player - get their model type directly
                        SkinModel model = skin.model() == PlayerSkin.Model.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
                        BlockPopsMod.logDebug("Detected {} skin from PlayerInfo for texture: {}", model, textureLocation);
                        DETECTION_CACHE.put(textureLocation, model);
                        return model;
                    }
                }
            }

            // Try to get texture from texture manager (for downloaded player skins)
            var textureManager = Minecraft.getInstance().getTextureManager();
            var abstractTexture = textureManager.getTexture(textureLocation);

            // Handle DynamicTexture (used by QuickSkin for local skins)
            if (abstractTexture instanceof net.minecraft.client.renderer.texture.DynamicTexture dynamicTexture) {
                NativeImage image = dynamicTexture.getPixels();
                if (image != null) {
                    SkinModel model = detectSkinModel(image);
                    BlockPopsMod.logDebug("Detected {} skin from DynamicTexture for texture: {}", model, textureLocation);
                    DETECTION_CACHE.put(textureLocation, model);
                    return model;
                }
            }
            // Handle HttpTexture (standard downloaded skins)
            else if (abstractTexture instanceof net.minecraft.client.renderer.texture.HttpTexture httpTexture) {
                // This is a downloaded player skin - try to access the loaded image
                try {
                    // Use reflection to get the NativeImage from HttpTexture
                    var textureImageField = net.minecraft.client.renderer.texture.HttpTexture.class.getDeclaredField("textureImage");
                    textureImageField.setAccessible(true);
                    NativeImage image = (NativeImage) textureImageField.get(httpTexture);

                    if (image != null) {
                        SkinModel model = detectSkinModel(image);
                        BlockPopsMod.logDebug("Detected {} skin from HttpTexture for texture: {}", model, textureLocation);
                        DETECTION_CACHE.put(textureLocation, model);
                        return model;
                    }
                } catch (Exception reflectionEx) {
                    LOGGER.debug("Could not access HttpTexture image via reflection: {}", reflectionEx.getMessage());
                }
            }

            // Try resource manager (for static textures in resources)
            try {
                InputStream inputStream = Minecraft.getInstance()
                        .getResourceManager()
                        .getResource(textureLocation)
                        .orElseThrow()
                        .open();

                NativeImage image = NativeImage.read(inputStream);
                SkinModel model = detectSkinModel(image);
                image.close();
                inputStream.close();

                BlockPopsMod.logDebug("Detected {} skin for texture: {}", model, textureLocation);
                // Cache the result
                DETECTION_CACHE.put(textureLocation, model);
                return model;
            } catch (Exception resourceException) {
                LOGGER.warn("Could not load texture from resource manager: {}", resourceException.getMessage());
            }

            LOGGER.warn("Could not detect skin model, defaulting to CLASSIC");
            // Cache the default result
            DETECTION_CACHE.put(textureLocation, SkinModel.CLASSIC);
            return SkinModel.CLASSIC; // Default to classic on error
        } catch (Exception e) {
            LOGGER.error("Failed to detect skin model from texture: {}", textureLocation, e);
            // Cache the default result even on error to avoid repeated failures
            DETECTION_CACHE.put(textureLocation, SkinModel.CLASSIC);
            return SkinModel.CLASSIC;
        }
    }

    /**
     * Detect skin model from NativeImage
     * @param image The skin image
     * @return SLIM or CLASSIC
     */
    public static SkinModel detectSkinModel(NativeImage image) {
        if (image == null) {
            return SkinModel.CLASSIC;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Determine resolution and scale factor
        SkinResolution resolution = SkinResolution.fromDimensions(width, height);
        int scale = resolution != null ? resolution.getScale() : 1;

        // For legacy skins (64x32), default to classic
        if (resolution == SkinResolution.LEGACY) {
            return SkinModel.CLASSIC;
        }

        // Check right arm outer layer (x=54-55 at 1x scale, y=20-32)
        // In slim skins, this column should be mostly transparent
        int rightArmX = 54 * scale;
        int rightArmEndX = 56 * scale;
        int rightArmStartY = 20 * scale;
        int rightArmEndY = 32 * scale;

        // Also check left arm outer layer (x=46-47 at 1x scale, y=52-64)
        int leftArmX = 46 * scale;
        int leftArmEndX = 48 * scale;
        int leftArmStartY = 52 * scale;
        int leftArmEndY = 64 * scale;

        int totalPixels = 0;
        int transparentPixels = 0;

        // Check right arm column
        for (int y = rightArmStartY; y < rightArmEndY && y < height; y++) {
            for (int x = rightArmX; x < rightArmEndX && x < width; x++) {
                totalPixels++;
                int rgba = image.getPixelRGBA(x, y);

                // NativeImage uses ABGR format in memory
                // getPixelRGBA returns in RGBA format
                int alpha = (rgba >> 24) & 0xFF;
                int blue = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int red = rgba & 0xFF;

                // Consider pixel as "empty" for slim detection if:
                // 1. Alpha is very low (transparent), OR
                // 2. It's pure black (RGB 0,0,0) - often used as a mask in slim skins
                if (alpha < 10 || (red == 0 && green == 0 && blue == 0)) {
                    transparentPixels++;
                }
            }
        }

        // Check left arm column
        for (int y = leftArmStartY; y < leftArmEndY && y < height; y++) {
            for (int x = leftArmX; x < leftArmEndX && x < width; x++) {
                totalPixels++;
                int rgba = image.getPixelRGBA(x, y);

                int alpha = (rgba >> 24) & 0xFF;
                int blue = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int red = rgba & 0xFF;

                // Consider pixel as "empty" for slim detection if:
                // 1. Alpha is very low (transparent), OR
                // 2. It's pure black (RGB 0,0,0) - often used as a mask in slim skins
                if (alpha < 10 || (red == 0 && green == 0 && blue == 0)) {
                    transparentPixels++;
                }
            }
        }

        // If more than 50% of arm pixels are transparent, it's a slim model
        if (totalPixels > 0) {
            float transparentRatio = (float) transparentPixels / totalPixels;
            boolean isSlim = transparentRatio > 0.5f;

            LOGGER.debug("Skin model detection: {}% transparent pixels -> {}",
                    (int) (transparentRatio * 100), isSlim ? "slim" : "classic");

            return isSlim ? SkinModel.SLIM : SkinModel.CLASSIC;
        }

        // Default to classic if detection fails
        return SkinModel.CLASSIC;
    }
}
