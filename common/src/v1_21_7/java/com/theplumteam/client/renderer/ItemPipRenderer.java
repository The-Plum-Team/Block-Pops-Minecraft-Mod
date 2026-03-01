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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * PiP renderer that renders box block entities with 3D rotation to an off-screen GPU texture.
 * <p>
 * Uses a per-color texture pool to solve the shared-texture problem: Minecraft's PiP base class
 * uses one GPU texture per renderer instance. When 16 different colored boxes submit states to
 * the same renderer, the renders are immediate but the blits are deferred. Without pooling, all
 * blits would reference the same texture (showing only the last rendered color).
 * <p>
 * The fix: before each state is processed, swap the base class texture fields to point to
 * per-color cached textures. Each color's blit then references its own texture.
 */
public class ItemPipRenderer extends PictureInPictureRenderer<ItemPipRenderState> {

    // Configurable translateY ratio (set from the latest render state)
    private static volatile float currentTranslateYRatio = 0.80f;

    // Per-color texture pool: each color gets its own GPU texture to prevent
    // deferred blit conflicts when rendering multiple colors in the same frame
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
            com.theplumteam.BlockPopsMod.LOGGER.error("ItemPipRenderer: Failed to initialize reflection for texture pooling", e);
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

    public ItemPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<ItemPipRenderState> getRenderStateClass() {
        return ItemPipRenderState.class;
    }

    @Override
    protected String getTextureLabel() {
        return "blockpops_item";
    }

    @Override
    protected boolean textureIsReadyToBlit(ItemPipRenderState state) {
        // Always re-render to ensure each color's content is up-to-date
        return false;
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height * currentTranslateYRatio;
    }

    /**
     * Set the translateY ratio from outside (e.g., from the screen's slider).
     */
    public static void setTranslateYRatio(float ratio) {
        currentTranslateYRatio = ratio;
    }

    @Override
    public void prepare(ItemPipRenderState state, GuiRenderState guiState, int scaleLevel) {
        initReflection();

        String key = state.color().name() + ":" + state.showFigure();
        TextureBundle cached = texturePool.get(key);

        if (cached != null) {
            // Swap in the per-color textures so prepare() renders to and blits from them
            setTexture(cached.colorTexture);
            setTextureView(cached.colorTextureView);
            setDepthTexture(cached.depthTex);
            setDepthTextureView(cached.depthTexView);
        } else {
            // Force the base class to create fresh textures for this new color
            setTexture(null);
            setTextureView(null);
            setDepthTexture(null);
            setDepthTextureView(null);
        }

        // Base class: creates textures if needed, renders to texture, submits blit
        super.prepare(state, guiState, scaleLevel);

        // Save the textures back to the per-color cache
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
    protected void renderToTexture(ItemPipRenderState state, PoseStack poseStack) {
        // Update the translateY ratio from the state for the next frame
        currentTranslateYRatio = state.translateYRatio();

        // Set up entity-in-UI lighting (proper for GeckoLib models)
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

        // Apply position offsets (in pre-scale space)
        if (state.offsetX() != 0 || state.offsetY() != 0) {
            poseStack.translate(state.offsetX(), state.offsetY(), 0);
        }

        // Camera X rotation - applied BEFORE the coordinate flip
        // This tilts the view angle (isometric perspective)
        if (state.camRotX() != 0) {
            poseStack.mulPose(Axis.XP.rotationDegrees(state.camRotX()));
        }

        // The PiP base class scales by (s, s, -s). GeckoLib block models need the
        // equivalent of the old scale(s, -s, s). Applying (1, -1, -1) here combines
        // with the base class to produce the net effect of (s, -s, s).
        poseStack.scale(1, -1, -1);

        // Apply model rotations
        poseStack.mulPose(Axis.YP.rotationDegrees(state.rotationY()));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.rotationX()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(state.rotationZ()));

        // Render the GeckoLib box model using the dedicated renderer for this entity's color
        BoxBlockEntity entity = state.renderEntity();
        BoxBlockRenderer renderer = BoxWidgetRenderer.getRenderer(entity.getColor(), state.showFigure());

        if (renderer != null) {
            renderer.render(
                entity,
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
     * Clears all per-color cached textures. Call when the screen closes or colors change.
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
