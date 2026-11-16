package com.theplumteam.remote.player;

import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.WorldCollectionRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * What tests of player collections read instead of the CDN: one collection published as the
 * upload service would, in a map in memory. The keys are made for the tests and sign nothing
 * real.
 */
public final class PlayerOrigin {
    private PlayerOrigin() {
    }

    public static final String ID = "p_k3v7q2m5x4ab";
    public static final String CODE = "maple-orbit-42";
    public static final String SKIN = JdkSha256.of("skin".getBytes(StandardCharsets.UTF_8));
    public static final long START = 1_794_050_000L;
    public static final long DAY = 24 * 60 * 60;

    public static final KeyPair O1 = pair();
    public static final KeyPair O2 = pair();
    public static final KeyPair R1 = pair();
    public static final PlayerKeys KEYS = PlayerKeys.of(new String[][] {
        {"o1", "online", spki(O1)}, {"o2", "online", spki(O2)}, {"r1", "root", spki(R1)}});

    public static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    public static String spki(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    public static byte[] sign(String kid, KeyPair signer, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signer.getPrivate());
            signature.update(payload);
            return new SignedEnvelope(kid, payload, signature.sign()).toUtf8();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    /** What the upload service would have published: an origin as a map of paths to bytes. */
    public static final class Origin implements RemoteSource {
        public final Map<String, byte[]> files = new HashMap<>();
        public final List<String> asked = new ArrayList<>();
        public boolean down;
        public long now = START;
        public long statusSeq;
        public long listSeq;
        public String kid = "o1";
        public KeyPair signer = O1;

        @Override
        public byte[] fetch(String path, long maxBytes) throws IOException {
            asked.add(path);
            if (down) {
                throw new RemoteException("The origin cannot be reached");
            }
            byte[] bytes = files.get(path);
            if (bytes == null) {
                throw new RemoteNotFoundException("Not found: " + path);
            }
            if (bytes.length > maxBytes) {
                throw new RemoteException(path + " is too large");
            }
            return bytes;
        }

        /** Publishes a revocation list and the status that names it, as the service does every day. */
        public void status(boolean loading, PlayerRevocations.Collection... revoked) {
            status(loading, List.of(revoked), List.of());
        }

        public void status(boolean loading, List<PlayerRevocations.Collection> collections,
                           List<PlayerRevocations.Revision> revisions) {
            PlayerRevocations list = new PlayerRevocations(++listSeq, now, now + 3 * DAY, collections, revisions);
            byte[] envelope = sign(kid, signer, list.toPayload());
            files.put(PlayerPaths.REVOCATIONS, envelope);
            files.put(PlayerPaths.STATUS, sign(kid, signer, new PlayerStatus(++statusSeq, now, now + 3 * DAY, loading,
                    list.seq(), JdkSha256.of(envelope), 900, 1800, 3600).toPayload()));
        }

        /** Publishes a revision of the collection and makes it the head; says the index's SHA-256. */
        public String publish(int rev, long seq, String name, String author) {
            WorldCollectionRecord record = new WorldCollectionRecord("w-col", name, "", 10, 20, 30, null, "red", null, false,
                    0, List.of(new WorldCollectionRecord.Figure("zombi", "Zombi", WorldCollectionRecord.Kind.SKIN, SKIN,
                            null, null, 1.0f, true)));
            byte[] recordBytes = record.toPlayerUtf8();
            String recordSha = JdkSha256.of(recordBytes);
            files.put(PlayerPaths.record(ID, recordSha), recordBytes);
            Map<String, BlobInfo> blobs = new LinkedHashMap<>();
            blobs.put(SKIN, BlobInfo.png(1_000, 64, 64));
            byte[] index = sign(kid, signer, new PlayerIndex(ID, rev, now, author, recordSha, recordBytes.length, blobs, 1,
                    JdkSha256.of(new byte[0])).toPayload());
            String indexSha = JdkSha256.of(index);
            files.put(PlayerPaths.index(ID, indexSha), index);
            head(new PlayerHead(seq, now, CODE, ID, PlayerHead.State.LIVE, rev, indexSha, null));
            return indexSha;
        }

        public void head(PlayerHead head) {
            files.put(PlayerPaths.head(CODE), sign(kid, signer, head.toPayload()));
        }
    }
}
