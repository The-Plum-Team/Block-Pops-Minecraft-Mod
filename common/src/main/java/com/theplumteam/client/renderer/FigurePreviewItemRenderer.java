package com.theplumteam.client.renderer;

//? if >=1.21.4 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.GreyedTextures;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
*///? }
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }

/**
 * Draws one figure of a collection screen as an item. From 1.21.4 that is how the screen shows
 * its figures: the item's model holds the screen's camera, and from 1.21.6 an item is the one
 * way a model of this mod reaches the deferred interface. Older versions draw the figure
 * straight into the screen instead and never bind this renderer.
 */
public class FigurePreviewItemRenderer
    //? if >=1.21.4 {
    /*implements SpecialModelRenderer<ItemStack>
    *///? }
{
    //? if >=1.21.4 {
    /*public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "figure_preview");
    // The item model that binds this renderer; its GUI display bakes this scale in.
    public static final ResourceLocation MODEL = ResourceLocations.of(BlockPopsMod.MOD_ID, "figure_preview");
    public static final float BAKED_SCALE = 4.0f;

    private static final String COLLECTION = "Collection";
    private static final String FIGURE = "Figure";
    private static final String GREYED = "Greyed";
    private static final String HOLOGRAPHIC = "Holographic";

    private record Preview(BoxBlockEntity entity, boolean greyed) {
    }

    // The stack a screen draws to show one figure, in black and white or holographic when asked.
    public static ItemStack stack(String collectionId, String figureId, boolean greyed, boolean holographic) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, MODEL);
        CompoundTag tag = new CompoundTag();
        tag.putString(COLLECTION, collectionId);
        tag.putString(FIGURE, figureId);
        tag.putBoolean(GREYED, greyed);
        tag.putBoolean(HOLOGRAPHIC, holographic);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static Preview read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        //? if >=1.21.5 {
        /^String collectionId = tag.getStringOr(COLLECTION, "");
        String figureId = tag.getStringOr(FIGURE, "");
        boolean greyed = tag.getBooleanOr(GREYED, false);
        boolean holographic = tag.getBooleanOr(HOLOGRAPHIC, false);
        ^///? } else {
        String collectionId = tag.getString(COLLECTION);
        String figureId = tag.getString(FIGURE);
        boolean greyed = tag.getBoolean(GREYED);
        boolean holographic = tag.getBoolean(HOLOGRAPHIC);
        //? }
        FigureDefinition figure = CollectionRegistry.getFigure(collectionId, figureId).orElse(null);
        BoxBlockEntity entity = FigureWidgetRenderer.getOrCreateRenderEntity(figure, collectionId);
        if (entity == null) {
            return null;
        }
        // One entity draws every preview of a figure, so each stack says how this one looks
        entity.setHolographicForRendering(holographic && !greyed);
        return new Preview(entity, greyed);
    }

    @Override
    public ItemStack extractArgument(ItemStack stack) {
        // Snapshot the stack so the render pass never observes a later mutation.
        return stack.copy();
    }

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
            return new FigurePreviewItemRenderer();
        }
    }
    *///? }

    //? if >=26 {
    /*@Override
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> extents) {
        // A plush reaches about twice a figure's width and above its head.
        for (float x = -0.5f; x <= 1.5f; x += 2) {
            for (float y = 0; y <= 2; y += 2) {
                for (float z = -0.5f; z <= 1.5f; z += 2) {
                    extents.accept(new org.joml.Vector3f(x, y, z));
                }
            }
        }
    }

    @Override
    public void submit(ItemStack stack, PoseStack poseStack,
                       net.minecraft.client.renderer.SubmitNodeCollector renderTasks,
                       int packedLight, int packedOverlay, boolean hasGlint, int outlineColor) {
        Preview preview = read(stack);
        if (preview == null) {
            return;
        }
        var renderer = FigureWidgetRenderer.getRenderer();
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState =
                renderer.createRenderState();
        renderer.extractRenderState(preview.entity(), renderState, partialTick,
                net.minecraft.world.phys.Vec3.ZERO, null);
        // The entity has a level, so its state took the world's light at the origin;
        // the screen lights the figure instead.
        renderState.lightCoords = packedLight;
        if (preview.greyed()) {
            software.bernie.geckolib.renderer.base.GeoRenderState geoState =
                    (software.bernie.geckolib.renderer.base.GeoRenderState) renderState;
            ResourceLocation greyed = GreyedTextures.of(FigureWidgetRenderer.getModel().getTextureResource(geoState));
            if (greyed == null) {
                return;
            }
            FigureModel.overrideTexture(geoState, greyed);
        }
        renderer.submit(renderState, poseStack, renderTasks,
                new net.minecraft.client.renderer.state.level.CameraRenderState());
    }
    *///? } elif >=1.21.5 {
    /*@Override
    public void render(ItemStack stack, net.minecraft.world.item.ItemDisplayContext displayContext,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                       boolean hasGlint) {
        Preview preview = read(stack);
        if (preview == null) {
            return;
        }
        FigureModel model = FigureWidgetRenderer.getModel();
        var renderer = FigureWidgetRenderer.getRenderer();
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        // GeckoLib 5 resolves everything from a render state, so this builds the same
        // state the block renderer would and draws from that.
        software.bernie.geckolib.renderer.base.GeoRenderState renderState =
                renderer.fillRenderState(preview.entity(), null,
                        new software.bernie.geckolib.renderer.base.GeoRenderState.Impl(), partialTick);
        ResourceLocation texture = model.getTextureResource(renderState);
        if (preview.greyed()) {
            texture = GreyedTextures.of(texture);
            if (texture == null) {
                return;
            }
        }
        net.minecraft.client.renderer.RenderType renderType = model.getRenderType(renderState, texture);
        poseStack.pushPose();
        // The renderer centres the block in a hook this direct call never reaches.
        poseStack.translate(0.5, 0, 0.5);
        renderer.actuallyRender(renderState, poseStack, model.getBakedModel(model.getModelResource(renderState)),
                renderType, bufferSource, bufferSource.getBuffer(renderType), false,
                packedLight, packedOverlay, 0xFFFFFFFF);
        poseStack.popPose();
    }

    //? if >=1.21.6 {
    /^@Override
    public void getExtents(java.util.Set<org.joml.Vector3f> extents) {
        // A plush reaches about twice a figure's width and above its head.
        for (float x = -0.5f; x <= 1.5f; x += 2) {
            for (float y = 0; y <= 2; y += 2) {
                for (float z = -0.5f; z <= 1.5f; z += 2) {
                    extents.add(new org.joml.Vector3f(x, y, z));
                }
            }
        }
    }
    ^///? }
    *///? } elif >=1.21.4 {
    /*@Override
    public void render(ItemStack stack, net.minecraft.world.item.ItemDisplayContext displayContext,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                       boolean hasGlint) {
        Preview preview = read(stack);
        if (preview == null) {
            return;
        }
        BoxBlockEntity entity = preview.entity();
        FigureModel model = FigureWidgetRenderer.getModel();
        var renderer = FigureWidgetRenderer.getRenderer();
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        ResourceLocation texture = model.getTextureResource(entity, null);
        // The model resolves its own texture again and ignores the one it is handed, so the
        // black-and-white copy gets its render type directly.
        net.minecraft.client.renderer.RenderType renderType;
        if (preview.greyed()) {
            texture = GreyedTextures.of(texture);
            if (texture == null) {
                return;
            }
            renderType = net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture);
        } else {
            renderType = model.getRenderType(entity, texture);
        }
        poseStack.pushPose();
        // The renderer centres the block in a hook this direct call never reaches.
        poseStack.translate(0.5, 0, 0.5);
        renderer.actuallyRender(poseStack, entity, model.getBakedModel(model.getModelResource(entity, null)),
                renderType, bufferSource, bufferSource.getBuffer(renderType), false, partialTick,
                packedLight, packedOverlay, 0xFFFFFFFF);
        poseStack.popPose();
    }
    *///? }
}
