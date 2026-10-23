package com.theplumteam.client.gui.creator.ui;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A short message at the bottom centre of the creator screens, with an optional action such as
 * "Undo" and a bar that counts down its six seconds. One shows at a time: a new message replaces
 * the one before. It belongs to the creator rather than to one screen, so a message about a save
 * stays on screen when the creator moves to another screen, and it goes away when the creator
 * closes.
 */
public final class UiToast {
    private static final long LIFETIME_MS = 6000;
    private static final int PADDING = 8;
    private static final int MIN_WIDTH = 210;
    private static final int HEIGHT = 20;

    @Nullable
    private static UiToast current;

    private final Component text;
    @Nullable
    private final Component action;
    @Nullable
    private final Runnable onAction;
    private final long shownAt;

    private UiToast(Component text, @Nullable Component action, @Nullable Runnable onAction) {
        this.text = text;
        this.action = action;
        this.onAction = onAction;
        this.shownAt = Util.getMillis();
    }

    /** Shows a message that offers no action. */
    public static void show(Component text) {
        show(text, null, null);
    }

    /** Shows a message with an action, such as "Undo", replacing any message shown now. */
    public static void show(Component text, @Nullable Component action, @Nullable Runnable onAction) {
        current = new UiToast(text, action, onAction);
    }

    public static void clear() {
        current = null;
    }

    /** The message shown now, or null once it has run its time. */
    @Nullable
    static UiToast current() {
        if (current != null && Util.getMillis() - current.shownAt >= LIFETIME_MS) {
            current = null;
        }
        return current;
    }

    private UiNode.Rect bounds(Font font, int screenWidth, int bottom) {
        int width = font.width(text) + 2 * PADDING;
        if (action != null) {
            width += 16 + font.width(action);
        }
        width = Math.min(Math.max(width, MIN_WIDTH), screenWidth - 16);
        return new UiNode.Rect((screenWidth - width) / 2, bottom - HEIGHT, width, HEIGHT);
    }

    private UiNode.Rect actionBounds(Font font, UiNode.Rect box) {
        if (action == null) {
            return UiNode.Rect.EMPTY;
        }
        int actionWidth = font.width(action);
        return new UiNode.Rect(box.right() - PADDING - actionWidth, box.y() + 2, actionWidth, HEIGHT - 6);
    }

    /** Whether the message sits under the pointer, so what is below it ignores the pointer. */
    boolean covers(int screenWidth, int bottom, double mouseX, double mouseY) {
        return bounds(Minecraft.getInstance().font, screenWidth, bottom).contains(mouseX, mouseY);
    }

    /** Draws the message with its bottom edge at a height. */
    void draw(GuiGraphics graphics, int screenWidth, int bottom, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        UiNode.Rect box = bounds(font, screenWidth, bottom);
        Draw.panel(graphics, box.x(), box.y(), box.width(), box.height(), Theme.TOAST, Theme.TOAST_OUTLINE);
        int textY = box.y() + 5;
        UiNode.Rect actionBox = actionBounds(font, box);
        int textRoom = (action != null ? actionBox.x() - 12 : box.right() - PADDING) - (box.x() + PADDING);
        Draw.text(graphics, font, text.getString(), box.x() + PADDING, textY, textRoom, Theme.TEXT);
        if (action != null) {
            boolean hovered = actionBox.contains(mouseX, mouseY);
            int colour = hovered ? Theme.GOLD_LIGHT : Theme.GOLD;
            graphics.drawString(font, action, actionBox.x(), textY, colour, false);
            graphics.fill(actionBox.x(), textY + 9, actionBox.right(), textY + 10, colour);
        }
        // The time left, as a bar that shrinks along the bottom edge
        float left = 1.0f - (Util.getMillis() - shownAt) / (float) LIFETIME_MS;
        Draw.progressBar(graphics, box.x() + 1, box.bottom() - 3, box.width() - 2, 2, left, Theme.TRACK, Theme.GOLD);
    }

    /** Runs the action when the click lands on it; a click anywhere on the message is taken. */
    boolean click(UiInput.Click click, int screenWidth, int bottom) {
        Font font = Minecraft.getInstance().font;
        UiNode.Rect box = bounds(font, screenWidth, bottom);
        if (!box.contains(click.x(), click.y())) {
            return false;
        }
        if (click.left() && onAction != null && actionBounds(font, box).contains(click.x(), click.y())) {
            current = null;
            onAction.run();
        }
        return true;
    }
}
