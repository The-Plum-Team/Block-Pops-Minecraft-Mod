package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
    }

    @Override
    public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture, true);
    }

    @Override
    public void render(FigureBlockEntity animatable, float partialTick, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        // Only render if there's a valid figure - prevents rendering the fallback
        // box_block model with Steve's skin while NBT data hasn't synced yet
        if (!animatable.hasFigure()) {
            return;
        }

        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);
    }
}
