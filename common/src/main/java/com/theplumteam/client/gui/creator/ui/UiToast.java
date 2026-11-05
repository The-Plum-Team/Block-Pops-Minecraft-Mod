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

import java.util.List;

/**
 * A short message at the bottom centre of the creator screens, with an optional action such as
 * "Undo", drawn as Quick Skin's toasts are: a card in the buttons' style on a one-pixel lip, with
 * a blue strip on its left holding an "i". It fades in, shows for six seconds and fades out. One
 * shows at a time: a new message replaces the one before. It belongs to the creator rather than
 * to one screen, so a message about a save stays on screen when the creator moves to another
 * screen, and it goes away when the creator closes.
 *
 * <p>A message too long for one line in the band it is shown in, as on a small canvas, takes a
 * second line and grows upwards, since a toast has no tooltip to show what a cut would hide; only
 * what passes even two lines ends in "...".
 */
public final class UiToast {
    // How long a message shows, which the sidebar's status slot keeps a passing line for too
    static final long LIFETIME_MS = 6000;
    private static final long FADE_IN_MS = 300;
    private static final long FADE_OUT_MS = 500;
    private static final int PADDING = 6;
    private static final int MIN_WIDTH = 200;
    private static final int HEIGHT = 25;
    private static final int LIP_HEIGHT = 1;
    // A long message's lines, and how far apart they are
    private static final int MAX_LINES = 2;
    private static final int LINE_STEP = 10;
    // The height of a line of text, which the face centres
    private static final int TEXT_HEIGHT = 7;
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

    /**
     * The height of the message shown now in a band of a width, one line or two, so a screen can
     * keep it clear of what it would cover; 0 while none shows.
     */
    public static int height(int screenWidth) {
        UiToast shown = current();
        return shown == null ? 0 : shown.bounds(Minecraft.getInstance().font, screenWidth, 0).height();
    }

    private int width(Font font, int screenWidth) {
        int width = 4 + STRIP_WIDTH + font.width(text) + 2 * PADDING;
        if (action != null) {
            width += 12 + font.width(action);
        }
        return Math.min(Math.max(width, MIN_WIDTH), screenWidth - 16);
    }

    // The room the text has between the strip and the action, or the right edge
    private int textRoom(Font font, int width) {
        int room = width - 4 - STRIP_WIDTH - 2 * PADDING;
        if (action != null) {
            room -= 12 + font.width(action);
        }
        return Math.max(0, room);
    }

    // The message in the lines it takes in a toast of a width: one, or two when it does not fit
    private List<String> lines(Font font, int width) {
        String whole = text.getString();
        int room = textRoom(font, width);
        return font.width(whole) <= room ? List.of(whole) : Draw.fit(font, whole, Math.max(1, room), MAX_LINES);
    }

    private UiNode.Rect bounds(Font font, int screenWidth, int bottom) {
        int width = width(font, screenWidth);
        int height = HEIGHT + (lines(font, width).size() - 1) * LINE_STEP;
        return new UiNode.Rect((screenWidth - width) / 2, bottom - height, width, height);
    }

    // The top of a block of lines of text centred on the face, from inside the outline to above the lip
    private static int textTop(UiNode.Rect box, int lineCount) {
        int faceBottom = box.bottom() - 1 - LIP_HEIGHT;
        int blockHeight = TEXT_HEIGHT + (lineCount - 1) * LINE_STEP;
        return box.y() + 2 + (faceBottom - 1 - box.y() - 2 - blockHeight) / 2;
    }

    // The action, on one line in the face's middle however many lines the message takes
    private UiNode.Rect actionBounds(Font font, UiNode.Rect box) {
        if (action == null) {
            return UiNode.Rect.EMPTY;
        }
        int actionWidth = font.width(action);
        return new UiNode.Rect(box.right() - 2 - PADDING - actionWidth, textTop(box, 1) - 6, actionWidth, HEIGHT - 6);
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
        List<String> lines = lines(font, box.width());
        int textY = textTop(box, lines.size());
        int textRoom = textRoom(font, box.width());
        for (String line : lines) {
            UiText.draw(graphics, font, Draw.cut(font, line, textRoom), textX, textY, fade(Theme.TEXT, alpha));
            textY += LINE_STEP;
        }
        if (action != null) {
            UiNode.Rect actionBox = actionBounds(font, box);
            int actionY = textTop(box, 1);
            boolean hovered = actionBox.contains(mouseX, mouseY);
            int colour = fade(hovered ? Theme.LINK_HOVER : Theme.LINK, alpha);
            UiText.draw(graphics, font, action, actionBox.x(), actionY, colour);
            graphics.fill(actionBox.x(), actionY + 9, actionBox.right(), actionY + 10, colour);
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
