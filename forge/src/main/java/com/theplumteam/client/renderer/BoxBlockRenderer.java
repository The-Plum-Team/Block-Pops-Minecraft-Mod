package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
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

        // Render the collection logo on the box
        renderLogo(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
    }

    private void renderFigureFace(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                  MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return;

        // Get the figure texture
        ResourceLocation figureTexture = figure.getTexturePath();
        RenderType figureRenderType = RenderType.entityCutoutNoCull(figureTexture);
        VertexConsumer figureBuffer = bufferSource.getBuffer(figureRenderType);

        // Get the figure model to access the head bone
        BakedGeoModel figureModel = figureRenderer.getGeoModel().getBakedModel(
            figureRenderer.getGeoModel().getModelResource(animatable)
        );

        // Find the head bone in the figure model
        GeoBone figureHeadBone = null;
        for (GeoBone bone : figureModel.topLevelBones()) {
            if (bone.getName().equals("head")) {
                figureHeadBone = bone;
                break;
            }
        }

        // Render both the flat texture layer and 3D head layer
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face")) {
                // Render flat texture layer
                poseStack.pushPose();
                renderRecursively(poseStack, animatable, bone, figureRenderType, bufferSource, figureBuffer,
                                true, partialTick, packedLight, packedOverlay, 1, 1, 1, 1);
                poseStack.popPose();
            } else if (bone.getName().equals("figure_head_3d") && figureHeadBone != null) {
                // Render 3D head layer at the bone's position
                poseStack.pushPose();

                // Translate to the bone's pivot point (as defined in the model)
                poseStack.translate(bone.getPivotX() / 16.0f, bone.getPivotY() / 16.0f, bone.getPivotZ() / 16.0f);

                // Scale the head to fit nicely
                float headScale = 0.3f;
                poseStack.scale(headScale, headScale, headScale);

                // Render the figure's head bone with the figure texture
                figureRenderer.renderRecursively(poseStack, animatable, figureHeadBone, figureRenderType,
                                                bufferSource, figureBuffer, false, partialTick,
                                                packedLight, packedOverlay, 1, 1, 1, 1);

                poseStack.popPose();
            }
        }
    }

    private void renderLogo(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                           MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        // Get logo texture from collection
        String collectionId = animatable.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);

        if (collection == null) return;

        ResourceLocation logoTexture = collection.getLogoTexture();
        if (logoTexture == null) return;

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoTexture);
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find the appropriate logo bone based on collection ID
        // Match any bone that starts with "logo_" or "Logo_" and contains the collection ID (case-insensitive)
        for (GeoBone bone : model.topLevelBones()) {
            String boneName = bone.getName();
            // Check if this bone is a logo bone that matches our collection
            if ((boneName.startsWith("logo_") || boneName.startsWith("Logo_")) &&
                boneName.toLowerCase().contains(collectionId.toLowerCase())) {
                poseStack.pushPose();

                // Render this bone with the logo texture using a special flag
                renderRecursively(poseStack, animatable, bone, logoRenderType, bufferSource, logoBuffer,
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
        // Skip rendering the "figure_face", "figure_head_3d", and logo bones during normal box rendering
        // They will be rendered separately with their own textures
        // When isReRender is true, we're rendering them with the appropriate texture
        String boneName = bone.getName();
        boolean isLogoBone = boneName.startsWith("logo_") || boneName.startsWith("Logo_");
        if ((boneName.equals("figure_face") || boneName.equals("figure_head_3d") || isLogoBone) && !isReRender) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                              partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
