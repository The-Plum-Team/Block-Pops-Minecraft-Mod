package com.theplumteam.client.upload;

import com.theplumteam.client.upload.PublishRun.Step;
import com.theplumteam.client.upload.UploadApi.Account;
import com.theplumteam.client.upload.UploadApi.Published;
import com.theplumteam.client.upload.UploadTransport.Answer;
import com.theplumteam.client.upload.UploadTransport.Call;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.ContentError;
import com.theplumteam.remote.player.UploadIntent;
import com.theplumteam.remote.player.UploadPackage;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game's side of the upload service, against a stand-in that answers as the service does:
 * a session only after the right join, a package only for the session's intent, each file once.
 */
class UploadClientTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String CODE = "maple-orbit-42";
    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";
    private static final String UPLOAD = "01hqv5c3y7n9k2m4p6r8s0t1vw";
    private static final byte[] RECORD = "{\"name\":\"Mis figuras\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SKIN = "skin".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOGO = "logo".getBytes(StandardCharsets.UTF_8);

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The service, as much of it as the game talks to. */
    private static final class Service implements UploadTransport {
        final List<String> asked = new ArrayList<>();
        final Set<String> joins = new LinkedHashSet<>();
        final Map<String, String> nonces = new HashMap<>();
        final Map<String, String> sessions = new LinkedHashMap<>();
        final Map<String, byte[]> arrived = new LinkedHashMap<>();
        final Set<String> kept = new LinkedHashSet<>();
        final List<String> uploadShas = new ArrayList<>();
        String config = "{\"schema\":1,\"uploads\":\"open\",\"min_client\":\"2.2.0\",\"terms\":1,\"rules\":1,\"limits\":"
                + "{\"collections\":5,\"figures\":128,\"bytes_collection\":16777216,\"bytes_owner\":67108864,"
                + "\"daily_commits\":50}}";
        String supporter = "{\"active\":true,\"via\":\"paynow-uuid\",\"until\":\"2026-12-09\"}";
        String sessionError;
        String sessionName = "Steve";
        String me = "{\"schema\":1,\"collections\":[],\"quota\":{\"collections\":0,\"collections_limit\":5,\"bytes\":0,"
                + "\"bytes_limit\":67108864,\"commits_today\":0,\"commits_limit\":50},\"supporter\":{\"active\":false},"
                + "\"owner\":{\"state\":\"active\"}}";
        /** How many of the next requests to a path with this prefix get no answer. */
        final Map<String, Integer> lost = new HashMap<>();
        /** The request, counted from 1, at which every session so far stops being good. */
        int expireAt = -1;
        /** An answer given instead of the usual one to the next request to a path with this prefix. */
        final Map<String, Answer> instead = new HashMap<>();
        boolean committed;
        int commits;
        int counter;

        private static Answer json(int status, String body) {
            return new Answer(status, utf8(body));
        }

        private static Answer error(int status, String code) {
            return json(status, "{\"schema\":1,\"error\":\"" + code + "\"}");
        }

        @Override
        public Answer send(Call call) throws UploadException {
            asked.add(call.method() + " " + call.path());
            if (asked.size() == expireAt) {
                sessions.clear();
            }
            for (Map.Entry<String, Integer> entry : lost.entrySet()) {
                if (call.path().startsWith(entry.getKey()) && entry.getValue() > 0) {
                    entry.setValue(entry.getValue() - 1);
                    // A commit whose answer is lost has still happened
                    if (call.path().endsWith("/commit")) {
                        committed = true;
                    }
                    throw new UploadException(ApiError.NETWORK);
                }
            }
            for (String prefix : List.copyOf(instead.keySet())) {
                if (call.path().startsWith(prefix)) {
                    return instead.remove(prefix);
                }
            }
            String body = call.body() == null ? "" : new String(call.body(), StandardCharsets.UTF_8);
            if (call.path().equals("/v1/config")) {
                return json(200, config);
            }
            if (call.path().equals("/v1/auth/challenge")) {
                String intent = body.replaceAll(".*\"intent\":\"([^\"]*)\".*", "$1");
                String nonce = String.format("%032x", ++counter);
                nonces.put(nonce, intent);
                return json(200, "{\"schema\":1,\"nonce\":\"" + nonce + "\",\"expires_in\":60}");
            }
            if (call.path().equals("/v1/auth/session")) {
                String nonce = body.replaceAll(".*\"nonce\":\"([^\"]*)\".*", "$1");
                String intent = nonces.remove(nonce);
                UploadIntent parsed = intent == null ? null : UploadIntent.parse(intent);
                // Mojang says who joined the server this nonce and intent name
                if (parsed == null || !joins.contains(SessionClient.sha1Hex(
                        UploadIntent.joinText(nonce, parsed.digest(JdkSha256.INSTANCE))))) {
                    return error(401, "not_verified");
                }
                if (sessionError != null) {
                    return error(403, sessionError);
                }
                String token = "bpu1.s" + (++counter) + ".mac";
                sessions.put(token, intent);
                return json(200, "{\"schema\":1,\"token\":\"" + token + "\",\"expires_in\":900,\"uuid\":\"" + UUID
                        + "\",\"name\":\"" + sessionName + "\",\"supporter\":" + supporter + ",\"owner\":{\"state\":\"active\"}}");
            }
            String intent = sessions.get(call.token());
            if (intent == null) {
                return error(401, "not_verified");
            }
            UploadIntent parsed = UploadIntent.parse(intent);
            if (call.path().equals("/v1/packages")) {
                if (parsed.action() != UploadIntent.Action.PUBLISH || !JdkSha256.of(call.body()).equals(parsed.sha256())) {
                    return error(400, "bad_request");
                }
                StringBuilder missing = new StringBuilder();
                for (String sha : uploadShas) {
                    if (!arrived.containsKey(sha) && !kept.contains(sha)) {
                        missing.append(missing.isEmpty() ? "" : ",").append('"').append(sha).append('"');
                    }
                }
                return json(200, "{\"schema\":1,\"upload\":\"" + UPLOAD + "\",\"missing\":[" + missing + "],\"expires_in\":900}");
            }
            if (call.path().startsWith("/v1/packages/" + UPLOAD + "/blobs/")) {
                String sha = call.path().substring(call.path().lastIndexOf('/') + 1);
                if (!JdkSha256.of(call.body()).equals(sha) || arrived.containsKey(sha)) {
                    return error(400, "bad_request");
                }
                arrived.put(sha, call.body());
                return new Answer(204, new byte[0]);
            }
            String result = "{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"rev\":1,\"seq\":1,\"index\":\"" + "ab".repeat(32) + "\"}";
            if (call.path().equals("/v1/packages/" + UPLOAD + "/commit")) {
                commits++;
                committed = true;
                return json(200, "{\"schema\":1," + result.substring(1));
            }
            if (call.path().equals("/v1/packages/" + UPLOAD)) {
                return json(200, committed ? "{\"schema\":1,\"state\":\"committed\",\"missing\":[],\"result\":" + result + "}"
                        : "{\"schema\":1,\"state\":\"open\",\"missing\":[]}");
            }
            if (call.path().equals("/v1/me")) {
                return parsed.action() == UploadIntent.Action.LIST ? json(200, me) : error(401, "not_verified");
            }
            if (call.path().equals("/v1/links/start")) {
                return json(200, "{\"schema\":1,\"code\":\"BP-ABCD2345\",\"expires_in\":1800}");
            }
            if (call.path().equals("/v1/collections/" + ID + "/rollback")) {
                return json(200, "{\"schema\":1,\"rev\":4,\"seq\":6,\"index\":\"" + "cd".repeat(32) + "\"}");
            }
            if (call.path().startsWith("/v1/collections/" + ID)) {
                return new Answer(204, new byte[0]);
            }
            return error(404, "not_found");
        }
    }

    /** Joins as the game does: the service's Mojang hears of it. */
    private static Joiner joiner(Service service, String name) {
        return new Joiner() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void join(String serverId) {
                service.joins.add(serverId);
            }
        };
    }

    private static UploadPackage pack(String target, int baseRev) {
        return new UploadPackage(1, 1, "2.2.0+1.21.8-fabric", target, baseRev, JdkSha256.of(RECORD), List.of(
                new UploadPackage.Blob(JdkSha256.of(RECORD), null, RECORD.length),
                new UploadPackage.Blob(JdkSha256.of(SKIN), BlobType.PNG, SKIN.length),
                new UploadPackage.Blob(JdkSha256.of(LOGO), BlobType.PNG, LOGO.length)));
    }

    private static final class Run {
        final Service service = new Service();
        final List<String> steps = new ArrayList<>();
        final List<Long> pauses = new ArrayList<>();
        final UploadApi api = new UploadApi(service);
        final SessionClient sessions = new SessionClient(api, joiner(service, "Steve"));
        final PublishRun publish = new PublishRun(api, sessions, pauses::add);

        Run() {
            service.uploadShas.addAll(List.of(JdkSha256.of(RECORD), JdkSha256.of(SKIN), JdkSha256.of(LOGO)));
        }

        Published go(UploadPackage pack) throws UploadException {
            Map<String, byte[]> files = Map.of(JdkSha256.of(RECORD), RECORD, JdkSha256.of(SKIN), SKIN,
                    JdkSha256.of(LOGO), LOGO);
            return publish.run(pack, pack.toUtf8(), files::get,
                    (step, done, total) -> steps.add(step + (step == Step.FILES ? " " + done + "/" + total : "")));
        }

        ApiError refused(UploadPackage pack) {
            return assertThrows(UploadException.class, () -> go(pack)).error();
        }
    }

    @Test
    void aCollectionIsPublishedInOneSessionGivenForExactlyItsPackage() throws UploadException {
        Run run = new Run();
        Published published = run.go(pack(null, 0));
        assertEquals(new Published(ID, CODE, 1, 1, "ab".repeat(32)), published);
        assertEquals(List.of("GET /v1/config", "POST /v1/auth/challenge", "POST /v1/auth/session", "POST /v1/packages",
                "PUT /v1/packages/" + UPLOAD + "/blobs/" + JdkSha256.of(RECORD),
                "PUT /v1/packages/" + UPLOAD + "/blobs/" + JdkSha256.of(SKIN),
                "PUT /v1/packages/" + UPLOAD + "/blobs/" + JdkSha256.of(LOGO),
                "POST /v1/packages/" + UPLOAD + "/commit"), run.service.asked);
        assertEquals(List.of("CHECKING", "VERIFYING", "FILES 0/3", "FILES 1/3", "FILES 2/3", "PUBLISHING"), run.steps);
        assertEquals(1, run.service.joins.size());
        assertTrue(run.pauses.isEmpty());

        // Changes to it: the session names the collection and the revision it builds on, and
        // only what the service does not hold yet goes up
        Run changes = new Run();
        changes.service.kept.addAll(List.of(JdkSha256.of(SKIN), JdkSha256.of(LOGO)));
        changes.go(pack(ID, 1));
        assertEquals(List.of("FILES 0/1"), changes.steps.stream().filter(step -> step.startsWith("FILES")).toList());
        assertTrue(changes.service.sessions.containsValue("publish|" + ID + "|1|" + JdkSha256.of(pack(ID, 1).toUtf8())));
    }

    @Test
    void anAnswerThatNeverCameIsAskedForAgainAndNothingGoesUpTwice() throws UploadException {
        Run run = new Run();
        run.service.lost.put("/v1/packages/" + UPLOAD + "/blobs/" + JdkSha256.of(SKIN), 2);
        run.service.lost.put("/v1/packages/" + UPLOAD + "/commit", 1);
        assertEquals(ID, run.go(pack(null, 0)).id());
        assertEquals(3, run.service.arrived.size());
        // Two waits for the file, one before asking what became of the commit, which had happened
        assertEquals(List.of(1_000L, 3_000L, 1_000L), run.pauses);
        assertEquals("GET /v1/packages/" + UPLOAD, run.service.asked.get(run.service.asked.size() - 1));
        assertEquals(0, run.service.commits);

        // A file that never gets an answer ends the run; nothing is committed
        Run stuck = new Run();
        stuck.service.lost.put("/v1/packages/" + UPLOAD + "/blobs/", PublishRun.ATTEMPTS);
        assertEquals(ApiError.NETWORK, stuck.refused(pack(null, 0)));
        assertFalse(stuck.service.committed);
        // And with no network at all nothing is asked of Mojang
        PublishRun offline = new PublishRun(new UploadApi(UploadTransport.NONE),
                new SessionClient(new UploadApi(UploadTransport.NONE), Joiner.NONE), millis -> { });
        assertEquals(ApiError.NETWORK, assertThrows(UploadException.class,
                () -> offline.run(pack(null, 0), pack(null, 0).toUtf8(), sha -> RECORD, (step, done, total) -> { })).error());
    }

    @Test
    void aSessionThatRanOutIsOpenedAgainAndTheUploadGoesOn() throws UploadException {
        Run run = new Run();
        // The sixth request is the second file: by then the session is no longer good
        run.service.expireAt = 6;
        assertEquals(ID, run.go(pack(null, 0)).id());
        assertEquals(2, run.service.joins.size());
        assertEquals(3, run.service.arrived.size());
        assertEquals(List.of("CHECKING", "VERIFYING", "FILES 0/3", "FILES 1/3", "VERIFYING", "FILES 0/2", "FILES 1/2",
                "PUBLISHING"), run.steps);
    }

    @Test
    void whatTheServiceRefusesIsToldAsItsErrorAndItsNumbers() {
        Run paused = new Run();
        paused.service.config = paused.service.config.replace("\"open\"", "\"paused\"");
        assertEquals(ApiError.PAUSED, paused.refused(pack(null, 0)));
        // Known before anybody is asked to prove anything
        assertEquals(List.of("GET /v1/config"), paused.service.asked);
        Run terms = new Run();
        terms.service.config = terms.service.config.replace("\"terms\":1", "\"terms\":2");
        assertEquals(ApiError.TERMS_OUTDATED, terms.refused(pack(null, 0)));
        Run outdated = new Run();
        outdated.service.config = outdated.service.config.replace("2.2.0", "2.3.0");
        assertEquals(ApiError.CLIENT_OUTDATED, outdated.refused(pack(null, 0)));

        Run gate = new Run();
        gate.service.sessionError = "supporter_link_required";
        assertEquals(ApiError.SUPPORTER_LINK_REQUIRED, gate.refused(pack(null, 0)));
        assertTrue(gate.service.arrived.isEmpty());

        Run conflict = new Run();
        conflict.service.instead.put("/v1/packages", Service.json(409, "{\"schema\":1,\"error\":\"conflict\",\"detail\":7}"));
        UploadException moved = assertThrows(UploadException.class, () -> conflict.go(pack(ID, 5)));
        assertEquals(ApiError.CONFLICT, moved.error());
        assertEquals(7, moved.detail());

        Run file = new Run();
        file.service.instead.put("/v1/packages/" + UPLOAD + "/blobs/", Service.json(422, "{\"schema\":1,\"error\":"
                + "\"invalid_file\",\"rule\":\"" + ContentError.values()[0].id() + "\",\"field\":\"/figures/3/texture\","
                + "\"figure\":3,\"limit\":64,\"value\":128,\"retry_after\":30,\"more\":\"<b>text</b>\"}"));
        UploadException invalid = assertThrows(UploadException.class, () -> file.go(pack(null, 0)));
        assertEquals(ApiError.INVALID_FILE, invalid.error());
        assertEquals(ContentError.values()[0], invalid.rule());
        assertEquals("/figures/3/texture", invalid.field());
        assertEquals(List.of(3L, 64L, 128L, 30L), List.of((long) invalid.figure(), invalid.limit(), invalid.value(),
                invalid.retryAfter()));
    }

    @Test
    void anAnswerThatIsNotTheServicesIsNeverTakenForOne() {
        Run run = new Run();
        // A gateway's page is no answer; an unknown error code is an error all the same
        run.service.instead.put("/v1/config", new Answer(502, utf8("<html>Bad gateway</html>")));
        assertEquals(ApiError.NETWORK, run.refused(pack(null, 0)));
        run.service.instead.put("/v1/config", Service.error(418, "teapot"));
        assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)));
        run.service.instead.put("/v1/config", new Answer(404, utf8("{\"error\":{\"path\":\"../../x\"},\"field\":\"/../..\"}")));
        assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)));

        // A success with another shape than the call has: a key more, a key less, a value of another kind
        String good = run.service.config;
        for (String bad : new String[] {good.replace("\"terms\":1", "\"terms\":1,\"extra\":true"),
            good.replace("\"terms\":1,", ""), good.replace("\"terms\":1", "\"terms\":\"1\""),
            good.replace("\"terms\":1", "\"terms\":1.5"), good.replace("\"open\"", "\"wide open\""),
            good.replace("\"schema\":1", "\"schema\":2"), "[]", ""}) {
            run.service.config = bad;
            assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)), bad);
        }
        run.service.config = good;

        // A session for another account than the one that asked, or a token that is not one
        run.service.sessionName = "Alex";
        assertEquals(ApiError.NOT_VERIFIED, run.refused(pack(null, 0)));
        run.service.sessionName = "Steve";
        run.service.instead.put("/v1/auth/session", Service.json(200, "{\"schema\":1,\"token\":\"Bearer x\\r\\nX-Evil: 1\","
                + "\"expires_in\":900,\"uuid\":\"" + UUID + "\",\"name\":\"Steve\",\"supporter\":{\"active\":false},"
                + "\"owner\":{\"state\":\"active\"}}"));
        assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)));
        // An upload id or a file name that would walk somewhere else never becomes a path
        run.service.instead.put("/v1/packages", Service.json(200, "{\"schema\":1,\"upload\":\"../../admin/v1/flags\","
                + "\"missing\":[],\"expires_in\":900}"));
        assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)));
        run.service.instead.put("/v1/packages", Service.json(200, "{\"schema\":1,\"upload\":\"" + UPLOAD + "\","
                + "\"missing\":[\"../x\"],\"expires_in\":900}"));
        assertEquals(ApiError.INTERNAL, run.refused(pack(null, 0)));
        assertFalse(run.service.asked.stream().anyMatch(path -> path.contains("..")));
    }

    @Test
    void anOwnerSeesAndManagesWhatTheyPublishedEachByItsOwnSession() throws UploadException {
        Service service = new Service();
        UploadApi api = new UploadApi(service);
        ManageRun manage = new ManageRun(api, new SessionClient(api, joiner(service, "Steve")));
        String row = "{\"id\":\"" + ID + "\",\"code\":\"" + CODE + "\",\"name\":\"Mis figuras\",\"rev\":3,\"state\":\"live\","
                + "\"updated_at\":1794050000,\"revisions\":[{\"rev\":3,\"at\":1794050000,\"figures\":2,\"bytes\":4096,"
                + "\"state\":\"kept\"},{\"rev\":2,\"at\":1794000000,\"figures\":1,\"bytes\":2048,\"state\":\"withdrawn\"}]}";
        // Rows whose name or code no collection could have are data from the internet, and are left out
        String hostile = row.replace("Mis figuras", "§kMis figuras").replace(ID, "p_aaaaaaaaaaaa");
        String foreign = row.replace(CODE, "willow-media").replace(ID, "p_bbbbbbbbbbbb");
        String removed = row.replace("\"state\":\"live\"", "\"state\":\"removed\",\"reason\":\"policy\"").replace(ID, "p_cccccccccccc");
        service.me = service.me.replace("\"collections\":[]", "\"collections\":[" + row + "," + hostile + "," + foreign + ","
                + removed + "]").replace("\"supporter\":{\"active\":false}", "\"supporter\":" + service.supporter);

        Account account = manage.list();
        assertEquals(List.of(ID, "p_cccccccccccc"), account.collections().stream().map(UploadApi.Mine::id).toList());
        assertEquals("Mis figuras", account.collections().get(0).name());
        assertEquals(List.of("kept", "withdrawn"), account.collections().get(0).revisions().stream()
                .map(UploadApi.RevisionRow::state).toList());
        assertNull(account.collections().get(0).reason());
        assertEquals("policy", account.collections().get(1).reason());
        assertEquals(new UploadApi.Supporter(true, "paynow-uuid", "2026-12-09"), account.supporter());
        assertEquals(5, account.quota().collectionsLimit());

        assertEquals(new UploadApi.Rolled(4, 6, "cd".repeat(32)), manage.rollback(ID, 3, 2));
        manage.withdraw(ID, 3, 2);
        manage.delete(ID, 3);
        assertEquals("BP-ABCD2345", manage.link().code());
        assertEquals(List.of("list", "rollback|" + ID + "|3|2", "withdraw|" + ID + "|3|2", "delete|" + ID + "|3", "link"),
                List.copyOf(new LinkedHashSet<>(service.sessions.values())));
        assertEquals(5, service.joins.size());
        assertTrue(service.asked.containsAll(List.of("POST /v1/collections/" + ID + "/rollback",
                "POST /v1/collections/" + ID + "/revisions/2/withdraw", "DELETE /v1/collections/" + ID,
                "POST /v1/links/start")));
        // A link code of another shape is not shown
        service.instead.put("/v1/links/start", Service.json(200, "{\"schema\":1,\"code\":\"https://evil.example\","
                + "\"expires_in\":1800}"));
        assertEquals(ApiError.INTERNAL, assertThrows(UploadException.class, manage::link).error());
        // Somebody else's collection id is refused before it is asked for
        assertThrows(UploadException.class, () -> api.delete("bpu1.s1.mac", "../../admin"));
    }

    @Test
    void aBuildNamesItselfByItsVersionTheGamesAndItsLoader() {
        assertEquals("2.2.0+1.21.8-fabric", UploadClientId.of("2.2.0", "1.21.8", "fabric"));
        assertEquals("2.2.0+26.3-neoforge", UploadClientId.of("2.2.0+mc26.3", "26.3", "neoforge"));
        assertEquals("0.0.0+unknown-forge", UploadClientId.of("${version}", null, "forge"));
        assertEquals("2.10.3+1.20.1-forge", UploadClientId.of("2.10.3-SNAPSHOT", "1.20.1\r\nX-Evil: 1", "forge").replace("XEvil1", ""));
        for (String name : List.of(UploadClientId.of("2.2.0", "1.21.8", "fabric"), UploadClientId.of("x", "y z", "forge"))) {
            assertTrue(UploadPackage.isClient(name), name);
        }
        assertTrue(PublishRun.older("2.1.9+1.21.8-fabric", "2.2.0"));
        assertTrue(PublishRun.older("2.2.0+1.21.8-fabric", "2.10.0"));
        assertFalse(PublishRun.older("2.2.0+1.21.8-fabric", "2.2.0"));
        assertFalse(PublishRun.older("3.0.0+26.3-neoforge", "2.9.9"));
    }

    @Test
    void everyErrorHasItsOwnWordsAndTheNumbersThatGoInThem() {
        for (ApiError error : ApiError.values()) {
            assertEquals("blockpops.upload.error." + error.id(), UploadErrors.key(error));
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (ContentError rule : ContentError.values()) {
            kinds.add(UploadErrors.ruleKey(rule).substring("blockpops.upload.rule.".length()));
        }
        assertEquals(Set.of("file", "json", "text", "png", "model", "animation", "record", "budget"), kinds);
        assertEquals("blockpops.upload.rule.png", UploadErrors.ruleKey(ContentError.PNG_SIZE_ROLE));
        assertEquals("blockpops.upload.rule.file", UploadErrors.ruleKey(ContentError.SHA256));

        assertEquals(List.of("7"), List.of(UploadErrors.arguments(
                new UploadException(ApiError.CONFLICT, 7, -1, -1, -1, -1, null, null))));
        assertEquals(List.of("5"), List.of(UploadErrors.arguments(
                new UploadException(ApiError.QUOTA_COLLECTIONS, -1, -1, 5, -1, -1, null, null))));
        assertEquals(List.of("70.5", "64.0"), List.of(UploadErrors.arguments(
                new UploadException(ApiError.QUOTA_BYTES, -1, -1, 64L << 20, (70L << 20) + (1L << 19), -1, null, null))));
        // Seconds become whole minutes, never none; a number the service did not send is not made up
        assertEquals(List.of("2"), List.of(UploadErrors.arguments(
                new UploadException(ApiError.RATE_LIMITED, -1, 61, -1, -1, -1, null, null))));
        assertEquals(List.of("1"), List.of(UploadErrors.arguments(
                new UploadException(ApiError.QUOTA_GLOBAL, -1, 0, -1, -1, -1, null, null))));
        assertEquals(List.of("?"), List.of(UploadErrors.arguments(new UploadException(ApiError.RATE_LIMITED))));
        assertEquals(0, UploadErrors.arguments(new UploadException(ApiError.NETWORK)).length);
    }

    private static byte[] png(int width, int height, int shade) throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 4) & 0xFF) << 16 | ((y * 4) & 0xFF) << 8 | shade);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void anExportIsTheCollectionAsItIsPublishedAndNothingElse() throws Exception {
        // A model and its poses as an editor saves them, with spaces and line breaks; images are
        // stored in their canonical form already
        byte[] model = utf8("{\n  \"format_version\": \"1.12.0\",\n  \"minecraft:geometry\": [{\"description\": "
                + "{\"identifier\": \"geometry.x\", \"texture_width\": 64, \"texture_height\": 32}, \"bones\": [{\"name\": "
                + "\"root\", \"pivot\": [0, 0, 0], \"cubes\": [{\"origin\": [-4, 0, -4], \"size\": [8, 8, 8], \"uv\": [0, 0]}]}]}]\n}\n");
        byte[] poses = utf8("{ \"format_version\": \"1.8.0\", \"animations\": { \"Pose_Stand\": "
                + "{ \"bones\": { \"root\": { \"rotation\": [0, 0, 0] } } } } }");
        byte[] skin = png(64, 64, 0);
        byte[] retired = png(64, 64, 200);
        byte[] texture = png(64, 32, 90);
        Map<String, byte[]> stored = new LinkedHashMap<>();
        Map<String, com.theplumteam.worldcontent.BlobInfo> table = new LinkedHashMap<>();
        for (byte[] image : List.of(skin, retired, texture)) {
            stored.put(JdkSha256.of(image), image);
            table.put(JdkSha256.of(image), BlobType.PNG.validate(image));
        }
        stored.put(JdkSha256.of(model), model);
        table.put(JdkSha256.of(model), BlobType.GEO.validate(model));
        stored.put(JdkSha256.of(poses), poses);
        table.put(JdkSha256.of(poses), BlobType.ANIMATION.validate(poses));
        com.theplumteam.worldcontent.WorldCollectionRecord record = new com.theplumteam.worldcontent.WorldCollectionRecord(
                "w-mine", "Mis figuras", "Whoever", 10, 20, 30, null, "red", null, false, 7, List.of(
                new com.theplumteam.worldcontent.WorldCollectionRecord.Figure("uno", "Uno",
                        com.theplumteam.worldcontent.WorldCollectionRecord.Kind.SKIN, JdkSha256.of(skin), null, null, 1.0f, true),
                new com.theplumteam.worldcontent.WorldCollectionRecord.Figure("dos", "Dos",
                        com.theplumteam.worldcontent.WorldCollectionRecord.Kind.SKIN, JdkSha256.of(retired), null, null, 1.0f,
                        false),
                new com.theplumteam.worldcontent.WorldCollectionRecord.Figure("tres", "Tres",
                        com.theplumteam.worldcontent.WorldCollectionRecord.Kind.MODEL, JdkSha256.of(texture),
                        JdkSha256.of(model), JdkSha256.of(poses), 1.0f, true)));

        PlayerExport.Export export = PlayerExport.of(record, table, stored::get, null, 0, 1, "2.2.0+1.21.8-fabric", "Steve");
        String recordText = new String(export.record(), StandardCharsets.UTF_8);
        // The model and its poses are published in their one spelling, and the record names that one
        byte[] minified = com.theplumteam.worldcontent.CanonicalJson.minify(model, 1 << 20);
        assertFalse(java.util.Arrays.equals(model, minified));
        assertTrue(recordText.contains(JdkSha256.of(minified)));
        assertFalse(recordText.contains(JdkSha256.of(model)));
        assertFalse(recordText.contains(JdkSha256.of(poses)));
        assertTrue(export.files().containsKey(JdkSha256.of(minified)));
        // An image that is canonical already keeps its name
        assertTrue(recordText.contains(JdkSha256.of(skin)));
        // The figure that is turned off is not published, and neither is its file
        assertFalse(recordText.contains("Dos"));
        assertFalse(recordText.contains(JdkSha256.of(retired)));
        assertFalse(export.files().containsKey(JdkSha256.of(retired)));
        // Nor anything that belongs to the world it was made in or to whoever made it
        for (String kept : List.of("w-mine", "Whoever", "revision", "draft", "archived")) {
            assertFalse(recordText.contains(kept), kept);
        }
        // The package lists the record and exactly the files it names, each once
        assertEquals(5, export.pack().blobs().size());
        assertEquals(export.files().keySet(), new LinkedHashSet<>(export.pack().blobs().stream()
                .map(UploadPackage.Blob::sha256).toList()));
        assertEquals(JdkSha256.of(export.record()), export.pack().record());
        assertNull(export.pack().target());
        for (Map.Entry<String, byte[]> file : export.files().entrySet()) {
            assertEquals(file.getKey(), JdkSha256.of(file.getValue()));
        }
        // Exporting twice gives the same bytes, and so the same package and the same session
        assertEquals(JdkSha256.of(export.packageBytes()), JdkSha256.of(PlayerExport.of(record, table, stored::get, null, 0, 1,
                "2.2.0+1.21.8-fabric", "Steve").packageBytes()));
        // The package it makes is one the service reads
        assertEquals(export.pack(), UploadPackage.parse(export.packageBytes()));

        // A file that is not at hand, no figure left, or a name no published collection may have
        assertThrows(com.theplumteam.worldcontent.WorldContentException.class,
                () -> PlayerExport.of(record, table, sha -> null, null, 0, 1, "2.2.0+1.21.8-fabric", "Steve"));
        assertThrows(com.theplumteam.worldcontent.WorldContentException.class, () -> PlayerExport.of(
                record.withFigures(List.of(record.figures().get(1))), table, stored::get, null, 0, 1, "2.2.0+1.21.8-fabric",
                "Steve"));
        assertThrows(com.theplumteam.worldcontent.WorldContentException.class, () -> PlayerExport.of(
                record.withIdentity("\u00a7kMis figuras", "", 10, 20, 30, null, "red", null), table, stored::get, null, 0, 1,
                "2.2.0+1.21.8-fabric", "Steve"));
    }
}
