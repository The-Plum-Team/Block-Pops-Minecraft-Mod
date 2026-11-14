package com.theplumteam.client.upload;

import com.theplumteam.client.upload.UploadApi.Account;
import com.theplumteam.client.upload.UploadApi.LinkCode;
import com.theplumteam.client.upload.UploadApi.Rolled;
import com.theplumteam.remote.player.UploadIntent;

/**
 * What an owner does with what they published, each as one session for exactly that action:
 * see it, go back to an earlier revision, take one revision back, delete it, and start tying
 * a purchase to the account. Each blocks until it is done, and runs on {@link UploadExecutor}.
 */
public final class ManageRun {
    private final UploadApi api;
    private final SessionClient sessions;

    public ManageRun(UploadApi api, SessionClient sessions) {
        this.api = api;
        this.sessions = sessions;
    }

    /** What this account has published, and whether it supports the project. */
    public Account list() throws UploadException {
        return api.me(sessions.open(UploadIntent.list()).token());
    }

    /**
     * Publishes an earlier revision's content again as a new revision.
     *
     * @param headRev the revision the owner last saw as the newest
     */
    public Rolled rollback(String id, int headRev, int toRev) throws UploadException {
        return api.rollback(sessions.open(UploadIntent.rollback(id, headRev, toRev)).token(), id, toRev);
    }

    /** Takes one earlier revision back, so worlds still on it stop showing it. */
    public void withdraw(String id, int headRev, int rev) throws UploadException {
        api.withdraw(sessions.open(UploadIntent.withdraw(id, headRev, rev)).token(), id, rev);
    }

    /** Deletes the collection from the service for good. */
    public void delete(String id, int headRev) throws UploadException {
        api.delete(sessions.open(UploadIntent.delete(id, headRev)).token(), id);
    }

    /** A code that ties a purchase made under this account's name to the account. */
    public LinkCode link() throws UploadException {
        return api.startLink(sessions.open(UploadIntent.link()).token());
    }
}
