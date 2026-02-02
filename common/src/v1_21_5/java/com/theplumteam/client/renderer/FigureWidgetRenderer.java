package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

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
                        public void adjustPositionForRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model, boolean isReRender) {
                            // Don't apply block centering offset (translate 0.5, 0, 0.5) in GUI widget rendering
                        }

                        @Override
                        protected void rotateBlock(Direction facing, PoseStack poseStack) {
                            // Don't apply block rotation in GUI widget rendering
                        }
                    };
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
