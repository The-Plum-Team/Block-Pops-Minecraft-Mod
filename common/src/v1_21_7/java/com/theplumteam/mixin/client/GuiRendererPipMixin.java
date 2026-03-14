package com.theplumteam.mixin.client;

import com.theplumteam.client.renderer.FigurePipRenderState;
import com.theplumteam.client.renderer.FigurePipRenderer;
import com.theplumteam.client.renderer.ItemPipRenderState;
import com.theplumteam.client.renderer.ItemPipRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixin on GuiRenderer to register our PiP renderer on Fabric.
 * On NeoForge, this mixin silently fails (require = 0) because the constructor
 * signature differs (uses PictureInPictureRendererRegistration instead of PictureInPictureRenderer).
 * NeoForge uses RegisterPictureInPictureRenderersEvent instead.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererPipMixin {

    @Shadow @Final @Mutable
    private Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void blockpops$registerPipRenderer(GuiRenderState guiRenderState,
                                                MultiBufferSource.BufferSource bufferSource,
                                                List<PictureInPictureRenderer<?>> renderers,
                                                CallbackInfo ci) {
        try {
            Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> newMap =
                new HashMap<>(this.pictureInPictureRenderers);
            newMap.put(FigurePipRenderState.class, new FigurePipRenderer(bufferSource));
            newMap.put(ItemPipRenderState.class, new ItemPipRenderer(bufferSource));
            this.pictureInPictureRenderers = Map.copyOf(newMap);
            com.theplumteam.BlockPopsMod.logDebug("GuiRendererPipMixin: Successfully registered FigurePipRenderer and ItemPipRenderer");
        } catch (Exception e) {
            // Silently skip on NeoForge (field name differs: pictureInPictureRendererPools) or on errors
            com.theplumteam.BlockPopsMod.logDebug("GuiRendererPipMixin: Failed to register PiP renderers (probably NeoForge): {}", e.getMessage());
        }
    }
}
