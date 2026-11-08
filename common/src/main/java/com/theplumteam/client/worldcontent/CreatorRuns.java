package com.theplumteam.client.worldcontent;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Every creator run on its way to the server, by the World collection it changes, until the
 * server has synced what it did: a save of a draft's changes ({@link AutoSaver}), a bulk add of
 * figures, a create, a publish of a published collection's changes, and the home's actions on a
 * whole collection (a duplicate of it, archiving, restoring, deleting and importing over it).
 *
 * <p>A run counts from when it is handed over until it ends and then, when it changed something on
 * the server, until the next sync arrives or {@link #SYNC_WAIT_MS} have passed. The server sends
 * the status of an edit before the sync it causes, on the same connection, so the first sync after
 * an accepted run already holds it. While any run of a collection counts ({@link #busy}), nothing
 * else plans against that collection's server state, which the run is about to change: a plan made
 * then would send a removal, a publish or a reorder again from a stale base.
 *
 * <p>A create counts under the id its slug gives, and moves with it when the slug is retried.
 * {@link #reset()} forgets every run when the client leaves or joins a server. Client thread only.
 */
public final class CreatorRuns {
    /** How long an accepted run waits for the sync that shows it before it stops counting. */
    public static final long SYNC_WAIT_MS = 10_000;

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private CreatorRuns() {
    }

    /** One run: in flight until {@link #finish}, then until its sync or {@link #SYNC_WAIT_MS}. */
    public static final class Entry {
        private String collectionId;
        @Nullable
        private final Object owner;
        private boolean finished;
        private int revisionAtFinish;
        private long finishedAt;

        private Entry(String collectionId, @Nullable Object owner) {
            this.collectionId = collectionId;
            this.owner = owner;
        }

        public String collectionId() {
            return this.collectionId;
        }

        /** Whether the run itself is still on its way, rather than waiting for its sync. */
        public boolean inFlight() {
            return !this.finished;
        }

        // Whether it still counts: in flight, or accepted and its sync not in yet
        private boolean counts(long now) {
            return !this.finished || ClientWorldCollections.revision() == this.revisionAtFinish
                    && now - this.finishedAt < SYNC_WAIT_MS;
        }
    }

    /**
     * Counts a run of a collection from now.
     *
     * @param owner what the run belongs to, such as a save, which {@link #busyOther} can leave out
     */
    public static Entry begin(String collectionId, @Nullable Object owner) {
        Entry entry = new Entry(collectionId, owner);
        ENTRIES.add(entry);
        return entry;
    }

    /** Moves a run to another collection id, as a create retried under another slug does. */
    public static void move(Entry entry, String collectionId) {
        entry.collectionId = collectionId;
    }

    /**
     * Ends a run. One that changed something on the server keeps counting until the next sync,
     * which shows the change; one that changed nothing stops counting at once.
     */
    public static void finish(Entry entry, boolean changedServer) {
        if (entry.finished) {
            return;
        }
        entry.finished = true;
        entry.revisionAtFinish = ClientWorldCollections.revision();
        entry.finishedAt = ClientWorldUploads.millis();
        if (!changedServer) {
            ENTRIES.remove(entry);
        }
    }

    /** Stops counting a run at once, whatever it did, as when its collection is gone. */
    public static void forget(Entry entry) {
        ENTRIES.remove(entry);
    }

    /** Whether any run of the collection is on its way or waits for the sync that shows it. */
    public static boolean busy(String collectionId) {
        return busyOther(collectionId, null);
    }

    /** As {@link #busy}, leaving out the runs that belong to an owner. */
    public static boolean busyOther(String collectionId, @Nullable Object owner) {
        long now = ClientWorldUploads.millis();
        ENTRIES.removeIf(entry -> !entry.counts(now));
        for (Entry entry : ENTRIES) {
            if (entry.collectionId.equals(collectionId) && (owner == null || entry.owner != owner)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a run of the collection that belongs to an owner is itself still on its way. */
    public static boolean inFlight(String collectionId, Object owner) {
        for (Entry entry : ENTRIES) {
            if (entry.collectionId.equals(collectionId) && entry.owner == owner && entry.inFlight()) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every run, when the client leaves or joins a server. */
    public static void reset() {
        ENTRIES.clear();
    }
}
