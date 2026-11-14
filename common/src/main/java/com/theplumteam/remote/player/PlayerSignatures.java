package com.theplumteam.remote.player;

import com.theplumteam.remote.RemoteException;

import java.util.Set;

/**
 * Opens a signed document: the only way a player document's payload reaches its parser. In
 * this order, each step only after the one before it held: the envelope's strict shape within
 * the kind's size; a key the build knows; a key the root has not revoked; a key that may sign
 * this kind of document; and the signature over the exact payload bytes. Nothing of the payload
 * is read before the signature holds, and the payload's own schema is the parser's to check.
 */
public final class PlayerSignatures {
    /** The kinds of signed document, each with its size and who signs it. */
    public enum Kind {
        HEAD(PlayerHead.ENVELOPE_BYTES, PlayerKeys.Role.ONLINE),
        INDEX(PlayerIndex.ENVELOPE_BYTES, PlayerKeys.Role.ONLINE),
        STATUS(PlayerStatus.ENVELOPE_BYTES, PlayerKeys.Role.ONLINE),
        REVOCATIONS(PlayerRevocations.ENVELOPE_BYTES, PlayerKeys.Role.ONLINE),
        ROOT(PlayerRoot.ENVELOPE_BYTES, PlayerKeys.Role.ROOT);

        private final int envelopeBytes;
        private final PlayerKeys.Role signer;

        Kind(int envelopeBytes, PlayerKeys.Role signer) {
            this.envelopeBytes = envelopeBytes;
            this.signer = signer;
        }

        /** The most bytes an envelope of this kind has. */
        public int envelopeBytes() {
            return envelopeBytes;
        }
    }

    /**
     * A payload whose signature held.
     *
     * @param kid     the key that signed it
     * @param payload the signed bytes, for the kind's parser
     */
    public record Signed(String kid, byte[] payload) {
    }

    private PlayerSignatures() {
    }

    /**
     * Opens an envelope of that kind.
     *
     * @param revokedKids the online keys the last accepted root revoked
     */
    public static Signed open(Kind kind, byte[] envelope, PlayerKeys keys, Set<String> revokedKids)
            throws RemoteException {
        SignedEnvelope read = SignedEnvelope.parse(envelope, kind.envelopeBytes);
        String kid = read.kid();
        PlayerKeys.Role role = keys.role(kid);
        if (role == null) {
            throw new RemoteException("Signed by unknown key " + kid);
        }
        if (revokedKids.contains(kid)) {
            throw new RemoteException("Signed by revoked key " + kid);
        }
        if (role != kind.signer) {
            throw new RemoteException("Key " + kid + " may not sign this kind of document");
        }
        if (!keys.verifies(kid, read.payload(), read.signature())) {
            throw new RemoteException("The signature of key " + kid + " does not hold");
        }
        return new Signed(kid, read.payload());
    }
}
