package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The small grey button in a preview's corner that opens a menu of how the preview shows its
 * subject, such as "View ⌄": its label in Minecraft's font and a pixel chevron pointing down
 * after it, both centred on the face, which sinks onto its lip while pressed. It is
 * {@link UiButton#SMALL_HEIGHT} high and as wide as {@link #naturalWidth} says.
 */
public class UiViewButton extends UiButton {
    // The room between the label and the chevron, and the chevron's width
    private static final int CHEVRON_GAP = 3;
    private static final int CHEVRON_WIDTH = 5;
    private static final int PADDING = 8;

    private final Component text;

    public UiViewButton(Component label, Runnable onPress) {
        // The face is drawn by the button; the label and the chevron are drawn here
        super(Component.empty(), Style.SECONDARY, onPress);
        this.text = label;
        this.height = SMALL_HEIGHT;
    }

    /** The width the label, the chevron and the padding take. */
    @Override
    public int naturalWidth() {
        return Minecraft.getInstance().font.width(this.text) + CHEVRON_GAP + CHEVRON_WIDTH + 2 * PADDING;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        super.draw(graphics, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        // The face sinks one unit, its lip's height on a small button, while it is pressed
        boolean pressed = hovered(mouseX, mouseY) && enabled() && Minecraft.getInstance().mouseHandler.isLeftPressed();
        int sink = pressed ? 1 : 0;
        int faceTop = y + 1 + sink;
        int faceHeight = height - 3;
        int room = Math.max(0, width - 2 * PADDING - CHEVRON_GAP - CHEVRON_WIDTH);
        String label = Draw.cut(font, this.text.getString(), room);
        int contentWidth = font.width(label) + CHEVRON_GAP + CHEVRON_WIDTH;
        int left = x + (width - contentWidth) / 2;
        int textY = faceTop + (faceHeight - 8) / 2;
        Draw.string(graphics, font, label, left, textY, Theme.TEXT);
        // The chevron's three rows level with the middle of the lower-case letters
        PixelIcon.draw(graphics, UiSideRow.CHEVRON_DOWN, left + font.width(label) + CHEVRON_GAP, textY + 2,
                Theme.TEXT);
    }
}
