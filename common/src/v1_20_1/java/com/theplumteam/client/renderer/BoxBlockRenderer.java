package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.SkinModelDetector;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.util.RenderUtils;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    private List<String> figureHiddenBones = Collections.emptyList();
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

            @Override
            public void preRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                 MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                 float partialTick, int packedLight, int packedOverlay, float red, float green,
                                 float blue, float alpha) {
                super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                               packedLight, packedOverlay, red, green, blue, alpha);

                // NOTE: Definition scale is applied in actuallyRender() AFTER GeckoLib's centering translate.
                // In GeckoLib 4.7.4, the centering translate(0.5, 0, 0.5) happens in actuallyRender(),
                // so applying scale here (in preRender) would cause the centering to be scaled down,
                // making custom-scaled figures (Dragon Ball, Willow Media) appear outside the box.

                // Detect skin model and show/hide appropriate arms
                if (animatable.hasFigure()) {
                    ResourceLocation texture = this.getGeoModel().getTextureResource(animatable);
                    SkinModelDetector.SkinModel skinModel = SkinModelDetector.detectSkinModel(texture);

                    // Hide/show arms based on detection
                    boolean isSlim = (skinModel == SkinModelDetector.SkinModel.SLIM);

                    // Find and set visibility for arm bones
                    GeoBone rightArmSlim = model.getBone("RightArmSlim").orElse(null);
                    GeoBone leftArmSlim = model.getBone("LeftArmSlim").orElse(null);
                    GeoBone rightArmClassic = model.getBone("RightArmClassic").orElse(null);
                    GeoBone leftArmClassic = model.getBone("LeftArmClassic").orElse(null);

                    if (rightArmSlim != null) {
                        rightArmSlim.setHidden(!isSlim);
                    }
                    if (leftArmSlim != null) {
                        leftArmSlim.setHidden(!isSlim);
                    }
                    if (rightArmClassic != null) {
                        rightArmClassic.setHidden(isSlim);
                    }
                    if (leftArmClassic != null) {
                        leftArmClassic.setHidden(isSlim);
                    }

                    // Store hidden bones for use in renderRecursively
                    FigureDefinition figureDef = animatable.getFigureDefinition();
                    figureHiddenBones = figureDef != null ?
                        figureDef.getHiddenBonesForSkinIndex(animatable.getAlternativeSkinIndex()) :
                        Collections.emptyList();

                    // Also apply bone visibility directly on the model (redundant safety net)
                    // BakedGeoModel is cached/shared, so we must reset all variant bones first
                    if (figureDef != null) {
                        for (String boneName : figureDef.getAllVariantBoneNames()) {
                            model.getBone(boneName).ifPresent(bone -> {
                                bone.setHidden(false);
                                bone.setChildrenHidden(false);
                            });
                        }
                        for (String boneName : figureHiddenBones) {
                            model.getBone(boneName).ifPresent(bone -> {
                                bone.setHidden(true);
                                bone.setChildrenHidden(true);
                            });
                        }
                    }
                } else {
                    figureHiddenBones = Collections.emptyList();
                }
            }

            @Override
            public void actuallyRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                      RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                      boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                      float red, float green, float blue, float alpha) {
                if (!isReRender) {
                    // Replicate base GeoBlockRenderer.actuallyRender() logic so we can insert
                    // defScale AFTER the centering translate but BEFORE model rendering.
                    // In GeckoLib 4.7.4, centering is in actuallyRender, not preRender.
                    AnimationState<BoxBlockEntity> animationState = new AnimationState<>(animatable, 0, 0, partialTick, false);
                    long instanceId = getInstanceId(animatable);
                    GeoModel<BoxBlockEntity> currentModel = getGeoModel();

                    animationState.setData(DataTickets.TICK, animatable.getTick(animatable));
                    animationState.setData(DataTickets.BLOCK_ENTITY, animatable);
                    currentModel.addAdditionalStateData(animatable, instanceId, animationState::setData);
                    poseStack.translate(0.5, 0, 0.5);
                    rotateBlock(getFacing(animatable), poseStack);

                    // Apply definition scale AFTER centering so figures are properly centered
                    float defScale = animatable.getFigureDefinition() != null ? animatable.getFigureDefinition().getScale() : 1.0f;
                    if (defScale != 1.0f) {
                        poseStack.scale(defScale, defScale, defScale);
                    }

                    currentModel.handleAnimations(animatable, instanceId, animationState);
                }

                this.modelRenderTranslations = new Matrix4f(poseStack.last().pose());

                if (renderType != null && buffer != null) {
                    for (GeoBone bone : model.topLevelBones()) {
                        renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                                        isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
                    }
                }
            }

            @Override
            public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone,
                                          RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                          boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                          float red, float green, float blue, float alpha) {
                if (!figureHiddenBones.isEmpty() && figureHiddenBones.contains(bone.getName())) {
                    return;
                }
                super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                                      isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
            }
        };
    }

    @Override
    public void actuallyRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              float red, float green, float blue, float alpha) {
        // Set figure_face bone visibility based on showBoxFace (e.g. Dragon Ball Z uses custom 3D models)
        if (!isReRender) {
            FigureDefinition figureDef = animatable.getFigureDefinition();
            boolean hideFace = figureDef != null && !figureDef.showBoxFace();
            model.getBone("figure_face").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
            model.getBone("figure_face_3d").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
        }

        // First, render the box model (the main model)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);

        // Then, render the figure model if one exists
        if (animatable.hasFigure()) {
            // Only render the 3D figure model if it hasn't been extracted
            if (!animatable.isFigureExtracted()) {
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
            }

            // Always render the figure face on the box (even when extracted)
            renderFigureFace(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
        }

        // Render the collection logo on the box
        renderLogo(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
    }

    private void renderFigureFace(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                  MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return;

        float[] customUV = figure.getBoxFaceUV();
        if (customUV == null && !figure.showBoxFace()) return;

        // Get the player skin texture directly (bypassing figure model)
        ResourceLocation skinTexture = figureRenderer.getGeoModel().getTextureResource(animatable);
        if (skinTexture == null) return; // Safety check

        // Use entityTranslucentCull for proper alpha blending with face culling (like player rendering)
        RenderType skinRenderType = RenderType.entityTranslucentCull(skinTexture);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        if (customUV != null) {
            // Custom model: render figure_face bone with remapped UVs
            renderFaceBoneWithCustomUV(poseStack, model, skinBuffer, customUV, packedLight, packedOverlay);
        } else {
            // Default skin model: render both face bones with original UVs
            for (GeoBone bone : model.topLevelBones()) {
                if (bone.getName().equals("figure_face") || bone.getName().equals("figure_face_3d")) {
                    poseStack.pushPose();
                    renderRecursively(poseStack, animatable, bone, skinRenderType, bufferSource, skinBuffer,
                                    true, partialTick, packedLight, packedOverlay, 1, 1, 1, 1);
                    poseStack.popPose();
                }
            }
        }
    }

    /**
     * Renders the figure_face bone with custom UV coordinates for non-skin-based models.
     * Uses the bone's geometry for correct positioning but remaps UVs to the face region
     * in the custom model's texture.
     *
     * @param customUV [u, v, width, height, texWidth, texHeight] in pixel coordinates
     */
    private void renderFaceBoneWithCustomUV(PoseStack poseStack, BakedGeoModel model,
                                             VertexConsumer buffer, float[] customUV,
                                             int packedLight, int packedOverlay) {
        GeoBone faceBone = null;
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face")) {
                faceBone = bone;
                break;
            }
        }
        if (faceBone == null) return;

        // Target UV range (normalized to custom texture dimensions)
        float newMinU = customUV[0] / customUV[4];
        float newMaxU = (customUV[0] + customUV[2]) / customUV[4];
        float newMinV = customUV[1] / customUV[5];
        float newMaxV = (customUV[1] + customUV[3]) / customUV[5];

        // Original UV range from box model (UV 8,8 size 8,8 on 64x64 texture)
        float origMinU = 8f / 64f;  // 0.125
        float origMaxU = 16f / 64f; // 0.25
        float origMinV = 8f / 64f;
        float origMaxV = 16f / 64f;
        float origRangeU = origMaxU - origMinU;
        float origRangeV = origMaxV - origMinV;

        poseStack.pushPose();
        RenderUtils.prepMatrixForBone(poseStack, faceBone);

        for (GeoCube cube : faceBone.getCubes()) {
            poseStack.pushPose();
            RenderUtils.translateToPivotPoint(poseStack, cube);
            RenderUtils.rotateMatrixAroundCube(poseStack, cube);
            RenderUtils.translateAwayFromPivotPoint(poseStack, cube);

            Matrix3f normalisedPoseState = poseStack.last().normal();
            Matrix4f poseState = new Matrix4f(poseStack.last().pose());

            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                // Only render the EAST face (the one with actual face UV in box model)
                if (quad.direction() != Direction.EAST) continue;

                Vector3f normal = normalisedPoseState.transform(new Vector3f(quad.normal()));
                RenderUtils.fixInvertedFlatCube(cube, normal);

                for (GeoVertex vertex : quad.vertices()) {
                    Vector4f pos = poseState.transform(new Vector4f(vertex.position(), 1));

                    // Remap UV from original box model space to custom texture space
                    float t_u = (vertex.texU() - origMinU) / origRangeU;
                    float t_v = (vertex.texV() - origMinV) / origRangeV;
                    float remappedU = newMinU + t_u * (newMaxU - newMinU);
                    float remappedV = newMinV + t_v * (newMaxV - newMinV);

                    buffer.vertex(pos.x(), pos.y(), pos.z(), 1, 1, 1, 1, remappedU, remappedV,
                                 packedOverlay, packedLight, normal.x(), normal.y(), normal.z());
                }
            }

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private void renderLogo(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                           MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        // Check if logo should be hidden (e.g., in UI displays)
        if (animatable.isHideLogo()) return;

        // Get logo configuration from collection
        String collectionId = animatable.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);

        if (collection == null) return;

        FigureCollection.LogoConfig collectionLogoConfig = collection.getLogoConfig();
        if (collectionLogoConfig == null) return;

        // Use entity-specific logo config if set, otherwise use collection defaults
        float logoPositionX = animatable.getLogoPositionX() != null ?
                              animatable.getLogoPositionX().floatValue() : collectionLogoConfig.getPositionX();
        float logoPositionY = animatable.getLogoPositionY() != null ?
                              animatable.getLogoPositionY().floatValue() : collectionLogoConfig.getPositionY();
        float logoPositionZ = animatable.getLogoPositionZ() != null ?
                              animatable.getLogoPositionZ().floatValue() : collectionLogoConfig.getPositionZ();
        float logoScaleX = animatable.getLogoScaleX() != null ?
                           animatable.getLogoScaleX().floatValue() : collectionLogoConfig.getScaleX();
        float logoScaleY = animatable.getLogoScaleY() != null ?
                           animatable.getLogoScaleY().floatValue() : collectionLogoConfig.getScaleY();
        float logoScaleZ = animatable.getLogoScaleZ() != null ?
                           animatable.getLogoScaleZ().floatValue() : collectionLogoConfig.getScaleZ();

        RenderType logoRenderType = RenderType.entityCutoutNoCull(collectionLogoConfig.getTexture());
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find the generic "logo" bone
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("logo")) {
                poseStack.pushPose();

                // Apply translate first, then scale (matrices apply in reverse order!)
                // A vertex goes through: scale → translate
                // This scales the 1x1 cube to desired size, then positions it correctly
                // Note: logoScaleX controls width (X axis), logoScaleY controls height (Y axis), logoScaleZ controls depth (Z axis)
                poseStack.translate(logoPositionX, logoPositionY, logoPositionZ);
                poseStack.scale(logoScaleX, logoScaleY, logoScaleZ);

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
        String boneName = bone.getName();
        if (boneName.equals("figure_face") || boneName.equals("figure_face_3d")) {
            if (!isReRender) return;
            FigureDefinition fd = animatable.getFigureDefinition();
            if (fd != null && !fd.showBoxFace()) return;
        }
        if (boneName.equals("logo") && !isReRender) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                              partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
