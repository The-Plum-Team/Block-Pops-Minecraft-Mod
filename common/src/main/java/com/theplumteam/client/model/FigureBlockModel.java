package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation FALLBACK_ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");
    private static final ResourceLocation POSE_ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "animations/figure/figure_poses.animation.json");

    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> snapshotRegistrationCache = new ConcurrentHashMap<>();
    private static final Map<UUID, GameProfile> liveProfileCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> liveRegistrationCache = new ConcurrentHashMap<>();

    private static boolean checkedQuickSkin = false;
    private static boolean quickSkinAvailable = false;
    private static java.lang.reflect.Method getSkinLocationMethod;
    private static Object skinServiceInstance;

    private static ResourceLocation resolveQuickSkinId(String skinId) {
        if (!checkedQuickSkin) {
            try {
                Class<?> serviceClass = Class.forName("com.quickskin.mod.client.services.SkinService");
                java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
                skinServiceInstance = getInstanceMethod.invoke(null);
                getSkinLocationMethod = serviceClass.getMethod("getSkinLocation", UUID.class, String.class);
                quickSkinAvailable = true;
            } catch (Exception e) {
                quickSkinAvailable = false;
            }
            checkedQuickSkin = true;
        }

        if (quickSkinAvailable && skinServiceInstance != null && skinId != null) {
            try {
                return (ResourceLocation) getSkinLocationMethod.invoke(skinServiceInstance, null, skinId);
            } catch (Exception e) {
                // Ignore
            }
        }
        return null;
    }

    private static ResourceLocation getLiveQuickSkin(UUID uuid) {
        try {
            Class<?> serviceClass = Class.forName("com.quickskin.mod.client.services.PlayerAppearanceService");
            java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            java.lang.reflect.Method getLocMethod = serviceClass.getMethod("getSkinLocation", UUID.class);
            return (ResourceLocation) getLocMethod.invoke(instance, uuid);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return FALLBACK_MODEL;
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
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
            // 1. Quick Skin Snapshot (from NBT)
            String qsId = animatable.getQuickSkinId();
            if (qsId != null && !qsId.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(qsId);
                if (loc != null) return loc;
            }

            // 2. Mojang Snapshot (from NBT)
            String blockSnapshot = animatable.getSkinSnapshot();
            if (blockSnapshot != null && !blockSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, blockSnapshot);
            }

            // 3. Live Quick Skin
            if (quickSkinAvailable) {
                ResourceLocation liveQS = getLiveQuickSkin(figure.getPlayerUUID());
                if (liveQS != null) return liveQS;
            }

            // 4. Discovery Snapshot Fallback
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 5. Live Mojang Fallback - Check PlayerInfo even for blocks
            if (Minecraft.getInstance().getConnection() != null) {
                PlayerInfo playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                if (playerInfo != null) return playerInfo.getSkin().texture();
            }

            // 6. Absolute Fallback - use the default Steve texture
            // In 1.21.1+, async skin loading would be required for proper profile-based loading
            return FALLBACK_TEXTURE;
        }

        return figure.getTexturePath() != null ? figure.getTexturePath() : FALLBACK_TEXTURE;
    }

    private ResourceLocation getSkinLocationFromSnapshot(FigureDefinition figure, String snapshot) {
        // In 1.21.1+, skin snapshot loading requires async handling
        // For now, return fallback - the snapshot system would need to be reworked
        // to use the new PlayerSkin async loading API
        return FALLBACK_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        // Always return the pose animation file which contains both Pose_Stand and Pose_Sit
        return POSE_ANIMATION;
    }

    @Override
    public RenderType getRenderType(FigureBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = getTextureResource(animatable);
        if (textureToUse == null) textureToUse = FALLBACK_TEXTURE;
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}
