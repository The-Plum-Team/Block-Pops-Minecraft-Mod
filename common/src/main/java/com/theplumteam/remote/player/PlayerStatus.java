package com.theplumteam.remote.player;

import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;

import java.nio.charset.StandardCharsets;

/**
 * Whether player collections load at all, which revocation list is in force, and how often
 * games ask again. It expires, so a game that cannot reach a newer one stops trusting it.
 *
 * @param seq               grows at every change
 * @param issuedAt          when it was signed, in Unix seconds
 * @param expiresAt         when it stops being fresh, at most {@link #MAX_VALIDITY_SECONDS} later
 * @param loading           false stops every game from loading player collections
 * @param revocationsSeq    the seq of the revocation list in force
 * @param revocationsSha256 the SHA-256 of that list's envelope
 */
public record PlayerStatus(long seq, long issuedAt, long expiresAt, boolean loading, long revocationsSeq,
                           String revocationsSha256, int serverPollSeconds, int headPollSeconds,
                           int clientPollSeconds) {
    public static final String KIND = "blockpops.player.status";
    public static final int ENVELOPE_BYTES = 4 * 1024;
    /** The longest a status or a revocation list may stay valid: seven days. */
    public static final long MAX_VALIDITY_SECONDS = 7L * 24 * 60 * 60;
    private static final String[] KEYS = {"kind", "schema", "seq", "issued_at", "expires_at", "loading",
        "revocations_seq", "revocations_sha256", "server_poll_seconds", "head_poll_seconds", "client_poll_seconds"};
    private static final int MAX_POLL_SECONDS = 7 * 24 * 60 * 60;

    /** Reads a status's signed payload. */
    public static PlayerStatus parse(byte[] payload) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(payload, ENVELOPE_BYTES);
        Payloads.header(json, KIND);
        Payloads.exactKeys(json, "Status", KEYS);
        long issuedAt = Payloads.integer(json, "issued_at", 0, Payloads.MAX_TIME);
        PlayerStatus status = new PlayerStatus(Payloads.integer(json, "seq", 1, PlayerRules.MAX_SEQ), issuedAt,
                expiry(json, issuedAt), Payloads.word(json, "loading", "on", "off").equals("on"),
                Payloads.integer(json, "revocations_seq", 1, PlayerRules.MAX_SEQ),
                Payloads.sha(json, "revocations_sha256"),
                (int) Payloads.integer(json, "server_poll_seconds", 1, MAX_POLL_SECONDS),
                (int) Payloads.integer(json, "head_poll_seconds", 1, MAX_POLL_SECONDS),
                (int) Payloads.integer(json, "client_poll_seconds", 1, MAX_POLL_SECONDS));
        return Payloads.canonical(status, status.toPayload(), payload, "Status");
    }

    /** An expiry after the document was issued and no more than the longest validity later. */
    static long expiry(JsonObject json, long issuedAt) throws RemoteException {
        long expiresAt = Payloads.integer(json, "expires_at", 0, Payloads.MAX_TIME);
        if (expiresAt <= issuedAt || expiresAt - issuedAt > MAX_VALIDITY_SECONDS) {
            throw new RemoteException("expires_at must be after issued_at and at most 7 days later");
        }
        return expiresAt;
    }

    /** Whether it has not expired at that time, in Unix seconds. */
    public boolean isFreshAt(long now) {
        return now < expiresAt;
    }

    /** The payload that is signed. */
    public byte[] toPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", KIND);
        json.addProperty("schema", 1);
        json.addProperty("seq", seq);
        json.addProperty("issued_at", issuedAt);
        json.addProperty("expires_at", expiresAt);
        json.addProperty("loading", loading ? "on" : "off");
        json.addProperty("revocations_seq", revocationsSeq);
        json.addProperty("revocations_sha256", revocationsSha256);
        json.addProperty("server_poll_seconds", serverPollSeconds);
        json.addProperty("head_poll_seconds", headPollSeconds);
        json.addProperty("client_poll_seconds", clientPollSeconds);
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
