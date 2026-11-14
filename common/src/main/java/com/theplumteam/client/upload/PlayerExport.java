package com.theplumteam.client.upload;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.JsonOut;
import com.theplumteam.remote.player.PlayerRules;
import com.theplumteam.remote.player.UploadPackage;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.CanonicalJson;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a collection made in the creator into what is published: the record in its published
 * form, each file it names in the one spelling a published file has, and the package that
 * lists them. Everything the upload service will check is checked here first, by the same
 * code, so a collection that exports is one the service takes unless its own lists refuse it.
 *
 * <p>Figures that are turned off are left out, and their files with them. A model or an
 * animation stored as an editor saved it is written again in its one published spelling, which
 * gives it another SHA-256; the record is published naming the new one. Images are stored in
 * their canonical form already and keep their names.
 */
public final class PlayerExport {
    /** The id a record is checked under before the service has given the collection one. */
    private static final String STAND_IN = "p_aaaaaaaaaaaa";

    /**
     * A collection ready to be sent.
     *
     * @param pack         what is published, as the service is told
     * @param packageBytes the package as it is sent, whose SHA-256 the session is given for
     * @param record       the record in its published form
     * @param files        the record and every file it names, each by its SHA-256
     * @param facts        what each file is, by its SHA-256
     */
    public record Export(UploadPackage pack, byte[] packageBytes, byte[] record, Map<String, byte[]> files,
                         Map<String, BlobInfo> facts) {
        /** The bytes of the record or of a file, as {@link PublishRun} asks for them. */
        public byte[] bytes(String sha256) {
            return files.get(sha256);
        }
    }

    private PlayerExport() {
    }

    /**
     * @param record  the collection as the creator holds it
     * @param table   what each file the record names is
     * @param bytes   the bytes of a file by its SHA-256, or null when they are not at hand
     * @param target  the published collection this is a new revision of, or null for a new one
     * @param baseRev the revision it builds on; 0 for a new one
     * @param terms   the version of the terms the player accepted
     * @param client  this build's name
     * @param author  the name of the account that publishes it
     * @throws WorldContentException when something of it may not be published, with the rule that says why
     */
    public static Export of(WorldCollectionRecord record, Map<String, BlobInfo> table, Function<String, byte[]> bytes,
                            @Nullable String target, int baseRev, int terms, String client, String author)
            throws WorldContentException {
        List<WorldCollectionRecord.Figure> shown = record.figures().stream()
                .filter(WorldCollectionRecord.Figure::enabled).toList();
        if (shown.isEmpty()) {
            throw new WorldContentException(ContentError.RECORD_FIGURES, "A published collection needs a figure");
        }
        WorldCollectionRecord published = record.withFigures(shown);

        // Each file in its canonical form, under the SHA-256 that form has
        Map<String, String> renamed = new LinkedHashMap<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, BlobInfo> facts = new LinkedHashMap<>();
        for (String sha : published.referencedShas()) {
            BlobInfo known = table.get(sha);
            byte[] stored = bytes.apply(sha);
            if (known == null || stored == null) {
                throw new WorldContentException(ContentError.FILE_MISSING, "A file of the collection is missing");
            }
            byte[] canonical = known.type() == BlobType.PNG ? PngValidator.canonicalize(stored)
                    : CanonicalJson.minify(stored, known.type().maxBytes());
            String name = JdkSha256.of(canonical);
            renamed.put(sha, name);
            files.put(name, canonical);
            facts.put(name, known.type().validatePlayer(canonical));
        }

        JsonObject json = published.toPlayerJson();
        rename(json, renamed);
        byte[] recordBytes = JsonOut.write(json).getBytes(StandardCharsets.UTF_8);
        // The whole check a published record gets, as the service and every game make it
        WorldCollectionRecord.parsePlayer(recordBytes, facts, STAND_IN, author, Math.max(1, baseRev + 1));

        String recordSha = JdkSha256.of(recordBytes);
        List<UploadPackage.Blob> blobs = new ArrayList<>();
        blobs.add(new UploadPackage.Blob(recordSha, null, recordBytes.length));
        facts.forEach((sha, info) -> blobs.add(new UploadPackage.Blob(sha, info.type(), info.size())));
        UploadPackage pack = new UploadPackage(terms, PlayerRules.VERSION, client, target, baseRev, recordSha, blobs);
        Map<String, byte[]> all = new LinkedHashMap<>();
        all.put(recordSha, recordBytes);
        all.putAll(files);
        return new Export(pack, pack.toUtf8(), recordBytes, all, facts);
    }

    // Every place the record names a file names it by its SHA-256, and nothing else in it is one
    private static void rename(JsonElement element, Map<String, String> renamed) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : List.copyOf(object.keySet())) {
                JsonElement value = object.get(key);
                if (value instanceof JsonPrimitive text && text.isString() && renamed.containsKey(text.getAsString())) {
                    object.addProperty(key, renamed.get(text.getAsString()));
                } else {
                    rename(value, renamed);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement value = array.get(i);
                if (value instanceof JsonPrimitive text && text.isString() && renamed.containsKey(text.getAsString())) {
                    array.set(i, new JsonPrimitive(renamed.get(text.getAsString())));
                } else {
                    rename(value, renamed);
                }
            }
        }
    }
}
