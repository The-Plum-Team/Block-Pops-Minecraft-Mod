package com.theplumteam.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a checked record into the collection the registry holds. It runs on the server
 * and on the client and depends on nothing but the record and the blob table, so both
 * sides build the same collection.
 */
public final class WorldCollectionMaterializer {
    /** The animation every figure plays in its box, as bundled figures do. */
    public static final ResourceLocation FIGURE_ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/box_figure_default");

    // How a model figure is fitted to its box, in pixels. The bundled 3D figures stand about
    // 12.5 px tall in the box (Goku is 42.5 px at scale 0.29) and about 15 px tall in the
    // collection screen (gui_scale 0.35); none is much wider than 9 px or deeper than 8 px.
    private static final double FIT_HEIGHT = 12.5;
    private static final double FIT_WIDTH = 9.0;
    private static final double FIT_DEPTH = 8.0;
    private static final double FIT_GUI_HEIGHT = 15.0;
    private static final double FIT_GUI_WIDTH = 9.0;
    // The most a fit may enlarge a model. Bundled and default models fit between 0.24 and
    // 1.075; a tiny model is not blown up past this, so it stays near its block.
    private static final double MAX_FIT = 4.0;
    // Where the default figure model stands at scale 1 relative to its origin, as GeckoLib
    // draws it: the middle of its width, its feet and its front. A fitted model is moved so
    // that these land where the default figure's do in the box.
    private static final double DEFAULT_MIDDLE_X = 0.145;
    private static final double DEFAULT_FEET_Y = 0.05;
    private static final double DEFAULT_FRONT_Z = -2.5;

    private WorldCollectionMaterializer() {
    }

    /** The bundled plain box texture of a box colour name. */
    public static ResourceLocation boxColorTexture(String color) {
        return ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/block/box/" + color + ".png");
    }

    /**
     * Builds the collection of a record that passed {@link WorldCollectionRecord#parse}.
     * An archived collection keeps every figure defined but enables none of them.
     */
    public static FigureCollection materialize(WorldCollectionRecord record, Map<String, BlobInfo> blobs) {
        List<FigureDefinition> figures = new ArrayList<>(record.figures().size());
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            figures.add(figure(figure, blobs, figure.enabled() && !record.archived()));
        }
        ResourceLocation boxTexture = record.boxTexture() != null ? WorldContentIds.texture(record.boxTexture())
                : record.boxColor() != null ? boxColorTexture(record.boxColor())
                : null;
        FigureCollection.LogoConfig logo = record.logo() != null
                ? FigureCollection.LogoConfig.automatic(WorldContentIds.texture(record.logo()), 1.0f, 0.0f, 0.0f)
                : null;
        return new FigureCollection(record.id(), record.name(), record.author().isEmpty() ? "Unknown" : record.author(),
                null, boxTexture, logo, figures, record.backgroundColor(), false, null);
    }

    private static FigureDefinition figure(WorldCollectionRecord.Figure figure, Map<String, BlobInfo> blobs,
                                           boolean enabled) {
        float scale = figure.scale();
        ResourceLocation texture = WorldContentIds.texture(figure.texture());
        if (figure.kind() == WorldCollectionRecord.Kind.SKIN) {
            return new FigureDefinition(figure.id(), figure.name(), FigureDefinition.DEFAULT_MODEL, texture,
                    FIGURE_ANIMATION, List.of(),
                    new FigureDefinition.RenderOptions(null, scale, scale, null, null, null, false, List.of(), List.of(),
                            List.of()),
                    false, enabled);
        }
        ResourceLocation poses = figure.animation() != null ? WorldContentIds.animation(figure.animation()) : null;
        BlobInfo animation = figure.animation() != null ? blobs.get(figure.animation()) : null;
        // A model with no sitting pose of its own keeps its stand pose instead of cycling
        boolean poseLocked = animation == null || !animation.hasPose(BlobInfo.POSE_SIT);
        BlobInfo model = blobs.get(figure.model());
        ModelBounds bounds = model != null ? model.bounds() : ModelBounds.NONE;
        // The figure's scale multiplies the fit, so 1.0 is a model sized like a bundled figure
        float fitted = scale * (float) fit(FIT_HEIGHT / bounds.height(), FIT_WIDTH / bounds.width(),
                FIT_DEPTH / bounds.depth());
        float guiScale = scale * (float) fit(FIT_GUI_HEIGHT / bounds.height(), FIT_GUI_WIDTH / bounds.width(),
                Double.POSITIVE_INFINITY);
        return new FigureDefinition(figure.id(), figure.name(), WorldContentIds.model(figure.model()), texture,
                FIGURE_ANIMATION, List.of(),
                new FigureDefinition.RenderOptions(poses, fitted, guiScale, null,
                        bounds.equals(ModelBounds.NONE) ? null : offset(bounds, fitted), false, poseLocked,
                        List.of(), List.of(), List.of()),
                false, enabled);
    }

    /**
     * The largest scale every limit allows, never more than {@link #MAX_FIT}. An extent of 0
     * sets no limit; a model with no extent at all keeps its own size.
     */
    private static double fit(double byHeight, double byWidth, double byDepth) {
        double fit = Math.min(byHeight, Math.min(byWidth, byDepth));
        return Double.isInfinite(fit) ? 1.0 : Math.min(fit, MAX_FIT);
    }

    /**
     * Where a model drawn at this scale stands in its box: centred across it, its feet on
     * the floor and its front behind the window, as the default figure stands.
     */
    private static float[] offset(ModelBounds bounds, float scale) {
        double unit = ModelBounds.UNIT;
        double middleX = (bounds.minX() + bounds.maxX()) / (2 * unit);
        return new float[] {
                (float) (BoxBlockEntity.DEFAULT_FIGURE_OFFSET_X + (DEFAULT_MIDDLE_X - scale * middleX) / 16),
                (float) (BoxBlockEntity.DEFAULT_FIGURE_OFFSET_Y + (DEFAULT_FEET_Y - scale * bounds.minY() / unit) / 16),
                (float) (BoxBlockEntity.DEFAULT_FIGURE_OFFSET_Z + (DEFAULT_FRONT_Z - scale * bounds.minZ() / unit) / 16)};
    }
}
