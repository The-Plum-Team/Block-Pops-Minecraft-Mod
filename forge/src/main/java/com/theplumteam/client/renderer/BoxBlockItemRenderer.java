package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.item.GeoBlockItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class BoxBlockItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final BoxBlockRenderer renderer;
    private BoxBlockEntity renderEntity;

    public BoxBlockItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.renderer = new BoxBlockRenderer();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (stack.getItem() instanceof GeoBlockItem geoBlockItem) {
            BoxBlock boxBlock = geoBlockItem.getBoxBlock();

            if (renderEntity == null || !renderEntity.getBlockState().is(boxBlock)) {
                renderEntity = new BoxBlockEntity(BlockPos.ZERO, boxBlock.defaultBlockState());
            }

            // Load NBT data from ItemStack to ensure figure data is available for rendering
            if (stack.hasTag()) {
                CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            }

            // Apply rotation for item rendering
            poseStack.pushPose();

            // Rotate 90 degrees clockwise around Y-axis to match block placement
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            poseStack.translate(-0.5, 0, -0.5);

            // Get the partial tick time for smooth animations
            float partialTick = Minecraft.getInstance().getFrameTime();

            // Render using BoxBlockRenderer which includes figure face rendering
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

            poseStack.popPose();
        }
    }
}
