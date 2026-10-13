package com.theplumteam.remote;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The one background thread Custom collection work runs on.
 * It is created on first use and is a daemon, so it never keeps the game from exiting.
 */
public final class RemoteExecutor {
    private RemoteExecutor() {
    }

    public static Executor get() {
        return Holder.EXECUTOR;
    }

    private static final class Holder {
        static final Executor EXECUTOR = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "BlockPops Custom Collections");
            thread.setDaemon(true);
            return thread;
        });
    }
}
