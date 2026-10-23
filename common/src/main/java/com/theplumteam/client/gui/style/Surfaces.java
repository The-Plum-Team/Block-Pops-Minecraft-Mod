package com.theplumteam.client.gui.style;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The menus' surfaces, in Quick Skin's dark-mode Bedrock style: a raised face (a charcoal face in
 * a thin grey border and a black outline, on a darker lip that gives it depth) for buttons, cards
 * and tiles, and a sunk well (a dark field with a shaded top edge) for fields, tracks and lists.
 */
public final class Surfaces {
    public static final int OUTLINE = 0xFF0E0E0F;
    public static final int BORDER = 0xFF6E6F73;
    public static final int BORDER_HOVER = 0xFF8D8E93;
    public static final int BORDER_SELECTED = 0xFFC6C7CC;
    public static final int BORDER_DISABLED = 0xFF46474A;
    public static final int FACE = 0xFF3A3B3E;
    public static final int FACE_HOVER = 0xFF4A4B4F;
    public static final int FACE_DISABLED = 0xFF2B2C2E;
    public static final int LIP = 0xFF1C1D1F;
    public static final int LIP_DISABLED = 0xFF161617;
    /** Height of the lip along a raised surface's bottom edge. */
    public static final int LIP_HEIGHT = 3;

    public static final int WELL = 0xFF1E1F21;
    public static final int WELL_HOVER = 0xFF2A2B2E;
    private static final int WELL_SHADE = 0xFF151617;
    private static final int WELL_SHADE_HEIGHT = 2;

    /** A dialog's face and the darker face of its sidebar's content. */
    public static final int DIALOG_FACE = 0xFF2E2F32;
    public static final int PANEL_FACE = 0xFF26272A;
    /** A selected card's or tile's border: the blue of Quick Skin's selection. */
    public static final int SELECTED = 0xFF6FA0FF;
    /** A hairline between rows on a dialog's face. */
    public static final int ROW_LINE = 0xFF323336;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFB0B0B0;
    public static final int TEXT_MUTED = 0xFF8A8A8A;

    private Surfaces() {
    }

    /** A raised surface with the usual border, face and lip. */
    public static void raised(GuiGraphics graphics, int left, int top, int right, int bottom, int border, int face) {
        raised(graphics, left, top, right, bottom, border, face, LIP, LIP_HEIGHT);
    }

    /**
     * A raised surface: the outline, the lip along the bottom, then the border and the face
     * resting on the lip.
     *
     * @return the bottom edge of the face, above the lip
     */
    public static int raised(GuiGraphics graphics, int left, int top, int right, int bottom,
                             int border, int face, int lip, int lipHeight) {
        graphics.fill(left, top, right, bottom, OUTLINE);
        int faceBottom = bottom - 1 - lipHeight;
        if (lipHeight > 0) {
            graphics.fill(left + 1, faceBottom, right - 1, bottom - 1, lip);
        }
        graphics.fill(left + 1, top + 1, right - 1, faceBottom, border);
        graphics.fill(left + 2, top + 2, right - 2, faceBottom - 1, face);
        return faceBottom;
    }

    /** A sunk well: the outline, a border, the dark field and the shade along its top edge. */
    public static void well(GuiGraphics graphics, int left, int top, int right, int bottom, int border, int well) {
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, border);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, well);
        graphics.fill(left + 2, top + 2, right - 2, Math.min(bottom - 2, top + 2 + WELL_SHADE_HEIGHT), WELL_SHADE);
    }

    /** A one-pixel outline whose sides stop short of its top and bottom lines. */
    public static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /**
     * A translucent dark backdrop behind a group of controls on the menus' starry background, as
     * Quick Skin's import zone: dark enough to read text on the brightest collection colours, with
     * a bracket at each corner.
     */
    public static void backdrop(GuiGraphics graphics, int left, int top, int right, int bottom, int alpha) {
        graphics.fill(left, top, right, bottom, alpha << 24 | 0x101012);
        int corner = 6;
        int color = 0x50FFFFFF;
        graphics.fill(left, top, left + corner, top + 1, color);
        graphics.fill(left, top + 1, left + 1, top + corner, color);
        graphics.fill(right - corner, top, right, top + 1, color);
        graphics.fill(right - 1, top + 1, right, top + corner, color);
        graphics.fill(left, bottom - 1, left + corner, bottom, color);
        graphics.fill(left, bottom - corner, left + 1, bottom - 1, color);
        graphics.fill(right - corner, bottom - 1, right, bottom, color);
        graphics.fill(right - 1, bottom - corner, right, bottom - 1, color);
    }
}
