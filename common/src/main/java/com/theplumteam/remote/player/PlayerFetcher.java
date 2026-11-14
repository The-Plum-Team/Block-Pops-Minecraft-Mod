package com.theplumteam.remote.player;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.remote.player.PlayerSignatures.Kind;
import com.theplumteam.remote.player.PlayerSignatures.Signed;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.PlayerAssetIds;
import com.theplumteam.worldcontent.WorldCollectionCheck;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentLimits;

import java.io.IOException;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Turns a player code into a collection a game may register: the signed head the code points
 * at, the signed index of the revision, the record it names, and the collection built from it.
 * Nothing is built from anything whose signature or SHA-256 has not held, and nothing new is
 * built unless player collections are on and were confirmed to be moments ago.
 *
 * <p>It fetches no file of a collection: the index carries what checking each file found, which
 * is enough to build the collection, and a client fetches and checks the files when it first
 * shows them. Every path asked of the source is computed by {@link PlayerPaths}.
 *
 * <p>Everything here blocks and reports failure by throwing, so callers run it on the remote
 * executor's thread and decide what to keep, log or show. A failure to reach the origin never
 * changes what is believed. It is not safe for use from several threads.
 */
public final class PlayerFetcher {
    /** Why a code, or a revision, is not to be loaded although nothing failed. */
    public enum Why {
        /** Player collections are off: no key, a root or a status that says so, or nothing confirmed. */
        OFF,
        /** No collection has that code. */
        UNKNOWN_CODE,
        /** The collection, or that revision of it, is quarantined, removed, withdrawn or revoked. */
        UNAVAILABLE
    }

    /** A code or a revision that is not to be loaded. */
    public static final class Refused extends Exception {
        private final Why why;

        public Refused(Why why) {
            super(why.name());
            this.why = why;
        }

        public Why why() {
            return why;
        }
    }

    /**
     * One revision of a collection, verified and built.
     *
     * @param indexSha256 the SHA-256 of the envelope its index was published in, which names it
     */
    public record Revision(String id, int rev, String indexSha256, PlayerIndex index, WorldCollectionRecord record,
                           FigureCollection collection) {
    }

    /**
     * What a code resolved to.
     *
     * @param headEnvelope the head as it was published, for a client to verify by itself
     */
    public record Resolved(PlayerHead head, byte[] headEnvelope, Revision revision) {
    }

    /**
     * A collection's head, believed.
     *
     * @param envelope the head as it was published, for a client to verify by itself
     */
    public record Head(PlayerHead head, byte[] envelope) {
    }

    private final RemoteSource source;
    private final PlayerStore store;
    private final PlayerKeys keys;
    private final LongSupplier clock;
    private PlayerState state;

    /**
     * @param clock the time in Unix seconds
     */
    public PlayerFetcher(RemoteSource source, PlayerStore store, PlayerKeys keys, LongSupplier clock) {
        this.source = source;
        this.store = store;
        this.keys = keys;
        this.clock = clock;
    }

    /** Whether this build can believe anything: with no key in it, player collections are off. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    /** What this game believes, as kept from earlier runs and brought up to date by each call here. */
    public PlayerState state() throws IOException {
        if (state == null) {
            state = store.loadState();
        }
        return state;
    }

    /**
     * Fetches the root, the status and the revocation list, and believes each one that is
     * genuine and current. With no key in the build nothing is fetched at all.
     *
     * @throws IOException when the origin cannot be reached or a document is not to be believed.
     *                     Whatever was believed before still is; a newer document that was
     *                     accepted before the failure stays accepted
     */
    public void refresh() throws IOException {
        if (keys.isEmpty()) {
            return;
        }
        PlayerState held = state();
        long now = clock.getAsLong();
        try {
            // The root is normally absent: it exists only to overrule the online keys
            byte[] envelope = source.fetch(PlayerPaths.ROOT_STATUS, Kind.ROOT.envelopeBytes());
            Signed signed = PlayerSignatures.open(Kind.ROOT, envelope, keys, java.util.Set.of());
            held.acceptRoot(PlayerRoot.parse(signed.payload()), signed.kid(), now);
        } catch (RemoteNotFoundException absent) {
            // No root has been published
        }
        status(held, now);
        byte[] envelope = source.fetch(PlayerPaths.REVOCATIONS, Kind.REVOCATIONS.envelopeBytes());
        Signed signed = PlayerSignatures.open(Kind.REVOCATIONS, envelope, keys, held.revokedKids());
        held.acceptRevocations(PlayerRevocations.parse(signed.payload()), signed.kid(), JdkSha256.of(envelope), now);
        // The list is written before the status that names it: a list newer than the status
        // just read means a newer status is there by now
        if (!held.revocationsInForce(now)) {
            status(held, now);
        }
        if (held.revocationsInForce(now)) {
            held.confirmed(now);
        }
        store.saveState(held);
    }

