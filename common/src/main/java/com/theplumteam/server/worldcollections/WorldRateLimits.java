package com.theplumteam.server.worldcollections;

import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentLimits;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How often one player may edit World collections, begin uploads and import datapacks.
 * Each edit saves the store on the server thread, so the limits also bound that work.
 * Commands are not limited: only operators at a console or in chat run them.
 *
 * <p>A batch saves once, but it can carry many edits, so it counts as one edit for every
 * {@link #EDITS_PER_COST} edits it holds, rounded up. Uploads are limited by count and by
 * bytes ({@link WorldContentLimits#UPLOAD_BEGINS_PER_MINUTE},
 * {@link WorldContentLimits#UPLOAD_BYTES_PER_MINUTE}), counted only for uploads the server
 * accepts. An import reads and writes up to a whole collection on the server thread, so it
 * has a limit of its own ({@link WorldContentLimits#IMPORTS_PER_MINUTE}) instead of an edit's.
 *
 * Server thread only.
 */
public final class WorldRateLimits {
    public static final int EDITS_PER_SECOND = 5;
    public static final int EDITS_PER_MINUTE = 60;
    /** How many edits of a batch count as one. */
    public static final int EDITS_PER_COST = 16;
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;

    private static final Map<UUID, Deque<Long>> EDITS = new HashMap<>();
    private static final Map<UUID, Deque<Long>> UPLOAD_BEGINS = new HashMap<>();
    /** The size of each upload in {@link #UPLOAD_BEGINS}, in the same order. */
    private static final Map<UUID, Deque<Long>> UPLOAD_SIZES = new HashMap<>();
    private static final Map<UUID, Deque<Long>> IMPORTS = new HashMap<>();

    private WorldRateLimits() {
    }

    /** Counts an edit, or answers false when the player has used up the edits it may send now. */
    public static boolean tryEdit(UUID player) {
        return tryEdit(player, 1);
    }

    /**
     * Counts an edit that costs as much as the given number of single edits, or answers
     * false, counting nothing, when the player cannot afford it now.
     */
    public static boolean tryEdit(UUID player, int cost) {
        long now = System.nanoTime();
        Deque<Long> times = recent(EDITS, player, now);
        if (times.size() + cost > EDITS_PER_MINUTE || countSince(times, now - SECOND) + cost > EDITS_PER_SECOND) {
            return false;
        }
        for (int i = 0; i < cost; i++) {
            times.addLast(now);
        }
        return true;
    }

    /** What an edit costs: 1, or for a batch one per {@link #EDITS_PER_COST} edits it holds, rounded up. */
    public static int editCost(WorldCollectionEdit edit) {
        if (edit instanceof WorldCollectionEdit.Batch batch) {
            return Math.max(1, (batch.edits().size() + EDITS_PER_COST - 1) / EDITS_PER_COST);
        }
        return 1;
    }

    /**
     * Counts the beginning of an upload of the given size, or answers false, counting
     * nothing, when the player has begun too many uploads or too many bytes in the last
     * minute.
     */
    public static boolean tryUploadBegin(UUID player, long size) {
        long now = System.nanoTime();
        Deque<Long> times = UPLOAD_BEGINS.computeIfAbsent(player, key -> new ArrayDeque<>());
        Deque<Long> sizes = UPLOAD_SIZES.computeIfAbsent(player, key -> new ArrayDeque<>());
        // The two queues grow and shrink together, so a begin and its size leave at once
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) {
            times.removeFirst();
            sizes.removeFirst();
        }
        long bytes = 0;
        for (long recent : sizes) {
            bytes += recent;
        }
        if (times.size() >= WorldContentLimits.UPLOAD_BEGINS_PER_MINUTE
                || bytes + size > WorldContentLimits.UPLOAD_BYTES_PER_MINUTE) {
            return false;
        }
        times.addLast(now);
        sizes.addLast(size);
        return true;
    }

    /** Counts a datapack import, or answers false when the player has started too many. */
    public static boolean tryImport(UUID player) {
        long now = System.nanoTime();
        Deque<Long> times = recent(IMPORTS, player, now);
        if (times.size() >= WorldContentLimits.IMPORTS_PER_MINUTE) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    /** Forgets a player that left. */
    public static void forget(UUID player) {
        EDITS.remove(player);
        UPLOAD_BEGINS.remove(player);
        UPLOAD_SIZES.remove(player);
        IMPORTS.remove(player);
    }

    public static void clear() {
        EDITS.clear();
        UPLOAD_BEGINS.clear();
        UPLOAD_SIZES.clear();
        IMPORTS.clear();
    }

    /** The player's times within the last minute, oldest first. */
    private static Deque<Long> recent(Map<UUID, Deque<Long>> all, UUID player, long now) {
        Deque<Long> times = all.computeIfAbsent(player, key -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) {
            times.removeFirst();
        }
        return times;
    }

    private static int countSince(Deque<Long> times, long since) {
        int count = 0;
        for (var iterator = times.descendingIterator(); iterator.hasNext(); ) {
            if (iterator.next() - since <= 0) {
                break;
            }
            count++;
        }
        return count;
    }
}
