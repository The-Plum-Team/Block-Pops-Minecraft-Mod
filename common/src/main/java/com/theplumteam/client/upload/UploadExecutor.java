package com.theplumteam.client.upload;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The one background thread every exchange with the upload service runs on, so two of them
 * never overlap. It is created on first use and is a daemon, so it never keeps the game from
 * exiting.
 */
public final class UploadExecutor {
    private UploadExecutor() {
    }

    public static Executor get() {
        return Holder.EXECUTOR;
    }

    private static final class Holder {
        static final Executor EXECUTOR = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "BlockPops Uploads");
            thread.setDaemon(true);
            return thread;
        });
    }
}
