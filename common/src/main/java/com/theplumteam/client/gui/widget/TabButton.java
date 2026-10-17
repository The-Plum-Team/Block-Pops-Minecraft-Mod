package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

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
    private static final int SELECTED_TEXT = 0xFFFFFFFF;      // White text
    private static final int UNSELECTED_TEXT = 0xFF999999;    // Gray text

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
            textColor = 0xFFCCCCCC;  // Slightly brighter text on hover
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
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        // Only allow left-click. The constant, not 0: 26.3 numbers the left button 1
        return button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    }

    /**
     * A folder tab standing on a panel's top edge, with an item icon before its label. The selected
     * tab is filled like the panel and leaves its bottom open, so it reads as part of the panel; the
     * panel leaves its own top edge out under it (see {@link #left()} and {@link #right()}).
     */
    public static class Folder extends TabButton {
        public static final int HEIGHT = 20;
        // An unselected tab sits lower, behind the panel
        private static final int UNSELECTED_DROP = 3;
        private static final int ICON_SIZE = 16;
        private static final float UNSELECTED_ICON_SCALE = 0.75f;
        private static final int PADDING = 5;

        private static final int OUTLINE = 0x60FFFFFF;
        private static final int UNSELECTED_BG = 0xFF1C1C1C;
        private static final int HOVERED_BG = 0xFF2A2A2A;
        private static final int SELECTED_TEXT = 0xFFFFFFFF;
        private static final int UNSELECTED_TEXT = 0xFFA0A0A0;
        private static final int HOVERED_TEXT = 0xFFCCCCCC;

        private final ItemStack icon;
        private final int selectedBg;

        /**
         * @param panelTop   the panel's top edge, which the tab stands on
         * @param selectedBg the panel's background colour, which the selected tab continues
         */
        public Folder(int x, int panelTop, Component label, ItemStack icon, boolean selected,
                      int selectedBg, OnPress onPress) {
            super(x, panelTop - HEIGHT + (selected ? 0 : UNSELECTED_DROP), widthOf(label),
                    HEIGHT - (selected ? 0 : UNSELECTED_DROP), label, selected, onPress);
            this.icon = icon;
            this.selectedBg = selectedBg;
        }

        public static int widthOf(Component label) {
            return PADDING + ICON_SIZE + 4 + net.minecraft.client.Minecraft.getInstance().font.width(label) + PADDING + 2;
        }

        public int left() {
            return getX();
        }

        public int right() {
            return getX() + width;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getX();
            int y = getY();
            int bottom = y + height;
            boolean selected = isSelected();
            boolean hovered = !selected && isHovered();

            graphics.fill(x, y, x + width, bottom, selected ? selectedBg : hovered ? HOVERED_BG : UNSELECTED_BG);
            graphics.fill(x, y, x + width, y + 1, OUTLINE);
            // The selected tab's sides run on into the panel's own border
            graphics.fill(x, y + 1, x + 1, bottom + (selected ? 1 : 0), OUTLINE);
            graphics.fill(x + width - 1, y + 1, x + width, bottom + (selected ? 1 : 0), OUTLINE);
            if (!selected) {
                graphics.fill(x + 1, bottom - 1, x + width - 1, bottom, OUTLINE);
            }

            int iconX = x + PADDING;
            int iconY = y + (height - ICON_SIZE) / 2;
            // An unselected tab's icon is drawn a little smaller, centred where the full one sits
            float iconScale = selected ? 1.0f : UNSELECTED_ICON_SCALE;
            GuiPose.push(graphics);
            GuiPose.translate(graphics, iconX + ICON_SIZE / 2.0f, iconY + ICON_SIZE / 2.0f);
            GuiPose.scale(graphics, iconScale, iconScale);
            GuiPose.translate(graphics, -ICON_SIZE / 2.0f, -ICON_SIZE / 2.0f);
            graphics.renderItem(icon, 0, 0);
            GuiPose.pop(graphics);

            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            int textColor = selected ? SELECTED_TEXT : hovered ? HOVERED_TEXT : UNSELECTED_TEXT;
            graphics.drawString(font, getMessage(), iconX + ICON_SIZE + 4, y + (height - 8) / 2 + 1, textColor, true);
        }
    }
}
