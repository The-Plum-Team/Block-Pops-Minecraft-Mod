package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.item.BoxBlockItem;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Set;

/**
 * Special model renderer for BoxBlock items in 1.21.4+
 * Uses the new SpecialModelRenderer system to replace BlockEntityWithoutLevelRenderer
 */
public class BoxBlockItemRenderer implements SpecialModelRenderer<BoxBlockItemRenderer.RenderData> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "box_block");

    private final BoxBlockRenderer renderer;
    private BoxBlockEntity renderEntity;

    /**
     * Data extracted from the ItemStack for rendering.
     * Contains the item (to get color) and optional NBT data.
     */
    public record RenderData(Item item, @Nullable CompoundTag blockEntityData) {}

    public BoxBlockItemRenderer() {
        this.renderer = new BoxBlockRenderer();
    }

    @Override
    @Nullable
    public RenderData extractArgument(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        return new RenderData(stack.getItem(), tag);
    }

    @Override
    public void render(@Nullable RenderData data, ItemDisplayContext displayContext, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, boolean hasGlint) {
        // Get the box block
        Block boxBlock = ModBlocks.BOX_BLOCK.get();

        if (renderEntity == null || !renderEntity.getBlockState().is(boxBlock)) {
            renderEntity = new BoxBlockEntity(BlockPos.ZERO, boxBlock.defaultBlockState());
            if (Minecraft.getInstance().level != null) {
                renderEntity.setLevel(Minecraft.getInstance().level);
            }
        }

        // Load data from the ItemStack if available
        if (data != null) {
            // First, try to get color from the BoxBlockItem directly
            if (data.item() instanceof BoxBlockItem boxBlockItem) {
                PopBlockColor color = boxBlockItem.getColor();
                if (color != null) {
                    renderEntity.setColorOverride(color.getSerializedName());
                }
                String collectionId = boxBlockItem.getCollectionId();
                if (collectionId != null) {
                    renderEntity.setCollectionIdOverride(collectionId);
                }
            }

            // Then load any additional NBT data (this may override the color if present in NBT)
            if (data.blockEntityData() != null) {
                renderEntity.loadFromItemNbt(data.blockEntityData());
            }
        }

        poseStack.pushPose();

        // Check for custom transformations in NBT (from ColorSelectionButton)
        float customRotX = 0;
        float customRotY = 180; // Default GUI rotation
        float customRotZ = 0;
        float customScale = 1.0f;
        float customOffsetX = 0;
        float customOffsetY = 0;
        float customOffsetZ = 0;

        if (data != null && data.blockEntityData() != null) {
            CompoundTag nbt = data.blockEntityData();
            if (nbt.contains("CustomRotationX")) {
                customRotX = nbt.getFloatOr("CustomRotationX", 0);
                customRotY = nbt.getFloatOr("CustomRotationY", 180);
                customRotZ = nbt.getFloatOr("CustomRotationZ", 0);
                customScale = nbt.getFloatOr("CustomScale", 1.0f);
                customOffsetX = nbt.getFloatOr("CustomOffsetX", 0);
                customOffsetY = nbt.getFloatOr("CustomOffsetY", 0);
                customOffsetZ = nbt.getFloatOr("CustomOffsetZ", 0);
            }
        }

        // Apply display context transformations
        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.5, 0.5, 0.5);

            // Apply custom offsets (before rotation)
            if (customOffsetX != 0 || customOffsetY != 0 || customOffsetZ != 0) {
                poseStack.translate(customOffsetX, customOffsetY, customOffsetZ);
            }

            // Apply custom rotations
            poseStack.mulPose(Axis.YP.rotationDegrees(customRotY));
            poseStack.mulPose(Axis.XP.rotationDegrees(customRotX));
            poseStack.mulPose(Axis.ZP.rotationDegrees(customRotZ));

            // Apply custom scale - now works with oversized_in_gui: true
            if (customScale != 1.0f) {
                poseStack.scale(customScale, customScale, customScale);
            }

            poseStack.translate(-0.5, -0.4375F, -0.5);
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
        // In GeckoLib 5, render() requires Vec3 camera position as 7th parameter
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay, net.minecraft.world.phys.Vec3.ZERO);

        poseStack.popPose();
    }

    /**
     * Renders the box block item with the full item stack context.
     * This is called from the Fabric client initialization.
     */
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

            // Load component data from ItemStack FIRST before any rendering
            // This ensures isOpen is set correctly before the animation controller evaluates
            CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (customData != null) {
                CompoundTag blockEntityTag = customData.copyTag();
                renderEntity.loadFromItemNbt(blockEntityTag);
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
            // In 1.21.4+, getTimer() was replaced with getDeltaTracker()
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);

            // Render using BoxBlockRenderer which includes figure face rendering
            // In GeckoLib 5, render() requires Vec3 camera position as 7th parameter
        this.renderer.render(renderEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay, net.minecraft.world.phys.Vec3.ZERO);

            poseStack.popPose();
        }
    }

    @Override
    public void getExtents(Set<Vector3f> extents) {
        extents.add(new Vector3f(0, 0, 0));
        extents.add(new Vector3f(1, 1, 1));
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
            return new BoxBlockItemRenderer();
        }
    }
}
