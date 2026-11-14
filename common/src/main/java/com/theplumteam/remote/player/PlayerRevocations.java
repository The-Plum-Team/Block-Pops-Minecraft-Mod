package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that must no longer load: collections that are quarantined or removed, and single
 * revisions their owner withdrew or a moderator revoked. A list is in force only while the
 * accepted status names exactly it, and it expires as the status does.
 *
 * @param seq         grows at every change
 * @param collections the collections that do not load, each once
 * @param revisions   the revisions that do not load, each once
 */
public record PlayerRevocations(long seq, long issuedAt, long expiresAt, List<Collection> collections,
                                List<Revision> revisions) {
    public static final String KIND = "blockpops.player.revocations";
    public static final int ENVELOPE_BYTES = 256 * 1024;
    /** The most entries of each list, which is about what an envelope of that size holds. */
    public static final int MAX_ENTRIES = 4096;
    private static final String[] KEYS = {"kind", "schema", "seq", "issued_at", "expires_at", "collections",
        "revisions"};
    private static final String[] COLLECTION_KEYS = {"id", "state"};
    private static final String[] REVISION_KEYS = {"id", "rev", "by"};

    /** A collection that does not load: quarantined for now, or removed. */
    public record Collection(String id, boolean removed) {
    }

    /** A revision that does not load, and whether a moderator rather than its owner took it back. */
    public record Revision(String id, int rev, boolean byModerator) {
    }

    public PlayerRevocations {
        collections = List.copyOf(collections);
        revisions = List.copyOf(revisions);
    }

    /** Reads a revocation list's signed payload. */
    public static PlayerRevocations parse(byte[] payload) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(payload, ENVELOPE_BYTES);
        Payloads.header(json, KIND);
        Payloads.exactKeys(json, "Revocations", KEYS);
        long issuedAt = Payloads.integer(json, "issued_at", 0, Payloads.MAX_TIME);
        List<Collection> collections = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : Payloads.array(json, "collections", MAX_ENTRIES)) {
            JsonObject entry = Payloads.object(element, "A revoked collection");
            Payloads.exactKeys(entry, "A revoked collection", COLLECTION_KEYS);
            String id = Payloads.playerId(entry, "id");
            if (!ids.add(id)) {
                throw new RemoteException("A collection is revoked twice");
            }
            collections.add(new Collection(id, Payloads.word(entry, "state", "removed", "quarantined").equals("removed")));
        }
        List<Revision> revisions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : Payloads.array(json, "revisions", MAX_ENTRIES)) {
            JsonObject entry = Payloads.object(element, "A revoked revision");
            Payloads.exactKeys(entry, "A revoked revision", REVISION_KEYS);
            String id = Payloads.playerId(entry, "id");
            int rev = (int) Payloads.integer(entry, "rev", 1, PlayerHead.MAX_REV);
            if (!seen.add(id + "/" + rev)) {
                throw new RemoteException("A revision is revoked twice");
            }
            revisions.add(new Revision(id, rev, Payloads.word(entry, "by", "owner", "moderator").equals("moderator")));
        }
        PlayerRevocations list = new PlayerRevocations(Payloads.integer(json, "seq", 1, PlayerRules.MAX_SEQ), issuedAt,
                PlayerStatus.expiry(json, issuedAt), collections, revisions);
        return Payloads.canonical(list, list.toPayload(), payload, "Revocations");
    }

    /** Whether the collection does not load at all. */
    public boolean revokes(String id) {
        for (Collection collection : collections) {
            if (collection.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether that revision of the collection does not load. */
    public boolean revokes(String id, int rev) {
        if (revokes(id)) {
            return true;
        }
        for (Revision revision : revisions) {
            if (revision.rev() == rev && revision.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** The payload that is signed. */
    public byte[] toPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", KIND);
        json.addProperty("schema", 1);
        json.addProperty("seq", seq);
        json.addProperty("issued_at", issuedAt);
        json.addProperty("expires_at", expiresAt);
        JsonArray collectionArray = new JsonArray();
        for (Collection collection : collections) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", collection.id());
            entry.addProperty("state", collection.removed() ? "removed" : "quarantined");
            collectionArray.add(entry);
        }
        json.add("collections", collectionArray);
        JsonArray revisionArray = new JsonArray();
        for (Revision revision : revisions) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", revision.id());
            entry.addProperty("rev", revision.rev());
            entry.addProperty("by", revision.byModerator() ? "moderator" : "owner");
            revisionArray.add(entry);
        }
        json.add("revisions", revisionArray);
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
