package com.theplumteam.remote;

import com.theplumteam.util.E2EDeterminism;

import java.io.IOException;

/** Where Custom collection files come from; {@link CdnSource} in the game. */
public interface RemoteSource {
    /**
     * Has no files at all. The packaged E2E reads from it, so it never reaches the CDN: its
     * Custom collection comes from the verified cache the E2E runtime fills before the game starts.
     */
    RemoteSource NONE = (relativePath, maxBytes) -> {
        throw new RemoteNotFoundException("The packaged E2E never reaches the CDN");
    };

    /** The source the game reads from: the CDN, or {@link #NONE} in the packaged E2E. */
    static RemoteSource forGame() {
        return E2EDeterminism.ENABLED ? NONE : new CdnSource();
    }

    /**
     * Reads one file completely.
     *
     * @param relativePath a path the caller has already validated; implementations still
     *                     refuse anything that is not {@link RemotePaths#isSafeRelative}
     * @param maxBytes     the read fails as soon as the file proves larger
     * @throws RemoteNotFoundException if the file does not exist
     */
    byte[] fetch(String relativePath, long maxBytes) throws IOException;
}
