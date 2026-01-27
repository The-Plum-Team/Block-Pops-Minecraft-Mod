package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Special model renderer for ClawMachineBlock items in 1.21.4+
 * Uses the new SpecialModelRenderer system to replace BlockEntityWithoutLevelRenderer
 */
public class ClawMachineBlockItemRenderer implements SpecialModelRenderer<ClawMachineBlockItemRenderer.RenderData> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "claw_machine_block");

    private final ClawMachineBlockRenderer renderer;
    private ClawMachineBlockEntity renderEntity;

    /**
     * Data extracted from the ItemStack for rendering.
     * Contains the NBT data with claw machine information.
     */
    public record RenderData(@Nullable CompoundTag blockEntityData) {}

    public ClawMachineBlockItemRenderer() {
        this.renderer = new ClawMachineBlockRenderer();
    }

    @Override
    @Nullable
    public RenderData extractArgument(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        return new RenderData(tag);
    }

    @Override
    public void render(@Nullable RenderData data, ItemDisplayContext displayContext, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, boolean hasGlint) {
        // Get the claw machine block
        Block clawMachineBlock = ModBlocks.CLAW_MACHINE_BLOCK.get();

        if (renderEntity == null || !renderEntity.getBlockState().is(clawMachineBlock)) {
            renderEntity = new ClawMachineBlockEntity(BlockPos.ZERO, clawMachineBlock.defaultBlockState());
        }

        // Load data from the ItemStack if available
        if (data != null && data.blockEntityData() != null) {
            renderEntity.loadFromItemNbt(data.blockEntityData());
        }

        poseStack.pushPose();

        // Rotate 90 degrees clockwise around Y-axis to match block placement
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(90));
        poseStack.translate(-0.5, 0, -0.5);

        // Base scale: divide by 3 since the model is about 3 blocks tall
        float baseScale = 1.0F / 3.0F;

        // Apply display context transformations
        if (displayContext == ItemDisplayContext.GROUND) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(baseScale * 0.7F, baseScale * 0.7F, baseScale * 0.7F);
            poseStack.translate(-0.5, 0.5, -0.5);
        } else if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(baseScale, baseScale, baseScale);
            poseStack.translate(-0.5, 0, -0.5);
        } else if (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                   displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(baseScale * 0.8F, baseScale * 0.8F, baseScale * 0.8F);
            poseStack.translate(-0.5, 1.0, -0.5);
        } else if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                   displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(baseScale, baseScale, baseScale);
            poseStack.translate(-0.5, 0, -0.5);
        } else {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(baseScale, baseScale, baseScale);
            poseStack.translate(-0.5, 0, -0.5);
        }

        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        // In GeckoLib 5, render() requires Vec3 camera position as 7th parameter
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay, net.minecraft.world.phys.Vec3.ZERO);

        poseStack.popPose();
    }

    /**
     * Renders the claw machine block item with the full item stack context.
     * This is called from the Fabric client initialization.
     */
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Block block = Block.byItem(stack.getItem());

        if (block instanceof ClawMachineBlock clawMachineBlock) {
            if (renderEntity == null || !renderEntity.getBlockState().is(clawMachineBlock)) {
                renderEntity = new ClawMachineBlockEntity(BlockPos.ZERO, clawMachineBlock.defaultBlockState());
            }

            // Load component data from ItemStack
            CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (customData != null) {
                CompoundTag blockEntityTag = customData.copyTag();
                renderEntity.loadFromItemNbt(blockEntityTag);
            }

            // Apply transformations for item rendering
            poseStack.pushPose();

            // Rotate 90 degrees clockwise around Y-axis to match block placement
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
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
            // In 1.21.4+, getTimer() was replaced with getDeltaTracker()
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);

            // Render using ClawMachineBlockRenderer
            // In GeckoLib 5, render() requires Vec3 camera position as 7th parameter
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay, net.minecraft.world.phys.Vec3.ZERO);

            poseStack.popPose();
        }
    }

    /**
     * Unbaked model for JSON serialization and registry
     */
    public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(EntityModelSet modelSet) {
            return new ClawMachineBlockItemRenderer();
        }
    }
}
