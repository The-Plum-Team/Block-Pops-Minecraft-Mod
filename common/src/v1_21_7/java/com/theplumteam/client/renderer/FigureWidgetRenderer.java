package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton manager for figure widget rendering in the collection selection screen.
 * Each figure gets a dedicated renderer AND model to prevent GeckoLib render state conflicts.
 */
public final class FigureWidgetRenderer {

    // Cache stores renderer + entity + model per figure
    // Key format: "collectionId:figureId"
    private static final Map<String, RendererEntityModelTriple> renderCache = new ConcurrentHashMap<>();
    private static final AtomicLong instanceIdCounter = new AtomicLong(1);

    private FigureWidgetRenderer() {
    }

    private static class RendererEntityModelTriple {
        final GeoBlockRenderer<BoxBlockEntity> renderer;
        final BoxBlockEntity entity;
        final FigureModel model;

        RendererEntityModelTriple(GeoBlockRenderer<BoxBlockEntity> renderer, BoxBlockEntity entity, FigureModel model) {
            this.renderer = renderer;
            this.entity = entity;
            this.model = model;
        }
    }

    private static GeoBlockRenderer<BoxBlockEntity> createRenderer(FigureModel model, long uniqueInstanceId) {
        return new GeoBlockRenderer<>(model) {
            @Override
            public long getInstanceId(BoxBlockEntity animatable, Void relatedObject) {
                return uniqueInstanceId;
            }

            @Override
            public void adjustPositionForRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel bakedModel, boolean isReRender) {
                // Don't apply block centering offset in GUI widget rendering
            }

            @Override
            protected void rotateBlock(Direction facing, PoseStack poseStack) {
                // Don't apply block rotation in GUI widget rendering
            }

            @Override
            public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
                return RenderType.entityTranslucent(texture, true);
            }
        };
    }

    /**
     * Gets the dedicated renderer for the given figure key.
     *
     * @param figureKey The cache key in format "collectionId:figureId"
     * @return The dedicated renderer, or null if not cached
     */
    public static GeoBlockRenderer<BoxBlockEntity> getRenderer(String figureKey) {
        if (figureKey == null) return null;
        RendererEntityModelTriple triple = renderCache.get(figureKey);
        return triple != null ? triple.renderer : null;
    }

    /**
     * Gets or creates a cached render entity for the given figure.
     * Each figure gets its own dedicated renderer, model, and entity.
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

        RendererEntityModelTriple triple = renderCache.computeIfAbsent(cacheKey, key -> {
            try {
                FigureModel dedicatedModel = new FigureModel();
                long uniqueInstanceId = instanceIdCounter.getAndIncrement();

                BlockPos uniquePos = new BlockPos((int)(uniqueInstanceId % 1000), 256, (int)(uniqueInstanceId / 1000));
                BoxBlockEntity entity = new BoxBlockEntity(uniquePos, ModBlocks.BOX_BLOCK.get().defaultBlockState());

                entity.setLevel(Minecraft.getInstance().level);
                entity.setFigureId(figure.getId());
                entity.setCollectionIdOverride(collectionId);

                if (figure.getType() == FigureType.PLAYER) {
                    PopBlockColor favoriteColor = figure.getFavoriteColor();
                    if (favoriteColor != null) {
                        entity.setColorOverride(favoriteColor.name());
                    }
                }

                GeoBlockRenderer<BoxBlockEntity> renderer = createRenderer(dedicatedModel, uniqueInstanceId);

                return new RendererEntityModelTriple(renderer, entity, dedicatedModel);
            } catch (Exception e) {
                return null;
            }
        });

        return triple != null ? triple.entity : null;
    }

    /**
     * Clears the render cache.
     * Should be called when disconnecting from a world to prevent memory leaks.
     */
    public static void clearCache() {
        renderCache.clear();
        instanceIdCounter.set(1);
    }

    /**
     * Gets the current size of the entity cache.
     *
     * @return The number of cached entities
     */
    public static int getCacheSize() {
        return renderCache.size();
    }
}
