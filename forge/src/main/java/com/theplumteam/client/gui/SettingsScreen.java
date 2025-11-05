package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.util.ButtonFactory;
import com.theplumteam.client.gui.widget.TabButton;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLLoader;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen displayed as a modal overlay with tabbed interface
 */
public class SettingsScreen extends Screen {
    private final Screen parent;

    // Panel styling
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline
    private static final int TITLE_COLOR = 0xFFFFFF;          // White title

    // Tab dimensions
    private static final int TAB_HEIGHT = 30;
    private static final int TAB_WIDTH = 100;
    private static final int TAB_SPACING = 2;

    // Panel dimensions
    private int panelWidth = 650;
    private int panelHeight = 350;
    private int panelX;
    private int panelY;

    // Tab system
    private enum Tab {
        SERVER("Server"),
        DEVELOP("Develop");

        private final String displayName;

        Tab(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private Tab activeTab = Tab.SERVER;
    private TabButton serverTabButton;
    private TabButton developTabButton;
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> developSettingWidgets = new ArrayList<>();

    // Buttons and sliders
    private Button closeButton;
    private Button resetButton;
    private Button colorTransitionToggle;

    // Server settings
    private HourSlider resetHourSlider;

    // Star color sliders (Develop tab)
    private ColorSlider starRedSlider;
    private ColorSlider starGreenSlider;
    private ColorSlider starBlueSlider;
    private OpacitySlider starOpacitySlider;

    // Background color sliders (Develop tab)
    private ColorSlider bgRedSlider;
    private ColorSlider bgGreenSlider;
    private ColorSlider bgBlueSlider;

    // Panel opacity slider (Develop tab)
    private OpacitySlider panelOpacitySlider;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Clear widget lists to prevent duplication on resize
        serverSettingWidgets.clear();
        developSettingWidgets.clear();

        // Calculate centered panel position
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        // Create tab buttons at the top of the panel
        int tabY = this.panelY;
        int tabStartX = this.panelX;

        // Server tab (always shown)
        serverTabButton = (TabButton) ButtonFactory.createTab(
            tabStartX, tabY,
            TAB_WIDTH, TAB_HEIGHT,
            Component.literal(Tab.SERVER.getDisplayName()),
            activeTab == Tab.SERVER,
            btn -> switchTab(Tab.SERVER)
        );
        this.addRenderableWidget(serverTabButton);

        // Develop tab (only in development mode)
        if (isDevelopmentMode()) {
            developTabButton = (TabButton) ButtonFactory.createTab(
                tabStartX + TAB_WIDTH + TAB_SPACING, tabY,
                TAB_WIDTH, TAB_HEIGHT,
                Component.literal(Tab.DEVELOP.getDisplayName()),
                activeTab == Tab.DEVELOP,
                btn -> switchTab(Tab.DEVELOP)
            );
            this.addRenderableWidget(developTabButton);
        }

        // Create server settings
        createServerSettings();

        // Create development settings (only in development mode)
        if (isDevelopmentMode()) {
            createDevelopSettings();
        }

        // Show initial tab
        switchTab(activeTab);

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
            // Reset color transition toggle
            this.colorTransitionToggle.setMessage(Component.literal("Transition: ON"));
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

        // Calculate content panel area (below tabs)
        int contentPanelY = this.panelY + TAB_HEIGHT;
        int contentPanelHeight = this.panelHeight - TAB_HEIGHT;

        // Draw main content panel background with configurable opacity
        ClientConfig config = ClientConfig.getInstance();
        int alpha = (int)(config.panelOpacity * 255);
        int panelBgColor = (alpha << 24) | 0x000000;  // Black with configurable alpha

        graphics.fill(this.panelX, contentPanelY,
                     this.panelX + this.panelWidth,
                     contentPanelY + contentPanelHeight,
                     panelBgColor);

        // Draw outline around content panel
        // Top line (connects tabs to content if in dev mode)
        graphics.fill(this.panelX, contentPanelY,
                     this.panelX + this.panelWidth, contentPanelY + 1,
                     PANEL_OUTLINE);
        // Bottom
        graphics.fill(this.panelX, contentPanelY + contentPanelHeight - 1,
                     this.panelX + this.panelWidth, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);
        // Left
        graphics.fill(this.panelX, contentPanelY,
                     this.panelX + 1, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);
        // Right
        graphics.fill(this.panelX + this.panelWidth - 1, contentPanelY,
                     this.panelX + this.panelWidth, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);

        // Draw column headers (only in Develop tab)
        if (isDevelopmentMode() && activeTab == Tab.DEVELOP) {
            int padding = 20;
            int columnSpacing = 15;
            int availableWidth = this.panelWidth - (padding * 2) - (columnSpacing * 2);
            int columnWidth = availableWidth / 3;

            int col1X = this.panelX + padding;
            int col2X = col1X + columnWidth + columnSpacing;
            int col3X = col2X + columnWidth + columnSpacing;
            int headerY = this.panelY + TAB_HEIGHT + 5;

            graphics.drawString(this.font, "Star Color",
                               col1X,
                               headerY,
                               0xFFFFFF);

            graphics.drawString(this.font, "Background Color",
                               col2X,
                               headerY,
                               0xFFFFFF);

            graphics.drawString(this.font, "Panel & Animation",
                               col3X,
                               headerY,
                               0xFFFFFF);
        }

        // Draw server tab content
        if (activeTab == Tab.SERVER) {
            int headerY = this.panelY + TAB_HEIGHT + 10;
            graphics.drawCenteredString(this.font, "Token Reset Settings",
                                       this.panelX + this.panelWidth / 2,
                                       headerY,
                                       0xFFFFFF);

            // Draw explanation text
            int explanationY = this.panelY + TAB_HEIGHT + 80;
            String[] explanationLines = {
                "The guaranteed token grants an undiscovered figure from the collection.",
                "This token resets daily at the hour specified above (in your local time).",
                "Set this to a time that works best for your server's player base."
            };

            for (int i = 0; i < explanationLines.length; i++) {
                int lineWidth = this.font.width(explanationLines[i]);
                graphics.drawString(this.font, explanationLines[i],
                                   this.panelX + (this.panelWidth - lineWidth) / 2,
                                   explanationY + (i * 12),
                                   0xAAAAAA);
            }
        }

        // Draw color preview boxes (only in Develop tab)
        if (isDevelopmentMode() && activeTab == Tab.DEVELOP) {
            int previewSize = 35;
            int previewSpacing = 50;
            int previewStartX = this.panelX + (this.panelWidth - (previewSize * 2 + previewSpacing)) / 2;
            int previewY = this.panelY + 210;

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
        }

        // Render our modal buttons and widgets
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if click is outside the panel (including tabs)
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
     * Check if running in development mode (runclient)
     */
    private static boolean isDevelopmentMode() {
        return !FMLLoader.isProduction();
    }

    /**
     * Create server settings widgets
     */
    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();

        int sliderHeight = 20;
        int sliderWidth = 400;

        // Center the slider horizontally
        int sliderX = this.panelX + (this.panelWidth - sliderWidth) / 2;
        int startY = this.panelY + TAB_HEIGHT + 50;

        // Get local timezone
        ZoneId localZone = ZoneId.systemDefault();
        String timezoneName = localZone.getDisplayName(TextStyle.SHORT, Locale.getDefault());

        // Convert UTC hour to local hour
        int utcHour = config.getGuaranteedTokenResetHour();
        int localHour = convertUtcToLocal(utcHour);

        // Reset hour slider (0-23 in local time)
        this.resetHourSlider = new HourSlider(
                sliderX, startY,
                sliderWidth, sliderHeight,
                Component.literal("Guaranteed Token Reset Hour (" + timezoneName + "): "),
                localHour,
                localValue -> {
                    // Convert local time back to UTC before saving
                    int utcValue = convertLocalToUtc(localValue);
                    config.setGuaranteedTokenResetHour(utcValue);
                }
        );
        serverSettingWidgets.add(this.resetHourSlider);
    }

