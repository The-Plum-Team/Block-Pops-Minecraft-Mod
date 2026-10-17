package com.theplumteam.server.worldcollections;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How often one player may edit World collections and begin uploads. Each edit saves the
 * store on the server thread, so the limits also bound that work. Commands are not
 * limited: only operators at a console or in chat run them.
 *
 * Server thread only.
 */
public final class WorldRateLimits {
    public static final int EDITS_PER_SECOND = 5;
    public static final int EDITS_PER_MINUTE = 60;
    public static final int UPLOAD_BEGINS_PER_MINUTE = 30;
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;

    private static final Map<UUID, Deque<Long>> EDITS = new HashMap<>();
    private static final Map<UUID, Deque<Long>> UPLOAD_BEGINS = new HashMap<>();

    private WorldRateLimits() {
    }

    /** Counts an edit, or answers false when the player has used up the edits it may send now. */
    public static boolean tryEdit(UUID player) {
        long now = System.nanoTime();
        Deque<Long> times = recent(EDITS, player, now);
        if (times.size() >= EDITS_PER_MINUTE || countSince(times, now - SECOND) >= EDITS_PER_SECOND) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    /** Counts the beginning of an upload, or answers false when the player has begun too many. */
    public static boolean tryUploadBegin(UUID player) {
        long now = System.nanoTime();
        Deque<Long> times = recent(UPLOAD_BEGINS, player, now);
        if (times.size() >= UPLOAD_BEGINS_PER_MINUTE) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    /** Forgets a player that left. */
    public static void forget(UUID player) {
        EDITS.remove(player);
        UPLOAD_BEGINS.remove(player);
    }

    public static void clear() {
        EDITS.clear();
        UPLOAD_BEGINS.clear();
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
