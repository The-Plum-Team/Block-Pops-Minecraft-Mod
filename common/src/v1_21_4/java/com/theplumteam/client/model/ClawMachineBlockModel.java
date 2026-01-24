package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

public class ClawMachineBlockModel extends GeoModel<ClawMachineBlockEntity> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "geo/block/claw_machine_block.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "textures/block/claw_machine_block.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "animations/block/claw_machine_block.animation.json");

    @Override
    public ResourceLocation getModelResource(ClawMachineBlockEntity animatable, GeoRenderer<ClawMachineBlockEntity> renderer) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ClawMachineBlockEntity animatable, GeoRenderer<ClawMachineBlockEntity> renderer) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ClawMachineBlockEntity animatable) {
        return ANIMATION;
    }
}
