package com.theplumteam.client.model;

import com.theplumteam.util.AuthlibProfiles;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
//? if >=1.21 {
/*import com.theplumteam.client.ClientSkinRegistration;
*///? }
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.PlayerSkins;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
//? if >=1.21.5 {
/*import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? } elif >=1.21.2 {
/*import software.bernie.geckolib.renderer.GeoRenderer;
*///? }

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FigureModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "figure/box_figure_default");
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocations.of("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation POSE_ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/figure_poses");

    // GeckoLib 5 resolves the model and texture from the render state alone, so
    // what the figure resolves to is carried across as render data.
    //? if >=1.21.5 {
    /*private static final DataTicket<ResourceLocation> FIGURE_MODEL =
            DataTicket.create("blockpops:figure_model", ResourceLocation.class);
    private static final DataTicket<ResourceLocation> FIGURE_TEXTURE =
            DataTicket.create("blockpops:figure_texture", ResourceLocation.class);
    *///? }

    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> snapshotRegistrationCache = new ConcurrentHashMap<>();
    private static final Map<UUID, GameProfile> liveProfileCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> liveRegistrationCache = new ConcurrentHashMap<>();

    // QuickSkin Reflection
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

    //? if >=1.21.5 {
    /*@Override
    public ResourceLocation getModelResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_MODEL, FALLBACK_MODEL);
    }

    @Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_TEXTURE, FALLBACK_TEXTURE);
    }

    @Override
    public void addAdditionalStateData(BoxBlockEntity animatable,
                                       //? if >=1.21.9 {
                                       /^Object relatedObject,
                                       ^///? }
                                       GeoRenderState renderState) {
        super.addAdditionalStateData(animatable,
                //? if >=1.21.9 {
                /^relatedObject,
                ^///? }
                renderState);
        ResourceLocation model = resolveModel(animatable);
        renderState.addGeckolibData(FIGURE_MODEL, model != null ? model : FALLBACK_MODEL);
        ResourceLocation texture = resolveTexture(animatable);
        renderState.addGeckolibData(FIGURE_TEXTURE, texture != null ? texture : FALLBACK_TEXTURE);
    }
    *///? } elif >=1.21.2 {
    /*@Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
        return resolveTexture(animatable);
    }
    *///? } else {
    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        return resolveTexture(animatable);
    }
    //? }

    private ResourceLocation resolveModel(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        return (figure != null) ? figure.getModelPath() : null;
    }

    /** Resolves a box's figure skin. Public so the box renderer can draw the face with it. */
    public ResourceLocation resolveTexture(BoxBlockEntity animatable) {
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
            // 1. Quick Skin Snapshot (from NBT) - PRIORITY #1
            // If the box was created while the player had a Quick Skin, use that stored ID.
            String qsId = animatable.getQuickSkinId();
            if (qsId != null && !qsId.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(qsId);
                if (loc != null) return loc;
            }

            // 2. Mojang Snapshot (from NBT) - PRIORITY #2
            String nbtSnapshot = animatable.getSkinSnapshot();
            if (nbtSnapshot != null && !nbtSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, nbtSnapshot);
            }

            // 3. Live Quick Skin - PRIORITY #3
            // Fallback if no snapshot exists (e.g. preview before drop).
            if (quickSkinAvailable) {
                ResourceLocation liveQS = getLiveQuickSkin(figure.getPlayerUUID());
                if (liveQS != null) return liveQS;
            }

            // 4. Live Mojang Skin - PRIORITY #4
            // Check PlayerInfo for both items AND placed blocks.
            // This allows client-side skin mods that update the player's connection info to work.
            if (Minecraft.getInstance().getConnection() != null) {
                PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                //? if >=1.21 {
                /*if (info != null) return info.getSkin().texture();
                *///? } else {
                if (info != null) return info.getSkinLocation();
                //? }
            }

            // 5. Discovery Snapshot Fallback
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();

            // 5a. Discovery Quick Skin (NEW)
            String discoveryQuickSkin = ClientDiscoveryManager.getFigureQuickSkin(uniqueFigureId);
            if (discoveryQuickSkin != null && !discoveryQuickSkin.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(discoveryQuickSkin);
                if (loc != null) return loc;
            }

            // 5b. Discovery Mojang Skin
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 6. Absolute Fallback
            GameProfile profile = liveProfileCache.computeIfAbsent(figure.getPlayerUUID(), uuid ->
                    new GameProfile(uuid, figure.getName()));
            liveRegistrationCache.computeIfAbsent(figure.getPlayerUUID(), uuid -> {
                //? if >=1.21 {
                /*ClientSkinRegistration.register(profile);
                *///? } else {
                Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, p) -> {}, false);
                //? }
                return true;
            });
            return PlayerSkins.insecureTexture(profile);
        }

        return figure.getTexturePath() != null ? figure.getTexturePath() : FALLBACK_TEXTURE;
    }

    private ResourceLocation getSkinLocationFromSnapshot(FigureDefinition figure, String snapshot) {
        UUID snapshotUUID = UUID.nameUUIDFromBytes((figure.getPlayerUUID().toString() + snapshot).getBytes());
        String uniqueCacheKey = snapshotUUID.toString();
        GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueCacheKey, id -> {
            GameProfile newProfile = new GameProfile(snapshotUUID, figure.getName());
            AuthlibProfiles.properties(newProfile).put("textures", new Property("textures", snapshot));
            return newProfile;
        });
        snapshotRegistrationCache.computeIfAbsent(uniqueCacheKey, id -> {
            //? if >=1.21 {
            /*ClientSkinRegistration.register(profile);
            *///? } else {
            Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {}, false);
            //? }
            return true;
        });
        return PlayerSkins.insecureTexture(profile);
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        // Always return the pose animation file which contains both Pose_Stand and Pose_Sit
        return POSE_ANIMATION;
    }

    //? if >=1.21.5 {
    /*@Override
    public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture != null ? texture : FALLBACK_TEXTURE);
    }
    *///? } else {
    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = resolveTexture(animatable);
        if (textureToUse == null) textureToUse = FALLBACK_TEXTURE;
        return RenderType.entityCutoutNoCull(textureToUse);
    }
    //? }
}
