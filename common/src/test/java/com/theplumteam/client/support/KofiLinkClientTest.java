package com.theplumteam.client.support;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.theplumteam.client.support.KofiLinkClient.Answer;
import com.theplumteam.client.support.KofiLinkClient.Failure;
import com.theplumteam.client.support.KofiLinkClient.LinkException;
import com.theplumteam.client.support.KofiLinkClient.Linked;
import com.theplumteam.client.upload.Joiner;
import com.theplumteam.client.upload.UploadException;
import com.theplumteam.remote.player.ApiError;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KofiLinkClientTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    /** Answers each post with the next answer and keeps what was posted. */
    private static final class Service implements KofiLinkClient.Transport {
        final List<String> paths = new ArrayList<>();
        final List<JsonObject> bodies = new ArrayList<>();
        final List<Answer> answers = new ArrayList<>();
        boolean down;

        Service answer(int status, String body) {
            answers.add(new Answer(status, body));
            return this;
        }

        @Override
        public Answer post(String path, String json) throws IOException {
            if (down) {
                throw new IOException("down");
            }
            paths.add(path);
            bodies.add(JsonParser.parseString(json).getAsJsonObject());
            return answers.remove(0);
        }
    }

    /** A Mojang session that joins, or refuses, and keeps the server ids it joined. */
    private static final class Session implements Joiner {
        final List<String> joined = new ArrayList<>();
        final String name;
        final boolean refuses;

        Session(String name, boolean refuses) {
            this.name = name;
            this.refuses = refuses;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void join(String serverId) throws UploadException {
            if (refuses) {
                throw new UploadException(ApiError.SESSION_INVALID);
            }
            joined.add(serverId);
        }
    }

    private static KofiLinkClient client(Service service, Session session) {
        return new KofiLinkClient(service, session, () -> NONCE);
    }

    @Test
    void theServerIdIsTheOneTheServiceComputes() {
        // The same vector as the supporter service's links.test.ts
        assertEquals("45e98c97f0eb7a7cdccf72a6f5863a98fb6e5b44",
                KofiLinkClient.serverId(NONCE, "steve@example.com", "123456", "Steve"));
    }

    @Test
    void emailsAreKeptAsTheServiceKeysThem() {
        assertEquals("steve@example.com", KofiLinkClient.normalizeEmail("  Steve@Example.COM "));
        for (String bad : new String[]{"", "steve", "steve@example", "a b@example.com", "x@exa\nmple.com", "<a>@b.c"}) {
            assertNull(KofiLinkClient.normalizeEmail(bad), bad);
        }
        assertNull(KofiLinkClient.normalizeEmail(null));
        assertNull(KofiLinkClient.normalizeEmail("a".repeat(250) + "@example.com"));
        assertTrue(KofiLinkClient.isCode("042137"));
        assertFalse(KofiLinkClient.isCode("42137"));
        assertFalse(KofiLinkClient.isCode("04213a"));
    }

    @Test
    void aCodeIsAskedForTheEmailAndThePlayersName() throws LinkException {
        Service service = new Service().answer(200, "{\"code\":\"sent\"}").answer(200, "{\"code\":\"not_needed\"}");
        KofiLinkClient client = client(service, new Session("Steve", false));
        assertTrue(client.requestCode("steve@example.com", "es_es"));
        assertEquals("/api/link/code", service.paths.get(0));
        assertEquals("steve@example.com", service.bodies.get(0).get("email").getAsString());
        assertEquals("Steve", service.bodies.get(0).get("minecraft").getAsString());
        assertEquals("es_es", service.bodies.get(0).get("lang").getAsString());
        // A service that sends no codes says so, and the link goes ahead without one
        assertFalse(client.requestCode("steve@example.com", "es_es"));
    }

    @Test
    void aJoinedAccountSendsItsNonceAndTheCode() throws LinkException {
        Service service = new Service().answer(200,
                "{\"linked\":true,\"verified\":true,\"name\":\"Steve\",\"supporter\":true}");
        Session session = new Session("Steve", false);
        Linked linked = client(service, session).link("steve@example.com", "123456");
        assertEquals(new Linked("Steve", true, true), linked);
        assertEquals(List.of("45e98c97f0eb7a7cdccf72a6f5863a98fb6e5b44"), session.joined);
        JsonObject body = service.bodies.get(0);
        assertEquals("/api/minecraft/link", service.paths.get(0));
        assertEquals(NONCE, body.get("nonce").getAsString());
        assertEquals("123456", body.get("code").getAsString());
        assertEquals("Steve", body.get("name").getAsString());
    }

    @Test
    void anAccountMojangRefusesStillLinksAsAClaimWithoutANonce() throws LinkException {
        Service service = new Service().answer(200, "{\"linked\":true,\"verified\":false,\"name\":\"Steve\",\"supporter\":false}");
        Linked linked = client(service, new Session("Steve", true)).link("steve@example.com", "");
        assertEquals(new Linked("Steve", false, false), linked);
        JsonObject body = service.bodies.get(0);
        assertFalse(body.has("nonce"));
        assertFalse(body.has("code"));
    }

    @Test
    void theServicesRefusalsBecomeFailures() {
        String[][] cases = {
                {"403", "code_wrong", "CODE_WRONG"},
                {"403", "code_expired", "CODE_EXPIRED"},
                {"403", "code_mismatch", "CODE_MISMATCH"},
                {"403", "no_payment", "NO_PAYMENT"},
                {"409", "name_taken", "NAME_TAKEN"},
                {"409", "already_linked", "ALREADY_LINKED"},
                {"403", "not_verified", "NOT_VERIFIED"},
                {"503", "mojang_unavailable", "MOJANG_UNAVAILABLE"},
                {"503", "email_failed", "EMAIL_FAILED"},
                {"400", "bad_request", "BAD_REQUEST"},
                {"400", "something new", "BAD_REQUEST"},
                {"500", "something new", "NETWORK"},
        };
        for (String[] c : cases) {
            Service service = new Service().answer(Integer.parseInt(c[0]), "{\"error\":\"" + c[1] + "\"}");
            LinkException e = assertThrows(LinkException.class,
                    () -> client(service, new Session("Steve", false)).link("steve@example.com", "123456"));
            assertEquals(Failure.valueOf(c[2]), e.failure(), c[1]);
        }
        Service html = new Service().answer(200, "<html>");
        assertEquals(Failure.NETWORK, assertThrows(LinkException.class,
                () -> client(html, new Session("Steve", false)).requestCode("steve@example.com", "en_us")).failure());
        Service down = new Service();
        down.down = true;
        assertEquals(Failure.NETWORK, assertThrows(LinkException.class,
                () -> client(down, new Session("Steve", false)).link("steve@example.com", "123456")).failure());
    }

    @Test
    void anAccountWithoutAPlayerNameAsksNothing() {
        Service service = new Service();
        assertEquals(Failure.BAD_REQUEST, assertThrows(LinkException.class,
                () -> client(service, new Session("", false)).requestCode("steve@example.com", "en_us")).failure());
        assertTrue(service.paths.isEmpty());
    }
}
