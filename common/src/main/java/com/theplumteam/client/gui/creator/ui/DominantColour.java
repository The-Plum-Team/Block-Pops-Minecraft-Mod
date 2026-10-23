package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.runtime.RuntimeTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Finds the colour an image is mostly made of, for "Take from the logo". Visible pixels are
 * sorted into 4096 buckets by the top four bits of each channel, and the answer is the average
 * colour of the fullest bucket. Near-white and near-black buckets lose to any other, because a
 * logo's outline and background say little about its colour; they win only when the image has
 * nothing else.
 */
public final class DominantColour {
    private static final int VISIBLE_ALPHA = 128;
    private static final int BUCKETS = 4096;
    // Buckets whose channels all sit at or above this (or at or below the dark one) are extremes
    private static final int NEAR_WHITE = 14;
    private static final int NEAR_BLACK = 1;

    private DominantColour() {
    }

    /** The image's dominant colour as 0xRRGGBB, or empty when no pixel is visible. Any thread. */
    public static OptionalInt of(NativeImage image) {
        int[] counts = new int[BUCKETS];
        long[] red = new long[BUCKETS];
        long[] green = new long[BUCKETS];
        long[] blue = new long[BUCKETS];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                //? if >=1.21.2 {
                /*int pixel = image.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int b = pixel & 0xFF;
                *///? } else {
                // Below 1.21.2 pixels read as ABGR: red is the low byte
                int pixel = image.getPixelRGBA(x, y);
                int r = pixel & 0xFF;
                int b = (pixel >> 16) & 0xFF;
                //? }
                int g = (pixel >> 8) & 0xFF;
                if (pixel >>> 24 < VISIBLE_ALPHA) {
                    continue;
                }
                int bucket = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                counts[bucket]++;
                red[bucket] += r;
                green[bucket] += g;
                blue[bucket] += b;
            }
        }
        int best = fullest(counts, false);
        if (best < 0) {
            best = fullest(counts, true);
        }
        if (best < 0) {
            return OptionalInt.empty();
        }
        int n = counts[best];
        int rgb = (int) (red[best] / n) << 16 | (int) (green[best] / n) << 8 | (int) (blue[best] / n);
        return OptionalInt.of(rgb);
    }

    /** The dominant colour of a PNG's bytes, decoded here. Off the render thread is fine. */
    public static OptionalInt ofPng(byte[] png) {
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(png))) {
            return of(image);
        } catch (IOException | RuntimeException e) {
            BlockPopsMod.logDebug("Could not read an image for its colour: {}", e.toString());
            return OptionalInt.empty();
        }
    }

    /** The dominant colour of a texture loaded for a World collection, such as an uploaded logo. Render thread. */
    public static OptionalInt ofTexture(ResourceLocation id) {
        NativeImage image = RuntimeTextures.image(id);
        return image != null ? of(image) : OptionalInt.empty();
    }

    /**
     * Works out a PNG's dominant colour on a worker thread and hands it over on the render
     * thread, only while the screen that asked is still open.
     */
    public static void ofPngLater(Screen owner, byte[] png, Consumer<OptionalInt> receiver) {
        Thread worker = new Thread(() -> {
            OptionalInt colour = ofPng(png);
            Minecraft.getInstance().execute(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.screen == owner) {
                    receiver.accept(colour);
                }
            });
        }, "BlockPops Colour Reader");
        worker.setDaemon(true);
        worker.start();
    }

    private static int fullest(int[] counts, boolean withExtremes) {
        int best = -1;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            if (counts[bucket] == 0 || (!withExtremes && extreme(bucket))) {
                continue;
            }
            if (best < 0 || counts[bucket] > counts[best]) {
                best = bucket;
            }
        }
        return best;
    }

    private static boolean extreme(int bucket) {
        int r = bucket >> 8;
        int g = (bucket >> 4) & 0xF;
        int b = bucket & 0xF;
        return (r >= NEAR_WHITE && g >= NEAR_WHITE && b >= NEAR_WHITE)
                || (r <= NEAR_BLACK && g <= NEAR_BLACK && b <= NEAR_BLACK);
    }
}
