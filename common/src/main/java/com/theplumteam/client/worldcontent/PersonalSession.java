package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.WorldCollectionStatusPacket;
import com.theplumteam.server.worldcollections.WorldCollectionStore;
import com.theplumteam.server.worldcollections.WorldCollections;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentStatus;
import com.theplumteam.worldcontent.WorldContentStatus.Result;
import net.minecraft.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The creator working on a player's own drafts instead of a server's World collections: the
 * same screens and the same saving, with the player's own disk at the other end.
 *
 * <p>The drafts are kept in a store of their own, in the game folder, of the very kind a server
 * keeps its World collections in. While a session is open it stands in for the server: the
 * creator's saves, which would travel as packets, are handed to it instead and applied to that
 * store by the code a server applies them with, so a draft is checked, bounded and stored
 * exactly as a World collection is. Nothing of it reaches the server the player is on, and
 * nothing is registered for the game: the creator shows a draft through its own previews.
 *
 * <p>One session at a time, and never beside a World session: opening one is refused while a
 * World save is on its way, and it takes the creator's savers and runs for itself until it is
 * closed. Client thread only.
 */
public final class PersonalSession implements ClientWorldUploads.Host {
    private static final String DIRECTORY = "creator";
    @Nullable
    private static PersonalSession active;

    /** Puts a stored file where the creator's previews find it, and takes it out again. */
    interface Assets {
        void load(String sha, BlobInfo info, byte[] bytes, ClientWorldAssets.Listener listener);

        void release(Set<String> shas);
    }

    /** The runtime registries, as the files of World collections are loaded into them. */
    static final Assets GAME_ASSETS = new Assets() {
        @Override
        public void load(String sha, BlobInfo info, byte[] bytes, ClientWorldAssets.Listener listener) {
            ClientWorldAssets.load(sha, info, bytes, listener);
        }

        @Override
        public void release(Set<String> shas) {
            ClientWorldAssets.release(shas);
        }
    };

    // The game always uses GAME_ASSETS; only the unit tests, which have no renderer, put a stand-in in its place
    static Assets assetsHost = GAME_ASSETS;

    private final WorldCollectionStore store;
    private final UUID owner;
    private final Map<Integer, Upload> uploads = new HashMap<>();
    private final Deque<WorldCollectionStatusPacket> answers = new ArrayDeque<>();
    /** The files this session put in the runtime registries, and whether each could be loaded. */
    private final Map<String, Boolean> assets = new HashMap<>();
    private int nextUpload = 1;
    /**
     * What the creator reads: the store as it was when the last answer was handed over. A server
     * answers a save first and syncs what it changed afterwards, and the creator's saving counts
     * on that order, so a change shows here only once its answer was heard.
     */
    private WorldCollectionStore.State shown;
    private boolean changed;
    private int revision;

    private record Upload(int requestId, BlobType type, String sha, byte[] bytes, int[] received) {
    }

    private final WorldCollections.Effects effects = new WorldCollections.Effects() {
        @Override
        public void registered(WorldCollectionRecord record, Map<String, BlobInfo> blobs) {
            changed();
        }

        @Override
        public void unregistered(String id) {
            changed();
        }

        @Override
        public void changed() {
            changed = true;
        }

        @Override
        public void filesChanged() {
            changed = true;
        }
    };

    private PersonalSession(WorldCollectionStore store, UUID owner) {
        this.store = store;
        this.owner = owner;
        this.shown = store.state();
    }

    // Lets the creator see what the store holds now, as a sync from a server would
    private void sync() {
        changed = false;
        shown = store.state();
        revision++;
        loadAssets();
    }

    /** Whether the creator is working on the player's own drafts right now. */
    public static boolean isActive() {
        return active != null;
    }

    @Nullable
    static PersonalSession active() {
        return active;
    }

    /**
     * Opens the player's drafts for the creator.
     *
     * @param gameDirectory the game folder, where the drafts are kept below {@code blockpops/creator}
     * @param owner         the player, recorded as who made each draft
     * @return false when it cannot be opened now: a World save is on its way, or the drafts cannot be read
     */
    public static boolean open(Path gameDirectory, UUID owner) {
        if (active != null) {
            return true;
        }
        if (ClientWorldUploads.isBusy() || !CreatorAutosave.savingIds().isEmpty()) {
            return false;
        }
        WorldCollectionStore store = WorldCollectionStore.load(gameDirectory.resolve("blockpops").resolve(DIRECTORY));
        if (store.isDegraded()) {
            BlockPopsMod.LOGGER.warn("The creator's drafts on this computer could not be read");
            return false;
        }
        // Whatever the World creator left queued or attached belongs to the server, not to the drafts
        ClientWorldUploads.reset();
        PersonalSession session = new PersonalSession(store, owner);
        active = session;
        ClientWorldUploads.host = session;
        session.sync();
        return true;
    }

