package com.theplumteam.client.gui;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.widget.CollectionEntry;
import com.theplumteam.client.gui.widget.CollectionListWidget;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.client.gui.widget.LinkButton;
import com.theplumteam.client.token.ClientTokenManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.TokenType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
    private Button useRegularButton;
    private Button useSpecialButton;
    private Button doneButton;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // Constants
    private static final int MIN_PANEL_WIDTH = 500;
    private static final int MAX_PANEL_WIDTH = 1200;
    private static final int MIN_PANEL_HEIGHT = 400;

    // Icon textures
    private static final ResourceLocation DISCORD_ICON = new ResourceLocation("blockpops", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = new ResourceLocation("blockpops", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = new ResourceLocation("blockpops", "textures/gui/modrinth_icon.png");

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/blockpops";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/blockpops";

    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId) {
        super(Component.literal("Claw Machine Configuration"));
        this.blockPos = blockPos;
        this.selectedCollectionId = currentCollectionId;
        this.collections = new ArrayList<>(CollectionRegistry.getAllCollections());

        // Sort collections to show players' collection first
        this.collections.sort((c1, c2) -> {
            boolean c1IsPlayers = "world_players".equals(c1.getId());
            boolean c2IsPlayers = "world_players".equals(c2.getId());
            if (c1IsPlayers && !c2IsPlayers) return -1;
            if (!c1IsPlayers && c2IsPlayers) return 1;
            return 0; // Keep original order for other collections
        });

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
        // Bottom section: token info text + spacing + token buttons + spacing + done button + padding
        int tokenInfoHeight = font.lineHeight + scaledSpacing; // Space for token info text above buttons
        int extraBottomSpacing = 5; // Additional spacing between lists and token info
        int bottomSectionHeight = tokenInfoHeight + (scaledComponentHeight * 2) + scaledSpacing + scaledPadding + extraBottomSpacing;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create collection list on the left (full height - header will render on top)
        int collectionHeaderHeight = 30; // Space for "Collections" header (for rendering only)
        collectionListWidget = new CollectionListWidget(
            this,
            this.minecraft,
            leftPanelWidth,
            listHeight, // Full height - allow scrolling under header
            yPos, // Start at same Y as before
            55 // Entry height - adjusted for optimal spacing
        );
        collectionListWidget.setLeftPos(componentX);
        collectionListWidget.setRenderBackground(false);
        collectionListWidget.setRenderTopAndBottom(false);
        this.addRenderableWidget(collectionListWidget);

        // Load collections
        loadCollections();

        // Create figure list on the right (full height - header will render on top)
        int previewX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int headerHeight = 50; // Space for collection name, count, and separator (for rendering only)
        int figureListY = yPos; // Start at same Y as collection list
        int figureListHeight = listHeight; // Full height - allow scrolling under header

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

        // Token buttons (side by side)
        bottomY -= (scaledComponentHeight + scaledSpacing);
        int buttonWidth = (fullComponentWidth - scaledSpacing) / 2;

        // Use Regular Token button (left)
        useRegularButton = Button.builder(Component.literal("Use Regular Token"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                LOGGER.info("Using regular token for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId, TokenType.REGULAR);
                BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
            }
        }).bounds(fullWidthX, bottomY, buttonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(useRegularButton);

        // Use Guaranteed Token button (right)
        useSpecialButton = Button.builder(Component.literal("Use Guaranteed Token"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                LOGGER.info("Using guaranteed token for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId, TokenType.GUARANTEED);
                BlockPopsModForge.NETWORK_CHANNEL.sendToServer(packet);
            }
        }).bounds(fullWidthX + buttonWidth + scaledSpacing, bottomY, buttonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(useSpecialButton);

        updateTokenButtonStates();

        // --- Top-Right Link Buttons (Discord, CurseForge, Modrinth) ---
        int buttonSize = 24; // Larger button size (previously scaledComponentHeight which was 20)
        int linkButtonY = panelY + scaledPadding;

        // Discord button (far right)
        int discordButtonX = panelX + panelWidth - buttonSize - scaledPadding;
        this.addRenderableWidget(new LinkButton(
                discordButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                DISCORD_ICON,
                DISCORD_URL,
                Component.literal("Join our Discord!")
        ));

        // CurseForge button (left of Discord)
        int curseforgeButtonX = discordButtonX - buttonSize - scaledSpacing;
        this.addRenderableWidget(new LinkButton(
                curseforgeButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                CURSEFORGE_ICON,
                CURSEFORGE_URL,
                Component.literal("Visit our CurseForge page")
        ));

        // Modrinth button (left of CurseForge)
        int modrinthButtonX = curseforgeButtonX - buttonSize - scaledSpacing;
        this.addRenderableWidget(new LinkButton(
                modrinthButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                MODRINTH_ICON,
                MODRINTH_URL,
                Component.literal("Visit our Modrinth page")
        ));
    }

    /**
     * Calculate panel dimensions based on screen size
     */
    private void calculatePanelDimensions() {
        int desiredWidth = (int)(this.width * 0.8f);  // Increased from 0.7 to 0.8 (10% increase)
        int desiredHeight = (int)(this.height * 0.85f); // Increased from 0.8 to 0.85 (5% increase)

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

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background (darken screen)
        renderBackground(graphics);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render widgets (buttons, lists, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render token information header (replacing title)
        renderTokenInfo(graphics);

        // Render collection list header (after widgets so it appears on top of scrollable content)
        renderCollectionListHeader(graphics);

        // Render figure panel header (after widgets so it appears on top of scrollable content)
        renderFigurePanelHeader(graphics);
    }

    /**
     * Render the header for the collection list panel
     */
    private void renderCollectionListHeader(GuiGraphics graphics) {
        if (collectionListWidget == null) {
            return;
        }

        int scaledPadding = 10;
        int scaledComponentHeight = 20;
        int leftPanelWidth = (int) (panelWidth * 0.45f);
        int componentX = panelX + scaledPadding;

        // Header starts at the top of the panel
        int headerStartY = panelY + scaledPadding;
        int headerHeight = scaledComponentHeight + scaledPadding; // Full top section height

        int currentY = headerStartY + 4;

        // Collection list title
        graphics.drawString(this.font, "Collections",
                          componentX + 8, currentY, 0xFFFFFF, false);
        currentY += font.lineHeight + 4;

        // Collection count
        String collectionCount = collections.size() + " collections available";
        graphics.drawString(this.font, collectionCount,
                          componentX + 8, currentY, 0xAAAAAA, false);

        // Separator line (below the collection count)
        currentY += font.lineHeight + 4;
        graphics.fill(componentX + 8, currentY, componentX + leftPanelWidth - 8, currentY + 1, 0x40FFFFFF);
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
        int scaledComponentHeight = 20;
        int rightPanelWidth = (int) (panelWidth * 0.50f);
        int previewX = panelX + panelWidth - rightPanelWidth - scaledPadding;

        // Header starts at the top of the panel
        int headerStartY = panelY + scaledPadding;

        int currentY = headerStartY + 4;

        // Collection name
        graphics.drawString(this.font, collection.getName(),
                          previewX + 8, currentY, 0xFFFFFF, false);
        currentY += font.lineHeight + 4;

        // Figure count
        String figureCount = collection.getFigures().size() + " figures in this collection";
        graphics.drawString(this.font, figureCount,
                          previewX + 8, currentY, 0xAAAAAA, false);

        // Separator line (full width - will render on top of buttons)
        currentY += font.lineHeight + 4;
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
            updateTokenButtonStates();
        }
    }

    /**
     * Update the token button states based on selection and token availability
     */
    private void updateTokenButtonStates() {
        boolean hasSelection = selectedCollectionId != null && !selectedCollectionId.isEmpty();

        if (useRegularButton != null) {
            useRegularButton.active = hasSelection && ClientTokenManager.getRegularTokens() > 0;
        }

        if (useSpecialButton != null) {
            boolean collectionComplete = hasSelection && isCollectionComplete();
            useSpecialButton.active = hasSelection && ClientTokenManager.hasSpecialToken() && !collectionComplete;
        }
    }

    /**
     * Check if the selected collection is complete (all figures discovered)
     */
    private boolean isCollectionComplete() {
        if (selectedCollectionId == null || selectedCollectionId.isEmpty()) {
            return false;
        }

        return CollectionRegistry.getCollection(selectedCollectionId)
                .map(collection -> {
                    for (FigureDefinition figure : collection.getFigures()) {
                        String figureId = collection.getId() + ":" + figure.getId();
                        if (!ClientDiscoveryManager.isDiscovered(figureId)) {
                            return false;
                        }
                    }
                    return true;
                })
                .orElse(false);
    }

    /**
     * Render token information centered above each respective button
     */
    private void renderTokenInfo(GuiGraphics graphics) {
        int scaledPadding = 10;
        int scaledSpacing = 6;
        int scaledComponentHeight = 20;

        // Calculate position to be above the token buttons
        int bottomY = panelY + panelHeight - scaledPadding;
        bottomY -= scaledComponentHeight; // Done button
        bottomY -= (scaledComponentHeight + scaledSpacing); // Token buttons
        int tokenInfoY = bottomY - font.lineHeight - scaledSpacing; // Above the token buttons

        // Colors
        int blueColor = 0x5599FF;  // Blue for regular tokens
        int goldColor = 0xFFD700;  // Gold for guaranteed tokens

        // Calculate button positions (same as in init())
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);
        int buttonWidth = (fullComponentWidth - scaledSpacing) / 2;

        // Regular token section (centered above left button)
        int regularTokens = ClientTokenManager.getRegularTokens();
        String regularText = "Regular Tokens: " + regularTokens + "/3";
        if (regularTokens < 3) {
            String nextRegularTime = ClientTokenManager.formatNextRegularTime();
            regularText += " - Next: " + nextRegularTime;
        }

        // Center text above regular button
        int regularButtonCenterX = fullWidthX + (buttonWidth / 2);
        int regularTextWidth = font.width(regularText);
        int regularTextX = regularButtonCenterX - (regularTextWidth / 2);
        graphics.drawString(this.font, regularText, regularTextX, tokenInfoY, blueColor, false);

        // Guaranteed token section (centered above right button)
        boolean hasSpecial = ClientTokenManager.hasSpecialToken();
        String specialText = "Guaranteed Token: " + (hasSpecial ? "Available" : "Used");
        if (!hasSpecial) {
            String nextSpecialTime = ClientTokenManager.formatNextSpecialResetTime();
            specialText += " - Resets: " + nextSpecialTime;
        }

        // Center text above guaranteed button
        int specialButtonCenterX = fullWidthX + buttonWidth + scaledSpacing + (buttonWidth / 2);
        int specialTextWidth = font.width(specialText);
        int specialTextX = specialButtonCenterX - (specialTextWidth / 2);
        graphics.drawString(this.font, specialText, specialTextX, tokenInfoY, goldColor, false);
    }

    @Override
    public void tick() {
        super.tick();
        // Update button states each tick to reflect token changes
        updateTokenButtonStates();
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
