package com.theplumteam.remote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Checks that bytes start like a PNG whose image is no larger than the texture limit,
 * reading only the signature and the IHDR chunk, so an oversized image is refused before
 * anything tries to decode it.
 */
public final class PngCheck {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** Signature, then the IHDR length, type, 13 data bytes and CRC. */
    private static final int HEADER_LENGTH = 8 + 4 + 4 + 13 + 4;

    private PngCheck() {
    }

    public static void check(byte[] bytes) throws RemoteException {
        if (bytes.length < HEADER_LENGTH) {
            throw new RemoteException("PNG is truncated");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, HEADER_LENGTH);
        if (!Arrays.equals(bytes, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new RemoteException("Not a PNG file");
        }
        header.position(SIGNATURE.length);
        int length = header.getInt();
        byte[] type = new byte[4];
        header.get(type);
        if (length != 13 || !"IHDR".equals(new String(type, StandardCharsets.US_ASCII))) {
            throw new RemoteException("PNG does not start with IHDR");
        }
        long width = Integer.toUnsignedLong(header.getInt());
        long height = Integer.toUnsignedLong(header.getInt());
        CRC32 crc = new CRC32();
        crc.update(bytes, SIGNATURE.length + 4, 4 + 13);
        if (crc.getValue() != Integer.toUnsignedLong(ByteBuffer.wrap(bytes, HEADER_LENGTH - 4, 4).getInt())) {
            throw new RemoteException("PNG header is corrupt");
        }
        if (width == 0 || height == 0 || width > RemoteLimits.PNG_MAX_DIMENSION || height > RemoteLimits.PNG_MAX_DIMENSION) {
            throw new RemoteException("PNG is " + width + "x" + height + ", the limit is "
                    + RemoteLimits.PNG_MAX_DIMENSION + "x" + RemoteLimits.PNG_MAX_DIMENSION);
        }
    }

    public static void check(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            check(in.readNBytes(HEADER_LENGTH));
        }
    }
}
