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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    // Store current animatable for use in actuallyRender
    private BoxBlockEntity currentAnimatable;

    public BoxBlockRenderer() {
        super(new BoxBlockModel());
        // Create a separate renderer instance for the figure
        // It will inherit the default rotateBlock() behavior to rotate with the box
        this.figureRenderer = new GeoBlockRenderer<>(new FigureModel());
    }

    @Override
    public void render(BoxBlockEntity animatable, float partialTick, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        // Store animatable for use in actuallyRender
        this.currentAnimatable = animatable;

        // Render the box model (the main model) - this calls actuallyRender() internally
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);

        // Render the figure model if one exists and hasn't been extracted
        // NOTE: This must be done AFTER super.render() completes, so we save/restore the pose stack
        if (animatable.hasFigure() && !animatable.isFigureExtracted()) {
            poseStack.pushPose();

            // GeckoLib 5 already centered the box at (0.5, 0, 0.5)
            // We need to position the figure relative to that center, not add another centering
            // The figure's adjustPositionForRender will try to center it again, so we compensate

            // Apply figure offset (these are relative to block origin, not box center)
            // Since the figure renderer will add (0.5, 0, 0.5), we need to subtract it first
            poseStack.translate(
                animatable.getFigureOffsetX() + 0.5,  // Compensate for figure's auto-centering
                animatable.getFigureOffsetY(),
                animatable.getFigureOffsetZ() + 0.5
            );
            poseStack.scale((float) animatable.getFigureScale(),
                          (float) animatable.getFigureScale(),
                          (float) animatable.getFigureScale());

            // Render the figure using separate renderer
            figureRenderer.render(animatable, partialTick, poseStack, bufferSource,
                                packedLight, packedOverlay, camPos);

            poseStack.popPose();
        }

        this.currentAnimatable = null;
    }

    @Override
    public void actuallyRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                              @Nullable RenderType renderType, MultiBufferSource bufferSource,
                              @Nullable VertexConsumer buffer, boolean isReRender,
                              int packedLight, int packedOverlay, int renderColor) {
        // Render the box model normally
        super.actuallyRender(renderState, poseStack, model, renderType, bufferSource, buffer,
                           isReRender, packedLight, packedOverlay, renderColor);

        // Don't render face/logo during re-render passes (e.g. render layers)
        if (isReRender || currentAnimatable == null) return;

        // Render figure face and logo - the PoseStack already has centering + rotation from GeckoLib 5
        if (currentAnimatable.hasFigure()) {
            renderFigureFace(renderState, poseStack, model, bufferSource, packedLight, packedOverlay);
        }

        if (!currentAnimatable.isHideLogo()) {
            renderCollectionLogo(renderState, poseStack, model, bufferSource, packedLight, packedOverlay);
        }
    }

    private void renderFigureFace(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                  MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        FigureDefinition figure = currentAnimatable.getFigureDefinition();
        if (figure == null) return;

        ResourceLocation skinTexture = figure.getTexturePath();
        if (skinTexture == null) return;

        RenderType skinRenderType = RenderType.itemEntityTranslucentCull(skinTexture);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face") || bone.getName().equals("figure_face_3d")) {
                poseStack.pushPose();
                renderRecursively(renderState, poseStack, bone, skinRenderType, bufferSource, skinBuffer,
                                true, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
            }
        }
    }

    private void renderCollectionLogo(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                     MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        String collectionId = currentAnimatable.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection == null) return;

        FigureCollection.LogoConfig logoConfig = collection.getLogoConfig();
        if (logoConfig == null) return;

        // Use entity-specific logo config if set, otherwise use collection defaults
        float logoPositionX = currentAnimatable.getLogoPositionX() != null ?
                              currentAnimatable.getLogoPositionX().floatValue() : logoConfig.getPositionX();
        float logoPositionY = currentAnimatable.getLogoPositionY() != null ?
                              currentAnimatable.getLogoPositionY().floatValue() : logoConfig.getPositionY();
        float logoPositionZ = currentAnimatable.getLogoPositionZ() != null ?
                              currentAnimatable.getLogoPositionZ().floatValue() : logoConfig.getPositionZ();
        float logoScaleX = currentAnimatable.getLogoScaleX() != null ?
                           currentAnimatable.getLogoScaleX().floatValue() : logoConfig.getScaleX();
        float logoScaleY = currentAnimatable.getLogoScaleY() != null ?
                           currentAnimatable.getLogoScaleY().floatValue() : logoConfig.getScaleY();
        float logoScaleZ = currentAnimatable.getLogoScaleZ() != null ?
                           currentAnimatable.getLogoScaleZ().floatValue() : logoConfig.getScaleZ();

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoConfig.getTexture());
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find and render the logo bone
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("logo")) {
                poseStack.pushPose();
                poseStack.translate(logoPositionX, logoPositionY, logoPositionZ);
                poseStack.scale(logoScaleX, logoScaleY, logoScaleZ);
                renderRecursively(renderState, poseStack, bone, logoRenderType, bufferSource, logoBuffer,
                                true, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(GeoRenderState renderState, PoseStack poseStack, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  int packedLight, int packedOverlay, int colour) {
        // Skip rendering special bones during normal box rendering
        // They will be rendered separately with their own textures
        String boneName = bone.getName();
        if ((boneName.equals("figure_face") || boneName.equals("figure_face_3d") || boneName.equals("logo")) && !isReRender) {
            return;
        }

        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender,
                              packedLight, packedOverlay, colour);
    }
}
