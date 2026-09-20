package com.theplumteam.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
//? if >=1.21 {
/*import net.minecraft.client.gui.navigation.ScreenRectangle;
*///? }

/** Keeps independent list edges when native list operations use the viewport height. */
public abstract class BoundedSelectionList<E extends ObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    public BoundedSelectionList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
        //? if >=1.21 {
        /*super(minecraft, width, height, top, itemHeight);
        this.x0 = 0;
        this.x1 = width;
        this.y0 = top;
        this.y1 = bottom;
        *///? } else {
        super(minecraft, width, height, top, bottom, itemHeight);
        //? }
    }

    //? if >=1.21 {
    /*protected int x0, x1, y0, y1;
    private boolean renderBackground = true;
    private boolean renderTopAndBottom = true;

    @Override
    public int getX() { return x0; }
    @Override
    public int getY() { return y0; }
    @Override
    public int getRight() { return x1; }
    @Override
    public int getBottom() { return y1; }

    public void setLeftPos(int left) {
        x0 = left;
        x1 = left + width;
    }

    @Override
    public ScreenRectangle getRectangle() {
        return new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
    }

    *///? }

    //? if >=1.21.2 {
    /*@Override
    public int maxScrollAmount() {
        return Math.max(0, contentHeight() - (y1 - y0 - 4));
    }
    *///? } elif >=1.21 {
    /*@Override
    public int getMaxScroll() {
        return Math.max(0, getMaxPosition() - (y1 - y0 - 4));
    }
    *///? }

    //? if >=1.21 {
    /*    public void setRenderBackground(boolean enabled) { renderBackground = enabled; }
    public void setRenderTopAndBottom(boolean enabled) { renderTopAndBottom = enabled; }

    protected void renderBackground(GuiGraphics graphics) {
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        if (renderBackground) super.renderListBackground(graphics);
    }

    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
        if (renderTopAndBottom) super.renderListSeparators(graphics);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int originalHeight = height;
        height = y1 - y0;
        try {
            renderBackground(graphics);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        } finally {
            height = originalHeight;
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int originalHeight = height;
        height = y1 - y0;
        try {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        } finally {
            height = originalHeight;
        }
    }

    @Override
    protected void centerScrollOn(E entry) {
        int originalHeight = height;
        height = y1 - y0;
        try {
            super.centerScrollOn(entry);
        } finally {
            height = originalHeight;
        }
    }
    *///? }
}
