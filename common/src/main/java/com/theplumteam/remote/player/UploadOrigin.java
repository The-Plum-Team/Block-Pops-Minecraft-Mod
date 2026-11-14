package com.theplumteam.remote.player;

/**
 * Where players publish their collections: the upload service. It is a constant of the build
 * and the only origin the game ever writes to; no server, packet or file can name another.
 */
public final class UploadOrigin {
    public static final String ORIGIN = PlayerBuildFlags.UPLOAD_ORIGIN;

    private UploadOrigin() {
    }
}
