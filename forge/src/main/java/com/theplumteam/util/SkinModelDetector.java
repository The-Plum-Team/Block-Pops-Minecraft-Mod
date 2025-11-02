package com.theplumteam.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Utility for detecting skin model type (slim/Alex vs classic/Steve)
 * by analyzing arm pixel transparency.
 *
 * Based on the algorithm from quick-skin mod.
 */
public class SkinModelDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkinModelDetector.class);

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
     * Detect skin model from a texture ResourceLocation
     * @param textureLocation The skin texture location
     * @return SLIM or CLASSIC
     */
    public static SkinModel detectSkinModel(ResourceLocation textureLocation) {
        LOGGER.info("Attempting to detect skin model for texture: {}", textureLocation);
        try {
            // Try to get texture from texture manager first (for player skins)
            var textureManager = Minecraft.getInstance().getTextureManager();
            var abstractTexture = textureManager.getTexture(textureLocation);

            LOGGER.info("Texture from manager: {}", abstractTexture);

            if (abstractTexture instanceof net.minecraft.client.renderer.texture.AbstractTexture) {
                LOGGER.info("Found texture in texture manager, attempting to get image data");
                // For player skins and other dynamic textures, try to bind and read
                try {
                    // This is a player skin or other already-loaded texture
                    // We need to read it from OpenGL
                    LOGGER.warn("Texture is loaded but we can't easily read pixel data from bound textures");
                    LOGGER.warn("Falling back to resource manager approach");
                } catch (Exception e) {
                    LOGGER.error("Error reading from texture manager", e);
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

                LOGGER.info("Successfully detected skin model from resource: {}", model);
                return model;
            } catch (Exception resourceException) {
                LOGGER.warn("Could not load texture from resource manager: {}", resourceException.getMessage());
            }

            LOGGER.warn("Could not detect skin model, defaulting to CLASSIC");
            return SkinModel.CLASSIC; // Default to classic on error
        } catch (Exception e) {
            LOGGER.error("Failed to detect skin model from texture: {}", textureLocation, e);
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
