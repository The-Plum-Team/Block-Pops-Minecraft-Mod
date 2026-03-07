package com.theplumteam.client.renderer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.lang.reflect.Field;
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

    // Reflection fields for accessing private base class texture fields
    private static Field textureField;
    private static Field textureViewField;
    private static Field depthTextureField;
    private static Field depthTextureViewField;
    private static boolean reflectionReady = false;

    private static class TextureBundle {
        GpuTexture colorTexture;
        GpuTextureView colorTextureView;
        GpuTexture depthTex;
        GpuTextureView depthTexView;
    }

    private static void initReflection() {
        if (reflectionReady) return;
        reflectionReady = true;
        try {
            textureField = PictureInPictureRenderer.class.getDeclaredField("texture");
            textureField.setAccessible(true);
            textureViewField = PictureInPictureRenderer.class.getDeclaredField("textureView");
            textureViewField.setAccessible(true);
            depthTextureField = PictureInPictureRenderer.class.getDeclaredField("depthTexture");
            depthTextureField.setAccessible(true);
            depthTextureViewField = PictureInPictureRenderer.class.getDeclaredField("depthTextureView");
            depthTextureViewField.setAccessible(true);
        } catch (Exception e) {
            com.theplumteam.BlockPopsMod.LOGGER.error("FigurePipRenderer: Failed to initialize reflection for texture pooling", e);
        }
    }

    private void setTexture(GpuTexture tex) {
        try { if (textureField != null) textureField.set(this, tex); } catch (Exception ignored) {}
    }

    private GpuTexture getTexture() {
        try { return textureField != null ? (GpuTexture) textureField.get(this) : null; } catch (Exception e) { return null; }
    }

    private void setTextureView(GpuTextureView view) {
        try { if (textureViewField != null) textureViewField.set(this, view); } catch (Exception ignored) {}
    }

    private GpuTextureView getTextureView() {
        try { return textureViewField != null ? (GpuTextureView) textureViewField.get(this) : null; } catch (Exception e) { return null; }
    }

    private void setDepthTexture(GpuTexture tex) {
        try { if (depthTextureField != null) depthTextureField.set(this, tex); } catch (Exception ignored) {}
    }

    private GpuTexture getDepthTexture() {
        try { return depthTextureField != null ? (GpuTexture) depthTextureField.get(this) : null; } catch (Exception e) { return null; }
    }

    private void setDepthTextureView(GpuTextureView view) {
        try { if (depthTextureViewField != null) depthTextureViewField.set(this, view); } catch (Exception ignored) {}
    }

    private GpuTextureView getDepthTextureView() {
        try { return depthTextureViewField != null ? (GpuTextureView) depthTextureViewField.get(this) : null; } catch (Exception e) { return null; }
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
        initReflection();

        String key = state.figureKey();
        TextureBundle cached = texturePool.get(key);

        if (cached != null) {
            // Swap in the per-figure textures so prepare() renders to and blits from them
            setTexture(cached.colorTexture);
            setTextureView(cached.colorTextureView);
            setDepthTexture(cached.depthTex);
            setDepthTextureView(cached.depthTexView);
        } else {
            // Force the base class to create fresh textures for this new figure
            setTexture(null);
            setTextureView(null);
            setDepthTexture(null);
            setDepthTextureView(null);
        }

        // Base class: creates textures if needed, renders to texture, submits blit
        super.prepare(state, guiState, scaleLevel);

        // Save the textures back to the per-figure cache
        if (cached == null) {
            cached = new TextureBundle();
            texturePool.put(key, cached);
        }
        cached.colorTexture = getTexture();
        cached.colorTextureView = getTextureView();
        cached.depthTex = getDepthTexture();
        cached.depthTexView = getDepthTextureView();
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
