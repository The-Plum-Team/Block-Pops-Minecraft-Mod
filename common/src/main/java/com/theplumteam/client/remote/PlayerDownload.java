package com.theplumteam.client.remote;

import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a published collection back whole, for its owner to go on editing it on a computer that
 * does not have its draft: the revision its code points at now and every file that revision
 * names.
 *
 * <p>It is believed exactly as a collection a server shows is. The head, the index and the
 * record are verified by {@link PlayerFetcher}, each file is fetched at its computed path as
 * the bytes its index names, and each gets the whole check of a published file here before it
 * is handed on. Nothing is registered for the game.
 */
public final class PlayerDownload {
    /**
     * A published collection, read back.
     *
     * @param record the record of that revision, under the collection's published id
     * @param files  every file the record names, by its SHA-256
     */
    public record Download(String id, String code, int rev, WorldCollectionRecord record, Map<String, File> files) {
    }

    /** One file of it: what it is and its bytes. */
    public record File(BlobType type, byte[] bytes) {
    }

    private PlayerDownload() {
    }

    /**
     * @throws PlayerFetcher.Refused when the code is unknown, or the collection is not to be loaded now
     * @throws IOException           when the origin cannot be reached or something of it is not to be believed
     */
    public static Download of(PlayerFetcher fetcher, String code) throws IOException, PlayerFetcher.Refused {
        PlayerFetcher.Resolved resolved = fetcher.resolve(code);
        PlayerFetcher.Revision revision = resolved.revision();
        Map<String, BlobInfo> facts = revision.index().blobs();
        Map<String, File> files = new LinkedHashMap<>();
        for (String sha : revision.record().referencedShas()) {
            BlobInfo fact = facts.get(sha);
            if (fact == null) {
                throw new RemoteException("The record of " + code + " names a file its index does not list");
            }
            byte[] bytes = fetcher.file(revision.id(), sha, fact);
            try {
                if (!fact.type().validatePlayer(bytes).sameFile(fact)) {
                    throw new RemoteException("A file of " + code + " is not what its index says it is");
                }
            } catch (WorldContentException e) {
                throw new RemoteException("A file of " + code + " is not one a player may publish: " + e.getMessage());
            }
            files.put(sha, new File(fact.type(), bytes));
        }
        return new Download(revision.id(), code, revision.rev(), revision.record(), Map.copyOf(files));
    }
}
