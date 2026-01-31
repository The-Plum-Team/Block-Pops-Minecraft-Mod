package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.item.BoxBlockItem;
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

            // Always create a fresh entity for item rendering to ensure correct texture/logo
            // Item rendering doesn't need animation state persistence like block entities do
            renderEntity = new BoxBlockEntity(BlockPos.ZERO, boxBlock.defaultBlockState());
            // Set client level for GeckoLib tick delta calculations
            renderEntity.setLevel(Minecraft.getInstance().level);

            // Load NBT data from ItemStack FIRST
            // This ensures isOpen is set correctly before the animation controller evaluates
            if (stack.hasTag()) {
                CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            }

            // Then extract collection/color from BoxBlockItem and apply as override
            // This ensures creative menu boxes show correct textures even if NBT is incomplete
            if (stack.getItem() instanceof BoxBlockItem boxBlockItem) {
                PopBlockColor color = boxBlockItem.getColor();
                if (color != null) {
                    renderEntity.setColorOverride(color.getSerializedName());
                }
                String collectionId = boxBlockItem.getCollectionId();
                if (collectionId != null) {
                    renderEntity.setCollectionIdOverride(collectionId);
                }
            }

            // Apply transformations for item rendering
            poseStack.pushPose();

            // Rotate 180 degrees in inventory/GUI
            if (displayContext == ItemDisplayContext.GUI) {
                poseStack.translate(0.5, 0.5, 0.5); // Move to center
                poseStack.mulPose(Axis.YP.rotationDegrees(180)); // Rotate 180 degrees around Y axis
                poseStack.translate(-0.5, -0.4375F, -0.5); // Move back and up 1 pixel
            }

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

            // Render using BoxBlockRenderer which includes figure face rendering
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

            poseStack.popPose();
        }
    }
}
