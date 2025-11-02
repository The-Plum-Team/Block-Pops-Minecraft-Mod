package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for rendering figures in standalone figure blocks
 */
public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    // Fallback resources when figure is not available (prevents crashes during NBT sync)
    private static final ResourceLocation FALLBACK_MODEL = new ResourceLocation(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation FALLBACK_ANIMATION = new ResourceLocation(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");

    @Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback to prevent crashes when NBT data hasn't synced yet
            return FALLBACK_MODEL;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback texture instead of null to prevent crashes
            return FALLBACK_TEXTURE;
        }

        // Check if this is a player figure (dynamic skin)
        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            // Use Minecraft's skin manager to get the player's skin dynamically
            GameProfile gameProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            ResourceLocation playerSkin = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
            // Return fallback if skin is null (shouldn't happen but safety first)
            return playerSkin != null ? playerSkin : FALLBACK_TEXTURE;
        }

        // Static figure - use the predefined texture path
        ResourceLocation texturePath = figure.getTexturePath();
        return texturePath != null ? texturePath : FALLBACK_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback to prevent crashes when NBT data hasn't synced yet
            return FALLBACK_ANIMATION;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(FigureBlockEntity animatable, ResourceLocation texture) {
        // Use entityCutoutNoCull for proper rendering without culling issues
        ResourceLocation textureToUse = getTextureResource(animatable);
        // Safety check: use fallback if texture is somehow null
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}
