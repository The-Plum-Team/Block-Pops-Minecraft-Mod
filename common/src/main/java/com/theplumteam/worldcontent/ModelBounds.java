package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * The box a model fills in its rest pose, as GeckoLib draws it: in sixteenths of a pixel,
 * with the file's x mirrored as GeckoLib mirrors it, after every bone and cube rotation.
 *
 * A model figure is fitted to its box from these bounds on both sides, so they travel in
 * the blob table instead of being worked out again from a file a client may not have yet.
 * Validation computes them with {@link StrictMath}, so every JVM arrives at the same values.
 */
public record ModelBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    /** The bounds of a model without cubes, and of every file that is not a model. */
    public static final ModelBounds NONE = new ModelBounds(0, 0, 0, 0, 0, 0);
    /** Sixteenths of a pixel per pixel. */
    public static final int UNIT = 16;
    /**
     * How far a bound may reach, 1024 pixels. Deeply nested rotated bones can carry a cube
     * further than the ±64 px each value is held to; such a model is clamped here. It
     * fits a short, which is how the sync packet carries it.
     */
    public static final int LIMIT = 1024 * UNIT;

    /** Bounds in pixels, widened to whole sixteenths and clamped to {@link #LIMIT}. */
    static ModelBounds of(double[] min, double[] max) {
        return new ModelBounds(floor(min[0]), floor(min[1]), floor(min[2]),
                ceil(max[0]), ceil(max[1]), ceil(max[2]));
    }

    private static int floor(double pixels) {
        return (int) Math.max(-LIMIT, Math.min(LIMIT, Math.floor(pixels * UNIT)));
    }

    private static int ceil(double pixels) {
        return (int) Math.max(-LIMIT, Math.min(LIMIT, Math.ceil(pixels * UNIT)));
    }

    /** Rebuilds bounds from the wire or the store, refusing any a validated model could not have. */
    public static ModelBounds checked(long minX, long minY, long minZ, long maxX, long maxY, long maxZ)
            throws WorldContentException {
        if (!within(minX, maxX) || !within(minY, maxY) || !within(minZ, maxZ)) {
            throw new WorldContentException("Model bounds are out of range");
        }
        return new ModelBounds((int) minX, (int) minY, (int) minZ, (int) maxX, (int) maxY, (int) maxZ);
    }

    private static boolean within(long min, long max) {
        return min >= -LIMIT && min <= max && max <= LIMIT;
    }

    /** The store's form: {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    JsonArray toJson() {
        JsonArray array = new JsonArray();
        array.add(minX);
        array.add(minY);
        array.add(minZ);
        array.add(maxX);
        array.add(maxY);
        array.add(maxZ);
        return array;
    }

    static ModelBounds fromJson(JsonElement element) throws WorldContentException {
        JsonArray array = JsonFields.array(element, "bounds");
        if (array.size() != 6) {
            throw new WorldContentException("bounds must have 6 numbers");
        }
        long[] values = new long[6];
        for (int i = 0; i < values.length; i++) {
            values[i] = JsonFields.integer(array.get(i), "bounds", -LIMIT, LIMIT);
        }
        return checked(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** Across the box (x), in pixels. */
    public double width() {
        return (double) (maxX - minX) / UNIT;
    }

    /** Floor to top (y), in pixels. */
    public double height() {
        return (double) (maxY - minY) / UNIT;
    }

    /** Front to back (z), in pixels. */
    public double depth() {
        return (double) (maxZ - minZ) / UNIT;
    }
}
