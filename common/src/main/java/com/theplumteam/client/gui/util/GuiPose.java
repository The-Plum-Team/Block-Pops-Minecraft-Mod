package com.theplumteam.client.gui.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Applies screen-space transforms through whichever stack the active version exposes.
 *
 * 1.21.6 made `GuiGraphics.pose()` a two-dimensional `Matrix3x2fStack` with its own
 * push and pop names, and removed the batch flush entirely because the GUI is drawn
 * from a deferred render state there.
 */
public final class GuiPose {
    private GuiPose() {
    }

    public static void push(GuiGraphics graphics) {
        //? if >=1.21.6 {
        /*graphics.pose().pushMatrix();
        *///? } else {
        graphics.pose().pushPose();
        //? }
    }

    public static void pop(GuiGraphics graphics) {
        //? if >=1.21.6 {
        /*graphics.pose().popMatrix();
        *///? } else {
        graphics.pose().popPose();
        //? }
    }

    public static void scale(GuiGraphics graphics, float x, float y) {
        //? if >=1.21.6 {
        /*graphics.pose().scale(x, y);
        *///? } else {
        graphics.pose().scale(x, y, 1.0f);
        //? }
    }

    public static void translate(GuiGraphics graphics, float x, float y) {
        //? if >=1.21.6 {
        /*graphics.pose().translate(x, y);
        *///? } else {
        graphics.pose().translate(x, y, 0.0f);
        //? }
    }

    /**
     * Ends the current batch so later drawing lands above it. From 1.21.6 the GUI is
     * drawn from a deferred render state that has no batch to end, so this does nothing.
     */
    public static void flush(GuiGraphics graphics) {
        //? if <1.21.6 {
        graphics.flush();
        //? }
    }
}
