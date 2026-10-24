package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A rubber stamp over part of a screen: its lines in the pixel font, centred in a thick frame worn
 * through in places with a thin one inside it, all in translucent ink and turned by a few degrees.
 * It is drawn on a layer of its own, above everything drawn before it.
 */
public final class Stamp {
    // In half GUI units, which keep the thin frame thin: the padding round the lines, the space
    // between them, and the frames
    private static final int PADDING = 9;
    private static final int LINE_GAP = 6;
    private static final int FRAME = 3;
    private static final int INNER_FRAME_INSET = 5;

    private Stamp() {
    }

    /**
     * Draws the stamp centred on (centerX, centerY), turned clockwise by {@code degrees}. Each
     * line has its own size, in font pixels per half GUI unit.
     */
    public static void draw(GuiGraphics graphics, String[] lines, float[] sizes, float centerX, float centerY, int ink,
                            float degrees) {
        int[] widths = new int[lines.length];
        int textWidth = 0;
        int textHeight = LINE_GAP * (lines.length - 1);
        for (int i = 0; i < lines.length; i++) {
            widths[i] = PixelFont.width(Component.literal(lines[i]), sizes[i]);
            textWidth = Math.max(textWidth, widths[i]);
            textHeight += Math.round(PixelFont.CAPITALS_HEIGHT * sizes[i]);
        }
        int width = textWidth + 2 * PADDING;
        int height = textHeight + 2 * PADDING;
        int color = (ink & 0x00FFFFFF) | 0xD8000000;

        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.translate(graphics, centerX, centerY);
        GuiPose.rotate(graphics, degrees);
        GuiPose.scale(graphics, 0.5f, 0.5f);
        GuiPose.translate(graphics, -width / 2.0f, -height / 2.0f);
        // The thick frame, with a gap worn through its top, bottom and left sides
        int topGap = width * 3 / 10;
        int bottomGap = width * 63 / 100;
        int sideGap = height * 2 / 5;
        graphics.fill(0, 0, topGap, FRAME, color);
        graphics.fill(topGap + 3, 0, width, FRAME, color);
        graphics.fill(0, height - FRAME, bottomGap, height, color);
        graphics.fill(bottomGap + 4, height - FRAME, width, height, color);
        graphics.fill(0, FRAME, FRAME, sideGap, color);
        graphics.fill(0, sideGap + 3, FRAME, height - FRAME, color);
        graphics.fill(width - FRAME, FRAME, width, height - FRAME, color);
        Surfaces.outline(graphics, INNER_FRAME_INSET, INNER_FRAME_INSET, width - 2 * INNER_FRAME_INSET,
                height - 2 * INNER_FRAME_INSET, color);
        // The lines, centred; the font puts its capitals 3 font pixels above its origin
        int y = PADDING;
        for (int i = 0; i < lines.length; i++) {
            PixelFont.draw(graphics, PixelFont.text(lines[i]), (width - widths[i]) / 2.0f, y + 3 * sizes[i], sizes[i],
                    color, false);
            y += Math.round(PixelFont.CAPITALS_HEIGHT * sizes[i]) + LINE_GAP;
        }
        GuiPose.pop(graphics);
    }
}
