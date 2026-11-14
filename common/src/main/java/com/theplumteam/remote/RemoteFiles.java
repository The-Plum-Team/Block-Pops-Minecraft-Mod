package com.theplumteam.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Where an acceptable relative path lands on disk; {@link RemotePaths} decides which are acceptable. */
public final class RemoteFiles {
    private RemoteFiles() {
    }

    /**
     * Resolves a relative path below root.
     * Fails if the result would leave root or if any existing component below root is a
     * symbolic link, junction or other special file, so a planted link cannot redirect a write.
     */
    public static Path resolveInside(Path root, String relativePath) throws IOException {
        if (!RemotePaths.isSafeRelative(relativePath)) {
            throw new RemoteException("Unsafe path " + RemoteException.excerpt(relativePath));
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new RemoteException("Path leaves the store: " + RemoteException.excerpt(relativePath));
        }
        Path current = base;
        for (Path part : base.relativize(resolved)) {
            current = current.resolve(part);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                break;
            }
            boolean last = current.equals(resolved);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !(last ? attributes.isRegularFile() : attributes.isDirectory())) {
                throw new RemoteException("Refusing link or special file at " + RemoteException.excerpt(relativePath));
            }
        }
        return resolved;
    }
}
