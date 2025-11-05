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
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FigureModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> snapshotRegistrationCache = new ConcurrentHashMap<>();

    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        return (figure != null) ? figure.getModelPath() : null;
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return FALLBACK_TEXTURE;

        int skinIndex = animatable.getAlternativeSkinIndex();
        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }

        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            // 1. ALWAYS prioritize the snapshot stored in NBT for placed blocks and items.
            String nbtSnapshot = animatable.getSkinSnapshot();
            if (nbtSnapshot != null && !nbtSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, nbtSnapshot);
            }

            // 2. For UI previews (BlockPos.ZERO), we want the live skin.
            if (animatable.getBlockPos().equals(BlockPos.ZERO)) {
                // First, check if we can find the live player info (most reliable)
                if (Minecraft.getInstance().getConnection() != null) {
                    PlayerInfo playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                    if (playerInfo != null) {
                        return playerInfo.getSkinLocation();
                    }
                }

                // If not found live, try falling back to the discovery snapshot so we at least show something custom in the menu
                String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
                String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
                if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                    return getSkinLocationFromSnapshot(figure, discoverySnapshot);
                }

                // Final fallback: Create profile and force registration (will eventually load)
                GameProfile liveProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                Minecraft.getInstance().getSkinManager().registerSkins(liveProfile, (type, location, profile) -> {}, true);
                return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(liveProfile);
            }

            // 3. Fallback for legacy placed blocks that have no snapshot in NBT.
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 4. Final, absolute fallback: fetch the live skin.
            GameProfile finalFallbackProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            Minecraft.getInstance().getSkinManager().registerSkins(finalFallbackProfile, (type, location, profile) -> {}, true);
            return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(finalFallbackProfile);
        }

        return figure.getTexturePath() != null ? figure.getTexturePath() : FALLBACK_TEXTURE;
    }

    private ResourceLocation getSkinLocationFromSnapshot(FigureDefinition figure, String snapshot) {
        UUID snapshotUUID = UUID.nameUUIDFromBytes((figure.getPlayerUUID().toString() + snapshot).getBytes());
        String uniqueCacheKey = snapshotUUID.toString();

        GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueCacheKey, id -> {
            GameProfile newProfile = new GameProfile(snapshotUUID, figure.getName());
            newProfile.getProperties().put("textures", new Property("textures", snapshot));
            return newProfile;
        });

        snapshotRegistrationCache.computeIfAbsent(uniqueCacheKey, id -> {
            Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {}, false);
            return true;
        });

        return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
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
        ResourceLocation textureToUse = getTextureResource(animatable);
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}