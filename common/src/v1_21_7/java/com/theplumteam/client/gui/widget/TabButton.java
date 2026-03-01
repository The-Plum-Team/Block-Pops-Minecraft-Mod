package com.theplumteam.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Custom tab button widget for tabbed interfaces
 * Features selected/unselected states with hover effects
 */
public class TabButton extends Button {
    private boolean selected;

    // Tab styling - matches the frosted glass theme
    private static final int SELECTED_BG = 0xB0000000;      // Darker semi-transparent background
    private static final int UNSELECTED_BG = 0x60000000;    // Lighter semi-transparent background
    private static final int SELECTED_OUTLINE = 0xFFFFFFFF; // White outline for selected
    private static final int UNSELECTED_OUTLINE = 0x40FFFFFF; // Faint outline for unselected
    private static final int SELECTED_TEXT = 0xFFFFFFFF;      // White text (ARGB)
    private static final int UNSELECTED_TEXT = 0xFF999999;    // Gray text (ARGB)

    public TabButton(int x, int y, int width, int height, Component label, boolean selected, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.selected = selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return this.selected;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Determine colors based on selected state
        int bgColor = this.selected ? SELECTED_BG : UNSELECTED_BG;
        int outlineColor = this.selected ? SELECTED_OUTLINE : UNSELECTED_OUTLINE;
        int textColor = this.selected ? SELECTED_TEXT : UNSELECTED_TEXT;

        // Add hover effect for unselected tabs
        if (!this.selected && this.isHovered()) {
            bgColor = 0x80000000; // Slightly darker on hover
            textColor = 0xFFCCCCCC;  // Slightly brighter text on hover (ARGB)
        }

        // Draw tab background
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width,
                     this.getY() + this.height,
                     bgColor);

        // Draw outline
        // Top
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width, this.getY() + 1,
                     outlineColor);
        // Left
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + 1, this.getY() + this.height,
                     outlineColor);
        // Right
        graphics.fill(this.getX() + this.width - 1, this.getY(),
                     this.getX() + this.width, this.getY() + this.height,
                     outlineColor);

        // For selected tab, don't draw bottom border (connects to content area)
        if (!this.selected) {
            graphics.fill(this.getX(), this.getY() + this.height - 1,
                         this.getX() + this.width, this.getY() + this.height,
                         outlineColor);
        }

        // Draw centered text
        graphics.drawCenteredString(
            net.minecraft.client.Minecraft.getInstance().font,
            this.getMessage(),
            this.getX() + this.width / 2,
            this.getY() + (this.height - 8) / 2,
            textColor
        );
    }

    @Override
    protected boolean isValidClickButton(int button) {
        // Only allow left-click
        return button == 0;
    }
}
