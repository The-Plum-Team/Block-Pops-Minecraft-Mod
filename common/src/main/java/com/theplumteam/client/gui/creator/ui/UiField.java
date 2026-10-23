package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The creator's text field, as Quick Skin's search field: a sunk well ({@link Draw#well}) whose
 * border lightens under the mouse and brightens while the field has the keyboard, around a
 * borderless vanilla field. The vanilla field covers only the text area inside the well's padding,
 * and its text, which a borderless field draws along its top edge, is moved down to the well's
 * middle. A click inside the text area places the cursor as vanilla does, since the text still
 * starts at the vanilla field's left edge.
 *
 * <p>The position and width given to the constructor and to {@link #place} are the well's; the
 * vanilla field's own {@code getX()} and {@code getWidth()} are the text area's.
 */
public class UiField extends EditBox {
    /** Room between the well's outside and the text, on the left and the right. */
    public static final int TEXT_PADDING = 5;
    public static final int HEIGHT = 20;
    private static final int LINE_HEIGHT = 8;

    public UiField(Font font, int x, int y, int width, int height, Component narration) {
        super(font, x + TEXT_PADDING, y, Math.max(1, width - 2 * TEXT_PADDING), height, narration);
        setBordered(false);
    }

    /** Moves the well to a position and a width, keeping its height. */
    public void place(int x, int y, int width) {
        setX(x + TEXT_PADDING);
        setY(y);
        setWidth(Math.max(1, width - 2 * TEXT_PADDING));
    }

    /** The well's left edge. */
    public int wellX() {
        return getX() - TEXT_PADDING;
    }

    /** The well's width. */
    public int wellWidth() {
        return this.width + 2 * TEXT_PADDING;
    }

    @Override
    //? if >=26 {
    /*public void extractWidgetRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///? } else {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //? }
        if (!isVisible()) {
            return;
        }
        int left = wellX();
        int top = getY();
        boolean hovered = this.active && mouseX >= left && mouseX < left + wellWidth() && mouseY >= top
                && mouseY < top + this.height;
        if (this.active) {
            Draw.well(graphics, left, top, wellWidth(), this.height, hovered, isFocused());
        } else {
            Draw.well(graphics, left, top, wellWidth(), this.height, Theme.CONTROL_OUTLINE);
        }

        // The text in the middle of the well, below its shade
        int shift = (this.height - LINE_HEIGHT) / 2 + 1;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, 0.0f, shift);
        //? if >=26 {
        /*super.extractWidgetRenderState(graphics, mouseX, mouseY - shift, partialTick);
        *///? } else {
        super.renderWidget(graphics, mouseX, mouseY - shift, partialTick);
        //? }
        GuiPose.pop(graphics);
    }
}
