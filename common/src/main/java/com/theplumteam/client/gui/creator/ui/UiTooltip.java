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

    /** Draws the tooltip asked for this frame, if any, and forgets it. */
    static void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>(PENDING);
        PENDING.clear();
        //? if >=1.21.6 {
        /*graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        *///? } else {
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        //? }
    }
}
