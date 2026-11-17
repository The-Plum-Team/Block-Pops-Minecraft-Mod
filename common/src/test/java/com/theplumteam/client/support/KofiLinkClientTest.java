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
        assertEquals("22babf36132c19f6c1df204d995d517b5db5b7b2",
                KofiLinkClient.serverId(NONCE, "steve@example.com", "Steve"));
    }

    @Test
    void emailsAreKeptAsTheServiceKeysThem() {
        assertEquals("steve@example.com", KofiLinkClient.normalizeEmail("  Steve@Example.COM "));
        for (String bad : new String[]{"", "steve", "steve@example", "a b@example.com", "x@exa\nmple.com", "<a>@b.c"}) {
            assertNull(KofiLinkClient.normalizeEmail(bad), bad);
        }
        assertNull(KofiLinkClient.normalizeEmail(null));
        assertNull(KofiLinkClient.normalizeEmail("a".repeat(250) + "@example.com"));
    }

    @Test
    void aJoinedAccountSendsItsNonce() throws LinkException {
        Service service = new Service().answer(200,
                "{\"linked\":true,\"verified\":true,\"name\":\"Steve\",\"supporter\":true}");
        Session session = new Session("Steve", false);
        Linked linked = client(service, session).link("steve@example.com");
        assertEquals(new Linked("Steve", true, true), linked);
        assertEquals(List.of("22babf36132c19f6c1df204d995d517b5db5b7b2"), session.joined);
        JsonObject body = service.bodies.get(0);
        assertEquals("/api/minecraft/link", service.paths.get(0));
        assertEquals(NONCE, body.get("nonce").getAsString());
        assertEquals("steve@example.com", body.get("email").getAsString());
        assertEquals("Steve", body.get("name").getAsString());
        assertEquals(3, body.size());
    }

    @Test
    void anAccountMojangRefusesStillLinksAsAClaimWithoutANonce() throws LinkException {
        Service service = new Service().answer(200, "{\"linked\":true,\"verified\":false,\"name\":\"Steve\",\"supporter\":false}");
        Linked linked = client(service, new Session("Steve", true)).link("steve@example.com");
        assertEquals(new Linked("Steve", false, false), linked);
        assertFalse(service.bodies.get(0).has("nonce"));
    }

    @Test
    void theServicesRefusalsBecomeFailures() {
        String[][] cases = {
                {"403", "no_payment", "NO_PAYMENT"},
                {"409", "name_taken", "NAME_TAKEN"},
                {"409", "already_linked", "ALREADY_LINKED"},
                {"400", "bad_request", "BAD_REQUEST"},
                {"400", "something new", "BAD_REQUEST"},
                {"500", "something new", "NETWORK"},
        };
        for (String[] c : cases) {
            Service service = new Service().answer(Integer.parseInt(c[0]), "{\"error\":\"" + c[1] + "\"}");
            LinkException e = assertThrows(LinkException.class,
                    () -> client(service, new Session("Steve", false)).link("steve@example.com"));
            assertEquals(Failure.valueOf(c[2]), e.failure(), c[1]);
        }
        Service html = new Service().answer(200, "<html>");
        assertEquals(Failure.NETWORK, assertThrows(LinkException.class,
                () -> client(html, new Session("Steve", false)).link("steve@example.com")).failure());
        Service down = new Service();
        down.down = true;
        assertEquals(Failure.NETWORK, assertThrows(LinkException.class,
                () -> client(down, new Session("Steve", false)).link("steve@example.com")).failure());
    }

    @Test
    void anAccountWithoutAPlayerNameAsksNothing() {
        Service service = new Service();
        assertEquals(Failure.BAD_REQUEST, assertThrows(LinkException.class,
                () -> client(service, new Session("", false)).link("steve@example.com")).failure());
        assertTrue(service.paths.isEmpty());
    }
}
