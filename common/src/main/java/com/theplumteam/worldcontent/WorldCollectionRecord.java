package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.CoreText;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.remote.player.PlayerIndex;
import com.theplumteam.remote.player.PlayerRules;
import com.theplumteam.remote.player.PlayerText;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * A collection a player publishes is the same record in a narrower form ({@link #toPlayerJson},
 * {@link #parsePlayer}): what the collection is, without what belongs to the world it was made
 * in or to whoever publishes it.
 *
 * @param boxTexture    the sha of the box texture, or null
 * @param boxColor      the name of a plain box's colour ({@link BoxColors}), or null; never set
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
 * @param holographic   whether a draw from it may come out holographic, as every draw may by
 *                      default; false keeps every figure it hands out plain
 */
public record WorldCollectionRecord(String id, String name, String author, int red, int green, int blue,
                                    @Nullable String boxTexture, @Nullable String boxColor, @Nullable String logo,
                                    boolean archived, int revision, List<Figure> figures,
                                    boolean draft, long editedAt, @Nullable String authorUrl,
                                    @Nullable LogoLayout logoLayout, boolean plush,
                                    @Nullable RarityWeights rarityWeights, boolean holographic) {
    /** The figure sizes an operator can choose from. */
    public static final List<Float> SCALES = List.of(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f);
    // The background a new collection starts with
    private static final int[] NEW_BACKGROUND = {152, 48, 167};
    /** The latest edit time a record may carry: the last second of the year 9999. */
    public static final long MAX_EDITED_AT = 253_402_300_799L;
    private static final Set<String> KEYS = Set.of("id", "name", "author", "background_color", "box", "logo",
            "archived", "revision", "figures");
    private static final Set<String> OPTIONAL_KEYS = Set.of("draft", "edited_at", "author_url", "logo_layout",
            "plush", "rarity_weights", "holographic");
    private static final Set<String> FIGURE_KEYS = Set.of("id", "name", "kind", "texture", "model", "animation",
            "scale", "enabled");
    private static final Set<String> FIGURE_OPTIONAL_KEYS = Set.of("rarity", "variants", "box_face");
    private static final Set<String> LOGO_LAYOUT_KEYS = Set.of("scale_pct", "offset_x", "offset_y");
    private static final Set<String> VARIANT_KEYS = Set.of("name", "texture");
    private static final Set<String> BOX_FACE_KEYS = Set.of("show");
    private static final Set<String> BOX_FACE_OPTIONAL_KEYS = Set.of("uv");
    // The published form: no id, author, link, archive, revision, draft or edit time, and no retired figure
    private static final Set<String> PLAYER_KEYS = Set.of("name", "background_color", "box", "logo", "figures");
    private static final Set<String> PLAYER_OPTIONAL_KEYS = Set.of("logo_layout", "plush", "rarity_weights",
            "holographic");
    private static final Set<String> PLAYER_FIGURE_KEYS = Set.of("id", "name", "kind", "texture", "model", "animation",
            "scale");
    /** What a refused author link is told. */
    public static final String AUTHOR_URL_RULE = "author_url must be an http or https link with a host, without spaces";

    /**
     * Which links a record's author link may be. The host that parses the record supplies it:
     * reading a link takes more than the content core has, and the game's rule is
     * {@link AuthorLinks#RULE}.
     */
    public interface LinkRule {
        boolean allows(String url);
    }

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
                throw new WorldContentException(ContentError.RECORD_RARITY, what + " must be rare, epic or legendary");
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
                throw new WorldContentException(ContentError.RECORD_RARITY_WEIGHTS,
                        "rarity_weights must have 4 values");
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
                    throw new WorldContentException(ContentError.RECORD_BOX_FACE, what + " uv must have 4 values");
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
                false, 0L, null, null, false, null, true);
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
                revision, figures, next, editedAt, authorUrl, logoLayout, plush, rarityWeights, holographic);
    }

    public WorldCollectionRecord withArchived(boolean next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, next,
                revision, figures, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights, holographic);
    }

    public WorldCollectionRecord withRevision(int next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                next, figures, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights, holographic);
    }

    public WorldCollectionRecord withEditedAt(long next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, draft, next, authorUrl, logoLayout, plush, rarityWeights, holographic);
    }

    public WorldCollectionRecord withFigures(List<Figure> next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, next, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights, holographic);
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
                nextLogo != null ? logoLayout : null, plush, rarityWeights, holographic);
    }

    public WorldCollectionRecord withExtras(@Nullable String nextAuthorUrl, @Nullable LogoLayout nextLogoLayout,
                                            boolean nextPlush, @Nullable RarityWeights nextRarityWeights) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, draft, editedAt, nextAuthorUrl, nextLogoLayout, nextPlush, nextRarityWeights,
                holographic);
    }

    /** This record with holographic draws allowed, or turned off. */
    public WorldCollectionRecord withHolographic(boolean next) {
        return new WorldCollectionRecord(id, name, author, red, green, blue, boxTexture, boxColor, logo, archived,
                revision, figures, draft, editedAt, authorUrl, logoLayout, plush, rarityWeights, next);
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
        json.add("background_color", colorJson());
        json.add("box", boxJson());
        json.add("logo", nullable(logo));
        json.addProperty("archived", archived);
        json.addProperty("revision", revision);
        JsonArray array = new JsonArray();
        for (Figure figure : figures) {
            array.add(figureJson(figure, false));
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
        addLookExtras(json);
        return json;
    }

    /**
     * The record in the form a player publishes: its name, look and figures. What belongs to
     * the world it was made in (id, revision, draft, archive, edit time) and to its publisher
     * (author, link) is left out, and so is every retired figure, whose files are then never
     * published.
     */
    public JsonObject toPlayerJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.add("background_color", colorJson());
        json.add("box", boxJson());
        json.add("logo", nullable(logo));
        JsonArray array = new JsonArray();
        for (Figure figure : figures) {
            if (figure.enabled()) {
                array.add(figureJson(figure, true));
            }
        }
        json.add("figures", array);
        addLookExtras(json);
        return json;
    }

    /** The published form as the bytes that are published; the same record always gives the same bytes. */
    public byte[] toPlayerUtf8() {
        return JsonOut.write(toPlayerJson()).getBytes(StandardCharsets.UTF_8);
    }

    private JsonArray colorJson() {
        JsonArray color = new JsonArray();
        color.add(red);
        color.add(green);
        color.add(blue);
        return color;
    }

    private JsonElement boxJson() {
        if (boxTexture == null && boxColor == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject box = new JsonObject();
        if (boxTexture != null) {
            box.addProperty("texture", boxTexture);
        } else {
            box.addProperty("color", boxColor);
        }
        return box;
    }

    // A figure as either form writes it; the published form has no retired figures, so no enabled key
    private static JsonObject figureJson(Figure figure, boolean player) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", figure.id());
        entry.addProperty("name", figure.name());
        entry.addProperty("kind", figure.kind().serializedName());
        entry.addProperty("texture", figure.texture());
        entry.add("model", nullable(figure.model()));
        entry.add("animation", nullable(figure.animation()));
        entry.addProperty("scale", (double) figure.scale());
        if (!player) {
            entry.addProperty("enabled", figure.enabled());
        }
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
        return entry;
    }

    // The optional keys both forms share, each only when it differs from its default
    private void addLookExtras(JsonObject json) {
        if (logoLayout != null) {
            json.add("logo_layout", logoLayout.toJson());
        }
        if (plush) {
            json.addProperty("plush", true);
        }
        if (rarityWeights != null) {
            json.add("rarity_weights", rarityWeights.toJson());
        }
        if (!holographic) {
            json.addProperty("holographic", false);
        }
    }

    /** The size of the record in its stored and wire form, which may not exceed {@link WorldContentLimits#RECORD_BYTES}. */
    public static int encodedSize(WorldCollectionRecord record) {
        return record.toUtf8().length;
    }

    /** The record as the UTF-8 bytes that are stored and sent; the same record always gives the same bytes. */
    public byte[] toUtf8() {
        return JsonOut.write(toJson()).getBytes(StandardCharsets.UTF_8);
    }

    /** Checks this record against the blob table by its own serialized form, as a receiver would. */
    public WorldCollectionRecord validated(Map<String, BlobInfo> blobs, LinkRule links) throws WorldContentException {
        return parse(toUtf8(), blobs, links);
    }

    /** Parses and checks a record received or read as bytes. */
    public static WorldCollectionRecord parse(byte[] utf8, Map<String, BlobInfo> blobs, LinkRule links)
            throws WorldContentException {
        if (utf8.length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException(ContentError.RECORD_BYTES,
                    "Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        return fromJson(JsonFields.parse(utf8, WorldContentLimits.RECORD_BYTES), blobs, links);
    }

    /** Checks a record already parsed strictly, such as one embedded in the store file. */
    public static WorldCollectionRecord fromJson(JsonObject json, Map<String, BlobInfo> blobs, LinkRule links)
            throws WorldContentException {
        JsonFields.keys(json, "collection", KEYS, OPTIONAL_KEYS);
        String id = JsonFields.string(json.get("id"), "id", 32);
        if (!WorldContentIds.isWorldCollectionId(id)) {
            throw new WorldContentException(ContentError.RECORD_ID,
                    "Collection id must be w- and 1 to 24 of a-z, 0-9 and _");
        }
        String name = text(json.get("name"), "name", 1, WorldContentLimits.MAX_NAME_LENGTH, false);
        String author = text(json.get("author"), "author", 0, WorldContentLimits.MAX_AUTHOR_LENGTH, false);
        int[] color = color(json);
        String[] box = box(json, blobs);
        String logo = logo(json, blobs);
        boolean archived = JsonFields.bool(json.get("archived"), "archived");
        int revision = (int) JsonFields.integer(json.get("revision"), "revision", 0, WorldContentLimits.MAX_REVISION);
        List<Figure> figures = figures(json, blobs, false);

        boolean draft = trueOnly(json, "draft");
        if (draft && archived) {
            throw new WorldContentException(ContentError.RECORD_FLAG, "A draft is never archived");
        }
        long editedAt = json.has("edited_at")
                ? JsonFields.integer(json.get("edited_at"), "edited_at", 1, MAX_EDITED_AT) : 0L;
        String authorUrl = null;
        if (json.has("author_url")) {
            authorUrl = JsonFields.string(json.get("author_url"), "author_url", WorldContentLimits.MAX_AUTHOR_URL_LENGTH);
            if (!links.allows(authorUrl)) {
                throw new WorldContentException(ContentError.RECORD_AUTHOR_URL, AUTHOR_URL_RULE);
            }
        }
        LogoLayout logoLayout = null;
        if (json.has("logo_layout")) {
            if (logo == null) {
                throw new WorldContentException(ContentError.RECORD_LOGO_LAYOUT,
                        "logo_layout places a logo the collection does not have");
            }
            logoLayout = LogoLayout.fromJson(json.get("logo_layout"));
        }
        boolean plush = trueOnly(json, "plush");
        RarityWeights rarityWeights = json.has("rarity_weights") ? RarityWeights.fromJson(json.get("rarity_weights")) : null;
        boolean holographic = !falseOnly(json, "holographic");
        WorldCollectionRecord record = new WorldCollectionRecord(id, name, author, color[0], color[1], color[2],
                box[0], box[1], logo, archived, revision, figures, draft, editedAt, authorUrl, logoLayout,
                plush, rarityWeights, holographic);
        if (record.toUtf8().length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException(ContentError.RECORD_BYTES,
                    "Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        return record;
    }

    /**
     * Parses and checks a record in the form a player publishes, which names neither its
     * collection nor its author: both come from the signed index it is listed in.
     *
     * <p>It is the World record's grammar without the keys {@link #toPlayerJson} leaves out,
     * held to more: at least one figure, names of the characters and spacing
     * {@link PlayerText} allows, numbers within the bounds of what players publish, the budgets
     * of one published collection, and bytes that are exactly what the record writes back, so
     * a record has one spelling.
     *
     * @param id       the player collection it is the record of
     * @param author   the verified name of who published it, or empty when none is shown
     * @param revision the revision of the collection it is the record of
     */
    public static WorldCollectionRecord parsePlayer(byte[] utf8, Map<String, BlobInfo> blobs, String id, String author,
                                                    int revision) throws WorldContentException {
        if (!PlayerIds.isPlayerId(id) || (!author.isEmpty() && !PlayerIndex.isAccountName(author)) || revision < 0) {
            throw new IllegalArgumentException("Invalid player collection identity");
        }
        if (utf8.length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException(ContentError.RECORD_BYTES,
                    "Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        JsonObject json = JsonFields.parsePlayer(utf8, WorldContentLimits.RECORD_BYTES);
        JsonFields.keys(json, "collection", PLAYER_KEYS, PLAYER_OPTIONAL_KEYS);
        String name = text(json.get("name"), "name", 1, WorldContentLimits.MAX_NAME_LENGTH, true);
        int[] color = color(json);
        String[] box = box(json, blobs);
        String logo = logo(json, blobs);
        List<Figure> figures = figures(json, blobs, true);
        if (figures.isEmpty()) {
            throw new WorldContentException(ContentError.RECORD_FIGURES, "A published collection has at least one figure");
        }
        LogoLayout logoLayout = null;
        if (json.has("logo_layout")) {
            if (logo == null) {
                throw new WorldContentException(ContentError.RECORD_LOGO_LAYOUT,
                        "logo_layout places a logo the collection does not have");
            }
            logoLayout = LogoLayout.fromJson(json.get("logo_layout"));
        }
        boolean plush = trueOnly(json, "plush");
        RarityWeights rarityWeights = json.has("rarity_weights") ? RarityWeights.fromJson(json.get("rarity_weights")) : null;
        boolean holographic = !falseOnly(json, "holographic");
        WorldCollectionRecord record = new WorldCollectionRecord(id, name, author, color[0], color[1], color[2],
                box[0], box[1], logo, false, revision, figures, false, 0L, null, logoLayout, plush, rarityWeights,
                holographic);
        if (!Arrays.equals(record.toPlayerUtf8(), utf8)) {
            throw new WorldContentException(ContentError.JSON_NOT_CANONICAL,
                    "Collection record is not written the way a record writes itself");
        }
        checkPlayerBudgets(record, blobs);
        return record;
    }

    /**
     * Checks what one published collection may hold: the number of files it names, their bytes,
     * and the pixels, cubes and keyframes they decode to.
     */
    public static void checkPlayerBudgets(WorldCollectionRecord record, Map<String, BlobInfo> blobs)
            throws WorldContentException {
        int files = record.referencedShas().size();
        if (files > PlayerRules.MAX_BLOBS) {
            throw new WorldContentException(ContentError.BUDGET_FILES,
                    "Budget files: " + files + " of " + PlayerRules.MAX_BLOBS);
        }
        checkBudgets(List.of(record), blobs);
    }

    private static int[] color(JsonObject json) throws WorldContentException {
        JsonArray color = JsonFields.array(json.get("background_color"), "background_color");
        if (color.size() != 3) {
            throw new WorldContentException(ContentError.RECORD_BACKGROUND, "background_color must have 3 values");
        }
        return new int[] {(int) JsonFields.integer(color.get(0), "background_color", 0, 255),
            (int) JsonFields.integer(color.get(1), "background_color", 0, 255),
            (int) JsonFields.integer(color.get(2), "background_color", 0, 255)};
    }

    // The box's texture and colour, of which at most one is set
    private static String[] box(JsonObject json, Map<String, BlobInfo> blobs) throws WorldContentException {
        String boxTexture = null;
        String boxColor = null;
        JsonElement box = json.get("box");
        if (!box.isJsonNull()) {
            JsonObject boxObject = JsonFields.object(box, "box");
            if (boxObject.size() != 1) {
                throw new WorldContentException(ContentError.RECORD_BOX, "box must name either a texture or a color");
            }
            if (boxObject.has("texture")) {
                boxTexture = sha(boxObject.get("texture"), "box texture");
                PngValidator.Role.BOX.check(blob(blobs, boxTexture, "box texture"));
            } else if (boxObject.has("color")) {
                boxColor = JsonFields.string(boxObject.get("color"), "box color", 16);
                if (!isBoxColor(boxColor)) {
                    throw new WorldContentException(ContentError.RECORD_BOX,
                            "Unknown box color " + WorldContentException.excerpt(boxColor));
                }
            } else {
                throw new WorldContentException(ContentError.RECORD_BOX, "box must name either a texture or a color");
            }
        }
        return new String[] {boxTexture, boxColor};
    }

    @Nullable
    private static String logo(JsonObject json, Map<String, BlobInfo> blobs) throws WorldContentException {
        if (json.get("logo").isJsonNull()) {
            return null;
        }
        String logo = sha(json.get("logo"), "logo");
        PngValidator.Role.LOGO.check(blob(blobs, logo, "logo"));
        return logo;
    }

    private static List<Figure> figures(JsonObject json, Map<String, BlobInfo> blobs, boolean player)
            throws WorldContentException {
        JsonArray array = JsonFields.array(json.get("figures"), "figures");
        if (array.size() > WorldContentLimits.MAX_FIGURES) {
            throw new WorldContentException(ContentError.RECORD_FIGURES,
                    "Collection has more than " + WorldContentLimits.MAX_FIGURES + " figures");
        }
        List<Figure> figures = new ArrayList<>(array.size());
        Set<String> figureIds = new HashSet<>();
        int variants = 0;
        for (JsonElement element : array) {
            Figure figure = figure(JsonFields.object(element, "figure"), blobs, player);
            if (!figureIds.add(figure.id())) {
                throw new WorldContentException(ContentError.RECORD_FIGURE_ID,
                        "Figure " + figure.id() + " is listed twice");
            }
            variants += figure.variants().size();
            figures.add(figure);
        }
        if (variants > WorldContentLimits.MAX_VARIANTS) {
            throw new WorldContentException(ContentError.RECORD_VARIANTS,
                    "Collection has more than " + WorldContentLimits.MAX_VARIANTS
                    + " skin variants");
        }
        return figures;
    }

    private static Figure figure(JsonObject json, Map<String, BlobInfo> blobs, boolean player)
            throws WorldContentException {
        JsonFields.keys(json, "figure", player ? PLAYER_FIGURE_KEYS : FIGURE_KEYS, FIGURE_OPTIONAL_KEYS);
        String id = JsonFields.string(json.get("id"), "figure id", 24);
        if (!WorldContentIds.isSlug(id)) {
            throw new WorldContentException(ContentError.RECORD_FIGURE_ID,
                    "Figure id must be 1 to 24 of a-z, 0-9 and _");
        }
        String name = text(json.get("name"), "figure " + id + " name", 1, WorldContentLimits.MAX_NAME_LENGTH, player);
        Kind kind = Kind.byName(JsonFields.string(json.get("kind"), "kind", 16));
        if (kind == null) {
            throw new WorldContentException(ContentError.RECORD_FIGURE_KIND,
                    "Figure " + id + " kind must be skin or model");
        }
        String texture = sha(json.get("texture"), "figure " + id + " texture");
        String model = json.get("model").isJsonNull() ? null : sha(json.get("model"), "figure " + id + " model");
        String animation = json.get("animation").isJsonNull()
                ? null : sha(json.get("animation"), "figure " + id + " animation");
        double scaleValue = JsonFields.number(json.get("scale"), "scale", 0, 16);
        int scaleIndex = scaleIndex((float) scaleValue);
        if (scaleIndex < 0 || SCALES.get(scaleIndex) != scaleValue) {
            throw new WorldContentException(ContentError.RECORD_SCALE,
                    "Figure " + id + " scale must be one of " + SCALES);
        }
        // A published record lists no retired figure
        boolean enabled = player || JsonFields.bool(json.get("enabled"), "enabled");

        BlobInfo textureInfo = blob(blobs, texture, "figure " + id + " texture");
        BlobInfo modelInfo = null;
        if (kind == Kind.SKIN) {
            if (model != null || animation != null) {
                throw new WorldContentException(ContentError.RECORD_FIGURE_KIND,
                        "Skin figure " + id + " cannot have a model or animation");
            }
            PngValidator.Role.SKIN.check(textureInfo);
        } else {
            if (model == null) {
                throw new WorldContentException(ContentError.RECORD_FIGURE_KIND,
                        "Model figure " + id + " has no model");
            }
            modelInfo = blob(blobs, model, "figure " + id + " model");
            if (modelInfo.type() != BlobType.GEO) {
                throw new WorldContentException(ContentError.RECORD_FIGURE_KIND,
                        "Figure " + id + " model is not a .geo.json file");
            }
            PngValidator.Role.MODEL_TEXTURE.check(textureInfo);
            if ((long) textureInfo.width() * modelInfo.texHeight() != (long) textureInfo.height() * modelInfo.texWidth()) {
                throw new WorldContentException(ContentError.RECORD_TEXTURE_RATIO,
                        "Figure " + id + " texture is " + textureInfo.width() + "x"
                        + textureInfo.height() + " but the model expects " + modelInfo.texWidth() + ":"
                        + modelInfo.texHeight());
            }
            if (animation != null) {
                BlobInfo animationInfo = blob(blobs, animation, "figure " + id + " animation");
                if (animationInfo.type() != BlobType.ANIMATION || !animationInfo.hasPose(BlobInfo.POSE_STAND)) {
                    throw new WorldContentException(ContentError.RECORD_ANIMATION,
                            "Figure " + id + " animation has no Pose_Stand");
                }
            }
        }

        Rarity rarity = json.has("rarity") ? Rarity.fromJson(json.get("rarity"), "figure " + id + " rarity") : Rarity.COMMON;
        List<Variant> variants = new ArrayList<>();
        if (json.has("variants")) {
            JsonArray array = JsonFields.array(json.get("variants"), "figure " + id + " variants");
            if (array.isEmpty() || array.size() > WorldContentLimits.MAX_VARIANTS_PER_FIGURE) {
                throw new WorldContentException(ContentError.RECORD_VARIANTS, "Figure " + id + " has 1 to "
                        + WorldContentLimits.MAX_VARIANTS_PER_FIGURE + " variants when it lists any");
            }
            for (JsonElement element : array) {
                variants.add(variant(JsonFields.object(element, "figure " + id + " variant"), id, kind, modelInfo,
                        blobs, player));
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
                                   Map<String, BlobInfo> blobs, boolean player) throws WorldContentException {
        JsonFields.keys(json, "figure " + figureId + " variant", VARIANT_KEYS, Set.of());
        String name = text(json.get("name"), "figure " + figureId + " variant name", 1,
                WorldContentLimits.MAX_NAME_LENGTH, player);
        String texture = sha(json.get("texture"), "figure " + figureId + " variant texture");
        BlobInfo info = blob(blobs, texture, "figure " + figureId + " variant texture");
        if (kind == Kind.SKIN) {
            PngValidator.Role.SKIN.check(info);
        } else {
            PngValidator.Role.MODEL_TEXTURE.check(info);
            if (modelInfo != null && (long) info.width() * modelInfo.texHeight() != (long) info.height() * modelInfo.texWidth()) {
                throw new WorldContentException(ContentError.RECORD_TEXTURE_RATIO,
                        "Figure " + figureId + " variant " + name + " texture is "
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
                throw new WorldContentException(ContentError.RECORD_BOX_FACE,
                        "Skin figure " + figureId + " box_face cannot name a uv");
            }
            return;
        }
        Uv uv = boxFace.uv();
        if (uv == null) {
            throw new WorldContentException(ContentError.RECORD_BOX_FACE,
                    "Model figure " + figureId + " box_face needs a uv");
        }
        if (uv.u() < 0 || uv.v() < 0 || uv.w() < 1 || uv.h() < 1
                || (long) uv.u() + uv.w() > textureInfo.width() || (long) uv.v() + uv.h() > textureInfo.height()) {
            throw new WorldContentException(ContentError.RECORD_BOX_FACE,
                    "Figure " + figureId + " box_face uv lies outside its "
                    + textureInfo.width() + "x" + textureInfo.height() + " texture");
        }
    }

    /** An optional flag that is written only when true; false is the omitted default. */
    private static boolean trueOnly(JsonObject json, String key) throws WorldContentException {
        if (!json.has(key)) {
            return false;
        }
        if (!JsonFields.bool(json.get(key), key)) {
            throw new WorldContentException(ContentError.RECORD_FLAG, key + " is written only when true");
        }
        return true;
    }

    /**
     * Whether an optional flag whose default is true is there, which it is only as false: true
     * is the omitted default, so each value has one form in the record.
     */
    private static boolean falseOnly(JsonObject json, String key) throws WorldContentException {
        if (!json.has(key)) {
            return false;
        }
        if (JsonFields.bool(json.get(key), key)) {
            throw new WorldContentException(ContentError.RECORD_FLAG, key + " is written only when false");
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
            throw new WorldContentException(ContentError.BUDGET_COLLECTIONS,
                    "Budget collections: more than " + WorldContentLimits.MAX_COLLECTIONS);
        }
        Set<String> ids = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        for (WorldCollectionRecord record : records) {
            if (!ids.add(record.id())) {
                throw new WorldContentException(ContentError.BUDGET_COLLECTIONS,
                        "Collection " + record.id() + " is defined twice");
            }
            long bytes = 0;
            for (String sha : record.referencedShas()) {
                bytes += blob(blobs, sha, "collection " + record.id() + " file").size();
            }
            if (bytes > WorldContentLimits.MAX_COLLECTION_BYTES) {
                throw new WorldContentException(ContentError.BUDGET_BYTES,
                        "Budget collection bytes: " + record.id() + " uses "
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
            throw new WorldContentException(ContentError.BUDGET_PIXELS,
                    "Budget texture pixels: " + pixels + " of " + WorldContentLimits.MAX_DECODED_PIXELS);
        }
        if (cubes > WorldContentLimits.MAX_DECODED_CUBES) {
            throw new WorldContentException(ContentError.BUDGET_CUBES,
                    "Budget model cubes: " + cubes + " of " + WorldContentLimits.MAX_DECODED_CUBES);
        }
        if (keyframes > WorldContentLimits.MAX_DECODED_KEYFRAMES) {
            throw new WorldContentException(ContentError.BUDGET_KEYFRAMES,
                    "Budget animation keyframes: " + keyframes + " of "
                    + WorldContentLimits.MAX_DECODED_KEYFRAMES);
        }
    }

    /** Whether the name is a box colour, each of which has a bundled plain box texture. */
    public static boolean isBoxColor(String name) {
        return BoxColors.NAMES.contains(name);
    }

    /**
     * A name or author: at most max characters, at least min, not blank when required, and
     * without control, format or unpaired surrogate characters. A name a player publishes is held
     * to {@link PlayerText} instead, which lists the characters it may have.
     */
    private static String text(JsonElement element, String what, int min, int max, boolean player)
            throws WorldContentException {
        String text = JsonFields.string(element, what, max);
        if (player) {
            ContentError problem = PlayerText.problem(text, min, max);
            if (problem != null) {
                throw new WorldContentException(problem, what + switch (problem) {
                    case TEXT_EMPTY -> " must not be empty";
                    case TEXT_SPACES -> " has a space at an end or two in a row";
                    default -> " has a character a published name cannot have";
                });
            }
            return text;
        }
        if (text.length() < min || (min > 0 && CoreText.isBlank(text))) {
            throw new WorldContentException(ContentError.TEXT_EMPTY, what + " must not be empty");
        }
        if (!isPlainText(text)) {
            throw new WorldContentException(ContentError.TEXT_CHARACTERS,
                    what + " has a control or formatting character");
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
            throw new WorldContentException(ContentError.FILE_MISSING, what + " names a file that is not stored");
        }
        return info;
    }

    private static JsonElement nullable(@Nullable String value) {
        return value != null ? new JsonPrimitive(value) : JsonNull.INSTANCE;
    }
}
