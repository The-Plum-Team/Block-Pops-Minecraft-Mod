package com.theplumteam.remote.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.worldcontent.Sha256;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a game believes about player collections, and the rules by which it comes to believe a
 * newer document: the last root, status and revocation list it accepted, and the last head of
 * each collection. Every document offered here has had its signature checked already
 * ({@link PlayerSignatures}); this class decides whether a genuine document is also a current
 * one.
 *
 * <ul>
 *   <li><b>No going back.</b> A document is accepted only when its sequence number is above the
 *       one held, by no more than {@link PlayerRules#MAX_SEQ_JUMP}. The same number is the same
 *       document or it is refused. A document issued more than a day ahead of the clock is
 *       refused.</li>
 *   <li><b>Epochs.</b> A root with a higher number than the last starts a new epoch: every
 *       other document held is forgotten and must be fetched again, so numbers a revoked key
 *       inflated bind nobody, and the keys the root revokes are believed no longer. A root is
 *       never forgotten: only a higher one replaces it.</li>
 *   <li><b>Freshness.</b> The status expires. A revocation list is in force only while the
 *       held status names exactly it. Player collections load only while the root allows it,
 *       the status is fresh and says so, and that list is in force; something new loads only
 *       when both were confirmed by a fetch in the last {@link #CONFIRM_SECONDS}. What was
 *       already verified is kept through an outage, unless what is held says it must go.</li>
 * </ul>
 *
 * It holds no clock and does no I/O: the caller says what time it is, and stores
 * {@link #toJson} where it keeps its cache. It is not safe for use from several threads.
 */
public final class PlayerState {
    /** What accepting a document did. */
    public enum Verdict {
        /** It replaced what was held. */
        ACCEPTED,
        /** It is the document already held. */
        UNCHANGED,
        /** It is a root that started a new epoch: everything else was forgotten. */
        NEW_EPOCH
    }

    /** How recently the status and its revocation list must have been fetched for something new to load. */
    public static final long CONFIRM_SECONDS = 5 * 60;
    /** The most bytes the stored form has. */
    public static final int MAX_BYTES = 1024 * 1024;
    private static final String[] KEYS = {"schema", "epoch", "root", "status", "revocations", "checked_at", "heads"};
    private static final String[] DOCUMENT_KEYS = {"kid", "payload"};
    private static final String[] REVOCATIONS_KEYS = {"kid", "payload", "sha256"};
    private static final String[] HEAD_KEYS = {"kid", "payload", "verified_at"};
    private static final int MAX_HEADS = 4096;

    private record Held<T>(T document, String kid) {
    }

    private record HeldHead(PlayerHead head, String kid, long verifiedAt) {
    }

    private long epoch;
    @Nullable
    private Held<PlayerRoot> root;
    @Nullable
    private Held<PlayerStatus> status;
    @Nullable
    private Held<PlayerRevocations> revocations;
    @Nullable
    private String revocationsSha256;
    private long confirmedAt;
    private final Map<String, HeldHead> heads = new TreeMap<>();

    /** The state of a game that has seen nothing yet: no root, and player collections off. */
    public PlayerState() {
    }

    // ---- Accepting documents

    /**
     * Offers a root. A higher number than the epoch's starts a new epoch and forgets every other
     * document; the caller then fetches the status, the revocation list and its heads again
     * before it keeps anything they supported.
     */
    public Verdict acceptRoot(PlayerRoot next, String kid, long now) throws RemoteException {
        notAhead(next.issuedAt(), now, "Root", kid);
        if (next.seq() < epoch) {
            throw new RemoteException("Root of key " + kid + " has seq " + next.seq() + ", below the epoch's " + epoch);
        }
        if (next.seq() == epoch) {
            if (root != null && root.document().equals(next)) {
                return Verdict.UNCHANGED;
            }
            throw new RemoteException("Root of key " + kid + " is another document with the epoch's seq " + epoch);
        }
        epoch = next.seq();
        root = new Held<>(next, kid);
        status = null;
        revocations = null;
        revocationsSha256 = null;
        confirmedAt = 0;
        heads.clear();
        return Verdict.NEW_EPOCH;
    }

    /** Offers a status. */
    public Verdict acceptStatus(PlayerStatus next, String kid, long now) throws RemoteException {
        notAhead(next.issuedAt(), now, "Status", kid);
        Verdict verdict = verdict(next.seq(), status != null ? status.document().seq() : -1,
                status != null && status.document().equals(next), "Status", kid);
        if (verdict == Verdict.ACCEPTED) {
            status = new Held<>(next, kid);
        }
        return verdict;
    }

    /**
     * Offers a revocation list.
     *
     * @param envelopeSha256 the SHA-256 of the envelope it arrived in, which is how a status names it
     */
    public Verdict acceptRevocations(PlayerRevocations next, String kid, String envelopeSha256, long now)
            throws RemoteException {
        if (!Sha256.isValidHex(envelopeSha256)) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        notAhead(next.issuedAt(), now, "Revocations", kid);
        Verdict verdict = verdict(next.seq(), revocations != null ? revocations.document().seq() : -1,
                revocations != null && revocations.document().equals(next), "Revocations", kid);
        // The same list signed again arrives in another envelope, which is then the one a status names
        if (verdict == Verdict.ACCEPTED || !envelopeSha256.equals(revocationsSha256)) {
            revocations = new Held<>(next, kid);
            revocationsSha256 = envelopeSha256;
            return Verdict.ACCEPTED;
        }
        return verdict;
    }

    /** Offers a collection's head. Either way it was verified now, which {@link #headVerifiedSince} reports. */
    public Verdict acceptHead(PlayerHead next, String kid, long now) throws RemoteException {
        notAhead(next.issuedAt(), now, "Head of " + next.id(), kid);
        HeldHead held = heads.get(next.id());
        Verdict verdict = verdict(next.seq(), held != null ? held.head().seq() : -1,
                held != null && held.head().equals(next), "Head of " + next.id(), kid);
        if (held == null && heads.size() >= MAX_HEADS) {
            throw new RemoteException("Too many heads are held");
        }
        heads.put(next.id(), new HeldHead(next, kid, now));
        return verdict;
    }

    /** Forgets a collection's head, as when the collection leaves the game for good. */
    public void forgetHead(String id) {
        heads.remove(id);
    }

    /** Records that the status and the revocation list it names were both just fetched and are the ones held. */
    public void confirmed(long now) {
        confirmedAt = now;
    }

    private static void notAhead(long issuedAt, long now, String what, String kid) throws RemoteException {
        if (issuedAt > now + PlayerRules.MAX_CLOCK_AHEAD_SECONDS) {
            throw new RemoteException(what + " of key " + kid + " is issued more than a day ahead of this clock");
        }
    }

    private static Verdict verdict(long seq, long held, boolean same, String what, String kid) throws RemoteException {
        if (held < 0) {
            return Verdict.ACCEPTED;
        }
        if (seq < held) {
            throw new RemoteException(what + " of key " + kid + " has seq " + seq + ", below the held " + held);
        }
        if (seq == held) {
            if (same) {
                return Verdict.UNCHANGED;
            }
            throw new RemoteException(what + " of key " + kid + " is another document with the held seq " + held);
        }
        if (seq - held > PlayerRules.MAX_SEQ_JUMP) {
            throw new RemoteException(what + " of key " + kid + " has seq " + seq + ", too far above the held " + held);
        }
        return Verdict.ACCEPTED;
    }

    // ---- What is believed

    /** The epoch: the number of the last root accepted, 0 before any. */
    public long epoch() {
        return epoch;
    }

    /** The online keys the last root revoked. */
    public Set<String> revokedKids() {
        return root != null ? Set.copyOf(root.document().revokedKids()) : Set.of();
    }

    @Nullable
    public PlayerStatus status() {
        return status != null ? status.document() : null;
    }

    @Nullable
    public PlayerRevocations revocations() {
        return revocations != null ? revocations.document() : null;
    }

    /** The last head accepted for a collection, or null. */
    @Nullable
    public PlayerHead head(String id) {
        HeldHead held = heads.get(id);
        return held != null ? held.head() : null;
    }

    /** Whether a collection's head was verified at or after that time. */
    public boolean headVerifiedSince(String id, long since) {
        HeldHead held = heads.get(id);
        return held != null && held.verifiedAt() >= since;
    }

    /** Whether the held status has not expired. */
    public boolean statusFresh(long now) {
        return status != null && status.document().isFreshAt(now);
    }

    /** Whether the held revocation list is exactly the one the held status names, and has not expired. */
    public boolean revocationsInForce(long now) {
        return status != null && revocations != null && now < revocations.document().expiresAt()
                && revocations.document().seq() == status.document().revocationsSeq()
                && status.document().revocationsSha256().equals(revocationsSha256);
    }

    /** Whether player collections load at all right now. */
    public boolean loading(long now) {
        return (root == null || root.document().loading()) && statusFresh(now) && status.document().loading()
                && revocationsInForce(now);
    }

    /** Whether something not verified before may load now: loading is on and was confirmed moments ago. */
    public boolean mayAdd(long now) {
        return loading(now) && confirmedAt <= now && now - confirmedAt <= CONFIRM_SECONDS;
    }

    /** Whether that revision of a collection, not verified before, may load now. */
    public boolean mayAdd(String id, int rev, long now) {
        return mayAdd(now) && !revocations.document().revokes(id, rev);
    }

    /**
     * Whether a revision that was verified before stays loaded. It does through an outage and
     * an expired status, so a world opens offline. It does not once the root or the last status
     * turned loading off, once the last revocation list names it, or while no status or list is
     * held at all, as right after a new epoch.
     */
    public boolean keeps(String id, int rev) {
        return (root == null || root.document().loading()) && status != null && status.document().loading()
                && revocations != null && !revocations.document().revokes(id, rev);
    }

    // ---- Storing

    /** The state as it is stored between runs. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("epoch", epoch);
        json.add("root", root != null ? held(root.kid(), root.document().toPayload()) : JsonNull.INSTANCE);
        json.add("status", status != null ? held(status.kid(), status.document().toPayload()) : JsonNull.INSTANCE);
        if (revocations != null) {
            JsonObject entry = held(revocations.kid(), revocations.document().toPayload());
            entry.addProperty("sha256", revocationsSha256);
            json.add("revocations", entry);
        } else {
            json.add("revocations", JsonNull.INSTANCE);
        }
        json.addProperty("checked_at", confirmedAt);
        JsonObject headsJson = new JsonObject();
        for (Map.Entry<String, HeldHead> entry : heads.entrySet()) {
            JsonObject head = held(entry.getValue().kid(), entry.getValue().head().toPayload());
            head.addProperty("verified_at", entry.getValue().verifiedAt());
            headsJson.add(entry.getKey(), head);
        }
        json.add("heads", headsJson);
        return json;
    }

    /** The stored form as bytes. */
    public byte[] toUtf8() {
        return JsonOut.write(toJson()).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject held(String kid, byte[] payload) {
        JsonObject json = new JsonObject();
        json.addProperty("kid", kid);
        json.addProperty("payload", CoreBase64.encode(payload));
        return json;
    }

    /**
     * Reads a stored state. The game's own cache is inside its trust boundary, so signatures
     * are not checked again; every document is still read by its parser, and a state that does
     * not hold together is refused whole, for the caller to set aside and start from nothing.
     */
    public static PlayerState parse(byte[] utf8) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(utf8, MAX_BYTES);
        Payloads.exactKeys(json, "State", KEYS);
        Payloads.integer(json, "schema", 1, 1);
        PlayerState state = new PlayerState();
        state.epoch = Payloads.integer(json, "epoch", 0, PlayerRules.MAX_SEQ);
        state.confirmedAt = Payloads.integer(json, "checked_at", 0, Payloads.MAX_TIME);
        if (!json.get("root").isJsonNull()) {
            JsonObject entry = entry(json.get("root"), "root", DOCUMENT_KEYS);
            state.root = new Held<>(PlayerRoot.parse(payload(entry)), kid(entry));
        }
        // The epoch is the last root's number, and there is no epoch without a root
        if (state.root == null ? state.epoch != 0 : state.root.document().seq() != state.epoch) {
            throw new RemoteException("Stored state names an epoch its root does not");
        }
        if (!json.get("status").isJsonNull()) {
            JsonObject entry = entry(json.get("status"), "status", DOCUMENT_KEYS);
            state.status = new Held<>(PlayerStatus.parse(payload(entry)), kid(entry));
        }
        if (!json.get("revocations").isJsonNull()) {
            JsonObject entry = entry(json.get("revocations"), "revocations", REVOCATIONS_KEYS);
            state.revocations = new Held<>(PlayerRevocations.parse(payload(entry)), kid(entry));
            state.revocationsSha256 = Payloads.sha(entry, "sha256");
        }
        JsonObject headsJson = Payloads.object(json.get("heads"), "heads");
        if (headsJson.size() > MAX_HEADS) {
            throw new RemoteException("Stored state holds too many heads");
        }
        for (Map.Entry<String, JsonElement> element : headsJson.entrySet()) {
            JsonObject entry = entry(element.getValue(), "head", HEAD_KEYS);
            PlayerHead head = PlayerHead.parse(payload(entry));
            if (!head.id().equals(element.getKey())) {
                throw new RemoteException("Stored head is filed under another collection");
            }
            state.heads.put(head.id(), new HeldHead(head, kid(entry),
                    Payloads.integer(entry, "verified_at", 0, Payloads.MAX_TIME)));
        }
        Set<String> revoked = state.revokedKids();
        if ((state.status != null && revoked.contains(state.status.kid()))
                || (state.revocations != null && revoked.contains(state.revocations.kid()))
                || state.heads.values().stream().anyMatch(head -> revoked.contains(head.kid()))) {
            throw new RemoteException("Stored state holds a document of a revoked key");
        }
        return Payloads.canonical(state, state.toUtf8(), utf8, "State");
    }

    private static JsonObject entry(JsonElement element, String what, String[] keys) throws RemoteException {
        JsonObject entry = Payloads.object(element, what);
        Payloads.exactKeys(entry, what, keys);
        return entry;
    }

    private static String kid(JsonObject entry) throws RemoteException {
        String kid = Payloads.string(entry, "kid", 8);
        if (!SignedEnvelope.isKid(kid)) {
            throw new RemoteException("Stored state names an invalid key");
        }
        return kid;
    }

    private static byte[] payload(JsonObject entry) throws RemoteException {
        byte[] payload = CoreBase64.decode(Payloads.string(entry, "payload", CoreBase64.encodedLength(MAX_BYTES)));
        if (payload == null) {
            throw new RemoteException("Stored payload is not Base64");
        }
        return payload;
    }
}
