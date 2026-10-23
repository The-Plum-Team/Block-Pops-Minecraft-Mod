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
        draw(graphics, font, text, centerX - font.width(text) / 2, y, color);
    }

    public static void drawCentered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
        draw(graphics, font, text, centerX - font.width(text) / 2, y, color);
    }

    /** Draws {@code text} cut short with "..." when it is wider than {@code room}. */
    public static void drawFitted(GuiGraphics graphics, Font font, String text, int x, int y, int room, int color) {
        draw(graphics, font, fit(font, text, room), x, y, color);
    }

    /** {@code text}, cut short with "..." when it is wider than {@code room}. */
    public static String fit(Font font, String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
    }

    /**
     * {@code text} broken at spaces into lines no wider than {@code width}; a word wider than
     * that stays whole on its own line, for {@link #drawFitted} to cut.
     */
    public static java.util.List<String> wrap(Font font, String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && font.width(line + " " + word) > width) {
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
