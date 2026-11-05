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
    }

    public static void set(List<Component> lines) {
        PENDING.clear();
        PENDING.addAll(lines);
    }

    /** Forgets a request that no longer applies, as when a modal covers the node that made it. */
    public static void clear() {
        PENDING.clear();
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
