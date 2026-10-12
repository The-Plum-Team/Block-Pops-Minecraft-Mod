package com.theplumteam.client.gui;

//? if <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
//? }
import com.mojang.blaze3d.platform.NativeImage;
//? if <1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem;
//? }
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
//? if <1.21.5 {
import org.lwjgl.opengl.GL11;
//? }

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Black-and-white copies of figure textures, for previewing a figure the player has not
 * discovered yet. A copy of a resource is made once from its pixels and kept. A player's skin
 * is no resource, so its copy is made from the texture the game has loaded.
 */
public class GreyedTextures {
    // A skin that is still downloading shows as the default one first, so a copy that was not
    // read from a resource is made again this often
    private static final long REMAKE_MILLIS = 10_000;
    private static final int MAX_SKIN_SIDE = 1024;

    private record Copy(@Nullable ResourceLocation location, long remakeAt) {}

    private static final Map<ResourceLocation, Copy> GREYED = new HashMap<>();

    /**
     * Get the black-and-white copy of a texture, or null if the texture cannot be read
     */
    @Nullable
    public static ResourceLocation of(ResourceLocation texture) {
        Copy copy = GREYED.get(texture);
        if (copy == null || System.currentTimeMillis() >= copy.remakeAt()) {
            copy = create(texture);
            GREYED.put(texture, copy);
        }
        return copy.location();
    }

    private static Copy create(ResourceLocation texture) {
        Minecraft mc = Minecraft.getInstance();
        boolean resource = mc.getResourceManager().getResource(texture).isPresent();
        long remakeAt = resource ? Long.MAX_VALUE : System.currentTimeMillis() + REMAKE_MILLIS;
        try {
            NativeImage image = resource ? fromResource(mc, texture) : fromLoadedTexture(mc, texture);
            if (image == null) {
                return new Copy(null, remakeAt);
            }

            ResourceLocation greyed = ResourceLocations.of(BlockPopsMod.MOD_ID,
                    "greyed/" + texture.getNamespace() + "/" + texture.getPath());
            //? if >=1.21.5 {
            /*mc.getTextureManager().register(greyed, new DynamicTexture(greyed::toString, image));
            *///? } else {
            mc.getTextureManager().register(greyed, new DynamicTexture(image));
            //? }
            return new Copy(greyed, remakeAt);
        } catch (IOException | RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Could not grey out texture {}: {}", texture, e.toString());
            return new Copy(null, remakeAt);
        }
    }

    private static NativeImage fromResource(Minecraft mc, ResourceLocation texture) throws IOException {
        try (InputStream stream = mc.getResourceManager().getResource(texture).orElseThrow().open();
             NativeImage coloured = NativeImage.read(stream)) {
            return blackAndWhite(coloured);
        }
    }

    @Nullable
    private static NativeImage fromLoadedTexture(Minecraft mc, ResourceLocation texture) {
        AbstractTexture loaded = mc.getTextureManager().getTexture(texture);
        if (loaded instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
            return blackAndWhite(dynamic.getPixels());
        }
        //? if <1.21.5 {
        // A downloaded skin keeps no pixels of its own here, so they are read back from the GPU
        RenderSystem.bindTexture(loaded.getId());
        int width = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0 || width > MAX_SKIN_SIDE || height > MAX_SKIN_SIDE) {
            return null;
        }
        try (NativeImage coloured = new NativeImage(width, height, false)) {
            coloured.downloadTexture(0, false);
            return blackAndWhite(coloured);
        }
        //? } else {
        /*return null;
        *///? }
    }

    private static NativeImage blackAndWhite(NativeImage coloured) {
        // Green, the middle byte in every version's pixel layout, weighs double; red and blue
        // swap ends between versions, so they weigh the same
        return coloured.mappedCopy(pixel -> {
            int grey = ((pixel & 0xFF) + 2 * (pixel >> 8 & 0xFF) + (pixel >> 16 & 0xFF)) / 4;
            return (pixel & 0xFF000000) | grey << 16 | grey << 8 | grey;
        });
    }
}
