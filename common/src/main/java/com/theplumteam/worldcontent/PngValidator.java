package com.theplumteam.worldcontent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Validates PNG files in pure Java, so the dedicated server, which has no image library,
 * checks exactly what clients will decode.
 *
 * Every chunk's bounds and CRC are verified, the colour type and bit depth must be a
 * legal non-16-bit pair, and the image data must inflate to exactly the size the header
 * implies, with no preset dictionary and nothing after it. An indexed image's rows are
 * unfiltered and every pixel must name an entry of its palette, since decoders read
 * whatever lies past the palette's end.
 *
 * Only the canonical form is ever stored or loaded: IHDR, a PLTE for indexed images,
 * tRNS, one IDAT holding the original zlib stream, and IEND. {@link #canonicalize}
 * produces it on the sending side, so no ancillary chunk, metadata or trailing data
 * leaves the client; the deflate stream itself passes through unchanged.
 */
public final class PngValidator {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** A chunk's length, type and CRC around its data. */
    private static final int CHUNK_OVERHEAD = 12;
    private static final String DEPTH_RULE = "save it with 8 bits per channel (or an indexed palette)";

    /** What a PNG is used for, and the sizes that use allows. */
    public enum Role {
        SKIN("Skin", WorldContentLimits.SKIN_SIZE, WorldContentLimits.SKIN_SIZE, WorldContentLimits.SKIN_BYTES),
        BOX("Box texture", WorldContentLimits.BOX_SIZE, WorldContentLimits.BOX_SIZE, WorldContentLimits.BOX_BYTES),
        LOGO("Logo", WorldContentLimits.LOGO_MIN_SIZE, WorldContentLimits.LOGO_MAX_SIZE, WorldContentLimits.LOGO_BYTES),
        MODEL_TEXTURE("Model texture", WorldContentLimits.MODEL_TEXTURE_MIN_SIZE,
                WorldContentLimits.MODEL_TEXTURE_MAX_SIZE, WorldContentLimits.MODEL_TEXTURE_BYTES);

        private final String label;
        private final int minSide;
        private final int maxSide;
        private final int maxBytes;

        Role(String label, int minSide, int maxSide, int maxBytes) {
            this.label = label;
            this.minSide = minSide;
            this.maxSide = maxSide;
            this.maxBytes = maxBytes;
        }

        public int maxBytes() {
            return maxBytes;
        }

        /** Throws unless the described file is a PNG this role accepts. */
        public void check(BlobInfo info) throws WorldContentException {
            if (info.type() != BlobType.PNG) {
                throw new WorldContentException(label + " must be a PNG");
            }
            if (info.size() > maxBytes) {
                throw new WorldContentException(label + " is larger than " + maxBytes / 1024 + " KiB");
            }
            if (info.width() < minSide || info.width() > maxSide || info.height() < minSide || info.height() > maxSide) {
                throw new WorldContentException(label + " is " + info.width() + "x" + info.height() + "; it must be "
                        + (minSide == maxSide ? minSide + "x" + maxSide : minSide + " to " + maxSide + " pixels per side"));
            }
        }
    }

    /** Where a chunk's data lies in the file. */
    private record Chunk(int offset, int length) {
    }

    /** The parts of a structurally valid PNG that its canonical form keeps. */
    private record Parsed(int width, int height, int colorType, int bitDepth, Chunk ihdr, Chunk plte, Chunk trns,
                          byte[] idat) {
    }

    private PngValidator() {
    }

    /** Validates a PNG that must already be canonical, and describes it. */
    public static BlobInfo validateCanonical(byte[] bytes) throws WorldContentException {
        Parsed png = parse(bytes, true);
        inflate(png);
        return BlobInfo.png(bytes.length, png.width(), png.height());
    }

    /** Validates a canonical PNG for one role. */
    public static BlobInfo validateCanonical(byte[] bytes, Role role) throws WorldContentException {
        BlobInfo info = validateCanonical(bytes);
        role.check(info);
        return info;
    }

    /**
     * Validates any PNG and returns its canonical form: the same image with every
     * ancillary chunk except tRNS removed and the image data in one IDAT.
     */
    public static byte[] canonicalize(byte[] bytes) throws WorldContentException {
        Parsed png = parse(bytes, false);
        inflate(png);
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.idat().length + 1024);
        out.writeBytes(SIGNATURE);
        writeChunk(out, "IHDR", bytes, png.ihdr());
        if (png.colorType() == 3) {
            writeChunk(out, "PLTE", bytes, png.plte());
        }
        if (png.trns() != null) {
            writeChunk(out, "tRNS", bytes, png.trns());
        }
        writeChunk(out, "IDAT", png.idat(), new Chunk(0, png.idat().length));
        writeChunk(out, "IEND", new byte[0], new Chunk(0, 0));
        return out.toByteArray();
    }

    private static Parsed parse(byte[] bytes, boolean canonical) throws WorldContentException {
        if (bytes.length > WorldContentLimits.PNG_BYTES) {
            throw new WorldContentException("PNG is larger than " + WorldContentLimits.PNG_BYTES / 1024 + " KiB");
        }
        if (bytes.length < SIGNATURE.length || !Arrays.equals(bytes, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new WorldContentException("Not a PNG file");
        }
        int width = 0;
        int height = 0;
        int colorType = 0;
        int bitDepth = 0;
        int paletteEntries = 0;
        Chunk ihdr = null;
        Chunk plte = null;
        Chunk trns = null;
        List<Chunk> idats = new ArrayList<>();
        boolean idatEnded = false;
        long idatBytes = 0;
        int pos = SIGNATURE.length;
        while (true) {
            if (bytes.length - pos < CHUNK_OVERHEAD) {
                throw new WorldContentException("PNG is truncated");
            }
            long length = readUnsigned(bytes, pos);
            if (length > bytes.length - pos - CHUNK_OVERHEAD) {
                throw new WorldContentException("PNG chunk runs past the end of the file");
            }
            String type = chunkType(bytes, pos + 4);
            Chunk chunk = new Chunk(pos + 8, (int) length);
            CRC32 crc = new CRC32();
            crc.update(bytes, pos + 4, 4 + chunk.length());
            if (crc.getValue() != readUnsigned(bytes, chunk.offset() + chunk.length())) {
                throw new WorldContentException("PNG chunk " + type + " is corrupt (bad CRC)");
            }
            pos = chunk.offset() + chunk.length() + 4;

            if (ihdr == null) {
                if (!type.equals("IHDR") || chunk.length() != 13) {
                    throw new WorldContentException("PNG does not start with IHDR");
                }
                ihdr = chunk;
                long w = readUnsigned(bytes, chunk.offset());
                long h = readUnsigned(bytes, chunk.offset() + 4);
                if (w < WorldContentLimits.PNG_MIN_SIZE || w > WorldContentLimits.PNG_MAX_SIZE
                        || h < WorldContentLimits.PNG_MIN_SIZE || h > WorldContentLimits.PNG_MAX_SIZE) {
                    throw new WorldContentException("PNG is " + w + "x" + h + "; sides must be "
                            + WorldContentLimits.PNG_MIN_SIZE + " to " + WorldContentLimits.PNG_MAX_SIZE + " pixels");
                }
                width = (int) w;
                height = (int) h;
                bitDepth = bytes[chunk.offset() + 8] & 0xFF;
                colorType = bytes[chunk.offset() + 9] & 0xFF;
                checkDepth(colorType, bitDepth);
                if (bytes[chunk.offset() + 10] != 0 || bytes[chunk.offset() + 11] != 0) {
                    throw new WorldContentException("PNG uses an unknown compression or filter method");
                }
                if (bytes[chunk.offset() + 12] != 0) {
                    throw new WorldContentException("PNG is interlaced; save it without interlacing");
                }
                continue;
            }
            switch (type) {
                case "IHDR" -> throw new WorldContentException("PNG has a second IHDR");
                case "PLTE" -> {
                    if (plte != null || !idats.isEmpty() || trns != null) {
                        throw new WorldContentException("PNG palette is repeated or out of order");
                    }
                    if (colorType == 0 || colorType == 4) {
                        throw new WorldContentException("Greyscale PNG cannot have a palette");
                    }
                    if (canonical && colorType != 3) {
                        throw new WorldContentException("PNG is not canonical: palette on a true-colour image");
                    }
                    int entries = chunk.length() / 3;
                    int maxEntries = 1 << Math.min(bitDepth, 8);
                    if (chunk.length() % 3 != 0 || entries < 1 || entries > maxEntries) {
                        throw new WorldContentException("PNG palette has an invalid size");
                    }
                    plte = chunk;
                    paletteEntries = entries;
                }
                case "tRNS" -> {
                    if (trns != null || !idats.isEmpty()) {
                        throw new WorldContentException("PNG transparency is repeated or after the image data");
                    }
                    boolean valid = switch (colorType) {
                        case 0 -> chunk.length() == 2;
                        case 2 -> chunk.length() == 6;
                        case 3 -> plte != null && chunk.length() >= 1 && chunk.length() <= paletteEntries;
                        default -> false;
                    };
                    if (!valid) {
                        throw new WorldContentException("PNG transparency chunk does not fit its colour type");
                    }
                    trns = chunk;
                }
                case "IDAT" -> {
                    if (idatEnded) {
                        throw new WorldContentException("PNG image data chunks are not contiguous");
                    }
                    if (colorType == 3 && plte == null) {
                        throw new WorldContentException("Indexed PNG has no palette");
                    }
                    if (canonical && !idats.isEmpty()) {
                        throw new WorldContentException("PNG is not canonical: more than one IDAT");
                    }
                    idats.add(chunk);
                    idatBytes += chunk.length();
                }
                case "IEND" -> {
                    if (chunk.length() != 0) {
                        throw new WorldContentException("PNG end chunk is not empty");
                    }
                    if (idats.isEmpty()) {
                        throw new WorldContentException("PNG has no image data");
                    }
                    if (pos != bytes.length) {
                        throw new WorldContentException("PNG has data after its end");
                    }
                    byte[] idat = new byte[(int) idatBytes];
                    int at = 0;
                    for (Chunk part : idats) {
                        System.arraycopy(bytes, part.offset(), idat, at, part.length());
                        at += part.length();
                    }
                    return new Parsed(width, height, colorType, bitDepth, ihdr, plte, trns, idat);
                }
                case "acTL", "fcTL", "fdAT" -> throw new WorldContentException("Animated PNG is not supported");
                default -> {
                    if ((type.charAt(0) & 0x20) == 0) {
                        throw new WorldContentException("PNG has unknown critical chunk " + type);
                    }
                    if (canonical) {
                        throw new WorldContentException("PNG is not canonical: extra chunk " + type);
                    }
                }
            }
            if (!idats.isEmpty() && !type.equals("IDAT")) {
                idatEnded = true;
            }
        }
    }

    private static void checkDepth(int colorType, int bitDepth) throws WorldContentException {
        boolean valid = switch (colorType) {
            case 0, 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 2, 4, 6 -> bitDepth == 8;
            default -> false;
        };
        if (valid) {
            return;
        }
        if (bitDepth == 16) {
            throw new WorldContentException("PNG uses 16 bits per channel; " + DEPTH_RULE);
        }
        throw new WorldContentException("PNG colour type " + colorType + " with bit depth " + bitDepth
                + " is not supported; " + DEPTH_RULE);
    }

    /**
     * Inflates the image data into a buffer of exactly the size the header implies, with
     * a bounded number of steps, and checks every row's filter byte.
     */
    private static void inflate(Parsed png) throws WorldContentException {
        byte[] idat = png.idat();
        if (idat.length < 2) {
            throw new WorldContentException("PNG image data is truncated");
        }
        int cmf = idat[0] & 0xFF;
        int flg = idat[1] & 0xFF;
        if ((cmf & 0x0F) != 8 || (cmf >> 4) > 7 || (cmf * 256 + flg) % 31 != 0) {
            throw new WorldContentException("PNG image data is not a zlib stream");
        }
        if ((flg & 0x20) != 0) {
            throw new WorldContentException("PNG image data needs a preset dictionary");
        }
        int channels = switch (png.colorType()) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
        long rowBytes = 1 + ((long) png.width() * channels * png.bitDepth() + 7) / 8;
        int expected = (int) (rowBytes * png.height());
        // One spare byte, so a stream that holds more than the image is caught rather than cut
        byte[] out = new byte[expected + 1];
        long maxSteps = 4 + ((long) idat.length + expected) / 64 + 1024;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat, 0, idat.length);
            int produced = 0;
            for (long step = 0; !inflater.finished(); step++) {
                if (step >= maxSteps) {
                    throw new WorldContentException("PNG image data does not decompress");
                }
                int n = inflater.inflate(out, produced, out.length - produced);
                produced += n;
                if (produced > expected) {
                    throw new WorldContentException("PNG image data is larger than the image");
                }
                if (n == 0 && !inflater.finished()) {
                    if (inflater.needsDictionary()) {
                        throw new WorldContentException("PNG image data needs a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        throw new WorldContentException("PNG image data is truncated");
                    }
                    throw new WorldContentException("PNG image data does not decompress");
                }
            }
            if (produced != expected) {
                throw new WorldContentException("PNG image data is smaller than the image");
            }
            if (inflater.getRemaining() != 0) {
                throw new WorldContentException("PNG has data after its compressed image");
            }
        } catch (DataFormatException e) {
            throw new WorldContentException("PNG image data is corrupt");
        } finally {
            inflater.end();
        }
        for (long row = 0; row < png.height(); row++) {
            if ((out[(int) (row * rowBytes)] & 0xFF) > 4) {
                throw new WorldContentException("PNG row " + row + " has an unknown filter");
            }
        }
        if (png.colorType() == 3) {
            checkIndices(png, out, (int) rowBytes);
        }
    }

    /**
     * Reverses the row filters of an indexed image in place and checks that every pixel
     * names an entry of the palette. An indexed pixel is under one byte, so filters
     * predict from the byte before it.
     */
    private static void checkIndices(Parsed png, byte[] data, int rowBytes) throws WorldContentException {
        int entries = png.plte().length() / 3;
        int depth = png.bitDepth();
        int mask = (1 << depth) - 1;
        int stride = rowBytes - 1;
        for (int row = 0; row < png.height(); row++) {
            int start = row * rowBytes + 1;
            int above = start - rowBytes;
            int filter = data[start - 1] & 0xFF;
            for (int i = 0; i < stride; i++) {
                int left = i > 0 ? data[start + i - 1] & 0xFF : 0;
                int up = row > 0 ? data[above + i] & 0xFF : 0;
                int upLeft = row > 0 && i > 0 ? data[above + i - 1] & 0xFF : 0;
                int predicted = switch (filter) {
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) >>> 1;
                    case 4 -> paeth(left, up, upLeft);
                    default -> 0;
                };
                data[start + i] = (byte) (data[start + i] + predicted);
            }
            for (int x = 0; x < png.width(); x++) {
                int bit = x * depth;
                int index = (data[start + bit / 8] & 0xFF) >>> (8 - depth - bit % 8) & mask;
                if (index >= entries) {
                    throw new WorldContentException("PNG pixel at " + x + "," + row + " uses colour " + index
                            + " but the palette has " + entries);
                }
            }
        }
    }

    private static int paeth(int left, int up, int upLeft) {
        int p = left + up - upLeft;
        int pa = Math.abs(p - left);
        int pb = Math.abs(p - up);
        int pc = Math.abs(p - upLeft);
        if (pa <= pb && pa <= pc) {
            return left;
        }
        return pb <= pc ? up : upLeft;
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] source, Chunk chunk) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, chunk.length());
        out.writeBytes(typeBytes);
        out.write(source, chunk.offset(), chunk.length());
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(source, chunk.offset(), chunk.length());
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static long readUnsigned(byte[] bytes, int at) {
        return ((long) (bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private static String chunkType(byte[] bytes, int at) throws WorldContentException {
        for (int i = at; i < at + 4; i++) {
            int c = bytes[i] & 0xFF;
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
                throw new WorldContentException("PNG has a chunk with an invalid type");
            }
        }
        return new String(bytes, at, 4, StandardCharsets.US_ASCII);
    }
}
