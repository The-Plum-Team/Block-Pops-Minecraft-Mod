package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntConsumer;

/**
 * A whole number between two limits, as a rarity's weight from 1 to 1000: a text field in the
 * menus' sunk well between a small raised minus and plus button. The buttons step the number by
 * one, or by ten with Shift, and so do the up and down arrow keys while the field types. Typing a
 * number in range changes it at once; anything else outlines the field in red and changes
 * nothing, and the field shows the last good number again when it loses the keyboard. Enter and
 * Escape both leave the field.
 *
 * <p>The field is the node's own vanilla text field, which it draws and hands input to itself,
 * so it works inside a scrolling area. Only digits, and a minus sign when the limits allow one,
 * are passed to it. Inside a dialog, add the field with {@link UiModal#ownField} as well, and the
 * screen gives it the keyboard as it does the dialog's other fields.
 */
public class UiStepper extends UiNode.Box {
    public static final int HEIGHT = 20;
    private static final int BUTTON = 16;
    private static final int GAP = 2;
    private static final int OFF_TEXT = 0xFF6A6A6A;

    private final int min;
    private final int max;
    private final IntConsumer onChange;
    private final UiField field;
    private int value;
    private boolean valid = true;
    // Set while the stepper writes the field itself, so its responder ignores the change
    private boolean syncing;
    // Whether the field had the keyboard when last looked at, to notice it losing it
    private boolean typing;
    // A press inside the field, whose drags select its text
    private boolean pressedField;
    @Nullable
    private Component disabledReason;

