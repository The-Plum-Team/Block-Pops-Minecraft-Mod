package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
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
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.x1 - 6;
    }

    @Override
    protected void renderBackground(net.minecraft.client.gui.GuiGraphics graphics) {
        // Don't render default background - parent screen handles it
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // Default scrolls by itemHeight/2 (18 pixels for 36px items)
        // We use 15 pixels per scroll tick for smooth, consistent control
        this.setScrollAmount(this.getScrollAmount() - amount * 15.0);
        return true;
    }

    @Override
    protected void renderSelection(net.minecraft.client.gui.GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
        // Disable default selection border - we handle selection rendering in CollectionEntry
    }
}
