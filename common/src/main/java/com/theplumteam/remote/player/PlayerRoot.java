package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The offline root's word, which stands above every online key: whether player collections load
 * at all, and which online keys are no longer to be believed. It is normally absent, and one
 * seen once stays in force until a root with a higher seq replaces it.
 *
 * @param seq         grows with every root document; it is also the epoch it starts
 * @param loading     false stops every game from loading player collections
 * @param revokedKids the online keys refused from now on, for every kind of document
 */
public record PlayerRoot(long seq, long issuedAt, boolean loading, List<String> revokedKids) {
    public static final String KIND = "blockpops.player.root";
    public static final int ENVELOPE_BYTES = 4 * 1024;
    /** The most keys one root document revokes. */
    public static final int MAX_REVOKED_KIDS = 16;
    private static final String[] KEYS = {"kind", "schema", "seq", "issued_at", "loading", "revoked_kids"};

    public PlayerRoot {
        revokedKids = List.copyOf(revokedKids);
    }

    /** Reads a root's signed payload. */
    public static PlayerRoot parse(byte[] payload) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(payload, ENVELOPE_BYTES);
        Payloads.header(json, KIND);
        Payloads.exactKeys(json, "Root", KEYS);
        List<String> kids = new ArrayList<>();
        for (JsonElement element : Payloads.array(json, "revoked_kids", MAX_REVOKED_KIDS)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || !SignedEnvelope.isKid(element.getAsString()) || kids.contains(element.getAsString())) {
                throw new RemoteException("revoked_kids must list each key id once");
            }
            kids.add(element.getAsString());
        }
        PlayerRoot root = new PlayerRoot(Payloads.integer(json, "seq", 1, PlayerRules.MAX_SEQ),
                Payloads.integer(json, "issued_at", 0, Payloads.MAX_TIME),
                Payloads.word(json, "loading", "on", "off").equals("on"), kids);
        return Payloads.canonical(root, root.toPayload(), payload, "Root");
    }

    /** The payload that is signed. */
    public byte[] toPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", KIND);
        json.addProperty("schema", 1);
        json.addProperty("seq", seq);
        json.addProperty("issued_at", issuedAt);
        json.addProperty("loading", loading ? "on" : "off");
        JsonArray kids = new JsonArray();
        for (String kid : revokedKids) {
            kids.add(kid);
        }
        json.add("revoked_kids", kids);
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
