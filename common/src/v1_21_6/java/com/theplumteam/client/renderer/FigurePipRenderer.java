package com.theplumteam.client.renderer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/**
 * PiP renderer that renders GeckoLib figure models to an off-screen GPU texture.
 * The base class {@link PictureInPictureRenderer} handles:
 * - Creating off-screen GpuTexture (color + depth)
 * - Setting orthographic projection
 * - Centering the PoseStack in the texture
 * - Scaling by guiScale * state.scale()
 * - Redirecting render output via RenderSystem.outputColorTextureOverride
 * - Blitting the result to the GUI
 */
public class FigurePipRenderer extends PictureInPictureRenderer<FigurePipRenderState> {

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
    protected float getTranslateY(int height, int guiScale) {
        // GeckoLib block models have their origin at the base and extend upward.
        // Position the origin at ~80% down the texture to give more headroom.
        // Lower percentage = figure positioned higher, higher percentage = lower.
        return height * 0.80f;
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

        // Render the GeckoLib figure model
        FigureWidgetRenderer.getRenderer().render(
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
