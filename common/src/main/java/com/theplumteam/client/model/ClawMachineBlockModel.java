package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
//? if >=1.21.5 {
/*import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? } elif >=1.21.2 {
/*import software.bernie.geckolib.renderer.GeoRenderer;
*///? }

public class ClawMachineBlockModel extends GeoModel<ClawMachineBlockEntity> {
    private static final ResourceLocation MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "block/claw_machine_block");
    private static final ResourceLocation TEXTURE = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/block/claw_machine_block.png");
    private static final ResourceLocation ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "block/claw_machine_block");

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getModelResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getModelResource(ClawMachineBlockEntity animatable, GeoRenderer<ClawMachineBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getModelResource(ClawMachineBlockEntity animatable) {
    //? }
        return MODEL;
    }

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getTextureResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getTextureResource(ClawMachineBlockEntity animatable, GeoRenderer<ClawMachineBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getTextureResource(ClawMachineBlockEntity animatable) {
    //? }
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ClawMachineBlockEntity animatable) {
        return ANIMATION;
    }
}
