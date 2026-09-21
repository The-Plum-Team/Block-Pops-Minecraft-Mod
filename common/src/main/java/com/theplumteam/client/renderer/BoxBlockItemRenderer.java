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
        //? if >=26 {
        /*this.renderer = new BoxBlockRenderer(GeoRendererContext.get());
        *///? } else {
        this.renderer = new BoxBlockRenderer();
        //? }
    }

    //? if >=1.21.4 {
    /*@Override
    public ItemStack extractArgument(ItemStack stack) {
        // Snapshot the stack so the render pass never observes a later mutation.
        return stack.copy();
    }

    //? if <26 {
    /^@Override
    public void render(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay, boolean hasGlint) {
        renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
    }
    ^///? }
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
            //? if >=26 {
            /*// 26.1 enters through submit; this draw path is never reached there.
            *///? } elif >=1.21.5 {
            /*this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay,
                    net.minecraft.world.phys.Vec3.ZERO);
            *///? } else {
            this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
            //? }

            poseStack.popPose();
        }
    }
    //? if >=1.21.4 {
    /*public static final ResourceLocation ID =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "box_block");

    // 1.21.4 binds a special renderer to an item model through this unbaked codec.
    //? if >=26 {
    /^public record Unbaked() implements SpecialModelRenderer.Unbaked<ItemStack> {
    ^///? } else {
    public record Unbaked() implements SpecialModelRenderer.Unbaked {
    //? }
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);

        @Override
        //? if >=26 {
        /^public MapCodec<? extends SpecialModelRenderer.Unbaked<ItemStack>> type() {
        ^///? } else {
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type() {
        //? }
            return MAP_CODEC;
        }

        @Override
        //? if >=26 {
        /^public SpecialModelRenderer<ItemStack> bake(SpecialModelRenderer.BakingContext bakingContext) {
        ^///? } else {
        public SpecialModelRenderer<?> bake(EntityModelSet modelSet) {
        //? }
            return new BoxBlockItemRenderer();
        }
    }
    *///? }

    //? if >=26 {
    /*@Override
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> extents) {
        // The model occupies the block's own unit cube.
        extents.accept(new org.joml.Vector3f(0, 0, 0));
        extents.accept(new org.joml.Vector3f(1, 1, 1));
    }

    // Builds the block entity the item stack stands for, mirroring what the older
    // draw path does inline. Returns false when the stack is not one of ours.
    private boolean prepareRenderEntity(ItemStack stack) {
        if (!(stack.getItem() instanceof GeoBlockItem geoBlockItem)) {
            return false;
        }
        BoxBlock boxBlock = geoBlockItem.getBoxBlock();
        if (renderEntity == null || !renderEntity.getBlockState().is(boxBlock)) {
            renderEntity = new BoxBlockEntity(BlockPos.ZERO, boxBlock.defaultBlockState());
            renderEntity.setLevel(Minecraft.getInstance().level);
        }
        CompoundTag blockEntityTag = BlockEntityItemData.read(stack);
        if (blockEntityTag != null) {
            renderEntity.loadForItemRendering(blockEntityTag);
        } else if (stack.getComponentsPatch().isEmpty()) {
            renderEntity.loadForItemRendering(new CompoundTag());
        }
        return true;
    }

    // 26.1 submits render tasks instead of drawing, and no longer tells a special
    // renderer which display context it is in; those transforms belong to the item
    // model's own display block from this version on.
    @Override
    public void submit(ItemStack stack, PoseStack poseStack,
                       net.minecraft.client.renderer.SubmitNodeCollector renderTasks,
                       int packedLight, int packedOverlay, boolean hasGlint, int outlineColor) {
        if (!prepareRenderEntity(stack)) {
            return;
        }
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState =
                this.renderer.createRenderState();
        this.renderer.extractRenderState(renderEntity, renderState, partialTick,
                net.minecraft.world.phys.Vec3.ZERO, null);
        this.renderer.submit(renderState, poseStack, renderTasks,
                new net.minecraft.client.renderer.state.level.CameraRenderState());
    }
    *///? } elif >=1.21.6 {
    /*@Override
    public void getExtents(java.util.Set<org.joml.Vector3f> extents) {
        // The model occupies the block's own unit cube.
        extents.add(new org.joml.Vector3f(0, 0, 0));
        extents.add(new org.joml.Vector3f(1, 1, 1));
    }
    *///? }
}
