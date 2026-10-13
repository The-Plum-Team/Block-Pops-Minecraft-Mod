package com.theplumteam.remote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * A local copy of Custom collection files, laid out like the CDN, holding only verified bytes.
 *
 * A file is kept only once its SHA-256 matches the manifest (and, for a texture, its PNG
 * header passes {@link PngCheck}); a mismatch writes nothing. Every write lands in a fresh
 * staging file and is moved into place in one step, so a crash never leaves a half-written
 * file where a verified one is expected. Paths are resolved with {@link RemotePaths#resolveInside},
 * so a link planted in the store cannot redirect a write.
 *
 * Not thread-safe: use it from one thread at a time, such as {@link RemoteExecutor}'s.
 */
public final class VerifiedStore {
    private static final String STAGING = ".staging";
    private static final int BUFFER = 8192;

    private final Path root;

    public VerifiedStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The stored file for the entry, if it is a regular file whose SHA-256 matches. */
    public Optional<Path> cached(ManifestEntry entry) throws IOException {
        Path file = RemotePaths.resolveInside(root, entry.path());
        long limit = RemoteLimits.limitFor(entry.path());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit) {
            return Optional.empty();
        }
        return sha256(file).equals(entry.sha256()) ? Optional.of(file) : Optional.empty();
    }

    /**
     * Downloads the entry, verifies it and stores it.
     *
     * @return the stored file
     * @throws RemoteException if the bytes are too large, do not match the manifest or are not a valid PNG
     */
    public Path fetch(RemoteSource source, ManifestEntry entry) throws IOException {
        long limit = RemoteLimits.limitFor(entry.path());
        if (limit <= 0) {
            throw new RemoteException("Not a downloadable file: " + entry.path());
        }
        // Refuse a path that cannot be stored before spending a download on it
        RemotePaths.resolveInside(root, entry.path());
        byte[] bytes = source.fetch(entry.path(), limit);
        if (bytes.length > limit) {
            throw new RemoteException(entry.path() + " is larger than " + limit + " bytes");
        }
        if (!sha256(bytes).equals(entry.sha256())) {
            throw new RemoteException("SHA-256 mismatch for " + entry.path());
        }
        if (entry.path().endsWith(".png")) {
            PngCheck.check(bytes);
        }
        return write(entry.path(), bytes);
    }

    /** Keeps a manifest that parsed, for when the CDN cannot be reached. */
    public void saveManifest(byte[] bytes) throws IOException {
        if (bytes.length > RemoteLimits.MANIFEST_BYTES) {
            throw new RemoteException("Manifest is larger than " + RemoteLimits.MANIFEST_BYTES + " bytes");
        }
        write(RemotePaths.MANIFEST, bytes);
    }

    /** The last manifest kept by {@link #saveManifest}, unparsed. */
    public Optional<byte[]> loadManifest() throws IOException {
        Path file = RemotePaths.resolveInside(root, RemotePaths.MANIFEST);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) RemoteLimits.MANIFEST_BYTES + 1);
            if (bytes.length > RemoteLimits.MANIFEST_BYTES) {
                throw new RemoteException("Stored manifest is larger than " + RemoteLimits.MANIFEST_BYTES + " bytes");
            }
            return Optional.of(bytes);
        }
    }

    private Path write(String relativePath, byte[] bytes) throws IOException {
        Path staging = stagingDirectory();
        Path target = RemotePaths.resolveInside(root, relativePath);
        Files.createDirectories(target.getParent());
        // Check again now that the directories exist
        target = RemotePaths.resolveInside(root, relativePath);
        Path temporary = staging.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return target;
    }

    private Path stagingDirectory() throws IOException {
        Path staging = root.resolve(STAGING);
        Files.createDirectories(staging);
        BasicFileAttributes attributes = Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new RemoteException("Refusing link or special file at " + STAGING);
        }
        return staging;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[BUFFER];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException(e);
        }
    }
}
