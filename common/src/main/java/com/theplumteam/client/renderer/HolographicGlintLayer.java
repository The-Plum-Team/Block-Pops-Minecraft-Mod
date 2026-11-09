package com.theplumteam.client.renderer;

//? if >=26 {
/*import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/^*
 * Under an Iris or Oculus shader pack, submits a holographic figure or plush a second time with
 * the rainbow glint, after its ordinary pass (see {@link HolographicRenderTypes#glint()}). The
 * model marks its render state holographic; a box's own state never is, so on a box renderer
 * this layer does nothing.
 ^/
@SuppressWarnings({"rawtypes", "unchecked"})
final class HolographicGlintLayer extends GeoRenderLayer {
    HolographicGlintLayer(GeoRenderer renderer) {
        super(renderer);
    }

    @Override
    public void submitRenderTask(RenderPassInfo renderPassInfo, SubmitNodeCollector renderTasks) {
        if (!renderPassInfo.willRender() || !HolographicRenderTypes.glintPass()
                || !((GeoRenderState) renderPassInfo.renderState())
                        .getOrDefaultGeckolibData(HolographicRenderTypes.STATE, false)) {
            return;
        }
        RenderType glint = HolographicRenderTypes.glint();
        if (glint != null) {
            // Ordered after the figure's own pass, which the glint's equal depth test needs
            this.renderer.submitRenderTasks(renderPassInfo, renderTasks.order(1), glint);
        }
    }
}
*///? } elif >=1.21.5 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.function.Predicate;

/^*
 * Under an Iris or Oculus shader pack, draws a holographic figure or plush a second time with the
 * rainbow glint, over its ordinary pass (see {@link HolographicRenderTypes#glint()}). The model
 * marks its render state holographic; the predicate is not needed here.
 ^/
final class HolographicGlintLayer<T extends GeoAnimatable> extends GeoRenderLayer<T, Void, GeoRenderState> {
    HolographicGlintLayer(GeoRenderer<T, Void, GeoRenderState> renderer, Predicate<T> holographic) {
        super(renderer);
    }

    @Override
    public void render(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel bakedModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       int packedLight, int packedOverlay, int colour) {
        if (!HolographicRenderTypes.glintPass()
                || !renderState.getOrDefaultGeckolibData(HolographicRenderTypes.STATE, false)) {
            return;
        }
        RenderType glint = HolographicRenderTypes.glint();
        if (glint != null) {
            // Asked for after the ordinary pass, so that pass is drawn first, as the glint's
            // equal depth test needs
            getRenderer().reRender(renderState, poseStack, bakedModel, bufferSource, glint,
                    bufferSource.getBuffer(glint), packedLight, packedOverlay, colour);
        }
    }
}
*///? } else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
//? if >=1.21 {
/*import software.bernie.geckolib.animatable.GeoAnimatable;
*///? } else {
import software.bernie.geckolib.core.animatable.GeoAnimatable;
//? }

import java.util.function.Predicate;

/**
 * Under an Iris or Oculus shader pack, draws a holographic figure or plush a second time with the
 * rainbow glint, over its ordinary pass (see {@link HolographicRenderTypes#glint()}).
 */
final class HolographicGlintLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {
    private final Predicate<T> holographic;

    HolographicGlintLayer(GeoRenderer<T> renderer, Predicate<T> holographic) {
        super(renderer);
        this.holographic = holographic;
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                       //? if >=1.21.2 {
                       /*int packedLight, int packedOverlay, int colour) {
                       *///? } else {
                       int packedLight, int packedOverlay) {
                       //? }
        if (!HolographicRenderTypes.glintPass() || !holographic.test(animatable)) {
            return;
        }
        RenderType glint = HolographicRenderTypes.glint();
        if (glint == null) {
            return;
        }
        // Asked for after the ordinary pass, so that pass is drawn first, as the glint's equal
        // depth test needs. A re-render keeps the pose the ordinary pass left on the stack.
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, glint,
                bufferSource.getBuffer(glint), partialTick, packedLight, packedOverlay,
                //? if >=1.21 {
                /*0xFFFFFFFF);
                *///? } else {
                1f, 1f, 1f, 1f);
                //? }
    }
}
//? }
