package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A square pad that moves something across and up, such as the logo on a box: the marker's
 * place on the pad is the offset, with the middle meaning none, and the offset shows under the
 * pad in model pixels with one decimal. Offsets are whole tenths of a pixel from minus to plus a
 * maximum, upward positive as the box's logo layout keeps them. Clicking or dragging moves the
 * marker, the arrow keys nudge it by a tenth (by a whole pixel with Shift) while the pad has the
 * keyboard, and a double click puts it back in the middle.
 */
public class UiXYPad extends UiNode.Box {
    /** Told each new offset. */
    @FunctionalInterface
    public interface Moved {
        void moved(int offsetX, int offsetY);
    }

    private static final int MARKER = 5;
    private static final int CROSS = 0xFF2E2E2E;

    private final int max;
    private final Moved onMove;
    private int offsetX;
    private int offsetY;
    private boolean pressed;
    @Nullable
    private Component disabledReason;

    /**
     * @param max the largest offset either way, in tenths of a pixel, such as
     *            {@link com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout#MAX_OFFSET}
     */
    public UiXYPad(int max, int offsetX, int offsetY, Moved onMove) {
        this.max = Math.max(1, max);
        this.onMove = onMove;
        set(offsetX, offsetY);
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    /** Moves the marker without telling the screen. */
    public void set(int newX, int newY) {
        this.offsetX = clamp(newX);
        this.offsetY = clamp(newY);
    }

    /** Disables the pad and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
        if (reason != null) {
            pressed = false;
        }
    }

    private int clamp(int value) {
        return Math.max(-max, Math.min(max, value));
    }

    // The pad is the largest square that leaves a line under it for the offset
    private int side() {
        return Math.max(MARKER * 3, Math.min(width, height - Theme.LINE_HEIGHT - 2));
    }

    private Rect pad() {
        return new Rect(x, y, side(), side());
    }

    // How far the marker can go from the middle, in units, keeping it whole on the pad
    private int reach() {
        return Math.max(1, (side() - MARKER) / 2);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible() && pad().contains(mouseX, mouseY);
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean enabled = disabledReason == null;
        boolean hovered = hovered(mouseX, mouseY);
        int side = side();
        int outline = pressed ? Theme.GOLD : hovered && enabled ? Theme.FIELD_OUTLINE : Theme.CONTROL_OUTLINE;
        Draw.panel(graphics, x, y, side, side, Theme.CONTROL, outline);
        int middleX = x + side / 2;
        int middleY = y + side / 2;
        graphics.fill(x + 1, middleY, x + side - 1, middleY + 1, CROSS);
        graphics.fill(middleX, y + 1, middleX + 1, y + side - 1, CROSS);

        int reach = reach();
        int markX = middleX + Math.round(reach * offsetX / (float) max) - MARKER / 2;
        int markY = middleY - Math.round(reach * offsetY / (float) max) - MARKER / 2;
        Draw.panel(graphics, markX, markY, MARKER, MARKER, enabled ? Theme.GOLD : Theme.CONTROL_OUTLINE,
                0xFF000000);

        Component value = CreatorScreen.tr("xy.value", tenths(offsetX), tenths(offsetY));
        Draw.text(graphics, font, value.getString(), x, y + side + 3, Math.max(side, width), Theme.MUTED);
        if (hovered) {
            UiTooltip.set(enabled ? CreatorScreen.tr("xy.hint") : disabledReason);
        }
    }

    // A tenth of a pixel as pixels with one decimal, such as -0.3
    private static String tenths(int value) {
        return String.format(Locale.ROOT, "%.1f", value / 10.0);
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressed = false;
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (disabledReason != null) {
            return true;
        }
        if (click.doubleClick()) {
            move(0, 0);
            return true;
        }
        pressed = true;
        pointAt(click.x(), click.y());
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (!pressed) {
            return false;
        }
        pointAt(click.x(), click.y());
        return true;
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean used = pressed;
        pressed = false;
        return used;
    }

    // Moves the marker under the pointer, kept on the pad while a drag strays off it
    private void pointAt(double mouseX, double mouseY) {
        int side = side();
        double across = (mouseX - (x + side / 2)) / reach();
        double up = ((y + side / 2) - mouseY) / reach();
        move((int) Math.round(across * max), (int) Math.round(up * max));
    }

    @Override
    public boolean key(UiInput.Key key) {
        // Keys reach every node when none has the keyboard; arrows move only a pad that has it
        if (disabledReason != null || !CreatorScreen.hasKeyboard(this)) {
            return false;
        }
        int step = key.shift() ? 10 : 1;
        if (key.is(InputConstants.KEY_LEFT)) {
            move(offsetX - step, offsetY);
        } else if (key.is(InputConstants.KEY_RIGHT)) {
            move(offsetX + step, offsetY);
        } else if (key.is(InputConstants.KEY_UP)) {
            move(offsetX, offsetY + step);
        } else if (key.is(InputConstants.KEY_DOWN)) {
            move(offsetX, offsetY - step);
        } else {
            return false;
        }
        return true;
    }

    private void move(int newX, int newY) {
        int clampedX = clamp(newX);
        int clampedY = clamp(newY);
        if (clampedX != offsetX || clampedY != offsetY) {
            offsetX = clampedX;
            offsetY = clampedY;
            onMove.moved(clampedX, clampedY);
        }
    }
}
