package com.theplumteam.worldcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.block.PopBlockColor;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The definition of one World collection as an operator authored it: names, colours and
 * the shas of its files, never the files themselves. The same JSON is stored on the
 * server and sent to clients, and both parse it with {@link #parse}, which checks every
 * field and every file reference against the blob table.
 *
 * Every key added after the first release is optional and left out while it holds its
 * default, so a record that does not use a feature keeps the exact bytes it always had.
 *
 * @param boxTexture    the sha of the box texture, or null
 * @param boxColor      the {@link PopBlockColor} name of a plain box, or null; never set
 *                      together with boxTexture
 * @param logo          the sha of the logo, or null
 * @param revision      counts the edits, so a changed record is always told apart
 * @param figures       every figure ever added, retired ones included
 * @param draft         true while the collection has never been published: defined and synced,
 *                      but offered in no machine; never set together with archived
 * @param editedAt      when it was last edited, in epoch seconds, or 0 when unknown
 * @param authorUrl     a link to the author's page, only ever shown and opened by the player's
 *                      browser after the game asks; null when there is none
 * @param logoLayout    how the logo is sized and moved on the box; null for the automatic fit
 * @param plush         whether the gacha machine offers it as a plush collection
 * @param rarityWeights the draw weight of each rarity; null for {@link RarityWeights#DEFAULT}
 */
public record WorldCollectionRecord(String id, String name, String author, int red, int green, int blue,
                                    @Nullable String boxTexture, @Nullable String boxColor, @Nullable String logo,
                                    boolean archived, int revision, List<Figure> figures,
                                    boolean draft, long editedAt, @Nullable String authorUrl,
                                    @Nullable LogoLayout logoLayout, boolean plush,
                                    @Nullable RarityWeights rarityWeights) {
    /** The figure sizes an operator can choose from. */
    public static final List<Float> SCALES = List.of(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f);
    // The background a new collection starts with
    private static final int[] NEW_BACKGROUND = {152, 48, 167};
    /** The latest edit time a record may carry: the last second of the year 9999. */
    public static final long MAX_EDITED_AT = 253_402_300_799L;
    private static final Set<String> KEYS = Set.of("id", "name", "author", "background_color", "box", "logo",
            "archived", "revision", "figures");
    private static final Set<String> OPTIONAL_KEYS = Set.of("draft", "edited_at", "author_url", "logo_layout",
            "plush", "rarity_weights");
    private static final Set<String> FIGURE_KEYS = Set.of("id", "name", "kind", "texture", "model", "animation",
            "scale", "enabled");
    private static final Set<String> FIGURE_OPTIONAL_KEYS = Set.of("rarity", "variants", "box_face");
    private static final Set<String> LOGO_LAYOUT_KEYS = Set.of("scale_pct", "offset_x", "offset_y");
    private static final Set<String> VARIANT_KEYS = Set.of("name", "texture");
    private static final Set<String> BOX_FACE_KEYS = Set.of("show");
    private static final Set<String> BOX_FACE_OPTIONAL_KEYS = Set.of("uv");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /** Whether a figure is a skin on the default model, or a model of its own. */
    public enum Kind {
        SKIN("skin"),
        MODEL("model");

        private final String serializedName;

        Kind(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        @Nullable
        public static Kind byName(String name) {
            for (Kind kind : values()) {
                if (kind.serializedName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * How rare a figure is. A collection where every figure is common draws them all alike;
     * once any figure has another rarity, each one is drawn by its rarity's weight.
     */
    public enum Rarity {
        COMMON("common"),
        RARE("rare"),
        EPIC("epic"),
        LEGENDARY("legendary");

        private final String serializedName;

        Rarity(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        @Nullable
        public static Rarity byName(String name) {
            for (Rarity rarity : values()) {
                if (rarity.serializedName.equals(name)) {
                    return rarity;
                }
            }
            return null;
        }

        /** Reads a figure's rarity key, which names only the rarities other than common. */
        public static Rarity fromJson(JsonElement element, String what) throws WorldContentException {
            Rarity rarity = byName(JsonFields.string(element, what, 16));
            if (rarity == null || rarity == COMMON) {
                throw new WorldContentException(what + " must be rare, epic or legendary");
            }
            return rarity;
        }
    }

    /**
     * How the logo is placed on the box, on top of its automatic fit.
     *
     * @param scalePct the logo's size in percent of the fit
     * @param offsetX  how far it moves right, in tenths of a model pixel
     * @param offsetY  how far it moves up, in tenths of a model pixel
     */
    public record LogoLayout(int scalePct, int offsetX, int offsetY) {
        public static final int MIN_SCALE_PCT = 50;
        public static final int MAX_SCALE_PCT = 150;
        public static final int MAX_OFFSET = 40;
        public static final LogoLayout DEFAULT = new LogoLayout(100, 0, 0);

        public static boolean isValid(int scalePct, int offsetX, int offsetY) {
            return scalePct >= MIN_SCALE_PCT && scalePct <= MAX_SCALE_PCT
                    && Math.abs(offsetX) <= MAX_OFFSET && Math.abs(offsetY) <= MAX_OFFSET;
        }

        /** The scale the box's logo fit is multiplied by. */
        public float scale() {
            return scalePct / 100.0f;
        }

        /** The horizontal offset in model pixels. */
        public float offsetXPixels() {
            return offsetX / 10.0f;
        }

        /** The vertical offset in model pixels. */
        public float offsetYPixels() {
            return offsetY / 10.0f;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("scale_pct", scalePct);
            json.addProperty("offset_x", offsetX);
            json.addProperty("offset_y", offsetY);
            return json;
        }

        /** Reads a logo_layout object, as the store, the wire and a datapack write it. */
        public static LogoLayout fromJson(JsonElement element) throws WorldContentException {
            JsonObject json = JsonFields.object(element, "logo_layout");
            JsonFields.keys(json, "logo_layout", LOGO_LAYOUT_KEYS, Set.of());
            return new LogoLayout(
                    (int) JsonFields.integer(json.get("scale_pct"), "logo_layout scale_pct", MIN_SCALE_PCT, MAX_SCALE_PCT),
                    (int) JsonFields.integer(json.get("offset_x"), "logo_layout offset_x", -MAX_OFFSET, MAX_OFFSET),
                    (int) JsonFields.integer(json.get("offset_y"), "logo_layout offset_y", -MAX_OFFSET, MAX_OFFSET));
        }
    }

    /** The draw weight of each rarity, used once any figure of the collection is not common. */
    public record RarityWeights(int common, int rare, int epic, int legendary) {
        public static final int MIN_WEIGHT = 1;
        public static final int MAX_WEIGHT = 1000;
        public static final RarityWeights DEFAULT = new RarityWeights(100, 40, 15, 5);

        public static boolean isValid(int common, int rare, int epic, int legendary) {
            return inRange(common) && inRange(rare) && inRange(epic) && inRange(legendary);
        }

        private static boolean inRange(int weight) {
            return weight >= MIN_WEIGHT && weight <= MAX_WEIGHT;
        }

        public int weight(Rarity rarity) {
            return switch (rarity) {
                case COMMON -> common;
                case RARE -> rare;
                case EPIC -> epic;
                case LEGENDARY -> legendary;
            };
        }

        JsonArray toJson() {
            JsonArray json = new JsonArray();
            json.add(common);
            json.add(rare);
            json.add(epic);
            json.add(legendary);
            return json;
        }

        /** Reads a rarity_weights array of the common, rare, epic and legendary weights. */
        public static RarityWeights fromJson(JsonElement element) throws WorldContentException {
            JsonArray json = JsonFields.array(element, "rarity_weights");
            if (json.size() != 4) {
                throw new WorldContentException("rarity_weights must have 4 values");
            }
            int[] weights = new int[4];
            for (int i = 0; i < 4; i++) {
                weights[i] = (int) JsonFields.integer(json.get(i), "rarity_weights", MIN_WEIGHT, MAX_WEIGHT);
            }
            return new RarityWeights(weights[0], weights[1], weights[2], weights[3]);
        }
    }

    /**
     * Another texture of a figure, which using a placed figure cycles to. Variants share the
     * figure's model, poses, size and box face.
     *
     * @param texture the sha of a skin for a skin figure, or of a texture in the model's
     *                proportions for a model figure
     */
    public record Variant(String name, String texture) {
    }

    /** A region of a texture in its pixels: left, top, width and height. */
    public record Uv(int u, int v, int w, int h) {
        JsonArray toJson() {
            JsonArray json = new JsonArray();
            json.add(u);
            json.add(v);
            json.add(w);
            json.add(h);
            return json;
        }
    }

    /**
     * Whether the box shows the figure's face. A skin figure's face is always the skin's;
     * a model figure names where its face lies in its texture.
     *
     * @param uv the face region of a model figure's texture; null for a skin figure
     */
    public record BoxFace(boolean show, @Nullable Uv uv) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("show", show);
            if (uv != null) {
                json.add("uv", uv.toJson());
            }
            return json;
        }

        /**
         * Reads a box_face object. Whether the uv fits the figure's texture is checked
         * against the blob table when the record is parsed.
         */
        public static BoxFace fromJson(JsonElement element, String what) throws WorldContentException {
            JsonObject json = JsonFields.object(element, what);
            JsonFields.keys(json, what, BOX_FACE_KEYS, BOX_FACE_OPTIONAL_KEYS);
            boolean show = JsonFields.bool(json.get("show"), what + " show");
            Uv uv = null;
            if (json.has("uv")) {
                JsonArray array = JsonFields.array(json.get("uv"), what + " uv");
                if (array.size() != 4) {
                    throw new WorldContentException(what + " uv must have 4 values");
                }
                int max = WorldContentLimits.PNG_MAX_SIZE;
                uv = new Uv((int) JsonFields.integer(array.get(0), what + " uv", 0, max),
                        (int) JsonFields.integer(array.get(1), what + " uv", 0, max),
                        (int) JsonFields.integer(array.get(2), what + " uv", 1, max),
                        (int) JsonFields.integer(array.get(3), what + " uv", 1, max));
            }
            return new BoxFace(show, uv);
        }
    }

    /**
     * One figure.
     *
     * @param texture   the sha of its skin or model texture
     * @param model     the sha of its model; null for a skin
     * @param animation the sha of its poses, or null for the shared poses; always null for a skin
     * @param enabled   false once retired: still defined, so existing boxes resolve, but never handed out
     * @param rarity    how rare it is in the draw
     * @param variants  its other textures, at most {@link WorldContentLimits#MAX_VARIANTS_PER_FIGURE}
     * @param boxFace   whether its box shows its face; null for the default (a skin figure's face
     *                  shows, a model figure's does not)
     */
    public record Figure(String id, String name, Kind kind, String texture, @Nullable String model,
                         @Nullable String animation, float scale, boolean enabled, Rarity rarity,
                         List<Variant> variants, @Nullable BoxFace boxFace) {
        public Figure {
            rarity = rarity != null ? rarity : Rarity.COMMON;
            variants = List.copyOf(variants);
        }

        /** A common figure with no variants and the default box face. */
        public Figure(String id, String name, Kind kind, String texture, @Nullable String model,
                      @Nullable String animation, float scale, boolean enabled) {
            this(id, name, kind, texture, model, animation, scale, enabled, Rarity.COMMON, List.of(), null);
        }

        /**
         * This figure with new names, files and size, keeping whether it is retired. Its rarity
         * is kept; its variants and box face are kept unless its kind changes, because a skin's
         * variants and face do not fit a model.
         */
        public Figure withBasics(String name, Kind kind, String texture, @Nullable String model,
                                 @Nullable String animation, float scale) {
            boolean sameKind = kind == this.kind;
            return new Figure(id, name, kind, texture, model, animation, scale, enabled, rarity,
                    sameKind ? variants : List.of(), sameKind ? boxFace : null);
        }

        public Figure withEnabled(boolean next) {
            return new Figure(id, name, kind, texture, model, animation, scale, next, rarity, variants, boxFace);
        }

        public Figure withExtras(Rarity nextRarity, List<Variant> nextVariants, @Nullable BoxFace nextBoxFace) {
            return new Figure(id, name, kind, texture, model, animation, scale, enabled, nextRarity, nextVariants,
                    nextBoxFace);
        }
    }

    public WorldCollectionRecord {
        if (boxTexture != null && boxColor != null) {
            throw new IllegalArgumentException("A box has either a texture or a color");
        }
        figures = List.copyOf(figures);
        // Defaults are normalized away so that equal records always have equal bytes
        if (authorUrl != null && authorUrl.isEmpty()) {
            authorUrl = null;
        }
        if (LogoLayout.DEFAULT.equals(logoLayout)) {
            logoLayout = null;
        }
        if (RarityWeights.DEFAULT.equals(rarityWeights)) {
            rarityWeights = null;
        }
    }

    /** A published record that uses none of the optional features. */
    public WorldCollectionRecord(String id, String name, String author, int red, int green, int blue,
                                 @Nullable String boxTexture, @Nullable String boxColor, @Nullable String logo,
                                 boolean archived, int revision, List<Figure> figures) {
        this(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived, revision, figures,
                false, 0L, null, null, false, null);
    }

    /**
     * The record a create makes: the name, no author, box or logo, the purple background
     * every new collection starts with, no figures and revision 0. The creator compares a
     * collection it has not saved yet against it, so it sends only what differs.
     */
    public static WorldCollectionRecord created(String id, String name, boolean draft) {
        return new WorldCollectionRecord(id, name, "", NEW_BACKGROUND[0], NEW_BACKGROUND[1], NEW_BACKGROUND[2],
                null, null, null, false, 0, List.of()).withDraft(draft);
    }

    public WorldCollectionRecord withDraft(boolean next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, next, editedAt, authorUrl, logoLayout, plush, rarityWeights);
    }

    public WorldCollectionRecord withArchived(boolean next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, next,
                revision, figures, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights);
    }

    public WorldCollectionRecord withRevision(int next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                next, figures, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights);
    }

    public WorldCollectionRecord withEditedAt(long next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, draft, next, authorUrl, logoLayout, plush, rarityWeights);
    }

    public WorldCollectionRecord withFigures(List<Figure> next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, next, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights);
    }

    /**
     * This record with new names, colours, box and logo. A logo layout has nothing to place
     * once the logo is removed, so it goes with it.
     */
    public WorldCollectionRecord withIdentity(String nextName, String nextAuthor, int nextRed, int nextGreen,
                                              int nextBlue, @Nullable String nextBoxTexture,
                                              @Nullable String nextBoxColor, @Nullable String nextLogo) {
        return new WorldCollectionRecord(id, nextName, nextAuthor, nextRed, nextGreen, nextBlue, nextBoxTexture,
                nextBoxColor, nextLogo, archived, revision, figures, draft, editedAt, authorUrl,
                nextLogo != null ? logoLayout : null, plush, rarityWeights);
    }

    public WorldCollectionRecord withExtras(@Nullable String nextAuthorUrl, @Nullable LogoLayout nextLogoLayout,
                                            boolean nextPlush, @Nullable RarityWeights nextRarityWeights) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, draft, editedAt, nextAuthorUrl, nextLogoLayout, nextPlush, nextRarityWeights);
    }

    /** Whether any figure has a rarity other than common, which makes the draw weighted. */
    public boolean usesRarity() {
        for (Figure figure : figures) {
            if (figure.rarity() != Rarity.COMMON) {
                return true;
            }
        }
        return false;
    }

    /** The weights the draw uses: the collection's own, or {@link RarityWeights#DEFAULT}. */
    public RarityWeights effectiveRarityWeights() {
        return rarityWeights != null ? rarityWeights : RarityWeights.DEFAULT;
    }

    /** A figure's draw weight: its rarity's weight once the collection uses rarities, else 1. */
    public int drawWeight(Figure figure) {
        return usesRarity() ? effectiveRarityWeights().weight(figure.rarity()) : 1;
    }

    /** The skin variants of every figure together. */
    public int variantCount() {
        int count = 0;
        for (Figure figure : figures) {
            count += figure.variants().size();
        }
        return count;
    }

    /** The logo placement in use: the record's own, or {@link LogoLayout#DEFAULT}. */
    public LogoLayout effectiveLogoLayout() {
        return logoLayout != null ? logoLayout : LogoLayout.DEFAULT;
    }

    /** The slug the collection was created with. */
    public String slug() {
        return id.substring(WorldContentIds.COLLECTION_PREFIX.length());
    }

    public int[] backgroundColor() {
        return new int[] {red, green, blue};
    }

    public Optional<Figure> figure(String figureId) {
        return figures.stream().filter(figure -> figure.id().equals(figureId)).findFirst();
    }

    /** Every sha the record names, in a stable order. */
    public Set<String> referencedShas() {
        Set<String> shas = new LinkedHashSet<>();
        if (boxTexture != null) {
            shas.add(boxTexture);
        }
        if (logo != null) {
            shas.add(logo);
        }
        for (Figure figure : figures) {
            shas.add(figure.texture());
            if (figure.model() != null) {
                shas.add(figure.model());
            }
            if (figure.animation() != null) {
                shas.add(figure.animation());
            }
            for (Variant variant : figure.variants()) {
                shas.add(variant.texture());
            }
        }
        return shas;
    }

    /** The index of a scale in {@link #SCALES}, or -1. */
    public static int scaleIndex(float scale) {
        return SCALES.indexOf(scale);
    }

    /** The record in its stored and wire form. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("author", author);
        JsonArray color = new JsonArray();
        color.add(red);
        color.add(green);
        color.add(blue);
        json.add("background_color", color);
        if (boxTexture != null) {
            JsonObject box = new JsonObject();
            box.addProperty("texture", boxTexture);
            json.add("box", box);
        } else if (boxColor != null) {
            JsonObject box = new JsonObject();
            box.addProperty("color", boxColor);
            json.add("box", box);
        } else {
            json.add("box", JsonNull.INSTANCE);
        }
        json.add("logo", nullable(logo));
        json.addProperty("archived", archived);
        json.addProperty("revision", revision);
        JsonArray array = new JsonArray();
        for (Figure figure : figures) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", figure.id());
            entry.addProperty("name", figure.name());
            entry.addProperty("kind", figure.kind().serializedName());
            entry.addProperty("texture", figure.texture());
            entry.add("model", nullable(figure.model()));
            entry.add("animation", nullable(figure.animation()));
            entry.addProperty("scale", (double) figure.scale());
            entry.addProperty("enabled", figure.enabled());
            if (figure.rarity() != Rarity.COMMON) {
                entry.addProperty("rarity", figure.rarity().serializedName());
            }
            if (!figure.variants().isEmpty()) {
                JsonArray variants = new JsonArray();
                for (Variant variant : figure.variants()) {
                    JsonObject variantJson = new JsonObject();
                    variantJson.addProperty("name", variant.name());
                    variantJson.addProperty("texture", variant.texture());
                    variants.add(variantJson);
                }
                entry.add("variants", variants);
            }
            if (figure.boxFace() != null) {
                entry.add("box_face", figure.boxFace().toJson());
            }
            array.add(entry);
        }
        json.add("figures", array);
        // Optional keys come last and only when they differ from their default
        if (draft) {
            json.addProperty("draft", true);
        }
        if (editedAt > 0) {
            json.addProperty("edited_at", editedAt);
        }
        if (authorUrl != null) {
            json.addProperty("author_url", authorUrl);
        }
        if (logoLayout != null) {
            json.add("logo_layout", logoLayout.toJson());
        }
        if (plush) {
            json.addProperty("plush", true);
        }
        if (rarityWeights != null) {
            json.add("rarity_weights", rarityWeights.toJson());
        }
        return json;
    }

    /** The size of the record in its stored and wire form, which may not exceed {@link WorldContentLimits#RECORD_BYTES}. */
    public static int encodedSize(WorldCollectionRecord record) {
        return record.toUtf8().length;
    }

    /** The record as the UTF-8 bytes that are stored and sent; the same record always gives the same bytes. */
    public byte[] toUtf8() {
        return GSON.toJson(toJson()).getBytes(StandardCharsets.UTF_8);
    }

    /** Checks this record against the blob table by its own serialized form, as a receiver would. */
    public WorldCollectionRecord validated(Map<String, BlobInfo> blobs) throws WorldContentException {
        return parse(toUtf8(), blobs);
    }

    /** Parses and checks a record received or read as bytes. */
    public static WorldCollectionRecord parse(byte[] utf8, Map<String, BlobInfo> blobs) throws WorldContentException {
        if (utf8.length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException("Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        return fromJson(JsonFields.parse(utf8, WorldContentLimits.RECORD_BYTES), blobs);
    }

    /** Checks a record already parsed strictly, such as one embedded in the store file. */
    public static WorldCollectionRecord fromJson(JsonObject json, Map<String, BlobInfo> blobs)
            throws WorldContentException {
        JsonFields.keys(json, "collection", KEYS, OPTIONAL_KEYS);
        String id = JsonFields.string(json.get("id"), "id", 32);
        if (!WorldContentIds.isWorldCollectionId(id)) {
            throw new WorldContentException("Collection id must be w- and 1 to 24 of a-z, 0-9 and _");
        }
        String name = text(json.get("name"), "name", 1, WorldContentLimits.MAX_NAME_LENGTH);
        String author = text(json.get("author"), "author", 0, WorldContentLimits.MAX_AUTHOR_LENGTH);
        JsonArray color = JsonFields.array(json.get("background_color"), "background_color");
        if (color.size() != 3) {
            throw new WorldContentException("background_color must have 3 values");
        }
        int red = (int) JsonFields.integer(color.get(0), "background_color", 0, 255);
        int green = (int) JsonFields.integer(color.get(1), "background_color", 0, 255);
        int blue = (int) JsonFields.integer(color.get(2), "background_color", 0, 255);

        String boxTexture = null;
        String boxColor = null;
        JsonElement box = json.get("box");
        if (!box.isJsonNull()) {
            JsonObject boxObject = JsonFields.object(box, "box");
            if (boxObject.size() != 1) {
                throw new WorldContentException("box must name either a texture or a color");
            }
            if (boxObject.has("texture")) {
                boxTexture = sha(boxObject.get("texture"), "box texture");
                PngValidator.Role.BOX.check(blob(blobs, boxTexture, "box texture"));
            } else if (boxObject.has("color")) {
                boxColor = JsonFields.string(boxObject.get("color"), "box color", 16);
                if (!isBoxColor(boxColor)) {
                    throw new WorldContentException("Unknown box color " + WorldContentException.excerpt(boxColor));
                }
            } else {
                throw new WorldContentException("box must name either a texture or a color");
            }
        }
        String logo = null;
        if (!json.get("logo").isJsonNull()) {
            logo = sha(json.get("logo"), "logo");
            PngValidator.Role.LOGO.check(blob(blobs, logo, "logo"));
        }
        boolean archived = JsonFields.bool(json.get("archived"), "archived");
        int revision = (int) JsonFields.integer(json.get("revision"), "revision", 0, WorldContentLimits.MAX_REVISION);

        JsonArray array = JsonFields.array(json.get("figures"), "figures");
        if (array.size() > WorldContentLimits.MAX_FIGURES) {
            throw new WorldContentException("Collection has more than " + WorldContentLimits.MAX_FIGURES + " figures");
        }
        List<Figure> figures = new ArrayList<>(array.size());
        Set<String> figureIds = new HashSet<>();
        int variants = 0;
        for (JsonElement element : array) {
            Figure figure = figure(JsonFields.object(element, "figure"), blobs);
            if (!figureIds.add(figure.id())) {
                throw new WorldContentException("Figure " + figure.id() + " is listed twice");
            }
            variants += figure.variants().size();
            figures.add(figure);
        }
        if (variants > WorldContentLimits.MAX_VARIANTS) {
            throw new WorldContentException("Collection has more than " + WorldContentLimits.MAX_VARIANTS
                    + " skin variants");
        }

        boolean draft = trueOnly(json, "draft");
        if (draft && archived) {
            throw new WorldContentException("A draft is never archived");
        }
        long editedAt = json.has("edited_at")
                ? JsonFields.integer(json.get("edited_at"), "edited_at", 1, MAX_EDITED_AT) : 0L;
        String authorUrl = json.has("author_url") ? authorUrl(json.get("author_url")) : null;
        LogoLayout logoLayout = null;
        if (json.has("logo_layout")) {
            if (logo == null) {
                throw new WorldContentException("logo_layout places a logo the collection does not have");
            }
            logoLayout = LogoLayout.fromJson(json.get("logo_layout"));
        }
        boolean plush = trueOnly(json, "plush");
        RarityWeights rarityWeights = json.has("rarity_weights") ? RarityWeights.fromJson(json.get("rarity_weights")) : null;
        WorldCollectionRecord record = new WorldCollectionRecord(id, name, author, red, green, blue,
                boxTexture, boxColor, logo, archived, revision, figures, draft, editedAt, authorUrl, logoLayout,
                plush, rarityWeights);
        if (record.toUtf8().length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException("Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        return record;
    }

    private static Figure figure(JsonObject json, Map<String, BlobInfo> blobs) throws WorldContentException {
        JsonFields.keys(json, "figure", FIGURE_KEYS, FIGURE_OPTIONAL_KEYS);
        String id = JsonFields.string(json.get("id"), "figure id", 24);
        if (!WorldContentIds.isSlug(id)) {
            throw new WorldContentException("Figure id must be 1 to 24 of a-z, 0-9 and _");
        }
        String name = text(json.get("name"), "figure " + id + " name", 1, WorldContentLimits.MAX_NAME_LENGTH);
        Kind kind = Kind.byName(JsonFields.string(json.get("kind"), "kind", 16));
        if (kind == null) {
            throw new WorldContentException("Figure " + id + " kind must be skin or model");
        }
        String texture = sha(json.get("texture"), "figure " + id + " texture");
        String model = json.get("model").isJsonNull() ? null : sha(json.get("model"), "figure " + id + " model");
        String animation = json.get("animation").isJsonNull()
                ? null : sha(json.get("animation"), "figure " + id + " animation");
        double scaleValue = JsonFields.number(json.get("scale"), "scale", 0, 16);
        int scaleIndex = scaleIndex((float) scaleValue);
        if (scaleIndex < 0 || SCALES.get(scaleIndex) != scaleValue) {
            throw new WorldContentException("Figure " + id + " scale must be one of " + SCALES);
        }
        boolean enabled = JsonFields.bool(json.get("enabled"), "enabled");

        BlobInfo textureInfo = blob(blobs, texture, "figure " + id + " texture");
        BlobInfo modelInfo = null;
        if (kind == Kind.SKIN) {
            if (model != null || animation != null) {
                throw new WorldContentException("Skin figure " + id + " cannot have a model or animation");
            }
            PngValidator.Role.SKIN.check(textureInfo);
        } else {
            if (model == null) {
                throw new WorldContentException("Model figure " + id + " has no model");
            }
            modelInfo = blob(blobs, model, "figure " + id + " model");
            if (modelInfo.type() != BlobType.GEO) {
                throw new WorldContentException("Figure " + id + " model is not a .geo.json file");
            }
            PngValidator.Role.MODEL_TEXTURE.check(textureInfo);
            if ((long) textureInfo.width() * modelInfo.texHeight() != (long) textureInfo.height() * modelInfo.texWidth()) {
                throw new WorldContentException("Figure " + id + " texture is " + textureInfo.width() + "x"
                        + textureInfo.height() + " but the model expects " + modelInfo.texWidth() + ":"
                        + modelInfo.texHeight());
            }
            if (animation != null) {
                BlobInfo animationInfo = blob(blobs, animation, "figure " + id + " animation");
                if (animationInfo.type() != BlobType.ANIMATION || !animationInfo.hasPose(BlobInfo.POSE_STAND)) {
                    throw new WorldContentException("Figure " + id + " animation has no Pose_Stand");
                }
            }
        }

        Rarity rarity = json.has("rarity") ? Rarity.fromJson(json.get("rarity"), "figure " + id + " rarity") : Rarity.COMMON;
        List<Variant> variants = new ArrayList<>();
        if (json.has("variants")) {
            JsonArray array = JsonFields.array(json.get("variants"), "figure " + id + " variants");
            if (array.isEmpty() || array.size() > WorldContentLimits.MAX_VARIANTS_PER_FIGURE) {
                throw new WorldContentException("Figure " + id + " has 1 to "
                        + WorldContentLimits.MAX_VARIANTS_PER_FIGURE + " variants when it lists any");
            }
            for (JsonElement element : array) {
                variants.add(variant(JsonFields.object(element, "figure " + id + " variant"), id, kind, modelInfo,
                        blobs));
            }
        }
        BoxFace boxFace = null;
        if (json.has("box_face")) {
            boxFace = BoxFace.fromJson(json.get("box_face"), "figure " + id + " box_face");
            checkBoxFace(boxFace, id, kind, textureInfo);
        }
        return new Figure(id, name, kind, texture, model, animation, SCALES.get(scaleIndex), enabled, rarity,
                variants, boxFace);
    }

    /** A variant: a skin for a skin figure, or a texture in the model's proportions for a model figure. */
    private static Variant variant(JsonObject json, String figureId, Kind kind, @Nullable BlobInfo modelInfo,
                                   Map<String, BlobInfo> blobs) throws WorldContentException {
        JsonFields.keys(json, "figure " + figureId + " variant", VARIANT_KEYS, Set.of());
        String name = text(json.get("name"), "figure " + figureId + " variant name", 1, WorldContentLimits.MAX_NAME_LENGTH);
        String texture = sha(json.get("texture"), "figure " + figureId + " variant texture");
        BlobInfo info = blob(blobs, texture, "figure " + figureId + " variant texture");
        if (kind == Kind.SKIN) {
            PngValidator.Role.SKIN.check(info);
        } else {
            PngValidator.Role.MODEL_TEXTURE.check(info);
            if (modelInfo != null && (long) info.width() * modelInfo.texHeight() != (long) info.height() * modelInfo.texWidth()) {
                throw new WorldContentException("Figure " + figureId + " variant " + name + " texture is "
                        + info.width() + "x" + info.height() + " but the model expects " + modelInfo.texWidth() + ":"
                        + modelInfo.texHeight());
            }
        }
        return new Variant(name, texture);
    }

    /** A skin figure's face is the skin's own; a model figure's names a region inside its texture. */
    private static void checkBoxFace(BoxFace boxFace, String figureId, Kind kind, BlobInfo textureInfo)
            throws WorldContentException {
        if (kind == Kind.SKIN) {
            if (boxFace.uv() != null) {
                throw new WorldContentException("Skin figure " + figureId + " box_face cannot name a uv");
            }
            return;
        }
        Uv uv = boxFace.uv();
        if (uv == null) {
            throw new WorldContentException("Model figure " + figureId + " box_face needs a uv");
        }
        if (uv.u() < 0 || uv.v() < 0 || uv.w() < 1 || uv.h() < 1
                || (long) uv.u() + uv.w() > textureInfo.width() || (long) uv.v() + uv.h() > textureInfo.height()) {
            throw new WorldContentException("Figure " + figureId + " box_face uv lies outside its "
                    + textureInfo.width() + "x" + textureInfo.height() + " texture");
        }
    }

    /** Reads an author link and refuses anything {@link #isValidAuthorUrl} does not accept. */
    public static String authorUrl(JsonElement element) throws WorldContentException {
        String url = JsonFields.string(element, "author_url", WorldContentLimits.MAX_AUTHOR_URL_LENGTH);
        if (!isValidAuthorUrl(url)) {
            throw new WorldContentException("author_url must be an http or https link with a host, without spaces");
        }
        return url;
    }

    /**
     * Whether a link may be an author link: at most {@link WorldContentLimits#MAX_AUTHOR_URL_LENGTH}
     * printable ASCII characters without spaces, an http or https scheme, a host and no user
     * info. ASCII alone keeps look-alike letters out of a link players are asked to open.
     */
    public static boolean isValidAuthorUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > WorldContentLimits.MAX_AUTHOR_URL_LENGTH) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c > '~') {
                return false;
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        return (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null;
    }

    /** An optional flag that is written only when true; false is the omitted default. */
    private static boolean trueOnly(JsonObject json, String key) throws WorldContentException {
        if (!json.has(key)) {
            return false;
        }
        if (!JsonFields.bool(json.get(key), key)) {
            throw new WorldContentException(key + " is written only when true");
        }
        return true;
    }

    /**
     * Checks the caps and decoded budgets over a whole would-be store: the number of
     * collections, the bytes each one references, and the pixels, cubes and keyframes of
     * every distinct file any of them references.
     */
    public static void checkBudgets(Collection<WorldCollectionRecord> records, Map<String, BlobInfo> blobs)
            throws WorldContentException {
        if (records.size() > WorldContentLimits.MAX_COLLECTIONS) {
            throw new WorldContentException("Budget collections: more than " + WorldContentLimits.MAX_COLLECTIONS);
        }
        Set<String> ids = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        for (WorldCollectionRecord record : records) {
            if (!ids.add(record.id())) {
                throw new WorldContentException("Collection " + record.id() + " is defined twice");
            }
            long bytes = 0;
            for (String sha : record.referencedShas()) {
                bytes += blob(blobs, sha, "collection " + record.id() + " file").size();
            }
            if (bytes > WorldContentLimits.MAX_COLLECTION_BYTES) {
                throw new WorldContentException("Budget collection bytes: " + record.id() + " uses "
                        + bytes / 1024 + " KiB of " + WorldContentLimits.MAX_COLLECTION_BYTES / 1024);
            }
            referenced.addAll(record.referencedShas());
        }
        long pixels = 0;
        long cubes = 0;
        long keyframes = 0;
        for (String sha : referenced) {
            BlobInfo info = blobs.get(sha);
            switch (info.type()) {
                case PNG -> pixels += info.pixels();
                case GEO -> cubes += info.cubes();
                case ANIMATION -> keyframes += info.keyframes();
            }
        }
        if (pixels > WorldContentLimits.MAX_DECODED_PIXELS) {
            throw new WorldContentException("Budget texture pixels: " + pixels + " of " + WorldContentLimits.MAX_DECODED_PIXELS);
        }
        if (cubes > WorldContentLimits.MAX_DECODED_CUBES) {
            throw new WorldContentException("Budget model cubes: " + cubes + " of " + WorldContentLimits.MAX_DECODED_CUBES);
        }
        if (keyframes > WorldContentLimits.MAX_DECODED_KEYFRAMES) {
            throw new WorldContentException("Budget animation keyframes: " + keyframes + " of "
                    + WorldContentLimits.MAX_DECODED_KEYFRAMES);
        }
    }

    /** Whether the name is a box colour, each of which has a bundled plain box texture. */
    public static boolean isBoxColor(String name) {
        for (PopBlockColor color : PopBlockColor.values()) {
            if (color.getSerializedName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A name or author: at most max characters, at least min, not blank when required, and
     * without control, format or unpaired surrogate characters.
     */
    private static String text(JsonElement element, String what, int min, int max) throws WorldContentException {
        String text = JsonFields.string(element, what, max);
        if (text.length() < min || (min > 0 && text.isBlank())) {
            throw new WorldContentException(what + " must not be empty");
        }
        if (!isPlainText(text)) {
            throw new WorldContentException(what + " has a control or formatting character");
        }
        return text;
    }

    /** Whether the text has no control, format or unpaired surrogate characters. */
    public static boolean isPlainText(String text) {
        return text.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE);
    }

    private static String sha(JsonElement element, String what) throws WorldContentException {
        return Sha256.require(JsonFields.string(element, what, 64), what);
    }

    private static BlobInfo blob(Map<String, BlobInfo> blobs, String sha, String what) throws WorldContentException {
        BlobInfo info = blobs.get(sha);
        if (info == null) {
            throw new WorldContentException(what + " names a file that is not stored");
        }
        return info;
    }

    private static JsonElement nullable(@Nullable String value) {
        return value != null ? new JsonPrimitive(value) : JsonNull.INSTANCE;
    }
}
