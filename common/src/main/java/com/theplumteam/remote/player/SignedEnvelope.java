package com.theplumteam.remote.player;

import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;

import java.nio.charset.StandardCharsets;

/**
 * A signed document as it is published: who signed, the exact bytes that were signed, and the
 * signature over them.
 *
 * <pre>{"kid":"o1","payload":"&lt;base64url&gt;","sig":"&lt;base64url, 64 bytes&gt;"}</pre>
 *
 * The signature covers the decoded payload bytes, so nothing is ever put into a canonical form
 * to check it. Reading one here checks only its shape. Whether the key may sign that kind of
 * document, the signature itself and the payload's own schema come after, in that order, and
 * checking the signature is the host's: this class holds no key.
 *
 * @param kid       the id of the key that signed
 * @param payload   the signed bytes: a head, an index, a status, a revocation list or a root
 * @param signature the 64 bytes of an Ed25519 signature
 */
public record SignedEnvelope(String kid, byte[] payload, byte[] signature) {
    public static final int SIGNATURE_BYTES = 64;
    private static final int MAX_KID_LENGTH = 8;
    private static final String[] KEYS = {"kid", "payload", "sig"};

    public SignedEnvelope {
        if (!isKid(kid) || payload == null || signature == null || signature.length != SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Invalid envelope");
        }
    }

    /** Whether the text may name a key: a lowercase letter and up to seven letters or digits. */
    public static boolean isKid(String kid) {
        if (kid == null || kid.isEmpty() || kid.length() > MAX_KID_LENGTH || kid.charAt(0) < 'a' || kid.charAt(0) > 'z') {
            return false;
        }
        for (int i = 1; i < kid.length(); i++) {
            char c = kid.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    /** Reads an envelope of at most maxBytes, refusing anything but its one strict spelling. */
    public static SignedEnvelope parse(byte[] utf8, long maxBytes) throws RemoteException {
        JsonObject json = StrictJson.parseObject(utf8, maxBytes);
        Payloads.exactKeys(json, "Envelope", KEYS);
        String kid = Payloads.string(json, "kid", MAX_KID_LENGTH);
        if (!isKid(kid)) {
            throw new RemoteException("Envelope names an invalid key " + RemoteException.excerpt(kid));
        }
        byte[] payload = CoreBase64.decode(Payloads.string(json, "payload", CoreBase64.encodedLength((int) maxBytes)));
        if (payload == null || payload.length == 0) {
            throw new RemoteException("Envelope payload is not unpadded URL-safe Base64");
        }
        byte[] signature = CoreBase64.decode(Payloads.string(json, "sig", CoreBase64.encodedLength(SIGNATURE_BYTES)));
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            throw new RemoteException("Envelope signature is not 64 bytes of unpadded URL-safe Base64");
        }
        SignedEnvelope envelope = new SignedEnvelope(kid, payload, signature);
        return Payloads.canonical(envelope, envelope.toUtf8(), utf8, "Envelope");
    }

    /** The envelope as it is published. */
    public byte[] toUtf8() {
        JsonObject json = new JsonObject();
        json.addProperty("kid", kid);
        json.addProperty("payload", CoreBase64.encode(payload));
        json.addProperty("sig", CoreBase64.encode(signature));
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
