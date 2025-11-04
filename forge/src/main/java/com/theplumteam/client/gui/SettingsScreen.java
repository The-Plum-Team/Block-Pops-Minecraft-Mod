package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings screen displayed as a modal overlay
 */
public class SettingsScreen extends Screen {
    private final Screen parent;

    // Panel styling
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline
    private static final int TITLE_COLOR = 0xFFFFFF;          // White title

    // Panel dimensions
    private int panelWidth = 400;
    private int panelHeight = 250;
    private int panelX;
    private int panelY;

    // Buttons
    private Button closeButton;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Calculate centered panel position
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Button dimensions
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;

        // Calculate button position (centered)
        int buttonX = this.panelX + (this.panelWidth - buttonWidth) / 2;

        // Close button (centered at bottom)
        this.closeButton = Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.closeButton);

        // TODO: Add settings widgets here (checkboxes, sliders, etc.)
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Render parent screen in background (but don't pass mouse coordinates to prevent interaction)
        if (this.parent != null) {
            this.parent.render(graphics, -1, -1, partialTicks);
        }

        // Flush the parent screen rendering
        graphics.flush();

        // Disable scissor test to ensure our overlay covers everything
        RenderSystem.disableScissor();

        // Re-enable depth test and clear depth buffer to force our modal on top
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256, false); // Clear depth buffer only

        // Draw overlay over entire screen
        graphics.fill(0, 0, this.width, this.height, 0x70000000);

        // Draw main content panel background
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth,
                     this.panelY + this.panelHeight,
                     PANEL_BG);

        // Draw outline around content panel
        // Top
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + 1,
                     PANEL_OUTLINE);
        // Bottom
        graphics.fill(this.panelX, this.panelY + this.panelHeight - 1,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Left
        graphics.fill(this.panelX, this.panelY,
                     this.panelX + 1, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);
        // Right
        graphics.fill(this.panelX + this.panelWidth - 1, this.panelY,
                     this.panelX + this.panelWidth, this.panelY + this.panelHeight,
                     PANEL_OUTLINE);

        // Draw title
        graphics.drawCenteredString(this.font, this.title,
                                   this.panelX + this.panelWidth / 2,
                                   this.panelY + 15,
                                   TITLE_COLOR);

        // Draw placeholder text
        int contentY = this.panelY + 60;
        graphics.drawCenteredString(this.font, "Settings coming soon!",
                                   this.panelX + this.panelWidth / 2,
                                   contentY,
                                   0xAAAAAA);

        // Render our modal buttons and widgets
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if click is outside the panel
        if (mouseX < this.panelX || mouseX > this.panelX + this.panelWidth ||
            mouseY < this.panelY || mouseY > this.panelY + this.panelHeight) {
            // Click outside panel - close the modal
            this.onClose();
            return true;
        }
        // Click inside panel - handle normally
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // TODO: Save any settings changes here

        // Return to parent screen
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
