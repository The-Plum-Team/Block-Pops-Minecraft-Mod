package com.theplumteam.client.upload;

import com.theplumteam.client.remote.PlayerDownload;
import com.theplumteam.client.upload.UploadApi.Account;
import com.theplumteam.client.upload.UploadApi.Published;
import com.theplumteam.client.upload.UploadTransport.Answer;
import com.theplumteam.client.upload.UploadTransport.Call;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.RemoteNotFoundException;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.remote.VerifiedStore;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.remote.player.PlayerFetcher.Refused;
import com.theplumteam.remote.player.PlayerFetcher.Resolved;
import com.theplumteam.remote.player.PlayerFetcher.Why;
import com.theplumteam.remote.player.PlayerKeys;
import com.theplumteam.remote.player.PlayerRules;
import com.theplumteam.remote.player.PlayerStore;
import com.theplumteam.remote.player.UploadPackage;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A rehearsal of the whole way a collection takes, with the game's code at one end and the
 * upload service's own code at the other: publish, read it back as a server or a client would,
 * change it, go back, withdraw, delete. It runs only where a rehearsal service is given:
 *
 * <pre>BLOCKPOPS_REHEARSAL="http://127.0.0.1:8787|o1|&lt;the public key it printed&gt;"</pre>
 *
 * which is the upload service's {@code test/dev-server.ts}, on this machine alone. Without it
 * the test is skipped: nothing in the build or the packaged E2E ever contacts a service.
 */
class UploadRehearsalTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @TempDir
    Path cache;

    private static HttpResponse<byte[]> send(String method, String url, String token, byte[] body, String contentType)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("X-BlockPops-Client", "2.2.0+1.20.1-fabric");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static byte[] skin(int shade) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 4 + shade) & 0xFF) << 16 | ((y * 4) & 0xFF) << 8 | shade);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return PngValidator.canonicalize(out.toByteArray());
    }

    private static WorldCollectionRecord record(String name, List<byte[]> skins) {
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>();
        for (int i = 0; i < skins.size(); i++) {
            figures.add(new WorldCollectionRecord.Figure("figura" + i, "Figura " + i, WorldCollectionRecord.Kind.SKIN,
                    JdkSha256.of(skins.get(i)), null, null, 1.0f, true));
        }
        return new WorldCollectionRecord("w-col", name, "", 10, 20, 30, null, "red", null, false, 0, figures);
    }

    @Test
    void aCollectionGoesFromTheGameThroughTheServiceAndBackIntoAGame() throws Exception {
        String rehearsal = System.getenv("BLOCKPOPS_REHEARSAL");
        assumeTrue(rehearsal != null && !rehearsal.isBlank(), "no rehearsal service given");
        String[] parts = rehearsal.split("\\|");
        String base = parts[0];
        PlayerKeys keys = PlayerKeys.of(new String[][] {{parts[1], "online", parts[2]}});

        UploadTransport transport = call -> {
            try {
                HttpResponse<byte[]> answer = send(call.method(), base + call.path(), call.token(), call.body(),
                        call.contentType());
                return new Answer(answer.statusCode(), answer.body());
            } catch (IOException e) {
                throw new UploadException(ApiError.NETWORK);
            }
        };
        Joiner joiner = new Joiner() {
            @Override
            public String name() {
                return "Steve";
            }

            @Override
            public void join(String serverId) throws UploadException {
                // What Mojang would hear of this game joining
                try {
                    send("POST", base + "/dev/join", null, ("{\"name\":\"Steve\",\"uuid\":\"" + UUID + "\",\"serverId\":\""
                            + serverId + "\"}").getBytes(StandardCharsets.UTF_8), "application/json");
                } catch (IOException e) {
                    throw new UploadException(ApiError.NETWORK);
                }
            }
        };
        UploadApi api = new UploadApi(transport);
        SessionClient sessions = new SessionClient(api, joiner);
        PublishRun publish = new PublishRun(api, sessions, Thread::sleep);
        ManageRun manage = new ManageRun(api, sessions);

        // ---- Publish: what the creator holds, exported as the game exports it
        byte[] first = skin(0);
        byte[] second = skin(90);
        Map<String, BlobInfo> table = new HashMap<>();
        Map<String, byte[]> stored = new HashMap<>();
        for (byte[] file : List.of(first, second)) {
            table.put(JdkSha256.of(file), BlobType.PNG.validate(file));
            stored.put(JdkSha256.of(file), file);
        }
        PlayerExport.Export exportOne = PlayerExport.of(record("Mis figuras", List.of(first)), table, stored::get, null, 0,
                1, "2.2.0+1.20.1-fabric", "Steve");
        UploadPackage packOne = exportOne.pack();
        List<String> steps = new ArrayList<>();
        Published published = publish.run(packOne, exportOne.packageBytes(), exportOne::bytes,
                (step, done, total) -> steps.add(step.name()));
        assertEquals(1, published.rev());
        assertTrue(steps.containsAll(List.of("CHECKING", "VERIFYING", "FILES", "PUBLISHING")), steps.toString());

        // ---- Read it back as a game does: signed documents, then the file, checked whole
        send("POST", base + "/dev/cron", null, "{\"job\":\"daily\"}".getBytes(StandardCharsets.UTF_8), "application/json");
        RemoteSource cdn = (path, maxBytes) -> {
            HttpResponse<byte[]> answer = send("GET", base + "/cdn/" + path, null, null, null);
            if (answer.statusCode() == 404) {
                throw new RemoteNotFoundException("Not found: " + path);
            }
            if (answer.statusCode() != 200 || answer.body().length > maxBytes) {
                throw new RemoteException("Could not read " + path);
            }
            return answer.body();
        };
        PlayerFetcher fetcher = new PlayerFetcher(cdn, new PlayerStore(new VerifiedStore(cache)), keys,
                () -> System.currentTimeMillis() / 1000);
        Resolved resolved = fetcher.resolve(published.code());
        assertEquals(published.id(), resolved.head().id());
        assertEquals(published.index(), resolved.revision().indexSha256());
        assertEquals("Mis figuras", resolved.revision().collection().getName());
        assertEquals("Steve", resolved.revision().index().author());
        BlobInfo fact = resolved.revision().index().blobs().get(JdkSha256.of(first));
        byte[] fetched = fetcher.file(published.id(), JdkSha256.of(first), fact);
        assertArrayEquals(first, fetched);
        assertTrue(BlobType.PNG.validatePlayer(fetched).sameFile(fact));

        // ---- Change it: only what is new goes up, and the service will not take a change built on an old head
        PlayerExport.Export exportTwo = PlayerExport.of(record("Mis figuras", List.of(first, second)), table, stored::get,
                published.id(), 1, 1, "2.2.0+1.20.1-fabric", "Steve");
        UploadPackage packTwo = exportTwo.pack();
        List<String> sent = new ArrayList<>();
        Published changed = publish.run(packTwo, exportTwo.packageBytes(), sha -> {
            sent.add(sha);
            return exportTwo.bytes(sha);
        }, (step, done, total) -> { });
        assertEquals(2, changed.rev());
        assertEquals(List.of(JdkSha256.of(exportTwo.record()), JdkSha256.of(second)), sent);
        UploadException stale = assertThrows(UploadException.class,
                () -> publish.run(packTwo, exportTwo.packageBytes(), exportTwo::bytes, (step, done, total) -> { }));
        assertEquals(ApiError.CONFLICT, stale.error());
        assertEquals(2, stale.detail());
        assertEquals(2, fetcher.resolve(published.code()).revision().collection().getFigures().size());

        // ---- Its owner brings it back whole where its draft is not: what was published, file for file
        PlayerDownload.Download download = PlayerDownload.of(fetcher, published.code());
        assertEquals(published.id(), download.id());
        assertEquals(2, download.rev());
        assertArrayEquals(exportTwo.record(), download.record().toPlayerUtf8());
        assertEquals(exportTwo.facts().keySet(), download.files().keySet());
        for (Map.Entry<String, PlayerDownload.File> file : download.files().entrySet()) {
            assertArrayEquals(exportTwo.bytes(file.getKey()), file.getValue().bytes());
        }

        // ---- The owner's list, a rollback, a withdrawal
        Account account = manage.list();
        assertEquals(1, account.collections().size());
        assertEquals(List.of(2, 1), account.collections().get(0).revisions().stream().map(UploadApi.RevisionRow::rev).toList());
        assertEquals("Mis figuras", account.collections().get(0).name());
        assertEquals(3, manage.rollback(published.id(), 2, 1).rev());
        assertEquals(1, fetcher.resolve(published.code()).revision().collection().getFigures().size());
        manage.withdraw(published.id(), 3, 2);
        fetcher.refresh();
        assertTrue(fetcher.state().revocations().revokes(published.id(), 2));
        byte[] head = fetcher.head(published.code(), published.id()).envelope();
        assertEquals(Why.UNAVAILABLE, assertThrows(Refused.class, () -> fetcher.follow(published.code(), published.id(), 2,
                changed.index(), head)).why());
        // A head from before the rollback is a replay, whoever hands it over
        assertThrows(RemoteException.class, () -> fetcher.follow(published.code(), published.id(), 1,
                published.index(), resolved.headEnvelope()));

        // ---- Deleted: the code resolves to nothing any more, for anybody
        manage.delete(published.id(), 3);
        assertEquals(Why.UNAVAILABLE, assertThrows(Refused.class, () -> fetcher.resolve(published.code())).why());
        assertEquals("removed", manage.list().collections().get(0).state());
    }
}
