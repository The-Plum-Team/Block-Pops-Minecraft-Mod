package com.theplumteam.client.gui.creator.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one tooltip a creator screen shows in a frame. A node under the pointer asks for it while
 * it draws, and {@link CreatorScreen} draws it after everything else, so it is never covered by a
 * node drawn later. Before 1.21.6 the tooltip is drawn at once; from 1.21.6 vanilla takes it for
 * the end of the frame instead.
 */
public final class UiTooltip {
    private static final List<Component> PENDING = new ArrayList<>();
    // Where a tooltip asked for above a line starts, and the line it stays above; none while ANCHOR_NONE
    private static final int ANCHOR_NONE = Integer.MIN_VALUE;
    private static int anchorX = ANCHOR_NONE;
    private static int anchorBottom;
    // Vanilla's tooltip text starts this far right of the pointer, and its frame reaches this far
    // past the text's right end, with a unit to spare
    private static final int POINTER_GAP = 12;
    private static final int FRAME_ROOM = 4;

    private UiTooltip() {
    }

    /** Asks for a tooltip this frame; a later request in the same frame replaces it. */
    public static void set(Component line) {
        PENDING.clear();
        PENDING.add(line);
        anchorX = ANCHOR_NONE;
    }

    public static void set(List<Component> lines) {
        PENDING.clear();
        PENDING.addAll(lines);
        anchorX = ANCHOR_NONE;
    }

    /**
     * Asks for a tooltip this frame whose frame ends just above a line, its text starting at
     * {@code x}, rather than beside the pointer, so it never covers the line it explains, as a
     * status line's tooltip would.
     *
     * @param bottom the line's top: the tooltip's frame ends two units above it
     */
    public static void setAbove(List<Component> lines, int x, int bottom) {
        set(lines);
        anchorX = x;
        anchorBottom = bottom;
    }

    /** Forgets a request that no longer applies, as when a modal covers the node that made it. */
    public static void clear() {
        PENDING.clear();
        anchorX = ANCHOR_NONE;
    }

    /**
     * Draws the tooltip asked for this frame, if any, and forgets it. Vanilla puts a tooltip
     * right of the pointer and flips it left only once its text would pass the screen's edge,
     * so a text that ends within the last few units loses its frame's right side there; such a
     * tooltip is moved left until its whole frame is on the screen.
     */
    static void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenWidth) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>(PENDING);
        PENDING.clear();
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        if (anchorX != ANCHOR_NONE) {
            // Vanilla puts the text POINTER_GAP right of and above the point it is given, a
            // single line 8 high and each line of several 10, inside a frame FRAME_ROOM wider
            int textHeight = lines.size() == 1 ? 8 : 10 * lines.size();
            int textTop = Math.max(FRAME_ROOM, anchorBottom - 2 - FRAME_ROOM - textHeight);
            mouseX = anchorX - POINTER_GAP;
            mouseY = textTop + POINTER_GAP;
            anchorX = ANCHOR_NONE;
        }
        int textRight = mouseX + POINTER_GAP + width;
        if (textRight <= screenWidth && textRight > screenWidth - FRAME_ROOM) {
            mouseX = screenWidth - FRAME_ROOM - POINTER_GAP - width;
        }
        //? if >=1.21.6 {
        /*graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        *///? } else {
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        //? }
    }
}
