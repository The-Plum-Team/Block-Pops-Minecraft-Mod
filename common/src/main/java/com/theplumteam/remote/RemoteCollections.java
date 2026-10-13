package com.theplumteam.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches Custom collections: the manifest, code lookups, and each collection's verified files.
 *
 * Everything here blocks and reports failure by throwing, so callers run it on
 * {@link RemoteExecutor} and decide what to log or show. A file that is already stored with
 * the expected SHA-256 is reused without touching the network.
 */
public final class RemoteCollections {
    private final RemoteSource source;
    private final VerifiedStore store;

    public RemoteCollections(RemoteSource source, VerifiedStore store) {
        this.source = source;
        this.store = store;
    }

    /**
     * The current manifest, kept for later; when the CDN cannot be reached or answers with a
     * manifest that does not parse, the last kept copy instead.
     *
     * @throws IOException the download failure, if there is no usable kept copy either,
     *                     or the failure to keep a fresh manifest, since the store is then unusable
     */
    public Manifest manifest() throws IOException {
        byte[] bytes;
        Manifest manifest;
        try {
            bytes = source.fetch(RemotePaths.MANIFEST, RemoteLimits.MANIFEST_BYTES);
            manifest = Manifest.parse(bytes);
        } catch (IOException fresh) {
            try {
                Optional<byte[]> stored = store.loadManifest();
                if (stored.isPresent()) {
                    return Manifest.parse(stored.get());
                }
            } catch (IOException storedFailure) {
                fresh.addSuppressed(storedFailure);
            }
            throw fresh;
        }
        store.saveManifest(bytes);
        return manifest;
    }

    /**
     * Looks a code up on the CDN.
     *
     * @throws RemoteNotFoundException if the code is malformed or unknown
     */
    public CodeEntry lookupCode(String rawCode) throws IOException {
        String code = RemotePaths.normalizeCode(rawCode);
        if (code == null) {
            throw new RemoteNotFoundException("Not a valid collection code");
        }
        return CodeEntry.parse(source.fetch(RemotePaths.CODES_DIRECTORY + code + ".json", RemoteLimits.CODE_BYTES));
    }

    /**
     * The verified local collection JSON of a collection the manifest lists.
     *
     * @throws RemoteNotFoundException if the manifest does not list the collection
     */
    public Path collectionJson(Manifest manifest, String id) throws IOException {
        ManifestEntry entry = manifest.collectionJson(id)
                .orElseThrow(() -> new RemoteNotFoundException("The manifest has no collection " + RemoteException.excerpt(id)));
        return fetchVerified(entry);
    }

    /**
     * Every allowlisted asset of the collection, verified and stored.
     * The first file that fails stops the call, so a collection is never half-trusted.
     *
     * @return the local file for each CDN path, in manifest order
     * @throws RemoteException if the collection has too many files or they add up to more than
     *                         {@link RemoteLimits#MAX_BYTES_PER_COLLECTION}
     */
    public Map<String, Path> assets(Manifest manifest, String id) throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        long total = 0;
        for (ManifestEntry entry : manifest.filesFor(id)) {
            Path file = fetchVerified(entry);
            total += Files.size(file);
            if (total > RemoteLimits.MAX_BYTES_PER_COLLECTION) {
                throw new RemoteException("Collection " + id + " is larger than "
                        + RemoteLimits.MAX_BYTES_PER_COLLECTION + " bytes");
            }
            files.put(entry.path(), file);
        }
        return files;
    }

    private Path fetchVerified(ManifestEntry entry) throws IOException {
        Optional<Path> cached = store.cached(entry);
        return cached.isPresent() ? cached.get() : store.fetch(source, entry);
    }
}