    /** @param onChange told each new number in range, as it is typed or stepped */
    public UiStepper(int min, int max, int value, IntConsumer onChange) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.value = clamp(value);
        this.onChange = onChange;
        this.height = HEIGHT;
        Font font = Minecraft.getInstance().font;
        this.field = new UiField(font, 0, 0, 40, UiField.HEIGHT, CreatorScreen.tr("stepper.range", this.min, this.max));
        this.field.setMaxLength(Math.max(String.valueOf(this.min).length(), String.valueOf(this.max).length()));
        this.field.setValue(String.valueOf(this.value));
        this.field.setResponder(this::typedText);
    }

    /** The node's own text field, for {@link UiModal#ownField} when the stepper sits in a dialog. */
    public EditBox field() {
        return field;
    }

    public int value() {
        return value;
    }

    /** Shows a number without telling the screen. */
    public void setValue(int newValue) {
        value = clamp(newValue);
        showValue();
    }

    /** Disables the stepper and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
        field.setEditable(reason == null);
        if (reason != null) {
            CreatorScreen.releaseKeyboard(this);
        }
    }

    private int clamp(int number) {
        return Math.max(min, Math.min(max, number));
    }

    @Override
    public Box at(int x, int y, int width, int height) {
        super.at(x, y, width, height);
        field.place(x + BUTTON + GAP, y, Math.max(16, width - 2 * (BUTTON + GAP)));
        return this;
    }

    private Rect minus() {
        return new Rect(x, y + (height - BUTTON) / 2, BUTTON, BUTTON);
    }

    private Rect plus() {
        return new Rect(x + width - BUTTON, y + (height - BUTTON) / 2, BUTTON, BUTTON);
    }

    // Follows the screen's keyboard: the field shows a cursor only while it types. A dialog's
    // screen hands the field the keyboard itself, so it is left alone there.
    private void followKeyboard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null && minecraft.screen.children().contains(field)) {
            return;
        }
        boolean now = disabledReason == null && CreatorScreen.hasKeyboard(this) && (typing || pressedField);
        if (typing && !now) {
            // Leaving the field: a number that was not in range gives way to the last good one
            showValue();
        }
        typing = now;
        // Only on a change: setting the focus restarts the cursor's blink, which would stop it
        if (field.isFocused() != now) {
            field.setFocused(now);
        }
    }

    @Override
    public void tick() {
        followKeyboard();
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        followKeyboard();
        Font font = Minecraft.getInstance().font;
        boolean enabled = disabledReason == null;
        drawButton(graphics, font, minus(), "-", enabled && value > min, mouseX, mouseY);
        drawButton(graphics, font, plus(), "+", enabled && value < max, mouseX, mouseY);
        field.render(graphics, mouseX, mouseY, partialTick);
        if (!valid) {
            // The well's border in red
            Draw.outline(graphics, field.wellX() + 1, field.getY() + 1, field.wellWidth() - 2, field.getHeight() - 2,
                    Theme.RED);
        }
        if (hovered(mouseX, mouseY)) {
            if (!enabled) {
                UiTooltip.set(disabledReason);
            } else if (!valid) {
                UiTooltip.set(CreatorScreen.tr("stepper.range", min, max));
            }
        }
    }

    private static void drawButton(GuiGraphics graphics, Font font, Rect at, String glyph, boolean usable,
                                   int mouseX, int mouseY) {
        boolean hovered = usable && at.contains(mouseX, mouseY);
        int border = !usable ? Surfaces.BORDER_DISABLED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        int face = !usable ? Surfaces.FACE_DISABLED : hovered ? Surfaces.FACE_HOVER : Surfaces.FACE;
        int faceBottom = Draw.raised(graphics, at.x(), at.y(), at.width(), at.height(), border, face, 1);
        Draw.string(graphics, font, glyph, at.centerX() - font.width(glyph) / 2 + 1,
                at.y() + (faceBottom - at.y() - 8) / 2 + 1, usable ? Theme.TEXT : OFF_TEXT);
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressedField = false;
        if (!isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (!click.left() || disabledReason != null) {
            return true;
        }
        int step = click.shift() ? 10 : 1;
        if (minus().contains(click.x(), click.y())) {
            stepBy(-step);
        } else if (plus().contains(click.x(), click.y())) {
            stepBy(step);
        } else if (field.isMouseOver(click.x(), click.y())) {
            // The screen gives this node the keyboard once the click is taken
            pressedField = true;
            typing = true;
            field.setFocused(true);
            UiInput.forwardClick(field, click);
        }
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        return pressedField && UiInput.forwardDrag(field, click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean used = pressedField;
        pressedField = false;
        if (used) {
            UiInput.forwardRelease(field, click);
        }
        return used;
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (!typing || disabledReason != null) {
            return false;
        }
        if (key.escape() || key.enter()) {
            CreatorScreen.releaseKeyboard(this);
            followKeyboard();
            return true;
        }
        if (key.is(InputConstants.KEY_UP) || key.is(InputConstants.KEY_DOWN)) {
            int step = key.shift() ? 10 : 1;
            stepBy(key.is(InputConstants.KEY_UP) ? step : -step);
            return true;
        }
        if (key.is(InputConstants.KEY_TAB)) {
            return false;
        }
        UiInput.forwardKey(field, key);
        // Every other key is the field's while it types, so no screen shortcut fires
        return true;
    }

    @Override
    public boolean typed(String text) {
        if (!typing || disabledReason != null) {
            return false;
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if ((character >= '0' && character <= '9') || (character == '-' && min < 0)) {
                kept.append(character);
            }
        }
        if (kept.length() > 0) {
            UiInput.forwardTyped(field, kept.toString());
        }
        return true;
    }

    private void stepBy(int amount) {
        int next = clamp(value + amount);
        showValue(next);
        if (next != value) {
            value = next;
            onChange.accept(next);
        }
    }

    private void showValue() {
        showValue(value);
    }

    private void showValue(int number) {
        syncing = true;
        field.setValue(String.valueOf(number));
        syncing = false;
        valid = true;
    }

    private void typedText(String text) {
        if (syncing) {
            return;
        }
        Integer parsed = parse(text);
        valid = parsed != null && parsed >= min && parsed <= max;
        if (valid && parsed != value) {
            value = parsed;
            onChange.accept(parsed);
        }
    }

    @Nullable
    private static Integer parse(String text) {
        String digits = text.strip();
        if (!digits.matches("-?[0-9]{1,9}")) {
            return null;
        }
        return Integer.parseInt(digits);
    }
}
