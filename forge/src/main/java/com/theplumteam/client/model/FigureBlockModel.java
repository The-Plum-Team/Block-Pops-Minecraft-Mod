package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = new ResourceLocation(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation FALLBACK_ANIMATION = new ResourceLocation(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");
    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> snapshotRegistrationCache = new ConcurrentHashMap<>();

    @Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return FALLBACK_MODEL;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return FALLBACK_TEXTURE;
        }

        int skinIndex = animatable.getAlternativeSkinIndex();
        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }

        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            // 1. ALWAYS prioritize the snapshot stored in NBT for placed blocks and items.
            String blockSnapshot = animatable.getSkinSnapshot();
            if (blockSnapshot != null && !blockSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, blockSnapshot);
            }

            // 2. If NO NBT snapshot exists, it's likely a legacy block. Fallback to discovery manager.
            // Note: FigureBlock items are not used for live previews, so we don't need the BlockPos.ZERO check here.
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 3. Final, absolute fallback: fetch the live skin.
            GameProfile finalFallbackProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(finalFallbackProfile);
        }

        ResourceLocation texturePath = figure.getTexturePath();
        return texturePath != null ? texturePath : FALLBACK_TEXTURE;
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
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return FALLBACK_ANIMATION;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(FigureBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = getTextureResource(animatable);
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}