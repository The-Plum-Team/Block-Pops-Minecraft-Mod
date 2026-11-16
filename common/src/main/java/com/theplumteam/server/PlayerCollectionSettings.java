package com.theplumteam.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.worldcontent.Sha256;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The player collections added to this world, stored in
 * data/blockpops-player-collections.json:
 * {@code {"schema":1,"entries":[{"id":"p_…","code":"maple-orbit-42","rev":5,"index":"<64 hex>"}]}},
 * with {@code "follow":true} on an entry only when it is set.
 *
 * <p>An entry's revision and index name the revision the world is pinned to. They only select
 * which signed index to load: nothing is believed because this file says it. A jar from before
 * player collections never reads the file, so a world taken back to one loses its player
 * collections and keeps its official ones.
 *
 * <p>Not thread-safe: the one thread that works with player collections uses it.
 */
public final class PlayerCollectionSettings {
    /** At most this many player collections are added to one world. */
    public static final int MAX_ENTRIES = 32;
    private static final int MAX_BYTES = 64 * 1024;
    private static final String FILE = "blockpops-player-collections.json";

    /**
     * One collection added to the world.
     *
     * @param rev    the revision the world is pinned to
     * @param index  the SHA-256 that names that revision's index
     * @param follow whether the world moves to each new revision by itself
     */
    public record Entry(String id, String code, int rev, String index, boolean follow) {
        public Entry {
            if (!PlayerIds.isPlayerId(id) || !PlayerIds.isPlayerCode(code) || rev < 1 || rev > PlayerHead.MAX_REV
                    || !Sha256.isValidHex(index)) {
                throw new IllegalArgumentException("Invalid player collection entry");
            }
        }

        /** The entry pinned to another revision. */
        public Entry at(int newRev, String newIndex) {
            return new Entry(id, code, newRev, newIndex, follow);
        }

        public Entry following(boolean on) {
            return new Entry(id, code, rev, index, on);
        }
    }

    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * @param dataDirectory the world's data directory
     * @param problems      told once if the file there could not be read and was set aside
     */
    public PlayerCollectionSettings(Path dataDirectory, java.util.function.Consumer<String> problems) {
        this.path = dataDirectory.resolve(FILE);
        if (Files.isRegularFile(path)) {
            try {
                read(Files.readAllBytes(path));
            } catch (IOException | RuntimeException e) {
                // Set the unreadable file aside, so the next save does not overwrite the list it held
                entries.clear();
                Path backup = path.resolveSibling(FILE + ".corrupt");
                try {
                    Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
                    problems.accept("Could not read " + path + ", no player collection is added; kept it as "
                            + backup.getFileName() + ": " + e.getMessage());
                } catch (IOException moveFailure) {
                    problems.accept("Could not read " + path + " nor set it aside: " + moveFailure.getMessage());
                }
            }
        }
    }

    private void read(byte[] utf8) throws IOException {
        if (utf8.length > MAX_BYTES) {
            throw new RemoteException("it is too large");
        }
        JsonObject root = StrictJson.parsePlayerObject(utf8, MAX_BYTES);
        if (!root.keySet().equals(Set.of("schema", "entries")) || !isInteger(root.get("schema"), 1, 1)
                || !root.get("entries").isJsonArray() || root.getAsJsonArray("entries").size() > MAX_ENTRIES) {
            throw new RemoteException("it is not a list of player collections");
        }
        for (JsonElement element : root.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                throw new RemoteException("an entry is not an object");
            }
            JsonObject json = element.getAsJsonObject();
            boolean follows = json.has("follow");
            if (!json.keySet().equals(follows ? Set.of("id", "code", "rev", "index", "follow")
                    : Set.of("id", "code", "rev", "index"))
                    || !isString(json.get("id")) || !isString(json.get("code")) || !isString(json.get("index"))
                    || !isInteger(json.get("rev"), 1, PlayerHead.MAX_REV)
                    || (follows && !(json.get("follow") instanceof JsonPrimitive flag && flag.isBoolean()
                            && flag.getAsBoolean()))) {
                throw new RemoteException("an entry has other keys or values than an entry has");
            }
            Entry entry;
            try {
                entry = new Entry(json.get("id").getAsString(), json.get("code").getAsString(),
                        json.get("rev").getAsInt(), json.get("index").getAsString(), follows);
            } catch (IllegalArgumentException e) {
                throw new RemoteException("an entry names no player collection");
            }
            if (entries.putIfAbsent(entry.id(), entry) != null || byCode(entry.code()).filter(entry::equals).isEmpty()) {
                throw new RemoteException("a collection or a code is listed twice");
            }
        }
    }

    private static boolean isString(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString();
    }

    private static boolean isInteger(JsonElement element, int min, int max) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return false;
        }
        String token = primitive.getAsString();
        if (!token.matches("[0-9]{1,9}")) {
            return false;
        }
        int value = Integer.parseInt(token);
        return value >= min && value <= max;
    }

    /** The entries, in the order they were added. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Optional<Entry> byId(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Optional<Entry> byCode(String code) {
        return entries.values().stream().filter(entry -> entry.code().equals(code)).findFirst();
    }

    public boolean isFull() {
        return entries.size() >= MAX_ENTRIES;
    }

    /**
     * Adds an entry, or replaces the one of the same collection, and saves the list.
     *
     * @return false if the list is full or could not be saved, in which case nothing changed
     */
    public boolean put(Entry entry) {
        Entry before = entries.get(entry.id());
        if (before == null && isFull()) {
            return false;
        }
        entries.put(entry.id(), entry);
        if (save()) {
            return true;
        }
        if (before == null) {
            entries.remove(entry.id());
        } else {
            entries.put(entry.id(), before);
        }
        return false;
    }

    /**
     * Removes a collection's entry and saves the list.
     *
     * @return false if it could not be saved, in which case nothing changed
     */
    public boolean remove(String id) {
        if (!entries.containsKey(id)) {
            return true;
        }
        // Rebuilt, so a failed save puts the entry back where it was
        List<Entry> before = new ArrayList<>(entries.values());
        entries.remove(id);
        if (save()) {
            return true;
        }
        entries.clear();
        before.forEach(entry -> entries.put(entry.id(), entry));
        return false;
    }

    private boolean save() {
        JsonArray list = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.id());
            json.addProperty("code", entry.code());
            json.addProperty("rev", entry.rev());
            json.addProperty("index", entry.index());
            if (entry.follow()) {
                json.addProperty("follow", true);
            }
            list.add(json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.add("entries", list);
        // Write a sibling file and swap it in, so a crash cannot leave a half-written list
        Path temporary = path.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, JsonOut.write(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                // Not every file system offers the atomic swap, and Windows can deny it
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
