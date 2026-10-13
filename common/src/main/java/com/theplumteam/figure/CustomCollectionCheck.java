package com.theplumteam.figure;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.ManifestEntry;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a Custom collection definition before it is registered, on the server and again
 * on the client: every model, animation and texture it names must be one of its own
 * downloaded files, or one of the shared assets every figure may use. A definition that
 * names anything else, such as another collection's assets or vanilla's, is refused.
 */
public final class CustomCollectionCheck {
    /** At most this many figures in one Custom collection. */
    public static final int MAX_FIGURES = 256;
    private static final Pattern FIGURE_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final String ROOT = "assets/" + BlockPopsMod.MOD_ID + "/";
    private static final Set<ResourceLocation> SHARED_MODELS = Set.of(FigureDefinition.DEFAULT_MODEL);
    private static final Set<ResourceLocation> SHARED_ANIMATIONS = Set.of(
            GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/box_figure_default"),
            GeoAssets.animation(BlockPopsMod.MOD_ID, "figure/figure_poses"));

    private CustomCollectionCheck() {
    }

    /** The ids a collection's downloaded files are known by. */
    private record Ids(Set<ResourceLocation> models, Set<ResourceLocation> animations,
                       Set<ResourceLocation> textures) {
    }

    /** The id a file of the collection is used by in collection data, or null for none. */
    @Nullable
    public static ResourceLocation idOf(String path) {
        if (!path.startsWith(ROOT)) {
            return null;
        }
        String rest = path.substring(ROOT.length());
        if (rest.startsWith("geckolib/models/") && rest.endsWith(".geo.json")) {
            return GeoAssets.model(BlockPopsMod.MOD_ID,
                    rest.substring("geckolib/models/".length(), rest.length() - ".geo.json".length()));
        }
        if (rest.startsWith("geckolib/animations/") && rest.endsWith(".animation.json")) {
            return GeoAssets.animation(BlockPopsMod.MOD_ID,
                    rest.substring("geckolib/animations/".length(), rest.length() - ".animation.json".length()));
        }
        if (rest.startsWith("textures/") && rest.endsWith(".png")) {
            return ResourceLocations.of(BlockPopsMod.MOD_ID, rest);
        }
        return null;
    }

    private static Ids ids(Collection<ManifestEntry> files) {
        Ids ids = new Ids(new HashSet<>(), new HashSet<>(), new HashSet<>());
        for (ManifestEntry file : files) {
            ResourceLocation id = idOf(file.path());
            if (id == null) {
                continue;
            }
            if (file.path().endsWith(".geo.json")) {
                ids.models().add(id);
            } else if (file.path().endsWith(".animation.json")) {
                ids.animations().add(id);
            } else {
                ids.textures().add(id);
            }
        }
        return ids;
    }

    /**
     * Returns null when the collection may be registered, or why it may not.
     *
     * @param expectedId the id the collection was enabled under
     * @param files      the collection's downloaded files
     */
    @Nullable
    public static String problem(FigureCollection collection, String expectedId, Collection<ManifestEntry> files) {
        if (!expectedId.equals(collection.getId())) {
            return "declares id " + collection.getId();
        }
        if (BuiltInCollections.COLLECTION_IDS.contains(expectedId)) {
            return "has the id of a bundled collection";
        }
        if (collection.getFigures().isEmpty() || collection.getFigures().size() > MAX_FIGURES) {
            return "has " + collection.getFigures().size() + " figures";
        }
        Ids ids = ids(files);
        if (collection.getBoxTexture() != null && !ids.textures().contains(collection.getBoxTexture())) {
            return "names box texture " + collection.getBoxTexture();
        }
        if (collection.getLogoTexture() != null && !ids.textures().contains(collection.getLogoTexture())) {
            return "names logo " + collection.getLogoTexture();
        }
        for (FigureDefinition figure : collection.getFigures()) {
            String problem = problem(figure, ids);
            if (problem != null) {
                return "figure " + figure.getId() + " " + problem;
            }
        }
        return null;
    }

    @Nullable
    private static String problem(FigureDefinition figure, Ids ids) {
        if (figure.getType() != FigureType.STATIC || !FIGURE_ID.matcher(figure.getId()).matches()) {
            return "is not a plain figure";
        }
        if (!model(figure.getModelPath(), ids)) {
            return "names model " + figure.getModelPath();
        }
        if (!animation(figure.getAnimationPath(), ids)) {
            return "names animation " + figure.getAnimationPath();
        }
        ResourceLocation poses = figure.getRenderOptions().poseAnimation();
        if (poses != null && !animation(poses, ids)) {
            return "names poses " + poses;
        }
        if (!ids.textures().contains(figure.getTexturePath())) {
            return "names texture " + figure.getTexturePath();
        }
        for (FigureDefinition.AlternativeSkin alternative : figure.getAlternatives()) {
            if (!ids.textures().contains(alternative.texture())
                    || (alternative.model() != null && !model(alternative.model(), ids))) {
                return "has an alternative naming another collection's assets";
            }
        }
        for (FigureDefinition.ExtraTexture extra : figure.getRenderOptions().extraTextures()) {
            if (!ids.textures().contains(extra.texture())) {
                return "names extra texture " + extra.texture();
            }
        }
        return null;
    }

    private static boolean model(ResourceLocation id, Ids ids) {
        return SHARED_MODELS.contains(id) || ids.models().contains(id);
    }

    private static boolean animation(ResourceLocation id, Ids ids) {
        return SHARED_ANIMATIONS.contains(id) || ids.animations().contains(id);
    }
}
