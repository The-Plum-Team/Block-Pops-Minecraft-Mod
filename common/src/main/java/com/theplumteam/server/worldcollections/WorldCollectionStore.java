package com.theplumteam.server.worldcollections;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.JsonFields;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionCheck;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The World collections of one world on disk: their records and the table of every
 * stored file in data/blockpops-world-collections.json, and the files themselves,
 * named by their SHA-256, in data/blockpops-world-collections/blobs/.
 *
 * <p>A file is written first, through a fresh staging file moved into place in one step,
 * and the metadata second, so the metadata never names a file that was not written. The
 * metadata is replaced the same way, after the current copy is kept as `.bak`.
 *
 * <p>At load, when the metadata file cannot be read but `.bak` can, the unreadable file is
 * set aside as `.corrupt` and the backup is used. When neither can be read, both stay where
 * they are; when neither exists but a `.corrupt` copy does, nothing was ever recovered from
 * it. In those cases, or when what was read breaks a cap or a budget, the store is
 * degraded: it serves what it has, collects no files and refuses every change until an
 * administrator fixes the files (deleting the `.corrupt` copy to start empty) and restarts.
 * Every file named in the table is hashed and checked again; one that is missing or
 * differs is moved to `corrupt/` and marked missing until it is uploaded again.
 *
 * <p>The state is an immutable {@link State}: a change builds the next one, which takes
 * effect only once it is saved. The state and the missing set belong to the server
 * thread; {@link #writeBlob}, {@link #readBlob} and {@link #deleteBlobFiles} only touch
 * files and may run on the World content executor.
 */
public final class WorldCollectionStore {
    public static final String FILE_NAME = "blockpops-world-collections.json";
    private static final String ROOT_NAME = "blockpops-world-collections";
    private static final String BLOBS = "blobs";
    private static final String STAGING = "staging";
    private static final String CORRUPT = "corrupt";
    /** Unreferenced files younger than this are kept, so an upload can still be used. */
    public static final long GC_GRACE_MILLIS = 24L * 60 * 60 * 1000;
    /** Entries looked at when sweeping a directory, so a flooded directory cannot stall the start. */
    private static final int MAX_SWEPT_ENTRIES = 4096;
    private static final int FORMAT = 1;
    private static final Set<String> KEYS = Set.of("format", "collections", "retired_ids", "blobs");
    private static final Set<String> COLLECTION_KEYS = Set.of("record", "created_by", "created_at");
    private static final Set<String> BLOB_STORE_KEYS = Set.of("added", "by");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    /** Who created what the server console or a datapack import made. */
    public static final UUID NOBODY = new UUID(0L, 0L);

    /** A stored file's table entry: what validation learned, and when and by whom it was added. */
    public record StoredBlob(BlobInfo info, long added, UUID by) {
    }

    /** A collection with who created it and when; neither is ever sent to players. */
    public record StoredCollection(WorldCollectionRecord record, UUID createdBy, long createdAt) {
    }

    /** Everything the metadata file holds. */
    public record State(Map<String, StoredCollection> collections, Set<String> retiredIds,
                        Map<String, StoredBlob> blobs) {
        public static final State EMPTY = new State(Map.of(), Set.of(), Map.of());

        public State {
            collections = Collections.unmodifiableMap(new LinkedHashMap<>(collections));
            retiredIds = Collections.unmodifiableSet(new LinkedHashSet<>(retiredIds));
            blobs = Collections.unmodifiableMap(new TreeMap<>(blobs));
        }

        public List<WorldCollectionRecord> records() {
            List<WorldCollectionRecord> records = new ArrayList<>(collections.size());
            collections.values().forEach(collection -> records.add(collection.record()));
            return records;
        }

        @Nullable
        public WorldCollectionRecord record(String id) {
            StoredCollection collection = collections.get(id);
            return collection != null ? collection.record() : null;
        }

        /** The blob table as both sides check records against it. */
        public Map<String, BlobInfo> blobInfos() {
            Map<String, BlobInfo> infos = new HashMap<>();
            blobs.forEach((sha, blob) -> infos.put(sha, blob.info()));
            return infos;
        }

        /** Bytes of every stored file, referenced or not. */
        public long storedBytes() {
            long bytes = 0;
            for (StoredBlob blob : blobs.values()) {
                bytes += blob.info().size();
            }
            return bytes;
        }

        /** Every sha some record names, archived ones included. */
        public Set<String> referencedShas() {
            Set<String> shas = new LinkedHashSet<>();
            collections.values().forEach(collection -> shas.addAll(collection.record().referencedShas()));
            return shas;
        }

        /** This state with the collection added or replaced, keeping its place. */
        public State withCollection(StoredCollection collection) {
            Map<String, StoredCollection> next = new LinkedHashMap<>(collections);
            next.put(collection.record().id(), collection);
            return new State(next, retiredIds, blobs);
        }

        /** This state without the collection, whose id can never be used again. */
        public State purged(String id) {
            Map<String, StoredCollection> next = new LinkedHashMap<>(collections);
            next.remove(id);
            Set<String> retired = new LinkedHashSet<>(retiredIds);
            retired.add(id);
            return new State(next, retired, blobs);
        }

        public State withBlobs(Map<String, StoredBlob> next) {
            return new State(collections, retiredIds, next);
        }
    }

    private final Path dataDirectory;
    private final Path root;
    private State state = State.EMPTY;
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private boolean degraded;
    private boolean directoriesReady;

    private WorldCollectionStore(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.root = this.dataDirectory.resolve(ROOT_NAME);
    }

    /** Loads the store of the world whose data directory is given. Server thread, at start. */
    public static WorldCollectionStore load(Path dataDirectory) {
        WorldCollectionStore store = new WorldCollectionStore(dataDirectory);
        store.load();
        return store;
    }

    public State state() {
        return state;
    }

    /** Whether changes are refused for this server run, because the store could not be read in full. */
    public boolean isDegraded() {
        return degraded;
    }

    /** Whether a file the table names is absent or failed its check, so it must be uploaded again. */
    public boolean isMissing(String sha) {
        return missing.contains(sha);
    }

    public Set<String> missing() {
        return Collections.unmodifiableSet(missing);
    }

    /**
     * Saves the next state and makes it current.
     *
     * @return false if it could not be saved, in which case the current state is unchanged
     */
    public boolean save(State next) {
        if (degraded) {
            return false;
        }
        byte[] bytes = GSON.toJson(toJson(next)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WorldContentLimits.STORE_JSON_BYTES) {
            BlockPopsMod.LOGGER.error("Not saving the World collections: the metadata would be {} bytes, more than {}",
                    bytes.length, WorldContentLimits.STORE_JSON_BYTES);
            return false;
        }
        try {
            Files.createDirectories(dataDirectory);
            Path file = RemotePaths.resolveInside(dataDirectory, FILE_NAME);
            Path temporary = RemotePaths.resolveInside(dataDirectory, FILE_NAME + ".tmp");
            Path backup = RemotePaths.resolveInside(dataDirectory, FILE_NAME + ".bak");
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                // Keep the last good copy, which load falls back to when the main file cannot be read
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            move(temporary, file);
            state = next;
            return true;
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to save the World collections {}", dataDirectory.resolve(FILE_NAME), e);
            return false;
        }
    }

    /** Marks a file as present again once an upload or import has written it. */
    public void markPresent(String sha) {
        missing.remove(sha);
    }

    /**
     * Marks a file the table names as missing because it could no longer be read while the
     * server runs, so it can be uploaded again. Server thread.
     *
     * @return whether it was not marked before
     */
    public boolean markMissing(String sha) {
        if (!state.blobs().containsKey(sha) || !missing.add(sha)) {
            return false;
        }
        BlockPopsMod.LOGGER.warn("World collection file {} could not be read and must be uploaded again", sha);
        return true;
    }

    /**
     * Writes a file that has passed its check under its sha, replacing any copy that is
     * there. Any thread.
     */
    public void writeBlob(String sha, byte[] bytes) throws IOException {
        requireSha(sha);
        Path staging = directory(STAGING);
        directory(BLOBS);
        Path target = RemotePaths.resolveInside(root, BLOBS + "/" + sha);
        Path temporary = staging.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            move(temporary, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Reads a stored file, refusing one larger than maxBytes or whose bytes no longer
     * match its sha. Any thread.
     */
    public byte[] readBlob(String sha, int maxBytes) throws IOException {
        requireSha(sha);
        Path file = RemotePaths.resolveInside(root, BLOBS + "/" + sha);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) {
            throw new IOException("Stored file " + sha + " is larger than " + maxBytes + " bytes");
        }
        if (!Sha256.of(bytes).equals(sha)) {
            throw new IOException("Stored file " + sha + " no longer matches its SHA-256");
        }
        return bytes;
    }

    /** Deletes stored files the table no longer names. Any thread. */
    public void deleteBlobFiles(Collection<String> shas) {
        for (String sha : shas) {
            try {
                Files.deleteIfExists(RemotePaths.resolveInside(root, BLOBS + "/" + requireSha(sha)));
            } catch (IOException e) {
                BlockPopsMod.LOGGER.warn("Could not delete the unused World collection file {}: {}", sha, e.getMessage());
            }
        }
    }

    /**
     * Clears what an interrupted run may have left: staging files, and stored files the
     * table does not name that are older than the grace period. Server thread, at start,
     * before any upload can be in flight.
     */
    public void sweep(long now) {
        if (degraded || !directoriesReady) {
            return;
        }
        int deleted = 0;
        for (Path file : list(STAGING)) {
            try {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                BlockPopsMod.LOGGER.warn("Could not delete the World collection staging file {}: {}",
                        file.getFileName(), e.getMessage());
            }
        }
        for (Path file : list(BLOBS)) {
            String name = file.getFileName().toString();
            if (!Sha256.isValidHex(name) || state.blobs().containsKey(name)) {
                continue;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && now - attributes.lastModifiedTime().toMillis() > GC_GRACE_MILLIS
                        && Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                BlockPopsMod.LOGGER.warn("Could not delete the unlisted World collection file {}: {}", name, e.getMessage());
            }
        }
        if (deleted > 0) {
            BlockPopsMod.LOGGER.info("Removed {} leftover World collection files", deleted);
        }
    }

    private List<Path> list(String directory) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory(directory))) {
            for (Path file : stream) {
                if (files.size() >= MAX_SWEPT_ENTRIES) {
                    break;
                }
                files.add(file);
            }
        } catch (IOException e) {
            BlockPopsMod.LOGGER.warn("Could not list the World collection directory {}: {}", directory, e.getMessage());
        }
        return files;
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        try {
            directory(BLOBS);
            directory(STAGING);
            directory(CORRUPT);
            directoriesReady = true;
        } catch (IOException e) {
            problems.add(root + ": " + e.getMessage());
        }

        Path file = dataDirectory.resolve(FILE_NAME);
        Path backup = dataDirectory.resolve(FILE_NAME + ".bak");
        Path corrupt = dataDirectory.resolve(FILE_NAME + ".corrupt");
        State loaded = null;
        boolean readFailed = false;
        boolean mainFailed = false;
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                loaded = read(FILE_NAME);
            } catch (IOException | WorldContentException e) {
                readFailed = true;
                mainFailed = true;
                problems.add(file + ": " + e.getMessage());
            }
        }
        if (loaded == null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            try {
                loaded = read(FILE_NAME + ".bak");
                BlockPopsMod.LOGGER.warn("Loaded the World collections from the backup {}", backup);
            } catch (IOException | WorldContentException e) {
                readFailed = true;
                problems.add(backup + ": " + e.getMessage());
            }
        }
        if (loaded != null && mainFailed) {
            // The backup is in use and the next save replaces the main file, so keep what it held
            setAside(file);
        }
        if (loaded == null && !readFailed && Files.exists(corrupt, LinkOption.NOFOLLOW_LINKS)) {
            // An earlier start set an unreadable file aside without recovering anything, so an
            // empty store here means lost metadata, not a new world
            readFailed = true;
            problems.add(corrupt + " is the only copy of the metadata; fix it into " + FILE_NAME
                    + ", or delete it to start empty");
        }
        if (loaded == null && readFailed) {
            degraded = true;
        }
        state = loaded != null ? loaded : State.EMPTY;

        List<String> capProblems = capProblems(state);
        if (!capProblems.isEmpty()) {
            degraded = true;
            problems.addAll(capProblems);
        }
        if (!directoriesReady) {
            degraded = true;
            missing.addAll(state.blobs().keySet());
        } else {
            verifyBlobs();
        }
        if (degraded) {
            BlockPopsMod.LOGGER.error("The World collections of this world could not be read in full, so they cannot be "
                    + "changed until the files are fixed or restored and the server restarts: {}", String.join("; ", problems));
        } else if (!problems.isEmpty()) {
            BlockPopsMod.LOGGER.warn("World collections: {}", String.join("; ", problems));
        }
        if (!missing.isEmpty()) {
            BlockPopsMod.LOGGER.warn("{} World collection files are missing or damaged and must be uploaded again; "
                    + "damaged copies are in {}", missing.size(), root.resolve(CORRUPT));
        }
    }

    /** Sets an unreadable metadata file aside, so the next save does not overwrite what it held. */
    private void setAside(Path file) {
        Path corrupt = file.resolveSibling(FILE_NAME + ".corrupt");
        try {
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
            BlockPopsMod.LOGGER.error("Kept the unreadable World collections file as {}", corrupt);
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Could not set the unreadable World collections file aside", e);
        }
    }

    /**
     * Hashes and checks every file the table names, setting aside each one that fails. A
     * model's bounds are derived data, so its entry takes the bounds computed from the file
     * rather than failing over stored ones that are absent or were computed differently.
     */
    private void verifyBlobs() {
        Map<String, StoredBlob> derived = new TreeMap<>(state.blobs());
        boolean rederived = false;
        for (Map.Entry<String, StoredBlob> entry : state.blobs().entrySet()) {
            String sha = entry.getKey();
            BlobInfo expected = entry.getValue().info();
            Path file = root.resolve(BLOBS).resolve(sha);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                missing.add(sha);
                continue;
            }
            String problem;
            try {
                byte[] bytes = readBlob(sha, expected.type().maxBytes());
                BlobInfo actual = expected.type().validate(bytes);
                problem = actual.sameFile(expected) ? null : "differs from its table entry";
                if (problem == null && !actual.bounds().equals(expected.bounds())) {
                    StoredBlob stored = entry.getValue();
                    derived.put(sha, new StoredBlob(actual, stored.added(), stored.by()));
                    rederived = true;
                }
            } catch (IOException | WorldContentException e) {
                problem = e.getMessage();
            }
            if (problem != null) {
                BlockPopsMod.LOGGER.warn("World collection file {} failed its check: {}", sha, problem);
                missing.add(sha);
                quarantine(sha, file);
            }
        }
        if (rederived) {
            // Only derived data changed, so the next save writes it and nothing is lost meanwhile
            state = state.withBlobs(derived);
        }
    }

    private void quarantine(String sha, Path file) {
        try {
            directory(CORRUPT);
            // Moving a link moves the link itself, never what it points to
            Files.move(file, root.resolve(CORRUPT).resolve(sha), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BlockPopsMod.LOGGER.warn("Could not set the damaged World collection file {} aside: {}", sha, e.getMessage());
        }
    }

    private State read(String name) throws IOException, WorldContentException {
        Path file = RemotePaths.resolveInside(dataDirectory, name);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(WorldContentLimits.STORE_JSON_BYTES + 1);
        }
        return parse(JsonFields.parse(bytes, WorldContentLimits.STORE_JSON_BYTES));
    }

    /** Reads the metadata schema. Anything malformed refuses the whole file. */
    private static State parse(JsonObject json) throws WorldContentException {
        JsonFields.keys(json, "store", KEYS, Set.of());
        if (JsonFields.integer(json.get("format"), "format", 0, Integer.MAX_VALUE) != FORMAT) {
            throw new WorldContentException("Unknown store format");
        }
        Map<String, StoredBlob> blobs = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : JsonFields.object(json.get("blobs"), "blobs").entrySet()) {
            String sha = Sha256.require(entry.getKey(), "file name");
            JsonObject blob = JsonFields.object(entry.getValue(), "file " + sha);
            BlobInfo info = BlobInfo.readJson(blob, BLOB_STORE_KEYS);
            long added = JsonFields.integer(blob.get("added"), "added", 0, Long.MAX_VALUE);
            blobs.put(sha, new StoredBlob(info, added, uuid(blob.get("by"), "by")));
        }
        Map<String, BlobInfo> infos = new HashMap<>();
        blobs.forEach((sha, blob) -> infos.put(sha, blob.info()));

        Set<String> retired = new LinkedHashSet<>();
        for (JsonElement element : JsonFields.array(json.get("retired_ids"), "retired_ids")) {
            String id = JsonFields.string(element, "retired id", 32);
            if (!WorldContentIds.isWorldCollectionId(id) || !retired.add(id)) {
                throw new WorldContentException("Invalid or repeated retired id " + WorldContentException.excerpt(id));
            }
        }

        Map<String, StoredCollection> collections = new LinkedHashMap<>();
        for (JsonElement element : JsonFields.array(json.get("collections"), "collections")) {
            JsonObject collection = JsonFields.object(element, "collection");
            JsonFields.keys(collection, "collection", COLLECTION_KEYS, Set.of());
            WorldCollectionRecord record = WorldCollectionRecord.fromJson(
                    JsonFields.object(collection.get("record"), "record"), infos);
            UUID createdBy = uuid(collection.get("created_by"), "created_by");
            long createdAt = JsonFields.integer(collection.get("created_at"), "created_at", 0, Long.MAX_VALUE);
            if (collections.put(record.id(), new StoredCollection(record, createdBy, createdAt)) != null) {
                throw new WorldContentException("Collection " + record.id() + " is stored twice");
            }
        }
        return new State(collections, retired, blobs);
    }

    /** The caps and budgets a readable store still breaks, which leave it degraded. */
    private static List<String> capProblems(State state) {
        List<String> problems = new ArrayList<>();
        if (state.retiredIds().size() > WorldContentLimits.MAX_RETIRED_IDS) {
            problems.add(state.retiredIds().size() + " purged ids, more than " + WorldContentLimits.MAX_RETIRED_IDS);
        }
        if (state.blobs().size() > WorldContentLimits.MAX_BLOBS) {
            problems.add(state.blobs().size() + " files, more than " + WorldContentLimits.MAX_BLOBS);
        }
        if (state.storedBytes() > WorldContentLimits.MAX_STORED_BYTES) {
            problems.add(state.storedBytes() + " stored bytes, more than " + WorldContentLimits.MAX_STORED_BYTES);
        }
        Map<String, BlobInfo> infos = state.blobInfos();
        try {
            WorldCollectionRecord.checkBudgets(state.records(), infos);
        } catch (WorldContentException e) {
            problems.add(e.getMessage());
        }
        for (WorldCollectionRecord record : state.records()) {
            if (state.retiredIds().contains(record.id())) {
                problems.add(record.id() + " is both stored and purged");
            }
            String problem = WorldCollectionCheck.problem(WorldCollectionMaterializer.materialize(record, infos), record);
            if (problem != null) {
                problems.add(record.id() + " " + problem);
            }
        }
        return problems;
    }

    private static JsonObject toJson(State state) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        JsonArray collections = new JsonArray();
        for (StoredCollection collection : state.collections().values()) {
            JsonObject entry = new JsonObject();
            entry.add("record", collection.record().toJson());
            entry.addProperty("created_by", collection.createdBy().toString());
            entry.addProperty("created_at", collection.createdAt());
            collections.add(entry);
        }
        json.add("collections", collections);
        JsonArray retired = new JsonArray();
        state.retiredIds().forEach(retired::add);
        json.add("retired_ids", retired);
        JsonObject blobs = new JsonObject();
        state.blobs().forEach((sha, blob) -> {
            JsonObject entry = new JsonObject();
            blob.info().writeJson(entry);
            entry.addProperty("added", blob.added());
            entry.addProperty("by", blob.by().toString());
            blobs.add(sha, entry);
        });
        json.add("blobs", blobs);
        return json;
    }

    private static UUID uuid(JsonElement element, String what) throws WorldContentException {
        String text = JsonFields.string(element, what, 36);
        try {
            UUID uuid = UUID.fromString(text);
            if (uuid.toString().equals(text)) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // Reported below
        }
        throw new WorldContentException(what + " is not a UUID");
    }

    /** Creates a directory of the store, refusing one that is a link or a special file. */
    private Path directory(String name) throws IOException {
        Files.createDirectories(root.resolve(name));
        for (Path path : List.of(root, root.resolve(name))) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Refusing link or special file at " + path.getFileName());
            }
        }
        return root.resolve(name);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
            // Not every file system offers the atomic swap, and Windows can deny it
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireSha(String sha) throws IOException {
        if (!Sha256.isValidHex(sha)) {
            throw new IOException("Not a SHA-256 file name");
        }
        return sha;
    }
}
