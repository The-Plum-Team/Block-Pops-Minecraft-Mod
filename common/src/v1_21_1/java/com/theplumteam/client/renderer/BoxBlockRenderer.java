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
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
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
                                 float partialTick, int packedLight, int packedOverlay, int colour) {
                super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                               packedLight, packedOverlay, colour);

                // Apply definition scale (after GeckoLib centering, so it scales around the block center)
                float defScale = animatable.getFigureDefinition() != null ? animatable.getFigureDefinition().getScale() : 1.0f;
                if (defScale != 1.0f) {
                    poseStack.scale(defScale, defScale, defScale);
                }

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
            public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone,
                                          RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                          boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                          int colour) {
                if (!figureHiddenBones.isEmpty() && figureHiddenBones.contains(bone.getName())) {
                    return;
                }
                super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                                      isReRender, partialTick, packedLight, packedOverlay, colour);
            }
        };
    }

    @Override
    public void actuallyRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              int colour) {
        // Set figure_face bone visibility based on showBoxFace (e.g. Dragon Ball Z uses custom 3D models)
        if (!isReRender) {
            FigureDefinition figureDef = animatable.getFigureDefinition();
            boolean hideFace = figureDef != null && !figureDef.showBoxFace();
            model.getBone("figure_face").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
            model.getBone("figure_face_3d").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
        }

        // First, render the box model (the main model)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           isReRender, partialTick, packedLight, packedOverlay, colour);

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
        if (figure == null || !figure.showBoxFace()) return;

        // Get the player skin texture directly (bypassing figure model)
        ResourceLocation skinTexture = figureRenderer.getGeoModel().getTextureResource(animatable);
        if (skinTexture == null) return; // Safety check

        // Render both the base skin layer and 3D overlay layer directly from player skin
        // Use entityTranslucentCull for proper alpha blending with face culling (like player rendering)
        RenderType skinRenderType = RenderType.entityTranslucentCull(skinTexture);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face")) {
                // Render base skin layer (head front: UV 8,8 to 16,16 on 64x64 skin)
                poseStack.pushPose();
                renderRecursively(poseStack, animatable, bone, skinRenderType, bufferSource, skinBuffer,
                                true, partialTick, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
            } else if (bone.getName().equals("figure_face_3d")) {
                // Render hat/overlay layer (hat front: UV 40,8 to 48,16 on 64x64 skin)
                // Same render type for consistency with player rendering
                poseStack.pushPose();
                renderRecursively(poseStack, animatable, bone, skinRenderType, bufferSource, skinBuffer,
                                true, partialTick, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
            }
        }
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
                                true, partialTick, packedLight, packedOverlay, 0xFFFFFFFF);

                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  float partialTick, int packedLight, int packedOverlay, int colour) {
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
                              partialTick, packedLight, packedOverlay, colour);
    }
}
