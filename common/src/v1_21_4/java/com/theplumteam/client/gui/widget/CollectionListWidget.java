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

    // SODIUM FIX: Store text rendering data to render from screen instead of entry
    public static final java.util.List<TextRenderData> deferredTextRenders = new java.util.ArrayList<>();

    public static class TextRenderData {
        public final net.minecraft.network.chat.Component text;
        public final int x, y, color;
        public final boolean shadow;

        public TextRenderData(net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.shadow = shadow;
        }
    }

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
        // Try normal rendering with new logo approach
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * SODIUM FIX: Render deferred text immediately in the same context as entries
     */
    private void renderDeferredTextImmediate(GuiGraphics graphics, net.minecraft.client.gui.Font font) {
        // Set explicit render states for text
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        for (TextRenderData data : deferredTextRenders) {
            // Draw text ABOVE the rectangle (higher Z)
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400); // Render at high Z-level
            graphics.drawString(font, data.text, data.x, data.y, data.color, data.shadow);
            graphics.pose().popPose();
        }

        // Reset render state
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        deferredTextRenders.clear();
    }

    /**
     * SODIUM FIX: Public method for screen to call (now does nothing since we render immediately)
     */
    public static void renderDeferredText(GuiGraphics graphics, net.minecraft.client.gui.Font font) {
        // Text is now rendered immediately in renderWidget, this is just a no-op for compatibility
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
