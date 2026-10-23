package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;

/**
 * Scrollable list widget for displaying available collections
 */
public class CollectionListWidget extends BoundedSelectionList<CollectionEntry> {
    private final CollectionSelectionScreen parentScreen;

    /** The screen this list belongs to. */
    public CollectionSelectionScreen getParentScreen() {
        return parentScreen;
    }

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
        this.clearEntries();
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
    /*protected void extractSelection(GuiGraphics graphics, CollectionEntry entry, int outerColor) {
    *///? } else {
    protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
    //? }
        // Disable default selection border - we handle selection rendering in CollectionEntry
    }
}
