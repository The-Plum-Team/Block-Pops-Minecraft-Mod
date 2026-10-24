package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A setting as a row of a dialog, as Quick Skin's settings are: the label on the left, a toggle on
 * the right (a sunk track, green while on, under a raised knob that slides to the side it is set
 * to), a hairline under the row and a lighter row under the mouse. A click anywhere on the row
 * flips it. An inactive row is greyed out.
 */
public class ToggleRow extends Button {
    /** The row's usual height. */
    public static final int HEIGHT = 24;

    private static final int ROW_HOVER = 0xFF35363A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_HOVER = 0xFFFFFFFF;
    // Dimmer than an active row, but still clearly readable beside its greyed-out toggle
    private static final int TEXT_DISABLED = 0xFFA8A8A8;
    private static final int TOGGLE_WIDTH = 30;
    private static final int KNOB_SIZE = 14;
    private static final int TOGGLE_OFF = 0xFF1E1F21;
    private static final int TOGGLE_OFF_SHADE = 0xFF151617;
    private static final int TOGGLE_ON = 0xFF3C8A3C;
    private static final int TOGGLE_ON_HOVER = 0xFF46A046;

    private boolean value;
    private final Consumer<Boolean> onChange;

    public ToggleRow(int x, int y, int width, Component label, boolean value, Consumer<Boolean> onChange) {
        this(x, y, width, HEIGHT, label, value, onChange);
    }

    public ToggleRow(int x, int y, int width, int height, Component label, boolean value, Consumer<Boolean> onChange) {
        super(x, y, width, height, label, button -> {
        }, DEFAULT_NARRATION);
        this.value = value;
        this.onChange = onChange;
    }

    public ToggleRow tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        return this;
    }

    public boolean value() {
        return value;
    }

    /** Sets the toggle without telling the listener, as when the setting changes elsewhere. */
    public void setValue(boolean value) {
        this.value = value;
    }

    @Override
    //? if >=26 {
    /*public void onPress(net.minecraft.client.input.InputWithModifiers bpInput) {
    *///? } else {
    public void onPress() {
    //? }
        value = !value;
        onChange.accept(value);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        UiSounds.play(soundManager, ModSounds.CLICK);
    }

    @Override
    //? if >=26 {
    /*protected void extractContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    *///? } else {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //? }
        boolean enabled = this.active;
        boolean hovered = enabled && this.isHovered();
        int left = getX();
        int top = getY();
        int right = left + this.width;
        int bottom = top + this.height;
        if (hovered) {
            graphics.fill(left, top, right, bottom - 1, ROW_HOVER);
        }
        graphics.fill(left, bottom - 1, right, bottom, Surfaces.ROW_LINE);

        // The toggle, right-aligned
        int trackRight = right - 6;
        int trackLeft = trackRight - TOGGLE_WIDTH;
        int middle = top + (this.height - 1) / 2;
        drawToggle(graphics, trackLeft, middle, value, enabled, hovered);

        // The label, in the room left of the toggle
        Font font = Minecraft.getInstance().font;
        int color = !enabled ? TEXT_DISABLED : hovered ? TEXT_HOVER : TEXT;
        int textX = left + 6;
        UiText.drawFitted(graphics, font, getMessage().getString(), textX, middle - 4, trackLeft - 8 - textX, color);
    }

    /** The toggle alone, its track's left edge at {@code trackLeft}, centred on {@code middle}. */
    public static void drawToggle(GuiGraphics graphics, int trackLeft, int middle, boolean on, boolean enabled, boolean hovered) {
        int trackRight = trackLeft + TOGGLE_WIDTH;
        int trackTop = middle - 7;
        int trackBottom = middle + 7;
        graphics.fill(trackLeft, trackTop, trackRight, trackBottom, Surfaces.OUTLINE);
        int track = !enabled ? Surfaces.FACE_DISABLED : on ? (hovered ? TOGGLE_ON_HOVER : TOGGLE_ON) : TOGGLE_OFF;
        graphics.fill(trackLeft + 1, trackTop + 1, trackRight - 1, trackBottom - 1, track);
        if (!on && enabled) {
            graphics.fill(trackLeft + 1, trackTop + 1, trackRight - 1, trackTop + 3, TOGGLE_OFF_SHADE);
        }
        int knobLeft = on ? trackRight - KNOB_SIZE : trackLeft;
        int knobTop = middle - KNOB_SIZE / 2 - 1;
        int knobBottom = knobTop + KNOB_SIZE + 2;
        Surfaces.raised(graphics, knobLeft, knobTop, knobLeft + KNOB_SIZE, knobBottom,
                !enabled ? Surfaces.BORDER_DISABLED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                !enabled ? Surfaces.FACE_DISABLED : hovered ? Surfaces.FACE_HOVER : Surfaces.FACE,
                Surfaces.LIP, 2);
    }

    @Override
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        return button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    }
}
