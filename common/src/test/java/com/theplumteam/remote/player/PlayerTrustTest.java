package com.theplumteam.remote.player;

import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.player.PlayerSignatures.Kind;
import com.theplumteam.remote.player.PlayerSignatures.Signed;
import com.theplumteam.remote.player.PlayerState.Verdict;
import com.theplumteam.worldcontent.JdkSha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which signed documents a game believes: the right key for the kind, a signature that holds,
 * and then a document that is current. The keys here are made for the test and sign nothing real.
 */
class PlayerTrustTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String OTHER = "p_aaaaaaaaaaaa";
    private static final String CODE = "maple-orbit-42";
    private static final String SHA = JdkSha256.of("a".getBytes(StandardCharsets.UTF_8));
    private static final long NOW = 1_794_050_000L;
    private static final long DAY = 24 * 60 * 60;

    private static final KeyPair O1 = pair();
    private static final KeyPair O2 = pair();
    private static final KeyPair R1 = pair();
    private static final KeyPair STRANGER = pair();
    private static final PlayerKeys KEYS = PlayerKeys.of(new String[][] {
        {"o1", "online", spki(O1)}, {"o2", "online", spki(O2)}, {"r1", "root", spki(R1)}});

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static String spki(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static byte[] envelope(String kid, KeyPair signer, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signer.getPrivate());
            signature.update(payload);
            return new SignedEnvelope(kid, payload, signature.sign()).toUtf8();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static PlayerRevocations list(long seq, long issuedAt, PlayerRevocations.Collection... collections) {
        return new PlayerRevocations(seq, issuedAt, issuedAt + 3 * DAY, List.of(collections), List.of());
    }

    private static PlayerStatus status(long seq, long issuedAt, boolean loading, PlayerRevocations named, String kid,
                                       KeyPair signer) {
        return new PlayerStatus(seq, issuedAt, issuedAt + 3 * DAY, loading, named.seq(),
                JdkSha256.of(envelope(kid, signer, named.toPayload())), 900, 1800, 3600);
    }

    private static PlayerHead head(long seq, int rev) {
        return new PlayerHead(seq, NOW, CODE, ID, PlayerHead.State.LIVE, rev, SHA, null);
    }

    /** A state that has accepted a status and the list it names, both signed by o1, and confirmed them. */
    private static PlayerState loaded() throws RemoteException {
        PlayerState state = new PlayerState();
        PlayerRevocations revocations = list(42, NOW);
        state.acceptRevocations(revocations, "o1", JdkSha256.of(envelope("o1", O1, revocations.toPayload())), NOW);
        state.acceptStatus(status(12, NOW, true, revocations, "o1", O1), "o1", NOW);
        state.confirmed(NOW);
        return state;
    }

    // ---- Signatures

    @Test
    void aDocumentOpensOnlyUnderAKeyThatMaySignItsKind() throws RemoteException {
        byte[] payload = head(7, 5).toPayload();
        Signed signed = PlayerSignatures.open(Kind.HEAD, envelope("o1", O1, payload), KEYS, Set.of());
        assertEquals("o1", signed.kid());
        assertArrayEquals(payload, signed.payload());
        assertEquals(head(7, 5), PlayerHead.parse(signed.payload()));
        assertEquals("o2", PlayerSignatures.open(Kind.STATUS, envelope("o2", O2, payload), KEYS, Set.of()).kid());
        byte[] root = new PlayerRoot(1, NOW, false, List.of("o1")).toPayload();
        assertEquals("r1", PlayerSignatures.open(Kind.ROOT, envelope("r1", R1, root), KEYS, Set.of()).kid());

        // Signed by the wrong private key, by a key the build does not have, and under a borrowed name
        refused(Kind.HEAD, envelope("o1", O2, payload), Set.of(), "does not hold");
        refused(Kind.HEAD, envelope("o1", STRANGER, payload), Set.of(), "does not hold");
        refused(Kind.HEAD, envelope("o3", STRANGER, payload), Set.of(), "unknown key");
        // A key outside its role: an online key on a root, the root key on anything else
        refused(Kind.ROOT, envelope("o1", O1, root), Set.of(), "may not sign");
        for (Kind kind : new Kind[] {Kind.HEAD, Kind.INDEX, Kind.STATUS, Kind.REVOCATIONS}) {
            refused(kind, envelope("r1", R1, payload), Set.of(), "may not sign");
        }
        // A key the root revoked, even with a signature that holds
        refused(Kind.HEAD, envelope("o1", O1, payload), Set.of("o1"), "revoked key");
        assertEquals("o2", PlayerSignatures.open(Kind.HEAD, envelope("o2", O2, payload), KEYS, Set.of("o1")).kid());

        // The payload or the signature changed after signing
        byte[] other = head(8, 5).toPayload();
        byte[] signature = SignedEnvelope.parse(envelope("o1", O1, payload), 4096).signature();
        refused(Kind.HEAD, new SignedEnvelope("o1", other, signature).toUtf8(), Set.of(), "does not hold");
        byte[] flipped = signature.clone();
        flipped[10] ^= 1;
        refused(Kind.HEAD, new SignedEnvelope("o1", payload, flipped).toUtf8(), Set.of(), "does not hold");
        // An envelope larger than its kind may be is not read at all
        byte[] large = new byte[PlayerHead.ENVELOPE_BYTES];
        assertThrows(RemoteException.class,
                () -> PlayerSignatures.open(Kind.HEAD, envelope("o1", O1, large), KEYS, Set.of()));
        assertEquals(Kind.INDEX.envelopeBytes(),
                PlayerSignatures.open(Kind.INDEX, envelope("o1", O1, large), KEYS, Set.of()).payload().length * 32);
    }

    private static void refused(Kind kind, byte[] envelope, Set<String> revoked, String why) {
        RemoteException refusal = assertThrows(RemoteException.class,
                () -> PlayerSignatures.open(kind, envelope, KEYS, revoked));
        assertTrue(refusal.getMessage().contains(why), refusal.getMessage());
    }

    @Test
    void aBuildWithNoKeyBelievesNothing() {
        assertTrue(PlayerKeys.builtIn().isEmpty());
        assertNull(PlayerKeys.builtIn().role("o1"));
        assertFalse(PlayerKeys.builtIn().verifies("o1", new byte[1], new byte[64]));
        assertThrows(RemoteException.class, () -> PlayerSignatures.open(Kind.HEAD,
                envelope("o1", O1, head(7, 5).toPayload()), PlayerKeys.builtIn(), Set.of()));
        assertEquals(Set.of("o1", "o2", "r1"), KEYS.kids());
        assertEquals(PlayerKeys.Role.ROOT, KEYS.role("r1"));
        for (String[][] bad : new String[][][] {{{"o1", "online"}}, {{"O1", "online", spki(O1)}},
            {{"o1", "admin", spki(O1)}}, {{"o1", "online", "not base64!"}}, {{"o1", "online", "AAAA"}},
            {{"o1", "online", spki(O1)}, {"o1", "online", spki(O2)}}}) {
            assertThrows(IllegalArgumentException.class, () -> PlayerKeys.of(bad));
        }
    }

    // ---- Sequence numbers

    @Test
    void aDocumentIsAcceptedOnlyWhenItIsNewerByAPlausibleStep() throws RemoteException {
        PlayerState state = new PlayerState();
        assertEquals(Verdict.ACCEPTED, state.acceptHead(head(7, 5), "o1", NOW));
        assertEquals(Verdict.UNCHANGED, state.acceptHead(head(7, 5), "o1", NOW + 60));
        assertTrue(state.headVerifiedSince(ID, NOW + 60));
        assertFalse(state.headVerifiedSince(ID, NOW + 61));
        // The same number on another document, and a lower number
        assertThrows(RemoteException.class, () -> state.acceptHead(head(7, 6), "o1", NOW));
        assertThrows(RemoteException.class, () -> state.acceptHead(head(6, 4), "o1", NOW));
        assertEquals(head(7, 5), state.head(ID));
        // A step no real document takes, and the largest one that is taken
        assertThrows(RemoteException.class,
                () -> state.acceptHead(head(7 + PlayerRules.MAX_SEQ_JUMP + 1, 6), "o1", NOW));
        assertEquals(Verdict.ACCEPTED, state.acceptHead(head(7 + PlayerRules.MAX_SEQ_JUMP, 6), "o1", NOW));
        // Issued more than a day ahead of this clock
        PlayerHead ahead = new PlayerHead(8 + PlayerRules.MAX_SEQ_JUMP, NOW + DAY + 1, CODE, ID, PlayerHead.State.LIVE,
                7, SHA, null);
        assertThrows(RemoteException.class, () -> state.acceptHead(ahead, "o1", NOW));
        assertEquals(Verdict.ACCEPTED, state.acceptHead(ahead, "o1", NOW + 1));
        // Each collection has its own numbers
        PlayerHead another = new PlayerHead(1, NOW, "birch-comet-10", OTHER, PlayerHead.State.LIVE, 1, SHA, null);
        assertEquals(Verdict.ACCEPTED, state.acceptHead(another, "o1", NOW));
        assertNull(state.head("p_bbbbbbbbbbbb"));
        state.forgetHead(OTHER);
        assertNull(state.head(OTHER));

        PlayerState loaded = loaded();
        PlayerRevocations older = list(41, NOW);
        assertThrows(RemoteException.class, () -> loaded.acceptRevocations(older, "o1", SHA, NOW));
        assertThrows(RemoteException.class,
                () -> loaded.acceptStatus(status(11, NOW, false, list(42, NOW), "o1", O1), "o1", NOW));
        assertThrows(RemoteException.class,
                () -> loaded.acceptStatus(status(12, NOW, false, list(42, NOW), "o1", O1), "o1", NOW));
        assertEquals(Verdict.UNCHANGED,
                loaded.acceptStatus(status(12, NOW, true, list(42, NOW), "o1", O1), "o1", NOW));
        assertTrue(loaded.loading(NOW));
    }

    // ---- Freshness and the revocation list

    @Test
    void loadingNeedsAFreshStatusAndTheListItNames() throws RemoteException {
        PlayerState empty = new PlayerState();
        assertFalse(empty.loading(NOW));
        assertFalse(empty.mayAdd(NOW));
        assertFalse(empty.keeps(ID, 5));

        PlayerState state = loaded();
        assertTrue(state.loading(NOW));
        assertTrue(state.statusFresh(NOW + 3 * DAY - 1));
        assertTrue(state.mayAdd(ID, 5, NOW + PlayerState.CONFIRM_SECONDS));
        // Confirmed too long ago: what is loaded stays, nothing new is added
        assertFalse(state.mayAdd(ID, 5, NOW + PlayerState.CONFIRM_SECONDS + 1));
        assertTrue(state.loading(NOW + PlayerState.CONFIRM_SECONDS + 1));
        assertTrue(state.keeps(ID, 5));
        // Expired: off for anything new, kept for what was verified, so a world opens offline
        assertFalse(state.statusFresh(NOW + 3 * DAY));
        assertFalse(state.loading(NOW + 3 * DAY));
        assertFalse(state.mayAdd(NOW + 3 * DAY));
        assertTrue(state.keeps(ID, 5));

        // A newer status that names a list this game does not hold yet
        PlayerRevocations next = list(43, NOW + 60, new PlayerRevocations.Collection(ID, true));
        assertEquals(Verdict.ACCEPTED, state.acceptStatus(status(13, NOW + 60, true, next, "o1", O1), "o1", NOW + 60));
        assertFalse(state.revocationsInForce(NOW + 60));
        assertFalse(state.loading(NOW + 60));
        assertFalse(state.mayAdd(OTHER, 1, NOW + 60));
        assertTrue(state.keeps(ID, 5));
        // The list arrives: in force again, and the collection it removes goes
        String sha = JdkSha256.of(envelope("o1", O1, next.toPayload()));
        assertEquals(Verdict.ACCEPTED, state.acceptRevocations(next, "o1", sha, NOW + 60));
        state.confirmed(NOW + 60);
        assertTrue(state.loading(NOW + 60));
        assertFalse(state.keeps(ID, 5));
        assertFalse(state.mayAdd(ID, 5, NOW + 60));
        assertTrue(state.mayAdd(OTHER, 1, NOW + 60));
        assertTrue(state.keeps(OTHER, 1));

        // A list of the right number in another envelope is not the one named
        PlayerState wrong = loaded();
        PlayerRevocations renamed = list(43, NOW + 60);
        wrong.acceptStatus(status(13, NOW + 60, true, renamed, "o1", O1), "o1", NOW + 60);
        wrong.acceptRevocations(renamed, "o2", JdkSha256.of(envelope("o2", O2, renamed.toPayload())), NOW + 60);
        assertFalse(wrong.revocationsInForce(NOW + 60));
        // The same list in the envelope the status names replaces it, and is then in force
        assertEquals(Verdict.ACCEPTED, wrong.acceptRevocations(renamed, "o1",
                JdkSha256.of(envelope("o1", O1, renamed.toPayload())), NOW + 60));
        assertTrue(wrong.revocationsInForce(NOW + 60));

        // The status itself turns loading off: everything goes
        PlayerState off = loaded();
        off.acceptStatus(status(13, NOW, false, list(42, NOW), "o1", O1), "o1", NOW);
        assertFalse(off.loading(NOW));
        assertFalse(off.keeps(ID, 5));

        // A single revision withdrawn leaves the collection's other revisions
        PlayerState withdrawn = loaded();
        PlayerRevocations revision = new PlayerRevocations(43, NOW, NOW + 3 * DAY, List.of(),
                List.of(new PlayerRevocations.Revision(ID, 4, false)));
        withdrawn.acceptRevocations(revision, "o1", JdkSha256.of(envelope("o1", O1, revision.toPayload())), NOW);
        withdrawn.acceptStatus(status(13, NOW, true, revision, "o1", O1), "o1", NOW);
        assertFalse(withdrawn.keeps(ID, 4));
        assertTrue(withdrawn.keeps(ID, 5));
        assertFalse(withdrawn.mayAdd(ID, 4, NOW));
        assertTrue(withdrawn.mayAdd(ID, 5, NOW));
    }

    // ---- The root and its epochs

    @Test
    void aRootStartsAnEpochThatForgetsEverythingElse() throws RemoteException {
        // The online key signs with numbers at the ceiling, as a stolen key would to lock others out
        PlayerState state = new PlayerState();
        long ceiling = PlayerRules.MAX_SEQ;
        PlayerRevocations inflatedList = list(ceiling, NOW);
        state.acceptRevocations(inflatedList, "o1", JdkSha256.of(envelope("o1", O1, inflatedList.toPayload())), NOW);
        state.acceptStatus(status(ceiling, NOW, true, inflatedList, "o1", O1), "o1", NOW);
        state.acceptHead(head(ceiling, 5), "o1", NOW);
        state.confirmed(NOW);
        assertTrue(state.loading(NOW));
        assertEquals(0, state.epoch());
        assertEquals(Set.of(), state.revokedKids());

        // The root revokes that key: a new epoch, with nothing held but the root
        PlayerRoot root = new PlayerRoot(1, NOW, true, List.of("o1"));
        assertEquals(Verdict.NEW_EPOCH, state.acceptRoot(root, "r1", NOW));
        assertEquals(1, state.epoch());
        assertEquals(Set.of("o1"), state.revokedKids());
        assertNull(state.status());
        assertNull(state.revocations());
        assertNull(state.head(ID));
        assertFalse(state.loading(NOW));
        assertFalse(state.keeps(ID, 5));
        assertEquals(Verdict.UNCHANGED, state.acceptRoot(root, "r1", NOW));

        // Documents of the spare key at ordinary numbers are believed again, with no release
        PlayerRevocations fresh = list(1, NOW);
        state.acceptRevocations(fresh, "o2", JdkSha256.of(envelope("o2", O2, fresh.toPayload())), NOW);
        state.acceptStatus(status(1, NOW, true, fresh, "o2", O2), "o2", NOW);
        assertEquals(Verdict.ACCEPTED, state.acceptHead(head(1, 6), "o2", NOW));
        state.confirmed(NOW);
        assertTrue(state.mayAdd(ID, 6, NOW));

        // A root never goes back, and its number names one document
        assertThrows(RemoteException.class,
                () -> state.acceptRoot(new PlayerRoot(1, NOW, false, List.of()), "r1", NOW));
        PlayerState later = loaded();
        later.acceptRoot(new PlayerRoot(5, NOW, true, List.of()), "r1", NOW);
        assertThrows(RemoteException.class,
                () -> later.acceptRoot(new PlayerRoot(4, NOW, true, List.of()), "r1", NOW));
        assertThrows(RemoteException.class,
                () -> later.acceptRoot(new PlayerRoot(6, NOW + DAY + 1, true, List.of()), "r1", NOW));
        assertEquals(5, later.epoch());

        // A root that turns loading off holds whatever the online documents say
        PlayerState off = loaded();
        assertEquals(Verdict.NEW_EPOCH, off.acceptRoot(new PlayerRoot(1, NOW, false, List.of()), "r1", NOW));
        PlayerRevocations again = list(1, NOW);
        off.acceptRevocations(again, "o1", JdkSha256.of(envelope("o1", O1, again.toPayload())), NOW);
        off.acceptStatus(status(1, NOW, true, again, "o1", O1), "o1", NOW);
        off.confirmed(NOW);
        assertFalse(off.loading(NOW));
        assertFalse(off.mayAdd(ID, 5, NOW));
        assertFalse(off.keeps(ID, 5));
        // Until a higher root turns it on again
        assertEquals(Verdict.NEW_EPOCH, off.acceptRoot(new PlayerRoot(2, NOW, true, List.of()), "r1", NOW));
        assertFalse(off.loading(NOW));
        off.acceptRevocations(again, "o1", JdkSha256.of(envelope("o1", O1, again.toPayload())), NOW);
        off.acceptStatus(status(1, NOW, true, again, "o1", O1), "o1", NOW);
        assertTrue(off.loading(NOW));
    }

    // ---- Storing

    @Test
    void theStateIsStoredAndReadBackWhole() throws RemoteException {
        PlayerState empty = new PlayerState();
        assertEquals("{\"schema\":1,\"epoch\":0,\"root\":null,\"status\":null,\"revocations\":null,\"checked_at\":0,"
                + "\"heads\":{}}", new String(empty.toUtf8(), StandardCharsets.UTF_8));
        assertArrayEquals(empty.toUtf8(), PlayerState.parse(empty.toUtf8()).toUtf8());

        PlayerState state = loaded();
        state.acceptRoot(new PlayerRoot(3, NOW, true, List.of("o1")), "r1", NOW);
        PlayerRevocations revocations = list(2, NOW, new PlayerRevocations.Collection(OTHER, false));
        state.acceptRevocations(revocations, "o2", JdkSha256.of(envelope("o2", O2, revocations.toPayload())), NOW);
        state.acceptStatus(status(2, NOW, true, revocations, "o2", O2), "o2", NOW);
        state.acceptHead(head(9, 5), "o2", NOW + 7);
        state.confirmed(NOW + 9);

        PlayerState read = PlayerState.parse(state.toUtf8());
        assertArrayEquals(state.toUtf8(), read.toUtf8());
        assertEquals(3, read.epoch());
        assertEquals(Set.of("o1"), read.revokedKids());
        assertEquals(state.status(), read.status());
        assertEquals(state.revocations(), read.revocations());
        assertEquals(head(9, 5), read.head(ID));
        assertTrue(read.headVerifiedSince(ID, NOW + 7));
        assertTrue(read.mayAdd(ID, 5, NOW + 9));
        assertFalse(read.keeps(OTHER, 1));
        // What was read goes on by the same rules
        assertThrows(RemoteException.class, () -> read.acceptHead(head(8, 5), "o2", NOW + 9));
        assertThrows(RemoteException.class,
                () -> read.acceptRoot(new PlayerRoot(2, NOW, true, List.of()), "r1", NOW + 9));

        String stored = new String(state.toUtf8(), StandardCharsets.UTF_8);
        for (String bad : new String[] {
            stored.replace("\"epoch\":3", "\"epoch\":4"),
            stored.replace("\"epoch\":3", "\"epoch\":0"),
            stored.replace("\"schema\":1", "\"schema\":2"),
            stored.replace("\"" + ID + "\":", "\"" + OTHER + "\":"),
            stored.replace("\"kid\":\"o2\"", "\"kid\":\"o1\""),
            stored.replace("\"checked_at\":", "\"extra\":1,\"checked_at\":"),
            stored.replace("{\"schema\"", "{ \"schema\""),
            stored + " ",
            "{}", "[]", ""}) {
            assertThrows(RemoteException.class, () -> PlayerState.parse(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        // A stored epoch with no root to name it
        assertThrows(RemoteException.class, () -> PlayerState.parse(
                "{\"schema\":1,\"epoch\":2,\"root\":null,\"status\":null,\"revocations\":null,\"checked_at\":0,\"heads\":{}}"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
