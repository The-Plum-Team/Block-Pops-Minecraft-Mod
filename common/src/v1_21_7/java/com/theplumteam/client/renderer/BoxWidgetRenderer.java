package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager for box widget rendering in the color selection screen.
 * Each color gets a dedicated renderer AND model to prevent GeckoLib render state conflicts.
 */
public final class BoxWidgetRenderer {

    // Cache stores renderer + entity + model per color
    // Key format: "color:showFigure"
    private static final Map<String, RendererEntityModelTriple> renderCache = new ConcurrentHashMap<>();

    private BoxWidgetRenderer() {
        // Private constructor to prevent instantiation
    }

    /**
     * Triple of renderer + entity + model for a specific color/figure configuration.
     */
    private static class RendererEntityModelTriple {
        final BoxBlockRenderer renderer;
        final BoxBlockEntity entity;
        final BoxBlockModel model;

        RendererEntityModelTriple(BoxBlockRenderer renderer, BoxBlockEntity entity, BoxBlockModel model) {
            this.renderer = renderer;
            this.entity = entity;
            this.model = model;
        }
    }

    /**
     * Creates a BoxBlockRenderer with a dedicated model instance.
     */
    private static BoxBlockRenderer createRenderer(BoxBlockModel model, PopBlockColor color, boolean showFigure) {
        final long uniqueInstanceId = color.ordinal() * 2L + (showFigure ? 1 : 0);

        return new BoxBlockRenderer() {
            @Override
            public software.bernie.geckolib.model.GeoModel<BoxBlockEntity> getGeoModel() {
                return model;
            }

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
     * Gets the dedicated renderer for the given color.
     */
    public static BoxBlockRenderer getRenderer(PopBlockColor color, boolean showFigure) {
        if (color == null) {
            return null;
        }

        String cacheKey = color.name() + ":" + showFigure;
        RendererEntityModelTriple triple = renderCache.get(cacheKey);
        return triple != null ? triple.renderer : null;
    }

    /**
     * Gets or creates a cached render entity for the given color.
     * Each color gets its own dedicated renderer, model, and entity.
     */
    public static BoxBlockEntity getOrCreateRenderEntity(PopBlockColor color, boolean showFigure) {
        if (color == null) {
            return null;
        }

        String cacheKey = color.name() + ":" + showFigure;

        RendererEntityModelTriple triple = renderCache.computeIfAbsent(cacheKey, key -> {
            try {
                // Create dedicated model instance
                BoxBlockModel dedicatedModel = new BoxBlockModel();

                // Use a unique BlockPos per color
                BlockPos uniquePos = new BlockPos(color.ordinal(), 256, showFigure ? 1 : 0);
                BoxBlockEntity entity = new BoxBlockEntity(uniquePos, ModBlocks.BOX_BLOCK.get().defaultBlockState());

                entity.setLevel(Minecraft.getInstance().level);
                entity.setColorOverride(color.name());
                entity.setHideLogo(true);

                if (showFigure) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        entity.setCollectionIdOverride("world_players");
                        entity.setFigureId(mc.player.getUUID().toString());
                    }
                }

                BoxBlockRenderer renderer = createRenderer(dedicatedModel, color, showFigure);

                return new RendererEntityModelTriple(renderer, entity, dedicatedModel);
            } catch (Exception e) {
                return null;
            }
        });

        return triple != null ? triple.entity : null;
    }

    /**
     * Clears the render cache.
     */
    public static void clearCache() {
        renderCache.clear();
    }
}
