package com.theplumteam.remote.player;

import org.jetbrains.annotations.Nullable;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The keys whose signatures on player documents the game believes, each with what it may sign.
 * They are Ed25519 public keys compiled into the build; the game holds no private key.
 *
 * <p>An online key signs what the upload service publishes day to day. The root key stays
 * offline and signs only the root document, which stands above the online keys and can revoke
 * them. A Java runtime without Ed25519 leaves the table empty, so nothing is believed there.
 */
public final class PlayerKeys {
    /** What a key may sign. */
    public enum Role {
        ONLINE("online"), ROOT("root");

        private final String word;

        Role(String word) {
            this.word = word;
        }
    }

    private record Entry(Role role, PublicKey key) {
    }

    private static final PlayerKeys BUILT_IN = of(PlayerBuildFlags.KEYS);

    private final Map<String, Entry> entries;

    private PlayerKeys(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** The keys this build was compiled with. */
    public static PlayerKeys builtIn() {
        return BUILT_IN;
    }

    /**
     * A table of keys: each row a key id, {@code online} or {@code root}, and the key's
     * SubjectPublicKeyInfo in Base64. A malformed row is a mistake in the build and throws; a
     * runtime without Ed25519 gives an empty table.
     */
    public static PlayerKeys of(String[][] table) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        KeyFactory factory;
        try {
            factory = KeyFactory.getInstance("Ed25519");
        } catch (GeneralSecurityException e) {
            return new PlayerKeys(Map.of());
        }
        for (String[] row : table) {
            if (row.length != 3 || !SignedEnvelope.isKid(row[0]) || entries.containsKey(row[0])) {
                throw new IllegalArgumentException("Invalid key table");
            }
            Role role = null;
            for (Role candidate : Role.values()) {
                if (candidate.word.equals(row[1])) {
                    role = candidate;
                }
            }
            try {
                if (role == null) {
                    throw new IllegalArgumentException("Invalid key role");
                }
                entries.put(row[0], new Entry(role,
                        factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(row[2])))));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid key " + row[0], e);
            }
        }
        return new PlayerKeys(entries);
    }

    /** Whether there is no key at all, so no document can be believed. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The ids of the keys. */
    public Set<String> kids() {
        return entries.keySet();
    }

    /** What the key of that id may sign, or null when there is no such key. */
    @Nullable
    public Role role(String kid) {
        Entry entry = entries.get(kid);
        return entry != null ? entry.role() : null;
    }

    /** Whether the signature is that key's over exactly these bytes. */
    public boolean verifies(String kid, byte[] payload, byte[] signature) {
        Entry entry = entries.get(kid);
        if (entry == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(entry.key());
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