    /** Closes the session: the creator's saving goes back to the server, and the drafts' assets are released. */
    public static void close() {
        PersonalSession session = active;
        if (session == null) {
            return;
        }
        ClientWorldUploads.reset();
        ClientWorldUploads.host = ClientWorldUploads.GAME;
        active = null;
        // A file a World collection of this server also names stays loaded for it
        Set<String> mine = new HashSet<>(session.assets.keySet());
        mine.removeIf(ClientWorldCollections::isLoaded);
        assetsHost.release(mine);
        session.store.saveUnsavedBlobs();
    }

    /**
     * Closes the session once the creator is left: back in the game, or back in the settings it
     * was opened from. Every client tick of the game; a screen the creator opened over itself,
     * as the question before a link, keeps it open.
     */
    public static void watch(@Nullable Object screen, boolean isSettings) {
        if (active != null && (screen == null || isSettings)) {
            close();
        }
    }

    /** Hands the creator the answers to what it sent. Every client tick, before the uploads' own. */
    public static void tick() {
        PersonalSession session = active;
        if (session == null) {
            return;
        }
        // Each answer may make the creator send the next step, whose answer waits for the next tick
        int pending = session.answers.size();
        for (int i = 0; i < pending && active == session; i++) {
            ClientWorldUploads.onStatus(session.answers.poll());
        }
        if (active == session && session.changed) {
            session.sync();
        }
    }

    // ---- What the creator reads

    int revision() {
        return revision;
    }

    List<WorldCollectionRecord> records() {
        return shown.records();
    }

    @Nullable
    WorldCollectionRecord record(String id) {
        return shown.record(id);
    }

    @Nullable
    BlobInfo blob(String sha) {
        return Sha256.isValidHex(sha) ? shown.blobInfos().get(sha) : null;
    }

    Map<String, BlobInfo> blobs() {
        return shown.blobInfos();
    }

    boolean isMissing(String sha) {
        return store.isMissing(sha);
    }

    boolean isLoaded(String sha) {
        return Boolean.TRUE.equals(assets.get(sha));
    }

    boolean failedToLoad(String sha) {
        return Boolean.FALSE.equals(assets.get(sha));
    }

    int usedKiB() {
        return (int) Math.min(Integer.MAX_VALUE, shown.storedBytes() / 1024);
    }

