package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A slider for a whole number between two ends, in fixed steps, such as the logo's size on the
 * box from 50 to 150 %: a sunk track, lit blue up to a raised knob, in the menus' style, and the
 * value written on the right of it.
 * Clicking or dragging moves the knob to the nearest step; the left and right arrow keys move it
 * a step (a single unit with Shift) while the slider has the keyboard, and a double click puts it
 * back on its usual value. It can be disabled with a reason in its tooltip.
 */
public class UiRangeSlider extends UiNode.Box {
    public static final int HEIGHT = 12;
    private static final int KNOB_WIDTH = 7;
    private static final int KNOB_HEIGHT = 11;
    private static final int VALUE_GAP = 6;
    /** The sunk track's height. */
    private static final int TRACK_HEIGHT = 6;
    /** The lit part of the track, up to the knob: the blue button's face. */
    private static final int LIT = 0xFF3A70B8;

    private final int min;
    private final int max;
    private final int step;
    private final int usual;
    private final IntFunction<Component> format;
    private final IntConsumer onChange;
    private int value;
    private boolean pressed;
    @Nullable
    private Component disabledReason;

    /**
     * @param usual    the value a double click goes back to
     * @param format   the text shown for a value, such as "100 %"
     * @param onChange told each new value, while a drag goes on as well
     */
    public UiRangeSlider(int min, int max, int step, int usual, int value, IntFunction<Component> format,
                         IntConsumer onChange) {
        this.min = min;
        this.max = Math.max(min, max);
        this.step = Math.max(1, step);
        this.usual = clamp(usual);
        this.format = format;
        this.onChange = onChange;
        this.value = clamp(value);
        this.height = HEIGHT;
    }

    public int value() {
        return value;
    }

    /** Moves the knob without telling the screen. */
    public void set(int newValue) {
        this.value = clamp(newValue);
    }

    /** Disables the slider and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
        if (reason != null) {
            pressed = false;
        }
    }

    private int clamp(int candidate) {
        return Math.max(min, Math.min(max, candidate));
    }

    // The value's text keeps a fixed column on the right, as wide as the widest value
    private int valueWidth(Font font) {
        return Math.max(font.width(format.apply(min)), font.width(format.apply(max)));
    }

    private int trackLeft() {
        return x + KNOB_WIDTH / 2;
    }

    private int trackRight(Font font) {
        return Math.max(trackLeft() + 1, x + width - valueWidth(font) - VALUE_GAP - KNOB_WIDTH / 2);
    }

    private int knobCentre(Font font) {
        int span = trackRight(font) - trackLeft();
        return trackLeft() + Math.round(span * (value - min) / (float) Math.max(1, max - min));
    }

    // The nearest step to a point on the track, counted in steps from the low end
    private int valueAt(double mouseX, Font font) {
        int span = Math.max(1, trackRight(font) - trackLeft());
        double fraction = Math.max(0.0, Math.min(1.0, (mouseX - trackLeft()) / span));
        int raw = min + (int) Math.round(fraction * (max - min) / step) * step;
        return clamp(raw);
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean enabled = disabledReason == null;
        boolean hovered = hovered(mouseX, mouseY);
        int trackTop = y + KNOB_HEIGHT / 2 + 1 - TRACK_HEIGHT / 2;
        int right = trackRight(font);
        int wellLeft = trackLeft() - KNOB_WIDTH / 2;
        Draw.well(graphics, wellLeft, trackTop, right + KNOB_WIDTH / 2 + 1 - wellLeft, TRACK_HEIGHT,
                enabled ? Surfaces.BORDER : Theme.CONTROL_OUTLINE);
        int centre = knobCentre(font);
        if (enabled && centre > wellLeft + 2) {
            // The part up to the knob is lit
            graphics.fill(wellLeft + 2, trackTop + 2, centre, trackTop + TRACK_HEIGHT - 2, LIT);
        }
        int border = !enabled ? Surfaces.BORDER_DISABLED : pressed ? Surfaces.BORDER_SELECTED
                : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        int face = !enabled ? Surfaces.FACE_DISABLED : hovered || pressed ? Surfaces.FACE_HOVER : Surfaces.FACE;
        Draw.raised(graphics, centre - KNOB_WIDTH / 2, y, KNOB_WIDTH, KNOB_HEIGHT, border, face, 1);
        Component text = format.apply(value);
        Draw.string(graphics, font, text, x + width - font.width(text), y + 2, enabled ? Theme.TEXT_SOFT : Theme.FAINT);
        if (hovered && disabledReason != null) {
            UiTooltip.set(disabledReason);
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressed = false;
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (disabledReason == null) {
            if (click.doubleClick()) {
                choose(usual);
                return true;
            }
            pressed = true;
            choose(valueAt(click.x(), Minecraft.getInstance().font));
        }
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (!pressed) {
            return false;
        }
        choose(valueAt(click.x(), Minecraft.getInstance().font));
        return true;
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean used = pressed;
        pressed = false;
        return used;
    }

    @Override
    public boolean key(UiInput.Key key) {
        // Keys reach every node when none has the keyboard; arrows move only a slider that has it
        if (disabledReason != null || !CreatorScreen.hasKeyboard(this)) {
            return false;
        }
        int amount = key.shift() ? 1 : step;
        if (key.is(InputConstants.KEY_LEFT)) {
            choose(value - amount);
            return true;
        }
        if (key.is(InputConstants.KEY_RIGHT)) {
            choose(value + amount);
            return true;
        }
        return false;
    }

    private void choose(int candidate) {
        int next = clamp(candidate);
        if (next != value) {
            value = next;
            onChange.accept(next);
        }
    }
}
