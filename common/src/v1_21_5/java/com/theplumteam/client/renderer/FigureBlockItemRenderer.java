package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.item.GeoBlockItem;
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
 * Special model renderer for FigureBlock items in 1.21.4+
 * Uses the new SpecialModelRenderer system to replace BlockEntityWithoutLevelRenderer
 */
public class FigureBlockItemRenderer implements SpecialModelRenderer<FigureBlockItemRenderer.RenderData> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "figure_block");

    private final FigureBlockRenderer renderer;
    private FigureBlockEntity renderEntity;

    /**
     * Data extracted from the ItemStack for rendering.
     * Contains the NBT data with figure information.
     */
    public record RenderData(@Nullable CompoundTag blockEntityData) {}

    public FigureBlockItemRenderer() {
        this.renderer = new FigureBlockRenderer();
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
        // Get the figure block
        Block figureBlock = ModBlocks.FIGURE_BLOCK.get();

        if (renderEntity == null || !renderEntity.getBlockState().is(figureBlock)) {
            renderEntity = new FigureBlockEntity(BlockPos.ZERO, figureBlock.defaultBlockState());
        }

        // Load data from the ItemStack if available
        if (data != null && data.blockEntityData() != null) {
            renderEntity.loadFromItemNbt(data.blockEntityData());
        }

        poseStack.pushPose();

        // Rotate 180 degrees around Y-axis
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.translate(-0.5, 0, -0.5);

        // Apply display context transformations
        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(1.2F, 1.2F, 1.2F);
            poseStack.translate(-0.5, 0.0375F, -0.5);
        } else if (displayContext == ItemDisplayContext.GROUND) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(0.7F, 0.7F, 0.7F);
            poseStack.translate(-0.5, 0.5, -0.5);
        } else if (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                   displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.scale(0.4F, 0.4F, 0.4F);
            poseStack.translate(-0.5, 1.0, -0.5);
        }

        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay, net.minecraft.world.phys.Vec3.ZERO);

        poseStack.popPose();
    }

    /**
     * Renders the figure block item with the full item stack context.
     * This is called from the Fabric client initialization.
     */
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

            // Load component data from ItemStack to ensure figure data is available for rendering
            CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (customData != null) {
                CompoundTag blockEntityTag = customData.copyTag();
                renderEntity.loadFromItemNbt(blockEntityTag);
            }

            // Apply transformations for item rendering
            poseStack.pushPose();

            // Rotate 180 degrees around Y-axis
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(180));
            poseStack.translate(-0.5, 0, -0.5);

            // Scale up GUI/inventory display
            if (displayContext == ItemDisplayContext.GUI) {
                poseStack.translate(0.5, 0, 0.5); // Move to center
                poseStack.scale(1.2F, 1.2F, 1.2F); // Slightly larger in inventory
                poseStack.translate(-0.5, 0.0375F, -0.5); // Move back and adjust position (moved up 3 pixels)
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
            // In 1.21.4+, getTimer() was replaced with getDeltaTracker()
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);

            // Render using FigureBlockRenderer
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
            return new FigureBlockItemRenderer();
        }
    }
}
