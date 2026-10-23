package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing helpers for the creator screens, made only of fills, text and items so they look the
 * same on every version: panels, outlines, dashed edges, badges, progress bars, and text cut or
 * wrapped to a width. Lines are drawn as one-pixel fills because the line helpers of
 * {@link GuiGraphics} were renamed in 26.1.
 */
public final class Draw {
    private static final String ELLIPSIS = "...";

    private Draw() {
    }

    /** A filled rectangle with a one-pixel outline. */
    public static void panel(GuiGraphics graphics, int x, int y, int width, int height, int fill, int outline) {
        graphics.fill(x, y, x + width, y + height, fill);
        outline(graphics, x, y, width, height, outline);
    }

    /** A one-pixel frame. */
    public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int colour) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + 1, colour);
        graphics.fill(x, y + height - 1, x + width, y + height, colour);
        graphics.fill(x, y + 1, x + 1, y + height - 1, colour);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
    }

    /** A one-pixel frame of dashes, as a drop zone's edge. */
    public static void dashed(GuiGraphics graphics, int x, int y, int width, int height, int colour) {
        dashed(graphics, x, y, width, height, colour, 4);
    }

    public static void dashed(GuiGraphics graphics, int x, int y, int width, int height, int colour, int dash) {
        int step = Math.max(1, dash) * 2;
        for (int i = 0; i < width; i += step) {
            int end = Math.min(x + width, x + i + dash);
            graphics.fill(x + i, y, end, y + 1, colour);
            graphics.fill(x + i, y + height - 1, end, y + height, colour);
        }
        for (int i = 0; i < height; i += step) {
            int end = Math.min(y + height, y + i + dash);
            graphics.fill(x, y + i, x + 1, end, colour);
            graphics.fill(x + width - 1, y + i, x + width, end, colour);
        }
    }

    /**
     * A small label in a box with a border of its text colour, such as a collection's
     * "PUBLISHED" badge.
     *
     * @return the badge's width
     */
    public static int badge(GuiGraphics graphics, Font font, Component text, int x, int y, int background,
                            int foreground) {
        int width = badgeWidth(font, text);
        panel(graphics, x, y, width, 12, background, foreground);
        graphics.drawString(font, text, x + 4, y + 2, foreground, false);
        return width;
    }

    public static int badgeWidth(Font font, Component text) {
        return font.width(text) + 8;
    }

    /**
     * A label on a fill with its corners cut off, such as a count beside a tab.
     *
     * @return the pill's width
     */
    public static int pill(GuiGraphics graphics, Font font, Component text, int x, int y, int background,
                           int foreground) {
        int width = font.width(text) + 8;
        graphics.fill(x + 1, y, x + width - 1, y + 12, background);
        graphics.fill(x, y + 1, x + 1, y + 11, background);
        graphics.fill(x + width - 1, y + 1, x + width, y + 11, background);
        graphics.drawString(font, text, x + 4, y + 2, foreground, false);
        return width;
    }

    /** A bar filled from the left by a fraction from 0 to 1. */
    public static void progressBar(GuiGraphics graphics, int x, int y, int width, int height, float fraction,
                                   int track, int fill) {
        graphics.fill(x, y, x + width, y + height, track);
        int filled = Math.round(width * Math.max(0.0f, Math.min(1.0f, fraction)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, fill);
        }
    }

    /** A small square dot centred on a point, as before a status line. */
    public static void dot(GuiGraphics graphics, int centerX, int centerY, int size, int colour) {
        int half = size / 2;
        graphics.fill(centerX - half, centerY - half, centerX - half + size, centerY - half + size, colour);
    }

    /** An item drawn at any size from its top left corner; 16 is the size of an inventory slot. */
    public static void item(GuiGraphics graphics, ItemStack stack, int x, int y, int size) {
        if (stack.isEmpty()) {
            return;
        }
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y);
        float scale = size / 16.0f;
        GuiPose.scale(graphics, scale, scale);
        graphics.renderItem(stack, 0, 0);
        GuiPose.pop(graphics);
    }

    /**
     * A whole texture of a known size, drawn into a rectangle, such as a file's thumbnail. The
     * caller keeps the size: below 1.21.5 a texture's size is only reachable through GL.
     */
    public static void texture(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                               int textureWidth, int textureHeight) {
        //? if >=1.21.6 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f,
                width, height, textureWidth, textureHeight, textureWidth, textureHeight);
        *///? } elif >=1.21.2 {
        /*graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, texture, x, y, 0.0f, 0.0f,
                width, height, textureWidth, textureHeight, textureWidth, textureHeight);
        *///? } else {
        // Transparent pixels need blending, which this blit does not switch on itself
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        graphics.blit(texture, x, y, width, height, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth,
                textureHeight);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        //? }
    }

    /** The largest size of a width and height that fits a box, keeping their proportions. */
    public static int[] fitInside(int width, int height, int boxWidth, int boxHeight) {
        if (width <= 0 || height <= 0) {
            return new int[]{boxWidth, boxHeight};
        }
        float scale = Math.min(boxWidth / (float) width, boxHeight / (float) height);
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    /** Text on one line, cut to a width as {@link #cut} cuts it. */
    public static void text(GuiGraphics graphics, Font font, String text, int x, int y, int width, int colour) {
        graphics.drawString(font, cut(font, text, width), x, y, colour, false);
    }

    /** Text centred on a point, cut to a width as {@link #cut} cuts it. */
    public static void centred(GuiGraphics graphics, Font font, String text, int centerX, int y, int width,
                               int colour) {
        String shown = cut(font, text, width);
        graphics.drawString(font, shown, centerX - font.width(shown) / 2, y, colour, false);
    }

    /**
     * Text wrapped to a width in at most the given number of lines, one line every
     * {@link Theme#LINE_HEIGHT}.
     *
     * @return the y below the last line drawn
     */
    public static int lines(GuiGraphics graphics, Font font, String text, int x, int y, int width, int maxLines,
                            int colour) {
        for (String line : fit(font, text, width, maxLines)) {
            graphics.drawString(font, line, x, y, colour, false);
            y += Theme.LINE_HEIGHT;
        }
        return y;
    }

    /**
     * Text that fits a width: the whole text when it fits, otherwise its start up to the
     * last whole word that fits with "..." after it. A text whose first word alone is too
     * wide, such as a long file path, is cut inside it.
     */
    public static String cut(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        int room = width - font.width(ELLIPSIS);
        if (room <= 0) {
            return font.plainSubstrByWidth(text, Math.max(0, width));
        }
        String start = font.plainSubstrByWidth(text, room);
        int space = start.lastIndexOf(' ');
        if (space > 0 && space >= start.length() / 2 && text.charAt(start.length()) != ' ') {
            // The cut fell inside a word: end at the space before it, unless that word is
            // most of what would show
            start = start.substring(0, space);
        }
        int end = start.length();
        while (end > 0 && " ,;:-".indexOf(start.charAt(end - 1)) >= 0) {
            end--;
        }
        return start.substring(0, end) + ELLIPSIS;
    }

    /**
     * Splits text into lines no wider than the width, breaking between words; a word wider
     * than a whole line, such as a file path, is broken where it has to be.
     */
    public static List<String> wrap(Font font, String text, int width) {
        return wrap(font, text, width, new ArrayList<>());
    }

    /** {@link #wrap(Font, String, int)}, adding to starts where each line begins in the text. */
    private static List<String> wrap(Font font, String text, int width, List<Integer> starts) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineStart = 0;
        int next = 0;
        while (next < text.length()) {
            int space = text.indexOf(' ', next);
            int wordEnd = space < 0 ? text.length() : space;
            int wordStart = next;
            String word = text.substring(wordStart, wordEnd);
            next = wordEnd + 1;
            if (word.isEmpty()) {
                continue;
            }
            String joined = line.length() == 0 ? word : line + " " + word;
            if (font.width(joined) <= width) {
                if (line.length() == 0) {
                    lineStart = wordStart;
                }
                line.setLength(0);
                line.append(joined);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                starts.add(lineStart);
                line.setLength(0);
            }
            while (font.width(word) > width) {
                String part = font.plainSubstrByWidth(word, width);
                if (part.isEmpty()) {
                    // Narrower than one character: one per line rather than none
                    part = word.substring(0, word.offsetByCodePoints(0, 1));
                }
                lines.add(part);
                starts.add(wordStart);
                word = word.substring(part.length());
                wordStart += part.length();
            }
            line.append(word);
            lineStart = wordStart;
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
            starts.add(lineStart);
        }
        return lines;
    }

    /**
     * Text wrapped to a width in at most the given number of lines; when it needs more, the
     * last line ends in "..." as {@link #cut} ends it.
     */
    public static List<String> fit(Font font, String text, int width, int maxLines) {
        List<Integer> starts = new ArrayList<>();
        List<String> lines = wrap(font, text, width, starts);
        int max = Math.max(1, maxLines);
        if (lines.size() <= max) {
            return lines;
        }
        List<String> fitted = new ArrayList<>(lines.subList(0, max - 1));
        // The rest of the text itself, so a word broken across lines gains no space
        fitted.add(cut(font, text.substring(starts.get(max - 1)), width));
        return fitted;
    }
}
