package com.theplumteam.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fits a collection logo onto the front of the box from the logo image itself, so a
 * collection only has to name its texture.
 *
 * <p>Only the part of the image the cutout render type draws counts, so transparent
 * margins in the file change nothing. That part keeps its proportions and sits at the
 * bottom-left corner of the window frame. Its width grows with the aspect ratio, but more
 * slowly, so wide logos stay legible and square ones do not cover the window. The
 * constants were fitted to the sixteen logos placed by hand before this existed, and
 * reproduce them to about 0.4 model pixels on average.
 */
public final class LogoLayout {
    // The window face of box_block.geo.json seen from the front, in model pixels:
    // its left edge is at x = 5.5 and its bottom at y = 0.1.
    private static final float FACE_LEFT_X = 5.5f;
    private static final float FACE_BOTTOM_Y = 0.1f;
    private static final float FACE_WIDTH = 11.0f;
    // Where the logo cube draws the image's left (u = 0) and top (v = 0) edges, before
    // the placement transform moves and stretches it.
    private static final float CUBE_LEFT_X = 4.86f;
    private static final float CUBE_TOP_Y = 1.75f;
    // Fitted to the hand-placed logos.
    private static final float MARGIN_LEFT = 0.78f;
    private static final float MARGIN_BOTTOM = 1.02f;
    private static final float SQUARE_WIDTH = 5.19f;
    private static final float WIDTH_EXPONENT = 0.3f;
    private static final float MAX_HEIGHT = 4.2f;
    private static final float MAX_WIDTH = FACE_WIDTH - 2 * MARGIN_LEFT;
    // In blocks: just clear of the face, so the two never z-fight.
    private static final float DEPTH = -0.002f;
    // The cutout render types discard texels under 10% alpha.
    private static final int VISIBLE_ALPHA = 26;

    // Per texture: {width, height, left, top, right, bottom} of the image and of its drawn part.
    // Measured once per session, so a resource pack that swaps a logo keeps the old fit until restart.
    private static final Map<ResourceLocation, int[]> BOUNDS = new ConcurrentHashMap<>();

    private LogoLayout() {
    }

    /** The logo's translation, in blocks, and scale: {x, y, z, scaleX, scaleY, scaleZ}. */
    public static float[] placement(FigureCollection.LogoConfig config) {
        if (!config.isAutomatic()) {
            return new float[] {config.getPositionX(), config.getPositionY(), config.getPositionZ(),
                    config.getScaleX(), config.getScaleY(), config.getScaleZ()};
        }
        int[] bounds = BOUNDS.computeIfAbsent(config.getTexture(), LogoLayout::measure);
        return place(bounds, config.getScale(), config.getOffsetX(), config.getOffsetY());
    }

    private static float[] place(int[] bounds, float scale, float offsetX, float offsetY) {
        float imageWidth = bounds[0];
        float imageHeight = bounds[1];
        float drawnWidth = bounds[4] - bounds[2];
        float drawnHeight = bounds[5] - bounds[3];
        float aspect = drawnWidth / drawnHeight;
        float width = Math.min((float) (SQUARE_WIDTH * Math.pow(aspect, WIDTH_EXPONENT)),
                Math.min(MAX_WIDTH, MAX_HEIGHT * aspect)) * scale;
        float scaleX = width * imageWidth / drawnWidth;
        float scaleY = width / aspect * imageHeight / drawnHeight;
        // Anchor the drawn part's bottom-left corner, then find where that puts the image's
        // own left and top edges. The front faces -z, so the viewer's right is -x.
        float left = FACE_LEFT_X - MARGIN_LEFT - offsetX + scaleX * bounds[2] / imageWidth;
        float top = FACE_BOTTOM_Y + MARGIN_BOTTOM + offsetY + scaleY * bounds[5] / imageHeight;
        return new float[] {(left - CUBE_LEFT_X * scaleX) / 16.0f, (top - CUBE_TOP_Y * scaleY) / 16.0f, DEPTH,
                scaleX, scaleY, 1.0f};
    }

    private static int[] measure(ResourceLocation texture) {
        try (InputStream stream = Minecraft.getInstance().getResourceManager().getResource(texture).orElseThrow().open();
             NativeImage image = NativeImage.read(stream)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int left = 0;
            int top = 0;
            int right = width;
            int bottom = height;
            while (top < bottom && rowClear(image, top, left, right)) {
                top++;
            }
            if (top == bottom) {
                return new int[] {width, height, 0, 0, width, height};
            }
            while (rowClear(image, bottom - 1, left, right)) {
                bottom--;
            }
            while (columnClear(image, left, top, bottom)) {
                left++;
            }
            while (columnClear(image, right - 1, top, bottom)) {
                right--;
            }
            return new int[] {width, height, left, top, right, bottom};
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not measure logo {}, fitting it as a square", texture, e);
            return new int[] {1, 1, 0, 0, 1, 1};
        }
    }

    private static boolean rowClear(NativeImage image, int y, int fromX, int toX) {
        for (int x = fromX; x < toX; x++) {
            if (drawn(image, x, y)) {
                return false;
            }
        }
        return true;
    }

    private static boolean columnClear(NativeImage image, int x, int fromY, int toY) {
        for (int y = fromY; y < toY; y++) {
            if (drawn(image, x, y)) {
                return false;
            }
        }
        return true;
    }

    private static boolean drawn(NativeImage image, int x, int y) {
        // Alpha is the top byte in both the old ABGR and the newer ARGB accessors.
        //? if >=1.21.2 {
        /*int pixel = image.getPixel(x, y);
        *///? } else {
        int pixel = image.getPixelRGBA(x, y);
        //? }
        return pixel >>> 24 >= VISIBLE_ALPHA;
    }
}