    /**
     * Convert UTC hour to local timezone hour
     */
    private static int convertUtcToLocal(int utcHour) {
        ZonedDateTime utcTime = ZonedDateTime.now(ZoneId.of("UTC"))
                .withHour(utcHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        ZonedDateTime localTime = utcTime.withZoneSameInstant(ZoneId.systemDefault());
        return localTime.getHour();
    }

    /**
     * Convert local timezone hour to UTC hour
     */
    private static int convertLocalToUtc(int localHour) {
        ZonedDateTime localTime = ZonedDateTime.now(ZoneId.systemDefault())
                .withHour(localHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        ZonedDateTime utcTime = localTime.withZoneSameInstant(ZoneId.of("UTC"));
        return utcTime.getHour();
    }

    /**
     * Create development settings widgets (all current background color settings)
     */
    private void createDevelopSettings() {
        ClientConfig config = ClientConfig.getInstance();

        // Reset background color to black when opening settings
        config.backgroundColorR = 0.0f;
        config.backgroundColorG = 0.0f;
        config.backgroundColorB = 0.0f;

        // Settings content area - 3 column layout
        int padding = 20;
        int columnSpacing = 15;
        int sliderHeight = 20;
        int verticalSpacing = 28;

        // Calculate column widths and positions
        int availableWidth = this.panelWidth - (padding * 2) - (columnSpacing * 2);
        int columnWidth = availableWidth / 3;

        int col1X = this.panelX + padding;
        int col2X = col1X + columnWidth + columnSpacing;
        int col3X = col2X + columnWidth + columnSpacing;

        // Start Y should account for tabs if in dev mode
        int startY = this.panelY + (isDevelopmentMode() ? TAB_HEIGHT + 20 : 50);

        // === COLUMN 1: STAR COLOR ===
        int col1Y = startY;

        this.starRedSlider = new ColorSlider(
                col1X, col1Y,
                columnWidth, sliderHeight,
                Component.literal("Red: "),
                config.starColorR,
                value -> config.starColorR = value.floatValue()
        );
        developSettingWidgets.add(this.starRedSlider);
        col1Y += verticalSpacing;

        this.starGreenSlider = new ColorSlider(
                col1X, col1Y,
                columnWidth, sliderHeight,
                Component.literal("Green: "),
                config.starColorG,
                value -> config.starColorG = value.floatValue()
        );
        developSettingWidgets.add(this.starGreenSlider);
        col1Y += verticalSpacing;

        this.starBlueSlider = new ColorSlider(
                col1X, col1Y,
                columnWidth, sliderHeight,
                Component.literal("Blue: "),
                config.starColorB,
                value -> config.starColorB = value.floatValue()
        );
        developSettingWidgets.add(this.starBlueSlider);
        col1Y += verticalSpacing;

        this.starOpacitySlider = new OpacitySlider(
                col1X, col1Y,
                columnWidth, sliderHeight,
                Component.literal("Opacity: "),
                config.starOpacity,
                value -> config.starOpacity = value.floatValue()
        );
        developSettingWidgets.add(this.starOpacitySlider);

        // === COLUMN 2: BACKGROUND COLOR ===
        int col2Y = startY;

        this.bgRedSlider = new ColorSlider(
                col2X, col2Y,
                columnWidth, sliderHeight,
                Component.literal("Red: "),
                config.backgroundColorR,
                value -> config.backgroundColorR = value.floatValue()
        );
        developSettingWidgets.add(this.bgRedSlider);
        col2Y += verticalSpacing;

        this.bgGreenSlider = new ColorSlider(
                col2X, col2Y,
                columnWidth, sliderHeight,
                Component.literal("Green: "),
                config.backgroundColorG,
                value -> config.backgroundColorG = value.floatValue()
        );
        developSettingWidgets.add(this.bgGreenSlider);
        col2Y += verticalSpacing;

        this.bgBlueSlider = new ColorSlider(
                col2X, col2Y,
                columnWidth, sliderHeight,
                Component.literal("Blue: "),
                config.backgroundColorB,
                value -> config.backgroundColorB = value.floatValue()
        );
        developSettingWidgets.add(this.bgBlueSlider);

        // === COLUMN 3: PANEL & ANIMATION ===
        int col3Y = startY;

        this.panelOpacitySlider = new OpacitySlider(
                col3X, col3Y,
                columnWidth, sliderHeight,
                Component.literal("Panel Opacity: "),
                config.panelOpacity,
                value -> config.panelOpacity = value.floatValue()
        );
        developSettingWidgets.add(this.panelOpacitySlider);
        col3Y += verticalSpacing;

        this.colorTransitionToggle = Button.builder(
                Component.literal("Transition: " + (config.enableColorTransition ? "ON" : "OFF")),
                button -> {
                    config.enableColorTransition = !config.enableColorTransition;
                    button.setMessage(Component.literal("Transition: " + (config.enableColorTransition ? "ON" : "OFF")));
                }
        )
                .bounds(col3X, col3Y, columnWidth, sliderHeight)
                .build();
        developSettingWidgets.add(this.colorTransitionToggle);
    }

    /**
     * Switch to a different tab
     */
    private void switchTab(Tab tab) {
        activeTab = tab;

        // Remove all setting widgets
        for (AbstractWidget widget : serverSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : developSettingWidgets) {
            this.removeWidget(widget);
        }

        // Add widgets for active tab
        List<AbstractWidget> activeWidgets;
        if (tab == Tab.SERVER) {
            activeWidgets = serverSettingWidgets;
        } else if (tab == Tab.DEVELOP) {
            activeWidgets = developSettingWidgets;
        } else {
            activeWidgets = new ArrayList<>();
        }

        for (AbstractWidget widget : activeWidgets) {
            this.addRenderableWidget(widget);
        }

        // Update tab button selected states (visual feedback)
        serverTabButton.setSelected(tab == Tab.SERVER);
        if (developTabButton != null) {
            developTabButton.setSelected(tab == Tab.DEVELOP);
        }
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

    /**
     * Custom slider for hour values (0-23)
     */
    private static class HourSlider extends AbstractSliderButton {
        private final Component prefix;
        private final java.util.function.Consumer<Integer> onValueChange;

        public HourSlider(int x, int y, int width, int height, Component prefix,
                         int initialValue, java.util.function.Consumer<Integer> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue / 23.0);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int hour = (int)(this.value * 23);
            this.setMessage(Component.literal(prefix.getString() + String.format("%02d:00", hour)));
        }

        @Override
        protected void applyValue() {
            int hour = (int)(this.value * 23);
            onValueChange.accept(hour);
        }

        public void setValue(int newValue) {
            this.value = newValue / 23.0;
            this.updateMessage();
        }
    }
}
