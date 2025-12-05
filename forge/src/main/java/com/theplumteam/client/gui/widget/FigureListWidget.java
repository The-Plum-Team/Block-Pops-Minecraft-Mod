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

    // Configuration values
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 150.0f;
    private float zRotation = 0.0f;
    private float xOffset = 14.0f;
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
        // Don't render default background
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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

            boolean result = super.mouseClicked(mouseX * scale, mouseY * scale, button);

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
        // We use 20 pixels per scroll tick for smooth, precise control
        this.setScrollAmount(this.getScrollAmount() - amount * 20.0);
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
}