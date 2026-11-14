package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Where the creator reads the collections it works on. Usually they are the World collections
 * the server synced ({@link ClientWorldCollections}); while a {@link PersonalSession} is open
 * they are the player's own drafts. The creator asks here and nowhere else, so its screens are
 * the same for both, and everything outside the creator keeps reading the server's.
 */
public final class CreatorSource {
    private CreatorSource() {
    }

    /** Whether the creator is working on the player's own drafts, not on a server's World collections. */
    public static boolean isPersonal() {
        return PersonalSession.isActive();
    }

    /** Whether there is anything to read: a server that synced its World collections, or open drafts. */
    public static boolean isReady() {
        return PersonalSession.isActive() || ClientWorldCollections.isSynced();
    }

    /** Changes every time what the creator reads changes, so a screen knows to rebuild. */
    public static int revision() {
        PersonalSession session = PersonalSession.active();
        // Told apart from the server's numbers, so switching between the two always reads as a change
        return session != null ? -1 - session.revision() : ClientWorldCollections.revision();
    }

    public static List<WorldCollectionRecord> records() {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.records() : ClientWorldCollections.records();
    }

    @Nullable
    public static WorldCollectionRecord record(String collectionId) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.record(collectionId) : ClientWorldCollections.record(collectionId);
    }

    /** The file table entry of a file a collection names, or null. */
    @Nullable
    public static BlobInfo blob(String sha) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.blob(sha) : ClientWorldCollections.blob(sha);
    }

    public static Map<String, BlobInfo> blobs() {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.blobs() : ClientWorldCollections.blobs();
    }

    /** Whether a file a collection names is lost where the collections are kept, and has to be given again. */
    public static boolean isMissing(String sha) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.isMissing(sha) : ClientWorldCollections.isMissingOnServer(sha);
    }

    public static boolean isLoaded(String sha) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.isLoaded(sha) : ClientWorldCollections.isLoaded(sha);
    }

    public static boolean failedToLoad(String sha) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.failedToLoad(sha) : ClientWorldCollections.failedToLoad(sha);
    }

    /** Stored kibibytes of the collections' files. */
    public static int quotaUsedKiB() {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.usedKiB() : ClientWorldCollections.quotaUsedKiB();
    }

    /** The most kibibytes that may be stored. */
    public static int quotaMaxKiB() {
        return PersonalSession.isActive() ? (int) (WorldContentLimits.MAX_STORED_BYTES / 1024)
                : ClientWorldCollections.quotaMaxKiB();
    }

    /** The bytes of a stored file of the player's own drafts, for publishing; null when not at hand. */
    @Nullable
    public static byte[] personalBytes(String sha) {
        PersonalSession session = PersonalSession.active();
        return session != null ? session.bytes(sha) : null;
    }
}
