package com.theplumteam.client.upload;

import com.theplumteam.remote.player.ApiError;
import com.theplumteam.util.E2EDeterminism;
import org.jetbrains.annotations.Nullable;

/**
 * How a request reaches the upload service. The game has one that talks to it and one that
 * never does; a test has its own.
 */
public interface UploadTransport {
    /** The most bytes any answer of the service has. */
    int MAX_ANSWER_BYTES = 64 * 1024;

    /**
     * One request.
     *
     * @param path        the path below the service's origin, beginning with {@code /v1/}
     * @param token       the session's token, or null for a request that needs none
     * @param body        the bytes to send, or null for none
     * @param contentType what the body is, or null with no body
     * @param slow        whether this is a file going up, which gets longer to finish
     */
    record Call(String method, String path, @Nullable String token, @Nullable byte[] body, @Nullable String contentType,
                boolean slow) {
    }

    /** What came back: a status and at most {@link #MAX_ANSWER_BYTES} bytes. */
    record Answer(int status, byte[] body) {
    }

    /**
     * Sends a request and reads the answer whole.
     *
     * @throws UploadException with {@link ApiError#NETWORK} when no answer came
     */
    Answer send(Call call) throws UploadException;

    /** Touches no socket: every request fails as if there were no network. */
    UploadTransport NONE = call -> {
        throw new UploadException(ApiError.NETWORK);
    };

    /** The game's transport: none at all in the packaged E2E, which never contacts the service. */
    static UploadTransport forGame() {
        return E2EDeterminism.ENABLED ? NONE : UploadHttp.get();
    }
}
