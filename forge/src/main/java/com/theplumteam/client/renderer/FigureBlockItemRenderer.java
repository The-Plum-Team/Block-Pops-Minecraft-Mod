package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.item.GeoBlockItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class FigureBlockItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final FigureBlockRenderer renderer;
    private FigureBlockEntity renderEntity;

    public FigureBlockItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.renderer = new FigureBlockRenderer();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (stack.getItem() instanceof GeoBlockItem geoBlockItem) {
            // Safely get the FigureBlock
            if (!(geoBlockItem.getBlock() instanceof FigureBlock figureBlock)) {
                return; // Not a figure block, skip rendering
            }

            if (renderEntity == null || !renderEntity.getBlockState().is(figureBlock)) {
                renderEntity = new FigureBlockEntity(BlockPos.ZERO, figureBlock.defaultBlockState());
            }

            // Load NBT data from ItemStack to ensure figure data is available for rendering
            if (stack.hasTag()) {
                CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            }

            // Apply transformations for item rendering
            poseStack.pushPose();

            // Rotate 180 degrees around Y-axis
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
            poseStack.translate(-0.5, 0, -0.5);

            // Scale down ground items to 70% size
            if (displayContext == ItemDisplayContext.GROUND) {
                poseStack.translate(0.5, 0, 0.5); // Move to center
                poseStack.scale(0.7F, 0.7F, 0.7F);
                poseStack.translate(-0.5, 0.5, -0.5); // Move back and lift up
            }

            // Scale down when held in hand (third person view)
            if (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                poseStack.translate(0.5, 0, 0.5); // Move to center
                poseStack.scale(0.4F, 0.4F, 0.4F); // Much smaller in hand
                poseStack.translate(-0.5, 1.0, -0.5); // Move back and up significantly
            }

            // Get the partial tick time for smooth animations
            float partialTick = Minecraft.getInstance().getFrameTime();

            // Render using FigureBlockRenderer
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

            poseStack.popPose();
        }
    }
}
