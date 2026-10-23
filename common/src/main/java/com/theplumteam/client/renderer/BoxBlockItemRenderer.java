package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.item.BoxBlockItem;
import com.theplumteam.item.GeoBlockItem;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
//? if >=1.21.4 {
/*import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.component.DataComponents;
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
import org.jetbrains.annotations.Nullable;

/**
 * Draws box items: the block entity a stack stands for, with the transforms each place it is
 * held or shown in asks for. The collection creator's previews may also turn a box to any angle
 * about its upright centre line and pick the pose of the figure inside (see {@link #setView}).
 */
public class BoxBlockItemRenderer
    //? if >=1.21.4 {
    /*implements SpecialModelRenderer<ItemStack>
    *///? } else {
    extends BlockEntityWithoutLevelRenderer
    //? }
{
    // Written only by the creator's previews: the box's turn, in degrees
    private static final String PREVIEW_YAW = "PreviewYaw";
    // How far a box turned to any angle reaches from its centre line: half a diagonal, rounded up
    private static final float TURN_REACH = 0.7072f;

    private BoxBlockRenderer renderer;
    private BoxBlockEntity renderEntity;
    // Whether this renderer draws the creator's previews, whose boxes may be turned to any angle
    private final boolean preview;

    // One entity draws every box stack, so each stack starts from a new box of its own item:
    // the collection or color the item stands for, whether or not the stack's tag repeats it
    private void resetRenderEntity(GeoBlockItem item) {
        if (item instanceof BoxBlockItem boxItem) {
            renderEntity.resetForItemRendering(boxItem.getCollectionId(), boxItem.getColor());
        } else {
            renderEntity.resetForItemRendering(null, null);
        }
    }

    // A drawn box of a World collection is the plain default box item with the collection in
    // its tag. Unless the tag names a color itself, it shows the collection's box art, as the
    // same box placed in the world does. Custom and built-in boxes keep their item's color.
    // The creator's preview of a collection is drawn the same way.
    private void useCollectionBoxArt(GeoBlockItem item, @Nullable CompoundTag blockEntityTag) {
        String collectionId = renderEntity.getCollectionId();
        if (item instanceof BoxBlockItem boxItem && boxItem.getCollectionId() == null
                && boxItem.getColor() == PopBlockColor.ORIGINAL
                && (blockEntityTag == null || !blockEntityTag.contains("Color"))
                && (WorldContentIds.isWorldCollectionId(collectionId)
                        || collectionId.startsWith(CollectionRegistry.PREVIEW_PREFIX))) {
            renderEntity.clearColorForItemRendering();
        }
    }

    // The turn a creator preview asks of a box, in degrees, or 0 for every other stack. A stack
    // from elsewhere may carry any number, and one that is not finite would wreck the matrix.
    private static float previewYaw(@Nullable CompoundTag blockEntityTag) {
        if (blockEntityTag == null) {
            return 0;
        }
        //? if >=1.21.5 {
        /*float yaw = blockEntityTag.getFloatOr(PREVIEW_YAW, 0f);
        *///? } else {
        float yaw = blockEntityTag.getFloat(PREVIEW_YAW);
        //? }
        return Float.isFinite(yaw) ? yaw : 0;
    }

    /**
     * Turns a box stack about its upright centre line, in degrees, and picks the pose of the
     * figure inside it, counted from 0 (standing) to the figure's pose count less one (sitting).
     * The stack's block entity data is rewritten in place, so a preview dragged round keeps
     * drawing one stack rather than one per angle. Only the creator's previews call this.
     */
    public static void setView(ItemStack stack, float yaw, int pose) {
        CompoundTag tag = BlockEntityItemData.read(stack);
        tag = tag != null ? tag.copy() : new CompoundTag();
        tag.putFloat(PREVIEW_YAW, yaw);
        tag.putInt("PoseIndex", Math.max(0, pose));
        BlockEntityItemData.write(stack, tag, "blockpops:box_block");
    }

    public BoxBlockItemRenderer() {
        this(false);
    }

    private BoxBlockItemRenderer(boolean preview) {
        //? if <1.21.4 {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        //? }
        this.preview = preview;
        //? if <26 {
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
    //? if <26.2 {
    // 26.2 removed MultiBufferSource along with immediate-mode drawing;
    // from that version the entry point is submit, and this path is unused.
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
            resetRenderEntity(geoBlockItem);

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
            CompoundTag blockEntityTag = null;
            if (stack.hasTag()) {
                blockEntityTag = stack.getTagElement("BlockEntityTag");
                if (blockEntityTag != null) {
                    renderEntity.load(blockEntityTag);
                }
            } else {
                // If no NBT, ensure it's explicitly closed
                // This handles brand new boxes from creative menu
                renderEntity.load(new CompoundTag());
            }
            //? }
            useCollectionBoxArt(geoBlockItem, blockEntityTag);

            // Apply transformations for item rendering
            poseStack.pushPose();

            // A creator preview turns the box about its upright centre line
            float yaw = previewYaw(blockEntityTag);
            if (yaw != 0) {
                poseStack.translate(0.5, 0, 0.5);
                poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
                poseStack.translate(-0.5, 0, -0.5);
            }

            // Legacy entity models lack the modern shulker template's held transform.
            // Apply it around the item center, including the mirrored left-hand rotation.
            //? if <1.21.4 {
            if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
                float rotation = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? -315 : 315;
                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
                poseStack.scale(0.4F, 0.4F, 0.4F);
                poseStack.translate(-0.5, -0.5, -0.5);
            }
            //? }

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
    //? }
    //? if >=1.21.4 {
    /*public static final ResourceLocation ID =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "box_block");
    public static final ResourceLocation FAVORITE_COLOR_MODEL =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "favorite_color_box");
    // The renderer of the creator's previews: this one, with room in the GUI for any turn
    public static final ResourceLocation PREVIEW_ID =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "creator_box");
    // The creator's box seen from the front, at the favorite color screen's angle
    public static final ResourceLocation CREATOR_BOX_MODEL =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "creator_box");
    // The creator's box as it stands in the world: at the vanilla block GUI angle once the box's
    // own half turn is added, and baked at four times the vanilla block GUI scale, so a stack
    // drawn at a quarter of that size lines up with a block item drawn at the same size
    public static final ResourceLocation CREATOR_BOX_WORLD_MODEL =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "creator_box_world");

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

    // Binds the creator's preview renderer, PREVIEW_ID, to an item model
    //? if >=26 {
    /^public record PreviewUnbaked() implements SpecialModelRenderer.Unbaked<ItemStack> {
    ^///? } else {
    public record PreviewUnbaked() implements SpecialModelRenderer.Unbaked {
    //? }
        public static final MapCodec<PreviewUnbaked> MAP_CODEC = MapCodec.unit(PreviewUnbaked::new);

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
            return new BoxBlockItemRenderer(true);
        }
    }
    *///? }

    //? if >=26 {
    /*@Override
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> extents) {
        // Minecraft transforms each vertex before computing the GUI bounds.
        // Opposite corners alone lose the width/depth of a rotated cube.
        // A preview may be turned to any angle and is lifted a pixel, so it reserves more.
        float low = preview ? 0.5f - TURN_REACH : 0;
        float high = preview ? 0.5f + TURN_REACH : 1;
        for (float x : new float[] {low, high}) {
            for (float y : new float[] {0, preview ? 1.0625f : 1}) {
                for (float z : new float[] {low, high}) {
                    extents.accept(new org.joml.Vector3f(x, y, z));
                }
            }
        }
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
        resetRenderEntity(geoBlockItem);
        CompoundTag blockEntityTag = BlockEntityItemData.read(stack);
        if (blockEntityTag != null) {
            renderEntity.loadForItemRendering(blockEntityTag);
        } else if (stack.getComponentsPatch().isEmpty()) {
            renderEntity.loadForItemRendering(new CompoundTag());
        }
        useCollectionBoxArt(geoBlockItem, blockEntityTag);
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
        // Model baking precedes block-entity renderer registration in 26.x.
        if (this.renderer == null) {
            this.renderer = new BoxBlockRenderer(GeoRendererContext.get());
        }
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState =
                this.renderer.createRenderState();
        this.renderer.extractRenderState(renderEntity, renderState, partialTick,
                net.minecraft.world.phys.Vec3.ZERO, null);
        // The entity has a level, so its state took the world's light at the origin;
        // a stack is lit by whatever holds it instead.
        renderState.lightCoords = packedLight;
        poseStack.pushPose();
        float yaw = previewYaw(BlockEntityItemData.read(stack));
        if (yaw != 0) {
            // A creator preview turns the box about its upright centre line
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(new org.joml.Matrix4f().rotationY((float) Math.toRadians(yaw)));
            poseStack.translate(-0.5, 0, -0.5);
        }
        if (preview || FAVORITE_COLOR_MODEL.equals(stack.get(DataComponents.ITEM_MODEL))) {
            // Preserve the legacy GUI-facing side and one-pixel lift for this preview.
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.mulPose(new org.joml.Matrix4f().rotationY((float) Math.PI));
            poseStack.translate(-0.5, -0.4375F, -0.5);
        }
        this.renderer.submit(renderState, poseStack, renderTasks,
                new net.minecraft.client.renderer.state.level.CameraRenderState());
        poseStack.popPose();
    }
    *///? } elif >=1.21.6 {
    /*@Override
    public void getExtents(java.util.Set<org.joml.Vector3f> extents) {
        // Include all corners so the rotated GUI bounds enclose every face.
        // A preview may be turned to any angle and is lifted a pixel, so it reserves more.
        float low = preview ? 0.5f - TURN_REACH : 0;
        float high = preview ? 0.5f + TURN_REACH : 1;
        for (float x : new float[] {low, high}) {
            for (float y : new float[] {0, preview ? 1.0625f : 1}) {
                for (float z : new float[] {low, high}) {
                    extents.add(new org.joml.Vector3f(x, y, z));
                }
            }
        }
    }
    *///? }
}