    private void status(PlayerState held, long now) throws IOException {
        byte[] envelope = source.fetch(PlayerPaths.STATUS, Kind.STATUS.envelopeBytes());
        Signed signed = PlayerSignatures.open(Kind.STATUS, envelope, keys, held.revokedKids());
        held.acceptStatus(PlayerStatus.parse(signed.payload()), signed.kid(), now);
    }

    /**
     * Resolves a code to the revision its head names, for a game that does not hold it yet.
     *
     * @throws Refused     when the code is unknown, or the collection is not to be loaded
     * @throws IOException when the origin cannot be reached or something is not to be believed
     */
    public Resolved resolve(String code) throws IOException, Refused {
        if (keys.isEmpty() || !PlayerIds.isPlayerCode(code)) {
            throw new Refused(keys.isEmpty() ? Why.OFF : Why.UNKNOWN_CODE);
        }
        PlayerState held = state();
        long now = clock.getAsLong();
        if (!held.mayAdd(now)) {
            refresh();
        }
        if (!held.mayAdd(now)) {
            throw new Refused(Why.OFF);
        }
        Signed signed;
        byte[] envelope;
        try {
            envelope = source.fetch(PlayerPaths.head(code), Kind.HEAD.envelopeBytes());
            signed = PlayerSignatures.open(Kind.HEAD, envelope, keys, held.revokedKids());
        } catch (RemoteNotFoundException unknown) {
            throw new Refused(Why.UNKNOWN_CODE);
        }
        PlayerHead head = PlayerHead.parse(signed.payload());
        if (!head.code().equals(code)) {
            throw new RemoteException("The head under " + code + " is another code's");
        }
        held.acceptHead(head, signed.kid(), now);
        store.saveState(held);
        store.saveHead(head.id(), envelope);
        if (head.state() != PlayerHead.State.LIVE || !held.mayAdd(head.id(), head.rev(), now)) {
            throw new Refused(Why.UNAVAILABLE);
        }
        return new Resolved(head, envelope, revision(head.id(), head.rev(), head.index()));
    }

    /**
     * The head a code points at now, believed if genuine and current, for a game that already
     * holds the collection and asks whether anything changed. Whatever it says, live or not, is
     * what {@link #state()} holds for the collection from then on.
     */
    public Head head(String code, String id) throws IOException {
        if (keys.isEmpty() || !PlayerIds.isPlayerCode(code)) {
            throw new RemoteException("Player collections are off");
        }
        PlayerState held = state();
        byte[] envelope = source.fetch(PlayerPaths.head(code), Kind.HEAD.envelopeBytes());
        Signed signed = PlayerSignatures.open(Kind.HEAD, envelope, keys, held.revokedKids());
        PlayerHead head = PlayerHead.parse(signed.payload());
        if (!head.code().equals(code) || !head.id().equals(id)) {
            throw new RemoteException("The head under " + code + " is another collection's");
        }
        held.acceptHead(head, signed.kid(), clock.getAsLong());
        store.saveState(held);
        store.saveHead(id, envelope);
        return new Head(head, envelope);
    }

    /**
     * The head kept of a collection, with no network: the one {@link #state()} holds, in the
     * envelope it was published in. Empty when none is kept or the two do not agree.
     */
    public Optional<Head> keptHead(String id) throws IOException {
        PlayerHead believed = state().head(id);
        Optional<byte[]> envelope = store.loadHead(id);
        if (believed == null || envelope.isEmpty()) {
            return Optional.empty();
        }
        try {
            PlayerHead kept = PlayerHead.parse(SignedEnvelope.parse(envelope.get(), Kind.HEAD.envelopeBytes()).payload());
            return kept.equals(believed) ? Optional.of(new Head(kept, envelope.get())) : Optional.empty();
        } catch (RemoteException e) {
            return Optional.empty();
        }
    }

    /**
     * The revision a code's head names now, for a game that already holds the collection.
     *
     * @throws Refused when the collection, or that revision of it, is not to be loaded
     */
    public Resolved poll(String code, String id) throws IOException, Refused {
        if (keys.isEmpty() || !PlayerIds.isPlayerCode(code)) {
            throw new Refused(Why.OFF);
        }
        Head now = head(code, id);
        PlayerHead head = now.head();
        if (head.state() != PlayerHead.State.LIVE || !state().keeps(id, head.rev())) {
            throw new Refused(Why.UNAVAILABLE);
        }
        return new Resolved(head, now.envelope(), revision(id, head.rev(), head.index()));
    }

