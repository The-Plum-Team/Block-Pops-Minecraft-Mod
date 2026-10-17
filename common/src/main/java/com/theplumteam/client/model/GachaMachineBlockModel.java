package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.GachaMachineBlockEntity;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
//? if >=1.21.5 {
/*import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? } elif >=1.21.2 {
/*import software.bernie.geckolib.renderer.GeoRenderer;
*///? }

public class GachaMachineBlockModel extends GeoModel<GachaMachineBlockEntity> {
    private static final ResourceLocation MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "block/gacha_machine_block");
    // Kept out of textures/block: the sheet is 128x64, which the block atlas would read as two animation frames.
    private static final ResourceLocation TEXTURE = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/entity/gacha_machine_block.png");
    private static final ResourceLocation ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "block/gacha_machine_block");

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getModelResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getModelResource(GachaMachineBlockEntity animatable, GeoRenderer<GachaMachineBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getModelResource(GachaMachineBlockEntity animatable) {
    //? }
        return MODEL;
    }

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getTextureResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getTextureResource(GachaMachineBlockEntity animatable, GeoRenderer<GachaMachineBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getTextureResource(GachaMachineBlockEntity animatable) {
    //? }
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(GachaMachineBlockEntity animatable) {
        return ANIMATION;
    }
}
