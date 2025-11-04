package com.theplumteam.client.gui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.widget.ColorSelectionButton;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SetFavoriteColorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background (darken screen)
        renderBackground(graphics);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render title and description on top
        renderTitleAndDescription(graphics);
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent)
        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                0xB0000000
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
