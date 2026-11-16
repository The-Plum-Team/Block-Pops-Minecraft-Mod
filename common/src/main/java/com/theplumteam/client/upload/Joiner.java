package com.theplumteam.client.upload;

import com.theplumteam.remote.player.ApiError;

/**
 * Proves to Mojang that this game holds a Minecraft account: joins the server a name stands
 * for, as the game does when it connects to any server. The access token never leaves the
 * game's own session service; nothing here sees it.
 */
public interface Joiner {
    /** The name of the account this game is signed in as. */
    String name();

    /**
     * Joins the server that id names.
     *
     * @throws UploadException when Mojang refuses or cannot be reached, with the error that says why
     */
    void join(String serverId) throws UploadException;

    /** Joins nothing: every attempt fails as if there were no network. */
    Joiner NONE = new Joiner() {
        @Override
        public String name() {
            return "";
        }

        @Override
        public void join(String serverId) throws UploadException {
            throw new UploadException(ApiError.NETWORK);
        }
    };
}
