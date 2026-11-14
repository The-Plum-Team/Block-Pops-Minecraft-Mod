package com.theplumteam.remote.player;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * What a player code points at now: the collection, its state and, while it is live, the index
 * of its current revision. It is the one document of a collection that changes.
 *
 * @param seq      grows by one at every change of this head
 * @param issuedAt when it was signed, in Unix seconds
 * @param state    whether the collection can be loaded
 * @param rev      the revision in force, which grows only when content changes
 * @param index    the SHA-256 of the revision's index envelope; null unless live
 * @param reason   why it is not live; null while it is
 */
public record PlayerHead(long seq, long issuedAt, String code, String id, State state, int rev,
                         @Nullable String index, @Nullable Reason reason) {
    public static final String KIND = "blockpops.player.head";
    /** The most bytes a head's envelope may have. */
    public static final int ENVELOPE_BYTES = 4 * 1024;
    /** The highest revision a collection reaches. */
    public static final int MAX_REV = 1_000_000;
    private static final String[] LIVE_KEYS = {"kind", "schema", "seq", "issued_at", "code", "id", "state", "rev",
        "index"};
    private static final String[] OTHER_KEYS = {"kind", "schema", "seq", "issued_at", "code", "id", "state", "rev",
        "index", "reason"};

    /** Whether a collection can be loaded, is hidden for now, or is gone. */
    public enum State {
        LIVE("live"), QUARANTINED("quarantined"), REMOVED("removed");

        private final String word;

        State(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }
    }

    /** Who took a collection out of use. */
    public enum Reason {
        OWNER("owner"), POLICY("policy"), LEGAL("legal");

        private final String word;

        Reason(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }
    }

    public PlayerHead {
        if (!PlayerIds.isPlayerCode(code) || !PlayerIds.isPlayerId(id) || state == null
                || (state == State.LIVE) != (index != null) || (state == State.LIVE) != (reason == null)) {
            throw new IllegalArgumentException("Invalid head");
        }
    }

    /** Reads a head's signed payload. */
    public static PlayerHead parse(byte[] payload) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(payload, ENVELOPE_BYTES);
        Payloads.header(json, KIND);
        State state = state(Payloads.word(json, "state", "live", "quarantined", "removed"));
        Payloads.exactKeys(json, "Head", state == State.LIVE ? LIVE_KEYS : OTHER_KEYS);
        long seq = Payloads.integer(json, "seq", 1, PlayerRules.MAX_SEQ);
        long issuedAt = Payloads.integer(json, "issued_at", 0, Payloads.MAX_TIME);
        String code = Payloads.string(json, "code", 32);
        if (!PlayerIds.isPlayerCode(code)) {
            throw new RemoteException("Head code is not a player code");
        }
        String id = Payloads.playerId(json, "id");
        int rev = (int) Payloads.integer(json, "rev", 1, MAX_REV);
        String index = null;
        Reason reason = null;
        if (state == State.LIVE) {
            index = Payloads.sha(json, "index");
        } else {
            if (!json.get("index").isJsonNull()) {
                throw new RemoteException("A head that is not live names no index");
            }
            reason = reason(Payloads.word(json, "reason", "owner", "policy", "legal"));
        }
        PlayerHead head = new PlayerHead(seq, issuedAt, code, id, state, rev, index, reason);
        return Payloads.canonical(head, head.toPayload(), payload, "Head");
    }

    /** The payload that is signed. */
    public byte[] toPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", KIND);
        json.addProperty("schema", 1);
        json.addProperty("seq", seq);
        json.addProperty("issued_at", issuedAt);
        json.addProperty("code", code);
        json.addProperty("id", id);
        json.addProperty("state", state.word());
        json.addProperty("rev", rev);
        if (index != null) {
            json.addProperty("index", index);
        } else {
            json.add("index", JsonNull.INSTANCE);
            json.addProperty("reason", reason.word());
        }
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }

    private static State state(String word) {
        for (State state : State.values()) {
            if (state.word.equals(word)) {
                return state;
            }
        }
        throw new IllegalArgumentException(word);
    }

    private static Reason reason(String word) {
        for (Reason reason : Reason.values()) {
            if (reason.word.equals(word)) {
                return reason;
            }
        }
        throw new IllegalArgumentException(word);
    }
}
