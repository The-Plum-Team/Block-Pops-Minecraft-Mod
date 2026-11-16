package com.theplumteam.client.upload;

import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.UploadOrigin;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Talks to the upload service over HTTPS. The origin is a constant of the build, redirects are
 * never followed, every request has a deadline, and an answer is abandoned as soon as it proves
 * longer than any answer of the service is.
 *
 * <p>It is the only code that sends anything of the player's to the internet, and it sends
 * only what an explicit action of the player asked for: never from a dedicated server, never on
 * its own, and never to a URL from a server, a packet or a file.
 */
final class UploadHttp implements UploadTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Until the answer's headers arrive. */
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);
    /** For a whole exchange, and for one in which a file goes up. */
    private static final long EXCHANGE_SECONDS = 45;
    private static final long FILE_SECONDS = 120;
    /** Every path the game asks for: the service's first version, of letters, digits and a few signs. */
    private static final Pattern PATH = Pattern.compile("/v1/[a-z0-9/_-]{1,160}");
    private static UploadHttp instance;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private UploadHttp() {
    }

    static synchronized UploadHttp get() {
        if (instance == null) {
            instance = new UploadHttp();
        }
        return instance;
    }

    @Override
    public Answer send(Call call) throws UploadException {
        if (!PATH.matcher(call.path()).matches()) {
            throw new UploadException(ApiError.BAD_REQUEST);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(UploadOrigin.ORIGIN + call.path()))
                .timeout(call.slow() ? Duration.ofSeconds(FILE_SECONDS) : ANSWER_TIMEOUT)
                .header("User-Agent", UploadClientId.userAgent())
                .header("X-BlockPops-Client", UploadClientId.current());
        if (call.token() != null) {
            request.header("Authorization", "Bearer " + call.token());
        }
        if (call.body() != null) {
            request.header("Content-Type", call.contentType() != null ? call.contentType() : "application/octet-stream");
        }
        request.method(call.method(), call.body() != null ? HttpRequest.BodyPublishers.ofByteArray(call.body())
                : HttpRequest.BodyPublishers.noBody());
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request.build(), info -> new Bounded());
        try {
            HttpResponse<byte[]> answered = response.get(call.slow() ? FILE_SECONDS : EXCHANGE_SECONDS, TimeUnit.SECONDS);
            return new Answer(answered.statusCode(), answered.body());
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new UploadException(ApiError.NETWORK);
        } catch (TimeoutException | ExecutionException e) {
            response.cancel(true);
            // An answer longer than any the service gives is not the service's
            throw new UploadException(e.getCause() instanceof TooLong ? ApiError.INTERNAL : ApiError.NETWORK);
        }
    }

    private static final class TooLong extends RuntimeException {
        TooLong() {
            super(null, null, false, false);
        }
    }

    /** Collects an answer of at most {@link UploadTransport#MAX_ANSWER_BYTES}, whatever its status. */
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_ANSWER_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLong());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
