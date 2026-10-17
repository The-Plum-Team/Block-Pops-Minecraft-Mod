package com.theplumteam.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.util.GeoAssets;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Checks a materialized World collection before it is registered, on the server and again
 * on the client, as {@link com.theplumteam.figure.CustomCollectionCheck} does for Custom
 * collections: every model, texture, animation and logo it names must be one of its own
 * files in the role the record gives it, or one of the shared assets every figure may use.
 */
public final class WorldCollectionCheck {
    private static final Set<ResourceLocation> SHARED_ANIMATIONS = Set.of(
            WorldCollectionMaterializer.FIGURE_ANIMATION,
            GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/figure_poses"));

    private WorldCollectionCheck() {
    }

    /** Returns null when the collection may be registered, or why it may not. */
    @Nullable
    public static String problem(FigureCollection collection, WorldCollectionRecord record) {
        if (!WorldContentIds.isWorldCollectionId(collection.getId()) || !collection.getId().equals(record.id())) {
            return "declares id " + collection.getId();
        }
        if (collection.isPlush() || collection.getSpecialsReleasedAt() != null || collection.getAuthorUrl() != null) {
            return "is not a plain collection";
        }
        if (collection.getFigures().size() != record.figures().size()
                || collection.getFigures().size() > WorldContentLimits.MAX_FIGURES) {
            return "has " + collection.getFigures().size() + " figures";
        }
        Set<ResourceLocation> boxTextures = new HashSet<>();
        for (PopBlockColor color : PopBlockColor.values()) {
            boxTextures.add(WorldCollectionMaterializer.boxColorTexture(color.getSerializedName()));
        }
        if (record.boxTexture() != null) {
            boxTextures.add(WorldContentIds.texture(record.boxTexture()));
        }
        if (collection.getBoxTexture() != null && !boxTextures.contains(collection.getBoxTexture())) {
            return "names box texture " + collection.getBoxTexture();
        }
        FigureCollection.LogoConfig logo = collection.getLogoConfig();
        if (logo != null && (record.logo() == null || !logo.isAutomatic()
                || !WorldContentIds.texture(record.logo()).equals(logo.getTexture()))) {
            return "names logo " + logo.getTexture();
        }
        for (int i = 0; i < record.figures().size(); i++) {
            FigureDefinition figure = collection.getFigures().get(i);
            String problem = problem(figure, record.figures().get(i));
            if (problem != null) {
                return "figure " + figure.getId() + " " + problem;
            }
        }
        return null;
    }

    @Nullable
    private static String problem(FigureDefinition figure, WorldCollectionRecord.Figure source) {
        if (figure.getType() != FigureType.STATIC || !WorldContentIds.isSlug(figure.getId())
                || !figure.getId().equals(source.id())) {
            return "is not a plain figure";
        }
        if (figure.hasAlternatives() || figure.isSpecial()
                || !figure.getRenderOptions().hiddenBones().isEmpty()
                || !figure.getRenderOptions().extraTextures().isEmpty()) {
            return "has alternatives, hidden bones, extra textures or a special flag";
        }
        ResourceLocation model = source.model() != null ? WorldContentIds.model(source.model()) : null;
        if (!figure.getModelPath().equals(FigureDefinition.DEFAULT_MODEL) && !figure.getModelPath().equals(model)) {
            return "names model " + figure.getModelPath();
        }
        if (!SHARED_ANIMATIONS.contains(figure.getAnimationPath())) {
            return "names animation " + figure.getAnimationPath();
        }
        ResourceLocation poses = figure.getRenderOptions().poseAnimation();
        if (poses != null && !SHARED_ANIMATIONS.contains(poses)
                && (source.animation() == null || !poses.equals(WorldContentIds.animation(source.animation())))) {
            return "names poses " + poses;
        }
        if (!WorldContentIds.texture(source.texture()).equals(figure.getTexturePath())) {
            return "names texture " + figure.getTexturePath();
        }
        return null;
    }
}
