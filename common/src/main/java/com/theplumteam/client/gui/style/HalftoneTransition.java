package com.theplumteam.client.gui.style;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
//? } elif <1.21.6 {
/*import net.minecraft.client.renderer.RenderType;
*///? }
import org.jetbrains.annotations.Nullable;

/**
 * A menu's opening transition, as a comic halftone: black dots on a grid grow over the world from
 * the middle of the screen outward, and before they reach the corners the dots in the middle
 * already shrink away to show the menu, so a ring of black travels outward with the world beyond
 * it and the menu inside it.
 *
 * <p>The timing follows how motion designers stagger many small elements: every dot grows, holds
 * and shrinks on the same eased curve, and only its start is delayed by its distance from the
 * middle, so the front moves at an even speed and no dot jumps. A dot's area, not its radius,
 * follows the curve, which is what the eye reads as how black a spot is.
 *
 * <p>The world beyond the ring is the frame the game showed when the menu opened, taken as F2
 * takes a screenshot; the menu, opaque and at its own GUI scale from the start, is drawn under it,
 * so the hotbar never changes size on screen. The dots are drawn at the window's real pixels from
 * sprites with anti-aliased edges, a size every half pixel, all in one batch.
 *
 * <p>The screen lays the menu out once {@link #ready}, which takes a frame or two from 1.21.5,
 * where the frame is read back from the GPU, then calls {@link #begin}; while {@link #hidesMenu}
 * the menu takes no input. The packaged E2E plays no transition.
 */
public final class HalftoneTransition {
    /** How much later the dots in the corners start than the dot in the middle, in ms. */
    private static final double STAGGER_MILLIS = 500.0;
    /** How long each dot takes to grow, stays grown, and takes to shrink, in ms. */
    private static final double GROW_MILLIS = 380.0;
    private static final double HOLD_MILLIS = 80.0;
    private static final double SHRINK_MILLIS = 380.0;
    private static final double REVEAL_OFFSET_MILLIS = GROW_MILLIS + HOLD_MILLIS;
    private static final double TOTAL_MILLIS = REVEAL_OFFSET_MILLIS + STAGGER_MILLIS + SHRINK_MILLIS;
    /** How long the screen waits for the frame read back from the GPU before opening without it. */
    private static final long CAPTURE_TIMEOUT_MILLIS = 500;

    /** The grid, in GUI units of the menu's scale, and a grown dot's radius in cells. */
    private static final int CELL = 10;
    private static final float FULL_RADIUS = 0.75f;
    /** The sprites' radii step by this much, in pixels; their edges are a pixel soft. */
    private static final float RADIUS_STEP = 0.5f;
    // In front of the menu's models, which are drawn with depth before 1.21.6
    private static final float DEPTH = 500.0f;

    // The dots' sprites, one per radius, in a grid of square slots after a solid black square,
    // made for the cell size in pixels the window last needed
    @Nullable
    private static DynamicTexture dots;
    @Nullable
    private static ResourceLocation dotsLocation;
    private static int dotsCellPixels;
    private static int dotsSlot;
    private static int dotsColumns;
    private static int dotsCount;
    private static int dotsWidth;
    private static int dotsHeight;

    private final long created = System.nanoTime();
    // When the menu was laid out and the transition started; unknown before
    private long start = -1;
    // The frame the game showed when the menu opened, once it is read back
    @Nullable
    private DynamicTexture frame;
    @Nullable
    private ResourceLocation frameLocation;
    private int frameWidth;
    private int frameHeight;
    // Whether the transition plays: it does not in the packaged E2E or without its frame
    private boolean plays = !E2EDeterminism.ENABLED;
    private boolean closed;

    public HalftoneTransition() {
        if (plays) {
            capture();
        }
    }

