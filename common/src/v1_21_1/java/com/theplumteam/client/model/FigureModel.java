package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.UUID;

public class FigureModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation POSE_ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "animations/figure/figure_poses.animation.json");

    private static boolean checkedQuickSkin = false;
    private static boolean quickSkinAvailable = false;
    private static java.lang.reflect.Method getSkinLocationMethod; // From SkinService
    private static Object skinServiceInstance;

    private static ResourceLocation resolveQuickSkinId(String skinId) {
        if (!checkedQuickSkin) {
            try {
                // Use SkinService to resolve arbitrary skin IDs (local_skin:HASH)
                Class<?> serviceClass = Class.forName("com.quickskin.mod.client.services.SkinService");
                java.lang.reflect.Method getInstanceMethod = serviceClass.getMethod("getInstance");
                skinServiceInstance = getInstanceMethod.invoke(null);
                // getSkinLocation(UUID, String) - UUID can be null for local skins
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

    // Helper for live lookup (via PlayerAppearanceService)
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
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();

            // 1. Box NBT Quick Skin (Top Priority - Specific Box)
            String qsId = animatable.getQuickSkinId();
            if (qsId != null && !qsId.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(qsId);
                if (loc != null) return loc;
            }

            // 2. Box NBT Mojang Snapshot (Specific Box)
            String nbtSnapshot = animatable.getSkinSnapshot();
            if (nbtSnapshot != null && !nbtSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, nbtSnapshot);
            }

            // 3. Discovery Quick Skin (Fallback - General Collection)
            // MOVED UP: If box has no specific data, check what the player discovered originally.
            String discoveryQuickSkin = ClientDiscoveryManager.getFigureQuickSkin(uniqueFigureId);
            if (discoveryQuickSkin != null && !discoveryQuickSkin.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(discoveryQuickSkin);
                if (loc != null) return loc;
            }

            // 4. Discovery Mojang Snapshot (Fallback - General Collection)
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 5. Live Quick Skin (Last Resort - Active Player Look)
            if (quickSkinAvailable) {
                ResourceLocation liveQS = getLiveQuickSkin(figure.getPlayerUUID());
                if (liveQS != null) return liveQS;
            }

            // 6. Live Mojang Skin
            if (Minecraft.getInstance().getConnection() != null) {
                PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                if (info != null) return info.getSkin().texture();
            }

            return FALLBACK_TEXTURE;
        }

        return figure.getTexturePath() != null ? figure.getTexturePath() : FALLBACK_TEXTURE;
    }

    /**
     * Converts the Base64 texture string back into a ResourceLocation
     * using Minecraft's SkinManager.
     */
    private ResourceLocation getSkinLocationFromSnapshot(FigureDefinition figure, String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return FALLBACK_TEXTURE;
        }

        try {
            // Reconstruct a temporary GameProfile with the saved texture data
            GameProfile profile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            profile.getProperties().put("textures", new Property("textures", snapshot));

            // Use Minecraft's SkinManager to process the property and get the cached skin location
            // getInsecureSkin skips session verification, which is appropriate for stored texture data
            return Minecraft.getInstance().getSkinManager().getInsecureSkin(profile).texture();
        } catch (Exception e) {
            return FALLBACK_TEXTURE;
        }
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        return POSE_ANIMATION;
    }

    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = getTextureResource(animatable);
        if (textureToUse == null) textureToUse = FALLBACK_TEXTURE;
        return RenderType.entityTranslucent(textureToUse);
    }
}
