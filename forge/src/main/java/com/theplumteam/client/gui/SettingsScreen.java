package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
    private int panelHeight = 540;
    private int panelX;
    private int panelY;

    // Buttons and sliders
    private Button closeButton;
    private Button resetButton;

    // Star color sliders
    private ColorSlider starRedSlider;
    private ColorSlider starGreenSlider;
    private ColorSlider starBlueSlider;
    private OpacitySlider starOpacitySlider;

    // Background color sliders
    private ColorSlider bgRedSlider;
    private ColorSlider bgGreenSlider;
    private ColorSlider bgBlueSlider;

    // Panel opacity slider
    private OpacitySlider panelOpacitySlider;

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

        // Settings content area
        int contentX = this.panelX + 20;
        int contentY = this.panelY + 50;
        int sliderWidth = this.panelWidth - 40;
        int sliderHeight = 20;
        int spacing = 28;
        int sectionSpacing = 15;

        ClientConfig config = ClientConfig.getInstance();

        // === STAR COLOR SECTION ===
        contentY += sectionSpacing;

        // Star Red slider
        this.starRedSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Red: "),
                config.starColorR,
                value -> {
                    config.starColorR = value.floatValue();
                }
        );
        this.addRenderableWidget(this.starRedSlider);
        contentY += spacing;

        // Star Green slider
        this.starGreenSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Green: "),
                config.starColorG,
                value -> {
                    config.starColorG = value.floatValue();
                }
        );
        this.addRenderableWidget(this.starGreenSlider);
        contentY += spacing;

        // Star Blue slider
        this.starBlueSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Blue: "),
                config.starColorB,
                value -> {
                    config.starColorB = value.floatValue();
                }
        );
        this.addRenderableWidget(this.starBlueSlider);
        contentY += spacing;

        // Star Opacity slider
        this.starOpacitySlider = new OpacitySlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Opacity: "),
                config.starOpacity,
                value -> {
                    config.starOpacity = value.floatValue();
                }
        );
        this.addRenderableWidget(this.starOpacitySlider);
        contentY += spacing + sectionSpacing;

        // === BACKGROUND COLOR SECTION ===
        contentY += sectionSpacing;

        // Background Red slider
        this.bgRedSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Red: "),
                config.backgroundColorR,
                value -> {
                    config.backgroundColorR = value.floatValue();
                }
        );
        this.addRenderableWidget(this.bgRedSlider);
        contentY += spacing;

        // Background Green slider
        this.bgGreenSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Green: "),
                config.backgroundColorG,
                value -> {
                    config.backgroundColorG = value.floatValue();
                }
        );
        this.addRenderableWidget(this.bgGreenSlider);
        contentY += spacing;

        // Background Blue slider
        this.bgBlueSlider = new ColorSlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Blue: "),
                config.backgroundColorB,
                value -> {
                    config.backgroundColorB = value.floatValue();
                }
        );
        this.addRenderableWidget(this.bgBlueSlider);
        contentY += spacing + sectionSpacing;

        // === PANEL OPACITY SECTION ===
        contentY += sectionSpacing;

        // Panel Opacity slider
        this.panelOpacitySlider = new OpacitySlider(
                contentX, contentY,
                sliderWidth, sliderHeight,
                Component.literal("Panel Opacity: "),
                config.panelOpacity,
                value -> {
                    config.panelOpacity = value.floatValue();
                }
        );
        this.addRenderableWidget(this.panelOpacitySlider);
        contentY += spacing + 20;

        // Color preview boxes
        // (rendered in render method)

        // Button dimensions
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;
        int buttonSpacing = 10;

        // Calculate button positions (two buttons side by side)
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonsStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Reset button (left)
        this.resetButton = Button.builder(Component.literal("Reset"), button -> {
            ClientConfig.getInstance().resetColors();
            // Reset star color sliders
            this.starRedSlider.setValue(1.0);
            this.starGreenSlider.setValue(1.0);
            this.starBlueSlider.setValue(1.0);
            this.starOpacitySlider.setValue(0.20);
            // Reset background color sliders
            this.bgRedSlider.setValue(0.0);
            this.bgGreenSlider.setValue(0.0);
            this.bgBlueSlider.setValue(0.0);
            // Reset panel opacity slider
            this.panelOpacitySlider.setValue(0.90);
        })
                .bounds(buttonsStartX, buttonY, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.resetButton);

        // Close button (right)
        this.closeButton = Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(buttonsStartX + buttonWidth + buttonSpacing, buttonY, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.closeButton);
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

        // Draw main content panel background with configurable opacity
        ClientConfig config = ClientConfig.getInstance();
        int alpha = (int)(config.panelOpacity * 255);
        int panelBgColor = (alpha << 24) | 0x000000;  // Black with configurable alpha

        graphics.fill(this.panelX, this.panelY,
                     this.panelX + this.panelWidth,
                     this.panelY + this.panelHeight,
                     panelBgColor);

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

        // Draw section titles
        int sectionY = this.panelY + 35;
        graphics.drawString(this.font, "Star Color",
                           this.panelX + 20,
                           sectionY,
                           0xFFFFFF);

        int bgSectionY = this.panelY + 170;
        graphics.drawString(this.font, "Background Color",
                           this.panelX + 20,
                           bgSectionY,
                           0xFFFFFF);

        int panelSectionY = this.panelY + 305;
        graphics.drawString(this.font, "Panel Opacity",
                           this.panelX + 20,
                           panelSectionY,
                           0xFFFFFF);

        // Draw color preview boxes
        // Reuse config variable from above
        int previewSize = 35;
        int previewSpacing = 50;
        int previewStartX = this.panelX + (this.panelWidth - (previewSize * 2 + previewSpacing)) / 2;
        int previewY = this.panelY + 320;

        // Star color preview
        int starRed = (int)(config.starColorR * 255);
        int starGreen = (int)(config.starColorG * 255);
        int starBlue = (int)(config.starColorB * 255);
        int starColor = 0xFF000000 | (starRed << 16) | (starGreen << 8) | starBlue;

        graphics.fill(previewStartX - 1, previewY - 1, previewStartX + previewSize + 1, previewY + previewSize + 1, 0xFFFFFFFF);
        graphics.fill(previewStartX, previewY, previewStartX + previewSize, previewY + previewSize, starColor);
        graphics.drawCenteredString(this.font, "Stars",
                                   previewStartX + previewSize / 2,
                                   previewY + previewSize + 5,
                                   0xAAAAAA);

        // Background color preview
        int bgPreviewX = previewStartX + previewSize + previewSpacing;
        int bgRed = (int)(config.backgroundColorR * 255);
        int bgGreen = (int)(config.backgroundColorG * 255);
        int bgBlue = (int)(config.backgroundColorB * 255);
        int bgColor = 0xFF000000 | (bgRed << 16) | (bgGreen << 8) | bgBlue;

        graphics.fill(bgPreviewX - 1, previewY - 1, bgPreviewX + previewSize + 1, previewY + previewSize + 1, 0xFFFFFFFF);
        graphics.fill(bgPreviewX, previewY, bgPreviewX + previewSize, previewY + previewSize, bgColor);
        graphics.drawCenteredString(this.font, "Background",
                                   bgPreviewX + previewSize / 2,
                                   previewY + previewSize + 5,
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

    /**
     * Custom slider for color values (0.0 - 1.0)
     */
    private static class ColorSlider extends AbstractSliderButton {
        private final Component prefix;
        private final java.util.function.Consumer<Double> onValueChange;

        public ColorSlider(int x, int y, int width, int height, Component prefix,
                          double initialValue, java.util.function.Consumer<Double> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int intValue = (int)(this.value * 255);
            this.setMessage(Component.literal(prefix.getString() + intValue));
        }

        @Override
        protected void applyValue() {
            onValueChange.accept(this.value);
        }

        public void setValue(double newValue) {
            this.value = newValue;
            this.updateMessage();
        }
    }

    /**
     * Custom slider for opacity values (0.0 - 1.0)
     */
    private static class OpacitySlider extends AbstractSliderButton {
        private final Component prefix;
        private final java.util.function.Consumer<Double> onValueChange;

        public OpacitySlider(int x, int y, int width, int height, Component prefix,
                            double initialValue, java.util.function.Consumer<Double> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int percentage = (int)(this.value * 100);
            this.setMessage(Component.literal(prefix.getString() + percentage + "%"));
        }

        @Override
        protected void applyValue() {
            onValueChange.accept(this.value);
        }

        public void setValue(double newValue) {
            this.value = newValue;
            this.updateMessage();
        }
    }
}
