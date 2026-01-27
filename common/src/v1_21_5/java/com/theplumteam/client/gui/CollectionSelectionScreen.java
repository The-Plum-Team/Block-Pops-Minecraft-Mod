package com.theplumteam.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.widget.CollectionEntry;
import com.theplumteam.client.gui.widget.CollectionListWidget;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.client.gui.widget.LinkButton;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.client.token.ClientTokenManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.TokenType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
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

    // Color transition fields
    private float currentColorR;
    private float currentColorG;
    private float currentColorB;
    private float targetColorR;
    private float targetColorG;
    private float targetColorB;
    private float colorTransitionProgress = 1.0f; // 0.0 = current, 1.0 = target
    private static final float COLOR_TRANSITION_SPEED = 0.05f; // Higher = faster transition
    private static final float COLOR_SNAP_THRESHOLD = 0.95f; // Snap to target when progress >= this value

    // GUI scale management
    private boolean guiScaleForced = false;
    private boolean isClosing = false;

    // Icon textures
    private static final ResourceLocation DISCORD_ICON = ResourceLocation.fromNamespaceAndPath("blockpops", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = ResourceLocation.fromNamespaceAndPath("blockpops", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = ResourceLocation.fromNamespaceAndPath("blockpops", "textures/gui/modrinth_icon.png");

    // Icon textures
    private static final ResourceLocation SETTINGS_ICON = ResourceLocation.fromNamespaceAndPath("blockpops", "textures/gui/settings_icon.png");

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/blockpops";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/blockpops";

    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId) {
        super(Component.literal("Claw Machine Configuration"));
        this.blockPos = blockPos;
        this.selectedCollectionId = currentCollectionId;
        // Filter out the default collection from the menu
        this.collections = CollectionRegistry.getAllCollections().stream()
                .filter(c -> !"default".equals(c.getId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Sort collections to show players' collection first
        this.collections.sort((c1, c2) -> {
            boolean c1IsPlayers = "world_players".equals(c1.getId());
            boolean c2IsPlayers = "world_players".equals(c2.getId());
            if (c1IsPlayers && !c2IsPlayers) return -1;
            if (!c1IsPlayers && c2IsPlayers) return 1;
            return 0; // Keep original order for other collections
        });

        // Initialize color transition with current background color
        ClientConfig config = ClientConfig.getInstance();
        this.currentColorR = config.backgroundColorR;
        this.currentColorG = config.backgroundColorG;
        this.currentColorB = config.backgroundColorB;
        this.targetColorR = config.backgroundColorR;
        this.targetColorG = config.backgroundColorG;
        this.targetColorB = config.backgroundColorB;
        this.colorTransitionProgress = 1.0f; // Start with no transition

        BlockPopsMod.logDebug("CollectionSelectionScreen opened at {} with current collection: {}",
                blockPos, currentCollectionId);
    }

    @Override
    protected void init() {
        // Pre-initialize the shared figure renderer to avoid lag on first render
        FigureWidgetRenderer.ensureInitialized();

        // Force GUI scale for consistent appearance
        if (!guiScaleForced && !isClosing) {
            guiScaleForced = true;
            int optimalScale = GuiScaleManager.getOptimalMenuScale();
            if (GuiScaleManager.setMenuGuiScale(optimalScale)) {
                // Scale was changed and resizeDisplay() was called, which will trigger init() again
                return;
            }
        }

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
        collectionListWidget.setXPosition(componentX);
        // Add for input handling only - we'll render manually outside the scaled pose
        this.addWidget(collectionListWidget);

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
        figureListWidget.setXPosition(previewX);
        // Add for input handling only - we'll render manually outside the scaled pose
        this.addWidget(figureListWidget);

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
                BlockPopsMod.logDebug("Using regular token for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId, TokenType.REGULAR);
                packet.sendToServer();
                this.onClose(); // Close the screen immediately after using a token
            }
        }).bounds(fullWidthX, bottomY, buttonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(useRegularButton);

        // Use Guaranteed Token button (right)
        useSpecialButton = Button.builder(Component.literal("Use Guaranteed Token"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                BlockPopsMod.logDebug("Using guaranteed token for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId, TokenType.GUARANTEED);
                packet.sendToServer();
                this.onClose(); // Close the screen immediately after using a token
            }
        }).bounds(fullWidthX + buttonWidth + scaledSpacing, bottomY, buttonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(useSpecialButton);

        updateTokenButtonStates();

        // --- Top-Right Link Buttons (Settings, Discord, CurseForge, Modrinth) ---
        int buttonSize = 24; // Larger button size (previously scaledComponentHeight which was 20)
        int linkButtonY = panelY + scaledPadding;

        // Settings button (far right)
        int settingsButtonX = panelX + panelWidth - buttonSize - scaledPadding;
        this.addRenderableWidget(new LinkButton(
                settingsButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                SETTINGS_ICON,
                null, // No URL, will be handled differently
                Component.literal("Settings")
        ) {
            @Override
            public void onPress() {
                openSettingsScreen();
            }
        });

        // Discord button (left of Settings)
        int discordButtonX = settingsButtonX - buttonSize - scaledSpacing;
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
     * Calculate panel dimensions based on screen size.
     * Uses virtual dimensions when inverse scale is active (shaders).
     */
    private void calculatePanelDimensions() {
        // Use virtual dimensions when shaders are active for consistent layout
        int screenWidth = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualWidth() : this.width;
        int screenHeight = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualHeight() : this.height;

        int desiredWidth = (int)(screenWidth * 0.8f);
        int desiredHeight = (int)(screenHeight * 0.85f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, screenWidth - 60)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                screenHeight - 60
        );

        // Center the panel
        panelX = (screenWidth - panelWidth) / 2;
        panelY = (screenHeight - panelHeight) / 2;
    }

    @Override
    public void removed() {
        super.removed();
        // Only restore GUI scale if we are actually closing (not just opening a modal)
        if (isClosing) {
            restoreGuiScaleIfNeeded();
        }
    }

    @Override
    public void onClose() {
        // Mark that we are truly closing (not just opening a modal)
        isClosing = true;
        // Restore GUI scale before closing
        restoreGuiScaleIfNeeded();
        super.onClose();
    }

    /**
     * Restore the original GUI scale if it was forced by this screen.
     */
    private void restoreGuiScaleIfNeeded() {
        if (guiScaleForced) {
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Transform mouse coordinates if using inverse scale (shaders active)
        int adjustedMouseX = mouseX;
        int adjustedMouseY = mouseY;
        if (GuiScaleManager.isUsingInverseScale()) {
            adjustedMouseX = (int) GuiScaleManager.transformMouseX(mouseX);
            adjustedMouseY = (int) GuiScaleManager.transformMouseY(mouseY);
        }

        // Apply inverse scale transformation if shaders are active
        if (GuiScaleManager.isUsingInverseScale()) {
            graphics.pose().pushPose();
            float scale = GuiScaleManager.getRenderScaleFactor();
            graphics.pose().scale(scale, scale, 1.0f);
        }

        // Render animated starry background
        renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render widgets (buttons, etc. - lists are rendered separately)
        super.render(graphics, adjustedMouseX, adjustedMouseY, partialTick);

        // Render token information header (replacing title)
        renderTokenInfo(graphics);

        // Render collection list header (after widgets so it appears on top of scrollable content)
        renderCollectionListHeader(graphics);

        // Render figure panel header (after widgets so it appears on top of scrollable content)
        renderFigurePanelHeader(graphics);

        // Pop the inverse scale transformation before rendering lists
        if (GuiScaleManager.isUsingInverseScale()) {
            graphics.pose().popPose();
        }

        // Render lists OUTSIDE the scaled pose - they handle their own scaling
        // Pass virtual mouse coordinates (same as mouseClicked) for consistency
        if (collectionListWidget != null) {
            collectionListWidget.render(graphics, adjustedMouseX, adjustedMouseY, partialTick);
        }
        if (figureListWidget != null) {
            figureListWidget.render(graphics, adjustedMouseX, adjustedMouseY, partialTick);
        }
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
     * Render animated starry background
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        ClientConfig config = ClientConfig.getInstance();

        // Update color transition progress
        if (colorTransitionProgress < 1.0f) {
            colorTransitionProgress = Math.min(1.0f, colorTransitionProgress + COLOR_TRANSITION_SPEED);

            // Snap to target when close enough to avoid imperceptible changes
            if (colorTransitionProgress >= COLOR_SNAP_THRESHOLD) {
                colorTransitionProgress = 1.0f;
            }

            // If transition is complete, update the ClientConfig
            if (colorTransitionProgress >= 1.0f) {
                config.backgroundColorR = targetColorR;
                config.backgroundColorG = targetColorG;
                config.backgroundColorB = targetColorB;
            }
        }

        // Determine which color to use
        float lerpedR, lerpedG, lerpedB;

        if (colorTransitionProgress >= 1.0f) {
            // No transition active - read directly from config for real-time updates from settings
            lerpedR = config.backgroundColorR;
            lerpedG = config.backgroundColorG;
            lerpedB = config.backgroundColorB;
        } else {
            // Transition in progress - interpolate between current and target colors
            lerpedR = Mth.lerp(colorTransitionProgress, currentColorR, targetColorR);
            lerpedG = Mth.lerp(colorTransitionProgress, currentColorG, targetColorG);
            lerpedB = Mth.lerp(colorTransitionProgress, currentColorB, targetColorB);
        }

        // 1. Fill with interpolated background color as a base layer
        int bgRed = (int)(lerpedR * 255);
        int bgGreen = (int)(lerpedG * 255);
        int bgBlue = (int)(lerpedB * 255);
        int bgColor = 0xFF000000 | (bgRed << 16) | (bgGreen << 8) | bgBlue;
        int bgWidth = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualWidth() : this.width;
        int bgHeight = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualHeight() : this.height;
        graphics.fill(0, 0, bgWidth, bgHeight, bgColor);

        // 2. Render the moving star pattern
        renderStarPattern(graphics, partialTick);
    }

    /**
     * Render the animated star pattern (OPTIMIZED - pre-tiled texture cache, 1 draw call)
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        // Calculate smooth scrolling offset
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;


        // Apply star color tint and opacity from config
        ClientConfig config = ClientConfig.getInstance();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        // Use the pre-tiled cached texture
        ResourceLocation cacheTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();

        // Calculate UV coordinates for smooth sub-pixel scrolling
        // The offset creates the scrolling effect via UV manipulation
        float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) (GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualWidth() : this.width) / (float) cacheWidth);
        float v1 = (float) (GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualHeight() : this.height) / (float) cacheHeight;

        // Render a single quad with the scrolling UV coordinates
        // In 1.21.5+, use GuiGraphics.innerBlit instead of BufferUploader
        int starHeight = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualHeight() : this.height;
        int starWidth = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualWidth() : this.width;

        // In 1.21.5+, use blit with RenderType::guiTextured
        graphics.blit(RenderType::guiTextured, cacheTexture, 0, 0, u0, v0, starWidth, starHeight, cacheWidth, cacheHeight);

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

            // Start color transition if collection has a background color
            if (collection.hasBackgroundColor()) {
                int[] bgColor = collection.getBackgroundColor();

                // Store current color as the starting point
                ClientConfig config = ClientConfig.getInstance();
                this.currentColorR = config.backgroundColorR;
                this.currentColorG = config.backgroundColorG;
                this.currentColorB = config.backgroundColorB;

                // Set target color
                this.targetColorR = bgColor[0] / 255.0f;
                this.targetColorG = bgColor[1] / 255.0f;
                this.targetColorB = bgColor[2] / 255.0f;

                // Check if transition animation is enabled
                if (config.enableColorTransition) {
                    // Reset transition progress to start the animation
                    this.colorTransitionProgress = 0.0f;
                } else {
                    // Instantly change color (no animation)
                    this.colorTransitionProgress = 1.0f;
                    config.backgroundColorR = this.targetColorR;
                    config.backgroundColorG = this.targetColorG;
                    config.backgroundColorB = this.targetColorB;
                }
            }

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
        BlockPopsMod.logDebug("Sending collection update - Position: {}, Collection ID: {}",
                blockPos, selectedCollectionId);
        ClawMachineCollectionPacket packet = new ClawMachineCollectionPacket(blockPos, selectedCollectionId);
        packet.sendToServer();
    }

    /**
     * Opens the settings screen as a modal overlay
     */
    private void openSettingsScreen() {
        this.minecraft.setScreen(new SettingsScreen(this));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
            float scale = GuiScaleManager.getMouseScaleFactor();
            dragX = dragX * scale;
            dragY = dragY * scale;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
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

    // renderBlurredBackground removed in 1.21.4 - no longer needed
    // The menu blur effect is handled differently in 1.21.4+
}
