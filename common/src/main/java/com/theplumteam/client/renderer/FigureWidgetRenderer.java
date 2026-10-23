package com.theplumteam.client.renderer;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

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
    //? if >=26 {
    /*private static GeoBlockRenderer<BoxBlockEntity,
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> figureRenderer;
    *///? } else {
    private static GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    //? }

    // Persistent cache for dummy entities used in UI rendering
    // Key format: "collectionId:figureId"
    private static final Map<String, BoxBlockEntity> renderEntityCache = new ConcurrentHashMap<>();
    // Ends the cache key of a posed render entity; a figure id never holds a "|"
    private static final String POSED = "|posed";

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
                    //? if >=26 {
                    /*figureRenderer = new BlockPopsGeoBlockRenderer<>(GeoRendererContext.get(), figureModel);
                    *///? } else {
                    figureRenderer = new GeoBlockRenderer<>(figureModel);
                    //? }
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
    //? if >=26 {
    /*public static GeoBlockRenderer<BoxBlockEntity,
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> getRenderer() {
    *///? } else {
    public static GeoBlockRenderer<BoxBlockEntity> getRenderer() {
    //? }
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
        return getOrCreateRenderEntity(figure, collectionId, false);
    }

    /**
     * Gets or creates a cached render entity for the given figure, as above. A {@code posed} one
     * is apart from the others: the creator's previews set its pose, so the lists drawing the
     * same figure in the same frame keep theirs standing and no two draws fight over one entity.
     */
    public static BoxBlockEntity getOrCreateRenderEntity(FigureDefinition figure, String collectionId,
                                                         boolean posed) {
        if (figure == null || collectionId == null) {
            return null;
        }

        String cacheKey = collectionId + ":" + figure.getId() + (posed ? POSED : "");

        return renderEntityCache.computeIfAbsent(cacheKey, key -> {
            try {
                // 1.21.1 refuses a block entity on a block its type does not list, so the
                // preview stands on the box block itself rather than on air.
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

    /** Drops the render entities of one collection, as when a preview of it goes away. */
    public static void clearCache(String collectionId) {
        String prefix = collectionId + ":";
        renderEntityCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Drops the render entity of one figure, as when a preview changes its model or poses. */
    public static void clearCache(String collectionId, String figureId) {
        renderEntityCache.remove(collectionId + ":" + figureId);
        renderEntityCache.remove(collectionId + ":" + figureId + POSED);
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
