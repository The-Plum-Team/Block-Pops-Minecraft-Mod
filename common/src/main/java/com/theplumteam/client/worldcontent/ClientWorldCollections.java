package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.SyncWorldCollectionsPacket;
import com.theplumteam.network.WorldAssetAckPacket;
import com.theplumteam.network.WorldAssetChunkPacket;
import com.theplumteam.network.WorldAssetRequestPacket;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionCheck;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The World collections of the server this client is connected to.
 *
 * <p>A sync arrives in parts that are kept until all of one sync id are in, and then
 * replaces everything the client holds, but only once every record, the budgets and
 * every materialized collection pass the same checks the server made; a sync that fails
 * any of them is refused whole and the previous state stays. Collections whose record
 * changed are registered again, the files they name are announced as coming and asked
 * for, and files no collection names any more are released.
 *
 * <p>Files arrive in parts, in order, and are acknowledged as they come so the server can
 * pace them. A complete file is verified and loaded by {@link ClientWorldAssets}; one that
 * fails verification is asked for once more, and after that its fallback keeps drawing.
 *
 * <p>Everything is forgotten when the client leaves or joins a server, and whenever a sync
 * carries another session nonce, as after a proxy moved the client to another server.
 *
 * Client thread only.
 */
public final class ClientWorldCollections {
    /** The most bytes of files being assembled at once. */
    private static final int MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024;
    /** What one file table entry counts against a sync's size, as the server reckons it. */
    private static final int BLOB_ENTRY_BYTES = 128;
    /** Each file once, and once more after a copy failed verification, as the server allows. */
    private static final int MAX_REQUESTS = 2;

    private enum AssetState { REQUESTED, LOADING, LOADED, FAILED }

    /** The parts of one sync received so far. */
    private static final class PartialSync {
        final int syncId;
        final List<byte[]>[] records;
        final List<SyncWorldCollectionsPacket.Blob>[] blobs;
        int received;
        long bytes;

        @SuppressWarnings("unchecked")
        PartialSync(int syncId, int parts) {
            this.syncId = syncId;
            this.records = new List[parts];
            this.blobs = new List[parts];
        }
    }

    /** A file whose parts are arriving. */
    private static final class Assembly {
        final byte[] bytes;
        int received;

        Assembly(int size) {
            this.bytes = new byte[size];
        }
    }

    // What the last applied sync held
    private static final Map<String, WorldCollectionRecord> RECORDS = new LinkedHashMap<>();
    private static final Map<String, byte[]> RECORD_BYTES = new HashMap<>();
    private static Map<String, BlobInfo> blobs = Map.of();
    private static Set<String> missingOnServer = Set.of();
    private static Set<String> referenced = Set.of();
    // The ids this client registered, which it alone unregisters
    private static final Set<String> REGISTERED = new LinkedHashSet<>();
    private static boolean synced;
    private static int revision;
    private static int quotaUsedKiB;
    private static int quotaMaxKiB;

    // This connection
    private static boolean hasSession;
    private static long sessionNonce;
    @Nullable
    private static PartialSync partial;
    private static final Map<String, AssetState> ASSETS = new HashMap<>();
    private static final Map<String, Integer> REQUESTS = new HashMap<>();
    private static final Map<String, Assembly> ASSEMBLIES = new HashMap<>();
    private static long assemblyBytes;
    private static long bytesReceived;

    private static final ClientWorldAssets.Listener LISTENER = new ClientWorldAssets.Listener() {
        @Override
        public void unverified(String sha, String reason) {
            onUnverified(sha, reason);
        }

        @Override
        public void failed(String sha, String reason) {
            if (ASSETS.get(sha) == AssetState.LOADING) {
                ASSETS.put(sha, AssetState.FAILED);
            }
            BlockPopsMod.LOGGER.warn("Could not load World collection file {}: {}", sha, reason);
        }

        @Override
        public void loaded(String sha) {
            if (ASSETS.get(sha) == AssetState.LOADING) {
                ASSETS.put(sha, AssetState.LOADED);
            }
        }

        @Override
        public boolean wants(String sha) {
            return referenced.contains(sha) && ASSETS.get(sha) == AssetState.LOADING;
        }
    };

