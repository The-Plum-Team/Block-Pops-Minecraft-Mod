package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.PlushBlockEntity;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
//? if >=1.21.5 {
/*import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? } elif >=1.21.2 {
/*import software.bernie.geckolib.renderer.GeoRenderer;
*///? }

/** One instance per plush: every plush block entity type has its own renderer and model. */
public class PlushBlockModel extends GeoModel<PlushBlockEntity> {
    private static final ResourceLocation ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "block/plush");

    private final ResourceLocation model;
    // The same sheet the plush's item model reads from the item atlas.
    private final ResourceLocation texture;

    public PlushBlockModel(String plushId) {
        this.model = GeoAssets.model(BlockPopsMod.MOD_ID, "plush/" + plushId);
        this.texture = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/item/plush_" + plushId + ".png");
    }

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getModelResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getModelResource(PlushBlockEntity animatable, GeoRenderer<PlushBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getModelResource(PlushBlockEntity animatable) {
    //? }
        return model;
    }

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getTextureResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getTextureResource(PlushBlockEntity animatable, GeoRenderer<PlushBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getTextureResource(PlushBlockEntity animatable) {
    //? }
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(PlushBlockEntity animatable) {
        return ANIMATION;
    }
}
