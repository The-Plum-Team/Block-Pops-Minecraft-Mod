package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.jetbrains.annotations.Nullable;

/**
 * Mouse and keyboard input in one shape on every version. Minecraft passed loose numbers to its
 * input hooks until 1.21.8 and passes event records from 1.21.9; {@link CreatorScreen} turns
 * either into these records, and the {@code forward} helpers turn them back into whatever the
 * running version's vanilla widgets take, for the nodes that own a text field.
 */
public final class UiInput {
    private UiInput() {
    }

    /**
     * A mouse button going down or up, or a drag.
     *
     * @param button      compare it with {@link InputConstants#MOUSE_BUTTON_LEFT}, never 0:
     *                    26.3 numbers the left button 1
     * @param doubleClick a second press of the same button close in time and place
     * @param raw         the version's own event from 1.21.9, otherwise null
     */
    public record Click(double x, double y, int button, boolean ctrl, boolean shift, boolean doubleClick,
                        @Nullable Object raw) {
        public boolean left() {
            return button == InputConstants.MOUSE_BUTTON_LEFT;
        }

        public boolean right() {
            return button == InputConstants.MOUSE_BUTTON_RIGHT;
        }

        /** The same click at another point, such as inside a scrolled area. */
        public Click at(double newX, double newY) {
            Object moved = raw;
            //? if >=26 {
            /*if (raw instanceof net.minecraft.client.input.MouseButtonEvent event) {
                moved = new net.minecraft.client.input.MouseButtonEvent(newX, newY, event.buttonInfo());
            }
            *///? }
            return new Click(newX, newY, button, ctrl, shift, doubleClick, moved);
        }
    }

    /**
     * A key going down.
     *
     * @param scan      the key's scancode, named so that 26.3's build-time rename of
     *                  {@code scancode()} leaves this accessor alone
     * @param copy      the version's copy shortcut, as are paste and select all
     * @param raw       the version's own event from 1.21.9, otherwise null
     */
    public record Key(int key, int scan, int mods, boolean ctrl, boolean shift, boolean escape, boolean copy,
                      boolean paste, boolean selectAll, @Nullable Object raw) {
        public boolean is(int keyCode) {
            return key == keyCode;
        }

        public boolean enter() {
            return key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
        }
    }

    /** Hands a click to a vanilla widget, such as a node's own text field. */
    public static boolean forwardClick(GuiEventListener listener, Click click) {
        //? if >=26 {
        /*return listener.mouseClicked(mouseEvent(click), click.doubleClick());
        *///? } else {
        return listener.mouseClicked(click.x(), click.y(), click.button());
        //? }
    }

    public static boolean forwardRelease(GuiEventListener listener, Click click) {
        //? if >=26 {
        /*return listener.mouseReleased(mouseEvent(click));
        *///? } else {
        return listener.mouseReleased(click.x(), click.y(), click.button());
        //? }
    }

    public static boolean forwardDrag(GuiEventListener listener, Click click, double dragX, double dragY) {
        //? if >=26 {
        /*return listener.mouseDragged(mouseEvent(click), dragX, dragY);
        *///? } else {
        return listener.mouseDragged(click.x(), click.y(), click.button(), dragX, dragY);
        //? }
    }

    public static boolean forwardKey(GuiEventListener listener, Key key) {
        //? if >=26 {
        /*net.minecraft.client.input.KeyEvent event = key.raw() instanceof net.minecraft.client.input.KeyEvent raw
                ? raw : new net.minecraft.client.input.KeyEvent(key.key(), key.scan(), key.mods());
        return listener.keyPressed(event);
        *///? } else {
        return listener.keyPressed(key.key(), key.scan(), key.mods());
        //? }
    }

    /** Hands a turn of the wheel to a vanilla widget, such as a list a node owns; up is positive. */
    public static boolean forwardScroll(GuiEventListener listener, double mouseX, double mouseY, double amount) {
        //? if >=1.21 {
        /*return listener.mouseScrolled(mouseX, mouseY, 0.0, amount);
        *///? } else {
        return listener.mouseScrolled(mouseX, mouseY, amount);
        //? }
    }

    /** Hands typed text to a vanilla widget, one character at a time as Minecraft types it. */
    public static boolean forwardTyped(GuiEventListener listener, String text) {
        boolean used = false;
        //? if >=26 {
        /*for (int codepoint : text.codePoints().toArray()) {
            used |= listener.charTyped(new net.minecraft.client.input.CharacterEvent(codepoint));
        }
        *///? } else {
        for (char character : text.toCharArray()) {
            used |= listener.charTyped(character, 0);
        }
        //? }
        return used;
    }

    //? if >=26 {
    /*private static net.minecraft.client.input.MouseButtonEvent mouseEvent(Click click) {
        if (click.raw() instanceof net.minecraft.client.input.MouseButtonEvent event) {
            return event;
        }
        int mods = (click.ctrl() ? InputConstants.MOD_CONTROL : 0) | (click.shift() ? InputConstants.MOD_SHIFT : 0);
        return new net.minecraft.client.input.MouseButtonEvent(click.x(), click.y(),
                new net.minecraft.client.input.MouseButtonInfo(click.button(), mods));
    }
    *///? }
}