    private ClientWorldCollections() {
    }

    /** Receives one part of a sync, and applies the sync once all its parts are in. */
    public static void onSyncPart(SyncWorldCollectionsPacket part) {
        if (!hasSession || part.sessionNonce() != sessionNonce) {
            if (hasSession) {
                BlockPopsMod.LOGGER.info("World collections now come from another server session; forgetting the previous one");
            }
            forget();
            hasSession = true;
            sessionNonce = part.sessionNonce();
        }
        // A new sync id replaces whatever of an older sync was still incomplete
        if (partial == null || partial.syncId != part.syncId()) {
            partial = new PartialSync(part.syncId(), part.parts());
        }
        PartialSync sync = partial;
        if (sync.records.length != part.parts() || sync.records[part.part()] != null) {
            BlockPopsMod.LOGGER.warn("Refusing World collections sync {}: its parts disagree", part.syncId());
            partial = null;
            return;
        }
        for (byte[] record : part.records()) {
            sync.bytes += record.length;
        }
        sync.bytes += (long) part.blobs().size() * BLOB_ENTRY_BYTES;
        if (sync.bytes > SyncWorldCollectionsPacket.MAX_SYNC_BYTES) {
            BlockPopsMod.LOGGER.warn("Refusing World collections sync {}: larger than {} bytes", part.syncId(),
                    SyncWorldCollectionsPacket.MAX_SYNC_BYTES);
            partial = null;
            return;
        }
        sync.records[part.part()] = part.records();
        sync.blobs[part.part()] = part.blobs();
        sync.received++;
        if (sync.received < sync.records.length) {
            return;
        }
        partial = null;
        List<byte[]> records = new ArrayList<>();
        List<SyncWorldCollectionsPacket.Blob> table = new ArrayList<>();
        for (int i = 0; i < sync.records.length; i++) {
            records.addAll(sync.records[i]);
            table.addAll(sync.blobs[i]);
        }
        quotaUsedKiB = part.quotaUsedKiB();
        quotaMaxKiB = part.quotaMaxKiB();
        apply(records, table);
    }

    /** Checks a complete sync and, when every part of it passes, makes it the client's state. */
    private static void apply(List<byte[]> recordBytes, List<SyncWorldCollectionsPacket.Blob> table) {
        Map<String, BlobInfo> newBlobs = new HashMap<>();
        Set<String> newMissing = new HashSet<>();
        Map<String, WorldCollectionRecord> newRecords = new LinkedHashMap<>();
        Map<String, byte[]> newBytes = new HashMap<>();
        Map<String, FigureCollection> materialized = new HashMap<>();
        try {
            for (SyncWorldCollectionsPacket.Blob blob : table) {
                if (newBlobs.put(blob.sha(), blob.info()) != null) {
                    throw new IllegalStateException("file " + blob.sha() + " is listed twice");
                }
                if (blob.missing()) {
                    newMissing.add(blob.sha());
                }
            }
            for (byte[] bytes : recordBytes) {
                WorldCollectionRecord record = WorldCollectionRecord.parse(bytes, newBlobs);
                if (newRecords.put(record.id(), record) != null) {
                    throw new IllegalStateException("collection " + record.id() + " is listed twice");
                }
                newBytes.put(record.id(), bytes);
            }
            WorldCollectionRecord.checkBudgets(newRecords.values(), newBlobs);
            for (WorldCollectionRecord record : newRecords.values()) {
                FigureCollection collection = WorldCollectionMaterializer.materialize(record, newBlobs);
                String problem = WorldCollectionCheck.problem(collection, record);
                if (problem != null) {
                    throw new IllegalStateException("collection " + record.id() + " " + problem);
                }
                materialized.put(record.id(), collection);
            }
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Refusing the server's World collections; keeping the previous ones: {}",
                    e.getMessage());
            return;
        }

