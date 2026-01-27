package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class ClawMachineBlockModel extends GeoModel<ClawMachineBlockEntity> {
    // GeckoLib 5: Simplified paths
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "block/claw_machine_block");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "textures/block/claw_machine_block.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "block/claw_machine_block");

    @Override
    public ResourceLocation getModelResource(GeoRenderState renderState) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ClawMachineBlockEntity animatable) {
        return ANIMATION;
    }
}
