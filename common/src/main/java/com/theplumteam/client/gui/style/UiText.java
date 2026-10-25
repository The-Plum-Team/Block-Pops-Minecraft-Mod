package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Text on the menus' dark faces, with the drop shadow of their pixel icons: the same dark grey one
 * pixel down and right. Minecraft's own shadow is the text's colour at a quarter of its
 * brightness, which on these dark faces is almost the face's own colour and disappears.
 */
public final class UiText {
    /** The icons' shadow grey; the text's own alpha is kept so fading text fades with it. */
    static final int SHADOW = 0xFF2A2A2A;

    private UiText() {
    }

    public static void draw(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        draw(graphics, font, Component.literal(text), x, y, color);
    }

    public static void draw(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
        int shadow = (color & 0xFF000000) | (SHADOW & 0x00FFFFFF);
        graphics.drawString(font, text, x + 1, y + 1, shadow, false);
        // Drawn at the same depth the shadow could cover the text where the glyphs overlap
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 0.03f);
        graphics.drawString(font, text, x, y, color, false);
        GuiPose.pop(graphics);
    }

    /** Draws {@code text} centred on {@code centerX}. */
    public static void drawCentered(GuiGraphics graphics, Font font, String text, int centerX, int y, int color) {
        draw(graphics, font, text, centerX - width(font, text) / 2, y, color);
    }

    public static void drawCentered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
        draw(graphics, font, text, centerX - width(font, text.getString()) / 2, y, color);
    }

    /**
     * The width {@code text} takes when drawn. In a right-to-left language the game shapes Arabic
     * letters into their joined forms before drawing them, and those are wider than the letters
     * {@link Font#width(String)} measures, so a line measured unshaped can run out of its place.
     */
    public static int width(Font font, String text) {
        return font.width(font.isBidirectional() ? font.bidirectionalShaping(text) : text);
    }

    /**
     * Draws {@code text} smaller when it is wider than {@code room}, on the same middle line, so a
     * long translation stays whole and inside its place. Text that fits is drawn as it is.
     */
    public static void drawShrunk(GuiGraphics graphics, Font font, String text, int x, int y, int room, int color) {
        int width = width(font, text);
        if (width <= room || room <= 0) {
            draw(graphics, font, text, x, y, color);
            return;
        }
        float scale = room / (float) width;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y + (1.0f - scale) * (font.lineHeight - 1) / 2.0f);
        GuiPose.scale(graphics, scale, scale);
        draw(graphics, font, text, 0, 0, color);
        GuiPose.pop(graphics);
    }

    /** {@link #drawShrunk} centred on {@code centerX}. */
    public static void drawCenteredShrunk(GuiGraphics graphics, Font font, String text, int centerX, int y, int room,
                                          int color) {
        int width = width(font, text);
        drawShrunk(graphics, font, text, centerX - (room > 0 ? Math.min(width, room) : width) / 2, y, room, color);
    }

    /** Draws {@code text} cut short with "..." when it is wider than {@code room}. */
    public static void drawFitted(GuiGraphics graphics, Font font, String text, int x, int y, int room, int color) {
        draw(graphics, font, fit(font, text, room), x, y, color);
    }

    /** {@code text}, cut short with "..." when it is wider than {@code room}. */
    public static String fit(Font font, String text, int room) {
        if (width(font, text) <= room) {
            return text;
        }
        String head = font.plainSubstrByWidth(text, Math.max(0, room - font.width("...")));
        while (!head.isEmpty() && width(font, head + "...") > room) {
            head = head.substring(0, head.length() - 1);
        }
        return head + "...";
    }

    /**
     * {@code text} broken at spaces into lines no wider than {@code width}. A run wider than a
     * line is broken wherever the width runs out: Chinese, Japanese and Thai put no spaces
     * between words, so a whole sentence of theirs is one such run.
     */
    public static java.util.List<String> wrap(Font font, String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            while (width(font, word) > width) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String head = font.plainSubstrByWidth(word, Math.max(1, width));
                while (head.length() > 1 && width(font, head) > width) {
                    head = head.substring(0, head.length() - 1);
                }
                if (head.isEmpty()) {
                    head = word.substring(0, Character.charCount(word.codePointAt(0)));
                }
                lines.add(head);
                word = word.substring(head.length());
            }
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && width(font, line + " " + word) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
