package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

import com.theplumteam.figure.FigureDefinition;

import java.util.HashSet;
import java.util.Set;

/**
 * Render layer that re-renders specific bones with an alternative texture.
 * Used for models that require multiple textures (e.g., Alexander's head uses a detail texture).
 *
 * Works by traversing the bone tree after the main render, applying the same bone transforms
 * via RenderUtil.prepMatrixForBone, but only rendering cubes for bones listed in the figure's
 * extra_textures definition. This reuses GeckoLib's own renderCubesOfBone for correct rendering.
 */
public class FigureBoneTextureLayer<T extends GeoAnimatable> extends GeoRenderLayer<T, Void, GeoRenderState> {
    /** DataTicket to store/retrieve FigureDefinition from the render state */
    public static final DataTicket<FigureDefinition> FIGURE_DEF_TICKET =
            DataTicket.create("blockpops_figure_def", FigureDefinition.class);

    public FigureBoneTextureLayer(GeoRenderer<T, Void, GeoRenderState> renderer) {
        super(renderer);
    }

    @Override
    protected ResourceLocation getTextureResource(GeoRenderState renderState) {
        return this.renderer.getTextureLocation(renderState);
    }

    @Override
    public void render(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel bakedModel,
                       @Nullable RenderType renderType, MultiBufferSource bufferSource,
                       @Nullable VertexConsumer buffer, int packedLight, int packedOverlay, int renderColor) {
        FigureDefinition def = renderState.getGeckolibData(FIGURE_DEF_TICKET);
        if (def == null || def.getExtraTextures().isEmpty()) return;

        for (FigureDefinition.ExtraTexture extra : def.getExtraTextures()) {
            Set<String> extraBoneNames = new HashSet<>(extra.bones());

            RenderType extraRT = this.renderer.getRenderType(renderState, extra.texture());
            if (extraRT == null) continue;
            VertexConsumer extraBuffer = bufferSource.getBuffer(extraRT);

            for (GeoBone topBone : bakedModel.topLevelBones()) {
                renderBoneTree(renderState, poseStack, topBone, extraBuffer,
                        packedLight, packedOverlay, renderColor, extraBoneNames);
            }
        }
    }

    /**
     * Recursively traverses the bone tree, applying standard GeckoLib bone transforms.
     * Only renders cubes for bones in the extraBoneNames set, using the provided buffer
     * (which is bound to the extra texture's RenderType).
     */
    private void renderBoneTree(GeoRenderState renderState, PoseStack poseStack, GeoBone bone,
                                VertexConsumer buffer, int packedLight, int packedOverlay, int renderColor,
                                Set<String> extraBoneNames) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, bone);

        if (extraBoneNames.contains(bone.getName())) {
            // Temporarily show the bone so renderCubesOfBone doesn't skip it
            boolean wasHidden = bone.isHidden();
            bone.setHidden(false);
            this.renderer.renderCubesOfBone(renderState, bone, poseStack, buffer, packedLight, packedOverlay, renderColor);
            bone.setHidden(wasHidden);
        }

        // Always recurse into children to apply transforms and reach deeper extra-texture bones
        for (GeoBone child : bone.getChildBones()) {
            renderBoneTree(renderState, poseStack, child, buffer, packedLight, packedOverlay, renderColor, extraBoneNames);
        }

        poseStack.popPose();
    }
}
