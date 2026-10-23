package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
//? if <1.21.9 {
import net.minecraft.resources.ResourceLocation;
//? }

import java.util.Locale;

/**
 * The menus' blocky pixel font for titles and the main buttons' labels: Quick Skin's own capitals,
 * digits and accented Latin letters, from assets/blockpops/font/pixel.json. Characters it lacks
 * fall back to Minecraft's default font. It is drawn in upper case, at a whole number of screen
 * pixels per font pixel so it stays crisp.
 */
public final class PixelFont {
    //? if >=1.21.9 {
    /*private static final net.minecraft.network.chat.FontDescription FONT =
            new net.minecraft.network.chat.FontDescription.Resource(ResourceLocations.of("blockpops", "pixel"));
    *///? } else {
    private static final ResourceLocation FONT = ResourceLocations.of("blockpops", "pixel");
    //? }

    /** Height of the font's capitals, in font pixels. */
    public static final int CAPITALS_HEIGHT = 10;
    /**
     * Distance from the text origin to the middle of the font's capitals, in font pixels. A bitmap
     * glyph's top is drawn 7 - ascent below the origin: with an ascent of 14, the four rows kept
     * for accents start at -7 and the capitals run from -3 to 7, on the default font's baseline.
     */
    public static final float CAPITALS_MIDDLE = 2.0f;

    private PixelFont() {
    }

    /** Text in the pixel font, in upper case. */
    public static Component text(Component text) {
        return Component.literal(text.getString().toUpperCase(Locale.ROOT)).withStyle(style -> style.withFont(FONT));
    }

    public static Component text(String text) {
        return text(Component.literal(text));
    }

    /** The window's GUI scale, at least 1. */
    public static double guiScale() {
        // getGuiScale() is a double before 1.21.8 and an int from it; both convert
        return Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /**
     * The GUI units per font pixel nearest {@code guiPixelsPerFontPixel} that still draws each
     * font pixel as a whole number of screen pixels.
     */
    public static float crispScale(float guiPixelsPerFontPixel) {
        double guiScale = guiScale();
        int pixels = Math.max(1, (int) Math.round(guiScale * guiPixelsPerFontPixel));
        return (float) (pixels / guiScale);
    }

    /**
     * Draws text in the pixel font with its capitals centred on {@code (centerX, middleY)}, at the
     * largest crisp size up to {@code maxScale} GUI units per font pixel that fits {@code room},
     * or smaller still when even one screen pixel per font pixel does not fit.
     */
    public static void drawCentered(GuiGraphics graphics, Component label, int centerX, float middleY,
                                    int room, float maxScale, int color, boolean shadow) {
        Font font = Minecraft.getInstance().font;
        Component text = text(label);
        int textWidth = Math.max(1, font.width(text));
        double guiScale = guiScale();
        int pixels = Math.max(1, (int) Math.floor(maxScale * guiScale + 1e-4));
        while (pixels > 1 && textWidth * pixels / guiScale > room) {
            pixels--;
        }
        float crisp = (float) (pixels / guiScale);
        float scale = textWidth * crisp > room ? room / (float) textWidth : crisp;
        float x = Math.round(centerX - textWidth * scale / 2.0f);
        float y = Math.round(middleY - CAPITALS_MIDDLE * scale);
        draw(graphics, text, x, y, scale, color, shadow);
    }

    /**
     * Draws text already in the pixel font with its origin at {@code (x, y)} and the given GUI
     * units per font pixel.
     */
    public static void draw(GuiGraphics graphics, Component pixelText, float x, float y, float scale,
                            int color, boolean shadow) {
        Font font = Minecraft.getInstance().font;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y);
        GuiPose.scale(graphics, scale, scale);
        graphics.drawString(font, pixelText, 0, 0, color, shadow);
        GuiPose.pop(graphics);
    }

    /** The width of text in the pixel font at the given scale, in GUI units. */
    public static int width(Component label, float scale) {
        return (int) Math.ceil(Minecraft.getInstance().font.width(text(label)) * scale);
    }
}
