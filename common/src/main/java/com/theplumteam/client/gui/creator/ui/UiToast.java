package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A short message at the bottom centre of the creator screens, with an optional action such as
 * "Undo", drawn as Quick Skin's toasts are: a card in the buttons' style on a one-pixel lip, with
 * a blue strip on its left holding an "i". It fades in, shows for six seconds and fades out. One
 * shows at a time: a new message replaces the one before. It belongs to the creator rather than
 * to one screen, so a message about a save stays on screen when the creator moves to another
 * screen, and it goes away when the creator closes.
 */
public final class UiToast {
    private static final long LIFETIME_MS = 6000;
    private static final long FADE_IN_MS = 300;
    private static final long FADE_OUT_MS = 500;
    private static final int PADDING = 6;
    private static final int MIN_WIDTH = 200;
    private static final int HEIGHT = 25;
    private static final int LIP_HEIGHT = 1;
    /** The coloured strip on the left, holding the icon. */
    private static final int STRIP_WIDTH = 18;
    private static final int STRIP = 0xFF1F3A6E;
    private static final String[] ICON = {
            "...#...", ".......", "..##...", "...#...", "...#...", "...#...", "..###.."};

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
        int width = 4 + STRIP_WIDTH + font.width(text) + 2 * PADDING;
        if (action != null) {
            width += 12 + font.width(action);
        }
        width = Math.min(Math.max(width, MIN_WIDTH), screenWidth - 16);
        return new UiNode.Rect((screenWidth - width) / 2, bottom - HEIGHT, width, HEIGHT);
    }

    private UiNode.Rect actionBounds(Font font, UiNode.Rect box) {
        if (action == null) {
            return UiNode.Rect.EMPTY;
        }
        int actionWidth = font.width(action);
        return new UiNode.Rect(box.right() - 2 - PADDING - actionWidth, box.y() + 2, actionWidth, HEIGHT - 6);
    }

    /** Whether the message sits under the pointer, so what is below it ignores the pointer. */
    boolean covers(int screenWidth, int bottom, double mouseX, double mouseY) {
        return bounds(Minecraft.getInstance().font, screenWidth, bottom).contains(mouseX, mouseY);
    }

    /** Draws the message with its bottom edge at a height. */
    void draw(GuiGraphics graphics, int screenWidth, int bottom, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        UiNode.Rect box = bounds(font, screenWidth, bottom);
        long elapsed = Util.getMillis() - shownAt;
        float alpha = elapsed < FADE_IN_MS ? elapsed / (float) FADE_IN_MS
                : elapsed > LIFETIME_MS - FADE_OUT_MS ? (LIFETIME_MS - elapsed) / (float) FADE_OUT_MS : 1.0f;
        // Never quite clear, since from 1.21.6 a colour with alpha 0 draws nothing at all
        alpha = Math.max(0.05f, Math.min(1.0f, alpha));

        int left = box.x();
        int top = box.y();
        int right = box.right();
        int faceBottom = box.bottom() - 1 - LIP_HEIGHT;
        graphics.fill(left, top, right, box.bottom(), fade(Surfaces.OUTLINE, alpha));
        graphics.fill(left + 1, faceBottom, right - 1, box.bottom() - 1, fade(Surfaces.LIP, alpha));
        graphics.fill(left + 1, top + 1, right - 1, faceBottom, fade(Theme.TOAST_OUTLINE, alpha));
        graphics.fill(left + 2, top + 2, right - 2, faceBottom - 1, fade(Theme.TOAST, alpha));
        graphics.fill(left + 2, top + 2, left + 2 + STRIP_WIDTH, faceBottom - 1, fade(STRIP, alpha));
        PixelIcon.draw(graphics, ICON, left + 2 + (STRIP_WIDTH - PixelIcon.width(ICON)) / 2,
                top + 2 + (faceBottom - 1 - top - 2 - ICON.length) / 2, fade(Theme.TEXT, alpha));

        int textX = left + 2 + STRIP_WIDTH + PADDING;
        int textY = top + 2 + (faceBottom - 1 - top - 2 - 7) / 2;
        UiNode.Rect actionBox = actionBounds(font, box);
        int textRoom = (action != null ? actionBox.x() - 12 : right - 2 - PADDING) - textX;
        UiText.draw(graphics, font, Draw.cut(font, text.getString(), Math.max(0, textRoom)), textX, textY,
                fade(Theme.TEXT, alpha));
        if (action != null) {
            boolean hovered = actionBox.contains(mouseX, mouseY);
            int colour = fade(hovered ? Theme.LINK_HOVER : Theme.LINK, alpha);
            UiText.draw(graphics, font, action, actionBox.x(), textY, colour);
            graphics.fill(actionBox.x(), textY + 9, actionBox.right(), textY + 10, colour);
        }
    }

    private static int fade(int argb, float alpha) {
        int a = Math.max(1, Math.round((argb >>> 24) * alpha));
        return a << 24 | (argb & 0x00FFFFFF);
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
