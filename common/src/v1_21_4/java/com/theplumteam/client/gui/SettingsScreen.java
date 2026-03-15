package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.config.ClientServerConfig;
import com.theplumteam.client.gui.util.ButtonFactory;
import com.theplumteam.client.gui.widget.TabButton;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.client.remote.RemoteAssetManager;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.network.ReloadTokensPacket;
import com.theplumteam.network.UpdateHiddenCollectionsPacket;
import com.theplumteam.network.UpdateRemoteCollectionsPacket;
import com.theplumteam.network.UpdateTokenSettingsPacket;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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
        ADMIN("Admin"),
        REMOTE("Custom"),
        DEVELOP("Develop"),
        CHEATS("Cheats");

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
    private TabButton adminTabButton;
    private TabButton remoteTabButton;
    private TabButton developTabButton;
    private TabButton cheatsTabButton;
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> adminSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> remoteSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> developSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> cheatsSettingWidgets = new ArrayList<>();

    // Admin tab - track hidden collection state locally before saving
    private final java.util.Set<String> pendingHiddenCollections = new java.util.HashSet<>();

    // Remote tab - track enabled remote collections before saving
    private final java.util.Set<String> pendingRemoteCollections = new java.util.HashSet<>();
    // Display names for added collections (id -> name)
    private final java.util.Map<String, String> remoteCollectionNames = new java.util.LinkedHashMap<>();
    private EditBox codeInputField;
    private String remoteStatusMessage = null;

    // Buttons and sliders
    private Button closeButton;
    private Button actionButton; // Context-sensitive button (Reset Colors / Change Time)
    private Button forceResyncButton; // Force Resync button (Remote tab only)
    private Button colorTransitionToggle;

    // Server settings
    private HourSlider resetHourSlider;
    private int loadedServerHourLocal; // The hour currently saved/loaded
    private int pendingServerHourLocal; // The hour currently selected on slider

    // Token settings (Server tab - EditBox fields for admins, read-only for non-admins)
    private EditBox regularCooldownBox;
    private EditBox maxRegularBox;
    private int loadedRegularCooldown;
    private int loadedMaxRegular;

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
        adminSettingWidgets.clear();
        remoteSettingWidgets.clear();
        developSettingWidgets.clear();
        cheatsSettingWidgets.clear();

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

        // Admin tab (only for admins)
        int nextTabX = tabStartX + TAB_WIDTH + TAB_SPACING;
        if (isAdmin()) {
            adminTabButton = (TabButton) ButtonFactory.createTab(
                    nextTabX, tabY,
                    TAB_WIDTH, TAB_HEIGHT,
                    Component.literal(Tab.ADMIN.getDisplayName()),
                    activeTab == Tab.ADMIN,
                    btn -> switchTab(Tab.ADMIN)
            );
            this.addRenderableWidget(adminTabButton);
            nextTabX += TAB_WIDTH + TAB_SPACING;

            // Remote tab (admin only)
            remoteTabButton = (TabButton) ButtonFactory.createTab(
                    nextTabX, tabY,
                    TAB_WIDTH, TAB_HEIGHT,
                    Component.literal(Tab.REMOTE.getDisplayName()),
                    activeTab == Tab.REMOTE,
                    btn -> switchTab(Tab.REMOTE)
            );
            this.addRenderableWidget(remoteTabButton);
            nextTabX += TAB_WIDTH + TAB_SPACING;
        }

        // Develop tab (only in development mode)
        if (isDevelopmentMode()) {
            developTabButton = (TabButton) ButtonFactory.createTab(
                    nextTabX, tabY,
                    TAB_WIDTH, TAB_HEIGHT,
                    Component.literal(Tab.DEVELOP.getDisplayName()),
                    activeTab == Tab.DEVELOP,
                    btn -> switchTab(Tab.DEVELOP)
            );
            this.addRenderableWidget(developTabButton);
            nextTabX += TAB_WIDTH + TAB_SPACING;
        }

        // Cheats tab (for admins and in development mode)
        if (canAccessCheats()) {
            cheatsTabButton = (TabButton) ButtonFactory.createTab(
                    nextTabX, tabY,
                    TAB_WIDTH, TAB_HEIGHT,
                    Component.literal(Tab.CHEATS.getDisplayName()),
                    activeTab == Tab.CHEATS,
                    btn -> switchTab(Tab.CHEATS)
            );
            this.addRenderableWidget(cheatsTabButton);
        }

        // Create button instances
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 20;
        int buttonSpacing = 10;
        int totalButtonWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonsStartX = this.panelX + (this.panelWidth - totalButtonWidth) / 2;

        // Close button (Left)
        this.closeButton = Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(buttonsStartX, buttonY, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.closeButton);

        // Action button (Right) - Text and behavior depend on tab
        this.actionButton = Button.builder(Component.literal("Action"), button -> handleActionClick())
                .bounds(buttonsStartX + buttonWidth + buttonSpacing, buttonY, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.actionButton);

        // Force Resync button (above Close + Action, spans full width, Remote tab only)
        this.forceResyncButton = Button.builder(Component.literal("Force Resync"), button -> {
                    button.setMessage(Component.literal("Syncing..."));
                    button.active = false;

                    RemoteAssetManager.init();
                    RemoteAssetManager.invalidateManifest();

                    java.util.Set<String> enabled = ClientServerConfig.getEnabledRemoteCollections();
                    if (!enabled.isEmpty()) {
                        RemoteAssetManager.syncEnabledCollections(new java.util.HashSet<>(enabled), () -> {
                            String error = RemoteAssetManager.getLastSyncError();
                            if (error != null) {
                                button.setMessage(Component.literal(error));
                                button.active = false;
                                scheduleButtonReset(button, 3000);
                            } else {
                                button.setMessage(Component.literal("Synced!"));
                                button.active = false;
                                scheduleButtonReset(button, 2000);
                            }
                        });
                    } else {
                        button.setMessage(Component.literal("Force Resync"));
                        button.active = true;
                    }
                })
                .bounds(buttonsStartX, buttonY - buttonHeight - 4, totalButtonWidth, buttonHeight)
                .build();
        this.forceResyncButton.visible = false;
        this.addRenderableWidget(this.forceResyncButton);

        // Create settings for all tabs
        createServerSettings();

        if (isAdmin()) {
            createAdminSettings();
            createRemoteSettings();
        }

        if (isDevelopmentMode()) {
            createDevelopSettings();
        }

        if (canAccessCheats()) {
            createCheatsSettings();
        }

        // Show initial tab and update action button state
        switchTab(activeTab);
    }

    /**
     * Check if the current player is an admin (permission level 2+)
     */
    private boolean isAdmin() {
        if (isDevelopmentMode()) {
            return true;
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            return this.minecraft.player.hasPermissions(2);
        }
        return false;
    }

    /**
     * Parse an integer from an EditBox value, returning a default if invalid/empty
     */
    private static int parseEditBoxInt(EditBox box, int defaultValue) {
        String text = box.getValue().trim();
        if (text.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if any server settings have been changed from their loaded values
     */
    private boolean hasServerSettingsChanged() {
        if (!isAdmin()) return false;
        if (regularCooldownBox == null || maxRegularBox == null) return false;
        int currentRegularCooldown = parseEditBoxInt(regularCooldownBox, loadedRegularCooldown);
        int currentMaxRegular = parseEditBoxInt(maxRegularBox, loadedMaxRegular);
        return currentRegularCooldown != loadedRegularCooldown
                || currentMaxRegular != loadedMaxRegular
                || pendingServerHourLocal != loadedServerHourLocal;
    }

    /**
     * Schedules a button to reset its text back to "Force Resync" after a delay.
     */
    private void scheduleButtonReset(Button btn, int delayMs) {
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            Minecraft.getInstance().execute(() -> {
                btn.setMessage(Component.literal("Force Resync"));
                btn.active = true;
            });
        });
    }

    /**
     * Handles the click of the context-sensitive action button
     */
    private void handleActionClick() {
        if (activeTab == Tab.SERVER) {
            // "Save Settings" logic - clamp values client-side to match server validation
            int regularCooldown = Math.max(1, Math.min(168, parseEditBoxInt(regularCooldownBox, loadedRegularCooldown)));
            int maxRegular = Math.max(1, Math.min(99, parseEditBoxInt(maxRegularBox, loadedMaxRegular)));
            int utcValue = convertLocalToUtc(pendingServerHourLocal);

            // Send packet to server with all 3 settings
            new UpdateTokenSettingsPacket(regularCooldown, maxRegular, utcValue).sendToServer();

            // Update loaded values to clamped values
            this.loadedRegularCooldown = regularCooldown;
            this.loadedMaxRegular = maxRegular;
            this.loadedServerHourLocal = pendingServerHourLocal;

            // Update EditBox fields to show clamped values
            this.regularCooldownBox.setValue(String.valueOf(regularCooldown));
            this.maxRegularBox.setValue(String.valueOf(maxRegular));

            // Update ClientServerConfig immediately for responsiveness
            ClientServerConfig.update(regularCooldown, maxRegular, utcValue);

            updateActionButtonState();

        } else if (activeTab == Tab.ADMIN) {
            // "Save Visibility" logic - send hidden collections to server
            new UpdateHiddenCollectionsPacket(new ArrayList<>(pendingHiddenCollections)).sendToServer();

            // Update client cache immediately for responsiveness
            ClientServerConfig.updateHiddenCollections(new ArrayList<>(pendingHiddenCollections));

            updateActionButtonState();

        } else if (activeTab == Tab.REMOTE) {
            // "Save Remote" logic - send enabled remote collections to server
            // Server will broadcast SyncServerConfigPacket which triggers the download
            new UpdateRemoteCollectionsPacket(new ArrayList<>(pendingRemoteCollections)).sendToServer();

            // Update client cache immediately
            ClientServerConfig.updateEnabledRemoteCollections(new ArrayList<>(pendingRemoteCollections));

            updateActionButtonState();

        } else if (activeTab == Tab.DEVELOP) {
            // "Reset Colors" logic
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
            this.panelOpacitySlider.setValue(1.0);
            // Reset color transition toggle
            this.colorTransitionToggle.setMessage(Component.literal("Transition: ON"));
        }
    }

    /**
     * Updates the text and active state of the action button based on current tab
     */
    private void updateActionButtonState() {
        if (activeTab == Tab.SERVER) {
            this.actionButton.setMessage(Component.literal("Save Settings"));
            // Only visible and active for admins when values have changed
            this.actionButton.visible = isAdmin();
            this.actionButton.active = isAdmin() && hasServerSettingsChanged();
        } else if (activeTab == Tab.ADMIN) {
            this.actionButton.setMessage(Component.literal("Save Visibility"));
            this.actionButton.visible = true;
            this.actionButton.active = hasHiddenCollectionsChanged();
        } else if (activeTab == Tab.REMOTE) {
            this.actionButton.setMessage(Component.literal("Save Custom"));
            this.actionButton.visible = true;
            this.actionButton.active = hasRemoteCollectionsChanged();
        } else if (activeTab == Tab.DEVELOP) {
            this.actionButton.setMessage(Component.literal("Reset Colors"));
            this.actionButton.active = true;
            this.actionButton.visible = true;
        } else {
            // Cheats tab doesn't use the main action button
            this.actionButton.visible = false;
        }

        // Force Resync: only visible on Remote tab when there are collections
        this.forceResyncButton.visible = activeTab == Tab.REMOTE && !pendingRemoteCollections.isEmpty();
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
        // In 1.21.4+, RenderSystem.clear() signature changed - just use clearDepth

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
        // Top line
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
            graphics.drawCenteredString(this.font, "Token Settings",
                    this.panelX + this.panelWidth / 2,
                    headerY,
                    0xFFFFFF);

            // Draw labels for the settings fields
            int contentWidth = 400;
            int contentX = this.panelX + (this.panelWidth - contentWidth) / 2;
            int fieldWidth = 60;
            int startY = this.panelY + TAB_HEIGHT + 30;
            int verticalSpacing = 30;
            boolean admin = isAdmin();

            int labelY = startY + 6; // Vertically center with EditBox
            graphics.drawString(this.font, "Regular Token Cooldown (hours):",
                    contentX, labelY, 0xFFFFFF);
            if (!admin) {
                // Show read-only value
                String val = String.valueOf(ClientServerConfig.getRegularTokenCooldownHours());
                graphics.drawString(this.font, val,
                        contentX + contentWidth - this.font.width(val), labelY, 0xAAAAAA);
            }

            labelY += verticalSpacing;
            graphics.drawString(this.font, "Max Regular Tokens:",
                    contentX, labelY, 0xFFFFFF);
            if (!admin) {
                String val = String.valueOf(ClientServerConfig.getMaxRegularTokens());
                graphics.drawString(this.font, val,
                        contentX + contentWidth - this.font.width(val), labelY, 0xAAAAAA);
            }


            // Draw explanation text below the fields
            int explanationY = startY + (verticalSpacing * 2) + 30;
            if (admin && resetHourSlider != null && resetHourSlider.visible) {
                explanationY += 30; // Push down if slider is visible
            }
            String[] explanationLines;
            if (admin) {
                explanationLines = new String[]{
                        "Configure token generation and reset timing for all players.",
                        "The guaranteed token resets daily at the specified hour."
                };
            } else {
                explanationLines = new String[]{
                        "These settings are configured by the server administrator.",
                        "Regular tokens regenerate every " + ClientServerConfig.getRegularTokenCooldownHours() + " hour(s), up to " + ClientServerConfig.getMaxRegularTokens() + " max.",
                        "The guaranteed token resets daily."
                };
            }

            for (int i = 0; i < explanationLines.length; i++) {
                int lineWidth = this.font.width(explanationLines[i]);
                graphics.drawString(this.font, explanationLines[i],
                        this.panelX + (this.panelWidth - lineWidth) / 2,
                        explanationY + (i * 12),
                        0xAAAAAA);
            }
        }

        // Draw admin tab content
        if (activeTab == Tab.ADMIN) {
            int headerY = this.panelY + TAB_HEIGHT + 10;
            graphics.drawCenteredString(this.font, "Collection Visibility",
                    this.panelX + this.panelWidth / 2,
                    headerY,
                    0xFFFFFF);

            int explanationY = this.panelY + TAB_HEIGHT + 30;
            String[] explanationLines = {
                    "Toggle which collections are visible in the claw machine menu.",
                    "Hidden collections will not appear for any player on this server."
            };
            for (int i = 0; i < explanationLines.length; i++) {
                int lineWidth = this.font.width(explanationLines[i]);
                graphics.drawString(this.font, explanationLines[i],
                        this.panelX + (this.panelWidth - lineWidth) / 2,
                        explanationY + (i * 12),
                        0xAAAAAA);
            }
        }

        // Draw remote tab content
        if (activeTab == Tab.REMOTE) {
            int headerY = this.panelY + TAB_HEIGHT + 10;
            Component headerText = Component.literal("Custom Collections - ")
                    .append(Component.literal("Coming Soon").withStyle(net.minecraft.network.chat.Style.EMPTY.withUnderlined(true)));
            graphics.drawCenteredString(this.font, headerText,
                    this.panelX + this.panelWidth / 2,
                    headerY,
                    0xFFFFFF);

            int explanationY = this.panelY + TAB_HEIGHT + 30;
            String instruction = "Enter your collection code to load it.";
            int instrWidth = this.font.width(instruction);
            graphics.drawString(this.font, instruction,
                    this.panelX + (this.panelWidth - instrWidth) / 2,
                    explanationY,
                    0xAAAAAA);

            // Draw status message (success/error feedback)
            if (remoteStatusMessage != null) {
                int statusY = explanationY + 14;
                // Strip color codes for width calculation
                String plainMsg = remoteStatusMessage.replaceAll("\u00A7.", "");
                int plainWidth = this.font.width(plainMsg);
                graphics.drawString(this.font, remoteStatusMessage,
                        this.panelX + (this.panelWidth - plainWidth) / 2,
                        statusY,
                        0xFFFFFF);
            }
        }

        // Draw cheats tab content (for admins)
        if (activeTab == Tab.CHEATS) {
            int headerY = this.panelY + TAB_HEIGHT + 10;
            graphics.drawCenteredString(this.font, "Collection Cheats",
                    this.panelX + this.panelWidth / 2,
                    headerY,
                    0xFFFFFF);

            // Draw explanation text
            int explanationY = this.panelY + TAB_HEIGHT + 30;
            String[] explanationLines = {
                    "Use the token reload buttons to restore your tokens.",
                    "Click a collection button to unlock all figures and receive all boxes."
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
        // Return to parent screen
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Disable the 1.21+ menu blur effect - this modal renders its own semi-transparent overlay
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Check if running in development mode using Architectury Platform API
     */
    private static boolean isDevelopmentMode() {
        return Platform.isDevelopmentEnvironment();
    }

    /**
     * Check if the current player can access cheats.
     * Returns true if in development mode OR if player is an admin (permission level 2+)
     */
    private boolean canAccessCheats() {
        if (isDevelopmentMode()) {
            return true;
        }
        // Check if player has admin permissions (level 2, same as /blockpops getbox command)
        if (this.minecraft != null && this.minecraft.player != null) {
            return this.minecraft.player.hasPermissions(2);
        }
        return false;
    }

    /**
     * Create server settings widgets
     */
    private void createServerSettings() {
        boolean admin = isAdmin();

        int fieldHeight = 20;
        int fieldWidth = 60;
        int labelFieldSpacing = 8;
        int verticalSpacing = 30;

        // Center the content area
        int contentWidth = 400;
        int contentX = this.panelX + (this.panelWidth - contentWidth) / 2;
        int startY = this.panelY + TAB_HEIGHT + 30;

        // Load current values from ClientServerConfig
        this.loadedRegularCooldown = ClientServerConfig.getRegularTokenCooldownHours();
        this.loadedMaxRegular = ClientServerConfig.getMaxRegularTokens();

        // Get local timezone
        ZoneId localZone = ZoneId.systemDefault();
        String timezoneName = localZone.getDisplayName(TextStyle.SHORT, Locale.getDefault());

        // Convert UTC hour to local hour
        int utcHour = ClientServerConfig.getGuaranteedTokenResetHour();
        int localHour = convertUtcToLocal(utcHour);

        // Initialize tracking variables
        this.loadedServerHourLocal = localHour;
        this.pendingServerHourLocal = localHour;

        int currentY = startY;

        // --- Regular Token Cooldown (hours) ---
        int fieldX = contentX + contentWidth - fieldWidth;
        if (admin) {
            this.regularCooldownBox = new EditBox(this.font, fieldX, currentY, fieldWidth, fieldHeight, Component.literal("Regular Cooldown"));
            this.regularCooldownBox.setValue(String.valueOf(loadedRegularCooldown));
            this.regularCooldownBox.setFilter(s -> s.matches("\\d*"));
            this.regularCooldownBox.setMaxLength(3);
            this.regularCooldownBox.setResponder(s -> updateActionButtonState());
            serverSettingWidgets.add(this.regularCooldownBox);
        }
        currentY += verticalSpacing;

        // --- Max Regular Tokens ---
        if (admin) {
            this.maxRegularBox = new EditBox(this.font, fieldX, currentY, fieldWidth, fieldHeight, Component.literal("Max Regular"));
            this.maxRegularBox.setValue(String.valueOf(loadedMaxRegular));
            this.maxRegularBox.setFilter(s -> s.matches("\\d*"));
            this.maxRegularBox.setMaxLength(2);
            this.maxRegularBox.setResponder(s -> updateActionButtonState());
            serverSettingWidgets.add(this.maxRegularBox);
        }
        currentY += verticalSpacing;

        // --- Reset Hour Slider (always visible - guaranteed token resets daily) ---
        int sliderWidth = contentWidth;
        this.resetHourSlider = new HourSlider(
                contentX, currentY,
                sliderWidth, fieldHeight,
                Component.literal("Guaranteed Token Reset Hour (" + timezoneName + "): "),
                localHour,
                localValue -> {
                    this.pendingServerHourLocal = localValue;
                    updateActionButtonState();
                }
        );
        if (admin) {
            serverSettingWidgets.add(this.resetHourSlider);
        }
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
     * Check if hidden collections have changed from the server state
     */
    private boolean hasHiddenCollectionsChanged() {
        java.util.Set<String> serverHidden = ClientServerConfig.getHiddenCollections();
        return !pendingHiddenCollections.equals(serverHidden);
    }

    /**
     * Check if remote collections have changed from the server state
     */
    private boolean hasRemoteCollectionsChanged() {
        java.util.Set<String> serverEnabled = ClientServerConfig.getEnabledRemoteCollections();
        return !pendingRemoteCollections.equals(serverEnabled);
    }

    /**
     * Create remote settings widgets (code input + collection list)
     */
    private void createRemoteSettings() {
        // Initialize pending state from current server config
        pendingRemoteCollections.clear();
        pendingRemoteCollections.addAll(ClientServerConfig.getEnabledRemoteCollections());

        // Populate display names for existing collections
        for (String id : pendingRemoteCollections) {
            if (!remoteCollectionNames.containsKey(id)) {
                // Capitalize first letter as fallback name
                remoteCollectionNames.put(id, id.substring(0, 1).toUpperCase() + id.substring(1));
            }
        }

        rebuildRemoteWidgets();
    }

    /**
     * Rebuilds all remote tab widgets (code input + collection list).
     */
    private void rebuildRemoteWidgets() {
        // Remove old remote widgets
        for (AbstractWidget widget : remoteSettingWidgets) {
            this.removeWidget(widget);
        }
        remoteSettingWidgets.clear();

        int padding = 20;
        int startY = this.panelY + TAB_HEIGHT + 55;
        int contentWidth = this.panelWidth - padding * 2;

        // --- Code input row ---
        int inputWidth = 180;
        int addBtnWidth = 60;
        int inputSpacing = 5;
        int totalInputWidth = inputWidth + inputSpacing + addBtnWidth;
        int inputX = this.panelX + (this.panelWidth - totalInputWidth) / 2;

        codeInputField = new EditBox(this.font, inputX, startY, inputWidth, 20, Component.literal("Collection Code"));
        codeInputField.setHint(Component.literal("Enter code...").withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0x808080)));
        codeInputField.setMaxLength(64);
        codeInputField.setResponder(text -> remoteStatusMessage = null); // Clear status on type
        remoteSettingWidgets.add(codeInputField);

        Button addButton = Button.builder(
                        Component.literal("Add"),
                        button -> {
                            String code = codeInputField.getValue().trim();
                            if (code.isEmpty()) return;

                            button.active = false;
                            button.setMessage(Component.literal("..."));
                            remoteStatusMessage = "\u00A7eLoading...";

                            RemoteAssetManager.init();
                            RemoteAssetManager.fetchCollectionByCode(code).thenAccept(result -> {
                                Minecraft.getInstance().execute(() -> {
                                    if (result == null) {
                                        remoteStatusMessage = "\u00A7cInvalid code. Collection not found.";
                                    } else if (result.isError()) {
                                        remoteStatusMessage = "\u00A7c" + result.error();
                                    } else if (pendingRemoteCollections.contains(result.id())) {
                                        remoteStatusMessage = "\u00A7e" + result.name() + " is already added.";
                                    } else {
                                        pendingRemoteCollections.add(result.id());
                                        remoteCollectionNames.put(result.id(), result.name());
                                        remoteStatusMessage = "\u00A7aAdded: " + result.name();
                                        codeInputField.setValue("");
                                        rebuildRemoteWidgets();
                                        if (activeTab == Tab.REMOTE) {
                                            switchTab(Tab.REMOTE);
                                        }
                                    }
                                    button.active = true;
                                    button.setMessage(Component.literal("Add"));
                                    updateActionButtonState();
                                });
                            });
                        }
                )
                .bounds(inputX + inputWidth + inputSpacing, startY, addBtnWidth, 20)
                .build();
        remoteSettingWidgets.add(addButton);

        startY += 35;

        // --- Collection list (added collections with remove buttons) ---
        if (!pendingRemoteCollections.isEmpty()) {
            int buttonHeight = 24;
            int removeWidth = 24;
            int entrySpacing = 4;
            int entryWidth = 250;
            int entryX = this.panelX + (this.panelWidth - entryWidth) / 2;

            for (String collectionId : new ArrayList<>(pendingRemoteCollections)) {
                String displayName = remoteCollectionNames.getOrDefault(collectionId,
                        collectionId.substring(0, 1).toUpperCase() + collectionId.substring(1));

                // Collection name label button (non-interactive, just for display)
                Button nameButton = Button.builder(
                                Component.literal("\u00A7a\u2714 " + displayName),
                                button -> {} // no-op
                        )
                        .bounds(entryX, startY, entryWidth - removeWidth - entrySpacing, buttonHeight)
                        .build();
                nameButton.active = false; // Display only
                remoteSettingWidgets.add(nameButton);

                // Remove "X" button
                Button removeButton = Button.builder(
                                Component.literal("\u00A7cX"),
                                button -> {
                                    pendingRemoteCollections.remove(collectionId);
                                    remoteCollectionNames.remove(collectionId);
                                    remoteStatusMessage = null;
                                    rebuildRemoteWidgets();
                                    if (activeTab == Tab.REMOTE) {
                                        switchTab(Tab.REMOTE);
                                    }
                                    updateActionButtonState();
                                }
                        )
                        .bounds(entryX + entryWidth - removeWidth, startY, removeWidth, buttonHeight)
                        .build();
                remoteSettingWidgets.add(removeButton);

                startY += buttonHeight + entrySpacing;
            }
        }

        // If we're currently on the Remote tab, re-show the widgets
        if (activeTab == Tab.REMOTE) {
            for (AbstractWidget widget : remoteSettingWidgets) {
                this.addRenderableWidget(widget);
            }
        }
    }

    /**
     * Create admin settings widgets (collection visibility toggles)
     */
    private void createAdminSettings() {
        // Initialize pending state from current server config
        pendingHiddenCollections.clear();
        pendingHiddenCollections.addAll(ClientServerConfig.getHiddenCollections());

        int padding = 20;
        int buttonWidth = 180;
        int buttonHeight = 24;
        int verticalSpacing = 30;
        int horizontalSpacing = 15;
        int buttonsPerRow = 3;

        int startY = this.panelY + TAB_HEIGHT + 60;
        int startX = this.panelX + padding;

        // Get all collections (excluding "default")
        java.util.Collection<FigureCollection> allCollections = CollectionRegistry.getAllCollections();
        java.util.List<FigureCollection> filteredCollections = allCollections.stream()
                .filter(collection -> !collection.getId().equals("default"))
                .collect(java.util.stream.Collectors.toList());

        int row = 0;
        int col = 0;

        for (FigureCollection collection : filteredCollections) {
            int buttonX = startX + (col * (buttonWidth + horizontalSpacing));
            int buttonY = startY + (row * verticalSpacing);

            boolean isHidden = pendingHiddenCollections.contains(collection.getId());
            String label = (isHidden ? "\u00A7c\u2716 " : "\u00A7a\u2714 ") + collection.getName();

            Button toggleButton = Button.builder(
                            Component.literal(label),
                            button -> {
                                String id = collection.getId();
                                if (pendingHiddenCollections.contains(id)) {
                                    pendingHiddenCollections.remove(id);
                                    button.setMessage(Component.literal("\u00A7a\u2714 " + collection.getName()));
                                } else {
                                    pendingHiddenCollections.add(id);
                                    button.setMessage(Component.literal("\u00A7c\u2716 " + collection.getName()));
                                }
                                updateActionButtonState();
                            }
                    )
                    .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                    .build();

            adminSettingWidgets.add(toggleButton);

            col++;
            if (col >= buttonsPerRow) {
                col = 0;
                row++;
            }
        }
    }

    /**
     * Create cheats settings widgets (collection unlock buttons)
     */
    private void createCheatsSettings() {
        int padding = 20;
        int buttonWidth = 180;
        int buttonHeight = 24;
        int verticalSpacing = 30;
        int horizontalSpacing = 15;
        int buttonsPerRow = 3;

        int startY = this.panelY + TAB_HEIGHT + 50;
        int startX = this.panelX + padding;

        // Add token reload buttons at the top
        int tokenButtonWidth = 200;
        int tokenButtonSpacing = 15;
        int tokenButtonsStartX = this.panelX + (this.panelWidth - (tokenButtonWidth * 2 + tokenButtonSpacing)) / 2;
        int tokenButtonY = startY;

        // Reload Regular Tokens button
        Button reloadRegularButton = Button.builder(
                        Component.literal("Reload Regular Tokens"),
                        button -> {
                            // Send packet to server to reload regular tokens
                            ReloadTokensPacket packet = new ReloadTokensPacket(true, false);
                            packet.sendToServer();

                            // Provide visual feedback
                            button.setMessage(Component.literal("Reloading..."));
                            button.active = false;

                            // Re-enable button after a short delay
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                    this.minecraft.execute(() -> {
                                        button.setMessage(Component.literal("Reload Regular Tokens"));
                                        button.active = true;
                                    });
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }).start();
                        }
                )
                .bounds(tokenButtonsStartX, tokenButtonY, tokenButtonWidth, buttonHeight)
                .build();
        cheatsSettingWidgets.add(reloadRegularButton);

        // Reload Guaranteed Token button
        Button reloadGuaranteedButton = Button.builder(
                        Component.literal("Reload Guaranteed Token"),
                        button -> {
                            // Send packet to server to reload guaranteed token
                            ReloadTokensPacket packet = new ReloadTokensPacket(false, true);
                            packet.sendToServer();

                            // Provide visual feedback
                            button.setMessage(Component.literal("Reloading..."));
                            button.active = false;

                            // Re-enable button after a short delay
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                    this.minecraft.execute(() -> {
                                        button.setMessage(Component.literal("Reload Guaranteed Token"));
                                        button.active = true;
                                    });
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }).start();
                        }
                )
                .bounds(tokenButtonsStartX + tokenButtonWidth + tokenButtonSpacing, tokenButtonY, tokenButtonWidth, buttonHeight)
                .build();
        cheatsSettingWidgets.add(reloadGuaranteedButton);

        // Adjust startY for collection unlock buttons to be below token buttons
        startY += verticalSpacing + 20;

        // Get all collections
        java.util.Collection<FigureCollection> collections = CollectionRegistry.getAllCollections();

        // Filter out the default collection if it exists
        java.util.List<FigureCollection> filteredCollections = collections.stream()
                .filter(collection -> !collection.getId().equals("default"))
                .collect(java.util.stream.Collectors.toList());

        int row = 0;
        int col = 0;

        for (FigureCollection collection : filteredCollections) {
            int buttonX = startX + (col * (buttonWidth + horizontalSpacing));
            int buttonY = startY + (row * verticalSpacing);

            Button unlockButton = Button.builder(
                            Component.literal("Unlock " + collection.getName()),
                            button -> {
                                // Send packet to server to unlock this collection
                                UnlockCollectionPacket packet = new UnlockCollectionPacket(collection.getId());
                                packet.sendToServer();

                                // Provide visual feedback
                                button.setMessage(Component.literal("Unlocking..."));
                                button.active = false;

                                // Re-enable button after a short delay
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(1000);
                                        this.minecraft.execute(() -> {
                                            button.setMessage(Component.literal("Unlock " + collection.getName()));
                                            button.active = true;
                                        });
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }).start();
                            }
                    )
                    .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                    .build();

            cheatsSettingWidgets.add(unlockButton);

            col++;
            if (col >= buttonsPerRow) {
                col = 0;
                row++;
            }
        }
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
        for (AbstractWidget widget : adminSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : remoteSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : developSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : cheatsSettingWidgets) {
            this.removeWidget(widget);
        }

        // Add widgets for active tab
        List<AbstractWidget> activeWidgets;
        if (tab == Tab.SERVER) {
            activeWidgets = serverSettingWidgets;
        } else if (tab == Tab.ADMIN) {
            activeWidgets = adminSettingWidgets;
        } else if (tab == Tab.REMOTE) {
            activeWidgets = remoteSettingWidgets;
        } else if (tab == Tab.DEVELOP) {
            activeWidgets = developSettingWidgets;
        } else if (tab == Tab.CHEATS) {
            activeWidgets = cheatsSettingWidgets;
        } else {
            activeWidgets = new ArrayList<>();
        }

        for (AbstractWidget widget : activeWidgets) {
            this.addRenderableWidget(widget);
        }

        // Update tab button selected states (visual feedback)
        serverTabButton.setSelected(tab == Tab.SERVER);
        if (adminTabButton != null) {
            adminTabButton.setSelected(tab == Tab.ADMIN);
        }
        if (remoteTabButton != null) {
            remoteTabButton.setSelected(tab == Tab.REMOTE);
        }
        if (developTabButton != null) {
            developTabButton.setSelected(tab == Tab.DEVELOP);
        }
        if (cheatsTabButton != null) {
            cheatsTabButton.setSelected(tab == Tab.CHEATS);
        }

        // Update action button text/state/visibility based on new tab
        updateActionButtonState();
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
