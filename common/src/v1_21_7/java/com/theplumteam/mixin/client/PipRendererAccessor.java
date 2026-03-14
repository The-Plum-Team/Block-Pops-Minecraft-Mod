package com.theplumteam.mixin.client;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor for PictureInPictureRenderer private texture fields.
 * Using @Accessor ensures field names are properly remapped between
 * development (Mojang mappings) and production (intermediary mappings).
 */
@Mixin(PictureInPictureRenderer.class)
public interface PipRendererAccessor {

    @Accessor("texture")
    GpuTexture blockpops$getTexture();

    @Accessor("texture")
    void blockpops$setTexture(GpuTexture texture);

    @Accessor("textureView")
    GpuTextureView blockpops$getTextureView();

    @Accessor("textureView")
    void blockpops$setTextureView(GpuTextureView textureView);

    @Accessor("depthTexture")
    GpuTexture blockpops$getDepthTexture();

    @Accessor("depthTexture")
    void blockpops$setDepthTexture(GpuTexture depthTexture);

    @Accessor("depthTextureView")
    GpuTextureView blockpops$getDepthTextureView();

    @Accessor("depthTextureView")
    void blockpops$setDepthTextureView(GpuTextureView depthTextureView);
}
