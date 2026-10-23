package com.theplumteam.client.gui.creator.ui;

import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One piece of a creator screen: a plain object that the host {@link CreatorScreen} draws and
 * hands input to, instead of a vanilla widget. Vanilla widgets changed their drawing and input
 * hooks several times across the versions this mod supports, so the creator keeps those hooks in
 * the host screen alone and every node sees the same calls on every version.
 *
 * <p>Input arrives in screen coordinates. Each input method answers whether the node used the
 * event, which stops it from reaching anything drawn below. Nodes draw through
 * {@link #draw}, never through a method named {@code render}, because 26.x renames those at build
 * time.
 */
public interface UiNode {
    /** Where the node sits on the screen. */
    Rect bounds();

    void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    default boolean click(UiInput.Click click) {
        return false;
    }

    /** The button went up; sent to the node that took the click, wherever the pointer is now. */
    default boolean release(UiInput.Click click) {
        return false;
    }

    /** The pointer moved with a button held; sent to the node that took the click. */
    default boolean drag(UiInput.Click click, double dragX, double dragY) {
        return false;
    }

    /** The wheel turned over the node; a positive amount scrolls up. */
    default boolean scroll(double mouseX, double mouseY, double amount) {
        return false;
    }

    /** A key went down while the node had the keyboard, or no node had it. */
    default boolean key(UiInput.Key key) {
        return false;
    }

    /** Text was typed while the node had the keyboard. */
    default boolean typed(String text) {
        return false;
    }

    /**
     * Finds where a file dropped on the window could go: adds every visible drop zone in this
     * node to the list and returns the one under the point, or null. A container passes the
     * point on in its children's coordinates, as it passes on clicks.
     */
    @Nullable
    default UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        return null;
    }

    /**
     * The child this node passes the keyboard to, such as the field last clicked inside a
     * scrolling area, or null. {@link CreatorScreen#hasKeyboard} follows it down.
     */
    @Nullable
    default UiNode focusedChild() {
        return null;
    }

    /** Runs once per client tick while the node is on screen. */
    default void tick() {
    }

    default boolean visible() {
        return true;
    }

    /** Whether the pointer is over the visible node. */
    default boolean isMouseOver(double mouseX, double mouseY) {
        return visible() && bounds().contains(mouseX, mouseY);
    }

    /** A rectangle in GUI units. */
    record Rect(int x, int y, int width, int height) {
        public static final Rect EMPTY = new Rect(0, 0, 0, 0);

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        /** The rectangle shrunk by an amount on every side. */
        public Rect inset(int amount) {
            return new Rect(x + amount, y + amount, Math.max(0, width - 2 * amount), Math.max(0, height - 2 * amount));
        }
    }

    /**
     * A node with a position the screen sets while it lays itself out, and a visibility it can
     * switch. Most toolkit nodes start from here.
     */
    abstract class Box implements UiNode {
        protected int x;
        protected int y;
        protected int width;
        protected int height;
        protected boolean shown = true;

        /** Places the node; returns it so a screen can place and add it in one line. */
        public Box at(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public void setVisible(boolean visible) {
            this.shown = visible;
        }

        @Override
        public boolean visible() {
            return shown;
        }

        @Override
        public Rect bounds() {
            return new Rect(x, y, width, height);
        }

        protected final boolean hovered(int mouseX, int mouseY) {
            return isMouseOver(mouseX, mouseY);
        }
    }
}
