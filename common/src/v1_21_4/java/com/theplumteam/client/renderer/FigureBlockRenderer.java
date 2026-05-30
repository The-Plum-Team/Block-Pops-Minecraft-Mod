package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.SkinModelDetector;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.Collections;
import java.util.List;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {
    private List<String> currentHiddenBones = Collections.emptyList();

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
        addRenderLayer(new FigureBoneTextureLayer<>(this, FigureBlockEntity::getFigureDefinition, FigureBlockEntity::getAlternativeSkinIndex));
    }

    @Override
    public void preRender(PoseStack poseStack, FigureBlockEntity animatable, BakedGeoModel model,
                         MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                         float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                       packedLight, packedOverlay, colour);

        // Detect skin model and show/hide appropriate arms
        if (animatable.hasFigure()) {
            // In GeckoLib 4.8+, getTextureResource requires the renderer as second parameter
            ResourceLocation texture = this.getGeoModel().getTextureResource(animatable, this);
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
            this.currentHiddenBones = figureDef != null ?
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
                for (String boneName : currentHiddenBones) {
                    model.getBone(boneName).ifPresent(bone -> {
                        bone.setHidden(true);
                        bone.setChildrenHidden(true);
                    });
                }

                // Hide bones that use extra textures - re-rendered by FigureBoneTextureLayer
                // Only apply when using the base model, not an alternative model
                boolean usingAltModel = animatable.getAlternativeSkinIndex() > 0
                        && figureDef.getModelForSkinIndex(animatable.getAlternativeSkinIndex()) != null
                        && !figureDef.getModelForSkinIndex(animatable.getAlternativeSkinIndex()).equals(figureDef.getModelPath());
                if (!usingAltModel) {
                    for (FigureDefinition.ExtraTexture extra : figureDef.getExtraTextures()) {
                        for (String boneName : extra.bones()) {
                            model.getBone(boneName).ifPresent(bone -> {
                                bone.setHidden(true);
                                bone.setChildrenHidden(false);
                            });
                        }
                    }
                }
            }
        } else {
            this.currentHiddenBones = Collections.emptyList();
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, FigureBlockEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                  int colour) {
        if (!currentHiddenBones.isEmpty() && currentHiddenBones.contains(bone.getName())) {
            return;
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                              isReRender, partialTick, packedLight, packedOverlay, colour);
    }

    @Override
    public void actuallyRender(PoseStack poseStack, FigureBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              int colour) {
        // Only render if there's a valid figure - check before model is accessed
        // This prevents crashes when NBT data hasn't synced yet
        if (!animatable.hasFigure()) {
            return;
        }

        // Apply definition scale for figures with custom model sizes
        float defScale = animatable.getFigureDefinition() != null ? animatable.getFigureDefinition().getScaleForSkinIndex(animatable.getAlternativeSkinIndex()) : 1.0f;
        if (defScale != 1.0f) {
            poseStack.scale(defScale, defScale, defScale);
        }

        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           isReRender, partialTick, packedLight, packedOverlay, colour);
    }
}
