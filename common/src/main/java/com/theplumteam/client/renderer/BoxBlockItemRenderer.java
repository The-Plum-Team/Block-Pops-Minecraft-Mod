package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.item.GeoBlockItem;
//? if >=1.21 {
/*import com.theplumteam.item.BlockEntityItemData;
*///? }
import net.minecraft.client.Minecraft;
//? if >=1.21.4 {
/*import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.ResourceLocation;
*///? } else {
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
//? }
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class BoxBlockItemRenderer
    //? if >=1.21.4 {
    /*implements SpecialModelRenderer<ItemStack>
    *///? } else {
    extends BlockEntityWithoutLevelRenderer
    //? }
{
    private final BoxBlockRenderer renderer;
    private BoxBlockEntity renderEntity;

    public BoxBlockItemRenderer() {
        //? if <1.21.4 {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        //? }
        this.renderer = new BoxBlockRenderer();
    }

    //? if >=1.21.4 {
    /*@Override
    public ItemStack extractArgument(ItemStack stack) {
        // Snapshot the stack so the render pass never observes a later mutation.
        return stack.copy();
    }

    @Override
    public void render(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay, boolean hasGlint) {
        renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
    }
    *///? } else {
    @Override
    //? }
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (stack.getItem() instanceof GeoBlockItem geoBlockItem) {
            BoxBlock boxBlock = geoBlockItem.getBoxBlock();

            // Reuse the entity instance if it's for the same block type
            // This is critical for GeckoLib animation state persistence - creating a new entity each frame
            // resets the AnimatableInstanceCache and prevents animations from playing
            if (renderEntity == null || !renderEntity.getBlockState().is(boxBlock)) {
                renderEntity = new BoxBlockEntity(BlockPos.ZERO, boxBlock.defaultBlockState());
                // Set client level for GeckoLib tick delta calculations
                renderEntity.setLevel(Minecraft.getInstance().level);
            }

            // Load NBT data from ItemStack FIRST before any rendering
            // This ensures isOpen is set correctly before the animation controller evaluates
            //? if >=1.21 {
            /*CompoundTag blockEntityTag = BlockEntityItemData.read(stack);
            if (blockEntityTag != null) {
                renderEntity.loadForItemRendering(blockEntityTag);
            } else if (stack.getComponentsPatch().isEmpty()) {
                renderEntity.loadForItemRendering(new CompoundTag());
            }
            *///? } else {
            if (stack.hasTag()) {
                CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            } else {
                // If no NBT, ensure it's explicitly closed
                // This handles brand new boxes from creative menu
                renderEntity.load(new CompoundTag());
            }
            //? }

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
            //? if >=1.21.4 {
            /*float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            *///? } elif >=1.21 {
            /*float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            *///? } else {
            float partialTick = Minecraft.getInstance().getFrameTime();
            //? }

            // Render using BoxBlockRenderer which includes figure face rendering
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

            poseStack.popPose();
        }
    }
    //? if >=1.21.4 {
    /*public static final ResourceLocation ID =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "box_block");

    // 1.21.4 binds a special renderer to an item model through this unbaked codec.
    public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(EntityModelSet modelSet) {
            return new BoxBlockItemRenderer();
        }
    }
    *///? }
}
