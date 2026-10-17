package com.theplumteam.client.model;

import com.theplumteam.util.AuthlibProfiles;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
//? if >=1.21 {
/*import com.theplumteam.client.ClientSkinRegistration;
*///? }
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.runtime.FigureVariants;
import com.theplumteam.client.runtime.RuntimeGeo;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.PlayerSkins;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
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

public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocations.of("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation FALLBACK_ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation POSE_ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/figure_poses");

    //? if <26 {
    // The model the animation processor was last switched to, runtime or bundled
    private BakedGeoModel activeModel;
    //? }

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
    public void addAdditionalStateData(FigureBlockEntity animatable,
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
        ResourceLocation texture = drawnTexture(animatable);
        renderState.addGeckolibData(FIGURE_TEXTURE, texture != null ? texture : FALLBACK_TEXTURE);
    }
    *///? } elif >=1.21.2 {
    /*@Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable, GeoRenderer<FigureBlockEntity> renderer) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable, GeoRenderer<FigureBlockEntity> renderer) {
        return drawnTexture(animatable);
    }
    *///? } else {
    @Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
        return drawnTexture(animatable);
    }
    //? }

    /**
     * The model a skin is drawn with: its variant when it has a ready one, the default
     * figure while its own model is still being downloaded.
     */
    private static ResourceLocation drawnModel(FigureDefinition figure, int skinIndex) {
        FigureVariants.Variant variant = FigureVariants.resolve(figure, skinIndex);
        if (variant != null) {
            return variant.model();
        }
        ResourceLocation model = figure.getModelForSkinIndex(skinIndex);
        return RuntimeGeo.isPending(model) ? FigureDefinition.DEFAULT_MODEL : model;
    }

    /** The texture GeckoLib draws the figure with: a variant's combined texture when it has one. */
    private ResourceLocation drawnTexture(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure != null) {
            int skinIndex = animatable.getAlternativeSkinIndex();
            if (RuntimeGeo.isPending(figure.getModelForSkinIndex(skinIndex))) {
                return FALLBACK_TEXTURE;
            }
            FigureVariants.Variant variant = FigureVariants.resolve(figure, skinIndex);
            if (variant != null) {
                return variant.texture();
            }
        }
        return resolveTexture(animatable);
    }

    private static ResourceLocation unlessPending(ResourceLocation texture) {
        return RuntimeTextures.isPending(texture) ? FALLBACK_TEXTURE : texture;
    }

    private ResourceLocation resolveModel(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return FALLBACK_MODEL;
        return drawnModel(figure, animatable.getAlternativeSkinIndex());
    }

    private ResourceLocation resolveTexture(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return FALLBACK_TEXTURE;

        int skinIndex = animatable.getAlternativeSkinIndex();
        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                return unlessPending(figure.getAlternatives().get(altListIndex).texture());
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
                //? if >=1.21.9 {
                /*if (playerInfo != null) return playerInfo.getSkin().body().texturePath();
                *///? } elif >=1.21 {
                /*if (playerInfo != null) return playerInfo.getSkin().texture();
                *///? } else {
                if (playerInfo != null) return playerInfo.getSkinLocation();
                //? }
            }

            // 6. Absolute Fallback
            GameProfile profile = liveProfileCache.computeIfAbsent(figure.getPlayerUUID(), uuid ->
                    new GameProfile(uuid, figure.getName()));
            liveRegistrationCache.computeIfAbsent(figure.getPlayerUUID(), uuid -> {
                //? if >=1.21.9 {
                /*// The populated profile is a new one there, so it replaces the cached one.
                ClientSkinRegistration.register(profile, prepared -> liveProfileCache.put(uuid, prepared));
                *///? } elif >=1.21 {
                /*ClientSkinRegistration.register(profile);
                *///? } else {
                Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, p) -> {}, false);
                //? }
                return true;
            });
            return PlayerSkins.insecureTexture(profile);
        }

        return figure.getTexturePath() != null ? unlessPending(figure.getTexturePath()) : FALLBACK_TEXTURE;
    }

    private ResourceLocation getSkinLocationFromSnapshot(FigureDefinition figure, String snapshot) {
        UUID snapshotUUID = UUID.nameUUIDFromBytes((figure.getPlayerUUID().toString() + snapshot).getBytes());
        String uniqueCacheKey = snapshotUUID.toString();
        GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueCacheKey, id -> {
            //? if >=1.21.9 {
            /*// Authlib 9 profiles are immutable, so the snapshot goes in as the profile is built.
            return new GameProfile(snapshotUUID, figure.getName(), new com.mojang.authlib.properties.PropertyMap(
                    com.google.common.collect.ImmutableMultimap.of("textures", new Property("textures", snapshot))));
            *///? } else {
            GameProfile newProfile = new GameProfile(snapshotUUID, figure.getName());
            AuthlibProfiles.properties(newProfile).put("textures", new Property("textures", snapshot));
            return newProfile;
            //? }
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
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        // The pose file holds both Pose_Stand and Pose_Sit; a figure with its own bones names its own
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return POSE_ANIMATION;
        }
        int skinIndex = animatable.getAlternativeSkinIndex();
        ResourceLocation poses = figure.getPoseAnimationForSkinIndex(skinIndex);
        if (poses == null || RuntimeGeo.isPending(poses) || RuntimeGeo.isPending(figure.getModelForSkinIndex(skinIndex))) {
            return POSE_ANIMATION;
        }
        return poses;
    }

    // Models and animations that arrive while the game runs live outside GeckoLib's
    // caches (see RuntimeGeo), so both lookups ask there first.
    @Override
    public BakedGeoModel getBakedModel(ResourceLocation location) {
        BakedGeoModel runtime = RuntimeGeo.model(location);
        BakedGeoModel model = runtime != null ? runtime : super.getBakedModel(location);
        //? if <26 {
        // Below GeckoLib 5.5 the model in use is also the one whose bones the animation
        // processor drives; GeckoLib's own lookup switches it, so a runtime model must too.
        if (model != activeModel) {
            getAnimationProcessor().setActiveModel(model);
            activeModel = model;
        }
        //? }
        return model;
    }

    //? if >=26 {
    /*@Override
    public com.geckolib.cache.animation.Animation getBakedAnimation(FigureBlockEntity animatable, String name) {
        ResourceLocation poses = getAnimationResource(animatable);
        if (RuntimeGeo.ownsAnimations(poses)) {
            var animations = RuntimeGeo.animations(poses);
            return animations != null ? animations.getAnimation(name) : null;
        }
        return super.getBakedAnimation(animatable, name);
    }
    *///? } elif >=1.21 {
    /*@Override
    public software.bernie.geckolib.animation.Animation getAnimation(FigureBlockEntity animatable, String name) {
        ResourceLocation poses = getAnimationResource(animatable);
        if (RuntimeGeo.ownsAnimations(poses)) {
            var animations = RuntimeGeo.animations(poses);
            return animations != null ? animations.getAnimation(name) : null;
        }
        return super.getAnimation(animatable, name);
    }
    *///? } else {
    @Override
    public software.bernie.geckolib.core.animation.Animation getAnimation(FigureBlockEntity animatable, String name) {
        ResourceLocation poses = getAnimationResource(animatable);
        if (RuntimeGeo.ownsAnimations(poses)) {
            var animations = RuntimeGeo.animations(poses);
            return animations != null ? animations.getAnimation(name) : null;
        }
        return super.getAnimation(animatable, name);
    }
    //? }

    //? if >=1.21.9 {
    /*// GeckoLib 5.4 moved getRenderType off GeoModel and onto the renderer,
    // so the renderer that owns this model names the render type instead.
    *///? } elif >=1.21.5 {
    /*@Override
    public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture != null ? texture : FALLBACK_TEXTURE);
    }
    *///? } else {
    @Override
    public RenderType getRenderType(FigureBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = drawnTexture(animatable);
        if (textureToUse == null) textureToUse = FALLBACK_TEXTURE;
        return RenderType.entityCutoutNoCull(textureToUse);
    }
    //? }
}