    /** The bytes of a stored file, for publishing it; null when it cannot be read. */
    @Nullable
    byte[] bytes(String sha) {
        BlobInfo info = blob(sha);
        if (info == null) {
            return null;
        }
        try {
            return store.readBlob(sha, info.type().maxBytes());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Taking a whole collection in

    /**
     * One file of a collection that is taken in whole.
     *
     * @param type  what it says it is
     * @param bytes its bytes, which are checked as an upload's are
     */
    public record Taken(BlobType type, byte[] bytes) {
    }

    /**
     * Takes a whole collection in as a new draft, as when its owner brings back one they
     * published: each file is checked and stored as an uploaded one is, and the draft is made of
     * the record with the slug given, checked as any changed draft is. Nothing is kept of a
     * collection that is refused but files no draft names, which are swept as any others are.
     *
     * @param files every file the record names, by its SHA-256
     * @return {@link Result#OK} with the draft's id, or why it was not taken in; a refusal when
     *         no session is open
     */
    public static WorldContentStatus adopt(String slug, WorldCollectionRecord record, Map<String, Taken> files) {
        PersonalSession session = active;
        if (session == null) {
            return WorldContentStatus.of(Result.DEGRADED);
        }
        for (String sha : record.referencedShas()) {
            Taken file = files.get(sha);
            if (file == null) {
                return WorldContentStatus.of(Result.MISSING_ASSET, "A file it names is not at hand: " + sha);
            }
            WorldContentStatus refused = session.take(sha, file);
            if (refused != null) {
                return refused;
            }
        }
        session.store.saveUnsavedBlobs();
        WorldContentStatus status = WorldCollections.adopt(session.store, slug, record, session.owner, session.effects);
        return status.result() == Result.OK
                ? WorldContentStatus.of(Result.OK, WorldContentIds.collectionId(slug)) : status;
    }

    // Stores one file as a finished upload is stored; null when it is in the store afterwards
    @Nullable
    private WorldContentStatus take(String sha, Taken file) {
        WorldContentStatus refused = WorldCollections.checkUpload(store, sha, file.type(), file.bytes().length);
        if (refused != null) {
            return refused.result() == Result.ALREADY_STORED ? null : refused;
        }
        try {
            if (!JdkSha256.of(file.bytes()).equals(sha)) {
                throw new WorldContentException("The file does not match its SHA-256");
            }
            BlobInfo info = file.type().validate(file.bytes());
            store.writeBlob(sha, file.bytes());
            WorldContentStatus status = WorldCollections.addBlob(store, sha, info, owner, effects);
            return status.result() == Result.STORED ? null : status;
        } catch (WorldContentException e) {
            return WorldContentStatus.invalid(e);
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to write the draft file {}", sha, e);
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
    }

    // Puts every file a draft names in the runtime registries, where the creator's previews find it
    private void loadAssets() {
        Map<String, BlobInfo> table = shown.blobInfos();
        for (WorldCollectionRecord record : shown.records()) {
            for (String sha : record.referencedShas()) {
                BlobInfo info = table.get(sha);
                if (info == null || assets.containsKey(sha) || store.isMissing(sha)) {
                    continue;
                }
                // Claimed at once, so it is read and decoded once however often this runs meanwhile
                assets.put(sha, null);
                byte[] bytes = bytes(sha);
                if (bytes == null) {
                    assets.put(sha, Boolean.FALSE);
                    continue;
                }
                assetsHost.load(sha, info, bytes, new ClientWorldAssets.Listener() {
                    @Override
                    public void unverified(String file, String reason) {
                        failed(file, reason);
                    }

                    @Override
                    public void failed(String file, String reason) {
                        BlockPopsMod.LOGGER.warn("Could not load the draft file {}: {}", file, reason);
                        assets.put(file, Boolean.FALSE);
                    }

                    @Override
                    public void loaded(String file) {
                        assets.put(file, Boolean.TRUE);
                    }

                    @Override
                    public boolean wants(String file) {
                        return active == PersonalSession.this;
                    }
                });
            }
        }
    }

    // ---- What the creator sends: the server's part of each step, done here

    @Override
    public long millis() {
        return Util.getMillis();
    }

    @Override
    public boolean connected() {
        return true;
    }

    @Override
    public boolean unlimited() {
        return true;
    }

    @Override
    public void begin(int requestId, BlobType type, int size, String sha) {
        WorldContentStatus refused = WorldCollections.checkUpload(store, sha, type, size);
        if (refused != null) {
            answer(requestId, refused, 0, sha);
            return;
        }
        int id = nextUpload++;
        uploads.put(id, new Upload(requestId, type, sha, new byte[size], new int[1]));
        answer(requestId, WorldContentStatus.of(Result.READY), id, sha);
    }

    @Override
    public void chunk(int uploadId, int offset, byte[] bytes) {
        Upload upload = uploads.get(uploadId);
        if (upload == null) {
            return;
        }
        if (offset != upload.received()[0] || bytes.length == 0 || bytes.length > upload.bytes().length - offset) {
            uploads.remove(uploadId);
            answer(upload.requestId(), WorldContentStatus.of(Result.INVALID, "Upload parts arrived out of order"),
                    uploadId, upload.sha());
            return;
        }
        System.arraycopy(bytes, 0, upload.bytes(), offset, bytes.length);
        upload.received()[0] += bytes.length;
    }

    @Override
    public void commit(int uploadId) {
        Upload upload = uploads.remove(uploadId);
        if (upload == null) {
            return;
        }
        answer(upload.requestId(), stored(upload), uploadId, upload.sha());
    }

    // The checks a server makes of a complete file before it keeps it
    private WorldContentStatus stored(Upload upload) {
        if (upload.received()[0] != upload.bytes().length) {
            return WorldContentStatus.of(Result.INVALID, "The upload is incomplete");
        }
        WorldContentStatus refused = WorldCollections.checkUpload(store, upload.sha(), upload.type(), upload.bytes().length);
        if (refused != null) {
            return refused.result() == Result.ALREADY_STORED ? WorldContentStatus.of(Result.STORED, upload.sha()) : refused;
        }
        try {
            if (!JdkSha256.of(upload.bytes()).equals(upload.sha())) {
                throw new WorldContentException("The file does not match its SHA-256");
            }
            BlobInfo info = upload.type().validate(upload.bytes());
            store.writeBlob(upload.sha(), upload.bytes());
            WorldContentStatus status = WorldCollections.addBlob(store, upload.sha(), info, owner, effects);
            // A draft's file is kept as soon as it is written: there is no sync to save it with
            store.saveUnsavedBlobs();
            return status;
        } catch (WorldContentException e) {
            return WorldContentStatus.invalid(e);
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to write the draft file {}", upload.sha(), e);
            return WorldContentStatus.of(Result.SAVE_FAILED);
        }
    }

    @Override
    public void edit(int requestId, WorldCollectionEdit edit) {
        WorldContentStatus status = edit instanceof WorldCollectionEdit.ImportPack
                ? WorldContentStatus.of(Result.INVALID, "Drafts on this computer are not imported from a datapack")
                : WorldCollections.edit(store, edit, owner, effects);
        answer(requestId, status, 0, "");
    }

    private void answer(int requestId, WorldContentStatus status, int uploadId, String sha) {
        answers.add(new WorldCollectionStatusPacket(requestId, status, uploadId, sha));
    }
}
