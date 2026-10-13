package com.theplumteam.client.runtime;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textures that arrive while the game runs, uploaded under the id the collection data
 * names, so renderers use them like any other texture.
 *
 * <p>Registration and release touch the GPU and must run on the render thread. An id
 * announced with {@link #expect} is pending until registered; renderers draw their
 * fallback texture meanwhile.
 */
public final class RuntimeTextures {
    private static final Map<ResourceLocation, DynamicTexture> TEXTURES = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> PENDING = ConcurrentHashMap.newKeySet();

    private RuntimeTextures() {
    }

    public static void expect(Collection<ResourceLocation> ids) {
        PENDING.addAll(ids);
    }

    public static boolean isPending(@Nullable ResourceLocation id) {
        return id != null && PENDING.contains(id);
    }

    /** Uploads an image under the id, taking ownership of it. Render thread only. */
    public static void register(ResourceLocation id, NativeImage image) {
        //? if >=1.21.5 {
        /*DynamicTexture texture = new DynamicTexture(id::toString, image);
        *///? } else {
        DynamicTexture texture = new DynamicTexture(image);
        //? }
        Minecraft.getInstance().getTextureManager().register(id, texture);
        TEXTURES.put(id, texture);
        PENDING.remove(id);
    }

    /** The pixels of a texture registered here, still owned by it, or null. */
    @Nullable
    public static NativeImage image(ResourceLocation id) {
        DynamicTexture texture = TEXTURES.get(id);
        return texture != null ? texture.getPixels() : null;
    }

    /** Releases every texture registered here. Render thread only. */
    public static void clear() {
        TEXTURES.keySet().forEach(Minecraft.getInstance().getTextureManager()::release);
        TEXTURES.clear();
        PENDING.clear();
    }
}
