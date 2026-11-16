package com.theplumteam.worldcontent;

/**
 * A 64-bit fingerprint of what an image looks like, so that an image taken down is known again
 * after it is saved anew, resized or lightly edited: images that look alike differ in few bits.
 *
 * <p>The image is laid over white and reduced to nine columns by eight rows of grey, each cell
 * the average of the pixels it covers; each bit says whether a cell is brighter than the one to
 * its right. Only whole numbers are used, so every host gives the same bits.
 */
public final class ImageHash {
    private static final int COLUMNS = 9;
    private static final int ROWS = 8;

    private ImageHash() {
    }

    /** The fingerprint of a canonical PNG. */
    public static long dhash(byte[] canonicalPng) throws WorldContentException {
        return dhash(PngValidator.decode(canonicalPng));
    }

    /** The fingerprint of a decoded image, whose sides are each at least one pixel. */
    public static long dhash(PngValidator.Image image) {
        int width = image.width();
        int height = image.height();
        int[] argb = image.argb();
        // A pixel spans COLUMNS by ROWS units and a cell width by height, so every overlap is whole
        long[] cells = new long[COLUMNS * ROWS];
        for (int y = 0; y < height; y++) {
            int firstRow = y * ROWS / height;
            int lastRow = ((y + 1) * ROWS - 1) / height;
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int alpha = pixel >>> 24;
                int luma = 77 * ((pixel >> 16) & 0xFF) + 150 * ((pixel >> 8) & 0xFF) + 29 * (pixel & 0xFF);
                // Over white, kept at 255 * 256 times the grey so nothing is rounded
                long grey = (long) luma * alpha + 255L * 256 * (255 - alpha);
                int firstColumn = x * COLUMNS / width;
                int lastColumn = ((x + 1) * COLUMNS - 1) / width;
                for (int row = firstRow; row <= lastRow; row++) {
                    long down = Math.min((row + 1) * height, (y + 1) * ROWS) - Math.max(row * height, y * ROWS);
                    for (int column = firstColumn; column <= lastColumn; column++) {
                        long across = Math.min((column + 1) * width, (x + 1) * COLUMNS)
                                - Math.max(column * width, x * COLUMNS);
                        cells[row * COLUMNS + column] += grey * down * across;
                    }
                }
            }
        }
        long hash = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS - 1; column++) {
                hash = hash << 1 | (cells[row * COLUMNS + column] > cells[row * COLUMNS + column + 1] ? 1 : 0);
            }
        }
        return hash;
    }

    /** In how many bits two fingerprints differ. */
    public static int distance(long a, long b) {
        long differing = a ^ b;
        int count = 0;
        while (differing != 0) {
            differing &= differing - 1;
            count++;
        }
        return count;
    }
}