        // A server owns the World collections of its world; one it no longer sends must not linger.
        // In single player the integrated server registers them itself, in the same registry,
        // on its own thread, so this client leaves that registry alone.
        boolean ownsRegistry = !Minecraft.getInstance().hasSingleplayerServer();
        if (ownsRegistry) {
            // A plush World collection is registered apart from the boxed ones, so both are swept
            List<FigureCollection> registered = new ArrayList<>(CollectionRegistry.getAllCollections());
            registered.addAll(CollectionRegistry.getPlushCollections());
            for (FigureCollection collection : registered) {
                String id = collection.getId();
                if (WorldContentIds.isWorldCollectionId(id) && !REGISTERED.contains(id)) {
                    BlockPopsMod.LOGGER.warn("Removing World collection {} that this server did not send", id);
                    CollectionRegistry.unregisterDynamicCollection(id);
                }
            }
        }

        Set<String> newReferenced = new LinkedHashSet<>();
        for (WorldCollectionRecord record : newRecords.values()) {
            newReferenced.addAll(record.referencedShas());
        }
        Set<String> coming = new LinkedHashSet<>();
        for (String sha : newReferenced) {
            // A file the creator loaded from disk is the same file, so it keeps drawing until this copy lands
            if (ASSETS.get(sha) != AssetState.LOADED && !ClientPreviewAssets.holds(sha)) {
                coming.add(sha);
            }
        }
        // Announced before anything that names them is registered, so nothing draws a missing texture
        ClientWorldAssets.expect(coming, newBlobs::get);

        for (String id : List.copyOf(REGISTERED)) {
            if (!newRecords.containsKey(id)) {
                CollectionRegistry.unregisterDynamicCollection(id);
                REGISTERED.remove(id);
            }
        }
        if (ownsRegistry) {
            for (WorldCollectionRecord record : newRecords.values()) {
                String id = record.id();
                if (REGISTERED.contains(id) && Arrays.equals(RECORD_BYTES.get(id), newBytes.get(id))) {
                    continue;
                }
                // Unregistering first drops the figures an edit removed or renamed
                CollectionRegistry.unregisterDynamicCollection(id);
                CollectionRegistry.registerDynamicCollection(materialized.get(id));
                REGISTERED.add(id);
            }
        }

        Set<String> released = new HashSet<>(referenced);
        released.removeAll(newReferenced);
        RECORDS.clear();
        RECORDS.putAll(newRecords);
        RECORD_BYTES.clear();
        RECORD_BYTES.putAll(newBytes);
        blobs = Map.copyOf(newBlobs);
        missingOnServer = Set.copyOf(newMissing);
        referenced = Set.copyOf(newReferenced);

        requestMissing();

