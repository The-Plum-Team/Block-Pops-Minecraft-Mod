package com.theplumteam.client.gui.style;

import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * The close button at the right end of a dialog's header: a square in the header's colours with a
 * light border and a bold X, lightening under the mouse. Its message is the dialog's close label,
 * shown as its tooltip, so the button is found and narrated by it.
 */
public class DialogCloseButton extends Button {
    /** The button's size, square. */
    public static final int SIZE = 17;

    private static final int BORDER = 0xFF6F8FB0;
    private static final int BORDER_HOVER = 0xFF9DB8D6;
    private static final int FACE = 0xFF3A4656;
    private static final int FACE_HOVER = 0xFF4A5A70;

    public DialogCloseButton(int x, int y, Component label, OnPress onPress) {
        super(x, y, SIZE, SIZE, label, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(label));
    }

    /** The button at the right end of the header of a dialog at {@code (dialogX, dialogY)}. */
    public static DialogCloseButton forDialog(int dialogX, int dialogY, int dialogWidth, Component label, OnPress onPress) {
        return new DialogCloseButton(dialogX + dialogWidth - 6 - SIZE,
                dialogY + (DialogFrame.HEADER_HEIGHT - SIZE) / 2, label, onPress);
    }

    @Override
    //? if >=26 {
    /*protected void extractContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    *///? } else {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //? }
        boolean hovered = this.active && this.isHovered();
        int x = this.getX();
        int y = this.getY();
        graphics.fill(x, y, x + this.width, y + this.height, Surfaces.OUTLINE);
        graphics.fill(x + 1, y + 1, x + this.width - 1, y + this.height - 1, hovered ? BORDER_HOVER : BORDER);
        graphics.fill(x + 2, y + 2, x + this.width - 2, y + this.height - 2, hovered ? FACE_HOVER : FACE);
        PixelIcon.drawCentered(graphics, PixelIcon.CLOSE, x, y, this.width, this.height, Surfaces.TEXT);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        UiSounds.play(soundManager, ModSounds.CLICK);
    }

    @Override
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        return button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    }
}
