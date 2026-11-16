package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a player is about to publish, as the list a publish begins with: the record and every
 * file it names, by SHA-256, type and size, with the rules and terms the game holds. The files
 * themselves follow one by one. It says nothing of what each file is for: that is read from
 * where the record uses it, never taken from the game's word.
 *
 * @param terms   the version of the terms the player accepted
 * @param rules   the {@link PlayerRules#VERSION} the game checked the content under
 * @param client  the game that sends it, such as {@code 2.2.0+1.21.8-fabric}
 * @param target  the player collection to change; null for a new one
 * @param baseRev the head revision the changes were made from; 0 for a new collection
 * @param record  the SHA-256 of the record
 * @param blobs   the record first, then each file it names once
 */
public record UploadPackage(int terms, int rules, String client, @Nullable String target, int baseRev, String record,
                            List<Blob> blobs) {
    /** The most bytes a package may have. */
    public static final int MAX_BYTES = 64 * 1024;
    private static final String[] KEYS = {"schema", "terms", "rules", "client", "target", "base_rev", "record",
        "blobs"};
    private static final String[] BLOB_KEYS = {"sha256", "type", "size"};
    private static final String NEW = "new";
    private static final String RECORD = "record";
    private static final int MAX_VERSION = 1_000_000;
    private static final int MAX_CLIENT_LENGTH = 48;

    /**
     * One file of a package.
     *
     * @param type the file's type; null for the record itself
     */
    public record Blob(String sha256, @Nullable BlobType type, int size) {
        /** The most bytes a file of this entry's type may have. */
        public int maxBytes() {
            return type == null ? WorldContentLimits.RECORD_BYTES : type.maxBytes();
        }
    }

    public UploadPackage {
        blobs = List.copyOf(blobs);
    }

    /** Whether the text may name a game build: 1 to 48 of letters, digits, dots, plus and hyphen. */
    public static boolean isClient(String client) {
        if (client == null || client.isEmpty() || client.length() > MAX_CLIENT_LENGTH) {
            return false;
        }
        for (int i = 0; i < client.length(); i++) {
            char c = client.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '.' && c != '+' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** Reads a package, refusing anything but its one strict spelling. */
    public static UploadPackage parse(byte[] utf8) throws RemoteException {
        JsonObject json = StrictJson.parsePlayerObject(utf8, MAX_BYTES);
        Payloads.exactKeys(json, "Package", KEYS);
        Payloads.integer(json, "schema", 1, 1);
        String client = Payloads.string(json, "client", MAX_CLIENT_LENGTH);
        if (!isClient(client)) {
            throw new RemoteException("Package client is not a game build");
        }
        String target = Payloads.string(json, "target", 32);
        int baseRev = (int) Payloads.integer(json, "base_rev", 0, PlayerHead.MAX_REV);
        if (NEW.equals(target) ? baseRev != 0 : !PlayerIds.isPlayerId(target) || baseRev == 0) {
            throw new RemoteException("Package target is neither new nor a player collection with its revision");
        }
        String record = Payloads.sha(json, "record");
        List<Blob> blobs = new ArrayList<>();
        Set<String> shas = new HashSet<>();
        for (JsonElement element : Payloads.array(json, "blobs", PlayerRules.MAX_BLOBS + 1)) {
            JsonObject entry = Payloads.object(element, "A package file");
            Payloads.exactKeys(entry, "A package file", BLOB_KEYS);
            String sha = Payloads.sha(entry, "sha256");
            String typeName = Payloads.string(entry, "type", 16);
            BlobType type = BlobType.byStoreName(typeName);
            if (type == null && !RECORD.equals(typeName)) {
                throw new RemoteException("Package file has an unknown type");
            }
            Blob blob = new Blob(sha, type, 0);
            blob = new Blob(sha, type, (int) Payloads.integer(entry, "size", 1, blob.maxBytes()));
            if (!shas.add(sha)) {
                throw new RemoteException("Package lists a file twice");
            }
            // The record comes first and only once, and it is the one the package names
            if ((type == null) != blobs.isEmpty() || (type == null && !sha.equals(record))) {
                throw new RemoteException("Package must list its record first and once");
            }
            blobs.add(blob);
        }
        if (blobs.isEmpty()) {
            throw new RemoteException("Package must list its record first and once");
        }
        UploadPackage read = new UploadPackage((int) Payloads.integer(json, "terms", 1, MAX_VERSION),
                (int) Payloads.integer(json, "rules", 1, MAX_VERSION), client, NEW.equals(target) ? null : target,
                baseRev, record, blobs);
        return Payloads.canonical(read, read.toUtf8(), utf8, "Package");
    }

    /** The package as it is sent, whose SHA-256 the publish intent names. */
    public byte[] toUtf8() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("terms", terms);
        json.addProperty("rules", rules);
        json.addProperty("client", client);
        json.addProperty("target", target == null ? NEW : target);
        json.addProperty("base_rev", baseRev);
        json.addProperty("record", record);
        JsonArray array = new JsonArray();
        for (Blob blob : blobs) {
            JsonObject entry = new JsonObject();
            entry.addProperty("sha256", blob.sha256());
            entry.addProperty("type", blob.type() == null ? RECORD : blob.type().storeName());
            entry.addProperty("size", blob.size());
            array.add(entry);
        }
        json.add("blobs", array);
        return JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
    }
}
