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
public class CollectionListWidget extends ObjectSelectionList<CollectionEntry> {
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
            double origScroll = this.getScrollAmount();
            this.setScrollAmount(origScroll * scale);

            // Render with scaled values
            super.render(graphics, scaledMouseX, scaledMouseY, partialTick);

            // Restore original values
            this.x0 = origX0;
            this.x1 = origX1;
            this.y0 = origY0;
            this.y1 = origY1;
            this.width = origWidth;
            this.height = origHeight;
            
            this.setScrollAmount(origScroll);
        } else {
            super.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.x1 - 6;
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        // Don't render default background - parent screen handles it
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            // Store and scale bounds (same as render)
            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            double origScroll = this.getScrollAmount();

            this.x0 = (int)(origX0 * scale);
            this.x1 = (int)(origX1 * scale);
            this.y0 = (int)(origY0 * scale);
            this.y1 = (int)(origY1 * scale);
            this.width = (int)(origWidth * scale);
            this.height = (int)(origHeight * scale);
            this.setScrollAmount(origScroll * scale);

            // Scale mouse coords (same as render)
            boolean result = super.mouseClicked(mouseX * scale, mouseY * scale, button);

            // Restore
            this.x0 = origX0; this.x1 = origX1; this.y0 = origY0; this.y1 = origY1;
            this.width = origWidth; this.height = origHeight;
            this.setScrollAmount(origScroll);
            return result;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // We use 15 pixels per scroll tick for smooth, consistent control
        this.setScrollAmount(this.getScrollAmount() - amount * 15.0);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();

            int origX0 = this.x0, origX1 = this.x1, origY0 = this.y0, origY1 = this.y1;
            int origWidth = this.width, origHeight = this.height;
            double origScroll = this.getScrollAmount();

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

    @Override
    protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
        // Disable default selection border - we handle selection rendering in CollectionEntry
    }
}