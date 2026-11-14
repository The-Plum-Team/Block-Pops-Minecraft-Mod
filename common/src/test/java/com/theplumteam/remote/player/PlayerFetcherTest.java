package com.theplumteam.remote.player;

import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.remote.player.PlayerFetcher.Refused;
import com.theplumteam.remote.player.PlayerFetcher.Resolved;
import com.theplumteam.remote.player.PlayerFetcher.Revision;
import com.theplumteam.remote.player.PlayerFetcher.Why;
import com.theplumteam.remote.player.PlayerOrigin.Origin;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.theplumteam.remote.player.PlayerOrigin.CODE;
import static com.theplumteam.remote.player.PlayerOrigin.DAY;
import static com.theplumteam.remote.player.PlayerOrigin.ID;
import static com.theplumteam.remote.player.PlayerOrigin.KEYS;
import static com.theplumteam.remote.player.PlayerOrigin.O2;
import static com.theplumteam.remote.player.PlayerOrigin.R1;
import static com.theplumteam.remote.player.PlayerOrigin.SKIN;
import static com.theplumteam.remote.player.PlayerOrigin.START;
import static com.theplumteam.remote.player.PlayerOrigin.pair;
import static com.theplumteam.remote.player.PlayerOrigin.sign;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A code becomes a collection only through documents whose signatures hold, that are current,
 * and that agree with each other. The origin here is a map in memory, and the keys are made for
 * the test and sign nothing real.
 */
class PlayerFetcherTest {
    @TempDir
    Path cache;

    private PlayerFetcher fetcher(Origin origin) {
        return fetcher(origin, KEYS);
    }

    private PlayerFetcher fetcher(Origin origin, PlayerKeys keys) {
        return new PlayerFetcher(origin, new PlayerStore(new VerifiedStore(cache)), keys, () -> origin.now);
    }

    private static Why refused(PlayerFetcher fetcher, String code) {
        return assertThrows(Refused.class, () -> fetcher.resolve(code)).why();
    }

    @Test
    void aCodeResolvesToACollectionBuiltFromSignedDocuments() throws IOException, Refused {
        Origin origin = new Origin();
        origin.status(true);
        String indexSha = origin.publish(5, 7, "Mis figuras", "Steve");
        PlayerFetcher fetcher = fetcher(origin);

        Resolved resolved = fetcher.resolve(CODE);
        assertEquals(ID, resolved.head().id());
        assertEquals(5, resolved.head().rev());
        assertArrayEquals(origin.files.get(PlayerPaths.head(CODE)), resolved.headEnvelope());
        Revision revision = resolved.revision();
        assertEquals(indexSha, revision.indexSha256());
        assertEquals("Mis figuras", revision.collection().getName());
        assertEquals("Steve", revision.collection().getAuthor());
        assertEquals(ID, revision.collection().getId());
        assertEquals("blockpops:textures/player/" + ID + "/" + SKIN + ".png",
                revision.collection().getFigures().get(0).getTexturePath().toString());
        // Only documents were asked for, each at its computed path; no file of the collection
        assertEquals(List.of("root/status.json", "p/status.json", "p/revoked.json", "p/codes/maple-orbit-42.json",
                "p/c/" + ID + "/i/" + indexSha + ".json", "p/c/" + ID + "/r/" + revision.index().recordSha256() + ".json"),
                origin.asked);

        // A collection nobody is named for
        origin.publish(6, 8, "Sin autor", null);
        assertEquals("Unknown", fetcher.resolve(CODE).revision().collection().getAuthor());
        // What was confirmed moments ago is not fetched again; after five minutes it is
        origin.asked.clear();
        fetcher.resolve(CODE);
        assertFalse(origin.asked.contains("p/status.json"));
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        fetcher.resolve(CODE);
        assertTrue(origin.asked.contains("p/status.json"));
    }

    @Test
    void whatIsNotThereOrNotOnIsRefusedAsSuch() throws IOException {
        Origin origin = new Origin();
        // No status published at all: nothing is confirmed, so nothing loads
        assertThrows(RemoteNotFoundException.class, () -> fetcher(origin).resolve(CODE));
        origin.status(true);
        PlayerFetcher fetcher = fetcher(origin);
        assertEquals(Why.UNKNOWN_CODE, refused(fetcher, CODE));
        assertEquals(Why.UNKNOWN_CODE, refused(fetcher, "willow-media"));
        assertEquals(Why.UNKNOWN_CODE, refused(fetcher, "../../manifest"));
        assertFalse(origin.asked.stream().anyMatch(path -> path.contains("willow") || path.contains("manifest")));

        // A build with no key asks nothing and loads nothing
        Origin untouched = new Origin();
        untouched.status(true);
        untouched.publish(1, 1, "Col", "Steve");
        PlayerFetcher keyless = fetcher(untouched, PlayerKeys.of(new String[0][]));
        assertEquals(Why.OFF, refused(keyless, CODE));
        keyless.refresh();
        assertTrue(untouched.asked.isEmpty());

        // The status turns loading off; then a root does, whatever the status says
        origin.publish(1, 1, "Col", "Steve");
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        origin.status(false);
        assertEquals(Why.OFF, refused(fetcher, CODE));
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        origin.status(true);
        assertEquals(ID, assertDoesNotThrowResolve(fetcher).head().id());
        origin.files.put(PlayerPaths.ROOT_STATUS, sign("r1", R1, new PlayerRoot(1, origin.now, false, List.of()).toPayload()));
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        origin.listSeq = 0;
        origin.statusSeq = 0;
        origin.status(true);
        assertEquals(Why.OFF, refused(fetcher, CODE));
        assertEquals(1, fetcher.state().epoch());
    }

