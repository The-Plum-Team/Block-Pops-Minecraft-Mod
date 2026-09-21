package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;

/**
 * Scrollable list widget for displaying available collections
 */
public class CollectionListWidget extends BoundedSelectionList<CollectionEntry> {
    private final CollectionSelectionScreen parentScreen;

    public CollectionListWidget(CollectionSelectionScreen parentScreen, Minecraft mc,
                               int width, int height, int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
        this.parentScreen = parentScreen;
    }

    /**
     * Add a collection entry to the list
     */
    public void addCollectionEntry(FigureCollection collection) {
        this.addEntry(new CollectionEntry(this, collection));
    }

    /**
     * Clear all entries
     */
    public void clearAllEntries() {
        this.children().clear();
    }

    /**
     * Called when a collection is selected
     */
    public void onCollectionSelected(CollectionEntry entry) {
        parentScreen.onCollectionSelected(entry);
    }

    /**
     * Find and select entry by collection ID
     */
    public void selectByCollectionId(String collectionId) {
        if (collectionId == null || collectionId.isEmpty()) {
            return;
        }

        for (CollectionEntry entry : children()) {
            if (entry.getCollection().getId().equals(collectionId)) {
                setSelected(entry);
                ensureVisible(entry);
                break;
            }
        }
    }

    @Override
    //? if >=1.21 {
    /*public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///? } else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //? }
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            // Store original values (virtual coordinates)
            int origX0 = this.x0;
            int origX1 = this.x1;
            int origY0 = this.y0;
            int origY1 = this.y1;
            int origWidth = this.width;
            int origHeight = this.height;
            // Note: itemHeight is final, entries handle their own scaling

            // Scale bounds for correct scissor positioning
            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);


            // Transform mouse coordinates for hit detection
            int scaledMouseX = (int)(mouseX * scale);
            int scaledMouseY = (int)(mouseY * scale);

            // Scale scroll amount for correct entry positioning
            //? if >=1.21.2 {
            /*double origScroll = this.scrollAmount();
            *///? } else {
            double origScroll = this.getScrollAmount();
            //? }
            this.setScrollAmount(origScroll * scale);

            // Render with scaled values
            //? if >=1.21 {
            /*super.renderWidget(graphics, scaledMouseX, scaledMouseY, partialTick);
            *///? } else {
            super.render(graphics, scaledMouseX, scaledMouseY, partialTick);
            //? }

            // Restore original values
            this.x0 = origX0;
            this.x1 = origX1;
            this.y0 = origY0;
            this.y1 = origY1;
            this.width = origWidth;
            this.height = origHeight;

            this.setScrollAmount(origScroll);
        } else {
            //? if >=1.21 {
            /*super.renderWidget(graphics, mouseX, mouseY, partialTick);
            *///? } else {
            super.render(graphics, mouseX, mouseY, partialTick);
            //? }
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    //? if >=1.21.2 {
    /*protected int scrollBarX() {
    *///? } else {
    protected int getScrollbarPosition() {
    //? }
        return this.x1 - 6;
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        // Don't render default background - parent screen handles it
    }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            //? if >=1.21.2 {
            /^double origScroll = this.scrollAmount();
            ^///? } else {
            double origScroll = this.getScrollAmount();
            //? }

            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);
            this.setScrollAmount(origScroll * scale);

            boolean result = super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), bpDouble);

            this.x0 = origX0; this.x1 = origX1; this.y0 = origY0; this.y1 = origY1;
            this.width = origWidth; this.height = origHeight;
            this.setScrollAmount(origScroll);
            return result;
        }
        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            //? if >=1.21.2 {
            /*double origScroll = this.scrollAmount();
            *///? } else {
            double origScroll = this.getScrollAmount();
            //? }

            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);
            this.setScrollAmount(origScroll * scale);

            boolean result = super.mouseClicked(mouseX * scale, mouseY * scale, button);

            this.x0 = origX0; this.x1 = origX1; this.y0 = origY0; this.y1 = origY1;
            this.width = origWidth; this.height = origHeight;
            this.setScrollAmount(origScroll);
            return result;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    @Override
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    //? }
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // We use 15 pixels per scroll tick for smooth, consistent control
        //? if >=1.21.2 {
        /*this.setScrollAmount(this.scrollAmount() - amount * 15.0);
        *///? } else {
        this.setScrollAmount(this.getScrollAmount() - amount * 15.0);
        //? }
        return true;
    }

    @Override
    //? if >=26 {
    /*public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent bpEvent, double dragX, double dragY) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            //? if >=1.21.2 {
            /^double origScroll = this.scrollAmount();
            ^///? } else {
            double origScroll = this.getScrollAmount();
            //? }

            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);
            this.setScrollAmount(origScroll * scale);

            boolean result = super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), dragX, dragY);

            this.x0 = origX0; this.x1 = origX1; this.y0 = origY0; this.y1 = origY1;
            this.width = origWidth; this.height = origHeight;
            this.setScrollAmount(origScroll);
            return result;
        }
        return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), dragX, dragY);
    }
    *///? } else {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            //? if >=1.21.2 {
            /*double origScroll = this.scrollAmount();
            *///? } else {
            double origScroll = this.getScrollAmount();
            //? }

            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);
            this.setScrollAmount(origScroll * scale);

            boolean result = super.mouseDragged(mouseX * scale, mouseY * scale, button, dragX * scale, dragY * scale);

            this.x0 = origX0; this.x1 = origX1; this.y0 = origY0; this.y1 = origY1;
            this.width = origWidth; this.height = origHeight;
            this.setScrollAmount(origScroll);
            return result;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    //? }

    @Override
    //? if >=26 {
    /*protected void extractSelection(GuiGraphics graphics, CollectionEntry entry, int outerColor) {
    *///? } else {
    protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
    //? }
        // Disable default selection border - we handle selection rendering in CollectionEntry
    }
}
