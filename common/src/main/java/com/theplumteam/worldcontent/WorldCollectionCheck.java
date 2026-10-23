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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks a materialized World collection before it is registered, on the server and again
 * on the client, as {@link com.theplumteam.figure.CustomCollectionCheck} does for Custom
 * collections: every model, texture, animation and logo it names must be one of its own
 * files in the role the record gives it, or one of the shared assets every figure may use.
 * Whatever else it carries (plush, author link, logo placement, rarity and draw weight,
 * skin variants and box face) must be exactly what its record says.
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
        if (collection.isPlush() != record.plush() || collection.getSpecialsReleasedAt() != null
                || !Objects.equals(collection.getAuthorUrl(), record.authorUrl())) {
            return "is plush, linked or special apart from its record";
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
        WorldCollectionRecord.LogoLayout layout = record.effectiveLogoLayout();
        if (logo != null && (logo.getScale() != layout.scale() || logo.getOffsetX() != layout.offsetXPixels()
                || logo.getOffsetY() != layout.offsetYPixels())) {
            return "places its logo apart from its record";
        }
        for (int i = 0; i < record.figures().size(); i++) {
            FigureDefinition figure = collection.getFigures().get(i);
            WorldCollectionRecord.Figure source = record.figures().get(i);
            if (figure.getRarity() != source.rarity() || figure.getDrawWeight() != record.drawWeight(source)) {
                return "figure " + figure.getId() + " is drawn apart from its record";
            }
            String problem = problem(figure, source);
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
        if (figure.isSpecial()
                || !figure.getRenderOptions().hiddenBones().isEmpty()
                || !figure.getRenderOptions().extraTextures().isEmpty()) {
            return "has hidden bones, extra textures or a special flag";
        }
        // Alternatives may only be the record's own variants: a texture each, in order
        List<FigureDefinition.AlternativeSkin> alternatives = figure.getAlternatives();
        if (alternatives.size() != source.variants().size()) {
            return "has " + alternatives.size() + " alternatives";
        }
        for (int i = 0; i < alternatives.size(); i++) {
            FigureDefinition.AlternativeSkin alternative = alternatives.get(i);
            WorldCollectionRecord.Variant variant = source.variants().get(i);
            if (!alternative.name().equals(variant.name())
                    || !WorldContentIds.texture(variant.texture()).equals(alternative.texture())
                    || alternative.model() != null || alternative.scale() != null || alternative.showBoxFace() != null
                    || alternative.boxFaceUv() != null || !alternative.hiddenBones().isEmpty()) {
                return "names alternative " + alternative.texture();
            }
        }
        if (!sameBoxFace(figure.getRenderOptions(), source)) {
            return "shows its box face apart from its record";
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

    /**
     * Whether the face the box shows is the record's: a skin figure decides only whether it
     * shows, and a model figure shows exactly the region its record names.
     */
    private static boolean sameBoxFace(FigureDefinition.RenderOptions options, WorldCollectionRecord.Figure source) {
        WorldCollectionRecord.BoxFace face = source.boxFace();
        float[] uv = options.boxFaceUv();
        if (source.kind() == WorldCollectionRecord.Kind.SKIN) {
            return uv == null && Objects.equals(options.showBoxFace(), face != null ? face.show() : null);
        }
        if (!Boolean.valueOf(face != null && face.show()).equals(options.showBoxFace())) {
            return false;
        }
        if (face == null || face.uv() == null) {
            return uv == null;
        }
        WorldCollectionRecord.Uv expected = face.uv();
        // The last two values are the texture's size, which the materializer reads from the blob table
        return uv != null && uv.length == 6 && uv[0] == expected.u() && uv[1] == expected.v()
                && uv[2] == expected.w() && uv[3] == expected.h() && uv[4] >= expected.u() + expected.w()
                && uv[5] >= expected.v() + expected.h();
    }
}
