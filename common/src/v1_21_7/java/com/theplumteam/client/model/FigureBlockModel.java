package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.util.SkinModelDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.GeckoLibResources;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.constant.dataticket.SerializableDataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import com.theplumteam.client.renderer.FigureBoneTextureLayer;

import java.util.UUID;

public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    private static final ResourceLocation FALLBACK_MODEL = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation FALLBACK_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation POSE_ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "figure/figure_poses");

    // Data tickets for GeckoLib 5 - use create() for custom types
    private static final DataTicket<ResourceLocation> FIGURE_MODEL = SerializableDataTicket.create(
        "figure_model", ResourceLocation.class, ResourceLocation.STREAM_CODEC);
    private static final DataTicket<ResourceLocation> FIGURE_TEXTURE = SerializableDataTicket.create(
        "figure_texture", ResourceLocation.class, ResourceLocation.STREAM_CODEC);
    private static final DataTicket<Boolean> HAS_FIGURE = SerializableDataTicket.ofBoolean(
        ResourceLocation.fromNamespaceAndPath("blockpops", "has_figure"));
    private static final DataTicket<Boolean> IS_SLIM = SerializableDataTicket.ofBoolean(
        ResourceLocation.fromNamespaceAndPath("blockpops", "is_slim"));

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
    public ResourceLocation getModelResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_MODEL, FALLBACK_MODEL);
    }

    @Override
    public BakedGeoModel getBakedModel(ResourceLocation location) {
        // Check if model exists in cache before calling super, fall back to default if missing
        if (GeckoLibResources.getBakedModels().get(location) == null
                && GeckoLibResources.getBakedModels().get(GeckoLibResources.stripPrefixAndSuffix(location)) == null) {
            return super.getBakedModel(FALLBACK_MODEL);
        }
        return super.getBakedModel(location);
    }

    @Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(FIGURE_TEXTURE, FALLBACK_TEXTURE);
    }

    // Helper method - moved from getTextureResource for use in addAdditionalStateData
    private ResourceLocation resolveTexture(FigureBlockEntity animatable) {
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

            // 1. NBT Quick Skin (Specific Box)
            String qsId = animatable.getQuickSkinId();
            if (qsId != null && !qsId.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(qsId);
                if (loc != null) return loc;
            }

            // 2. NBT Mojang Snapshot (Specific Box)
            String blockSnapshot = animatable.getSkinSnapshot();
            if (blockSnapshot != null && !blockSnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, blockSnapshot);
            }

            // 3. Discovery Quick Skin (Fallback)
            String discoveryQuickSkin = ClientDiscoveryManager.getFigureQuickSkin(uniqueFigureId);
            if (discoveryQuickSkin != null && !discoveryQuickSkin.isEmpty()) {
                ResourceLocation loc = resolveQuickSkinId(discoveryQuickSkin);
                if (loc != null) return loc;
            }

            // 4. Discovery Mojang Snapshot (Fallback)
            String discoverySnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);
            if (discoverySnapshot != null && !discoverySnapshot.isEmpty()) {
                return getSkinLocationFromSnapshot(figure, discoverySnapshot);
            }

            // 5. Live Quick Skin
            if (quickSkinAvailable) {
                ResourceLocation liveQS = getLiveQuickSkin(figure.getPlayerUUID());
                if (liveQS != null) return liveQS;
            }

            // 6. Live Mojang
            if (Minecraft.getInstance().getConnection() != null) {
                PlayerInfo playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(figure.getPlayerUUID());
                if (playerInfo != null) return playerInfo.getSkin().texture();
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
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure != null) {
            // If variant uses a different model, use standard pose animation
            int skinIndex = animatable.getAlternativeSkinIndex();
            if (skinIndex > 0 && figure.hasAlternatives()) {
                ResourceLocation altModel = figure.getModelForSkinIndex(skinIndex);
                if (altModel != null && !altModel.equals(figure.getModelPath())) {
                    return POSE_ANIMATION;
                }
            }

            if (figure.getPoseAnimationPath() != null) {
                return figure.getPoseAnimationPath();
            }
        }
        return POSE_ANIMATION;
    }

    @Override
    public void addAdditionalStateData(FigureBlockEntity animatable, GeoRenderState renderState) {
        super.addAdditionalStateData(animatable, renderState);

        boolean hasFigure = animatable.hasFigure();
        renderState.addGeckolibData(HAS_FIGURE, hasFigure);

        // Resolve and store model and texture paths
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure != null) {
            ResourceLocation texture = resolveTexture(animatable);
            renderState.addGeckolibData(FIGURE_MODEL, figure.getModelForSkinIndex(animatable.getAlternativeSkinIndex()));
            renderState.addGeckolibData(FIGURE_TEXTURE, texture);
            renderState.addGeckolibData(FigureBoneTextureLayer.FIGURE_DEF_TICKET, figure);

            // Detect skin model for arm visibility
            SkinModelDetector.SkinModel skinModel = SkinModelDetector.detectSkinModel(texture);
            renderState.addGeckolibData(IS_SLIM, skinModel == SkinModelDetector.SkinModel.SLIM);
        } else {
            renderState.addGeckolibData(FIGURE_MODEL, FALLBACK_MODEL);
            renderState.addGeckolibData(FIGURE_TEXTURE, FALLBACK_TEXTURE);
            renderState.addGeckolibData(IS_SLIM, false);
        }
    }

    @Override
    public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture, true);
    }

    // Note: Arm visibility would need to be handled via a custom GeoRenderLayer in GeckoLib 5
    // For now, we'll keep the basic rendering functional
}
