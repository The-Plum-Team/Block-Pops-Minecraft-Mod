package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
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

    // Cache for GameProfiles created from skin snapshots to improve performance
    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();

    // Cache to track which player UUIDs have had their skins requested for live rendering
    private static final Map<UUID, Boolean> skinLoadRequests = new ConcurrentHashMap<>();

    // Cache to track which snapshot skins have been registered
    private static final Map<String, Boolean> snapshotRegistrationCache = new ConcurrentHashMap<>();

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
            // Check if this is a preview entity (rendered at BlockPos.ZERO in UI)
            // Preview entities should always show the live skin, not the snapshot
            boolean isPreview = animatable.getBlockPos().equals(net.minecraft.core.BlockPos.ZERO);

            if (!isPreview) {
                // This is a real placed box - check for snapshot
                String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
                String skinSnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);

                // If a snapshot exists, use it for permanent skin
                if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                    GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueFigureId, id -> {
                        GameProfile newProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                        newProfile.getProperties().put("textures", new Property("textures", skinSnapshot));
                        return newProfile;
                    });

                    // Register skin only once to avoid ConcurrentModificationException
                    snapshotRegistrationCache.computeIfAbsent(uniqueFigureId, id -> {
                        // Schedule registration on main thread to avoid threading issues
                        Minecraft.getInstance().execute(() -> {
                            Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {
                                // Skin loaded callback
                            }, true);
                        });
                        return true;
                    });

                    return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
                }
            }

            // Preview entity or no snapshot - show the live skin
            UUID playerUUID = figure.getPlayerUUID();

            // Try to get the profile from online players first (most reliable for live skin)
            if (Minecraft.getInstance().getConnection() != null) {
                var playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(playerUUID);
                if (playerInfo != null) {
                    // Player is online, use their skin directly
                    ResourceLocation skinLocation = playerInfo.getSkinLocation();
                    if (skinLocation != null) {
                        return skinLocation;
                    }
                }
            }

            // Player not online, request skin loading from Mojang
            skinLoadRequests.computeIfAbsent(playerUUID, uuid -> {
                // Schedule skin loading on the main thread
                Minecraft.getInstance().execute(() -> {
                    GameProfile profileForLoading = new GameProfile(uuid, figure.getName());
                    Minecraft.getInstance().getSkinManager().registerSkins(profileForLoading, (type, location, texture) -> {
                        // Skin loaded callback
                    }, true);
                });
                return true;
            });

            // Get the skin location - will use default until loaded
            GameProfile gameProfile = new GameProfile(playerUUID, figure.getName());
            ResourceLocation playerSkin = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
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
