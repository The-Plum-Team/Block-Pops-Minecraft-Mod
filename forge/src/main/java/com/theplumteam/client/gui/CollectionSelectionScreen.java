package com.theplumteam.client.gui;

import com.theplumteam.client.gui.widget.CollectionEntry;
import com.theplumteam.client.gui.widget.CollectionListWidget;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Polished collection selection screen for the Claw Machine
 * Inspired by modern UI design with panel-based layout
 */
public class CollectionSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionSelectionScreen.class);

    private final BlockPos blockPos;
    private String selectedCollectionId;
    private final List<FigureCollection> collections;

    // Widgets
    @Nullable
    private CollectionListWidget collectionListWidget;
    @Nullable
    private FigureListWidget figureListWidget;
    private Button dropBoxButton;
    private Button doneButton;
    private Button configButton;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // Constants
    private static final int MIN_PANEL_WIDTH = 500;
    private static final int MAX_PANEL_WIDTH = 800;
    private static final int MIN_PANEL_HEIGHT = 400;

    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId) {
        super(Component.literal("Claw Machine Configuration"));
        this.blockPos = blockPos;
        this.selectedCollectionId = currentCollectionId;
        this.collections = new ArrayList<>(CollectionRegistry.getAllCollections());
        LOGGER.info("CollectionSelectionScreen opened at {} with current collection: {}",
                    blockPos, currentCollectionId);
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        // Calculate panel dimensions
        calculatePanelDimensions();

        int scaledPadding = 10;
        int scaledSpacing = 6;
        int scaledComponentHeight = 20;

        // Calculate layout
        int leftPanelWidth = (int) (panelWidth * 0.45f);
        int rightPanelWidth = (int) (panelWidth * 0.50f);

        int componentX = panelX + scaledPadding;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        // Calculate heights
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding;
        int bottomSectionHeight = (scaledComponentHeight * 2) + scaledSpacing + scaledPadding;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create collection list on the left
        collectionListWidget = new CollectionListWidget(
            this,
            this.minecraft,
            leftPanelWidth,
            listHeight,
            yPos,
            36 // Entry height
        );
        collectionListWidget.setLeftPos(componentX);
        collectionListWidget.setRenderBackground(false);
        collectionListWidget.setRenderTopAndBottom(false);
        this.addRenderableWidget(collectionListWidget);

        // Load collections
        loadCollections();

        // Create figure list on the right (leave space for header)
        int previewX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int headerHeight = 50; // Space for collection name, count, and separator
        int figureListY = yPos + headerHeight;
        int figureListHeight = listHeight - headerHeight;

        figureListWidget = new FigureListWidget(
            this.minecraft,
            rightPanelWidth,
            figureListHeight,
            figureListY,
            90 // Entry height for figure cells
        );
        figureListWidget.setLeftPos(previewX);
        figureListWidget.setRenderBackground(false);
        figureListWidget.setRenderTopAndBottom(false);
        this.addRenderableWidget(figureListWidget);

        // Config button (gear icon) in top right of figure panel
        int configButtonSize = 20;
        int configButtonX = previewX + rightPanelWidth - configButtonSize - 4;
        int configButtonY = yPos + 4;
        configButton = Button.builder(Component.literal("⚙"), button -> {
            if (figureListWidget != null && minecraft != null) {
                minecraft.setScreen(new PreviewConfigScreen(this, figureListWidget));
            }
        }).bounds(configButtonX, configButtonY, configButtonSize, configButtonSize).build();
        this.addRenderableWidget(configButton);

        // Update preview if there's a selected collection
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
            CollectionRegistry.getCollection(selectedCollectionId).ifPresent(collection -> {
                if (figureListWidget != null) {
                    figureListWidget.setCollection(collection);
                }
            });
        }

        // Bottom buttons
        int bottomY = panelY + panelHeight - scaledPadding;
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);

        // Done button (bottom-most)
        bottomY -= scaledComponentHeight;
        doneButton = Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(fullWidthX, bottomY, fullComponentWidth, scaledComponentHeight)
                .build();
        this.addRenderableWidget(doneButton);

        // Drop Box button
        bottomY -= (scaledComponentHeight + scaledSpacing);
        dropBoxButton = Button.builder(Component.literal("Drop a Box from Selected Collection"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                LOGGER.info("Requesting box drop for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId);
                BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
            }
        }).bounds(fullWidthX, bottomY, fullComponentWidth, scaledComponentHeight).build();
        this.addRenderableWidget(dropBoxButton);
        updateDropBoxButtonState();
    }

    /**
     * Calculate panel dimensions based on screen size
     */
    private void calculatePanelDimensions() {
        int desiredWidth = (int)(this.width * 0.7f);
        int desiredHeight = (int)(this.height * 0.8f);

        panelWidth = Mth.clamp(
            desiredWidth,
            MIN_PANEL_WIDTH,
            Math.min(MAX_PANEL_WIDTH, this.width - 80)
        );

        panelHeight = Mth.clamp(
            desiredHeight,
            MIN_PANEL_HEIGHT,
            this.height - 80
        );

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background (darken screen)
        renderBackground(graphics);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render title
        graphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                panelY + 10,
                0xFFFFFF
        );

        // Render figure panel header
        renderFigurePanelHeader(graphics);

        // Render widgets (buttons, lists, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Render the header for the figure preview panel
     */
    private void renderFigurePanelHeader(GuiGraphics graphics) {
        if (figureListWidget == null) {
            return;
        }

        FigureCollection collection = figureListWidget.getCurrentCollection();
        if (collection == null) {
            return;
        }

        int scaledPadding = 10;
        int rightPanelWidth = (int) (panelWidth * 0.50f);
        int previewX = panelX + panelWidth - rightPanelWidth - scaledPadding;

        int scaledComponentHeight = 20;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        int currentY = yPos + 8;

        // Collection name
        graphics.drawString(this.font, collection.getName(),
                          previewX + 8, currentY, 0xFFFFFF, false);
        currentY += font.lineHeight + 4;

        // Figure count
        String figureCount = collection.getFigures().size() + " figures in this collection";
        graphics.drawString(this.font, figureCount,
                          previewX + 8, currentY, 0xAAAAAA, false);
        currentY += font.lineHeight + 8;

        // Separator line
        graphics.fill(previewX + 8, currentY, previewX + rightPanelWidth - 8, currentY + 1, 0x40FFFFFF);
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
     * Load collections into the list widget
     */
    private void loadCollections() {
        if (collectionListWidget == null) {
            return;
        }

        for (FigureCollection collection : collections) {
            collectionListWidget.addCollectionEntry(collection);
        }

        // Select the current collection if it exists
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
            collectionListWidget.selectByCollectionId(selectedCollectionId);
        }
    }

    /**
     * Called when a collection is selected from the list
     */
    public void onCollectionSelected(CollectionEntry entry) {
        if (entry != null) {
            FigureCollection collection = entry.getCollection();
            selectedCollectionId = collection.getId();

            // Update figure list widget
            if (figureListWidget != null) {
                figureListWidget.setCollection(collection);
            }

            // Send update to server
            sendUpdate();

            // Update button states
            updateDropBoxButtonState();
        }
    }

    /**
     * Update the drop box button state based on selection
     */
    private void updateDropBoxButtonState() {
        if (dropBoxButton != null) {
            dropBoxButton.active = selectedCollectionId != null && !selectedCollectionId.isEmpty();
        }
    }

    /**
     * Send collection update to server
     */
    private void sendUpdate() {
        LOGGER.info("Sending collection update - Position: {}, Collection ID: {}",
                    blockPos, selectedCollectionId);
        ClawMachineCollectionPacket packet = new ClawMachineCollectionPacket(blockPos, selectedCollectionId);
        BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Allow ESC to close
        if (keyCode == 256) { // ESC key
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
