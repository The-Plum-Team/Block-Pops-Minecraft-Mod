package com.theplumteam.client.gui.widget;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Scrollable list widget for displaying figures with 3D models
 */
public class FigureListWidget extends ObjectSelectionList<FigureEntry> {
    @Nullable
    private FigureCollection currentCollection;

    // Configuration values
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 70.0f;
    private float zRotation = 0.0f;
    private float xOffset = -60.0f;
    private float yOffset = 15.0f;
    private float zOffset = 0.0f;

    public FigureListWidget(Minecraft mc, int width, int height, int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
    }

    public void setCollection(@Nullable FigureCollection collection) {
        this.currentCollection = collection;
        this.children().clear();

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
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.x1 - 6;
    }

    @Override
    protected void renderBackground(net.minecraft.client.gui.GuiGraphics graphics) {
        // Don't render default background
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // Smooth scrolling: use fixed pixel amount instead of itemHeight-based
        // Default scrolls by itemHeight/2 (45 pixels for 90px items) which is too jerky
        // We use 20 pixels per scroll tick for smooth, precise control
        this.setScrollAmount(this.getScrollAmount() - amount * 20.0);
        return true;
    }
}
