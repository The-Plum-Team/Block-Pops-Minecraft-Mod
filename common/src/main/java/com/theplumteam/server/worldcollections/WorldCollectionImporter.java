package com.theplumteam.server.worldcollections;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.AnimationJsonCheck;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.GeoJsonCheck;
import com.theplumteam.worldcontent.JsonFields;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a World collection a datapack ships under
 * data/&lt;namespace&gt;/blockpops_world_collections/&lt;path&gt;/, for administrators without the
 * editor and for packaged tests:
 *
 * <pre>
 * collection.json   {name, author, background_color, box_color?, author_url?, logo_layout?, plush?,
 *                    rarity_weights?, figures: [{id, name, kind, scale?, rarity?, variants?, box_face?}]}
 * box.png, logo.png (optional)
 * figures/&lt;id&gt;/texture.png
 * figures/&lt;id&gt;/variant_&lt;n&gt;.png, for the nth entry of variants (n = 1 to 4)
 * figures/&lt;id&gt;/model.geo.json, figures/&lt;id&gt;/animation.animation.json (optional), for a model figure
 * </pre>
 *
 * The optional keys have the record's form; a variant names only its name here, and its
 * texture is the variant file of the same number.
 *
 * Each file is opened by its exact name, never by listing a directory, and checked as an
 * upload is. {@link WorldCollections#importPack} stores the result.
 */
public final class WorldCollectionImporter {
    public static final String DIRECTORY = "blockpops_world_collections";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_]{1,32}");
    private static final int COLLECTION_JSON_BYTES = 64 * 1024;
    private static final Set<String> KEYS = Set.of("name", "author", "background_color", "figures");
    private static final Set<String> OPTIONAL_KEYS = Set.of("box_color", "author_url", "logo_layout", "plush",
            "rarity_weights");
    private static final Set<String> FIGURE_KEYS = Set.of("id", "name", "kind");
    private static final Set<String> FIGURE_OPTIONAL_KEYS = Set.of("scale", "rarity", "variants", "box_face");
    private static final Set<String> VARIANT_KEYS = Set.of("name");

    /** A file of the pack that passed its check, in the form it is stored. */
    public record PackFile(String sha, BlobInfo info, byte[] bytes) {
    }

    /**
     * What a pack defines: a record whose figures are all enabled, not archived and at
     * revision 0, and the files it names by sha.
     */
    public record Pack(WorldCollectionRecord record, Map<String, PackFile> files) {
    }

    /** A pack file larger than its kind allows. */
    public static final class TooLargeException extends WorldContentException {
        TooLargeException(String message) {
            super(message);
        }
    }

    private WorldCollectionImporter() {
    }

    /** Whether a namespace and path may name a pack. */
    public static boolean isValidSource(String namespace, String path) {
        return namespace != null && NAMESPACE.matcher(namespace).matches() && path != null && PATH.matcher(path).matches();
    }

    /** Reads and checks the pack at namespace:path as the collection with the slug. */
    public static Pack read(ResourceManager resources, String slug, String namespace, String path)
            throws WorldContentException {
        if (!WorldContentIds.isSlug(slug)) {
            throw new WorldContentException("Slug must be 1 to 24 of a-z, 0-9 and _");
        }
        if (!isValidSource(namespace, path)) {
            throw new WorldContentException("A pack is named namespace:path, with a path of 1 to 32 of a-z, 0-9 and _");
        }
        String base = DIRECTORY + "/" + path + "/";
        byte[] definition = file(resources, namespace, base + "collection.json", COLLECTION_JSON_BYTES);
        if (definition == null) {
            throw new WorldContentException("No " + DIRECTORY + "/" + path + "/collection.json in namespace " + namespace);
        }
        JsonObject json = JsonFields.parse(definition, COLLECTION_JSON_BYTES);
        JsonFields.keys(json, "collection.json", KEYS, OPTIONAL_KEYS);

        Map<String, PackFile> files = new LinkedHashMap<>();
        String boxTexture = null;
        String boxColor = null;
        PackFile box = png(resources, namespace, base + "box.png", PngValidator.Role.BOX, files);
        if (box != null) {
            boxTexture = box.sha();
        }
        if (json.has("box_color")) {
            if (box != null) {
                throw new WorldContentException("A pack has either box.png or box_color, not both");
            }
            boxColor = JsonFields.string(json.get("box_color"), "box_color", 16);
            if (!WorldCollectionRecord.isBoxColor(boxColor)) {
                throw new WorldContentException("Unknown box_color " + WorldContentException.excerpt(boxColor));
            }
        }
        PackFile logo = png(resources, namespace, base + "logo.png", PngValidator.Role.LOGO, files);

        JsonArray color = JsonFields.array(json.get("background_color"), "background_color");
        if (color.size() != 3) {
            throw new WorldContentException("background_color must have 3 values");
        }
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            rgb[i] = (int) JsonFields.integer(color.get(i), "background_color", 0, 255);
        }

        JsonArray array = JsonFields.array(json.get("figures"), "figures");
        if (array.size() > WorldContentLimits.MAX_FIGURES) {
            throw new WorldContentException("A pack has at most " + WorldContentLimits.MAX_FIGURES + " figures");
        }
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>(array.size());
        Set<String> figureIds = new HashSet<>();
        int variantCount = 0;
        for (JsonElement element : array) {
            JsonObject figure = JsonFields.object(element, "figure");
            JsonFields.keys(figure, "figure", FIGURE_KEYS, FIGURE_OPTIONAL_KEYS);
            String id = JsonFields.string(figure.get("id"), "figure id", 24);
            if (!WorldContentIds.isSlug(id) || !figureIds.add(id)) {
                throw new WorldContentException("Figure ids must be unique, 1 to 24 of a-z, 0-9 and _");
            }
            String name = JsonFields.string(figure.get("name"), "figure " + id + " name", WorldContentLimits.MAX_NAME_LENGTH);
            WorldCollectionRecord.Kind kind = WorldCollectionRecord.Kind.byName(
                    JsonFields.string(figure.get("kind"), "figure " + id + " kind", 16));
            if (kind == null) {
                throw new WorldContentException("Figure " + id + " kind must be skin or model");
            }
            float scale = 1.0f;
            if (figure.has("scale")) {
                double value = JsonFields.number(figure.get("scale"), "figure " + id + " scale", 0, 16);
                int index = WorldCollectionRecord.scaleIndex((float) value);
                if (index < 0 || WorldCollectionRecord.SCALES.get(index) != value) {
                    throw new WorldContentException("Figure " + id + " scale must be one of " + WorldCollectionRecord.SCALES);
                }
                scale = WorldCollectionRecord.SCALES.get(index);
            }
            String figureBase = base + "figures/" + id + "/";
            PngValidator.Role textureRole = kind == WorldCollectionRecord.Kind.SKIN
                    ? PngValidator.Role.SKIN : PngValidator.Role.MODEL_TEXTURE;
            String texture = required(png(resources, namespace, figureBase + "texture.png", textureRole, files),
                    figureBase + "texture.png").sha();
            String model = null;
            String animation = null;
            if (kind == WorldCollectionRecord.Kind.MODEL) {
                model = required(json(resources, namespace, figureBase + "model.geo.json", true, files),
                        figureBase + "model.geo.json").sha();
                PackFile poses = json(resources, namespace, figureBase + "animation.animation.json", false, files);
                animation = poses != null ? poses.sha() : null;
            }
            WorldCollectionRecord.Rarity rarity = figure.has("rarity")
                    ? WorldCollectionRecord.Rarity.fromJson(figure.get("rarity"), "figure " + id + " rarity")
                    : WorldCollectionRecord.Rarity.COMMON;
            List<WorldCollectionRecord.Variant> variants = new ArrayList<>();
            if (figure.has("variants")) {
                JsonArray entries = JsonFields.array(figure.get("variants"), "figure " + id + " variants");
                if (entries.size() > WorldContentLimits.MAX_VARIANTS_PER_FIGURE) {
                    throw new WorldContentException("Figure " + id + " has more than "
                            + WorldContentLimits.MAX_VARIANTS_PER_FIGURE + " variants");
                }
                // Counted before any variant file is read, so a pack never holds more of them in memory
                variantCount += entries.size();
                if (variantCount > WorldContentLimits.MAX_VARIANTS) {
                    throw new WorldContentException("A pack has at most " + WorldContentLimits.MAX_VARIANTS
                            + " variants");
                }
                for (int i = 0; i < entries.size(); i++) {
                    JsonObject entry = JsonFields.object(entries.get(i), "figure " + id + " variant");
                    JsonFields.keys(entry, "figure " + id + " variant", VARIANT_KEYS, Set.of());
                    String variantName = JsonFields.string(entry.get("name"), "figure " + id + " variant name",
                            WorldContentLimits.MAX_NAME_LENGTH);
                    // Opened by its exact numbered name, as every other pack file is
                    String file = figureBase + "variant_" + (i + 1) + ".png";
                    variants.add(new WorldCollectionRecord.Variant(variantName,
                            required(png(resources, namespace, file, textureRole, files), file).sha()));
                }
            }
            WorldCollectionRecord.BoxFace boxFace = figure.has("box_face")
                    ? WorldCollectionRecord.BoxFace.fromJson(figure.get("box_face"), "figure " + id + " box_face")
                    : null;
            figures.add(new WorldCollectionRecord.Figure(id, name, kind, texture, model, animation, scale, true,
                    rarity, variants, boxFace));
        }
        String name = JsonFields.string(json.get("name"), "name", WorldContentLimits.MAX_NAME_LENGTH);
        String author = JsonFields.string(json.get("author"), "author", WorldContentLimits.MAX_AUTHOR_LENGTH);
        // Whatever depends on the files (variant proportions, the face region, a layout without a
        // logo) is checked when the record is validated against the blob table before it is stored
        String authorUrl = json.has("author_url") ? WorldCollectionRecord.authorUrl(json.get("author_url")) : null;
        WorldCollectionRecord.LogoLayout logoLayout = json.has("logo_layout")
                ? WorldCollectionRecord.LogoLayout.fromJson(json.get("logo_layout")) : null;
        if (logoLayout != null && logo == null) {
            throw new WorldContentException("logo_layout places a logo.png the pack does not have");
        }
        boolean plush = json.has("plush") && JsonFields.bool(json.get("plush"), "plush");
        WorldCollectionRecord.RarityWeights rarityWeights = json.has("rarity_weights")
                ? WorldCollectionRecord.RarityWeights.fromJson(json.get("rarity_weights")) : null;
        WorldCollectionRecord record = new WorldCollectionRecord(WorldContentIds.collectionId(slug), name, author,
                rgb[0], rgb[1], rgb[2], boxTexture, boxColor, logo != null ? logo.sha() : null, false, 0, figures,
                false, 0L, authorUrl, logoLayout, plush, rarityWeights);
        return new Pack(record, files);
    }

    private static PackFile required(@Nullable PackFile file, String path) throws WorldContentException {
        if (file == null) {
            throw new WorldContentException("The pack has no " + path);
        }
        return file;
    }

    /** Reads, canonicalizes and checks a PNG of the pack, or null when it has none. */
    @Nullable
    private static PackFile png(ResourceManager resources, String namespace, String path, PngValidator.Role role,
                                Map<String, PackFile> files) throws WorldContentException {
        byte[] bytes = file(resources, namespace, path, role.maxBytes());
        if (bytes == null) {
            return null;
        }
        byte[] canonical;
        BlobInfo info;
        try {
            canonical = PngValidator.canonicalize(bytes);
            info = PngValidator.validateCanonical(canonical, role);
        } catch (WorldContentException e) {
            throw new WorldContentException(path + ": " + e.getMessage());
        }
        return add(files, canonical, info);
    }

    /** Reads and checks a model, or animation, of the pack; null when an optional one is absent. */
    @Nullable
    private static PackFile json(ResourceManager resources, String namespace, String path, boolean model,
                                 Map<String, PackFile> files) throws WorldContentException {
        byte[] bytes = file(resources, namespace, path,
                model ? WorldContentLimits.GEO_BYTES : WorldContentLimits.ANIMATION_BYTES);
        if (bytes == null) {
            return null;
        }
        BlobInfo info;
        try {
            info = model ? GeoJsonCheck.validate(bytes) : AnimationJsonCheck.validate(bytes);
        } catch (WorldContentException e) {
            throw new WorldContentException(path + ": " + e.getMessage());
        }
        return add(files, bytes, info);
    }

    private static PackFile add(Map<String, PackFile> files, byte[] bytes, BlobInfo info) {
        String sha = Sha256.of(bytes);
        return files.computeIfAbsent(sha, key -> new PackFile(key, info, bytes));
    }

    /** The bytes of one pack file, or null when the pack does not have it. */
    @Nullable
    private static byte[] file(ResourceManager resources, String namespace, String path, int maxBytes)
            throws WorldContentException {
        ResourceLocation location = ResourceLocations.of(namespace, path);
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = resource.get().open()) {
            bytes = in.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            throw new WorldContentException("Could not read " + path);
        }
        if (bytes.length > maxBytes) {
            throw new TooLargeException(path + " is larger than " + maxBytes / 1024 + " KiB");
        }
        return bytes;
    }
}
