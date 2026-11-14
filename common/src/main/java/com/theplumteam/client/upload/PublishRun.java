package com.theplumteam.client.upload;

import com.theplumteam.client.upload.UploadApi.Begun;
import com.theplumteam.client.upload.UploadApi.Config;
import com.theplumteam.client.upload.UploadApi.Published;
import com.theplumteam.client.upload.UploadApi.Session;
import com.theplumteam.client.upload.UploadApi.UploadState;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.UploadIntent;
import com.theplumteam.remote.player.UploadPackage;
import com.theplumteam.worldcontent.JdkSha256;

import java.io.IOException;
import java.util.List;

/**
 * Publishes one collection: asks what the service takes, proves the account, sends the package
 * that lists the collection, sends each file the service is missing, and commits. Every step
 * can be asked again without harm, so a lost answer is asked for again and a session that ran
 * out is opened again; what already arrived is not sent twice.
 *
 * <p>It blocks until it is done, and runs on {@link UploadExecutor}.
 */
public final class PublishRun {
    /** How often a file, or the commit's answer, is asked for again when no answer came. */
    static final int ATTEMPTS = 3;
    private static final long[] WAITS_MILLIS = {1_000, 3_000};
    private static final int STATE_POLLS = 6;

    /** Where a run is, for whoever shows it. */
    public enum Step { CHECKING, VERIFYING, FILES, PUBLISHING }

    /** Told as the run moves on; with files, how many went up of how many. */
    public interface Progress {
        void at(Step step, int done, int total);
    }

    /** The bytes of the record and of each file, by their SHA-256. */
    public interface Files {
        byte[] bytes(String sha256) throws IOException;
    }

    /** Waits between two attempts. */
    public interface Pause {
        void millis(long millis) throws InterruptedException;
    }

    private final UploadApi api;
    private final SessionClient sessions;
    private final Pause pause;

    public PublishRun(UploadApi api, SessionClient sessions, Pause pause) {
        this.api = api;
        this.sessions = sessions;
        this.pause = pause;
    }

    /**
     * @param pack         what is published
     * @param packageBytes the package as it is sent, whose SHA-256 the session is given for
     * @throws UploadException when it could not be published, with the error that says why
     */
    public Published run(UploadPackage pack, byte[] packageBytes, Files files, Progress progress) throws UploadException {
        progress.at(Step.CHECKING, 0, 0);
        Config config = api.config();
        if (config.uploads().equals("paused")) {
            throw new UploadException(ApiError.PAUSED);
        }
        if (pack.terms() < config.terms()) {
            throw new UploadException(ApiError.TERMS_OUTDATED);
        }
        if (pack.rules() < config.rules() || older(pack.client(), config.minClient())) {
            throw new UploadException(ApiError.CLIENT_OUTDATED);
        }
        UploadIntent intent = pack.target() == null ? UploadIntent.publishNew(JdkSha256.of(packageBytes))
                : UploadIntent.publishChanges(pack.target(), pack.baseRev(), JdkSha256.of(packageBytes));
        UploadException last = null;
        // A session lasts a quarter of an hour; a long upload opens a second one and goes on
        for (int opened = 0; opened < 2; opened++) {
            progress.at(Step.VERIFYING, 0, 0);
            Session session = sessions.open(intent);
            try {
                return publish(session.token(), packageBytes, files, progress);
            } catch (UploadException e) {
                if (e.error() != ApiError.NOT_VERIFIED && e.error() != ApiError.EXPIRED) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    private Published publish(String token, byte[] packageBytes, Files files, Progress progress) throws UploadException {
        Begun begun = api.begin(token, packageBytes);
        List<String> missing = begun.missing();
        for (int i = 0; i < missing.size(); i++) {
            progress.at(Step.FILES, i, missing.size());
            byte[] bytes;
            try {
                bytes = files.bytes(missing.get(i));
            } catch (IOException e) {
                throw new UploadException(ApiError.INVALID_FILE);
            }
            String sha = missing.get(i);
            retrying(() -> {
                api.putFile(token, begun.upload(), sha, bytes);
                return null;
            });
        }
        progress.at(Step.PUBLISHING, missing.size(), missing.size());
        try {
            return api.commit(token, begun.upload());
        } catch (UploadException e) {
            if (e.error() != ApiError.NETWORK) {
                throw e;
            }
        }
        // No answer to the commit: it may have happened. The upload says, and committing again answers the same
        for (int poll = 0; poll < STATE_POLLS; poll++) {
            waitBefore(poll);
            try {
                UploadState state = api.state(token, begun.upload());
                if (state.result() != null) {
                    return state.result();
                }
                if (state.state().equals("failed")) {
                    throw new UploadException(ApiError.INTERNAL);
                }
                if (state.state().equals("open")) {
                    return api.commit(token, begun.upload());
                }
            } catch (UploadException e) {
                if (e.error() != ApiError.NETWORK) {
                    throw e;
                }
            }
        }
        throw new UploadException(ApiError.NETWORK);
    }

    private interface Attempt<T> {
        T run() throws UploadException;
    }

    // Asks again when no answer came; any answer, good or bad, is final
    private <T> T retrying(Attempt<T> attempt) throws UploadException {
        for (int tried = 0; ; tried++) {
            try {
                return attempt.run();
            } catch (UploadException e) {
                if (e.error() != ApiError.NETWORK || tried + 1 >= ATTEMPTS) {
                    throw e;
                }
                waitBefore(tried);
            }
        }
    }

    private void waitBefore(int tried) throws UploadException {
        try {
            pause.millis(WAITS_MILLIS[Math.min(tried, WAITS_MILLIS.length - 1)]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UploadException(ApiError.NETWORK);
        }
    }

    /** Whether a build's version is below the lowest the service takes. */
    static boolean older(String client, String minimum) {
        int[] have = numbers(client);
        int[] need = numbers(minimum);
        for (int i = 0; i < 3; i++) {
            if (have[i] != need[i]) {
                return have[i] < need[i];
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        int[] numbers = new int[3];
        String[] parts = version.split("[^0-9]+", 4);
        for (int i = 0; i < 3 && i < parts.length; i++) {
            if (!parts[i].isEmpty() && parts[i].length() <= 4) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        }
        return numbers;
    }
}
