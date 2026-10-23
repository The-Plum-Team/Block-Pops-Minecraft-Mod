package com.theplumteam.server.worldcollections;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.SyncWorldCollectionsPacket;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionCheck;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The World collections of the running server: operator-made collections whose files are
 * stored with the world ({@link WorldCollectionStore}).
 *
 * <p>Every change, whether an edit from the editor, an import or a command, builds the
 * next record, checks it, its budgets and its materialized collection exactly as clients
 * will, saves it and only then registers it again. A change that fails any step changes
 * nothing; one that changes nothing saves nothing. A batch of edits is one change: its
 * edits apply in order to a working copy of the state, and it is saved once or not at all.
 *
 * <p>Every player is sent the published and archived collections; operators are sent the
 * drafts as well. A player whose operator status changes is sent the other view within
 * {@link #SYNC_INTERVAL_TICKS}, since no event announces it.
 *
 * <p>The registry, the store's state and players are touched only on the server thread.
 * Checking, hashing and writing uploaded files runs on the "BlockPops World Content"
 * executor, which lives as long as the server.
 */
public final class WorldCollections {
    /** The fewest ticks between two syncs sent to every player. */
    private static final int SYNC_INTERVAL_TICKS = 20;
    /** The fewest ticks between two syncs to every player when the sync is larger than one part. */
    private static final int LARGE_SYNC_INTERVAL_TICKS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile WorldCollectionStore store;
    private static ExecutorService executor;
    /** The collections this server registered, so stopping removes exactly those. */
    private static final Set<String> REGISTERED = new LinkedHashSet<>();
    /** Files being checked and written, which collection must not delete meanwhile. */
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Map<UUID, Long> SESSION_NONCES = new HashMap<>();
    /** Whether each player was last sent the operators' view, which includes the drafts. */
    private static final Map<UUID, Boolean> SYNCED_AS_OPERATOR = new HashMap<>();
    private static boolean dirty;
    private static int revision;
    private static int lastSyncTick;
    private static int lastSyncId;
    /** What a sync sends operators at {@link #revision}, built when first needed. */
    @Nullable
    private static SyncContent operatorContent;
    /** What a sync sends every other player at {@link #revision}, built when first needed. */
    @Nullable
    private static SyncContent publicContent;

    /**
     * What one view of the collections sends: the registered records it shows, the files
     * they name, and the stored-bytes quota. Every player of the same view gets the same
     * content; only the sync id and session nonce differ between them.
     *
     * @param shas every file the records name, which are the only ones players may ask for
     */
    private record SyncContent(int revision, List<byte[]> records, List<SyncWorldCollectionsPacket.Blob> blobs,
                               Set<String> shas, int quotaUsedKiB, int quotaMaxKiB, boolean large) {
    }

    private WorldCollections() {
    }

    // Lifecycle

    /** Loads the world's store, collects unused files and registers every collection. */
    public static void onServerStarting(MinecraftServer server) {
        onServerStopped();
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "BlockPops World Content");
            thread.setDaemon(true);
            return thread;
        });
        WorldCollectionStore loaded = WorldCollectionStore.load(server.getWorldPath(LevelResource.ROOT).resolve("data"));
        store = loaded;
        if (!loaded.isDegraded()) {
            collectGarbage(loaded);
            loaded.sweep(System.currentTimeMillis());
        }
        Map<String, BlobInfo> blobs = loaded.state().blobInfos();
        List<WorldCollectionRecord> registered = new ArrayList<>();
        Set<String> sentFiles = new HashSet<>();
        for (WorldCollectionRecord record : loaded.state().records()) {
            String problem = problem(record, blobs);
            if (problem != null) {
                BlockPopsMod.LOGGER.error("Not registering World collection {}: it {}", record.id(), problem);
                continue;
            }
            // A store that breaks a budget is degraded; clients refuse a sync that breaks one,
            // so only the collections that fit, in store order, are registered and sent
            List<WorldCollectionRecord> candidate = new ArrayList<>(registered);
            candidate.add(record);
            Set<String> named = new HashSet<>(sentFiles);
            named.addAll(record.referencedShas());
            try {
                WorldCollectionRecord.checkBudgets(candidate, blobs);
                if (named.size() > WorldContentLimits.MAX_BLOBS) {
                    throw new WorldContentException("Budget files: more than " + WorldContentLimits.MAX_BLOBS + " named");
                }
            } catch (WorldContentException e) {
                BlockPopsMod.LOGGER.error("Not registering World collection {}: {}", record.id(), e.getMessage());
                continue;
            }
            registered.add(record);
            sentFiles.addAll(record.referencedShas());
            register(record, blobs);
        }
        if (!REGISTERED.isEmpty()) {
            BlockPopsMod.LOGGER.info("Registered {} World collections", REGISTERED.size());
        }
    }

    /** Unregisters every World collection and drops all per-server state. */
    public static void onServerStopped() {
        REGISTERED.forEach(CollectionRegistry::unregisterDynamicCollection);
        REGISTERED.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        store = null;
        IN_FLIGHT.clear();
        SESSION_NONCES.clear();
        SYNCED_AS_OPERATOR.clear();
        WorldRateLimits.clear();
        WorldUploads.clear();
        WorldAssetSender.clear();
        dirty = false;
        lastSyncTick = 0;
        operatorContent = null;
        publicContent = null;
    }

    /** Starts a player's session: a fresh nonce, then a sync even when there is nothing to show. */
    public static void onPlayerJoin(ServerPlayer player) {
        SESSION_NONCES.put(player.getUUID(), RANDOM.nextLong());
        sendSync(player);
    }

    /** Ends a player's session: its nonce, view, uploads, file queue and rate limits. */
    public static void onPlayerQuit(ServerPlayer player) {
        SESSION_NONCES.remove(player.getUUID());
        SYNCED_AS_OPERATOR.remove(player.getUUID());
        WorldRateLimits.forget(player.getUUID());
        WorldUploads.dropPlayer(player.getUUID());
        WorldAssetSender.dropPlayer(player.getUUID());
    }

    /**
     * Drops stalled uploads, sends the files players asked for, and sends the latest state
     * to every player once a change has waited long enough: {@link #SYNC_INTERVAL_TICKS},
     * or {@link #LARGE_SYNC_INTERVAL_TICKS} when the sync takes more than one part. Every
     * {@link #SYNC_INTERVAL_TICKS} it also resyncs each player whose operator status no
     * longer matches the view it was sent.
     */
    public static void onServerTick(MinecraftServer server) {
        if (store == null) {
            return;
        }
        WorldUploads.sweep(server);
        WorldAssetSender.tick(server);
        int tick = server.getTickCount();
        if (tick % SYNC_INTERVAL_TICKS == 0) {
            resyncChangedOperators(server);
        }
        if (!dirty) {
            return;
        }
        // The operators' view holds every record the other one does, so it bounds both
        int interval = syncContent(true).large() ? LARGE_SYNC_INTERVAL_TICKS : SYNC_INTERVAL_TICKS;
        if (tick - lastSyncTick < interval && tick >= lastSyncTick) {
            return;
        }
        dirty = false;
        lastSyncTick = tick;
        server.getPlayerList().getPlayers().forEach(WorldCollections::sendSync);
    }

    /**
     * Sends the operators' view to a player who became an operator and the public one to a
     * player who stopped being one. There is no event for either, so this polls; a change
     * shows on the client within a second, and its drafts appear or vanish with that sync.
     */
    private static void resyncChangedOperators(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Boolean synced = SYNCED_AS_OPERATOR.get(player.getUUID());
            if (synced != null && synced != isOperator(player)) {
                sendSync(player);
            }
        }
    }

    /** Whether a player may see drafts: the level every World collection edit needs. */
    private static boolean isOperator(ServerPlayer player) {
        return ServerLevels.hasCommandLevel(player, 2);
    }

    /** Sends the records and the file table to one player, in as many parts as they need. */
    private static void sendSync(ServerPlayer player) {
        boolean operator = isOperator(player);
        // Kept even for a player without the channel, so the operator poll does not retry it every second
        SYNCED_AS_OPERATOR.put(player.getUUID(), operator);
        if (!SyncWorldCollectionsPacket.canReceive(player)) {
            return;
        }
        SyncContent content = syncContent(operator);
        lastSyncId = lastSyncId == Integer.MAX_VALUE ? 1 : lastSyncId + 1;
        List<SyncWorldCollectionsPacket> parts = SyncWorldCollectionsPacket.split(lastSyncId, sessionNonce(player),
                content.quotaUsedKiB(), content.quotaMaxKiB(), content.records(), content.blobs());
        if (parts.isEmpty()) {
            // The caps keep every valid store far below this, so only a store that broke them gets here
            BlockPopsMod.LOGGER.error("Not sending World collections revision {}: {} records and {} files do not fit a sync",
                    content.revision(), content.records().size(), content.blobs().size());
            return;
        }
        parts.forEach(part -> part.sendToPlayer(player));
        WorldAssetSender.onSyncSent(player);
    }

    /** What a sync sends now to operators or to every other player: each built once per revision. */
    private static SyncContent syncContent(boolean operator) {
        SyncContent content = operator ? operatorContent : publicContent;
        if (content != null && content.revision() == revision) {
            return content;
        }
        WorldCollectionStore current = store;
        WorldCollectionStore.State state = state();
        List<byte[]> records = new ArrayList<>();
        Set<String> shas = new LinkedHashSet<>();
        for (WorldCollectionRecord record : state.records()) {
            // A record that failed its check at load is not registered, and clients would refuse the whole sync over it.
            // A draft is shown to operators alone, who are the only ones that can edit or publish it
            if (REGISTERED.contains(record.id()) && (operator || !record.draft())) {
                records.add(record.toUtf8());
                shas.addAll(record.referencedShas());
            }
        }
        List<SyncWorldCollectionsPacket.Blob> blobs = new ArrayList<>();
        for (String sha : new TreeMap<>(state.blobs()).keySet()) {
            if (shas.contains(sha)) {
                blobs.add(new SyncWorldCollectionsPacket.Blob(sha, state.blobs().get(sha).info(),
                        current != null && current.isMissing(sha)));
            }
        }
        int usedKiB = (int) Math.min(Integer.MAX_VALUE, (state.storedBytes() + 1023) / 1024);
        int maxKiB = (int) (WorldContentLimits.MAX_STORED_BYTES / 1024);
        boolean large = SyncWorldCollectionsPacket.estimatedBytes(records, blobs) > SyncWorldCollectionsPacket.MAX_PART_BYTES;
        content = new SyncContent(revision, List.copyOf(records), List.copyOf(blobs), Set.copyOf(shas), usedKiB, maxKiB,
                large);
        if (operator) {
            operatorContent = content;
        } else {
            publicContent = content;
        }
        return content;
    }

    /**
     * Every file the records of a player's view name, the only files that player may ask
     * for: none before its first sync, and the public view's alone once it is no longer an
     * operator, even before the poll sends it that view.
     */
    public static Set<String> sentShasFor(ServerPlayer player) {
        Boolean synced = SYNCED_AS_OPERATOR.get(player.getUUID());
        if (synced == null) {
            return Set.of();
        }
        return syncContent(synced && isOperator(player)).shas();
    }

    // Queries, server thread

    /** Whether the store of the running server is loaded and accepts changes. */
    public static boolean isWritable() {
        WorldCollectionStore current = store;
        return current != null && !current.isDegraded();
    }

    public static boolean isDegraded() {
        WorldCollectionStore current = store;
        return current == null || current.isDegraded();
    }

    /** The current state, or an empty one while no server runs. */
    public static WorldCollectionStore.State state() {
        WorldCollectionStore current = store;
        return current != null ? current.state() : WorldCollectionStore.State.EMPTY;
    }

    public static List<WorldCollectionRecord> records() {
        return state().records();
    }

    @Nullable
    public static WorldCollectionRecord record(String id) {
        return state().record(id);
    }

    public static boolean isMissing(String sha) {
        WorldCollectionStore current = store;
        return current != null && current.isMissing(sha);
    }

    /** Whether the record names a file that is missing from disk. */
    public static boolean hasMissingFiles(WorldCollectionRecord record) {
        WorldCollectionStore current = store;
        return current != null && record.referencedShas().stream().anyMatch(current::isMissing);
    }

    @Nullable
    public static WorldCollectionStore store() {
        return store;
    }

    /** The executor that checks, hashes, reads and writes files, or null while no server runs. */
    @Nullable
    public static ExecutorService executor() {
        return executor;
    }

    /** The nonce of a player's current session, drawn when it joined. */
    public static long sessionNonce(ServerPlayer player) {
        return SESSION_NONCES.getOrDefault(player.getUUID(), 0L);
    }

    /** Counts every change players must be told about. */
    public static int revision() {
        return revision;
    }

    /**
     * A stored file could not be read for a player: marks it missing, so every player is
     * told and an operator can upload it again. Server thread.
     */
    static void markLost(WorldCollectionStore current, String sha) {
        if (store == current && current.markMissing(sha)) {
            markDirty();
        }
    }

    /** Schedules a sync of the current state to every player. */
    public static void markDirty() {
        revision++;
        dirty = true;
    }

    // Uploads

    /**
     * Whether a file may be uploaded: null when it may, ALREADY_STORED when the table has
     * it and it is on disk, or why not. A file the table marks missing may be uploaded
     * again, which repairs it.
     */
    @Nullable
    public static WorldContentStatus checkUpload(String sha, @Nullable BlobType type, long size) {
        WorldCollectionStore current = store;
        if (current == null || current.isDegraded()) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        if (!Sha256.isValidHex(sha) || type == null) {
            return WorldContentStatus.of(Result.INVALID, "Unknown file type or SHA-256");
        }
        if (size < 1 || size > type.maxBytes()) {
            return WorldContentStatus.of(Result.TOO_LARGE, "A " + type.storeName() + " file is at most "
                    + type.maxBytes() / 1024 + " KiB");
        }
        WorldCollectionStore.State state = current.state();
        WorldCollectionStore.StoredBlob existing = state.blobs().get(sha);
        if (existing != null) {
            if (!current.isMissing(sha)) {
                return WorldContentStatus.of(Result.ALREADY_STORED, sha);
            }
            return existing.info().type() == type ? null
                    : WorldContentStatus.of(Result.INVALID, "That file is stored as another type");
        }
        if (state.blobs().size() + 1 > WorldContentLimits.MAX_BLOBS) {
            return WorldContentStatus.of(Result.QUOTA, "Budget files: " + WorldContentLimits.MAX_BLOBS + " stored");
        }
        if (state.storedBytes() + size > WorldContentLimits.MAX_STORED_BYTES) {
            return WorldContentStatus.of(Result.QUOTA, "Budget stored bytes: " + state.storedBytes() / 1024 + " of "
                    + WorldContentLimits.MAX_STORED_BYTES / 1024 + " KiB used");
        }
        return null;
    }

    /**
     * Stores a complete uploaded file: checks its sha and content on the content executor,
     * writes it there, then adds it to the table on the server thread. Reply runs on the
     * server thread with STORED, ALREADY_STORED or why not.
     */
    public static void storeBlob(MinecraftServer server, String sha, BlobType type, byte[] bytes, UUID by,
                                 Consumer<WorldContentStatus> reply) {
        WorldContentStatus refused = checkUpload(sha, type, bytes.length);
        if (refused != null) {
            reply.accept(refused);
            return;
        }
        if (!IN_FLIGHT.add(sha)) {
            reply.accept(WorldContentStatus.of(Result.BUSY, "That file is being stored already"));
            return;
        }
        WorldCollectionStore current = store;
        try {
            executor.execute(() -> {
                BlobInfo info = null;
                WorldContentStatus failure = null;
                try {
                    if (!Sha256.of(bytes).equals(sha)) {
                        throw new WorldContentException("The file does not match its SHA-256");
                    }
                    info = type.validate(bytes);
                    current.writeBlob(sha, bytes);
                } catch (WorldContentException e) {
                    failure = WorldContentStatus.invalid(e);
                } catch (IOException e) {
                    BlockPopsMod.LOGGER.error("Failed to write the World collection file {}", sha, e);
                    failure = WorldContentStatus.of(Result.SAVE_FAILED);
                }
                BlobInfo checked = info;
                WorldContentStatus outcome = failure;
                server.execute(() -> {
                    if (store != current) {
                        // The server stopped meanwhile; whatever was written is collected later
                        return;
                    }
                    IN_FLIGHT.remove(sha);
                    reply.accept(outcome != null ? outcome : addBlob(current, sha, checked, by));
                });
            });
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.remove(sha);
            reply.accept(WorldContentStatus.of(Result.SAVE_FAILED));
        }
    }

    /** Adds a written file to the table. Server thread. */
    private static WorldContentStatus addBlob(WorldCollectionStore current, String sha, BlobInfo info, UUID by) {
        WorldContentStatus refused = checkUpload(sha, info.type(), info.size());
        if (refused != null) {
            if (refused.result() == Result.QUOTA || refused.result() == Result.DEGRADED) {
                // Not in the table, so nothing else uses the copy just written
                current.deleteBlobFiles(List.of(sha));
            }
            return refused.result() == Result.ALREADY_STORED ? WorldContentStatus.of(Result.STORED, sha) : refused;
        }
        Map<String, WorldCollectionStore.StoredBlob> blobs = new TreeMap<>(current.state().blobs());
        blobs.put(sha, new WorldCollectionStore.StoredBlob(info, System.currentTimeMillis(), by));
        if (!current.save(current.state().withBlobs(blobs))) {
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
        current.markPresent(sha);
        markDirty();
        return WorldContentStatus.of(Result.STORED, sha);
    }

    // Edits

    /**
     * The changes of one edit or batch, made to a working copy of the state. Nothing
     * reaches the store or the registry until {@link #finish} has checked every changed
     * record against the working state as a whole.
     */
    private static final class Txn {
        private WorldCollectionStore.State working;
        /** Each changed collection's record before the batch, or null for one the batch created. */
        private final Map<String, WorldCollectionRecord> before = new HashMap<>();
        /** The collection each duplicate was copied from, whose missing files the copy may keep naming. */
        private final Map<String, WorldCollectionRecord> copiedFrom = new HashMap<>();
        private final LinkedHashSet<String> changed = new LinkedHashSet<>();
        /** The collection the last edit touched, whose name a successful answer carries. */
        @Nullable
        private String lastId;

        private Txn(WorldCollectionStore.State start) {
            this.working = start;
        }

        /** Puts the next record of an existing collection in the working state. */
        private void change(WorldCollectionRecord record) {
            WorldCollectionStore.StoredCollection stored = working.collections().get(record.id());
            if (changed.add(record.id())) {
                before.put(record.id(), stored.record());
            }
            working = working.withCollection(
                    new WorldCollectionStore.StoredCollection(record, stored.createdBy(), stored.createdAt()));
            lastId = record.id();
        }

        /** Adds a new collection to the working state. */
        private void create(WorldCollectionRecord record, UUID by, long now) {
            changed.add(record.id());
            before.put(record.id(), null);
            working = working.withCollection(new WorldCollectionStore.StoredCollection(record, by, now));
            lastId = record.id();
        }

        /**
         * The record a changed collection's files are checked against: a file marked missing
         * may stay named only when this record named it already.
         */
        @Nullable
        private WorldCollectionRecord filesBefore(String id) {
            WorldCollectionRecord previous = before.get(id);
            return previous != null ? previous : copiedFrom.get(id);
        }
    }

    /**
     * Applies an operator's edit, or every edit of a batch in order, and saves the result
     * once. When any edit or any changed record is refused, nothing changes and the answer
     * is the first refusal. A purge goes to {@link #purge}; an import needs the server's
     * resources and goes to {@link #importPack} instead. Server thread; the caller has
     * checked the operator.
     */
    public static WorldContentStatus edit(WorldCollectionEdit edit, UUID by) {
        WorldCollectionStore current = store;
        if (current == null || current.isDegraded()) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        if (edit instanceof WorldCollectionEdit.Purge purge) {
            return purgeFromEditor(purge);
        }
        if (edit instanceof WorldCollectionEdit.ImportPack) {
            return WorldContentStatus.of(Result.INVALID, "An import reads the server's datapacks and is not an edit");
        }
        List<WorldCollectionEdit> edits = edit instanceof WorldCollectionEdit.Batch batch ? batch.edits() : List.of(edit);
        if (edits.isEmpty() || edits.size() > WorldCollectionEdit.MAX_BATCH) {
            return WorldContentStatus.of(Result.INVALID, "A batch holds 1 to " + WorldCollectionEdit.MAX_BATCH + " edits");
        }
        if (edits.size() > 1 || edit instanceof WorldCollectionEdit.Batch) {
            for (WorldCollectionEdit one : edits) {
                if (WorldCollectionEdit.standsAlone(one)) {
                    return WorldContentStatus.of(Result.INVALID, "A batch cannot hold " + one.action());
                }
            }
        }
        Txn txn = new Txn(current.state());
        for (WorldCollectionEdit one : edits) {
            WorldContentStatus refused = apply(txn, one, by);
            if (refused != null) {
                return refused;
            }
        }
        return finish(current, txn);
    }

    /**
     * Applies one edit to the working state. Every edit reads the working state, so an edit
     * sees what the edits before it in the same batch did.
     *
     * @return null when it applies, or why not
     */
    @Nullable
    private static WorldContentStatus apply(Txn txn, WorldCollectionEdit edit, UUID by) {
        if (edit instanceof WorldCollectionEdit.Batch || edit instanceof WorldCollectionEdit.Purge
                || edit instanceof WorldCollectionEdit.ImportPack) {
            return WorldContentStatus.of(Result.INVALID, "A batch cannot hold " + edit.action());
        }
        if (edit instanceof WorldCollectionEdit.Create create) {
            return create(txn, create.slug(), create.name(), false, by);
        }
        if (edit instanceof WorldCollectionEdit.CreateDraft create) {
            return create(txn, create.slug(), create.name(), true, by);
        }
        if (edit instanceof WorldCollectionEdit.Duplicate duplicate) {
            return duplicate(txn, duplicate, by);
        }
        String id = editedId(edit);
        WorldCollectionRecord previous = id != null ? txn.working.record(id) : null;
        if (previous == null) {
            return WorldContentStatus.of(Result.UNKNOWN_COLLECTION, WorldContentException.excerpt(id));
        }
        try {
            txn.change(edited(previous, edit));
        } catch (WorldContentException e) {
            return WorldContentStatus.invalid(e);
        } catch (Refused e) {
            return e.status;
        }
        return null;
    }

    @Nullable
    private static String editedId(WorldCollectionEdit edit) {
        if (edit instanceof WorldCollectionEdit.SetCollection set) {
            return set.id();
        } else if (edit instanceof WorldCollectionEdit.SetArchived set) {
            return set.id();
        } else if (edit instanceof WorldCollectionEdit.PutFigure put) {
            return put.id();
        } else if (edit instanceof WorldCollectionEdit.SetFigureEnabled set) {
            return set.id();
        } else if (edit instanceof WorldCollectionEdit.Publish publish) {
            return publish.id();
        } else if (edit instanceof WorldCollectionEdit.ReorderFigures reorder) {
            return reorder.id();
        } else if (edit instanceof WorldCollectionEdit.RemoveFigure remove) {
            return remove.id();
        } else if (edit instanceof WorldCollectionEdit.SetCollectionExtras set) {
            return set.id();
        } else if (edit instanceof WorldCollectionEdit.SetFigureExtras set) {
            return set.id();
        }
        return null;
    }

    /** Adds an empty collection with the slug, published or as a draft, to the working state. */
    @Nullable
    private static WorldContentStatus create(Txn txn, String slug, String name, boolean draft, UUID by) {
        WorldContentStatus refused = refuseNew(txn.working, slug);
        if (refused != null) {
            return refused;
        }
        String id = WorldContentIds.collectionId(slug);
        WorldCollectionRecord record = WorldCollectionRecord.created(id, name, draft);
        txn.create(record, by, System.currentTimeMillis());
        return null;
    }

    /**
     * Adds a copy of a collection to the working state as a new draft with the slug and
     * name: its figures, retired ones included, and every file and extra, at revision 0.
     * The copy names the same stored files, so nothing is uploaded, and it may keep naming
     * a file the source has lost.
     */
    @Nullable
    private static WorldContentStatus duplicate(Txn txn, WorldCollectionEdit.Duplicate duplicate, UUID by) {
        WorldCollectionRecord source = txn.working.record(duplicate.sourceId());
        if (source == null) {
            return WorldContentStatus.of(Result.UNKNOWN_COLLECTION, WorldContentException.excerpt(duplicate.sourceId()));
        }
        WorldContentStatus refused = refuseNew(txn.working, duplicate.slug());
        if (refused != null) {
            return refused;
        }
        String id = WorldContentIds.collectionId(duplicate.slug());
        WorldCollectionRecord copy = new WorldCollectionRecord(id, duplicate.name(), source.author(), source.red(),
                source.green(), source.blue(), source.boxTexture(), source.boxColor(), source.logo(), false, 0,
                source.figures(), true, 0L, source.authorUrl(), source.logoLayout(), source.plush(),
                source.rarityWeights());
        txn.create(copy, by, System.currentTimeMillis());
        txn.copiedFrom.put(id, source);
        return null;
    }

    /** Why a new collection cannot take the slug in the state, or null when it can. */
    @Nullable
    private static WorldContentStatus refuseNew(WorldCollectionStore.State state, String slug) {
        if (!WorldContentIds.isSlug(slug)) {
            return WorldContentStatus.of(Result.INVALID, "Slug must be 1 to 24 of a-z, 0-9 and _");
        }
        String id = WorldContentIds.collectionId(slug);
        if (state.collections().containsKey(id) || state.retiredIds().contains(id)) {
            return WorldContentStatus.of(Result.ID_TAKEN, id);
        }
        if (state.collections().size() >= WorldContentLimits.MAX_COLLECTIONS) {
            return WorldContentStatus.of(Result.FULL, "A world has at most " + WorldContentLimits.MAX_COLLECTIONS
                    + " World collections");
        }
        return null;
    }

    /** The time a collection changed now, as its {@code edited_at} holds it: whole seconds, in range. */
    private static long editedNow() {
        return Math.min(WorldCollectionRecord.MAX_EDITED_AT, Math.max(1L, System.currentTimeMillis() / 1000));
    }

    /**
     * Saves what a transaction changed. A record that ends equal to the stored one is left
     * as it is; every other one goes to the next revision (a new one stays at 0), is stamped
     * with the time of this save, and is checked with the whole working state in place.
     * Then the state is saved once, every changed collection is registered again and one
     * sync is scheduled.
     */
    private static WorldContentStatus finish(WorldCollectionStore current, Txn txn) {
        // Stamped after the equality test, so an edit that changes nothing still saves nothing
        long editedAt = editedNow();
        WorldCollectionStore.State next = txn.working;
        List<WorldCollectionRecord> saved = new ArrayList<>();
        for (String id : txn.changed) {
            WorldCollectionStore.StoredCollection stored = next.collections().get(id);
            WorldCollectionRecord previous = txn.before.get(id);
            WorldCollectionRecord record = stored.record();
            if (record.equals(previous)) {
                continue;
            }
            if (previous != null) {
                if (previous.revision() >= WorldContentLimits.MAX_REVISION) {
                    return WorldContentStatus.of(Result.FULL, "This collection cannot be changed any more");
                }
                record = record.withRevision(previous.revision() + 1);
            }
            record = record.withEditedAt(editedAt);
            next = next.withCollection(
                    new WorldCollectionStore.StoredCollection(record, stored.createdBy(), stored.createdAt()));
            saved.add(record);
        }
        String name = txn.lastId != null && next.record(txn.lastId) != null ? next.record(txn.lastId).name() : "";
        if (saved.isEmpty()) {
            return WorldContentStatus.of(Result.OK, name);
        }
        Map<String, BlobInfo> blobs = next.blobInfos();
        for (WorldCollectionRecord record : saved) {
            WorldContentStatus refused = check(current, next, record, txn.filesBefore(record.id()), blobs, Set.of());
            if (refused != null) {
                return refused;
            }
        }
        if (!current.save(next)) {
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
        for (WorldCollectionRecord record : saved) {
            register(record, blobs);
        }
        markDirty();
        return WorldContentStatus.of(Result.OK, name);
    }

    /** The record an edit of an existing collection makes, at the previous revision. */
    private static WorldCollectionRecord edited(WorldCollectionRecord previous, WorldCollectionEdit edit)
            throws WorldContentException, Refused {
        if (edit instanceof WorldCollectionEdit.SetCollection set) {
            String boxTexture = null;
            String boxColor = null;
            switch (set.boxMode()) {
                case WorldCollectionEdit.BOX_NONE -> {
                }
                case WorldCollectionEdit.BOX_TEXTURE -> boxTexture = Sha256.require(set.box(), "Box texture");
                case WorldCollectionEdit.BOX_COLOR -> {
                    if (!WorldCollectionRecord.isBoxColor(set.box())) {
                        throw new WorldContentException("Unknown box color " + WorldContentException.excerpt(set.box()));
                    }
                    boxColor = set.box();
                }
                default -> throw new WorldContentException("Unknown box mode");
            }
            String logo = set.logo() == null || set.logo().isEmpty() ? null : Sha256.require(set.logo(), "Logo");
            if (!inByteRange(set.red()) || !inByteRange(set.green()) || !inByteRange(set.blue())) {
                throw new WorldContentException("Background colour values are 0 to 255");
            }
            return previous.withIdentity(set.name(), set.author(), set.red(), set.green(), set.blue(), boxTexture,
                    boxColor, logo);
        }
        if (edit instanceof WorldCollectionEdit.SetArchived set) {
            if (previous.draft()) {
                throw new Refused(WorldContentStatus.of(Result.NOT_DRAFT, "Drafts are not archived"));
            }
            return previous.withArchived(set.archived());
        }
        if (edit instanceof WorldCollectionEdit.PutFigure put) {
            return withFigure(previous, figure(put, previous));
        }
        if (edit instanceof WorldCollectionEdit.SetFigureEnabled set) {
            return withFigure(previous, existingFigure(previous, set.figure()).withEnabled(set.enabled()));
        }
        if (edit instanceof WorldCollectionEdit.Publish) {
            if (!previous.draft()) {
                throw new Refused(WorldContentStatus.of(Result.NOT_DRAFT, "Only a draft can be published"));
            }
            if (previous.figures().stream().noneMatch(WorldCollectionRecord.Figure::enabled)) {
                throw new Refused(WorldContentStatus.of(Result.EMPTY, previous.name()));
            }
            return previous.withDraft(false);
        }
        if (edit instanceof WorldCollectionEdit.ReorderFigures reorder) {
            return reordered(previous, reorder.order());
        }
        if (edit instanceof WorldCollectionEdit.SetCollectionExtras set) {
            return withCollectionExtras(previous, set);
        }
        if (edit instanceof WorldCollectionEdit.SetFigureExtras set) {
            return withFigure(previous, figureExtras(existingFigure(previous, set.figure()), set));
        }
        if (edit instanceof WorldCollectionEdit.RemoveFigure remove) {
            if (!previous.draft()) {
                // Players' boxes may name a published figure, so it is only ever retired
                throw new Refused(WorldContentStatus.of(Result.NOT_DRAFT,
                        "Only a draft's figures can be deleted; retire this one instead"));
            }
            existingFigure(previous, remove.figure());
            List<WorldCollectionRecord.Figure> figures = new ArrayList<>(previous.figures());
            figures.removeIf(figure -> figure.id().equals(remove.figure()));
            return previous.withFigures(figures);
        }
        throw new WorldContentException("Unknown edit");
    }

    /**
     * The record with the author link, logo placement, plush flag and rarity weights of
     * the edit. The record's own check repeats every rule; these give the clearer reason.
     */
    private static WorldCollectionRecord withCollectionExtras(WorldCollectionRecord previous,
                                                              WorldCollectionEdit.SetCollectionExtras set)
            throws WorldContentException {
        String authorUrl = set.authorUrl().isEmpty() ? null : set.authorUrl();
        if (authorUrl != null && !WorldCollectionRecord.isValidAuthorUrl(authorUrl)) {
            throw new WorldContentException("The author link must be an http or https link with a host, "
                    + "at most " + WorldContentLimits.MAX_AUTHOR_URL_LENGTH + " characters, without spaces");
        }
        WorldCollectionRecord.LogoLayout layout = set.logoLayout();
        if (layout != null) {
            if (!WorldCollectionRecord.LogoLayout.isValid(layout.scalePct(), layout.offsetX(), layout.offsetY())) {
                throw new WorldContentException("The logo's size is " + WorldCollectionRecord.LogoLayout.MIN_SCALE_PCT
                        + " to " + WorldCollectionRecord.LogoLayout.MAX_SCALE_PCT + " % and it moves at most "
                        + WorldCollectionRecord.LogoLayout.MAX_OFFSET + " tenths of a pixel each way");
            }
            if (previous.logo() == null && !WorldCollectionRecord.LogoLayout.DEFAULT.equals(layout)) {
                throw new WorldContentException("The collection has no logo to place");
            }
        }
        WorldCollectionRecord.RarityWeights weights = set.rarityWeights();
        if (weights != null && !WorldCollectionRecord.RarityWeights.isValid(weights.common(), weights.rare(),
                weights.epic(), weights.legendary())) {
            throw new WorldContentException("Rarity weights are " + WorldCollectionRecord.RarityWeights.MIN_WEIGHT
                    + " to " + WorldCollectionRecord.RarityWeights.MAX_WEIGHT);
        }
        return previous.withExtras(authorUrl, layout, set.plush(), weights);
    }

    /**
     * The figure with the rarity, variants and box face of the edit. A face mode that asks
     * for what the figure's kind shows anyway is stored as the default, so a figure always
     * has one form for each look: a skin figure shows its face and a model figure does not
     * unless told otherwise.
     */
    private static WorldCollectionRecord.Figure figureExtras(WorldCollectionRecord.Figure figure,
                                                             WorldCollectionEdit.SetFigureExtras set)
            throws WorldContentException {
        if (set.variants().size() > WorldContentLimits.MAX_VARIANTS_PER_FIGURE) {
            throw new WorldContentException("A figure has at most " + WorldContentLimits.MAX_VARIANTS_PER_FIGURE
                    + " variants");
        }
        List<WorldCollectionRecord.Variant> variants = new ArrayList<>(set.variants().size());
        for (WorldCollectionRecord.Variant variant : set.variants()) {
            variants.add(new WorldCollectionRecord.Variant(variant.name(),
                    Sha256.require(variant.texture(), "Variant texture")));
        }
        boolean skin = figure.kind() == WorldCollectionRecord.Kind.SKIN;
        WorldCollectionRecord.BoxFace boxFace = switch (set.faceMode()) {
            case WorldCollectionEdit.FACE_DEFAULT -> null;
            case WorldCollectionEdit.FACE_SHOW -> {
                if (!skin) {
                    throw new WorldContentException("A model figure's face needs its region in the texture");
                }
                yield null;
            }
            case WorldCollectionEdit.FACE_HIDE -> skin ? new WorldCollectionRecord.BoxFace(false, null) : null;
            case WorldCollectionEdit.FACE_SHOW_UV -> {
                if (skin || set.faceUv() == null) {
                    throw new WorldContentException(skin ? "A skin figure's face is its skin's and takes no region"
                            : "A model figure's face needs its region in the texture");
                }
                yield new WorldCollectionRecord.BoxFace(true, set.faceUv());
            }
            default -> throw new WorldContentException("Unknown box face");
        };
        return figure.withExtras(set.rarity(), variants, boxFace);
    }

    private static WorldCollectionRecord.Figure existingFigure(WorldCollectionRecord record, String figureId)
            throws Refused {
        return record.figure(figureId).orElseThrow(() -> new Refused(
                WorldContentStatus.of(Result.UNKNOWN_FIGURE, WorldContentException.excerpt(figureId))));
    }

    /** The record with its figures in the given order, which must name each of them exactly once. */
    private static WorldCollectionRecord reordered(WorldCollectionRecord previous, List<String> order)
            throws WorldContentException {
        Map<String, WorldCollectionRecord.Figure> byId = new HashMap<>();
        previous.figures().forEach(figure -> byId.put(figure.id(), figure));
        WorldContentException wrong = new WorldContentException("A new order must name each of the "
                + previous.figures().size() + " figures exactly once");
        if (order.size() != byId.size()) {
            throw wrong;
        }
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>(order.size());
        Set<String> seen = new HashSet<>();
        for (String figureId : order) {
            WorldCollectionRecord.Figure figure = byId.get(figureId);
            if (figure == null || !seen.add(figureId)) {
                throw wrong;
            }
            figures.add(figure);
        }
        return previous.withFigures(figures);
    }

    /**
     * The figure a put describes. A figure that exists keeps whether it is retired and the
     * extras a put does not carry: its rarity, variants and box face.
     */
    private static WorldCollectionRecord.Figure figure(WorldCollectionEdit.PutFigure put, WorldCollectionRecord previous)
            throws WorldContentException {
        if (!WorldContentIds.isSlug(put.figure())) {
            throw new WorldContentException("Figure id must be 1 to 24 of a-z, 0-9 and _");
        }
        if (put.scaleIndex() < 0 || put.scaleIndex() >= WorldCollectionRecord.SCALES.size()) {
            throw new WorldContentException("Unknown scale");
        }
        String texture = Sha256.require(put.texture(), "Texture");
        String model = put.model() == null || put.model().isEmpty() ? null : Sha256.require(put.model(), "Model");
        String animation = put.animation() == null || put.animation().isEmpty()
                ? null : Sha256.require(put.animation(), "Animation");
        WorldCollectionRecord.Kind kind = switch (put.kind()) {
            case WorldCollectionEdit.KIND_SKIN -> WorldCollectionRecord.Kind.SKIN;
            case WorldCollectionEdit.KIND_MODEL -> WorldCollectionRecord.Kind.MODEL;
            default -> throw new WorldContentException("Unknown figure type");
        };
        float scale = WorldCollectionRecord.SCALES.get(put.scaleIndex());
        return previous.figure(put.figure())
                .map(existing -> existing.withBasics(put.name(), kind, texture, model, animation, scale))
                .orElseGet(() -> new WorldCollectionRecord.Figure(put.figure(), put.name(), kind, texture, model,
                        animation, scale, true));
    }

    /** The record with the figure replaced in its place, or added last. */
    private static WorldCollectionRecord withFigure(WorldCollectionRecord previous, WorldCollectionRecord.Figure figure)
            throws Refused {
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>(previous.figures());
        boolean replaced = false;
        for (int i = 0; i < figures.size(); i++) {
            if (figures.get(i).id().equals(figure.id())) {
                figures.set(i, figure);
                replaced = true;
            }
        }
        if (!replaced) {
            if (figures.size() >= WorldContentLimits.MAX_FIGURES) {
                throw new Refused(WorldContentStatus.of(Result.FULL, "A collection has at most "
                        + WorldContentLimits.MAX_FIGURES + " figures"));
            }
            figures.add(figure);
        }
        return previous.withFigures(figures);
    }

    /**
     * Saves a changed record at the next revision, stamped with the time of the save, with the
     * files it adds, and registers it again. A record equal to the stored one changes nothing
     * unless files are added.
     */
    private static WorldContentStatus replace(WorldCollectionStore current, WorldCollectionStore.StoredCollection stored,
                                              WorldCollectionRecord candidate,
                                              Map<String, WorldCollectionStore.StoredBlob> addedBlobs) {
        WorldCollectionRecord previous = stored.record();
        WorldCollectionStore.State state = current.state();
        if (candidate.equals(previous) && addedBlobs.isEmpty()) {
            return WorldContentStatus.of(Result.OK, previous.name());
        }
        WorldCollectionRecord next = candidate;
        if (!candidate.equals(previous)) {
            if (previous.revision() >= WorldContentLimits.MAX_REVISION) {
                return WorldContentStatus.of(Result.FULL, "This collection cannot be changed any more");
            }
            // Stamped after the equality test, as an edit is, so an import that changes nothing
            // keeps the time of the last change
            next = candidate.withRevision(previous.revision() + 1).withEditedAt(editedNow());
        }
        Map<String, WorldCollectionStore.StoredBlob> blobs = new TreeMap<>(state.blobs());
        blobs.putAll(addedBlobs);
        Map<String, BlobInfo> infos = new HashMap<>();
        blobs.forEach((sha, blob) -> infos.put(sha, blob.info()));
        WorldContentStatus refused = check(current, state, next, previous, infos, addedBlobs.keySet());
        if (refused != null) {
            return refused;
        }
        return commit(current, state.withBlobs(blobs).withCollection(
                new WorldCollectionStore.StoredCollection(next, stored.createdBy(), stored.createdAt())), next);
    }

    // Commands and imports

    /**
     * Purges a collection for an operator in the editor, who names it twice. Only an
     * archived collection or a draft can go, so a collection still in the machines is never
     * deleted by one mistaken click.
     */
    private static WorldContentStatus purgeFromEditor(WorldCollectionEdit.Purge purge) {
        if (!purge.id().equals(purge.idAgain())) {
            return WorldContentStatus.of(Result.INVALID, "The two ids of a purge differ");
        }
        WorldCollectionRecord record = record(purge.id());
        if (record == null) {
            return WorldContentStatus.of(Result.UNKNOWN_COLLECTION, WorldContentException.excerpt(purge.id()));
        }
        if (!record.archived() && !record.draft()) {
            return WorldContentStatus.of(Result.STILL_LIVE, record.name());
        }
        return purge(purge.id());
    }

    /** Purges a collection: it is unregistered, its id is never used again and its files are left to collection. */
    public static WorldContentStatus purge(String id) {
        WorldCollectionStore current = store;
        if (current == null || current.isDegraded()) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        WorldCollectionStore.State state = current.state();
        WorldCollectionRecord record = state.record(id);
        if (record == null) {
            return WorldContentStatus.of(Result.UNKNOWN_COLLECTION, WorldContentException.excerpt(id));
        }
        if (state.retiredIds().size() >= WorldContentLimits.MAX_RETIRED_IDS) {
            return WorldContentStatus.of(Result.FULL, "This world has purged " + WorldContentLimits.MAX_RETIRED_IDS
                    + " collections, the most it can");
        }
        if (!current.save(state.purged(id))) {
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
        CollectionRegistry.unregisterDynamicCollection(id);
        REGISTERED.remove(id);
        markDirty();
        return WorldContentStatus.of(Result.OK, record.name());
    }

    /** Imports a pack as the command does: a collection it creates is published at once. */
    public static WorldContentStatus importPack(ResourceManager resources, String slug, String namespace, String path,
                                                UUID by) {
        return importPack(resources, slug, namespace, path, by, false);
    }

    /**
     * Imports the pack the server's datapacks ship at namespace:path as the collection with the slug,
     * creating or updating it. Figures the pack no longer lists are retired, never removed.
     * Server thread; the files are small and written synchronously.
     *
     * @param asDraft whether a collection the import creates is a draft; an existing one keeps
     *                whether it is
     */
    public static WorldContentStatus importPack(ResourceManager resources, String slug, String namespace, String path,
                                                UUID by, boolean asDraft) {
        WorldCollectionStore current = store;
        if (current == null || current.isDegraded()) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        WorldCollectionImporter.Pack pack;
        try {
            pack = WorldCollectionImporter.read(resources, slug, namespace, path);
        } catch (WorldCollectionImporter.TooLargeException e) {
            return WorldContentStatus.of(Result.TOO_LARGE, e.getMessage());
        } catch (WorldContentException e) {
            return WorldContentStatus.invalid(e);
        }
        WorldCollectionStore.State state = current.state();
        String id = pack.record().id();
        if (state.retiredIds().contains(id)) {
            return WorldContentStatus.of(Result.ID_TAKEN, id);
        }
        WorldCollectionStore.StoredCollection stored = state.collections().get(id);
        if (stored == null && state.collections().size() >= WorldContentLimits.MAX_COLLECTIONS) {
            return WorldContentStatus.of(Result.FULL, "A world has at most " + WorldContentLimits.MAX_COLLECTIONS
                    + " World collections");
        }

        List<WorldCollectionRecord.Figure> figures = new ArrayList<>(pack.record().figures());
        Set<String> listed = new HashSet<>();
        figures.forEach(figure -> listed.add(figure.id()));
        if (stored != null) {
            for (WorldCollectionRecord.Figure figure : stored.record().figures()) {
                if (!listed.contains(figure.id())) {
                    figures.add(figure.withEnabled(false));
                }
            }
        }
        if (figures.size() > WorldContentLimits.MAX_FIGURES) {
            return WorldContentStatus.of(Result.FULL, "With the figures it retires the collection would have more than "
                    + WorldContentLimits.MAX_FIGURES);
        }
        // The pack defines the collection and its extras; whether it is archived or a draft and
        // its revision stay the world's own. A new collection is stamped with the time now, and an
        // existing one keeps its time unless the import changes it (see replace), so the creator
        // sorts imports among the recently edited
        WorldCollectionRecord candidate = pack.record().withFigures(figures);
        if (stored == null) {
            candidate = candidate.withDraft(asDraft).withEditedAt(editedNow());
        } else {
            WorldCollectionRecord before = stored.record();
            candidate = candidate.withArchived(before.archived()).withDraft(before.draft())
                    .withRevision(before.revision()).withEditedAt(before.editedAt());
        }

        // Write only the files the table lacks or has lost
        long now = System.currentTimeMillis();
        Map<String, WorldCollectionStore.StoredBlob> added = new TreeMap<>();
        for (WorldCollectionImporter.PackFile file : pack.files().values()) {
            if (!state.blobs().containsKey(file.sha()) || current.isMissing(file.sha())) {
                if (IN_FLIGHT.contains(file.sha())) {
                    return WorldContentStatus.of(Result.BUSY, "A file of the pack is being uploaded");
                }
                added.put(file.sha(), new WorldCollectionStore.StoredBlob(file.info(), now, by));
            }
        }
        long storedBytes = state.storedBytes();
        int blobCount = state.blobs().size();
        for (String sha : added.keySet()) {
            if (!state.blobs().containsKey(sha)) {
                storedBytes += added.get(sha).info().size();
                blobCount++;
            }
        }
        if (blobCount > WorldContentLimits.MAX_BLOBS) {
            return WorldContentStatus.of(Result.QUOTA, "Budget files: " + WorldContentLimits.MAX_BLOBS + " stored");
        }
        if (storedBytes > WorldContentLimits.MAX_STORED_BYTES) {
            return WorldContentStatus.of(Result.QUOTA, "Budget stored bytes: the pack needs " + storedBytes / 1024
                    + " of " + WorldContentLimits.MAX_STORED_BYTES / 1024 + " KiB");
        }

        if (stored == null) {
            Map<String, WorldCollectionStore.StoredBlob> blobs = new TreeMap<>(state.blobs());
            blobs.putAll(added);
            Map<String, BlobInfo> infos = new HashMap<>();
            blobs.forEach((sha, blob) -> infos.put(sha, blob.info()));
            WorldContentStatus refused = check(current, state, candidate, null, infos, added.keySet());
            if (refused != null) {
                return refused;
            }
            if (!write(current, pack, added.keySet())) {
                return WorldContentStatus.of(Result.SAVE_FAILED);
            }
            WorldContentStatus status = commit(current, state.withBlobs(blobs).withCollection(
                    new WorldCollectionStore.StoredCollection(candidate, by, now)), candidate);
            added.keySet().forEach(current::markPresent);
            return status;
        }
        WorldContentStatus refused = dryRun(current, stored, candidate, added);
        if (refused != null) {
            return refused;
        }
        if (!write(current, pack, added.keySet())) {
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
        WorldContentStatus status = replace(current, stored, candidate, added);
        if (status.result() == Result.OK) {
            added.keySet().forEach(current::markPresent);
        }
        return status;
    }

    /** Checks an import into an existing collection before any file is written. */
    @Nullable
    private static WorldContentStatus dryRun(WorldCollectionStore current, WorldCollectionStore.StoredCollection stored,
                                             WorldCollectionRecord candidate,
                                             Map<String, WorldCollectionStore.StoredBlob> added) {
        Map<String, WorldCollectionStore.StoredBlob> blobs = new TreeMap<>(current.state().blobs());
        blobs.putAll(added);
        Map<String, BlobInfo> infos = new HashMap<>();
        blobs.forEach((sha, blob) -> infos.put(sha, blob.info()));
        return check(current, current.state(), candidate, stored.record(), infos, added.keySet());
    }

    private static boolean write(WorldCollectionStore current, WorldCollectionImporter.Pack pack, Set<String> shas) {
        for (String sha : shas) {
            try {
                current.writeBlob(sha, pack.files().get(sha).bytes());
            } catch (IOException e) {
                BlockPopsMod.LOGGER.error("Failed to write the World collection file {}", sha, e);
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the files no record names that were added more than a day ago, unless they
     * are being uploaded. Server thread.
     *
     * @return how many were removed, or why nothing could be
     */
    public static WorldContentStatus collectGarbage() {
        WorldCollectionStore current = store;
        if (current == null || current.isDegraded()) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        int removed = collectGarbage(current);
        return removed < 0 ? WorldContentStatus.of(Result.SAVE_FAILED)
                : WorldContentStatus.of(Result.OK, Integer.toString(removed));
    }

    private static int collectGarbage(WorldCollectionStore current) {
        WorldCollectionStore.State state = current.state();
        Set<String> referenced = state.referencedShas();
        long now = System.currentTimeMillis();
        Map<String, WorldCollectionStore.StoredBlob> kept = new TreeMap<>();
        List<String> removed = new ArrayList<>();
        state.blobs().forEach((sha, blob) -> {
            if (referenced.contains(sha) || IN_FLIGHT.contains(sha) || now - blob.added() <= WorldCollectionStore.GC_GRACE_MILLIS) {
                kept.put(sha, blob);
            } else {
                removed.add(sha);
            }
        });
        if (removed.isEmpty()) {
            return 0;
        }
        if (!current.save(state.withBlobs(kept))) {
            return -1;
        }
        // The table no longer names them, so a failed delete leaves only a file the next start sweeps
        current.deleteBlobFiles(removed);
        removed.forEach(current::markPresent);
        markDirty();
        BlockPopsMod.LOGGER.info("Removed {} unused World collection files", removed.size());
        return removed.size();
    }

    // Checks and registration

    /**
     * Checks a candidate record as clients will: its files are stored, its schema, the
     * budgets of the whole state with it in place, and its materialized collection. A
     * file marked missing may stay referenced but not be newly referenced, unless it is
     * being written again.
     *
     * @param state    the state whose other records count towards the budgets: the store's
     *                 own, or the working state of a batch, which may change several records
     * @param previous the record before the change, or null for a new collection
     * @return null when it passes, or why not
     */
    @Nullable
    private static WorldContentStatus check(WorldCollectionStore current, WorldCollectionStore.State state,
                                            WorldCollectionRecord candidate, @Nullable WorldCollectionRecord previous,
                                            Map<String, BlobInfo> blobs, Set<String> written) {
        Set<String> before = previous != null ? previous.referencedShas() : Set.of();
        for (String sha : candidate.referencedShas()) {
            if (!blobs.containsKey(sha)) {
                return WorldContentStatus.of(Result.MISSING_ASSET, "A file it names is not uploaded: " + sha);
            }
            if (current.isMissing(sha) && !before.contains(sha) && !written.contains(sha)) {
                return WorldContentStatus.of(Result.MISSING_ASSET, "A file it names is missing; upload it again: " + sha);
            }
        }
        try {
            candidate.validated(blobs);
        } catch (WorldContentException e) {
            return WorldContentStatus.invalid(e);
        }
        List<WorldCollectionRecord> all = new ArrayList<>();
        for (WorldCollectionRecord record : state.records()) {
            if (!record.id().equals(candidate.id())) {
                all.add(record);
            }
        }
        all.add(candidate);
        try {
            WorldCollectionRecord.checkBudgets(all, blobs);
        } catch (WorldContentException e) {
            return WorldContentStatus.of(Result.QUOTA, e.getMessage());
        }
        String problem = problem(candidate, blobs);
        return problem != null ? WorldContentStatus.of(Result.INVALID, "The collection " + problem) : null;
    }

    @Nullable
    private static String problem(WorldCollectionRecord record, Map<String, BlobInfo> blobs) {
        return WorldCollectionCheck.problem(WorldCollectionMaterializer.materialize(record, blobs), record);
    }

    /** Saves the next state and registers the changed record in it. */
    private static WorldContentStatus commit(WorldCollectionStore current, WorldCollectionStore.State next,
                                             WorldCollectionRecord record) {
        if (!current.save(next)) {
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
        register(record, next.blobInfos());
        markDirty();
        return WorldContentStatus.of(Result.OK, record.name());
    }

    /** Registers a record's collection again, which also drops figures it no longer has. */
    private static void register(WorldCollectionRecord record, Map<String, BlobInfo> blobs) {
        FigureCollection collection = WorldCollectionMaterializer.materialize(record, blobs);
        CollectionRegistry.unregisterDynamicCollection(record.id());
        CollectionRegistry.registerDynamicCollection(collection);
        REGISTERED.add(record.id());
    }

    private static boolean inByteRange(int value) {
        return value >= 0 && value <= 255;
    }

    /** Ids of every registered World collection. */
    public static Collection<String> registeredIds() {
        return List.copyOf(REGISTERED);
    }

    /** An edit refused with a status of its own rather than as an invalid record. */
    private static final class Refused extends Exception {
        private final WorldContentStatus status;

        Refused(WorldContentStatus status) {
            super(null, null, false, false);
            this.status = status;
        }
    }
}
