package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.util.E2EDeterminism;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A menu's opening transition, as a Nintendo game changes screens: small dark diamonds with a
 * grey rim grow from the middle of the screen outward until they cover the world, then shrink
 * away in the same order to uncover the menu. A diamond is a square turned 45 degrees, so the
 * whole transition is plain GUI fills on every version.
 *
 * <p>The screen shows the world, not the menu, until {@link #covered}. It then lays the menu out
 * behind the diamonds and calls {@link #uncover}, and the diamonds start to shrink a moment
 * later, so they never uncover a menu that is not there yet; while {@link #hidesMenu} the menu
 * takes no input. Each frame the screen calls {@link #draw} last, which draws nothing once the
 * transition is over. The packaged E2E finishes it at once (see
 * {@link E2EDeterminism#transitionMillis}).
 */
public final class DiamondTransition {
    /** How long the diamonds take to cover the world, stay at least, and uncover the menu, in ms. */
    public static final long COVER_MILLIS = 300;
    public static final long HOLD_MILLIS = 60;
    public static final long REVEAL_MILLIS = 320;

    /**
     * Distance between two diamonds of a row at the menu's own scale, in GUI units; the rows are
     * staggered by half of it, so diamonds half as tall as this cover the screen.
     */
    private static final float MENU_STEP = 28.0f;
    /** The share of a phase over which the diamonds start, from the middle to the corners. */
    private static final float SPREAD = 0.55f;
    /** Grown diamonds overlap a little, so no seam shows between them. */
    private static final float FULL = 1.04f;
    /** The rim's width, measured along a diamond's half-diagonal. */
    private static final float RIM = 1.4f;
    private static final float HALF_SIDE_PER_HALF_DIAGONAL = (float) (1.0 / Math.sqrt(2.0));
    private static final int FACE_COLOR = 0xFF16161A;
    private static final int RIM_COLOR = 0xFF46474C;
    // In front of the menu's models, which are drawn with depth before 1.21.6
    private static final float DEPTH = 500.0f;

    private final long start = Util.getMillis();
    // When the diamonds start to uncover the menu; unknown until the menu is laid out
    private long revealStart = -1;

    // Time since the transition started, and since the diamonds started to uncover the menu
    private long sinceStart() {
        return E2EDeterminism.transitionMillis(Util.getMillis() - start);
    }

    private long sinceReveal() {
        return revealStart < 0 ? -1 : E2EDeterminism.transitionMillis(Util.getMillis() - revealStart);
    }

    /** Whether the diamonds have covered the world, so the menu may be laid out behind them. */
    public boolean covered() {
        return sinceStart() >= COVER_MILLIS;
    }

    /**
     * Tells the diamonds the menu is laid out behind them, so they may uncover it once they have
     * covered the screen for {@link #HOLD_MILLIS}.
     */
    public void uncover() {
        if (revealStart < 0) {
            revealStart = Math.max(Util.getMillis(), start + COVER_MILLIS + HOLD_MILLIS);
        }
    }

    /** Whether the menu is still entirely hidden, so it takes no input. */
    public boolean hidesMenu() {
        return sinceReveal() < 0;
    }

    /**
     * Draws the diamonds over everything the screen drew this frame, on a canvas of the given
     * size; nothing once the transition is over. The diamonds keep their size on screen when the
     * window's scale changes under them, as it does when the menu takes its own scale.
     */
    public void draw(GuiGraphics graphics, int width, int height) {
        long cover = sinceStart();
        long reveal = sinceReveal();
        if (reveal >= REVEAL_MILLIS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float step = MENU_STEP * MenuGuiScale.scaleFor(minecraft) / (float) PixelFont.guiScale();
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;
        double farthest = Math.hypot(centerX, centerY);
        int rows = (int) Math.ceil(height / (step / 2.0f)) + 2;
        int columns = (int) Math.ceil(width / step) + 2;

        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, DEPTH);
        //? if <1.21.2 {
        // One batch for the thousand fills, instead of a draw call each
        graphics.drawManaged(() -> drawDiamonds(graphics, cover, reveal, step, rows, columns, centerX, centerY, farthest));
        //? } else {
        /*drawDiamonds(graphics, cover, reveal, step, rows, columns, centerX, centerY, farthest);
        *///? }
        GuiPose.pop(graphics);
    }

    private static void drawDiamonds(GuiGraphics graphics, long cover, long reveal, float step, int rows,
                                     int columns, float centerX, float centerY, double farthest) {
        for (int row = -1; row < rows; row++) {
            float y = row * step / 2.0f;
            float offset = (row & 1) == 0 ? 0.0f : step / 2.0f;
            for (int column = -1; column < columns; column++) {
                float x = column * step + offset;
                float delay = (float) Math.min(1.0, Math.hypot(x - centerX, y - centerY) / farthest);
                float halfDiagonal = size(cover, reveal, delay) * step / 2.0f * FULL;
                if (halfDiagonal <= 0.5f) {
                    continue;
                }
                drawDiamond(graphics, x, y, halfDiagonal, RIM_COLOR);
                drawDiamond(graphics, x, y, halfDiagonal - RIM, FACE_COLOR);
            }
        }
    }

    /**
     * How grown a diamond is, from 0 to 1: each grows, and later shrinks, over the part of a phase
     * that starts with its delay, the middle ones first, easing out as it nears its size. Between
     * the phases every diamond stays grown.
     */
    private static float size(long cover, long reveal, float delay) {
        if (cover < COVER_MILLIS) {
            return ease(progress(cover, delay, COVER_MILLIS));
        }
        if (reveal < 0) {
            return 1.0f;
        }
        return 1.0f - ease(progress(reveal, delay, REVEAL_MILLIS));
    }

    private static float progress(long time, float delay, long length) {
        return (time - delay * SPREAD * length) / ((1.0f - SPREAD) * length);
    }

    private static float ease(float progress) {
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        float rest = 1.0f - clamped;
        return 1.0f - rest * rest * rest;
    }

    // A square turned 45 degrees about its centre, scaled from the unit fill
    private static void drawDiamond(GuiGraphics graphics, float x, float y, float halfDiagonal, int color) {
        if (halfDiagonal <= 0.0f) {
            return;
        }
        float halfSide = halfDiagonal * HALF_SIDE_PER_HALF_DIAGONAL;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y);
        GuiPose.rotate(graphics, 45.0f);
        GuiPose.scale(graphics, halfSide, halfSide);
        graphics.fill(-1, -1, 1, 1, color);
        GuiPose.pop(graphics);
    }
}
