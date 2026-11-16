package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Makes a picked logo fit what a logo may be, so an operator does not have to resize it by hand:
 * a larger image is scaled down, keeping its proportions, until its sides and its file are within
 * the logo's limits, and a smaller one is enlarged by whole pixels.
 *
 * <p>It works on decoded pixels and writes a new PNG, which the picker then checks like any other
 * file; nothing here decides what is accepted. The pixels are 0xAARRGGBB, not premultiplied. Pure
 * Java, any thread.
 */
public final class LogoFit {
    /** The largest file read for a logo that will be scaled; the stored logo's own cap is far lower. */
    public static final int SOURCE_BYTES = 16 * 1024 * 1024;
    /** The longest side of an image decoded to be scaled, which bounds the memory it takes. */
    public static final int SOURCE_MAX_SIDE = 4096;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int MIN = WorldContentLimits.LOGO_MIN_SIZE;
    private static final int MAX = WorldContentLimits.LOGO_MAX_SIZE;
    // How much smaller the next try is when the file is still too large
    private static final double SHRINK = 0.85;

    /**
     * The size an image is drawn at and the canvas it is centred on, which is larger only for an
     * image too thin to reach the smallest side.
     */
    public record Plan(int width, int height, int canvasWidth, int canvasHeight) {
    }

    /** A fitted logo: its PNG and its size. */
    public record Fitted(byte[] png, int width, int height) {
    }

    private LogoFit() {
    }

