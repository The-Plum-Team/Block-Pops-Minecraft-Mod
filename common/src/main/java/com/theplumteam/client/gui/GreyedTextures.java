package com.theplumteam.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Black-and-white copies of figure textures, for previewing a figure the player has not
 * discovered yet. Each copy is made once from the texture's pixels and kept.
 */
public class GreyedTextures {
    private static final Map<ResourceLocation, Optional<ResourceLocation>> GREYED = new HashMap<>();

    /**
     * Get the black-and-white copy of a texture, or null if the texture cannot be read
     */
    @Nullable
    public static ResourceLocation of(ResourceLocation texture) {
        return GREYED.computeIfAbsent(texture, key -> Optional.ofNullable(create(key))).orElse(null);
    }

    @Nullable
    private static ResourceLocation create(ResourceLocation texture) {
        Minecraft mc = Minecraft.getInstance();
        try {
            NativeImage image;
            try (InputStream stream = mc.getResourceManager().getResource(texture).orElseThrow().open();
                 NativeImage coloured = NativeImage.read(stream)) {
                // Green, the middle byte in every version's pixel layout, weighs double; red and
                // blue swap ends between versions, so they weigh the same
                image = coloured.mappedCopy(pixel -> {
                    int grey = ((pixel & 0xFF) + 2 * (pixel >> 8 & 0xFF) + (pixel >> 16 & 0xFF)) / 4;
                    return (pixel & 0xFF000000) | grey << 16 | grey << 8 | grey;
                });
            }

            ResourceLocation greyed = ResourceLocations.of(BlockPopsMod.MOD_ID,
                    "greyed/" + texture.getNamespace() + "/" + texture.getPath());
            //? if >=1.21.5 {
            /*mc.getTextureManager().register(greyed, new DynamicTexture(greyed::toString, image));
            *///? } else {
            mc.getTextureManager().register(greyed, new DynamicTexture(image));
            //? }
            return greyed;
        } catch (IOException | RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Could not grey out texture {}: {}", texture, e.toString());
            return null;
        }
    }
}
