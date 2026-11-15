package com.theplumteam.client.worldcontent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.worldcontent.WorldContentIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What ties a player's drafts to what they published: for each draft that was published, the
 * collection it became, its code, and the revision the draft was last published as, which the
 * next publish builds on; and the version of the publishing terms the player accepted. Kept
 * beside the drafts, in {@code blockpops/creator/links.json} of the game folder.
 *
 * <p>Nothing here is believed by anybody but this game: the upload service knows who owns what,
 * and a link that names a collection of somebody else's only gets a refusal.
 */
public final class PersonalLinks {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_LINKS = 4096;
    private static final String FILE = "links.json";

    /**
     * @param id   the published collection
     * @param code the code it is added by
     * @param rev  the revision the draft was last published as
     */
    public record Link(String id, String code, int rev) {
        public Link {
            if (!PlayerIds.isPlayerId(id) || !PlayerIds.isPlayerCode(code) || rev < 1 || rev > PlayerHead.MAX_REV) {
                throw new IllegalArgumentException("Invalid link");
            }
        }
    }

    private final Path path;
    private final Map<String, Link> links = new LinkedHashMap<>();
    private int terms;

    private PersonalLinks(Path path) {
        this.path = path;
    }

    /** Reads the links of the game folder; a file that does not hold together is set aside and nothing is linked. */
    public static PersonalLinks load(Path gameDirectory) {
        PersonalLinks loaded = new PersonalLinks(gameDirectory.resolve("blockpops").resolve("creator").resolve(FILE));
        if (Files.isRegularFile(loaded.path)) {
            try {
                loaded.read(Files.readAllBytes(loaded.path));
            } catch (IOException | RuntimeException e) {
                loaded.links.clear();
                loaded.terms = 0;
                try {
                    Files.move(loaded.path, loaded.path.resolveSibling(FILE + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // The next save writes over it
                }
            }
        }
        return loaded;
    }

    private void read(byte[] utf8) throws IOException {
        JsonObject root = StrictJson.parsePlayerObject(utf8, MAX_BYTES);
        if (!root.keySet().equals(Set.of("schema", "terms", "links")) || number(root.get("schema"), 1, 1) != 1
                || !root.get("links").isJsonObject() || root.getAsJsonObject("links").size() > MAX_LINKS) {
            throw new IOException("Not a links file");
        }
        terms = number(root.get("terms"), 0, 1_000_000);
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("links").entrySet()) {
            if (!WorldContentIds.isWorldCollectionId(entry.getKey()) || !entry.getValue().isJsonObject()) {
                throw new IOException("Not a link");
            }
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.keySet().equals(Set.of("id", "code", "rev")) || !isString(json.get("id")) || !isString(json.get("code"))) {
                throw new IOException("Not a link");
            }
            links.put(entry.getKey(), new Link(json.get("id").getAsString(), json.get("code").getAsString(),
                    number(json.get("rev"), 1, PlayerHead.MAX_REV)));
        }
    }

    private static boolean isString(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString();
    }

    private static int number(JsonElement element, int min, int max) throws IOException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber() || !primitive.getAsString().matches("[0-9]{1,9}")) {
            throw new IOException("Not a number");
        }
        int value = Integer.parseInt(primitive.getAsString());
        if (value < min || value > max) {
            throw new IOException("Out of range");
        }
        return value;
    }

    /** What a draft was published as, if it was. */
    public Optional<Link> link(String draftId) {
        return Optional.ofNullable(links.get(draftId));
    }

    /** The draft that was published as that collection, if one is recorded. */
    public Optional<String> draftOf(String publishedId) {
        for (Map.Entry<String, Link> entry : links.entrySet()) {
            if (entry.getValue().id().equals(publishedId)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** The version of the publishing terms the player accepted; 0 when none. */
    public int terms() {
        return terms;
    }

    /** Records that the draft was published as that collection at that revision. */
    public boolean put(String draftId, Link link) {
        if (!WorldContentIds.isWorldCollectionId(draftId) || (!links.containsKey(draftId) && links.size() >= MAX_LINKS)) {
            return false;
        }
        links.put(draftId, link);
        return save();
    }

    /** Forgets what a draft was published as, as when the published collection was deleted. */
    public boolean remove(String draftId) {
        return links.remove(draftId) == null || save();
    }

    public boolean acceptTerms(int version) {
        terms = Math.max(terms, version);
        return save();
    }

    private boolean save() {
        JsonObject all = new JsonObject();
        links.forEach((draft, link) -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", link.id());
            json.addProperty("code", link.code());
            json.addProperty("rev", link.rev());
            all.add(draft, json);
        });
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("terms", terms);
        root.add("links", all);
        Path temporary = path.resolveSibling(FILE + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, JsonOut.write(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
