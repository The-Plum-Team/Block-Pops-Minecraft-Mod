package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an uploaded `.geo.json` model against an allowlisted schema before any GeckoLib
 * version sees it: exact keys, bounded geometry, finite numbers, and no meshes.
 *
 * Positions are bounded to 64 pixels, which covers every bundled model and, with the
 * largest figure scale, keeps a model within a few blocks of its box.
 */
public final class GeoJsonCheck {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final double MAX_POSITION = 64;
    private static final double MAX_ROTATION = 360;
    private static final double MAX_INFLATE = 4;
    private static final double MAX_BOUNDS = 16;
    private static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");
    /** An affine transform as a row-major 3x3 matrix followed by its translation. */
    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};

    /** What the blob table records about a valid model. */
    public record Geometry(int cubes, int textureWidth, int textureHeight, ModelBounds bounds) {
    }

    private GeoJsonCheck() {
    }

    /** Parses and checks a model file. */
    public static BlobInfo validate(byte[] bytes) throws WorldContentException {
        if (bytes.length > WorldContentLimits.GEO_BYTES) {
            throw new WorldContentException("Model is larger than " + WorldContentLimits.GEO_BYTES / 1024 + " KiB");
        }
        Geometry geometry = check(JsonFields.parse(bytes, WorldContentLimits.GEO_BYTES));
        return BlobInfo.geo(bytes.length, geometry.cubes(), geometry.textureWidth(), geometry.textureHeight(),
                geometry.bounds());
    }

    /** Checks a parsed model, throwing the first problem found. */
    public static Geometry check(JsonObject json) throws WorldContentException {
        JsonFields.keys(json, "model", Set.of("format_version", "minecraft:geometry"), Set.of());
        JsonFields.string(json.get("format_version"), "format_version", 16);
        JsonArray geometries = JsonFields.array(json.get("minecraft:geometry"), "minecraft:geometry");
        if (geometries.size() != 1) {
            throw new WorldContentException("Model must hold exactly one geometry");
        }
        JsonObject geometry = JsonFields.object(geometries.get(0), "geometry");
        JsonFields.keys(geometry, "geometry", Set.of("description", "bones"), Set.of());

        JsonObject description = JsonFields.object(geometry.get("description"), "description");
        JsonFields.keys(description, "description", Set.of("identifier", "texture_width", "texture_height"),
                Set.of("visible_bounds_width", "visible_bounds_height", "visible_bounds_offset"));
        JsonFields.string(description.get("identifier"), "identifier", 64);
        int textureWidth = (int) JsonFields.integer(description.get("texture_width"), "texture_width",
                1, WorldContentLimits.GEO_MAX_TEXTURE_SIZE);
        int textureHeight = (int) JsonFields.integer(description.get("texture_height"), "texture_height",
                1, WorldContentLimits.GEO_MAX_TEXTURE_SIZE);
        if (description.has("visible_bounds_width")) {
            JsonFields.number(description.get("visible_bounds_width"), "visible_bounds_width", 0, MAX_BOUNDS);
        }
        if (description.has("visible_bounds_height")) {
            JsonFields.number(description.get("visible_bounds_height"), "visible_bounds_height", 0, MAX_BOUNDS);
        }
        if (description.has("visible_bounds_offset")) {
            JsonFields.numbers(description.get("visible_bounds_offset"), "visible_bounds_offset", 3,
                    -MAX_BOUNDS, MAX_BOUNDS);
        }

        JsonArray bones = JsonFields.array(geometry.get("bones"), "bones");
        if (bones.isEmpty() || bones.size() > WorldContentLimits.GEO_MAX_BONES) {
            throw new WorldContentException("Model must have 1 to " + WorldContentLimits.GEO_MAX_BONES + " bones");
        }
        Map<String, String> parents = new HashMap<>();
        Map<String, JsonObject> boneObjects = new LinkedHashMap<>();
        int cubes = 0;
        int locators = 0;
        for (JsonElement element : bones) {
            JsonObject bone = JsonFields.object(element, "bone");
            String name = boneName(bone.get("name"));
            if (parents.containsKey(name)) {
                throw new WorldContentException("Bone " + name + " is defined twice");
            }
            if (bone.has("poly_mesh") || bone.has("texture_meshes")) {
                throw new WorldContentException("Bone " + name + " uses a mesh, which is not supported");
            }
            JsonFields.keys(bone, "bone " + name, Set.of("name", "pivot"),
                    Set.of("parent", "rotation", "mirror", "inflate", "cubes", "locators"));
            parents.put(name, bone.has("parent") ? boneName(bone.get("parent")) : null);
            boneObjects.put(name, bone);
            JsonFields.numbers(bone.get("pivot"), "bone pivot", 3, -MAX_POSITION, MAX_POSITION);
            if (bone.has("rotation")) {
                JsonFields.numbers(bone.get("rotation"), "bone rotation", 3, -MAX_ROTATION, MAX_ROTATION);
            }
            if (bone.has("mirror")) {
                JsonFields.bool(bone.get("mirror"), "bone mirror");
            }
            if (bone.has("inflate")) {
                JsonFields.number(bone.get("inflate"), "bone inflate", -MAX_INFLATE, MAX_INFLATE);
            }
            if (bone.has("locators")) {
                JsonObject boneLocators = JsonFields.object(bone.get("locators"), "locators");
                locators += boneLocators.size();
                if (locators > WorldContentLimits.GEO_MAX_LOCATORS) {
                    throw new WorldContentException("Model has more than " + WorldContentLimits.GEO_MAX_LOCATORS
                            + " locators");
                }
                for (Map.Entry<String, JsonElement> locator : boneLocators.entrySet()) {
                    checkName(locator.getKey());
                    checkLocator(locator.getValue());
                }
            }
            if (bone.has("cubes")) {
                JsonArray boneCubes = JsonFields.array(bone.get("cubes"), "cubes");
                cubes += boneCubes.size();
                if (cubes > WorldContentLimits.GEO_MAX_CUBES) {
                    throw new WorldContentException("Model has more than " + WorldContentLimits.GEO_MAX_CUBES + " cubes");
                }
                for (JsonElement cube : boneCubes) {
                    checkCube(JsonFields.object(cube, "cube"), textureWidth, textureHeight);
                }
            }
        }
        for (Map.Entry<String, String> bone : parents.entrySet()) {
            String parent = bone.getValue();
            for (int depth = 0; parent != null; depth++) {
                if (!parents.containsKey(parent)) {
                    throw new WorldContentException("Bone " + bone.getKey() + " names a missing parent");
                }
                if (depth >= parents.size()) {
                    throw new WorldContentException("Bone " + bone.getKey() + " is its own ancestor");
                }
                parent = parents.get(parent);
            }
        }
        return new Geometry(cubes, textureWidth, textureHeight, bounds(boneObjects, parents));
    }

    /**
     * The rest-pose bounds of a checked model as GeckoLib draws it: x mirrored, every cube
     * inflated and turned about its pivot, then by each bone up to the root. Rotations
     * apply about z, then y, then x, with x and y negated, as GeckoLib's baking does.
     */
    private static ModelBounds bounds(Map<String, JsonObject> bones, Map<String, String> parents)
            throws WorldContentException {
        Map<String, double[]> placed = new HashMap<>();
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Map.Entry<String, JsonObject> entry : bones.entrySet()) {
            JsonObject bone = entry.getValue();
            if (!bone.has("cubes")) {
                continue;
            }
            double[] boneTransform = placement(entry.getKey(), bones, parents, placed);
            double boneInflate = bone.has("inflate") ? JsonFields.number(bone.get("inflate"), "bone inflate",
                    -MAX_INFLATE, MAX_INFLATE) : 0;
            for (JsonElement element : JsonFields.array(bone.get("cubes"), "cubes")) {
                JsonObject cube = JsonFields.object(element, "cube");
                double[] origin = JsonFields.numbers(cube.get("origin"), "cube origin", 3, -MAX_POSITION, MAX_POSITION);
                double[] size = JsonFields.numbers(cube.get("size"), "cube size", 3, 0, MAX_POSITION);
                double inflate = cube.has("inflate") ? JsonFields.number(cube.get("inflate"), "cube inflate",
                        -MAX_INFLATE, MAX_INFLATE) : boneInflate;
                double[] low = {-(origin[0] + size[0]) - inflate, origin[1] - inflate, origin[2] - inflate};
                double[] high = {-origin[0] + inflate, origin[1] + size[1] + inflate, origin[2] + size[2] + inflate};
                double[] transform = boneTransform;
                if (cube.has("rotation")) {
                    double[] pivot = cube.has("pivot")
                            ? JsonFields.numbers(cube.get("pivot"), "cube pivot", 3, -MAX_POSITION, MAX_POSITION)
                            : new double[3];
                    transform = compose(boneTransform, turn(pivot, JsonFields.numbers(cube.get("rotation"),
                            "cube rotation", 3, -MAX_ROTATION, MAX_ROTATION)));
                }
                for (int corner = 0; corner < 8; corner++) {
                    double[] point = apply(transform, (corner & 1) == 0 ? low[0] : high[0],
                            (corner & 2) == 0 ? low[1] : high[1], (corner & 4) == 0 ? low[2] : high[2]);
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], point[axis]);
                        max[axis] = Math.max(max[axis], point[axis]);
                    }
                }
            }
        }
        return min[0] > max[0] ? ModelBounds.NONE : ModelBounds.of(min, max);
    }

    /** Where a bone's rotations, and its ancestors', carry a point; parents were checked to end. */
    private static double[] placement(String name, Map<String, JsonObject> bones, Map<String, String> parents,
                                      Map<String, double[]> placed) throws WorldContentException {
        double[] known = placed.get(name);
        if (known != null) {
            return known;
        }
        String parent = parents.get(name);
        double[] transform = parent != null ? placement(parent, bones, parents, placed) : IDENTITY;
        JsonObject bone = bones.get(name);
        if (bone.has("rotation")) {
            transform = compose(transform, turn(
                    JsonFields.numbers(bone.get("pivot"), "bone pivot", 3, -MAX_POSITION, MAX_POSITION),
                    JsonFields.numbers(bone.get("rotation"), "bone rotation", 3, -MAX_ROTATION, MAX_ROTATION)));
        }
        placed.put(name, transform);
        return transform;
    }

    /** A turn by a file's rotation in degrees about a file's pivot, in GeckoLib's drawn space. */
    private static double[] turn(double[] pivot, double[] degrees) {
        double x = Math.toRadians(-degrees[0]);
        double y = Math.toRadians(-degrees[1]);
        double z = Math.toRadians(degrees[2]);
        double cx = StrictMath.cos(x), sx = StrictMath.sin(x);
        double cy = StrictMath.cos(y), sy = StrictMath.sin(y);
        double cz = StrictMath.cos(z), sz = StrictMath.sin(z);
        // Rz * Ry * Rx
        double[] m = {
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                -sy, cy * sx, cy * cx};
        double[] p = {-pivot[0], pivot[1], pivot[2]};
        double[] turned = {m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], 0, 0, 0};
        double[] moved = apply(turned, p[0], p[1], p[2]);
        turned[9] = p[0] - moved[0];
        turned[10] = p[1] - moved[1];
        turned[11] = p[2] - moved[2];
        return turned;
    }

    /** outer after inner. */
    private static double[] compose(double[] outer, double[] inner) {
        double[] result = new double[12];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row * 3 + column] = outer[row * 3] * inner[column] + outer[row * 3 + 1] * inner[3 + column]
                        + outer[row * 3 + 2] * inner[6 + column];
            }
        }
        double[] translation = apply(outer, inner[9], inner[10], inner[11]);
        result[9] = translation[0];
        result[10] = translation[1];
        result[11] = translation[2];
        return result;
    }

    private static double[] apply(double[] transform, double x, double y, double z) {
        return new double[] {
                transform[0] * x + transform[1] * y + transform[2] * z + transform[9],
                transform[3] * x + transform[4] * y + transform[5] * z + transform[10],
                transform[6] * x + transform[7] * y + transform[8] * z + transform[11]};
    }

    private static String boneName(JsonElement element) throws WorldContentException {
        return checkName(JsonFields.string(element, "bone name", 64));
    }

    private static String checkName(String name) throws WorldContentException {
        if (!NAME.matcher(name).matches()) {
            throw new WorldContentException("Bone or locator name " + WorldContentException.excerpt(name)
                    + " may use only letters, digits, _ . and -");
        }
        return name;
    }

    /** A locator is a position, or a position with a rotation as Blockbench also writes it. */
    private static void checkLocator(JsonElement locator) throws WorldContentException {
        if (locator.isJsonObject()) {
            JsonObject object = locator.getAsJsonObject();
            JsonFields.keys(object, "locator", Set.of("offset"), Set.of("rotation"));
            JsonFields.numbers(object.get("offset"), "locator offset", 3, -MAX_POSITION, MAX_POSITION);
            if (object.has("rotation")) {
                JsonFields.numbers(object.get("rotation"), "locator rotation", 3, -MAX_ROTATION, MAX_ROTATION);
            }
            return;
        }
        JsonFields.numbers(locator, "locator", 3, -MAX_POSITION, MAX_POSITION);
    }

    private static void checkCube(JsonObject cube, int textureWidth, int textureHeight) throws WorldContentException {
        JsonFields.keys(cube, "cube", Set.of("origin", "size", "uv"), Set.of("inflate", "pivot", "rotation", "mirror"));
        JsonFields.numbers(cube.get("origin"), "cube origin", 3, -MAX_POSITION, MAX_POSITION);
        JsonFields.numbers(cube.get("size"), "cube size", 3, 0, MAX_POSITION);
        if (cube.has("inflate")) {
            JsonFields.number(cube.get("inflate"), "cube inflate", -MAX_INFLATE, MAX_INFLATE);
        }
        if (cube.has("pivot")) {
            JsonFields.numbers(cube.get("pivot"), "cube pivot", 3, -MAX_POSITION, MAX_POSITION);
        }
        if (cube.has("rotation")) {
            JsonFields.numbers(cube.get("rotation"), "cube rotation", 3, -MAX_ROTATION, MAX_ROTATION);
        }
        if (cube.has("mirror")) {
            JsonFields.bool(cube.get("mirror"), "cube mirror");
        }
        JsonElement uv = cube.get("uv");
        if (uv.isJsonArray()) {
            checkUv(uv, 0, textureWidth, textureHeight);
            return;
        }
        JsonObject faces = JsonFields.object(uv, "cube uv");
        for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
            if (!FACES.contains(face.getKey())) {
                throw new WorldContentException("Cube uv has unknown face " + WorldContentException.excerpt(face.getKey()));
            }
            JsonObject faceUv = JsonFields.object(face.getValue(), "face uv");
            JsonFields.keys(faceUv, "face uv", Set.of("uv", "uv_size"), Set.of("material_instance", "uv_rotation"));
            // A face's corner may sit just off the texture when its size is zero or negative
            checkUv(faceUv.get("uv"), -1, textureWidth, textureHeight);
            double[] size = JsonFields.numbers(faceUv.get("uv_size"), "face uv_size", 2,
                    -Math.max(textureWidth, textureHeight), Math.max(textureWidth, textureHeight));
            if (Math.abs(size[0]) > textureWidth || Math.abs(size[1]) > textureHeight) {
                throw new WorldContentException("Face uv_size is larger than the texture");
            }
            if (faceUv.has("material_instance")) {
                JsonFields.string(faceUv.get("material_instance"), "material_instance", 32);
            }
            if (faceUv.has("uv_rotation")) {
                long rotation = JsonFields.integer(faceUv.get("uv_rotation"), "uv_rotation", 0, 270);
                if (rotation % 90 != 0) {
                    throw new WorldContentException("uv_rotation must be 0, 90, 180 or 270");
                }
            }
        }
    }

    /** A texture position within the texture, or within its mirror image too when lowest is -1. */
    private static void checkUv(JsonElement element, int lowest, int textureWidth, int textureHeight)
            throws WorldContentException {
        JsonArray uv = JsonFields.array(element, "uv");
        if (uv.size() != 2) {
            throw new WorldContentException("uv must have 2 numbers");
        }
        JsonFields.number(uv.get(0), "uv", (double) lowest * textureWidth, textureWidth);
        JsonFields.number(uv.get(1), "uv", (double) lowest * textureHeight, textureHeight);
    }
}