    // Reads back the last frame, world and hotbar, before the menu draws anything over it
    private void capture() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            // 1.21.5 reads the frame back from the GPU and hands it over later; 26.2 moved the
            // main render target onto the game renderer
            //? if >=26.2 {
            /*Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), this::receive);
            *///? } elif >=1.21.5 {
            /*Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), this::receive);
            *///? } else {
            receive(Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
            //? }
        } catch (RuntimeException failure) {
            BlockPopsMod.LOGGER.warn("Could not capture the frame the menu opens over", failure);
            plays = false;
        }
    }

    private void receive(NativeImage image) {
        if (closed || !plays) {
            image.close();
            return;
        }
        frameWidth = image.getWidth();
        frameHeight = image.getHeight();
        frameLocation = ResourceLocations.of("blockpops", "opening_frame");
        frame = register(frameLocation, image);
    }

    /**
     * Whether the menu may be laid out: the frame it opens over is ready, or the transition does
     * not play, as when the frame could not be read back in time.
     */
    public boolean ready() {
        if (plays && frame == null && (System.nanoTime() - created) / 1_000_000 >= CAPTURE_TIMEOUT_MILLIS) {
            plays = false;
        }
        return !plays || frame != null;
    }

    /** Starts the transition, once the menu is laid out under it. */
    public void begin() {
        if (start < 0) {
            start = System.nanoTime();
        }
    }

    private double elapsed() {
        return start < 0 ? 0.0 : (System.nanoTime() - start) / 1_000_000.0;
    }

    /** Whether no dot has started to shrink yet, so the menu shows nowhere and takes no input. */
    public boolean hidesMenu() {
        return plays && elapsed() < REVEAL_OFFSET_MILLIS;
    }

    /** Releases the frame, as when the menu closes before the transition ends. */
    public void close() {
        closed = true;
        if (frame != null) {
            Minecraft.getInstance().getTextureManager().release(frameLocation);
            frame = null;
        }
    }

    /**
     * Draws the transition over what the screen drew this frame: the frame the menu opened over
     * wherever no dot has covered it yet, and the dots. Draws nothing once the transition is over.
     */
    public void draw(GuiGraphics graphics) {
        if (!plays || frame == null) {
            return;
        }
        double time = elapsed();
        if (time >= TOTAL_MILLIS) {
            close();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        int cellPixels = CELL * MenuGuiScale.scaleFor(minecraft);
        prepareDots(minecraft, cellPixels);
        int columns = (windowWidth + cellPixels - 1) / cellPixels;
        int rows = (windowHeight + cellPixels - 1) / cellPixels;
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float farthest = (float) Math.sqrt(centerX * centerX + centerY * centerY);
        // Each dot's share of its full area, and whether the world still shows under it
        float[] amounts = new float[columns * rows];
        boolean[] world = new boolean[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                float dx = column * cellPixels + cellPixels / 2.0f - centerX;
                float dy = row * cellPixels + cellPixels / 2.0f - centerY;
                double delay = Math.min(1.0f, (float) Math.sqrt(dx * dx + dy * dy) / farthest) * STAGGER_MILLIS;
                int index = row * columns + column;
                world[index] = time - delay < GROW_MILLIS;
                amounts[index] = amount(time - delay);
            }
        }

        // Drawn in the window's own pixels, whatever its GUI scale
        float pixel = (float) (1.0 / PixelFont.guiScale());
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, DEPTH);
        GuiPose.scale(graphics, pixel, pixel);

        // The world, in runs of cells along each row that no dot covers yet
        for (int row = 0; row < rows; row++) {
            int runStart = -1;
            for (int column = 0; column <= columns; column++) {
                boolean shows = column < columns && world[row * columns + column];
                if (shows && runStart < 0) {
                    runStart = column;
                } else if (!shows && runStart >= 0) {
                    int left = runStart * cellPixels;
                    int top = row * cellPixels;
                    int right = Math.min(windowWidth, column * cellPixels);
                    int bottom = Math.min(windowHeight, top + cellPixels);
                    quad(graphics, frameLocation, left, top, right, bottom, left, top, right - left, bottom - top,
                            frameWidth, frameHeight);
                    runStart = -1;
                }
            }
        }
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);

        // The dots: whole black cells in runs where they have grown over their cell, a sprite of
        // the dot's size elsewhere
        for (int row = 0; row < rows; row++) {
            int runStart = -1;
            for (int column = 0; column <= columns; column++) {
                float amount = column < columns ? amounts[row * columns + column] : 0.0f;
                float radius = (float) Math.sqrt(amount) * FULL_RADIUS * cellPixels;
                boolean full = column < columns && radius >= cellPixels * 0.7072f;
                if (full && runStart < 0) {
                    runStart = column;
                } else if (!full && runStart >= 0) {
                    int top = row * cellPixels;
                    quad(graphics, dotsLocation, runStart * cellPixels, top, column * cellPixels, top + cellPixels,
                            0, 0, 2, 2, dotsWidth, dotsHeight);
                    runStart = -1;
                }
                if (column < columns && !full && radius >= RADIUS_STEP) {
                    int index = Math.min(dotsCount - 1, Math.round(radius / RADIUS_STEP));
                    int left = column * cellPixels + cellPixels / 2 - dotsSlot / 2;
                    int top = row * cellPixels + cellPixels / 2 - dotsSlot / 2;
                    int slot = index + 1;
                    quad(graphics, dotsLocation, left, top, left + dotsSlot, top + dotsSlot,
                            dotsSlot * (slot % dotsColumns), dotsSlot * (slot / dotsColumns), dotsSlot, dotsSlot,
                            dotsWidth, dotsHeight);
                }
            }
        }
        GuiPose.flush(graphics);
        GuiPose.pop(graphics);
    }

    /**
     * How black a dot is, as the share of its full area, this long after it starts: growing,
     * grown, then shrinking away.
     */
    private static float amount(double since) {
        if (since < GROW_MILLIS) {
            return (float) ease(Math.max(0.0, since / GROW_MILLIS));
        }
        double shrink = (since - REVEAL_OFFSET_MILLIS) / SHRINK_MILLIS;
        return (float) (1.0 - ease(Math.max(0.0, Math.min(1.0, shrink))));
    }

    // Gentle at both ends: a sine ease in and out
    private static double ease(double progress) {
        return 0.5 - 0.5 * Math.cos(Math.PI * progress);
    }

    // A part of a texture drawn into a rectangle, in the current pose's units, through each
    // version's textured GUI drawing; before 1.21.2 the quads join one batch drawn at the flush
    private static void quad(GuiGraphics graphics, ResourceLocation texture, int left, int top, int right, int bottom,
                             int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        if (right <= left || bottom <= top) {
            return;
        }
        //? if >=1.21.6 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, left, top, u, v,
                right - left, bottom - top, regionWidth, regionHeight, textureWidth, textureHeight);
        *///? } elif >=1.21.2 {
        /*graphics.blit(RenderType::guiTextured, texture, left, top, u, v,
                right - left, bottom - top, regionWidth, regionHeight, textureWidth, textureHeight);
        *///? } else {
        float u0 = u / (float) textureWidth;
        float v0 = v / (float) textureHeight;
        float u1 = (u + regionWidth) / (float) textureWidth;
        float v1 = (v + regionHeight) / (float) textureHeight;
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.text(texture));
        Matrix4f pose = graphics.pose().last().pose();
        vertex(consumer, pose, left, top, u0, v0);
        vertex(consumer, pose, left, bottom, u0, v1);
        vertex(consumer, pose, right, bottom, u1, v1);
        vertex(consumer, pose, right, top, u1, v0);
        //? }
    }

    //? if <1.21.2 {
    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y, float u, float v) {
        //? if >=1.21 {
        /*consumer.addVertex(pose, x, y, 0.0f).setColor(0xFFFFFFFF).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
        *///? } else {
        consumer.vertex(pose, x, y, 0.0f).color(255, 255, 255, 255).uv(u, v).uv2(LightTexture.FULL_BRIGHT).endVertex();
        //? }
    }
    //? }

    // The dots' sprites for a cell size in pixels, made again when the window needs another one:
    // a solid black square, then one dot per radius step up to a grown dot, each in a square slot
    private static void prepareDots(Minecraft minecraft, int cellPixels) {
        if (dots != null && dotsCellPixels == cellPixels) {
            return;
        }
        if (dots != null) {
            minecraft.getTextureManager().release(dotsLocation);
        }
        float largest = FULL_RADIUS * cellPixels;
        int count = (int) Math.ceil(largest / RADIUS_STEP) + 1;
        int slot = 2 * (int) Math.ceil(largest) + 2;
        // A grid rather than a row, which an 8K window's grid would make too wide for a texture
        int columns = (int) Math.ceil(Math.sqrt(count + 1));
        int rows = (count + columns) / columns;
        NativeImage image = new NativeImage(slot * columns, slot * rows, true);
        for (int y = 0; y < slot; y++) {
            for (int x = 0; x < slot; x++) {
                setPixel(image, x, y, 0xFF000000);
            }
        }
        float middle = slot / 2.0f;
        for (int index = 0; index < count; index++) {
            float radius = index * RADIUS_STEP;
            int left = slot * ((index + 1) % columns);
            int top = slot * ((index + 1) / columns);
            for (int y = 0; y < slot; y++) {
                for (int x = 0; x < slot; x++) {
                    float dx = x + 0.5f - middle;
                    float dy = y + 0.5f - middle;
                    // A pixel soft at the edge: how much of the pixel the circle covers, roughly
                    float alpha = Math.max(0.0f, Math.min(1.0f, radius - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f));
                    setPixel(image, left + x, top + y, Math.round(alpha * 255.0f) << 24);
                }
            }
        }
        dotsLocation = ResourceLocations.of("blockpops", "opening_dots");
        dots = register(dotsLocation, image);
        dotsCellPixels = cellPixels;
        dotsSlot = slot;
        dotsColumns = columns;
        dotsCount = count;
        dotsWidth = image.getWidth();
        dotsHeight = image.getHeight();
    }

    // Black has no colour channels to order, so the pixel reads the same in every layout
    private static void setPixel(NativeImage image, int x, int y, int value) {
        //? if >=1.21.2 {
        /*image.setPixel(x, y, value);
        *///? } else {
        image.setPixelRGBA(x, y, value);
        //? }
    }

    private static DynamicTexture register(ResourceLocation location, NativeImage image) {
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=1.21.5 {
        /*DynamicTexture texture = new DynamicTexture(location::toString, image);
        *///? } else {
        DynamicTexture texture = new DynamicTexture(image);
        //? }
        minecraft.getTextureManager().register(location, texture);
        return texture;
    }
}
