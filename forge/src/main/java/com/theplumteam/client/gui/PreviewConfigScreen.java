package com.theplumteam.client.gui;

import com.theplumteam.client.gui.widget.FigureListWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * Configuration modal for adjusting 3D model preview settings
 */
public class PreviewConfigScreen extends Screen {
    private final Screen parent;
    private final FigureListWidget figureListWidget;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth = 320;
    private int panelHeight = 400; // Increased to fit all sliders

    // Sliders
    private ConfigSlider scaleSlider;

    // Rotation sliders
    private ConfigSlider xRotationSlider;
    private ConfigSlider yRotationSlider;
    private ConfigSlider zRotationSlider;

    // Position offset sliders
    private ConfigSlider xOffsetSlider;
    private ConfigSlider yOffsetSlider;
    private ConfigSlider zOffsetSlider;

    // Buttons
    private Button resetButton;
    private Button closeButton;

    public PreviewConfigScreen(Screen parent, FigureListWidget figureListWidget) {
        super(Component.literal("Preview Configuration"));
        this.parent = parent;
        this.figureListWidget = figureListWidget;
    }

    @Override
    protected void init() {
        super.init();

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;

        int padding = 10;
        int sliderHeight = 20;
        int spacing = 8;
        int labelHeight = 12;

        int currentY = panelY + padding + 20; // Start below title

        // Scale Slider (0.1-1.0)
        currentY += labelHeight;
        scaleSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("Scale: "),
            Component.empty(),
            0.1,
            1.0,
            figureListWidget.getModelScale(),
            0.01,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(scaleSlider);
        currentY += sliderHeight + spacing;

        // X Rotation Slider (-180 to 180 degrees)
        currentY += labelHeight;
        xRotationSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("X Rotation: "),
            Component.literal("°"),
            -180.0,
            180.0,
            figureListWidget.getXRotation(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(xRotationSlider);
        currentY += sliderHeight + spacing;

        // Y Rotation Slider (0-360 degrees)
        currentY += labelHeight;
        yRotationSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("Y Rotation: "),
            Component.literal("°"),
            0.0,
            360.0,
            figureListWidget.getYRotation(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(yRotationSlider);
        currentY += sliderHeight + spacing;

        // Z Rotation Slider (-180 to 180 degrees)
        currentY += labelHeight;
        zRotationSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("Z Rotation: "),
            Component.literal("°"),
            -180.0,
            180.0,
            figureListWidget.getZRotation(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(zRotationSlider);
        currentY += sliderHeight + spacing;

        // X Offset Slider (-100 to 100)
        currentY += labelHeight;
        xOffsetSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("X Offset: "),
            Component.empty(),
            -100.0,
            100.0,
            figureListWidget.getXOffset(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(xOffsetSlider);
        currentY += sliderHeight + spacing;

        // Y Offset Slider (-50 to 50)
        currentY += labelHeight;
        yOffsetSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("Y Offset: "),
            Component.empty(),
            -50.0,
            50.0,
            figureListWidget.getYOffset(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(yOffsetSlider);
        currentY += sliderHeight + spacing;

        // Z Offset Slider (-50 to 50)
        currentY += labelHeight;
        zOffsetSlider = new ConfigSlider(
            panelX + padding,
            currentY,
            panelWidth - (padding * 2),
            sliderHeight,
            Component.literal("Z Offset: "),
            Component.empty(),
            -50.0,
            50.0,
            figureListWidget.getZOffset(),
            1.0,
            value -> updateAllConfiguration()
        );
        this.addRenderableWidget(zOffsetSlider);
        currentY += sliderHeight + spacing + 10;

        // Bottom buttons
        int buttonWidth = (panelWidth - (padding * 3)) / 2;
        int buttonY = panelY + panelHeight - padding - 20;

        // Reset button
        resetButton = Button.builder(Component.literal("Reset to Defaults"), button -> {
            resetToDefaults();
        }).bounds(panelX + padding, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(resetButton);

        // Close button
        closeButton = Button.builder(Component.literal("Close"), button -> {
            this.onClose();
        }).bounds(panelX + padding + buttonWidth + padding, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(closeButton);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render parent screen in the background so preview is visible
        if (parent != null) {
            parent.render(graphics, -1, -1, partialTick);
        }

        // Light darken overlay (more transparent to see preview)
        graphics.fillGradient(0, 0, this.width, this.height, 0x40000000, 0x40000000);

        // Draw panel background (solid to remain readable)
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0000000);

        // Draw panel border
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x80FFFFFF);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x80FFFFFF);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0x80FFFFFF);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0x80FFFFFF);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelY + 8, 0xFFFFFF);

        // Render widgets
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void updateAllConfiguration() {
        figureListWidget.updateConfiguration(
            scaleSlider.getCurrentValue().floatValue(),
            xRotationSlider.getCurrentValue().floatValue(),
            yRotationSlider.getCurrentValue().floatValue(),
            zRotationSlider.getCurrentValue().floatValue(),
            xOffsetSlider.getCurrentValue().floatValue(),
            yOffsetSlider.getCurrentValue().floatValue(),
            zOffsetSlider.getCurrentValue().floatValue()
        );
    }

    private void resetToDefaults() {
        // Reset to defaults
        scaleSlider.setValue(1.0f);
        xRotationSlider.setValue(0.0f);
        yRotationSlider.setValue(70.0f);
        zRotationSlider.setValue(0.0f);
        xOffsetSlider.setValue(-60.0f);
        yOffsetSlider.setValue(15.0f);
        zOffsetSlider.setValue(0.0f);

        // Update the figure list
        updateAllConfiguration();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Custom slider widget with configurable range and callback
     */
    private static class ConfigSlider extends AbstractSliderButton {
        private final Component prefix;
        private final Component suffix;
        private final double minValue;
        private final double maxValue;
        private final double step;
        private final java.util.function.Consumer<Double> onChange;

        public ConfigSlider(int x, int y, int width, int height,
                          Component prefix, Component suffix,
                          double minValue, double maxValue,
                          double initialValue, double step,
                          java.util.function.Consumer<Double> onChange) {
            super(x, y, width, height, Component.empty(),
                  (initialValue - minValue) / (maxValue - minValue));
            this.prefix = prefix;
            this.suffix = suffix;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
            this.onChange = onChange;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double currentValue = getCurrentValue();
            String formatted;

            // Format based on step size
            if (step >= 1.0) {
                formatted = String.format("%.0f", currentValue);
            } else if (step >= 0.1) {
                formatted = String.format("%.1f", currentValue);
            } else {
                formatted = String.format("%.2f", currentValue);
            }

            this.setMessage(Component.empty()
                .append(prefix)
                .append(formatted)
                .append(suffix));
        }

        @Override
        protected void applyValue() {
            double value = getCurrentValue();
            onChange.accept(value);
        }

        public Double getCurrentValue() {
            return Mth.lerp(this.value, minValue, maxValue);
        }

        public void setValue(double newValue) {
            this.value = (newValue - minValue) / (maxValue - minValue);
            this.updateMessage();
        }
    }
}
