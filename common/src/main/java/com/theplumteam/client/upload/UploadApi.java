package com.theplumteam.client.upload;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.client.upload.UploadTransport.Answer;
import com.theplumteam.client.upload.UploadTransport.Call;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.remote.player.PlayerIndex;
import com.theplumteam.remote.player.PlayerText;
import com.theplumteam.remote.player.UploadIntent;
import com.theplumteam.worldcontent.Sha256;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The upload service's calls, each as what it asks and what it answers. An answer is read as
 * strict JSON with the keys it is known to have and values of the shapes they are known to
 * take; anything else is the service not being itself, and is {@link ApiError#INTERNAL}. Names
 * and codes that come back are data from the internet: they are held to the same grammar a
 * published record holds them to before anything shows them.
 */
public final class UploadApi {
    private static final String JSON = "application/json";
    private static final byte[] EMPTY = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TOKEN = Pattern.compile("bpu1\\.[A-Za-z0-9_-]{1,1024}\\.[A-Za-z0-9_-]{1,128}");
    private static final Pattern UUID32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern UPLOAD = Pattern.compile("[0-9a-hjkmnp-tv-z]{26}");
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    private static final Pattern FIELD = Pattern.compile("(/[a-z_]{1,24}|/[0-9]{1,3}){1,6}");
    private static final long MAX_NUMBER = 1L << 40;

    /** What the service is taking right now and the limits it holds everyone to. */
    public record Config(String uploads, String minClient, int terms, int rules, int collections, int figures,
                         long bytesCollection, long bytesOwner, int dailyCommits) {
    }

    public record Challenge(String nonce, int expiresIn) {
    }

    /**
     * A session: the proof that this game speaks for a Minecraft account, good for one action.
     *
     * @param token kept in memory only, never written anywhere
     */
    public record Session(String token, int expiresIn, String uuid, String name, String ownerState) {
    }

    public record Begun(String upload, List<String> missing, int expiresIn) {
    }

    public record Published(String id, String code, int rev, long seq, String index) {
    }

    public record UploadState(String state, List<String> missing, @Nullable Published result) {
    }

    public record RevisionRow(int rev, long at, int figures, long bytes, String state) {
    }

    public record Mine(String id, String code, String name, int rev, String state, @Nullable String reason, long updatedAt,
                       List<RevisionRow> revisions) {
    }

    public record Quota(int collections, int collectionsLimit, long bytes, long bytesLimit, int commitsToday,
                        int commitsLimit) {
    }

    public record Account(List<Mine> collections, Quota quota, String ownerState) {
    }

    public record Rolled(int rev, long seq, String index) {
    }

    private final UploadTransport transport;

    public UploadApi(UploadTransport transport) {
        this.transport = transport;
    }

    /** The game's: it reaches the service, except in the packaged E2E, where it reaches nothing. */
    public static UploadApi forGame() {
        return new UploadApi(UploadTransport.forGame());
    }

    public Config config() throws UploadException {
        JsonObject json = object(send("GET", "/v1/config", null, null, null, false), "schema", "uploads", "min_client",
                "terms", "rules", "limits");
        JsonObject limits = exact(json.get("limits"), "collections", "figures", "bytes_collection", "bytes_owner",
                "daily_commits");
        return new Config(word(json, "uploads", "open", "beta", "paused"), matching(json, "min_client", VERSION),
                (int) number(json, "terms", 1, 1_000_000), (int) number(json, "rules", 1, 1_000_000),
                (int) number(limits, "collections", 0, 1_000), (int) number(limits, "figures", 0, 100_000),
                number(limits, "bytes_collection", 0, MAX_NUMBER), number(limits, "bytes_owner", 0, MAX_NUMBER),
                (int) number(limits, "daily_commits", 0, 100_000));
    }

    /** Asks for the number the account then proves itself with, for exactly this action. */
    public Challenge challenge(String name, UploadIntent intent) throws UploadException {
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("name", name);
        body.addProperty("intent", intent.text());
        JsonObject json = object(send("POST", "/v1/auth/challenge", null, bytes(body), JSON, false), "schema", "nonce",
                "expires_in");
        return new Challenge(matching(json, "nonce", NONCE), (int) number(json, "expires_in", 1, 3600));
    }

    /** Trades the number, once the account has joined the server it names, for a session. */
    public Session session(String nonce) throws UploadException {
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("nonce", nonce);
        JsonObject json = object(send("POST", "/v1/auth/session", null, bytes(body), JSON, false), "schema", "token",
                "expires_in", "uuid", "name", "owner");
        String name = text(json, "name", 16);
        if (!PlayerIndex.isAccountName(name)) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return new Session(matching(json, "token", TOKEN), (int) number(json, "expires_in", 1, 86_400),
                matching(json, "uuid", UUID32), name, ownerState(json.get("owner")));
    }

    /** Opens an upload with the package that lists what is to be published. */
    public Begun begin(String token, byte[] packageBytes) throws UploadException {
        JsonObject json = object(send("POST", "/v1/packages", token, packageBytes, JSON, false), "schema", "upload",
                "missing", "expires_in");
        return new Begun(matching(json, "upload", UPLOAD), shas(json.get("missing")),
                (int) number(json, "expires_in", 1, 86_400));
    }

    /** Sends one file the service said it is missing. */
    public void putFile(String token, String upload, String sha256, byte[] bytes) throws UploadException {
        requireNoBody(send("PUT", "/v1/packages/" + upload(upload) + "/blobs/" + sha(sha256), token, bytes, null, true));
    }

    /** Publishes what was uploaded. Asking again for the same upload answers the same. */
    public Published commit(String token, String upload) throws UploadException {
        return published(object(send("POST", "/v1/packages/" + upload(upload) + "/commit", token, EMPTY, JSON, true),
                "schema", "id", "code", "rev", "seq", "index"));
    }

    /** Where an upload stands, for a game that lost the answer to its commit. */
    public UploadState state(String token, String upload) throws UploadException {
        JsonObject json = parse(send("GET", "/v1/packages/" + upload(upload), token, null, null, false));
        boolean done = json.has("result");
        requireKeys(json, done ? new String[] {"schema", "state", "missing", "result"}
                : new String[] {"schema", "state", "missing"});
        return new UploadState(word(json, "state", "open", "committing", "committed", "failed"), shas(json.get("missing")),
                done ? published(exact(json.get("result"), "id", "code", "rev", "seq", "index")) : null);
    }

    /** What this account has published and how much of each quota it uses. */
    public Account me(String token) throws UploadException {
        JsonObject json = object(send("GET", "/v1/me", token, null, null, false), "schema", "collections", "quota",
                "owner");
        List<Mine> collections = new ArrayList<>();
        for (JsonElement element : array(json.get("collections"), 256)) {
            Mine mine = mine(element);
            if (mine != null) {
                collections.add(mine);
            }
        }
        JsonObject quota = exact(json.get("quota"), "collections", "collections_limit", "bytes", "bytes_limit",
                "commits_today", "commits_limit");
        return new Account(List.copyOf(collections), new Quota((int) number(quota, "collections", 0, 100_000),
                (int) number(quota, "collections_limit", 0, 100_000), number(quota, "bytes", 0, MAX_NUMBER),
                number(quota, "bytes_limit", 0, MAX_NUMBER), (int) number(quota, "commits_today", 0, 1_000_000),
                (int) number(quota, "commits_limit", 0, 1_000_000)), ownerState(json.get("owner")));
    }

    // One collection of the list. A name or a code that is not one a collection can have is left out
    @Nullable
    private static Mine mine(JsonElement element) throws UploadException {
        JsonObject json = asObject(element);
        boolean reasoned = json.has("reason");
        requireKeys(json, reasoned
                ? new String[] {"id", "code", "name", "rev", "state", "reason", "updated_at", "revisions"}
                : new String[] {"id", "code", "name", "rev", "state", "updated_at", "revisions"});
        String id = text(json, "id", PlayerIds.ID_LENGTH);
        String code = text(json, "code", PlayerIds.CODE_LENGTH);
        String name = text(json, "name", PlayerText.MAX_NAME_LENGTH);
        List<RevisionRow> revisions = new ArrayList<>();
        for (JsonElement row : array(json.get("revisions"), 4096)) {
            JsonObject revision = exact(row, "rev", "at", "figures", "bytes", "state");
            revisions.add(new RevisionRow((int) number(revision, "rev", 1, PlayerHead.MAX_REV),
                    number(revision, "at", 0, MAX_NUMBER), (int) number(revision, "figures", 0, 100_000),
                    number(revision, "bytes", 0, MAX_NUMBER), word(revision, "state", "kept", "withdrawn", "revoked")));
        }
        Mine mine = new Mine(id, code, name, (int) number(json, "rev", 1, PlayerHead.MAX_REV),
                word(json, "state", "live", "quarantined", "removed"),
                reasoned ? word(json, "reason", "owner", "policy", "legal") : null,
                number(json, "updated_at", 0, MAX_NUMBER), List.copyOf(revisions));
        return PlayerIds.isPlayerId(id) && PlayerIds.isPlayerCode(code) && PlayerText.nameProblem(name) == null ? mine : null;
    }

    /** Publishes an earlier revision's content again, as a new revision. */
    public Rolled rollback(String token, String id, int toRev) throws UploadException {
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("to_rev", toRev);
        JsonObject json = object(send("POST", "/v1/collections/" + id(id) + "/rollback", token, bytes(body), JSON, true),
                "schema", "rev", "seq", "index");
        return new Rolled((int) number(json, "rev", 1, PlayerHead.MAX_REV), number(json, "seq", 1, MAX_NUMBER),
                sha(text(json, "index", 64)));
    }

    /** Takes one earlier revision back. */
    public void withdraw(String token, String id, int rev) throws UploadException {
        requireNoBody(send("POST", "/v1/collections/" + id(id) + "/revisions/" + rev + "/withdraw", token, EMPTY, JSON,
                false));
    }

    /** Deletes the collection from the service for good. */
    public void delete(String token, String id) throws UploadException {
        requireNoBody(send("DELETE", "/v1/collections/" + id(id), token, null, null, false));
    }

    // ---- The exchange

    private Answer send(String method, String path, @Nullable String token, @Nullable byte[] body,
                        @Nullable String contentType, boolean slow) throws UploadException {
        Answer answer = transport.send(new Call(method, path, token, body, contentType, slow));
        if (answer.status() >= 200 && answer.status() < 300) {
            return answer;
        }
        throw failure(answer);
    }

    // What an error answer says; an answer that is no error answer is the service not being itself
    private static UploadException failure(Answer answer) {
        try {
            JsonObject json = StrictJson.parsePlayerObject(answer.body(), UploadTransport.MAX_ANSWER_BYTES);
            if (!(json.get("error") instanceof JsonPrimitive code) || !code.isString()) {
                return new UploadException(answer.status() >= 500 ? ApiError.NETWORK : ApiError.INTERNAL);
            }
            String field = json.get("field") instanceof JsonPrimitive value && value.isString()
                    && FIELD.matcher(value.getAsString()).matches() ? value.getAsString() : null;
            ContentError rule = null;
            if (json.get("rule") instanceof JsonPrimitive value && value.isString()) {
                for (ContentError known : ContentError.values()) {
                    if (known.id().equals(value.getAsString())) {
                        rule = known;
                    }
                }
            }
            return new UploadException(ApiError.byId(code.getAsString()), optional(json, "detail"),
                    optional(json, "retry_after"), optional(json, "limit"), optional(json, "value"),
                    (int) Math.min(optional(json, "figure"), Integer.MAX_VALUE), field, rule);
        } catch (RemoteException | RuntimeException e) {
            // A gateway's page, or nothing: the service did not answer
            return new UploadException(answer.status() >= 500 ? ApiError.NETWORK : ApiError.INTERNAL);
        }
    }

    private static long optional(JsonObject json, String key) {
        if (json.get(key) instanceof JsonPrimitive value && value.isNumber() && value.getAsString().matches("[0-9]{1,13}")) {
            long number = Long.parseLong(value.getAsString());
            return number <= MAX_NUMBER ? number : -1;
        }
        return -1;
    }

    private static void requireNoBody(Answer answer) throws UploadException {
        if (answer.body().length != 0) {
            throw new UploadException(ApiError.INTERNAL);
        }
    }

    // ---- Reading answers

    private static byte[] bytes(JsonObject body) {
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parse(Answer answer) throws UploadException {
        try {
            JsonObject json = StrictJson.parsePlayerObject(answer.body(), UploadTransport.MAX_ANSWER_BYTES);
            if (number(json, "schema", 1, 1) != 1) {
                throw new UploadException(ApiError.INTERNAL);
            }
            return json;
        } catch (RemoteException | RuntimeException e) {
            throw new UploadException(ApiError.INTERNAL);
        }
    }

    private static JsonObject object(Answer answer, String... keys) throws UploadException {
        JsonObject json = parse(answer);
        requireKeys(json, keys);
        return json;
    }

    private static JsonObject asObject(JsonElement element) throws UploadException {
        if (element == null || !element.isJsonObject()) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return element.getAsJsonObject();
    }

    private static JsonObject exact(JsonElement element, String... keys) throws UploadException {
        JsonObject json = asObject(element);
        requireKeys(json, keys);
        return json;
    }

    private static void requireKeys(JsonObject json, String... keys) throws UploadException {
        if (!json.keySet().equals(Set.of(keys))) {
            throw new UploadException(ApiError.INTERNAL);
        }
    }

    private static JsonArray array(JsonElement element, int max) throws UploadException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() > max) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return element.getAsJsonArray();
    }

    private static String text(JsonObject json, String key, int maxLength) throws UploadException {
        if (!(json.get(key) instanceof JsonPrimitive value) || !value.isString() || value.getAsString().length() > maxLength) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return value.getAsString();
    }

    private static String matching(JsonObject json, String key, Pattern pattern) throws UploadException {
        String value = text(json, key, 2048);
        if (!pattern.matcher(value).matches()) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return value;
    }

    private static String word(JsonObject json, String key, String... words) throws UploadException {
        String value = text(json, key, 32);
        for (String word : words) {
            if (word.equals(value)) {
                return value;
            }
        }
        throw new UploadException(ApiError.INTERNAL);
    }

    private static long number(JsonObject json, String key, long min, long max) throws UploadException {
        if (!(json.get(key) instanceof JsonPrimitive value) || !value.isNumber() || !value.getAsString().matches("[0-9]{1,13}")) {
            throw new UploadException(ApiError.INTERNAL);
        }
        long number = Long.parseLong(value.getAsString());
        if (number < min || number > max) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return number;
    }

    private static List<String> shas(JsonElement element) throws UploadException {
        List<String> shas = new ArrayList<>();
        for (JsonElement entry : array(element, 1024)) {
            if (!(entry instanceof JsonPrimitive value) || !value.isString()) {
                throw new UploadException(ApiError.INTERNAL);
            }
            shas.add(sha(value.getAsString()));
        }
        return List.copyOf(shas);
    }

    private static Published published(JsonObject json) throws UploadException {
        String id = text(json, "id", PlayerIds.ID_LENGTH);
        String code = text(json, "code", PlayerIds.CODE_LENGTH);
        if (!PlayerIds.isPlayerId(id) || !PlayerIds.isPlayerCode(code)) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return new Published(id, code, (int) number(json, "rev", 1, PlayerHead.MAX_REV), number(json, "seq", 1, MAX_NUMBER),
                sha(text(json, "index", 64)));
    }

    private static String ownerState(JsonElement element) throws UploadException {
        return word(exact(element, "state"), "state", "active", "frozen", "banned");
    }

    // Parts of a path: each is checked before it is put in one
    private static String sha(String sha256) throws UploadException {
        if (!Sha256.isValidHex(sha256)) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return sha256;
    }

    private static String upload(String upload) throws UploadException {
        if (!UPLOAD.matcher(upload).matches()) {
            throw new UploadException(ApiError.INTERNAL);
        }
        return upload;
    }

    private static String id(String id) throws UploadException {
        if (!PlayerIds.isPlayerId(id)) {
            throw new UploadException(ApiError.BAD_REQUEST);
        }
        return id;
    }
}
