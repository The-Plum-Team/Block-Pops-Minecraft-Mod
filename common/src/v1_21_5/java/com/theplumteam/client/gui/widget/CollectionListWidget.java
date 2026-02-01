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
    private int xPosition;

    public CollectionListWidget(CollectionSelectionScreen parentScreen, Minecraft mc,
                               int width, int height, int y, int entryHeight) {
        super(mc, width, height, y, entryHeight);
        this.parentScreen = parentScreen;
        this.xPosition = 0;
    }

    /**
     * Set the X position of this widget
     */
    public void setXPosition(int x) {
        this.xPosition = x;
        this.setX(x);
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
    protected void renderListBackground(GuiGraphics graphics) {
        // Don't render default dark menu background - we use custom panel background
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Sodium compatibility: flush before enabling scissor to ensure all previous draws complete
        graphics.flush();

        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        // Sodium compatibility: flush after rendering with scissor enabled to submit batched text
        graphics.flush();
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    public int getX() {
        return xPosition;
    }

    @Override
    public int getRowLeft() {
        return xPosition + 4;
    }

    // In 1.21.4+, getScrollbarPosition is no longer overridable
    // Scrollbar position is handled by the parent class

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // We use 15 pixels per scroll tick for smooth, consistent control
        // In 1.21.4+, getScrollAmount() renamed to scrollAmount()
        this.setScrollAmount(this.scrollAmount() - scrollY * 15.0);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
        // Disable default selection border - we handle selection rendering in CollectionEntry
    }
}