    /**
     * Whether an index is still published. One that is not has aged out of what the service
     * keeps: a game that kept it could go on, but no other game could load it.
     */
    public boolean published(String id, String indexSha256) throws IOException {
        try {
            byte[] envelope = source.fetch(PlayerPaths.index(id, indexSha256), Kind.INDEX.envelopeBytes());
            return JdkSha256.of(envelope).equals(indexSha256);
        } catch (RemoteNotFoundException gone) {
            return false;
        }
    }

    /**
     * One revision, from what this game keeps of it or from the origin: the index that
     * SHA-256 names, signed and of that collection and revision, and the record it names.
     */
    public Revision revision(String id, int rev, String indexSha256) throws IOException {
        Optional<Revision> kept = kept(id, rev, indexSha256);
        if (kept.isPresent()) {
            return kept.get();
        }
        if (keys.isEmpty()) {
            throw new RemoteException("Player collections are off");
        }
        PlayerState held = state();
        byte[] envelope = source.fetch(PlayerPaths.index(id, indexSha256), Kind.INDEX.envelopeBytes());
        if (!JdkSha256.of(envelope).equals(indexSha256)) {
            throw new RemoteException("The index of " + id + " is not the one named");
        }
        Signed signed = PlayerSignatures.open(Kind.INDEX, envelope, keys, held.revokedKids());
        PlayerIndex index = index(signed.payload(), id, rev);
        byte[] record = source.fetch(PlayerPaths.record(id, index.recordSha256()), WorldContentLimits.RECORD_BYTES);
        if (record.length != index.recordSize() || !JdkSha256.of(record).equals(index.recordSha256())) {
            throw new RemoteException("The record of " + id + " is not the one its index names");
        }
        Revision built = build(id, rev, indexSha256, index, record);
        // Kept only now that all of it held, so a kept revision is always a whole one
        store.saveRecord(id, index.recordSha256(), record);
        store.saveIndex(indexSha256, signed.kid(), held.epoch(), signed.payload());
        return built;
    }

    /**
     * One revision from what this game keeps of it alone, with no network: how a world opens
     * offline. Empty when it is not kept, was accepted in an earlier epoch or under a key
     * revoked since, or no longer holds together.
     */
    public Optional<Revision> kept(String id, int rev, String indexSha256) throws IOException {
        PlayerState held = state();
        Optional<byte[]> payload = store.loadIndex(indexSha256, held.epoch(), held.revokedKids());
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            PlayerIndex index = index(payload.get(), id, rev);
            Optional<byte[]> record = store.loadRecord(id, index.recordSha256());
            return record.isEmpty() ? Optional.empty() : Optional.of(build(id, rev, indexSha256, index, record.get()));
        } catch (RemoteException e) {
            return Optional.empty();
        }
    }

    /** Deletes the files kept of a collection that is not to be loaded; its head stays believed. */
    public void discard(String id) throws IOException {
        store.delete(id);
    }

    /** Forgets a collection for good: what is kept of it and its head. */
    public void forget(String id) throws IOException {
        state().forgetHead(id);
        store.saveState(state());
        store.delete(id);
        store.deleteHead(id);
    }

    private static PlayerIndex index(byte[] payload, String id, int rev) throws RemoteException {
        PlayerIndex index = PlayerIndex.parse(payload);
        if (!index.id().equals(id) || index.rev() != rev) {
            throw new RemoteException("The index is of another collection or revision than " + id + " " + rev);
        }
        return index;
    }

    // The record against the facts its index gives, and the collection built from it and checked
    private static Revision build(String id, int rev, String indexSha256, PlayerIndex index, byte[] recordBytes)
            throws RemoteException {
        WorldCollectionRecord record;
        try {
            record = WorldCollectionRecord.parsePlayer(recordBytes, index.blobs(), id,
                    index.author() != null ? index.author() : "", rev);
        } catch (WorldContentException e) {
            throw new RemoteException("The record of " + id + " is refused: " + e.getMessage());
        }
        // An index lists exactly the files its record names
        if (!record.referencedShas().equals(index.blobs().keySet())) {
            throw new RemoteException("The index of " + id + " does not list exactly its record's files");
        }
        PlayerAssetIds ids = new PlayerAssetIds(id);
        FigureCollection collection = WorldCollectionMaterializer.materialize(record, index.blobs(), ids);
        String problem = WorldCollectionCheck.problem(collection, record, ids);
        if (problem != null) {
            throw new RemoteException("The collection " + id + " " + problem);
        }
        return new Revision(id, rev, indexSha256, index, record, collection);
    }
}
