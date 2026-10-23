package com.theplumteam.client.gui.style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slider in the menus' style: a sunk well for the track, with the part up to the handle filled
 * in the settings' green, under a raised handle like the buttons' face; the message is drawn
 * centred on the well with the icons' shadow. Subclasses keep vanilla's message and value hooks.
 */
public abstract class StyledSlider extends AbstractSliderButton {
    private static final int HANDLE_WIDTH = 8;
    private static final int FILL = 0xFF2E6A2E;
    private static final int FILL_HOVER = 0xFF3C8A3C;

    protected StyledSlider(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    @Override
    //? if >=26 {
    /*public void extractWidgetRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    *///? } else {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //? }
        boolean enabled = this.active;
        boolean hovered = enabled && (this.isHovered() || this.isFocused());
        int left = getX();
        int top = getY();
        int right = left + this.width;
        int bottom = top + this.height;
        Surfaces.well(graphics, left, top, right, bottom,
                !enabled ? Surfaces.BORDER_DISABLED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                hovered ? Surfaces.WELL_HOVER : Surfaces.WELL);

        int handleLeft = left + (int) (this.value * (this.width - HANDLE_WIDTH));
        if (enabled && handleLeft > left + 2) {
            graphics.fill(left + 2, top + 4, handleLeft, bottom - 2, hovered ? FILL_HOVER : FILL);
        }
        Surfaces.raised(graphics, handleLeft, top, handleLeft + HANDLE_WIDTH, bottom,
                !enabled ? Surfaces.BORDER_DISABLED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                !enabled ? Surfaces.FACE_DISABLED : hovered ? Surfaces.FACE_HOVER : Surfaces.FACE,
                Surfaces.LIP, 2);

        Font font = Minecraft.getInstance().font;
        String label = UiText.fit(font, getMessage().getString(), this.width - 8);
        UiText.drawCentered(graphics, font, label, left + this.width / 2, top + (this.height - 8) / 2,
                enabled ? Surfaces.TEXT : 0xFFA8A8A8);
    }
}
