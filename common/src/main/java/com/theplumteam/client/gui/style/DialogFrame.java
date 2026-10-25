package com.theplumteam.client.gui.style;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The panel of the menus' dialogs, as Quick Skin draws its own: a card in the buttons' style (a
 * charcoal face in a thin border and a black outline, on a darker lip) with a coloured header strip
 * that holds a pixel icon and the title in the {@link PixelFont}. The header's colour says what the
 * dialog does: red to delete, blue to edit, green to create, slate for settings, purple for the
 * collections.
 */
public final class DialogFrame {
    /** Height of the header strip, from the panel's top edge to below its accent line. */
    public static final int HEADER_HEIGHT = 26;
    /** How large the dialogs' pixel button labels are drawn, so they leave room inside the border. */
    public static final float BUTTON_LABEL_SCALE = 0.8f;
    /** Room between the panel's edge and its content. */
    public static final int PADDING = 10;

    /** The header's strip and the line under it. */
    public enum Accent {
        DANGER(0xFF7A2020, 0xFFE04040),
        EDIT(0xFF1F3A6E, 0xFF4080FF),
        CREATE(0xFF1F5A2A, 0xFF40C060),
        SETTINGS(0xFF2E3A4A, 0xFF6F8FB0),
        COLLECTION(0xFF4A2A5E, 0xFFBB8FCE);

        public final int strip;
        public final int line;

        Accent(int strip, int line) {
            this.strip = strip;
            this.line = line;
        }
    }

    private DialogFrame() {
    }

    /** A dialog's button: the menus' button with its pixel label a little smaller to leave room. */
    public static StyledButton button(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new StyledButton(x, y, width, height, label, onPress).pixelLabel().labelScale(BUTTON_LABEL_SCALE);
    }

    /** Draws the panel, its header, the icon and the title. The body starts at {@link #bodyTop}. */
    public static void draw(GuiGraphics graphics, int x, int y, int width, int height, Accent accent,
                            String[] icon, Component title) {
        draw(graphics, x, y, width, height, accent, icon, title, Surfaces.DIALOG_FACE);
    }

    /** As {@link #draw}, with the panel's face in the given colour. */
    public static void draw(GuiGraphics graphics, int x, int y, int width, int height, Accent accent,
                            String[] icon, Component title, int face) {
        int right = x + width;
        int bottom = y + height;
        Surfaces.raised(graphics, x, y, right, bottom, Surfaces.BORDER, face, Surfaces.LIP, Surfaces.LIP_HEIGHT);

        // The header: the accent's strip, then a line in the accent's brighter colour
        int lineY = y + HEADER_HEIGHT - 1;
        graphics.fill(x + 2, y + 2, right - 2, lineY, accent.strip);
        graphics.fill(x + 2, lineY, right - 2, lineY + 1, accent.line);

        int middle = (y + 2 + lineY) / 2;
        int iconX = x + 10;
        PixelIcon.draw(graphics, icon, iconX, middle - icon.length / 2, Surfaces.TEXT);

        // The title in the pixel font, its capitals centred on the strip, smaller when a translation
        // would reach the close button
        int textX = iconX + PixelIcon.width(icon) + 6;
        int room = right - 6 - DialogCloseButton.SIZE - 6 - textX;
        float scale = Math.min(1.0f, room / (float) Math.max(1, PixelFont.width(title, 1.0f)));
        float textY = Math.round(middle + 0.5f - PixelFont.CAPITALS_MIDDLE * scale);
        PixelFont.draw(graphics, PixelFont.text(title), textX, textY, scale, Surfaces.TEXT, true);
    }

    /** The first row below the header. */
    public static int bodyTop(int y) {
        return y + HEADER_HEIGHT;
    }

    /** The last row above the lip, where the body ends. */
    public static int bodyBottom(int y, int height) {
        return y + height - 2 - Surfaces.LIP_HEIGHT;
    }
}