    private static Resolved assertDoesNotThrowResolve(PlayerFetcher fetcher) {
        try {
            return fetcher.resolve(CODE);
        } catch (IOException | Refused e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aCollectionThatWasTakenDownDoesNotLoad() throws IOException, Refused {
        Origin origin = new Origin();
        origin.status(true);
        String indexSha = origin.publish(5, 7, "Col", "Steve");
        PlayerFetcher fetcher = fetcher(origin);
        fetcher.resolve(CODE);
        assertTrue(fetcher.state().keeps(ID, 5));

        // Removed: its head says so, and the revocation list names it
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        origin.head(new PlayerHead(8, origin.now, CODE, ID, PlayerHead.State.REMOVED, 5, null, PlayerHead.Reason.POLICY));
        origin.status(true, new PlayerRevocations.Collection(ID, true));
        assertEquals(Why.UNAVAILABLE, refused(fetcher, CODE));
        assertFalse(fetcher.state().keeps(ID, 5));
        assertEquals(Why.UNAVAILABLE, assertThrows(Refused.class, () -> fetcher.poll(CODE, ID)).why());

        // A head replayed from before the removal is older than the one held, and is refused
        Origin replay = new Origin();
        replay.now = origin.now;
        replay.files.putAll(origin.files);
        replay.head(new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null));
        PlayerFetcher replayed = new PlayerFetcher(replay, new PlayerStore(new VerifiedStore(cache)), KEYS, () -> replay.now);
        assertThrows(RemoteException.class, () -> replayed.resolve(CODE));
        // And a quarantined collection whose head still says live is refused by the list alone
        Origin quarantined = new Origin();
        quarantined.status(true, new PlayerRevocations.Collection(ID, false));
        quarantined.publish(1, 1, "Col", "Steve");
        Path other = Files.createDirectory(cache.resolve("other"));
        PlayerFetcher second = new PlayerFetcher(quarantined, new PlayerStore(new VerifiedStore(other)), KEYS, () -> START);
        assertEquals(Why.UNAVAILABLE, refused(second, CODE));
        fetcher.forget(ID);
        assertFalse(Files.exists(cache.resolve("p/c/" + ID)));
    }

    @Test
    void documentsThatDoNotAgreeOrWereNotSignedAreNeverBuiltFrom() throws IOException, Refused {
        Origin origin = new Origin();
        origin.status(true);
        String indexSha = origin.publish(5, 7, "Col", "Steve");
        String indexPath = PlayerPaths.index(ID, indexSha);
        byte[] index = origin.files.get(indexPath);
        PlayerIndex parsed = PlayerIndex.parse(SignedEnvelope.parse(index, PlayerIndex.ENVELOPE_BYTES).payload());
        String recordPath = PlayerPaths.record(ID, parsed.recordSha256());
        byte[] record = origin.files.get(recordPath);

        // The head under the code is another code's, or signed by a stranger, or by the root key
        origin.head(new PlayerHead(7, START, "birch-comet-10", ID, PlayerHead.State.LIVE, 5, indexSha, null));
        assertThrows(RemoteException.class, () -> fetcher(origin).resolve(CODE));
        origin.files.put(PlayerPaths.head(CODE), sign("o1", pair(),
                new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null).toPayload()));
        assertThrows(RemoteException.class, () -> fetcher(origin).resolve(CODE));
        origin.files.put(PlayerPaths.head(CODE), sign("r1", R1,
                new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null).toPayload()));
        assertThrows(RemoteException.class, () -> fetcher(origin).resolve(CODE));
        origin.head(new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null));

        // Another index under the named path: its bytes are not the ones the head names
        Origin swapped = new Origin();
        swapped.files.putAll(origin.files);
        String otherSha = swapped.publish(5, 7, "Otra", "Alex");
        swapped.files.put(indexPath, swapped.files.get(PlayerPaths.index(ID, otherSha)));
        swapped.head(new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null));
        assertThrows(RemoteException.class, () -> fetcher(swapped).resolve(CODE));
        // An index of another revision than the head says
        Origin revision = new Origin();
        revision.files.putAll(origin.files);
        revision.head(new PlayerHead(7, START, CODE, ID, PlayerHead.State.LIVE, 6, indexSha, null));
        assertThrows(RemoteException.class, () -> fetcher(revision).resolve(CODE));
        // A record that is not the one the index names, by a byte
        byte[] changed = record.clone();
        changed[10] ^= 1;
        origin.files.put(recordPath, changed);
        assertThrows(RemoteException.class, () -> fetcher(origin).resolve(CODE));
        origin.files.put(recordPath, record);
        // Nothing of a refused collection was kept, beyond the genuine head of the last cases
        assertFalse(Files.exists(cache.resolve("p/verified/i")));
        assertFalse(Files.exists(cache.resolve("p/c")));
        assertEquals(ID, fetcher(origin).resolve(CODE).head().id());
        assertTrue(Files.exists(cache.resolve("p/verified/i/" + indexSha + ".json")));
    }

    @Test
    void aWorldOpensFromWhatWasKeptWhenTheOriginCannotBeReached() throws IOException, Refused {
        Origin origin = new Origin();
        origin.status(true);
        String indexSha = origin.publish(5, 7, "Col", "Steve");
        fetcher(origin).resolve(CODE);

        // A new run with no network: the kept revision is built again, and stays loaded
        origin.down = true;
        origin.asked.clear();
        PlayerFetcher offline = fetcher(origin);
        Revision kept = offline.kept(ID, 5, indexSha).orElseThrow();
        assertEquals("Col", kept.collection().getName());
        assertEquals(kept.collection().toJson(), offline.revision(ID, 5, indexSha).collection().toJson());
        assertTrue(origin.asked.isEmpty());
        assertTrue(offline.state().keeps(ID, 5));
        // Nothing new is added while it cannot be confirmed, even once the status would have expired
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        assertThrows(RemoteException.class, () -> offline.resolve(CODE));
        assertThrows(RemoteException.class, offline::refresh);
        origin.now += 4 * DAY;
        assertTrue(offline.state().keeps(ID, 5));
        assertFalse(offline.state().loading(origin.now));
        // What is not kept is not made up
        assertTrue(offline.kept(ID, 4, indexSha).isEmpty());
        assertTrue(offline.kept(ID, 5, JdkSha256.of(new byte[1])).isEmpty());
        // A kept record that was changed on disk no longer is the one its index names
        Path record = cache.resolve("p/c/" + ID + "/r/" + kept.index().recordSha256() + ".json");
        Files.writeString(record, Files.readString(record).replace("Col", "Cal"));
        assertTrue(fetcher(origin).kept(ID, 5, indexSha).isEmpty());
        // And a kept state that does not hold together starts from nothing, set aside
        Files.writeString(cache.resolve("p/state.json"), "{\"schema\":1}");
        assertEquals(0, fetcher(origin).state().epoch());
        assertTrue(Files.exists(cache.resolve("p/state.json.corrupt")));
    }

    @Test
    void aRevokedKeyIsBelievedNoLongerAndWhatItSignedIsFetchedAgain() throws IOException, Refused {
        Origin origin = new Origin();
        origin.status(true);
        String indexSha = origin.publish(5, 7, "Col", "Steve");
        PlayerFetcher fetcher = fetcher(origin);
        fetcher.resolve(CODE);

        // The root revokes the online key; the service has signed everything again with the spare
        origin.now += PlayerState.CONFIRM_SECONDS + 1;
        origin.files.put(PlayerPaths.ROOT_STATUS,
                sign("r1", R1, new PlayerRoot(1, origin.now, true, List.of("o1")).toPayload()));
        origin.kid = "o2";
        origin.signer = O2;
        origin.listSeq = 0;
        origin.statusSeq = 0;
        origin.status(true);
        String resigned = origin.publish(5, 1, "Col", "Steve");
        Resolved resolved = fetcher.resolve(CODE);
        assertEquals(1, fetcher.state().epoch());
        assertEquals(resigned, resolved.revision().indexSha256());
        // What the revoked key signed is not given back from what was kept
        assertTrue(fetcher.kept(ID, 5, indexSha).isEmpty());
        assertTrue(fetcher.kept(ID, 5, resigned).isPresent());
        // And documents still signed by the revoked key are refused even if someone serves them
        Origin stale = new Origin();
        stale.now = origin.now;
        stale.files.putAll(origin.files);
        stale.head(new PlayerHead(9, stale.now, CODE, ID, PlayerHead.State.LIVE, 5, indexSha, null));
        PlayerFetcher behind = new PlayerFetcher(stale, new PlayerStore(new VerifiedStore(cache)), KEYS, () -> stale.now);
        assertThrows(RemoteException.class, () -> behind.resolve(CODE));
    }
}
