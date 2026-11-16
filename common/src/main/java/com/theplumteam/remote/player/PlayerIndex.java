package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One revision of a player collection: its record and every file the record names, each with
 * what checking it found. A revision never changes, so neither does its index, and the facts in
 * it let a game build the collection before it has any of the files.
 *
 * @param author       the verified Minecraft name of who published it, or null when that name
 *                     may not be shown
 * @param recordSha256 the SHA-256 of the record's bytes
 * @param recordSize   the record's size in bytes
 * @param blobs        the files the record names, by SHA-256, in the order they are listed
 * @param rules        the {@link PlayerRules#VERSION} the content was checked under
 * @param validator    the SHA-256 of the program that checked it
 */
public record PlayerIndex(String id, int rev, long issuedAt, @Nullable String author, String recordSha256,
                          int recordSize, Map<String, BlobInfo> blobs, int rules, String validator) {
    public static final String KIND = "blockpops.player.index";
    public static final int ENVELOPE_BYTES = 128 * 1024;
    private static final String[] KEYS = {"kind", "schema", "id", "rev", "issued_at", "author", "record", "blobs",
        "rules", "validator"};
    private static final String[] RECORD_KEYS = {"sha256", "size"};
    private static final String[] BLOB_KEYS = {"sha256", "info"};
    private static final int MAX_AUTHOR_LENGTH = 16;
    private static final int MAX_RULES = 1_000_000;

    public PlayerIndex {
        if (!PlayerIds.isPlayerId(id) || (author != null && !isAccountName(author)) || !Sha256.isValidHex(recordSha256)
                || !Sha256.isValidHex(validator)) {
            throw new IllegalArgumentException("Invalid index");
        }
        blobs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(blobs));
    }

    /** Whether the text may be a Minecraft account's name: 1 to 16 of letters, digits and underscores. */
    public static boolean isAccountName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_AUTHOR_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** Reads an index's signed payload. */
    public static PlayerIndex parse(byte[] payload) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(payload, ENVELOPE_BYTES);
        Payloads.header(json, KIND);
        Payloads.exactKeys(json, "Index", KEYS);
        String author = null;
        if (!json.get("author").isJsonNull()) {
            author = Payloads.string(json, "author", MAX_AUTHOR_LENGTH);
            if (!isAccountName(author)) {
                throw new RemoteException("Index author is not an account name");
            }
        }
        JsonObject record = Payloads.object(json.get("record"), "record");
        Payloads.exactKeys(record, "record", RECORD_KEYS);
        Map<String, BlobInfo> blobs = new LinkedHashMap<>();
        for (JsonElement element : Payloads.array(json, "blobs", PlayerRules.MAX_BLOBS)) {
            JsonObject entry = Payloads.object(element, "A file entry");
            Payloads.exactKeys(entry, "A file entry", BLOB_KEYS);
            BlobInfo info;
            try {
                info = BlobInfo.readJson(Payloads.object(entry.get("info"), "info"), Set.of());
            } catch (WorldContentException e) {
                throw new RemoteException("Index file entry: " + e.getMessage());
            }
            if (blobs.put(Payloads.sha(entry, "sha256"), info) != null) {
                throw new RemoteException("Index lists a file twice");
            }
        }
        PlayerIndex index = new PlayerIndex(Payloads.playerId(json, "id"), (int) Payloads.integer(json, "rev", 1, PlayerHead.MAX_REV),
                Payloads.integer(json, "issued_at", 0, Payloads.MAX_TIME), author, Payloads.sha(record, "sha256"),
                (int) Payloads.integer(record, "size", 1, WorldContentLimits.RECORD_BYTES), blobs,
                (int) Payloads.integer(json, "rules", 1, MAX_RULES), Payloads.sha(json, "validator"));
        return Payloads.canonical(index, index.toPayload(), payload, "Index");
    }

    /** The payload that is signed. */
    public byte[] toPayload() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", KIND);
        json.addProperty("schema", 1);
        json.addProperty("id", id);
        json.addProperty("rev", rev);
        json.addProperty("issued_at", issuedAt);
        if (author != null) {
            json.addProperty("author", author);
        } else {
            json.add("author", JsonNull.INSTANCE);
        }
        JsonObject record = new JsonObject();
        record.addProperty("sha256", recordSha256);
        record.addProperty("size", recordSize);
        json.add("record", record);
        JsonArray array = new JsonArray();
        for (Map.Entry<String, BlobInfo> blob : blobs.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("sha256", blob.getKey());
            JsonObject info = new JsonObject();
            blob.getValue().writeJson(info);
            entry.add("info", info);
            array.add(entry);
        }
        json.add("blobs", array);
        json.addProperty("rules", rules);
        json.addProperty("validator", validator);
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
