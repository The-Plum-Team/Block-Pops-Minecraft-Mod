package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.PlushBlockEntity;
import com.theplumteam.client.model.PlushBlockModel;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
//? if >=1.21.5 {
/*import net.minecraft.world.phys.Vec3;
*///? }

// Draws a plush where its block entity says: shifted, and scaled about the centre of the block's floor.
//? if >=26 {
/*public class PlushBlockRenderer extends BlockPopsGeoBlockRenderer<PlushBlockEntity,
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {

    public PlushBlockRenderer(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context,
                              String plushId) {
        super(context, new PlushBlockModel(plushId));
        GeoRendererContext.capture(context);
    }
*///? } else {
public class PlushBlockRenderer extends GeoBlockRenderer<PlushBlockEntity> {

    public PlushBlockRenderer(String plushId) {
        super(new PlushBlockModel(plushId));
    }
//? }

    //? if >=26 {
    /*// 26.1 draws nothing inline, so the placement is captured off the block entity
    // while it is still in hand and applied when the pass is prepared.
    private static final com.geckolib.constant.dataticket.DataTicket<float[]> PLACEMENT =
            com.geckolib.constant.dataticket.DataTicket.create("blockpops:plush_placement", float[].class);

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void addRenderData(PlushBlockEntity animatable, Void relatedObject,
                              net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState,
                              float partialTick) {
        super.addRenderData(animatable, relatedObject, renderState, partialTick);
        ((com.geckolib.renderer.base.GeoRenderState) renderState).addGeckolibData(PLACEMENT, new float[] {
                (float) animatable.getOffsetX(), (float) animatable.getOffsetY(),
                (float) animatable.getOffsetZ(), (float) animatable.getScale()});
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void preRenderPass(com.geckolib.renderer.base.RenderPassInfo renderPassInfo,
                              net.minecraft.client.renderer.SubmitNodeCollector renderTasks) {
        // super moves to the centre of the block's floor
        super.preRenderPass(renderPassInfo, renderTasks);
        float[] placement = ((com.geckolib.renderer.base.GeoRenderState) renderPassInfo.renderState())
                .getGeckolibData(PLACEMENT);
        if (placement != null) {
            PoseStack poseStack = renderPassInfo.poseStack();
            poseStack.translate(placement[0], placement[1], placement[2]);
            poseStack.scale(placement[3], placement[3], placement[3]);
        }
    }
    *///? } elif >=1.21.5 {
    /*// From 1.21.5 the render methods only see the render state, so the placement is
    // applied here, where the block entity is still in hand.
    @Override
    public void render(PlushBlockEntity animatable, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        poseStack.pushPose();
        applyPlacement(poseStack, animatable);
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);
        poseStack.popPose();
    }
    *///? } else {
    @Override
    public void preRender(PoseStack poseStack, PlushBlockEntity animatable, BakedGeoModel model,
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
        if (!isReRender) {
            applyPlacement(poseStack, animatable);
        }
    }
    //? }

    //? if <26 {
    // Runs before GeckoLib moves to the centre of the block's floor, so it does that move itself around the scale.
    private static void applyPlacement(PoseStack poseStack, PlushBlockEntity plush) {
        float scale = (float) plush.getScale();
        poseStack.translate(plush.getOffsetX() + 0.5, plush.getOffsetY(), plush.getOffsetZ() + 0.5);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-0.5, 0.0, -0.5);
    }
    //? }
}
