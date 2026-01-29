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
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    // Store current animatable for use in renderRecursively
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
        // Store animatable for use in custom rendering methods
        this.currentAnimatable = animatable;

        // Render the box model (the main model)
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

        // Render figure face and logo using custom logic
        if (animatable.hasFigure()) {
            renderFigureFaceAndLogo(animatable, poseStack, bufferSource, packedLight, packedOverlay);
        }

        this.currentAnimatable = null;
    }

    private void renderFigureFaceAndLogo(BoxBlockEntity animatable, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // Get the baked model to access bones
        ResourceLocation modelLoc = this.getGeoModel().getModelResource(null);
        BakedGeoModel model = this.getGeoModel().getBakedModel(modelLoc);

        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure != null) {
            // Render figure face on the box
            ResourceLocation skinTexture = figure.getTexturePath();
            if (skinTexture != null) {
                RenderType skinRenderType = RenderType.itemEntityTranslucentCull(skinTexture);
                VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

                for (GeoBone bone : model.topLevelBones()) {
                    if (bone.getName().equals("figure_face")) {
                        poseStack.pushPose();
                        renderBoneWithTexture(bone, skinRenderType, skinBuffer, poseStack,
                                            bufferSource, packedLight, packedOverlay);
                        poseStack.popPose();
                    } else if (bone.getName().equals("figure_face_3d")) {
                        poseStack.pushPose();
                        renderBoneWithTexture(bone, skinRenderType, skinBuffer, poseStack,
                                            bufferSource, packedLight, packedOverlay);
                        poseStack.popPose();
                    }
                }
            }
        }

        // Render the collection logo
        if (!animatable.isHideLogo()) {
            renderCollectionLogo(animatable, model, poseStack, bufferSource, packedLight, packedOverlay);
        }
    }

    private void renderBoneWithTexture(GeoBone bone, RenderType renderType, VertexConsumer buffer,
                                      PoseStack poseStack, MultiBufferSource bufferSource,
                                      int packedLight, int packedOverlay) {
        // Render bone cubes manually in GeckoLib 5
        poseStack.pushPose();

        // Apply bone transforms
        bone.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
        bone.updatePosition(bone.getPosX(), bone.getPosY(), bone.getPosZ());
        bone.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());

        // Render all cubes in this bone
        for (var cube : bone.getCubes()) {
            // Render each quad in the cube
            for (var quad : cube.quads()) {
                for (var vertex : quad.vertices()) {
                    var pos = vertex.position();
                    var normal = quad.normal();
                    buffer.addVertex(
                        poseStack.last().pose(),
                        (float) pos.x, (float) pos.y, (float) pos.z
                    ).setColor(0xFFFFFFFF)
                     .setUv(vertex.texU(), vertex.texV())
                     .setLight(packedLight)
                     .setNormal(poseStack.last(), normal.x, normal.y, normal.z);
                }
            }
        }

        // Render child bones
        for (GeoBone childBone : bone.getChildBones()) {
            renderBoneWithTexture(childBone, renderType, buffer, poseStack, bufferSource, packedLight, packedOverlay);
        }

        poseStack.popPose();
    }

    private void renderCollectionLogo(BoxBlockEntity animatable, BakedGeoModel model, PoseStack poseStack,
                                     MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        String collectionId = animatable.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection == null) return;

        FigureCollection.LogoConfig logoConfig = collection.getLogoConfig();
        if (logoConfig == null) return;

        // Use entity-specific logo config if set, otherwise use collection defaults
        float logoPositionX = animatable.getLogoPositionX() != null ?
                              animatable.getLogoPositionX().floatValue() : logoConfig.getPositionX();
        float logoPositionY = animatable.getLogoPositionY() != null ?
                              animatable.getLogoPositionY().floatValue() : logoConfig.getPositionY();
        float logoPositionZ = animatable.getLogoPositionZ() != null ?
                              animatable.getLogoPositionZ().floatValue() : logoConfig.getPositionZ();
        float logoScaleX = animatable.getLogoScaleX() != null ?
                           animatable.getLogoScaleX().floatValue() : logoConfig.getScaleX();
        float logoScaleY = animatable.getLogoScaleY() != null ?
                           animatable.getLogoScaleY().floatValue() : logoConfig.getScaleY();
        float logoScaleZ = animatable.getLogoScaleZ() != null ?
                           animatable.getLogoScaleZ().floatValue() : logoConfig.getScaleZ();

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoConfig.getTexture());
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find and render the logo bone
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("logo")) {
                poseStack.pushPose();
                poseStack.translate(logoPositionX, logoPositionY, logoPositionZ);
                poseStack.scale(logoScaleX, logoScaleY, logoScaleZ);
                renderBoneWithTexture(bone, logoRenderType, logoBuffer, poseStack,
                                    bufferSource, packedLight, packedOverlay);
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
