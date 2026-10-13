package com.theplumteam.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads Custom collection files from the BlockPops CDN.
 *
 * The origin is a constant: no server, packet or file can point downloads anywhere else,
 * and redirects are never followed. Only status 200 counts, and a body is abandoned as soon
 * as it proves larger than the caller's cap.
 */
public final class CdnSource implements RemoteSource {
    public static final String ORIGIN = "https://f003.backblazeb2.com/file/blockpops-assets";
    private static final String USER_AGENT = "BlockPops";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Until the response headers arrive. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** For the whole transfer, so a server that trickles bytes cannot hold the download forever. */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public byte[] fetch(String relativePath, long maxBytes) throws IOException {
        if (!RemotePaths.isSafeRelative(relativePath)) {
            throw new RemoteException("Refusing to download " + RemoteException.excerpt(relativePath));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(ORIGIN + "/" + relativePath))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> response =
                client.sendAsync(request, info -> new BoundedBody(relativePath, info, maxBytes));
        try {
            return response.get(TRANSFER_TIMEOUT.toSeconds(), TimeUnit.SECONDS).body();
        } catch (TimeoutException e) {
            response.cancel(true);
            throw new RemoteException("Timed out downloading " + relativePath);
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted downloading " + relativePath);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteException remote) {
                throw remote;
            }
            if (cause instanceof HttpTimeoutException) {
                throw new RemoteException("Timed out downloading " + relativePath, cause);
            }
            throw new RemoteException("Could not download " + relativePath + ": "
                    + (cause == null ? "unknown error" : cause.getClass().getSimpleName()), cause);
        }
    }

    /** Collects a 200 response of at most maxBytes, and fails any other response without reading it. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long maxBytes;
        private final RemoteException refusal;
        private Flow.Subscription subscription;

        BoundedBody(String path, HttpResponse.ResponseInfo info, long maxBytes) {
            this.maxBytes = maxBytes;
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (info.statusCode() == 404) {
                refusal = new RemoteNotFoundException("Not found on the CDN: " + path);
            } else if (info.statusCode() != 200) {
                refusal = new RemoteException("CDN answered HTTP " + info.statusCode() + " for " + path);
            } else if (declared > maxBytes) {
                refusal = new RemoteException(path + " is larger than " + maxBytes + " bytes");
            } else {
                refusal = null;
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (refusal != null) {
                fail(refusal);
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + (long) buffer.remaining() > maxBytes) {
                    fail(new RemoteException("Download is larger than " + maxBytes + " bytes"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }

        private void fail(RemoteException error) {
            subscription.cancel();
            result.completeExceptionally(error);
        }
    }
}
