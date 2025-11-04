package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeoModel for rendering figures dynamically based on collection data
 */
public class FigureModel extends GeoModel<BoxBlockEntity> {
    // Fallback texture when figure is not available (uses default Steve skin)
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");

    // Cache to track which player UUIDs have had their skins requested
    private static final Map<UUID, Boolean> skinLoadRequests = new ConcurrentHashMap<>();

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
            UUID playerUUID = figure.getPlayerUUID();

            // Try to get the skin from online players first (most reliable)
            if (Minecraft.getInstance().getConnection() != null) {
                var onlinePlayer = Minecraft.getInstance().getConnection().getPlayerInfo(playerUUID);
                if (onlinePlayer != null) {
                    // Player is online, use their skin directly
                    ResourceLocation skinLocation = onlinePlayer.getSkinLocation();
                    if (skinLocation != null) {
                        BlockPopsMod.LOGGER.debug("Using online player skin for {}: {}", figure.getName(), skinLocation);
                        return skinLocation;
                    }
                }
            }

            // If player is not online, we need to fetch from Mojang
            // Request skin loading only once per UUID (prevents concurrent modification)
            skinLoadRequests.computeIfAbsent(playerUUID, uuid -> {
                BlockPopsMod.LOGGER.info("Requesting skin load for offline player: {} (UUID: {})", figure.getName(), uuid);
                // Schedule skin loading on the main thread to avoid concurrent modification
                Minecraft.getInstance().execute(() -> {
                    GameProfile gameProfile = new GameProfile(uuid, figure.getName());
                    Minecraft.getInstance().getSkinManager().registerSkins(gameProfile, (type, location, texture) -> {
                        // Skin loaded callback - texture is now available
                        BlockPopsMod.LOGGER.info("Skin loaded for {}: {}", figure.getName(), location);
                    }, true);
                });
                return true;
            });

            // Get the skin location - will be default until loaded
            GameProfile gameProfile = new GameProfile(playerUUID, figure.getName());
            ResourceLocation playerSkin = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
            BlockPopsMod.LOGGER.debug("Retrieved skin for {}: {}", figure.getName(), playerSkin);
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
