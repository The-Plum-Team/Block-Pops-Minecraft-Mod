package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
    }

    @Override
    public void actuallyRender(PoseStack poseStack, FigureBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              float red, float green, float blue, float alpha) {
        // Only render if there's a valid figure - check before model is accessed
        // This prevents crashes when NBT data hasn't synced yet
        if (!animatable.hasFigure()) {
            return;
        }

        // Render the figure at default position (no offset or scale adjustments)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
