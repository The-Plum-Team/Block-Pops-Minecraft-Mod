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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * @param boxTexture the sha of the box texture, or null
 * @param boxColor   the {@link PopBlockColor} name of a plain box, or null; never set
 *                   together with boxTexture
 * @param logo       the sha of the logo, or null
 * @param revision   counts the edits, so a changed record is always told apart
 * @param figures    every figure ever added, retired ones included
 */
public record WorldCollectionRecord(String id, String name, String author, int red, int green, int blue,
                                    @Nullable String boxTexture, @Nullable String boxColor, @Nullable String logo,
                                    boolean archived, int revision, List<Figure> figures) {
    /** The figure sizes an operator can choose from. */
    public static final List<Float> SCALES = List.of(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f);
    private static final Set<String> KEYS = Set.of("id", "name", "author", "background_color", "box", "logo",
            "archived", "revision", "figures");
    private static final Set<String> FIGURE_KEYS = Set.of("id", "name", "kind", "texture", "model", "animation",
            "scale", "enabled");
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
     * One figure.
     *
     * @param texture   the sha of its skin or model texture
     * @param model     the sha of its model; null for a skin
     * @param animation the sha of its poses, or null for the shared poses; always null for a skin
     * @param enabled   false once retired: still defined, so existing boxes resolve, but never handed out
     */
    public record Figure(String id, String name, Kind kind, String texture, @Nullable String model,
                         @Nullable String animation, float scale, boolean enabled) {
    }

    public WorldCollectionRecord {
        if (boxTexture != null && boxColor != null) {
            throw new IllegalArgumentException("A box has either a texture or a color");
        }
        figures = List.copyOf(figures);
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
            array.add(entry);
        }
        json.add("figures", array);
        return json;
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
        JsonFields.keys(json, "collection", KEYS, Set.of());
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
        for (JsonElement element : array) {
            Figure figure = figure(JsonFields.object(element, "figure"), blobs);
            if (!figureIds.add(figure.id())) {
                throw new WorldContentException("Figure " + figure.id() + " is listed twice");
            }
            figures.add(figure);
        }
        WorldCollectionRecord record = new WorldCollectionRecord(id, name, author, red, green, blue,
                boxTexture, boxColor, logo, archived, revision, figures);
        if (record.toUtf8().length > WorldContentLimits.RECORD_BYTES) {
            throw new WorldContentException("Collection record is larger than " + WorldContentLimits.RECORD_BYTES + " bytes");
        }
        return record;
    }

    private static Figure figure(JsonObject json, Map<String, BlobInfo> blobs) throws WorldContentException {
        JsonFields.keys(json, "figure", FIGURE_KEYS, Set.of());
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
        if (kind == Kind.SKIN) {
            if (model != null || animation != null) {
                throw new WorldContentException("Skin figure " + id + " cannot have a model or animation");
            }
            PngValidator.Role.SKIN.check(textureInfo);
        } else {
            if (model == null) {
                throw new WorldContentException("Model figure " + id + " has no model");
            }
            BlobInfo modelInfo = blob(blobs, model, "figure " + id + " model");
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
        return new Figure(id, name, kind, texture, model, animation, SCALES.get(scaleIndex), enabled);
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
