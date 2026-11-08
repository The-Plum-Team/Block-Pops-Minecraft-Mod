package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.WorldCollectionRecord;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The client's savers of World collections being edited, at most one per collection
 * ({@link AutoSaver}), ticked on every client tick whichever screen is shown, so a draft's changes
 * reach the server after its editor closes, behind a screen of the editor's own, or with no
 * creator screen open at all.
 *
 * <p>An editor attaches to its collection's saver, or makes one when there is none. Opening the
 * collection again while its saver is alive attaches to the same working copy, with its undo steps
 * forgotten, rather than to a new copy of a server state the saver is about to change. A saver no
 * editor shows ends once nothing of it waits; one whose collection is no longer synced ends at
 * once. When a saver left in the background cannot finish, because the server refused it or some
 * changes cannot be saved as they are, the reporter set with {@link #onBackgroundFailure} hears it
 * once. {@link #reset()}, run by {@link ClientWorldUploads#reset()}, ends every saver when the client
 * leaves or joins a server; what was not sent is lost then.
 *
 * <p>Every autosave of this client counts against one interval, whatever its collection
 * ({@link AutoSaver#MIN_FLUSH_INTERVAL_MS}), so one operator causes a bounded number of store
 * writes and syncs a minute.
 *
 * Client thread only.
 */
public final class CreatorAutosave {
    private static final Map<String, AutoSaver> SAVERS = new LinkedHashMap<>();
    private static long lastFlushAt = Long.MIN_VALUE / 2;
    @Nullable
    private static Consumer<AutoSaver> reporter;

    private CreatorAutosave() {
    }

    /**
     * The saver of a collection this client was sent, attached to an editor: the one alive,
     * whose undo steps the new editor does not take over, or a new one made from the record.
     *
     * @throws IllegalStateException when the client has no such collection
     */
    public static AutoSaver attach(String collectionId) {
        AutoSaver saver = SAVERS.get(collectionId);
        if (saver == null || saver.isCancelled()) {
            WorldCollectionRecord record = ClientWorldCollections.record(collectionId);
            if (record == null) {
                throw new IllegalStateException("No synced World collection " + collectionId);
            }
            saver = new AutoSaver(record);
            SAVERS.put(collectionId, saver);
        } else {
            saver.syncNow();
            saver.draft().clearUndo();
        }
        saver.attach();
        return saver;
    }

    /**
     * Attaches an editor shown again to the saver it let go of: the one in the registry still, or
     * that saver put back when nothing replaced it.
     */
    public static void reattach(AutoSaver saver) {
        AutoSaver alive = SAVERS.get(saver.collectionId());
        if (alive == null && !saver.isCancelled()) {
            SAVERS.put(saver.collectionId(), saver);
        }
        saver.attach();
    }

    /** Lets go of a saver an editor attached to; it goes on in the background until nothing waits. */
    public static void detach(AutoSaver saver) {
        saver.detach();
    }

    /** The collection's saver while one is alive, or null. */
    @Nullable
    public static AutoSaver get(String collectionId) {
        return SAVERS.get(collectionId);
    }

    /**
     * Whether the collection's changes are still being saved: its saver sends or waits for
     * something, or another of the creator's runs of it is on its way ({@link CreatorRuns}). The
     * home's actions on the collection wait for it.
     */
    public static boolean isSaving(String collectionId) {
        AutoSaver saver = SAVERS.get(collectionId);
        return CreatorRuns.busy(collectionId) || saver != null && saver.isSaving();
    }

    /** The ids of the collections whose changes are being saved now, sorted. */
    public static Set<String> savingIds() {
        Set<String> ids = new TreeSet<>();
        for (AutoSaver saver : SAVERS.values()) {
            if (saver.isSaving()) {
                ids.add(saver.collectionId());
            }
        }
        return ids;
    }

    /**
     * Sets what hears of a saver left in the background that cannot finish, so it can offer to
     * open the collection again; null for nothing.
     */
    public static void onBackgroundFailure(@Nullable Consumer<AutoSaver> next) {
        reporter = next;
    }

    /** Ticks every saver, ends those nothing waits on, and reports those that cannot finish. */
    public static void tick() {
        if (SAVERS.isEmpty()) {
            return;
        }
        List<AutoSaver> savers = new ArrayList<>(SAVERS.values());
        for (AutoSaver saver : savers) {
            saver.tick();
            if (saver.isCancelled()) {
                SAVERS.remove(saver.collectionId(), saver);
                continue;
            }
            if (saver.isAttached()) {
                continue;
            }
            if (saver.isIdle()) {
                saver.cancel();
                SAVERS.remove(saver.collectionId(), saver);
            } else if (saver.needsReport() && reporter != null) {
                reporter.accept(saver);
            }
        }
    }

    /**
     * Ends a collection's saver at once, as before the editor deletes the collection for good:
     * what waits is dropped, its batches not sent yet leave the upload queue, and the outcome of
     * one already on its way is no longer heard, so it says nothing once the collection is gone.
     */
    public static void cancel(String collectionId) {
        AutoSaver saver = SAVERS.remove(collectionId);
        if (saver != null) {
            saver.cancel();
        }
    }

    /** Ends every saver, when the client leaves or joins a server. */
    public static void reset() {
        for (AutoSaver saver : SAVERS.values()) {
            saver.cancel();
        }
        SAVERS.clear();
        lastFlushAt = Long.MIN_VALUE / 2;
    }

    static void remove(AutoSaver saver) {
        SAVERS.remove(saver.collectionId(), saver);
    }

    /** When this client last started an autosave, of any collection. */
    static long lastFlushAt() {
        return lastFlushAt;
    }

    static void markFlush(long now) {
        lastFlushAt = now;
    }
}
