package com.theplumteam.client.renderer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.mixin.client.PipRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.HashMap;
import java.util.Map;

/**
 * PiP renderer that renders GeckoLib figure models to an off-screen GPU texture.
 * <p>
 * Uses a per-figure texture pool to solve the shared-texture problem: Minecraft's PiP base class
 * uses one GPU texture per renderer instance. When multiple different figures submit states to
 * the same renderer, the renders are immediate but the blits are deferred. Without pooling, all
 * blits would reference the same texture (showing only the last rendered figure).
 * <p>
 * The fix: before each state is processed, swap the base class texture fields to point to
 * per-figure cached textures. Each figure's blit then references its own texture.
 */
public class FigurePipRenderer extends PictureInPictureRenderer<FigurePipRenderState> {

    // Per-figure texture pool: each figure gets its own GPU texture to prevent
    // deferred blit conflicts when rendering multiple figures in the same frame
    private final Map<String, TextureBundle> texturePool = new HashMap<>();

    private static class TextureBundle {
        GpuTexture colorTexture;
        GpuTextureView colorTextureView;
        GpuTexture depthTex;
        GpuTextureView depthTexView;
    }

    /** Cast this to the mixin accessor for accessing parent class private fields. */
    private PipRendererAccessor self() {
        return (PipRendererAccessor) this;
    }

    public FigurePipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<FigurePipRenderState> getRenderStateClass() {
        return FigurePipRenderState.class;
    }

    @Override
    protected String getTextureLabel() {
        return "geckolib_figure";
    }

    @Override
    protected boolean textureIsReadyToBlit(FigurePipRenderState state) {
        // Always re-render to ensure each figure's content is up-to-date
        return false;
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height * 0.80f;
    }

    @Override
    public void prepare(FigurePipRenderState state, GuiRenderState guiState, int scaleLevel) {
        PipRendererAccessor accessor = self();

        String key = state.figureKey();
        TextureBundle cached = texturePool.get(key);

        if (cached != null) {
            // Swap in the per-figure textures so prepare() renders to and blits from them
            accessor.blockpops$setTexture(cached.colorTexture);
            accessor.blockpops$setTextureView(cached.colorTextureView);
            accessor.blockpops$setDepthTexture(cached.depthTex);
            accessor.blockpops$setDepthTextureView(cached.depthTexView);
        } else {
            // Force the base class to create fresh textures for this new figure
            accessor.blockpops$setTexture(null);
            accessor.blockpops$setTextureView(null);
            accessor.blockpops$setDepthTexture(null);
            accessor.blockpops$setDepthTextureView(null);
        }

        // Base class: creates textures if needed, renders to texture, submits blit
        super.prepare(state, guiState, scaleLevel);

        // Save the textures back to the per-figure cache
        if (cached == null) {
            cached = new TextureBundle();
            texturePool.put(key, cached);
        }
        cached.colorTexture = accessor.blockpops$getTexture();
        cached.colorTextureView = accessor.blockpops$getTextureView();
        cached.depthTex = accessor.blockpops$getDepthTexture();
        cached.depthTexView = accessor.blockpops$getDepthTextureView();
    }

    @Override
    protected void renderToTexture(FigurePipRenderState state, PoseStack poseStack) {
        // Set up entity-in-UI lighting (replaces old Lighting.setupForFlatItems())
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

        // The PiP base class scales by (s, s, -s). GeckoLib block models need the
        // equivalent of the old scale(s, -s, s). Applying (1, -1, -1) here combines
        // with the base class to produce the net effect of (s, -s, s).
        poseStack.scale(1, -1, -1);

        // Apply model rotations
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yRotation()));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.xRotation()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(state.zRotation()));

        // Use the per-figure dedicated renderer instead of a shared singleton
        GeoBlockRenderer<BoxBlockEntity> renderer = FigureWidgetRenderer.getRenderer(state.figureKey());

        if (renderer != null) {
            renderer.render(
                state.renderEntity(),
                0, // partialTick
                poseStack,
                this.bufferSource,
                15728880, // packed light (full bright)
                OverlayTexture.NO_OVERLAY,
                Vec3.ZERO // camera position
            );
        }
    }

    /**
     * Clears all per-figure cached textures. Call when the screen closes.
     */
    public void clearTexturePool() {
        for (TextureBundle bundle : texturePool.values()) {
            if (bundle.colorTextureView != null) bundle.colorTextureView.close();
            if (bundle.colorTexture != null) bundle.colorTexture.close();
            if (bundle.depthTexView != null) bundle.depthTexView.close();
            if (bundle.depthTex != null) bundle.depthTex.close();
        }
        texturePool.clear();
    }
}
