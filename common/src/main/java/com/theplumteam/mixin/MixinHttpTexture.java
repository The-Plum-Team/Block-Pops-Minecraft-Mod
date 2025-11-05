package com.theplumteam.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to preserve alpha channel in player skin textures.
 * By default, Minecraft strips the alpha channel from downloaded skins,
 * causing transparent pixels to render as black. This mixin prevents that.
 */
@Mixin(HttpTexture.class)
public class MixinHttpTexture {

    /**
     * Redirects the setNoAlpha call to do nothing, preserving the alpha channel.
     * This allows the second skin layer (hat/overlay) to render with proper transparency.
     */
    @Redirect(
        method = "processLegacySkin",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/NativeImage;setNoAlpha()V"
        ),
        require = 0
    )
    private void blockpops$preserveAlpha(NativeImage instance) {
        // Do nothing - keep the alpha channel intact
    }
}
