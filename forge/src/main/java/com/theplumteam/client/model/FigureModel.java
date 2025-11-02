package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for rendering figures dynamically based on collection data
 */
public class FigureModel extends GeoModel<BoxBlockEntity> {
    // Fallback texture when figure is not available (uses default Steve skin)
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");

    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback texture instead of null to prevent crashes
            return FALLBACK_TEXTURE;
        }

        // Check for alternative skins
        int skinIndex = animatable.getAlternativeSkinIndex();

        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                // Return the alternative texture
                return figure.getAlternatives().get(altListIndex).texture();
            }
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
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        // Use entityCutoutNoCull for proper rendering without culling issues
        ResourceLocation textureToUse = getTextureResource(animatable);
        // Safety check: use fallback if texture is somehow null
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}
