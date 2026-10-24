package com.theplumteam.client.gui.style;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A small dark label for a corner, in the pixel font at about half a GUI unit per font pixel,
 * crisp, with an optional icon after the text, and gold under the mouse. A figure's cell says
 * which of its versions it shows in one ("1/3"); a collection's card links to its credits with
 * another ("WEB").
 */
public final class Chip {
    private static final int FACE = 0xD0101012;
    private static final int BORDER = 0x80FFFFFF;
    private static final int HOVER = 0xFFFFD700;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int ICON_GAP = 2;

    /** An arrow out of the corner, for a link that opens outside the game. */
    public static final String[] ARROW_OUT = {"..###", "...##", "..#.#", ".#...", "#...."};

    private Chip() {
    }

    private static float scale() {
        return PixelFont.crispScale(0.5f);
    }

    /** The chip's width for the text and icon, in GUI units. */
    public static int width(String text, @Nullable String[] icon) {
        int width = PixelFont.width(Component.literal(text), scale()) + 6;
        return icon == null ? width : width + PixelIcon.width(icon) + ICON_GAP;
    }

    /** The chip's height, in GUI units. */
    public static int height() {
        return Math.round(PixelFont.CAPITALS_HEIGHT * scale()) + 4;
    }

    /** Draws the chip with its top-right corner at {@code (right, top)}. */
    public static void draw(GuiGraphics graphics, String text, @Nullable String[] icon, int right, int top,
                            boolean hovered) {
        float scale = scale();
        int width = width(text, icon);
        int height = height();
        int left = right - width;
        int color = hovered ? HOVER : TEXT;
        graphics.fill(left, top, right, top + height, FACE);
        Surfaces.outline(graphics, left, top, width, height, hovered ? HOVER : BORDER);
        // The capitals start 3 font pixels above the text's origin
        PixelFont.draw(graphics, PixelFont.text(text), left + 3, top + 2 + 3 * scale, scale, color, true);
        if (icon != null) {
            PixelIcon.draw(graphics, icon, right - 3 - PixelIcon.width(icon), top + (height - icon.length) / 2,
                    color);
        }
    }
}
