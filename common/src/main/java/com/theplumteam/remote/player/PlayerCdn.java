package com.theplumteam.remote.player;

import com.theplumteam.remote.CdnSource;
import com.theplumteam.remote.RemoteSource;
import com.theplumteam.util.E2EDeterminism;

/**
 * Reads what players publish, from the one origin compiled into the build. Every path asked
 * of it is computed by {@link PlayerPaths}; what comes back is believed only once its signature
 * or its SHA-256 has held, so the origin is a place to fetch from, never a thing to trust.
 */
public final class PlayerCdn extends CdnSource {
    public static final String ORIGIN = PlayerBuildFlags.READ_ORIGIN;

    private PlayerCdn() {
        super(ORIGIN);
    }

    /** The source the game reads player content from: the origin, or nothing at all in the packaged E2E. */
    public static RemoteSource forGame() {
        return E2EDeterminism.ENABLED ? RemoteSource.NONE : new PlayerCdn();
    }
}
