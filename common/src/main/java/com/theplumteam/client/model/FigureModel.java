package com.theplumteam.client.model;

import com.theplumteam.client.QuickSkinClient;
import com.theplumteam.util.AuthlibProfiles;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
//? if >=1.21 {
/*import com.theplumteam.client.ClientSkinRegistration;
*///? }
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.renderer.HolographicRenderTypes;
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

public class FigureModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "figure/box_figure_default");
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocations.of("minecraft", "textures/entity/player/wide/steve.png");
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

    //? if >=1.21.5 {
    /*@Override
    public ResourceLocation getModelResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_MODEL, FALLBACK_MODEL);
    }

    @Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_TEXTURE, FALLBACK_TEXTURE);
    }

    // Swaps the texture a state resolved to, for a caller that draws with a copy of it.
    public static void overrideTexture(GeoRenderState renderState, ResourceLocation texture) {
        renderState.addGeckolibData(FIGURE_TEXTURE, texture);
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
        ResourceLocation texture = drawnTexture(animatable);
        renderState.addGeckolibData(FIGURE_TEXTURE, texture != null ? texture : FALLBACK_TEXTURE);
        renderState.addGeckolibData(HolographicRenderTypes.STATE, animatable.isHolographic());
    }
    *///? } elif >=1.21.2 {
    /*@Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
        return drawnTexture(animatable);
    }
    *///? } else {
    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        return resolveModel(animatable);
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
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
    private ResourceLocation drawnTexture(BoxBlockEntity animatable) {
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

    private ResourceLocation resolveModel(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        return (figure != null) ? drawnModel(figure, animatable.getAlternativeSkinIndex()) : null;
    }

    /** Resolves a box's figure skin. Public so the box renderer can draw the face with it. */
    public ResourceLocation resolveTexture(BoxBlockEntity animatable) {
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
            // 1. Quick Skin Snapshot (from NBT) - PRIORITY #1
            // If the box was created while the player had a Quick Skin, use that stored ID.
            String qsId = animatable.getQuickSkinId();
            if (qsId != null && !qsId.isEmpty()) {
                ResourceLocation loc = QuickSkinClient.skin(qsId);
                if (loc != null) return loc;
            }

            // 2. Mojang Snapshot (from NBT) - PRIORITY #2
            String nbtSnapshot = animatable.getSkinSnapshot();
            if (nbtSnapshot != null && !nbtSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, nbtSnapshot);
            }

            // 3. Live Quick Skin - PRIORITY #3
            // Fallback if no snapshot exists (e.g. preview before drop).
            ResourceLocation liveQS = QuickSkinClient.playerSkin(figure.getPlayerUUID());
            if (liveQS != null) return liveQS;

            // 4. Live Mojang Skin - PRIORITY #4
            // Check PlayerInfo for both items AND placed blocks.
            // This allows client-side skin mods that update the player's connection info to work.
            if (Minecraft.getInstance().getConnection() != null) {
                PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                //? if >=1.21.9 {
                /*if (info != null) return info.getSkin().body().texturePath();
                *///? } elif >=1.21 {
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
                ResourceLocation loc = QuickSkinClient.skin(discoveryQuickSkin);
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
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
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
    public com.geckolib.cache.animation.Animation getBakedAnimation(BoxBlockEntity animatable, String name) {
        ResourceLocation poses = getAnimationResource(animatable);
        if (RuntimeGeo.ownsAnimations(poses)) {
            var animations = RuntimeGeo.animations(poses);
            return animations != null ? animations.getAnimation(name) : null;
        }
        return super.getBakedAnimation(animatable, name);
    }
    *///? } elif >=1.21 {
    /*@Override
    public software.bernie.geckolib.animation.Animation getAnimation(BoxBlockEntity animatable, String name) {
        ResourceLocation poses = getAnimationResource(animatable);
        if (RuntimeGeo.ownsAnimations(poses)) {
            var animations = RuntimeGeo.animations(poses);
            return animations != null ? animations.getAnimation(name) : null;
        }
        return super.getAnimation(animatable, name);
    }
    *///? } else {
    @Override
    public software.bernie.geckolib.core.animation.Animation getAnimation(BoxBlockEntity animatable, String name) {
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
        ResourceLocation drawn = texture != null ? texture : FALLBACK_TEXTURE;
        if (renderState.getOrDefaultGeckolibData(HolographicRenderTypes.STATE, false)) {
            return HolographicRenderTypes.entity(drawn);
        }
        return RenderType.entityCutoutNoCull(drawn);
    }
    *///? } else {
    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        ResourceLocation textureToUse = drawnTexture(animatable);
        if (textureToUse == null) textureToUse = FALLBACK_TEXTURE;
        if (animatable.isHolographic()) {
            return HolographicRenderTypes.entity(textureToUse);
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
    //? }
}
