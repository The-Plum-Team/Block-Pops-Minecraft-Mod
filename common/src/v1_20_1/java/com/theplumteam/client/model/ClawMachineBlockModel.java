package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ClawMachineBlockModel extends GeoModel<ClawMachineBlockEntity> {
    private static final ResourceLocation MODEL = new ResourceLocation(BlockPopsMod.MOD_ID, "geo/block/claw_machine_block.geo.json");
    private static final ResourceLocation TEXTURE = new ResourceLocation(BlockPopsMod.MOD_ID, "textures/block/claw_machine_block.png");
    private static final ResourceLocation ANIMATION = new ResourceLocation(BlockPopsMod.MOD_ID, "animations/block/claw_machine_block.animation.json");

    @Override
    public ResourceLocation getModelResource(ClawMachineBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ClawMachineBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ClawMachineBlockEntity animatable) {
        return ANIMATION;
    }
}
