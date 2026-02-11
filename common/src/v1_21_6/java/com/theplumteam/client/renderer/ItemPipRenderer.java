package com.theplumteam.client.renderer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/**
 * PiP renderer that renders box block entities with 3D rotation to an off-screen GPU texture.
 * Mirrors {@link FigurePipRenderer} to bypass the item rendering pipeline entirely,
 * rendering BoxBlockEntity directly through GeckoLib.
 */
public class ItemPipRenderer extends PictureInPictureRenderer<ItemPipRenderState> {

    // Configurable translateY ratio (set from the latest render state)
    private static volatile float currentTranslateYRatio = 0.80f;

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
        // CRITICAL FIX: Always return false to force re-rendering for each state
        // This prevents Minecraft from reusing cached GPU textures between different colors
        return false;
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        // Use the configurable ratio (set by the most recent render state)
        return height * currentTranslateYRatio;
    }

    /**
     * Set the translateY ratio from outside (e.g., from the screen's slider).
     */
    public static void setTranslateYRatio(float ratio) {
        currentTranslateYRatio = ratio;
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
        // Use state.showFigure() instead of entity.hasFigure() to match the cache key used
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
}
