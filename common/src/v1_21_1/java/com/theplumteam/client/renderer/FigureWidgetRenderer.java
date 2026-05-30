package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager for figure widget rendering.
 * Holds shared instances of the renderer and model to avoid creating
 * duplicate heavy objects for each FigureEntry in the UI list.
 *
 * This dramatically reduces lag when opening the collection selection screen
 * by reusing a single renderer/model instance instead of creating one per row.
 */
public final class FigureWidgetRenderer {

    // Lazy-initialized singleton instances
    private static FigureModel figureModel;
    private static GeoBlockRenderer<BoxBlockEntity> figureRenderer;

    // Persistent cache for dummy entities used in UI rendering
    // Key format: "collectionId:figureId"
    private static final Map<String, BoxBlockEntity> renderEntityCache = new ConcurrentHashMap<>();

    // Initialization flag
    private static volatile boolean initialized = false;

    private FigureWidgetRenderer() {
        // Private constructor to prevent instantiation
    }

    /**
     * Initializes the shared renderer and model if not already initialized.
     * This method is thread-safe and idempotent.
     */
    public static void ensureInitialized() {
        if (!initialized) {
            synchronized (FigureWidgetRenderer.class) {
                if (!initialized) {
                    figureModel = new FigureModel();
                    figureRenderer = new GeoBlockRenderer<>(figureModel) {
                        @Override
                        protected void rotateBlock(Direction facing, PoseStack poseStack) {
                            // Don't apply block rotation in GUI widget rendering
                        }

                        @Override
                        public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
                            return RenderType.entityTranslucent(texture);
                        }

                        @Override
                        public void preRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                             MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                             float partialTick, int packedLight, int packedOverlay, int colour) {
                            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                                           packedLight, packedOverlay, colour);

                            FigureDefinition figureDef = animatable.getFigureDefinition();
                            if (figureDef != null) {
                                // Reset all variant bones to visible
                                for (String boneName : figureDef.getAllVariantBoneNames()) {
                                    model.getBone(boneName).ifPresent(bone -> {
                                        bone.setHidden(false);
                                        bone.setChildrenHidden(false);
                                    });
                                }
                                // Hide current variant's hidden bones
                                List<String> hiddenBones = figureDef.getHiddenBonesForSkinIndex(animatable.getAlternativeSkinIndex());
                                for (String boneName : hiddenBones) {
                                    model.getBone(boneName).ifPresent(bone -> {
                                        bone.setHidden(true);
                                        bone.setChildrenHidden(true);
                                    });
                                }
                                // Hide extra texture bones - re-rendered by FigureBoneTextureLayer
                                boolean usingAltModel = animatable.getAlternativeSkinIndex() > 0
                                        && figureDef.getModelForSkinIndex(animatable.getAlternativeSkinIndex()) != null
                                        && !figureDef.getModelForSkinIndex(animatable.getAlternativeSkinIndex()).equals(figureDef.getModelPath());
                                if (!usingAltModel) {
                                    for (FigureDefinition.ExtraTexture extra : figureDef.getExtraTextures()) {
                                        for (String boneName : extra.bones()) {
                                            model.getBone(boneName).ifPresent(bone -> {
                                                bone.setHidden(true);
                                                bone.setChildrenHidden(false);
                                            });
                                        }
                                    }
                                }
                                // Apply definition scale
                                float defScale = figureDef.getScaleForSkinIndex(animatable.getAlternativeSkinIndex());
                                if (defScale != 1.0f) {
                                    poseStack.scale(defScale, defScale, defScale);
                                }
                            }
                        }

                        @Override
                        public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone,
                                                      RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                                      boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                                      int colour) {
                            FigureDefinition fd = animatable.getFigureDefinition();
                            if (fd != null) {
                                List<String> hiddenBones = fd.getHiddenBonesForSkinIndex(animatable.getAlternativeSkinIndex());
                                if (!hiddenBones.isEmpty() && hiddenBones.contains(bone.getName())) {
                                    return;
                                }
                            }
                            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                                                  isReRender, partialTick, packedLight, packedOverlay, colour);
                        }
                    };
                    figureRenderer.addRenderLayer(new FigureBoneTextureLayer<>(figureRenderer, BoxBlockEntity::getFigureDefinition, BoxBlockEntity::getAlternativeSkinIndex));
                    initialized = true;
                }
            }
        }
    }

    /**
     * Gets the shared FigureModel instance.
     * Initializes on first access if needed.
     *
     * @return The shared FigureModel
     */
    public static FigureModel getModel() {
        ensureInitialized();
        return figureModel;
    }

    /**
     * Gets the shared GeoBlockRenderer instance.
     * Initializes on first access if needed.
     *
     * @return The shared renderer
     */
    public static GeoBlockRenderer<BoxBlockEntity> getRenderer() {
        ensureInitialized();
        return figureRenderer;
    }

    /**
     * Gets or creates a cached render entity for the given figure.
     * The entity is stored in a persistent cache to avoid creating new
     * instances every frame or when the screen is reopened.
     *
     * @param figure The figure definition
     * @param collectionId The collection ID
     * @return The cached or newly created BoxBlockEntity, or null on error
     */
    public static BoxBlockEntity getOrCreateRenderEntity(FigureDefinition figure, String collectionId) {
        if (figure == null || collectionId == null) {
            return null;
        }

        String cacheKey = collectionId + ":" + figure.getId();

        return renderEntityCache.computeIfAbsent(cacheKey, key -> {
            try {
                BoxBlockEntity entity = new BoxBlockEntity(BlockPos.ZERO, ModBlocks.BOX_BLOCK.get().defaultBlockState());

                // Set client level for GeckoLib tick delta calculations
                // Without this, animation controllers may crash when accessing level
                entity.setLevel(Minecraft.getInstance().level);

                entity.setFigureId(figure.getId());
                entity.setCollectionIdOverride(collectionId);

                // For player figures, set the color override from their favorite color
                if (figure.getType() == FigureType.PLAYER) {
                    PopBlockColor favoriteColor = figure.getFavoriteColor();
                    if (favoriteColor != null) {
                        entity.setColorOverride(favoriteColor.name());
                    }
                }

                return entity;
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * Clears the render entity cache.
     * Should be called when disconnecting from a world to prevent memory leaks
     * from entities holding references to old Level instances.
     */
    public static void clearCache() {
        renderEntityCache.clear();
    }

    /**
     * Gets the current size of the entity cache.
     * Useful for debugging.
     *
     * @return The number of cached entities
     */
    public static int getCacheSize() {
        return renderEntityCache.size();
    }
}
