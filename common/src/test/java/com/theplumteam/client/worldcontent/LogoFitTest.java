package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A picked logo that is not one a logo may be is scaled to fit rather than refused. */
class LogoFitTest {
    private static final int MAX = WorldContentLimits.LOGO_MAX_SIZE;
    private static final int MIN = WorldContentLimits.LOGO_MIN_SIZE;

    @Test
    void aLargerImageIsScaledDownKeepingItsProportions() {
        assertEquals(new LogoFit.Plan(512, 171, 512, 171), LogoFit.plan(1024, 341, MAX));
        assertEquals(new LogoFit.Plan(171, 512, 171, 512), LogoFit.plan(341, 1024, MAX));
        assertEquals(new LogoFit.Plan(512, 512, 512, 512), LogoFit.plan(4096, 4096, MAX));
    }

    @Test
    void anImageThatFitsKeepsItsSize() {
        assertEquals(new LogoFit.Plan(512, 16, 512, 16), LogoFit.plan(512, 16, MAX));
        assertEquals(new LogoFit.Plan(200, 80, 200, 80), LogoFit.plan(200, 80, MAX));
    }

    @Test
    void aSmallerImageIsEnlargedByWholePixels() {
        assertEquals(new LogoFit.Plan(16, 16, 16, 16), LogoFit.plan(8, 8, MAX));
        assertEquals(new LogoFit.Plan(30, 20, 30, 20), LogoFit.plan(15, 10, MAX));
    }

    @Test
    void anImageTooThinToReachTheSmallestSideIsCentredOnAClearCanvas() {
        assertEquals(new LogoFit.Plan(512, 8, 512, MIN), LogoFit.plan(1024, 16, MAX));
        assertEquals(new LogoFit.Plan(10, 500, MIN, 500), LogoFit.plan(10, 500, MAX));

        int[] white = new int[1024 * 16];
        java.util.Arrays.fill(white, 0xFFFFFFFF);
        LogoFit.Fitted fitted = LogoFit.fit(white, 1024, 16);
        assertEquals(512, fitted.width());
        assertEquals(MIN, fitted.height());
    }

    @Test
    void scalingDownAveragesThePixelsEachNewPixelCovers() {
        // Two by two blocks of black and white become one grey pixel each
        int[] source = {
            0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFFFF0000,
            0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFFFF0000,
        };
        assertArrayEquals(new int[] {0xFF808080, 0xFFFF0000}, LogoFit.resample(source, 4, 2, 2, 1));
    }

    @Test
    void aClearPixelDoesNotDarkenItsNeighbours() {
        // Clear pixels carry black; the edge keeps the visible pixel's colour at half the opacity
        int[] source = {0xFFFF8040, 0x00000000};
        assertArrayEquals(new int[] {0x80FF8040}, LogoFit.resample(source, 2, 1, 1, 1));
        assertArrayEquals(new int[] {0}, LogoFit.resample(new int[] {0, 0}, 2, 1, 1, 1));
    }

    @Test
    void enlargingRepeatsPixels() {
        int[] source = {0xFF112233, 0x80445566};
        assertArrayEquals(new int[] {0xFF112233, 0xFF112233, 0x80445566, 0x80445566,
                0xFF112233, 0xFF112233, 0x80445566, 0x80445566}, LogoFit.resample(source, 2, 1, 4, 2));
        assertSame(source, LogoFit.resample(source, 2, 1, 2, 1));
    }

