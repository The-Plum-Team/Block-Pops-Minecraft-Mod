package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
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
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public class ClawMachineBlockItemRenderer
    //? if >=1.21.4 {
    /*implements SpecialModelRenderer<ItemStack>
    *///? } else {
    extends BlockEntityWithoutLevelRenderer
    //? }
{
    private final ClawMachineBlockRenderer renderer;
    private ClawMachineBlockEntity renderEntity;

    public ClawMachineBlockItemRenderer() {
        //? if <1.21.4 {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        //? }
        //? if >=26 {
        /*this.renderer = new ClawMachineBlockRenderer(GeoRendererContext.get());
        *///? } else {
        this.renderer = new ClawMachineBlockRenderer();
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
    //? if <26.2 {
    // 26.2 removed MultiBufferSource along with immediate-mode drawing;
    // from that version the entry point is submit, and this path is unused.
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
            //? if >=1.21.4 {
            /*float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            *///? } elif >=1.21 {
            /*float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            *///? } else {
            float partialTick = Minecraft.getInstance().getFrameTime();
            //? }

            // Render using ClawMachineBlockRenderer
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
    //? }
    //? if >=1.21.4 {
    /*public static final ResourceLocation ID =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "claw_machine_block");

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
            return new ClawMachineBlockItemRenderer();
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
        if (!(Block.byItem(stack.getItem()) instanceof ClawMachineBlock clawMachineBlock)) {
            return false;
        }
        if (renderEntity == null || !renderEntity.getBlockState().is(clawMachineBlock)) {
            renderEntity = new ClawMachineBlockEntity(BlockPos.ZERO, clawMachineBlock.defaultBlockState());
        }
        CompoundTag blockEntityTag = BlockEntityItemData.read(stack);
        if (blockEntityTag != null) {
            renderEntity.loadForItemRendering(blockEntityTag);
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
