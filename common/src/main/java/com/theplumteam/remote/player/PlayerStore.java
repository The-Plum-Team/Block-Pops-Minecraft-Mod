package com.theplumteam.remote.player;

import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.WorldContentLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * What a game keeps of player collections between runs, in its cache below {@code p/}: what it
 * believes ({@link PlayerState}), the heads and the indexes whose signatures it has checked, and
 * the records they name. It is what lets a world open with no network.
 *
 * <p>An index is kept as its payload with the key that signed it and the epoch it was accepted
 * in, written only after its signature held; the game's own disk is inside its trust boundary,
 * so it is not checked again on the way back, but one of a revoked key or of an earlier epoch
 * is not given back. A record is kept under the SHA-256 of its bytes and checked against it
 * every time.
 *
 * <p>Not thread-safe: use it from one thread at a time, as {@link VerifiedStore}.
 */
public final class PlayerStore {
    private static final String STATE = "p/state.json";
    private static final String[] VERIFIED_KEYS = {"schema", "kid", "epoch", "payload"};

    private final VerifiedStore files;

    public PlayerStore(VerifiedStore files) {
        this.files = files;
    }

    /**
     * The state kept by {@link #saveState}; a game that has kept none, or whose kept state does
     * not hold together, starts from believing nothing, and what was kept is set aside.
     */
    public PlayerState loadState() throws IOException {
        Optional<byte[]> kept = files.load(STATE, PlayerState.MAX_BYTES);
        if (kept.isEmpty()) {
            return new PlayerState();
        }
        try {
            return PlayerState.parse(kept.get());
        } catch (RemoteException e) {
            files.save(STATE + ".corrupt", kept.get());
            files.delete(STATE);
            return new PlayerState();
        }
    }

    public void saveState(PlayerState state) throws IOException {
        files.save(STATE, state.toUtf8());
    }

    /** Keeps the payload of an index whose signature has just held. */
    public void saveIndex(String indexSha256, String kid, long epoch, byte[] payload) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("kid", kid);
        json.addProperty("epoch", epoch);
        json.addProperty("payload", CoreBase64.encode(payload));
        files.save(verifiedIndex(indexSha256), JsonOut.write(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The payload of an index kept by {@link #saveIndex}, unless it was accepted in another
     * epoch or signed by a key that is revoked since.
     */
    public Optional<byte[]> loadIndex(String indexSha256, long epoch, Set<String> revokedKids) throws IOException {
        Optional<byte[]> kept = files.load(verifiedIndex(indexSha256), 2L * PlayerIndex.ENVELOPE_BYTES);
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject json = StrictJson.parsePlayerObject(kept.get(), 2L * PlayerIndex.ENVELOPE_BYTES);
            Payloads.exactKeys(json, "Kept index", VERIFIED_KEYS);
            Payloads.integer(json, "schema", 1, 1);
            byte[] payload = CoreBase64.decode(Payloads.string(json, "payload", 2 * PlayerIndex.ENVELOPE_BYTES));
            if (payload == null || Payloads.integer(json, "epoch", 0, PlayerRules.MAX_SEQ) != epoch
                    || revokedKids.contains(Payloads.string(json, "kid", 8))) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (RemoteException e) {
            return Optional.empty();
        }
    }

    /** Keeps a record whose bytes have just matched the SHA-256 its index names. */
    public void saveRecord(String id, String recordSha256, byte[] bytes) throws IOException {
        files.save(PlayerPaths.record(id, recordSha256), bytes);
    }

    /** The record kept under that SHA-256, if its bytes still are the ones it names. */
    public Optional<byte[]> loadRecord(String id, String recordSha256) throws IOException {
        Optional<byte[]> kept = files.load(PlayerPaths.record(id, recordSha256), WorldContentLimits.RECORD_BYTES);
        return kept.isPresent() && JdkSha256.of(kept.get()).equals(recordSha256) ? kept : Optional.empty();
    }

    /**
     * Keeps the envelope a collection's head was published in, whose signature has just held:
     * a server hands it to each client, which checks the signature for itself.
     */
    public void saveHead(String id, byte[] envelope) throws IOException {
        files.save(verifiedHead(id), envelope);
    }

    public Optional<byte[]> loadHead(String id) throws IOException {
        return files.load(verifiedHead(id), PlayerHead.ENVELOPE_BYTES);
    }

    /** Deletes the files kept of one collection, as when it is revoked. */
    public void delete(String id) throws IOException {
        String directory = PlayerPaths.directory(id);
        files.deleteDirectory(directory.substring(0, directory.length() - 1));
    }

    /** Deletes the head kept of one collection, as when the collection leaves the game for good. */
    public void deleteHead(String id) throws IOException {
        files.delete(verifiedHead(id));
    }

    private static String verifiedHead(String id) {
        if (!PlayerIds.isPlayerId(id)) {
            throw new IllegalArgumentException("Invalid player collection id");
        }
        return "p/verified/heads/" + id + ".json";
    }

    private static String verifiedIndex(String indexSha256) {
        if (!com.theplumteam.worldcontent.Sha256.isValidHex(indexSha256)) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return "p/verified/i/" + indexSha256 + ".json";
    }
}
