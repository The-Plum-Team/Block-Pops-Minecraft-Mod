// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\client\gui\FavoriteColorSelectionScreen.java ==========
package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.widget.ColorSelectionButton;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SetFavoriteColorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen displayed when a player joins a world for the first time.
 * Allows them to choose their favorite color which will be used for their figure box
 * in the World Players collection.
 */
public class FavoriteColorSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(FavoriteColorSelectionScreen.class);

    @Nullable
    private PopBlockColor selectedColor = null;
    private Button doneButton;
    private final List<ColorSelectionButton> colorButtons = new ArrayList<>();

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // Constants
    private static final int MIN_PANEL_WIDTH = 400;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 450;

    // Background textures
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation("blockpops", "textures/gui/background/star_pattern.png");

    // Transformation values for box rendering
    private float rotationX = 342.3f;  // Default rotation
    private float rotationY = 335.9f;
    private float rotationZ = 0.0f;
    private float scale = 1.2f;
    private float offsetX = 198.0f;
    private float offsetY = -134.0f;
    private float offsetZ = 0.0f;

    // Box container scale (scales the grid area only, not the entire panel)
    private float containerScale = 1.2f;

    public FavoriteColorSelectionScreen() {
        super(Component.literal("Choose Your Favorite Color"));
        LOGGER.info("FavoriteColorSelectionScreen created");
    }

    @Override
    protected void init() {
        // Enforce GUI Scale
        if (GuiScaleManager.setMenuGuiScale(GuiScaleManager.getOptimalMenuScale())) {
            // If scale changed, the screen will be re-initialized by the engine
            return;
        }

        super.init();
        clearWidgets();
        colorButtons.clear();

        // Calculate panel dimensions
        calculatePanelDimensions();

        int scaledPadding = 20;
        int scaledSpacing = 10;
        int scaledComponentHeight = 24;

        // Title and description
        int titleY = panelY + scaledPadding;

        // Color grid setup - scale button size with container scale
        int buttonSize = (int)(60 * containerScale); // Size of each color button
        int gridCols = 4; // 4x4 grid
        int gridRows = 4;
        int gridWidth = (gridCols * buttonSize) + ((gridCols - 1) * scaledSpacing);
        int gridHeight = (gridRows * buttonSize) + ((gridRows - 1) * scaledSpacing);

        // Center the grid horizontally
        int gridStartX = panelX + (panelWidth - gridWidth) / 2;

        // Position grid below title and description
        int descriptionHeight = font.lineHeight * 3; // Title + description (2 lines)
        int gridStartY = titleY + descriptionHeight + scaledPadding;

        // Create 4x4 grid of color buttons
        PopBlockColor[] colors = PopBlockColor.values();
        for (int i = 0; i < colors.length; i++) {
            PopBlockColor color = colors[i];
            int row = i / gridCols;
            int col = i % gridCols;
            int x = gridStartX + (col * (buttonSize + scaledSpacing));
            int y = gridStartY + (row * (buttonSize + scaledSpacing));

            ColorSelectionButton colorButton = new ColorSelectionButton(x, y, buttonSize, color, this);
            this.addRenderableWidget(colorButton);
            colorButtons.add(colorButton);
        }

        // Done button at the bottom, centered
        int buttonWidth = 200;
        int doneButtonX = panelX + (panelWidth - buttonWidth) / 2;
        int doneButtonY = panelY + panelHeight - scaledPadding - scaledComponentHeight;

        doneButton = Button.builder(Component.literal("Done"), button -> {
            if (selectedColor != null) {
                LOGGER.info("Player confirmed favorite color choice: {}", selectedColor.getSerializedName());
                // Send packet to server
                SetFavoriteColorPacket packet = new SetFavoriteColorPacket(selectedColor.getSerializedName());
                BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
                this.onClose();
            }
        }).bounds(doneButtonX, doneButtonY, buttonWidth, scaledComponentHeight).build();

        doneButton.active = false; // Initially disabled
        this.addRenderableWidget(doneButton);

        // Initialize button transforms
        updateButtonTransforms();
    }

    /**
     * Update all color buttons with current transformation values
     */
    private void updateButtonTransforms() {
        for (ColorSelectionButton button : colorButtons) {
            button.setTransforms(rotationX, rotationY, rotationZ, scale, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Calculate panel dimensions based on screen size
     */
    private void calculatePanelDimensions() {
        int desiredWidth = (int)(this.width * 0.5f);
        int desiredHeight = (int)(this.height * 0.7f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, this.width - 60)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                this.height - 60
        );

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    /**
     * Called when a color is selected
     */
    public void setSelectedColor(PopBlockColor color) {
        this.selectedColor = color;
        this.doneButton.active = true; // Enable Done button

        // Update button visual states
        for (ColorSelectionButton button : colorButtons) {
            button.setSelected(button.getColor() == color);
        }

        LOGGER.debug("Selected color: {}", color.getSerializedName());
    }

    @Override
    public void removed() {
        // Restore original GUI scale when screen is closed/removed
        GuiScaleManager.restoreOriginalGuiScale();
        super.removed();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render animated starry background
        renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render title and description on top
        renderTitleAndDescription(graphics);
    }

    /**
     * Render animated starry background
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        // 1. Fill with configured background color as a base layer
        ClientConfig config = ClientConfig.getInstance();
        int bgRed = (int)(config.backgroundColorR * 255);
        int bgGreen = (int)(config.backgroundColorG * 255);
        int bgBlue = (int)(config.backgroundColorB * 255);
        int bgColor = 0xFF000000 | (bgRed << 16) | (bgGreen << 8) | bgBlue;
        graphics.fill(0, 0, this.width, this.height, bgColor);

        // 2. Render the moving star pattern
        renderStarPattern(graphics, partialTick);
    }

    /**
     * Render the animated star pattern
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        // Actual texture size
        int textureSize = 1024;
        // The size to render each tile (smaller = more stars visible)
        int tileSize = 55;
        // Animation speed: pixels per second
        double pixelsPerSecond = 8.0;

        // Use Minecraft's smooth game time for smooth animation
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0; // Convert to seconds
        double offset = (smoothTime * pixelsPerSecond) % tileSize;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Apply star color tint and opacity from config
        ClientConfig config = ClientConfig.getInstance();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        // Calculate how many tiles are needed to cover the screen
        int xTiles = Mth.ceil((float) this.width / tileSize) + 2;
        int yTiles = Mth.ceil((float) this.height / tileSize) + 1;

        var pose = graphics.pose();
        pose.pushPose();

        for (int y = 0; y < yTiles; ++y) {
            for (int x = 0; x < xTiles; ++x) {
                // Draw each tile, applying the horizontal scroll offset
                double drawX = x * tileSize - offset;
                double drawY = y * tileSize;

                // Draw the full texture scaled down to tileSize x tileSize
                pose.pushPose();
                pose.translate(drawX, drawY, 0);
                pose.scale(tileSize / (float)textureSize, tileSize / (float)textureSize, 1.0f);
                graphics.blit(STAR_PATTERN_TEXTURE, 0, 0, 0, 0.0f, 0.0f, textureSize, textureSize, textureSize, textureSize);
                pose.popPose();
            }
        }

        pose.popPose();

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent with configurable opacity)
        ClientConfig config = ClientConfig.getInstance();
        int alpha = (int)(config.panelOpacity * 255);
        int panelBgColor = (alpha << 24) | 0x000000;  // Black with configurable alpha

        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                panelBgColor
        );

        // Panel outline (subtle white)
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
    }

    /**
     * Render the title and description text
     */
    private void renderTitleAndDescription(GuiGraphics graphics) {
        int scaledPadding = 20;
        int titleY = panelY + scaledPadding;

        // Title
        String title = "Choose Your Favorite Color";
        int titleWidth = font.width(title);
        int titleX = panelX + (panelWidth - titleWidth) / 2;
        graphics.drawString(this.font, title, titleX, titleY, 0xFFFFFF, false);

        // Description (2 lines)
        titleY += font.lineHeight + 8;
        String desc1 = "This color will be used for your figure box";
        int desc1Width = font.width(desc1);
        int desc1X = panelX + (panelWidth - desc1Width) / 2;
        graphics.drawString(this.font, desc1, desc1X, titleY, 0xAAAAAA, false);

        titleY += font.lineHeight + 2;
        String desc2 = "in the World Players collection.";
        int desc2Width = font.width(desc2);
        int desc2X = panelX + (panelWidth - desc2Width) / 2;
        graphics.drawString(this.font, desc2, desc2X, titleY, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Prevent closing with ESC - player must make a choice
        return false;
    }

}