    @Test
    void theWrittenPngIsOneTheServerStoresAsItIs() throws WorldContentException {
        int[] pixels = new int[40 * 24];
        Random random = new Random(7);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt();
        }
        byte[] png = LogoFit.encode(pixels, 40, 24);
        // Already canonical: making it so changes nothing
        assertArrayEquals(png, PngValidator.canonicalize(png));
        BlobInfo info = PngValidator.validateCanonical(png, PngValidator.Role.LOGO);
        assertEquals(40, info.width());
        assertEquals(24, info.height());
        assertArrayEquals(pixels, decode(png, 40, 24));
    }

    @Test
    void aLogoOfTheUsualOversizeFitsAfterScaling() throws WorldContentException {
        int[] pixels = new int[1024 * 341];
        for (int y = 0; y < 341; y++) {
            for (int x = 0; x < 1024; x++) {
                pixels[y * 1024 + x] = (x / 64 + y / 64) % 2 == 0 ? 0xFF9B59B6 : 0x00000000;
            }
        }
        LogoFit.Fitted fitted = LogoFit.fit(pixels, 1024, 341);
        assertEquals(512, fitted.width());
        assertEquals(171, fitted.height());
        BlobInfo info = PngValidator.validateCanonical(fitted.png(), PngValidator.Role.LOGO);
        assertEquals(512, info.width());
        assertEquals(171, info.height());
    }

    @Test
    void anImageWhoseFileStaysTooLargeIsMadeSmallerUntilItFits() throws WorldContentException {
        // Noise does not compress: at 512 pixels a side it is about four times a logo's cap
        int[] pixels = new int[800 * 800];
        Random random = new Random(11);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt() | 0xFF000000;
        }
        LogoFit.Fitted fitted = LogoFit.fit(pixels, 800, 800);
        assertTrue(fitted.png().length <= WorldContentLimits.LOGO_BYTES, "file is " + fitted.png().length);
        assertTrue(fitted.width() < MAX && fitted.width() >= MIN, "side is " + fitted.width());
        assertEquals(fitted.width(), fitted.height());
        PngValidator.validateCanonical(fitted.png(), PngValidator.Role.LOGO);
    }

    @Test
    void theDeclaredSizeIsReadOnlyFromAPngHeader() {
        byte[] png = LogoFit.encode(new int[20 * 30], 20, 30);
        assertArrayEquals(new int[] {20, 30}, LogoFit.declaredSize(png));
        assertNull(LogoFit.declaredSize(new byte[64]));
        assertNull(LogoFit.declaredSize(java.util.Arrays.copyOf(png, 20)));
        byte[] noSize = png.clone();
        noSize[16] = noSize[17] = noSize[18] = noSize[19] = 0;
        assertNull(LogoFit.declaredSize(noSize));
        assertNotNull(LogoFit.declaredSize(java.util.Arrays.copyOf(png, 24)));
    }

    // Reads back what encode wrote: 8-bit RGBA rows, each with one of the five PNG filters
    private static int[] decode(byte[] png, int width, int height) {
        try {
            int at = 8 + 12 + 13;
            int length = (png[at] & 0xFF) << 24 | (png[at + 1] & 0xFF) << 16 | (png[at + 2] & 0xFF) << 8 | (png[at + 3] & 0xFF);
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(png, at + 8, length);
            int stride = width * 4;
            byte[] data = new byte[(stride + 1) * height];
            int read = 0;
            while (read < data.length) {
                int n = inflater.inflate(data, read, data.length - read);
                if (n == 0) {
                    break;
                }
                read += n;
            }
            inflater.end();
            assertEquals(data.length, read);
            int[] pixels = new int[width * height];
            byte[] previous = new byte[stride];
            byte[] row = new byte[stride];
            for (int y = 0; y < height; y++) {
                int filter = data[y * (stride + 1)];
                for (int i = 0; i < stride; i++) {
                    int left = i >= 4 ? row[i - 4] & 0xFF : 0;
                    int up = previous[i] & 0xFF;
                    int upLeft = i >= 4 ? previous[i - 4] & 0xFF : 0;
                    int predicted = switch (filter) {
                        case 1 -> left;
                        case 2 -> up;
                        case 3 -> (left + up) / 2;
                        case 4 -> {
                            int estimate = left + up - upLeft;
                            int byLeft = Math.abs(estimate - left);
                            int byUp = Math.abs(estimate - up);
                            int byUpLeft = Math.abs(estimate - upLeft);
                            yield byLeft <= byUp && byLeft <= byUpLeft ? left : byUp <= byUpLeft ? up : upLeft;
                        }
                        default -> 0;
                    };
                    row[i] = (byte) (data[y * (stride + 1) + 1 + i] + predicted);
                }
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = (row[x * 4 + 3] & 0xFF) << 24 | (row[x * 4] & 0xFF) << 16
                            | (row[x * 4 + 1] & 0xFF) << 8 | row[x * 4 + 2] & 0xFF;
                }
                byte[] swap = previous;
                previous = row;
                row = swap;
            }
            return pixels;
        } catch (java.util.zip.DataFormatException e) {
            throw new AssertionError(e);
        }
    }
}
