package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

import com.theplumteam.figure.FigureDefinition;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Render layer that re-renders specific bones with an alternative texture.
 * Used for models that require multiple textures (e.g., Alexander's head uses a detail texture).
 *
 * Works by traversing the bone tree after the main render, applying the same bone transforms
 * via RenderUtils.prepMatrixForBone, but only rendering cubes for bones listed in the figure's
 * extra_textures definition. This reuses GeckoLib's own renderCubesOfBone for correct rendering.
 *
 * GeckoLib 4.7.4 version (single type parameter, float RGBA in renderCubesOfBone).
 */
public class FigureBoneTextureLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {
    private final Function<T, FigureDefinition> defGetter;
    private final ToIntFunction<T> skinIndexGetter;

    public FigureBoneTextureLayer(GeoRenderer<T> renderer, Function<T, FigureDefinition> defGetter, ToIntFunction<T> skinIndexGetter) {
        super(renderer);
        this.defGetter = defGetter;
        this.skinIndexGetter = skinIndexGetter;
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        FigureDefinition def = defGetter.apply(animatable);
        if (def == null || def.getExtraTextures().isEmpty()) return;

        // Skip extra texture rendering when using an alternative model
        int skinIndex = skinIndexGetter.applyAsInt(animatable);
        if (skinIndex > 0) {
            ResourceLocation altModel = def.getModelForSkinIndex(skinIndex);
            if (altModel != null && !altModel.equals(def.getModelPath())) return;
        }

        for (FigureDefinition.ExtraTexture extra : def.getExtraTextures()) {
            Set<String> extraBoneNames = new HashSet<>(extra.bones());

            RenderType extraRT = RenderType.entityCutoutNoCull(extra.texture());
            VertexConsumer extraBuffer = bufferSource.getBuffer(extraRT);

            for (GeoBone topBone : bakedModel.topLevelBones()) {
                renderBoneTree(poseStack, topBone, extraBuffer, packedLight, packedOverlay, extraBoneNames);
            }
        }
    }

    /**
     * Recursively traverses the bone tree, applying standard GeckoLib bone transforms.
     * Only renders cubes for bones in the extraBoneNames set, using the provided buffer
     * (which is bound to the extra texture's RenderType).
     */
    private void renderBoneTree(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                                int packedLight, int packedOverlay, Set<String> extraBoneNames) {
        poseStack.pushPose();
        RenderUtils.prepMatrixForBone(poseStack, bone);

        if (extraBoneNames.contains(bone.getName())) {
            // Temporarily show the bone so renderCubesOfBone doesn't skip it
            boolean wasHidden = bone.isHidden();
            bone.setHidden(false);
            this.renderer.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, 1, 1, 1, 1);
            bone.setHidden(wasHidden);
        }

        // Always recurse into children to apply transforms and reach deeper extra-texture bones
        for (GeoBone child : bone.getChildBones()) {
            renderBoneTree(poseStack, child, buffer, packedLight, packedOverlay, extraBoneNames);
        }

        poseStack.popPose();
    }
}
