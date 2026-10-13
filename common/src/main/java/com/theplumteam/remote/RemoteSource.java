package com.theplumteam.remote;

import java.io.IOException;

/** Where Custom collection files come from; {@link CdnSource} in the game. */
public interface RemoteSource {
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
