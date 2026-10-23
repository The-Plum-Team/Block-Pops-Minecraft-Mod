package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shows the creator's unsaved collections in the renderers. A draft record is materialized as
 * the server would materialize it, without the server's check, and registered in the
 * {@link CollectionRegistry} overlay under a preview id of its slot (see {@link #previewId}), so
 * the figure, box and machine renderers draw it like any collection. A preview never takes the
 * collection's real id, is never listed, and only the client thread sees it, so the integrated
 * server of a single player world never does.
 *
 * <p>A preview shows the collection as a machine would offer it: the figures its record enables
 * are listed even while it is a draft or archived. Every preview also holds a disabled reference
 * figure, {@link #REFERENCE_FIGURE}, the default figure in the default skin at scale 1, which the
 * creator draws beside a figure to show its size.
 *
 * <p>The files a preview names come from the server's file table or, before they are uploaded,
 * from the creator's own (see {@link ClientPreviewAssets}). Previews are cleared when the creator
 * closes and when the client leaves a server.
 *
 * Client thread only.
 */
public final class CreatorPreview {
    /** How many previews may be shown at once. */
    public static final int MAX_SLOTS = 8;
    /**
     * The id of every preview's reference figure. The dot keeps it from ever being the id of a
     * real figure.
     */
    public static final String REFERENCE_FIGURE = "__ref.1x";
    // The default skin, at the same path on every supported version
    static final ResourceLocation DEFAULT_SKIN =
            ResourceLocations.of("minecraft", "textures/entity/player/wide/steve.png");
    private static final FigureDefinition REFERENCE = new FigureDefinition(REFERENCE_FIGURE, "1x",
            FigureDefinition.DEFAULT_MODEL, DEFAULT_SKIN, WorldCollectionMaterializer.FIGURE_ANIMATION, List.of(),
            FigureDefinition.RenderOptions.DEFAULT, false, false);

    private static final Map<String, FigureCollection> PREVIEWS = new HashMap<>();
    private static boolean installed;

    private CreatorPreview() {
    }

    /** The id the preview in a slot is registered under. */
    public static String previewId(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("No preview slot " + slot);
        }
        return CollectionRegistry.PREVIEW_PREFIX + slot;
    }

    /** Whether the collection id is one of the creator's previews. */
    public static boolean isPreviewId(@Nullable String collectionId) {
        return collectionId != null && collectionId.startsWith(CollectionRegistry.PREVIEW_PREFIX);
    }

    /**
     * Shows a draft record in a slot, replacing what the slot showed. Files the record names are
     * described by {@code localBlobs} first, then by the server's file table; a file neither
     * describes is drawn as its fallback. Returns false, keeping the slot as it was, when the
     * record cannot be materialized.
     */
    public static boolean show(int slot, WorldCollectionRecord draftRecord, Map<String, BlobInfo> localBlobs) {
        String id = previewId(slot);
        Map<String, BlobInfo> blobs = new HashMap<>();
        for (String sha : draftRecord.referencedShas()) {
            BlobInfo info = localBlobs.get(sha);
            if (info == null) {
                info = ClientWorldCollections.blob(sha);
            }
            if (info != null) {
                blobs.put(sha, info);
            }
        }
        FigureCollection collection;
        try {
            // A draft or archived collection enables none of its figures, and the machine lists only enabled ones
            collection = WorldCollectionMaterializer.materialize(
                    draftRecord.withDraft(false).withArchived(false), blobs);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Could not preview collection {}: {}", draftRecord.id(), e.toString());
            return false;
        }
        List<FigureDefinition> figures = new ArrayList<>(collection.getFigures());
        figures.add(REFERENCE);
        install();
        FigureCollection next = collection.withId(id).withFigures(figures);
        FigureCollection previous = PREVIEWS.put(id, next);
        if (previous != null) {
            dropChangedFigures(id, previous, next);
        }
        return true;
    }

    // A render entity keeps playing the poses it started with and outlives a renamed figure,
    // so the entities of figures that went away or changed their model or poses are dropped.
    // The others keep theirs, so an edit does not restart every animation.
    private static void dropChangedFigures(String id, FigureCollection previous, FigureCollection next) {
        for (FigureDefinition before : previous.getFigures()) {
            FigureDefinition after = next.getFigure(before.getId()).orElse(null);
            if (after == null || !Objects.equals(before.getModelPath(), after.getModelPath())
                    || !Objects.equals(before.getRenderOptions().poseAnimation(),
                            after.getRenderOptions().poseAnimation())) {
                FigureWidgetRenderer.clearCache(id, before.getId());
            }
        }
    }

    /** The collection a slot shows, or null. */
    @Nullable
    public static FigureCollection collection(int slot) {
        return PREVIEWS.get(previewId(slot));
    }

    /** Stops showing a slot. */
    public static void clear(int slot) {
        String id = previewId(slot);
        if (PREVIEWS.remove(id) != null) {
            FigureWidgetRenderer.clearCache(id);
        }
    }

    /** Stops showing every slot, as when the creator closes or the client leaves a server. */
    public static void clearAll() {
        for (String id : PREVIEWS.keySet()) {
            FigureWidgetRenderer.clearCache(id);
        }
        PREVIEWS.clear();
    }

    // The overlay stays installed once used: from 1.21.6 an item stack is drawn later in the
    // frame it was queued in, after a screen may have cleared its slot
    private static void install() {
        if (!installed) {
            CollectionRegistry.setPreviewOverlay(PREVIEWS::get, () -> Minecraft.getInstance().isSameThread());
            installed = true;
        }
    }
}