    /** The width and height a PNG's header declares, or null when the bytes do not start as a PNG. */
    @Nullable
    public static int[] declaredSize(byte[] raw) {
        if (raw.length < 24) {
            return null;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (raw[i] != SIGNATURE[i]) {
                return null;
            }
        }
        if (raw[12] != 'I' || raw[13] != 'H' || raw[14] != 'D' || raw[15] != 'R') {
            return null;
        }
        int width = readInt(raw, 16);
        int height = readInt(raw, 20);
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    /**
     * How an image of this size fits a square of {@code longest} pixels: scaled down when a side
     * is longer, enlarged by a whole factor when a side is under the smallest a logo may have.
     */
    public static Plan plan(int width, int height, int longest) {
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        int w = width;
        int h = height;
        if (longSide > longest) {
            w = Math.max(1, (int) Math.round((double) width * longest / longSide));
            h = Math.max(1, (int) Math.round((double) height * longest / longSide));
        } else if (shortSide < MIN) {
            int factor = Math.max(1, Math.min((MIN + shortSide - 1) / shortSide, longest / longSide));
            w = width * factor;
            h = height * factor;
        }
        return new Plan(w, h, Math.max(w, MIN), Math.max(h, MIN));
    }

    /**
     * The image as a PNG a logo may be: at most the largest side and the largest file. An image
     * whose file is still too large at the largest side is made smaller until it fits.
     */
    public static Fitted fit(int[] argb, int width, int height) {
        int longest = MAX;
        while (true) {
            Plan plan = plan(width, height, longest);
            int[] scaled = resample(argb, width, height, plan.width(), plan.height());
            int[] canvas = centre(scaled, plan);
            byte[] png = encode(canvas, plan.canvasWidth(), plan.canvasHeight());
            if (png.length <= WorldContentLimits.LOGO_BYTES || longest <= MIN) {
                return new Fitted(png, plan.canvasWidth(), plan.canvasHeight());
            }
            longest = Math.max(MIN, (int) (Math.min(longest, Math.max(plan.width(), plan.height())) * SHRINK));
        }
    }

    /**
     * The image at another size. Making it smaller averages the pixels each new pixel covers,
     * weighted by their opacity so a transparent edge does not darken; making it larger repeats
     * pixels, which keeps pixel art sharp.
     */
    public static int[] resample(int[] argb, int width, int height, int toWidth, int toHeight) {
        if (toWidth == width && toHeight == height) {
            return argb;
        }
        int[] out = new int[toWidth * toHeight];
        if (toWidth >= width && toHeight >= height) {
            for (int y = 0; y < toHeight; y++) {
                int from = (int) ((long) y * height / toHeight) * width;
                for (int x = 0; x < toWidth; x++) {
                    out[y * toWidth + x] = argb[from + (int) ((long) x * width / toWidth)];
                }
            }
            return out;
        }
        double stepX = (double) width / toWidth;
        double stepY = (double) height / toHeight;
        for (int y = 0; y < toHeight; y++) {
            double top = y * stepY;
            double bottom = Math.min(height, (y + 1) * stepY);
            int lastY = Math.min(height, (int) Math.ceil(bottom));
            for (int x = 0; x < toWidth; x++) {
                double left = x * stepX;
                double right = Math.min(width, (x + 1) * stepX);
                int lastX = Math.min(width, (int) Math.ceil(right));
                double area = 0;
                double alpha = 0;
                double red = 0;
                double green = 0;
                double blue = 0;
                for (int sy = (int) top; sy < lastY; sy++) {
                    double coverY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    if (coverY <= 0) {
                        continue;
                    }
                    for (int sx = (int) left; sx < lastX; sx++) {
                        double cover = (Math.min(right, sx + 1) - Math.max(left, sx)) * coverY;
                        if (cover <= 0) {
                            continue;
                        }
                        int pixel = argb[sy * width + sx];
                        double weight = (pixel >>> 24) * cover;
                        area += cover;
                        alpha += weight;
                        red += ((pixel >> 16) & 0xFF) * weight;
                        green += ((pixel >> 8) & 0xFF) * weight;
                        blue += (pixel & 0xFF) * weight;
                    }
                }
                if (alpha > 0 && area > 0) {
                    out[y * toWidth + x] = channel(alpha / area) << 24 | channel(red / alpha) << 16
                            | channel(green / alpha) << 8 | channel(blue / alpha);
                }
            }
        }
        return out;
    }

    // The scaled image in the middle of its canvas, with clear pixels around it
    private static int[] centre(int[] scaled, Plan plan) {
        if (plan.canvasWidth() == plan.width() && plan.canvasHeight() == plan.height()) {
            return scaled;
        }
        int[] canvas = new int[plan.canvasWidth() * plan.canvasHeight()];
        int left = (plan.canvasWidth() - plan.width()) / 2;
        int top = (plan.canvasHeight() - plan.height()) / 2;
        for (int y = 0; y < plan.height(); y++) {
            System.arraycopy(scaled, y * plan.width(), canvas, (top + y) * plan.canvasWidth() + left, plan.width());
        }
        return canvas;
    }

    /**
     * The pixels as an 8-bit RGBA PNG in the canonical form the server stores: a header, one
     * block of image data and the end. Each row takes the filter that leaves it smallest.
     */
    public static byte[] encode(int[] argb, int width, int height) {
        int stride = width * 4;
        byte[] previous = new byte[stride];
        byte[] row = new byte[stride];
        byte[] filtered = new byte[stride];
        byte[] best = new byte[stride];
        byte[] data = new byte[(stride + 1) * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                row[x * 4] = (byte) (pixel >> 16);
                row[x * 4 + 1] = (byte) (pixel >> 8);
                row[x * 4 + 2] = (byte) pixel;
                row[x * 4 + 3] = (byte) (pixel >>> 24);
            }
            int bestFilter = 0;
            long bestCost = Long.MAX_VALUE;
            for (int filter = 0; filter <= 4; filter++) {
                long cost = filter(filter, row, previous, filtered);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestFilter = filter;
                    byte[] swap = best;
                    best = filtered;
                    filtered = swap;
                }
            }
            int at = y * (stride + 1);
            data[at] = (byte) bestFilter;
            System.arraycopy(best, 0, data, at + 1, stride);
            byte[] swap = previous;
            previous = row;
            row = swap;
        }

        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(data.length / 4 + 64);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
        } finally {
            deflater.end();
        }

        byte[] header = new byte[13];
        writeInt(header, 0, width);
        writeInt(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.size() + 64);
        out.writeBytes(SIGNATURE);
        writeChunk(out, "IHDR", header);
        writeChunk(out, "IDAT", compressed.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    // Filters one row the PNG way and says how large the result reads, the usual measure of which filter packs best
    private static long filter(int type, byte[] row, byte[] previous, byte[] out) {
        long cost = 0;
        for (int i = 0; i < row.length; i++) {
            int left = i >= 4 ? row[i - 4] & 0xFF : 0;
            int up = previous[i] & 0xFF;
            int upLeft = i >= 4 ? previous[i - 4] & 0xFF : 0;
            int predicted = switch (type) {
                case 1 -> left;
                case 2 -> up;
                case 3 -> (left + up) / 2;
                case 4 -> paeth(left, up, upLeft);
                default -> 0;
            };
            byte value = (byte) ((row[i] & 0xFF) - predicted);
            out[i] = value;
            cost += Math.abs(value);
        }
        return cost;
    }

    private static int paeth(int left, int up, int upLeft) {
        int estimate = left + up - upLeft;
        int byLeft = Math.abs(estimate - left);
        int byUp = Math.abs(estimate - up);
        int byUpLeft = Math.abs(estimate - upLeft);
        if (byLeft <= byUp && byLeft <= byUpLeft) {
            return left;
        }
        return byUp <= byUpLeft ? up : upLeft;
    }

    private static int channel(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] number = new byte[4];
        writeInt(number, 0, data.length);
        out.writeBytes(number);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        writeInt(number, 0, (int) crc.getValue());
        out.writeBytes(number);
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | (bytes[at + 3] & 0xFF);
    }

    private static void writeInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
