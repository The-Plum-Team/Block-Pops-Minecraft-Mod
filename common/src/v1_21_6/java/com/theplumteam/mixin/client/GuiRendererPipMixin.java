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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
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

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void blockpops$registerPipRenderer(GuiRenderState guiRenderState,
                                                MultiBufferSource.BufferSource bufferSource,
                                                List<PictureInPictureRenderer<?>> renderers,
                                                CallbackInfo ci) {
        try {
            Field field = GuiRenderer.class.getDeclaredField("pictureInPictureRenderers");

            // Use Unsafe to modify the final field (Field.set doesn't work on final fields in Java 17+)
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            long offset = unsafe.objectFieldOffset(field);

            @SuppressWarnings("unchecked")
            Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> existing =
                (Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>>) unsafe.getObject(this, offset);

            Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> newMap = new HashMap<>(existing);
            newMap.put(FigurePipRenderState.class, new FigurePipRenderer(bufferSource));
            newMap.put(ItemPipRenderState.class, new ItemPipRenderer(bufferSource));

            unsafe.putObject(this, offset, Map.copyOf(newMap));
            com.theplumteam.BlockPopsMod.logDebug("GuiRendererPipMixin: Successfully registered FigurePipRenderer and ItemPipRenderer");
        } catch (Exception e) {
            // Silently skip on NeoForge (field name differs: pictureInPictureRendererPools) or on errors
            com.theplumteam.BlockPopsMod.logDebug("GuiRendererPipMixin: Failed to register PiP renderers (probably NeoForge): {}", e.getMessage());
        }
    }
}
