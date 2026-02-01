package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Scrollable list widget for displaying figures with 3D models
 */
public class FigureListWidget extends ObjectSelectionList<FigureEntry> {
    @Nullable
    private FigureCollection currentCollection;
    private int xPosition;

    // SODIUM FIX: Store text rendering data to render from screen instead of entry
    public static final java.util.List<CollectionListWidget.TextRenderData> deferredTextRenders = new java.util.ArrayList<>();

    // Configuration values
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 150.0f;
    private float zRotation = 0.0f;
    private float xOffset = 0.0f;
    private float yOffset = 15.0f;
    private float zOffset = 0.0f;

    public FigureListWidget(Minecraft mc, int width, int height, int y, int entryHeight) {
        super(mc, width, height, y, entryHeight);
        this.xPosition = 0;
    }

    /**
     * Set the X position of this widget
     */
    public void setXPosition(int x) {
        this.xPosition = x;
        this.setX(x);
    }

    public void setCollection(@Nullable FigureCollection collection) {
        this.currentCollection = collection;
        this.clearEntries();

        if (collection != null) {
            List<FigureDefinition> figures = collection.getFigures();

            // Group figures into rows of 4
            List<FigureDefinition> currentRow = new java.util.ArrayList<>();
            for (int i = 0; i < figures.size(); i++) {
                currentRow.add(figures.get(i));

                // When we have 4 figures or reached the end, create a row entry
                if (currentRow.size() == 4 || i == figures.size() - 1) {
                    FigureEntry entry = new FigureEntry(currentRow, collection.getId());
                    entry.setConfiguration(modelScale, xRotation, yRotation, zRotation, xOffset, yOffset, zOffset);
                    this.addEntry(entry);
                    currentRow = new java.util.ArrayList<>();
                }
            }
        }

        // Reset scroll to top when collection changes
        this.setScrollAmount(0.0);
    }

    public void updateConfiguration(float modelScale, float xRotation, float yRotation, float zRotation,
                                   float xOffset, float yOffset, float zOffset) {
        this.modelScale = modelScale;
        this.xRotation = xRotation;
        this.yRotation = yRotation;
        this.zRotation = zRotation;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;

        // Update all entries
        for (FigureEntry entry : children()) {
            entry.setConfiguration(modelScale, xRotation, yRotation, zRotation, xOffset, yOffset, zOffset);
        }
    }

    public float getModelScale() {
        return modelScale;
    }

    public float getXRotation() {
        return xRotation;
    }

    public float getYRotation() {
        return yRotation;
    }

    public float getZRotation() {
        return zRotation;
    }

    public float getXOffset() {
        return xOffset;
    }

    public float getYOffset() {
        return yOffset;
    }

    public float getZOffset() {
        return zOffset;
    }

    @Nullable
    public FigureCollection getCurrentCollection() {
        return currentCollection;
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        // Don't render default dark menu background - we use custom panel background
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // SODIUM FIX: Clear deferred text before rendering entries
        deferredTextRenders.clear();

        // Render normally - entries will populate deferredTextRenders
        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        // SODIUM FIX: Render deferred text IMMEDIATELY after entries, while still in renderWidget context
        renderDeferredTextImmediate(graphics, minecraft.font);
    }

    /**
     * SODIUM FIX: Render deferred text immediately in the same context as entries
     */
    private void renderDeferredTextImmediate(GuiGraphics graphics, net.minecraft.client.gui.Font font) {
        // Set explicit render states for text
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        for (CollectionListWidget.TextRenderData data : deferredTextRenders) {
            // Draw text at high Z-level
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
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
        // We use 20 pixels per scroll tick for smooth, precise control
        // In 1.21.4+, getScrollAmount() renamed to scrollAmount()
        this.setScrollAmount(this.scrollAmount() - scrollY * 20.0);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
}
