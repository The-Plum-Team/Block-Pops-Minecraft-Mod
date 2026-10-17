package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * What validation learned about one stored file: its type and size, and what it costs a
 * client once decoded. Fields that do not apply to the type are 0.
 *
 * This is the blob table entry both sides check records against; the store adds who
 * added the file and when, and the wire carries exactly these fields.
 *
 * @param width     a PNG's width in pixels
 * @param height    a PNG's height in pixels
 * @param texWidth  the texture width a model declares
 * @param texHeight the texture height a model declares
 * @param cubes     a model's cube count
 * @param keyframes an animation's keyframe count
 * @param poses     which of {@link #POSE_STAND} and {@link #POSE_SIT} an animation has
 * @param bounds    a model's rest-pose bounds, {@link ModelBounds#NONE} for other files. They are
 *                  derived from the file: a side that holds the file computes them again and
 *                  never checks them against a stored or received entry (see {@link #sameFile})
 */
public record BlobInfo(BlobType type, int size, int width, int height, int texWidth, int texHeight,
                       int cubes, int keyframes, int poses, ModelBounds bounds) {
    public static final String POSE_STAND_NAME = "Pose_Stand";
    public static final String POSE_SIT_NAME = "Pose_Sit";
    public static final int POSE_STAND = 1;
    public static final int POSE_SIT = 2;
    private static final Set<String> PNG_KEYS = Set.of("type", "size", "width", "height");
    private static final Set<String> GEO_KEYS = Set.of("type", "size", "cubes", "tex_w", "tex_h");
    private static final Set<String> GEO_OPTIONAL_KEYS = Set.of("bounds");
    private static final Set<String> ANIMATION_KEYS = Set.of("type", "size", "keyframes", "poses");

    public static BlobInfo png(int size, int width, int height) {
        return new BlobInfo(BlobType.PNG, size, width, height, 0, 0, 0, 0, 0, ModelBounds.NONE);
    }

    public static BlobInfo geo(int size, int cubes, int texWidth, int texHeight, ModelBounds bounds) {
        return new BlobInfo(BlobType.GEO, size, 0, 0, texWidth, texHeight, cubes, 0, 0, bounds);
    }

    public static BlobInfo animation(int size, int keyframes, int poses) {
        return new BlobInfo(BlobType.ANIMATION, size, 0, 0, 0, 0, 0, keyframes, poses, ModelBounds.NONE);
    }

    /**
     * Whether this entry and another describe the same file. Bounds are left out: they are
     * derived data, so a checked file's entry adopts the bounds computed from it instead.
     */
    public boolean sameFile(BlobInfo other) {
        return equals(other.withBounds(bounds));
    }

    /** This entry with other bounds. */
    public BlobInfo withBounds(ModelBounds next) {
        return new BlobInfo(type, size, width, height, texWidth, texHeight, cubes, keyframes, poses, next);
    }

    public boolean hasPose(int pose) {
        return (poses & pose) != 0;
    }

    /** A PNG's decoded pixel count. */
    public long pixels() {
        return (long) width * height;
    }

    /**
     * Rebuilds an entry that arrived from the wire or the store, refusing any value a
     * validated file could not have produced.
     */
    public static BlobInfo checked(BlobType type, long size, long width, long height, long texWidth, long texHeight,
                                   long cubes, long keyframes, long poses, ModelBounds bounds)
            throws WorldContentException {
        if (type == null || bounds == null) {
            throw new WorldContentException("Unknown file type");
        }
        if (size < 1 || size > type.maxBytes()) {
            throw new WorldContentException("File size " + size + " is outside 1.." + type.maxBytes());
        }
        boolean ok = switch (type) {
            case PNG -> within(width, WorldContentLimits.PNG_MIN_SIZE, WorldContentLimits.PNG_MAX_SIZE)
                    && within(height, WorldContentLimits.PNG_MIN_SIZE, WorldContentLimits.PNG_MAX_SIZE)
                    && texWidth == 0 && texHeight == 0 && cubes == 0 && keyframes == 0 && poses == 0
                    && bounds.equals(ModelBounds.NONE);
            case GEO -> width == 0 && height == 0
                    && within(texWidth, 1, WorldContentLimits.GEO_MAX_TEXTURE_SIZE)
                    && within(texHeight, 1, WorldContentLimits.GEO_MAX_TEXTURE_SIZE)
                    && within(cubes, 0, WorldContentLimits.GEO_MAX_CUBES) && keyframes == 0 && poses == 0
                    && (cubes > 0 || bounds.equals(ModelBounds.NONE));
            case ANIMATION -> width == 0 && height == 0 && texWidth == 0 && texHeight == 0 && cubes == 0
                    && within(keyframes, 0, WorldContentLimits.ANIMATION_MAX_KEYFRAMES)
                    && (poses & POSE_STAND) != 0 && (poses & ~(POSE_STAND | POSE_SIT)) == 0
                    && bounds.equals(ModelBounds.NONE);
        };
        if (!ok) {
            throw new WorldContentException("File details do not fit a " + type.storeName() + " file");
        }
        return new BlobInfo(type, (int) size, (int) width, (int) height, (int) texWidth, (int) texHeight,
                (int) cubes, (int) keyframes, (int) poses, bounds);
    }

    private static boolean within(long value, long min, long max) {
        return value >= min && value <= max;
    }

    /** Writes this entry's keys of the store schema; the store adds its own. */
    public void writeJson(JsonObject json) {
        json.addProperty("type", type.storeName());
        json.addProperty("size", size);
        switch (type) {
            case PNG -> {
                json.addProperty("width", width);
                json.addProperty("height", height);
            }
            case GEO -> {
                json.addProperty("cubes", cubes);
                json.addProperty("tex_w", texWidth);
                json.addProperty("tex_h", texHeight);
                json.add("bounds", bounds.toJson());
            }
            case ANIMATION -> {
                json.addProperty("keyframes", keyframes);
                JsonArray names = new JsonArray();
                if (hasPose(POSE_STAND)) {
                    names.add(POSE_STAND_NAME);
                }
                if (hasPose(POSE_SIT)) {
                    names.add(POSE_SIT_NAME);
                }
                json.add("poses", names);
            }
        }
    }

    /**
     * Reads an entry of the store schema. The object must have exactly this type's keys
     * plus storeKeys, which the caller reads itself. A model's bounds may be left out, as
     * they are derived data: the entry then has {@link ModelBounds#NONE} until the stored
     * file is checked and its bounds are computed from it.
     */
    public static BlobInfo readJson(JsonObject json, Set<String> storeKeys) throws WorldContentException {
        BlobType type = BlobType.byStoreName(JsonFields.string(json.get("type"), "type", 16));
        if (type == null) {
            throw new WorldContentException("Unknown file type");
        }
        JsonFields.keys(json, "file entry", union(switch (type) {
            case PNG -> PNG_KEYS;
            case GEO -> GEO_KEYS;
            case ANIMATION -> ANIMATION_KEYS;
        }, storeKeys), type == BlobType.GEO ? GEO_OPTIONAL_KEYS : Set.of());
        long size = JsonFields.integer(json.get("size"), "size", 1, type.maxBytes());
        return switch (type) {
            case PNG -> checked(type, size,
                    JsonFields.integer(json.get("width"), "width", 0, Integer.MAX_VALUE),
                    JsonFields.integer(json.get("height"), "height", 0, Integer.MAX_VALUE), 0, 0, 0, 0, 0,
                    ModelBounds.NONE);
            case GEO -> checked(type, size, 0, 0,
                    JsonFields.integer(json.get("tex_w"), "tex_w", 0, Integer.MAX_VALUE),
                    JsonFields.integer(json.get("tex_h"), "tex_h", 0, Integer.MAX_VALUE),
                    JsonFields.integer(json.get("cubes"), "cubes", 0, Integer.MAX_VALUE), 0, 0,
                    json.has("bounds") ? ModelBounds.fromJson(json.get("bounds")) : ModelBounds.NONE);
            case ANIMATION -> checked(type, size, 0, 0, 0, 0, 0,
                    JsonFields.integer(json.get("keyframes"), "keyframes", 0, Integer.MAX_VALUE),
                    readPoses(JsonFields.array(json.get("poses"), "poses")), ModelBounds.NONE);
        };
    }

    private static int readPoses(JsonArray names) throws WorldContentException {
        int poses = 0;
        for (JsonElement element : names) {
            String name = JsonFields.string(element, "pose", 16);
            int pose = POSE_STAND_NAME.equals(name) ? POSE_STAND : POSE_SIT_NAME.equals(name) ? POSE_SIT : 0;
            if (pose == 0 || (poses & pose) != 0) {
                throw new WorldContentException("poses must list Pose_Stand and Pose_Sit at most once each");
            }
            poses |= pose;
        }
        return poses;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
