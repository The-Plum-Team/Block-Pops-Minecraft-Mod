package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import com.theplumteam.util.SkinModelDetector;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
    }

    @Override
    public void preRender(PoseStack poseStack, FigureBlockEntity animatable, BakedGeoModel model,
                         MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                         //? if >=1.21 {
                         /*float partialTick, int packedLight, int packedOverlay, int colour) {
                         *///? } else {
                         float partialTick, int packedLight, int packedOverlay, float red, float green,
                         float blue, float alpha) {
                         //? }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                       //? if >=1.21 {
                       /*packedLight, packedOverlay, colour);
                       *///? } else {
                       packedLight, packedOverlay, red, green, blue, alpha);
                       //? }

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
        }
    }

    @Override
    public void actuallyRender(PoseStack poseStack, FigureBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              //? if >=1.21 {
                              /*int colour) {
                              *///? } else {
                              float red, float green, float blue, float alpha) {
                              //? }
        // Only render if there's a valid figure - check before model is accessed
        // This prevents crashes when NBT data hasn't synced yet
        if (!animatable.hasFigure()) {
            return;
        }

        // Render the figure at default position (no offset or scale adjustments)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           //? if >=1.21 {
                           /*isReRender, partialTick, packedLight, packedOverlay, colour);
                           *///? } else {
                           isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
                           //? }
    }
}
