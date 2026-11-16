package com.theplumteam.remote.player;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;

import java.util.HashMap;
import java.util.Map;

/**
 * How much of player collections one game holds at a time, across all of them. Each collection
 * is bounded by itself when its record is read; this bounds what a server can make a client
 * load by showing many: the pixels, cubes and keyframes their files decode to, and their bytes.
 * A collection that does not fit in what is left is left out whole.
 */
public final class PlayerBudgets {
    /** The pixels every loaded image may have together: 128 MiB as RGBA. */
    public static final long PIXELS = 33_554_432L;
    public static final long CUBES = 65_536L;
    public static final long KEYFRAMES = 262_144L;
    public static final long BYTES = 128L * 1024 * 1024;

    private record Use(long pixels, long cubes, long keyframes, long bytes) {
        Use plus(Use other) {
            return new Use(pixels + other.pixels, cubes + other.cubes, keyframes + other.keyframes,
                    bytes + other.bytes);
        }

        boolean fits() {
            return pixels <= PIXELS && cubes <= CUBES && keyframes <= KEYFRAMES && bytes <= BYTES;
        }
    }

    private final Map<String, Use> admitted = new HashMap<>();

    /** What the files of one collection come to. */
    private static Use of(Map<String, BlobInfo> blobs) {
        long pixels = 0;
        long cubes = 0;
        long keyframes = 0;
        long bytes = 0;
        for (BlobInfo blob : blobs.values()) {
            bytes += blob.size();
            if (blob.type() == BlobType.PNG) {
                pixels += (long) blob.width() * blob.height();
            }
            cubes += blob.cubes();
            keyframes += blob.keyframes();
        }
        return new Use(pixels, cubes, keyframes, bytes);
    }

    /**
     * Counts a collection in, if it fits with the others; one that is counted in already is
     * counted by its new files instead.
     *
     * @return false when it does not fit, in which case it is not counted
     */
    public synchronized boolean admit(String id, Map<String, BlobInfo> blobs) {
        Use total = of(blobs);
        for (Map.Entry<String, Use> entry : admitted.entrySet()) {
            if (!entry.getKey().equals(id)) {
                total = total.plus(entry.getValue());
            }
        }
        if (!total.fits()) {
            return false;
        }
        admitted.put(id, of(blobs));
        return true;
    }

    /** Stops counting a collection, as when it is no longer loaded. */
    public synchronized void release(String id) {
        admitted.remove(id);
    }

    public synchronized void clear() {
        admitted.clear();
    }
}
