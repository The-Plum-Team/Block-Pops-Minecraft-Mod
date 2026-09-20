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
//? if >=1.21.5 {
/*import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? }

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
    }

    //? if >=1.21.5 {
    /*// From 1.21.5 the render methods only see the render state, so the "is there a
    // figure yet" question is answered once, where the block entity is still in hand.
    @Override
    public void render(FigureBlockEntity animatable, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        if (!animatable.hasFigure()) {
            return;
        }
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);
    }

    @Override
    public void preRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          int packedLight, int packedOverlay, int colour) {
        super.preRender(renderState, poseStack, model, bufferSource, buffer, isReRender,
                packedLight, packedOverlay, colour);
        applyArmVisibility(model, this.getGeoModel().getTextureResource(renderState));
    }
    *///? } else {
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
            //? if >=1.21.2 {
            /*applyArmVisibility(model, this.getGeoModel().getTextureResource(animatable, this));
            *///? } else {
            applyArmVisibility(model, this.getGeoModel().getTextureResource(animatable));
            //? }
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
    //? }

    /** Shows the arm pair that matches the skin the figure is wearing. */
    static void applyArmVisibility(BakedGeoModel model, ResourceLocation texture) {
        boolean isSlim = SkinModelDetector.detectSkinModel(texture) == SkinModelDetector.SkinModel.SLIM;

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