        if (!released.isEmpty()) {
            for (String sha : released) {
                ASSETS.remove(sha);
                dropAssembly(sha);
            }
            // A file the creator still shows stays loaded until the creator closes
            ClientWorldAssets.release(ClientPreviewAssets.adopt(released));
        }
        synced = true;
        revision++;
    }

    /** Asks for every file a collection names that the client has not asked for, in packets of at most 64. */
    private static void requestMissing() {
        List<WorldAssetRequestPacket.Request> requests = new ArrayList<>();
        for (String sha : referenced) {
            if (missingOnServer.contains(sha)) {
                // The server drops a request for a file it lost; once the file is uploaded
                // again, a later sync asks for it anew
                if (ASSETS.get(sha) == AssetState.REQUESTED && !ASSEMBLIES.containsKey(sha)) {
                    ASSETS.remove(sha);
                }
                continue;
            }
            if (ASSETS.containsKey(sha)) {
                continue;
            }
            int made = REQUESTS.getOrDefault(sha, 0);
            if (made >= MAX_REQUESTS) {
                // Released and named again after both copies were spent; it draws its fallback
                ASSETS.put(sha, AssetState.FAILED);
                continue;
            }
            // A file released earlier on this connection was already sent once, so the server
            // sends it again only as a retry
            requests.add(new WorldAssetRequestPacket.Request(sha, made > 0));
            REQUESTS.put(sha, made + 1);
            ASSETS.put(sha, AssetState.REQUESTED);
        }
        send(requests);
    }

    private static void send(List<WorldAssetRequestPacket.Request> requests) {
        for (int from = 0; from < requests.size(); from += WorldAssetRequestPacket.MAX_REQUESTS) {
            int to = Math.min(requests.size(), from + WorldAssetRequestPacket.MAX_REQUESTS);
            new WorldAssetRequestPacket(requests.subList(from, to)).sendToServer();
        }
    }

    /** Receives part of a file the client asked for. */
    public static void onAssetChunk(WorldAssetChunkPacket chunk) {
        // Every byte the server sent counts, used or not, so its window never closes for good
        bytesReceived += chunk.data().length;
        new WorldAssetAckPacket((int) Math.min(bytesReceived, Integer.MAX_VALUE)).sendToServer();

        String sha = chunk.sha();
        BlobInfo info = blobs.get(sha);
        if (ASSETS.get(sha) != AssetState.REQUESTED || info == null) {
            BlockPopsMod.LOGGER.warn("Ignoring World collection file {} that was not asked for", sha);
            return;
        }
        // The server sends a file's parts in order and finishes one before the next, so a part
        // that does not fit leaves nothing a second request could repair while the rest arrives
        Assembly assembly = ASSEMBLIES.get(sha);
        if (assembly == null && chunk.offset() != 0 && chunk.type() == info.type() && chunk.total() == info.size()) {
            // The rest of a copy that began while the file was released, whose first parts were
            // dropped then; the request made when it was named again has the server send it
            // whole once more after this copy
            BlockPopsMod.logDebug("Skipping the rest of an earlier copy of World collection file {}", sha);
            return;
        }
        String problem = null;
        if (chunk.type() != info.type() || chunk.total() != info.size()) {
            problem = "its type or size differs from the file table";
        } else if (assembly == null && assemblyBytes + info.size() > MAX_ASSEMBLY_BYTES) {
            problem = "too many files are arriving at once";
        } else if (assembly != null && chunk.offset() != assembly.received) {
            problem = "its parts arrived out of order";
        }
        if (problem != null) {
            dropAssembly(sha);
            ASSETS.put(sha, AssetState.FAILED);
            BlockPopsMod.LOGGER.warn("Giving up on World collection file {}: {}", sha, problem);
            return;
        }
        if (assembly == null) {
            assembly = new Assembly(info.size());
            ASSEMBLIES.put(sha, assembly);
            assemblyBytes += info.size();
        }
        System.arraycopy(chunk.data(), 0, assembly.bytes, assembly.received, chunk.data().length);
        assembly.received += chunk.data().length;
        if (assembly.received < assembly.bytes.length) {
            return;
        }
        dropAssembly(sha);
        ASSETS.put(sha, AssetState.LOADING);
        ClientWorldAssets.load(sha, info, assembly.bytes, LISTENER);
    }

    private static void dropAssembly(String sha) {
        Assembly assembly = ASSEMBLIES.remove(sha);
        if (assembly != null) {
            assemblyBytes -= assembly.bytes.length;
        }
    }

    /**
     * A complete copy of a file could not be verified: ask once more, or give up and keep
     * its fallback.
     */
    private static void onUnverified(String sha, String reason) {
        if (ASSETS.get(sha) != AssetState.LOADING) {
            return;
        }
        int made = REQUESTS.getOrDefault(sha, 0);
        if (made >= MAX_REQUESTS || !referenced.contains(sha) || missingOnServer.contains(sha)) {
            ASSETS.put(sha, AssetState.FAILED);
            BlockPopsMod.LOGGER.warn("World collection file {} failed verification: {}", sha, reason);
            return;
        }
        BlockPopsMod.LOGGER.warn("World collection file {} failed verification, asking again: {}", sha, reason);
        REQUESTS.put(sha, made + 1);
        ASSETS.put(sha, AssetState.REQUESTED);
        send(List.of(new WorldAssetRequestPacket.Request(sha, true)));
    }

    /**
     * Forgets everything the current server sent, unregisters the collections this client
     * registered, releases every World asset and drops the creator's previews. Runs when the
     * client leaves or joins a server, and when a sync comes from another session.
     */
    public static void forget() {
        REGISTERED.forEach(CollectionRegistry::unregisterDynamicCollection);
        REGISTERED.clear();
        ClientWorldAssets.forget();
        ClientPreviewAssets.forget();
        CreatorPreview.clearAll();
        RECORDS.clear();
        RECORD_BYTES.clear();
        blobs = Map.of();
        missingOnServer = Set.of();
        referenced = Set.of();
        partial = null;
        ASSETS.clear();
        REQUESTS.clear();
        ASSEMBLIES.clear();
        assemblyBytes = 0;
        bytesReceived = 0;
        hasSession = false;
        sessionNonce = 0;
        quotaUsedKiB = 0;
        quotaMaxKiB = 0;
        synced = false;
        revision++;
    }

    /** Whether this server has sent its World collections, which also tells it offers them. */
    public static boolean isSynced() {
        return synced;
    }

    /** Changes every time the collections change, so a screen knows to rebuild. */
    public static int revision() {
        return revision;
    }

    /** Whether the World collection is a draft: defined, but offered nowhere until it is published. */
    public static boolean isDraft(String collectionId) {
        WorldCollectionRecord record = RECORDS.get(collectionId);
        return record != null && record.draft();
    }

    /**
     * Whether a machine may offer the collection: every collection that is not a World
     * collection, and a World collection the server sent that is neither archived nor a draft.
     */
    public static boolean isOffered(String collectionId) {
        WorldCollectionRecord record = RECORDS.get(collectionId);
        if (record == null) {
            // In single player the integrated server registers drafts it sends only to operators
            return !WorldContentIds.isWorldCollectionId(collectionId);
        }
        return !record.archived() && !record.draft();
    }

    /** When the World collection was last edited, in epoch seconds, or 0 when unknown. */
    public static long editedAt(String collectionId) {
        WorldCollectionRecord record = RECORDS.get(collectionId);
        return record != null ? record.editedAt() : 0L;
    }

    /** The records of this server's World collections, in the order it sent them. */
    public static List<WorldCollectionRecord> records() {
        return List.copyOf(RECORDS.values());
    }

    @Nullable
    public static WorldCollectionRecord record(String collectionId) {
        return RECORDS.get(collectionId);
    }

    /** The position of a World collection in the server's order, or -1. */
    public static int order(String collectionId) {
        int index = 0;
        for (String id : RECORDS.keySet()) {
            if (id.equals(collectionId)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** The file table entry of a file a collection names, or null. */
    @Nullable
    public static BlobInfo blob(String sha) {
        return Sha256.isValidHex(sha) ? blobs.get(sha) : null;
    }

    /** The whole file table of the last applied sync, for forecasting a change against the budgets. */
    public static Map<String, BlobInfo> blobs() {
        return blobs;
    }

    /** Whether the server has lost the file and waits for an operator to upload it again. */
    public static boolean isMissingOnServer(String sha) {
        return missingOnServer.contains(sha);
    }

    /** Whether any file the collection names is lost on the server. */
    public static boolean hasMissingFiles(String collectionId) {
        WorldCollectionRecord record = RECORDS.get(collectionId);
        return record != null && record.referencedShas().stream().anyMatch(missingOnServer::contains);
    }

    /** Whether the file arrived and is loaded, or was refused because a bundled resource has its id. */
    public static boolean isLoaded(String sha) {
        return ASSETS.get(sha) == AssetState.LOADED;
    }

    /** Whether the file arrived but could not be verified or loaded, so its fallback draws. */
    public static boolean failedToLoad(String sha) {
        return ASSETS.get(sha) == AssetState.FAILED;
    }

    /** Stored kibibytes of this server's World collection files. */
    public static int quotaUsedKiB() {
        return quotaUsedKiB;
    }

    /** The most kibibytes this server stores. */
    public static int quotaMaxKiB() {
        return quotaMaxKiB;
    }
}
