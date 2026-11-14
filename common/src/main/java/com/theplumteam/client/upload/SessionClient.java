package com.theplumteam.client.upload;

import com.theplumteam.client.upload.UploadApi.Challenge;
import com.theplumteam.client.upload.UploadApi.Session;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.UploadIntent;
import com.theplumteam.worldcontent.JdkSha256;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Gets a session for one action: asks the service for a number, joins the server that number
 * and the action name together through Mojang, and trades the number for the session. Mojang
 * tells the service which account joined, so the service believes no name the game sends, and
 * the session it gives is good for that one action alone.
 */
public final class SessionClient {
    private final UploadApi api;
    private final Joiner joiner;

    public SessionClient(UploadApi api, Joiner joiner) {
        this.api = api;
        this.joiner = joiner;
    }

    /**
     * @throws UploadException when the account cannot be verified or may not do this, with the error that says why
     */
    public Session open(UploadIntent intent) throws UploadException {
        String name = joiner.name();
        Challenge challenge = api.challenge(name, intent);
        joiner.join(sha1Hex(UploadIntent.joinText(challenge.nonce(), intent.digest(JdkSha256.INSTANCE))));
        Session session = api.session(challenge.nonce());
        // The service answers for the account Mojang named; another name is not this game's session
        if (!session.name().equalsIgnoreCase(name)) {
            throw new UploadException(ApiError.NOT_VERIFIED);
        }
        return session;
    }

    // Mojang's join names a server by a SHA-1; it is a name here, not a protection
    static String sha1Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }
}
