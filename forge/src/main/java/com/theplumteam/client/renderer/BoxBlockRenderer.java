package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    private boolean renderingFigure = false;

    public BoxBlockRenderer() {
        super(new BoxBlockModel());
        // Create a separate renderer instance for the figure (like Lineages does with the book)
        this.figureRenderer = new GeoBlockRenderer<>(new FigureModel()) {
            @Override
            protected void rotateBlock(Direction facing, PoseStack poseStack) {
                // Don't apply block rotation to the figure - we want it to always face the same direction
                // or we can apply a custom rotation here
            }
        };
    }

    @Override
    public void actuallyRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              float red, float green, float blue, float alpha) {
        // First, render the box model (the main model)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);

        // Then, render the figure model if one exists
        if (animatable.hasFigure()) {
            poseStack.pushPose();

            // Apply figure offset and scale
            poseStack.translate(animatable.getFigureOffsetX(),
                              animatable.getFigureOffsetY(),
                              animatable.getFigureOffsetZ());
            poseStack.scale((float) animatable.getFigureScale(),
                          (float) animatable.getFigureScale(),
                          (float) animatable.getFigureScale());

            // Render the figure using separate renderer
            figureRenderer.render(animatable, partialTick, poseStack, bufferSource,
                                packedLight, packedOverlay);

            poseStack.popPose();

            // Render the figure face on the box
            renderFigureFace(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
        }
    }

    private void renderFigureFace(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                  MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return;

        // Get the figure texture
        ResourceLocation figureTexture = figure.getTexturePath();
        RenderType figureRenderType = RenderType.entityCutoutNoCull(figureTexture);
        VertexConsumer figureBuffer = bufferSource.getBuffer(figureRenderType);

        // Find the "figure_face" bone
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face")) {
                poseStack.pushPose();

                // Render this bone with the figure texture using a special flag
                renderRecursively(poseStack, animatable, bone, figureRenderType, bufferSource, figureBuffer,
                                true, partialTick, packedLight, packedOverlay, 1, 1, 1, 1);

                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        // Skip rendering the "figure_face" bone during normal box rendering
        // It will be rendered separately with the figure texture
        // When isReRender is true, we're rendering it with the figure texture
        if (bone.getName().equals("figure_face") && !isReRender) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                              partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
