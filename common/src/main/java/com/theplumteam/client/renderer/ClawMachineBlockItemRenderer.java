package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
//? if >=1.21 {
/*import com.theplumteam.item.BlockEntityItemData;
*///? }
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public class ClawMachineBlockItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final ClawMachineBlockRenderer renderer;
    private ClawMachineBlockEntity renderEntity;

    public ClawMachineBlockItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.renderer = new ClawMachineBlockRenderer();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Block block = Block.byItem(stack.getItem());

        if (block instanceof ClawMachineBlock clawMachineBlock) {
            if (renderEntity == null || !renderEntity.getBlockState().is(clawMachineBlock)) {
                renderEntity = new ClawMachineBlockEntity(BlockPos.ZERO, clawMachineBlock.defaultBlockState());
            }

            // Load NBT data from ItemStack
            //? if >=1.21 {
            /*CompoundTag blockEntityTag = BlockEntityItemData.read(stack);
            if (blockEntityTag != null) {
                renderEntity.loadForItemRendering(blockEntityTag);
            }
            *///? } else {
            if (stack.hasTag()) {
                CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            }
            //? }

            // Apply transformations for item rendering
            poseStack.pushPose();

            // Rotate 90 degrees clockwise around Y-axis to match block placement
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            poseStack.translate(-0.5, 0, -0.5);

            // Base scale: divide by 3 since the model is about 3 blocks tall
            float baseScale = 1.0F / 3.0F;

            // Scale down ground items
            if (displayContext == ItemDisplayContext.GROUND) {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.scale(baseScale * 0.7F, baseScale * 0.7F, baseScale * 0.7F);
                poseStack.translate(-0.5, 0.5, -0.5);
            }
            // Scale for GUI/inventory display
            else if (displayContext == ItemDisplayContext.GUI) {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.scale(baseScale, baseScale, baseScale);
                poseStack.translate(-0.5, 0, -0.5);
            }
            // Scale down when held in hand (third person view)
            else if (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                     displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.scale(baseScale * 0.8F, baseScale * 0.8F, baseScale * 0.8F);
                poseStack.translate(-0.5, 1.0, -0.5);
            }
            // Scale for first person view
            else if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                     displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.scale(baseScale * 1.0F, baseScale * 1.0F, baseScale * 1.0F);
                poseStack.translate(-0.5, 0, -0.5);
            }
            // Default scaling for other contexts
            else {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.scale(baseScale, baseScale, baseScale);
                poseStack.translate(-0.5, 0, -0.5);
            }

            // Get the partial tick time for smooth animations
            //? if >=1.21 {
            /*float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            *///? } else {
            float partialTick = Minecraft.getInstance().getFrameTime();
            //? }

            // Render using ClawMachineBlockRenderer
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

            poseStack.popPose();
        }
    }
}
