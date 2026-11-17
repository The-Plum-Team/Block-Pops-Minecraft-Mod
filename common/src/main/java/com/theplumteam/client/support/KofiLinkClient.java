package com.theplumteam.client.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.theplumteam.client.upload.Joiner;
import com.theplumteam.client.upload.UploadException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Links the Ko-fi email a player paid with to their Minecraft account, from the game: it talks
 * straight to the supporter service, never through the Minecraft server, so no server owner sees
 * the email, and only to the service's fixed origin, so no server can send it elsewhere.
 *
 * <p>{@link #link} joins, through Mojang, the "server" that names the email and the name, as the
 * game joins any server, and the service asks Mojang which account did: the link is then
 * verified, tied to the account rather than only to its name. An account Mojang cannot vouch for
 * (an offline one) still links, as a claim on its name.
 *
 * <p>Every call blocks: run it off the client thread.
 */
public final class KofiLinkClient {
    /** The supporter service, the one servers ask whether a player is a supporter. */
    public static final String ORIGIN = "https://kofi-linker.rubengalandiaz.workers.dev";
    public static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_ANSWER_BYTES = 4096;
    // The same shape the service takes: one @, a dot after it, no spaces or address syntax
    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>()\",;:\\\\\\[\\]]+@[^\\s@<>()\",;:\\\\\\[\\]]+\\.[^\\s@<>()\",;:\\\\\\[\\]]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why a call failed, which the dialog says in the player's language. */
    public enum Failure {
        /** The service refused the email or the name as written. */
        BAD_REQUEST,
        /** The service could not be reached, or answered something unreadable. */
        NETWORK,
        /** Ko-fi has no payment from the email. */
        NO_PAYMENT,
        /** Another email already has this name. */
        NAME_TAKEN,
        /** The email already has another account. */
        ALREADY_LINKED
    }

    public static final class LinkException extends Exception {
        private final Failure failure;

        public LinkException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** What the service answered: its status and its body. */
    public record Answer(int status, String body) {
    }

    /** Posts JSON to a path of the service. */
    public interface Transport {
        Answer post(String path, String json) throws IOException;
    }

    /** The account the email is now linked to, and whether its membership is running. */
    public record Linked(String name, boolean verified, boolean supporter) {
    }

    private final Transport transport;
    private final Joiner joiner;
    private final Supplier<String> nonces;

    public KofiLinkClient(Transport transport, Joiner joiner, Supplier<String> nonces) {
        this.transport = transport;
        this.joiner = joiner;
        this.nonces = nonces;
    }

    /** The game's: the real service, the game's own Mojang session and random nonces. */
    public static KofiLinkClient forGame(Joiner joiner) {
        return new KofiLinkClient(KofiLinkClient::httpPost, joiner, KofiLinkClient::randomNonce);
    }

    /** The email as the service keys it (trimmed, lower case), or null when it is not one. */
    @Nullable
    public static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() < 3 || email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            return null;
        }
        for (int i = 0; i < email.length(); i++) {
            if (email.charAt(i) < 0x20 || email.charAt(i) == 0x7f) {
                return null;
            }
        }
        return email;
    }

    /**
     * Links the email (as {@link #normalizeEmail} gives it) to this game's account. A join Mojang
     * refuses or cannot take still links, as a claim on the name.
     */
    public Linked link(String email) throws LinkException {
        String name = joiner.name();
        if (!NAME.matcher(name).matches()) {
            throw new LinkException(Failure.BAD_REQUEST);
        }
        String nonce = nonces.get();
        boolean joined;
        try {
            joiner.join(serverId(nonce, email, name));
            joined = true;
        } catch (UploadException e) {
            joined = false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("name", name);
        if (joined) {
            body.addProperty("nonce", nonce);
        }
        Answer answer = post("/api/minecraft/link", body);
        JsonObject json = object(answer.body());
        if (answer.status() == 200 && json != null && json.has("linked")
                && json.get("linked").isJsonPrimitive() && json.get("linked").getAsBoolean()) {
            String linkedName = text(json, "name");
            return new Linked(linkedName != null && NAME.matcher(linkedName).matches() ? linkedName : name,
                    flag(json, "verified"), flag(json, "supporter"));
        }
        throw new LinkException(failure(answer.status(), json));
    }

    /**
     * The server id the game joins: the SHA-1, in hex, of the purpose, the nonce, the email and
     * the lower-case name, one per line. The service computes the same.
     */
    public static String serverId(String nonce, String email, String name) {
        String text = String.join("\n", "blockpops-kofi-link-v1", nonce, email, name.toLowerCase(Locale.ROOT));
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is missing", e);
        }
    }

    private Answer post(String path, JsonObject body) throws LinkException {
        try {
            return transport.post(path, body.toString());
        } catch (IOException | RuntimeException e) {
            throw new LinkException(Failure.NETWORK);
        }
    }

    private static Failure failure(int status, @Nullable JsonObject json) {
        String error = json == null ? null : text(json, "error");
        if (error != null) {
            switch (error) {
                case "bad_request":
                    return Failure.BAD_REQUEST;
                case "no_payment":
                    return Failure.NO_PAYMENT;
                case "name_taken":
                    return Failure.NAME_TAKEN;
                case "already_linked":
                    return Failure.ALREADY_LINKED;
                default:
                    break;
            }
        }
        return status == 400 ? Failure.BAD_REQUEST : Failure.NETWORK;
    }

    @Nullable
    private static JsonObject object(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static String text(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    private static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Answer httpPost(String path, String json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(ORIGIN + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "BlockPops");
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return new Answer(status, "");
            }
            try (stream) {
                return new Answer(status, new String(stream.readNBytes(MAX_ANSWER_BYTES), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
