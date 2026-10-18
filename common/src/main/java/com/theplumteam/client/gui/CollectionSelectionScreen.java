package com.theplumteam.client.gui;

import com.theplumteam.client.gui.util.GuiPose;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
//? if >=1.21.5 {
/*import net.minecraft.client.renderer.RenderType;
*///? } elif >=1.21 {
/*import com.mojang.blaze3d.vertex.BufferUploader;
*///? }
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//? if <26.2 {
import com.mojang.blaze3d.vertex.Tesselator;
//? }
//? if <26.3 {
import com.mojang.blaze3d.vertex.VertexFormat;
//? }
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.ClientSpecialAccess;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.widget.CollectionEntry;
import com.theplumteam.client.gui.widget.CollectionListWidget;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.client.gui.widget.TabButton;
import com.theplumteam.client.gui.widget.LinkButton;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.client.token.ClientTokenManager;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.TokenType;
import com.theplumteam.registry.ModItems;
import com.theplumteam.server.SpecialAccess;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
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
    // Which tab is open: the plush collections or the figure ones. Either machine shows both.
    private boolean plush;
    private String selectedCollectionId;
    // The selection the other tab keeps until it opens again
    private String otherTabSelection = "";
    // Whether init has run once; later runs (a resize, a tab switch) keep the selection
    private boolean opened;
    private List<FigureCollection> collections;
    // Revision of the server's hidden collections the list was last built from
    private int shownHiddenRevision;
    // Revision of the server's World collections the list was last built from
    private int shownWorldRevision = ClientWorldCollections.revision();

    // Widgets
    @Nullable
    private CollectionListWidget collectionListWidget;
    @Nullable
    private FigureListWidget figureListWidget;
    private Button useRegularButton;
    private Button useSpecialButton;
    private Button doneButton;
    @Nullable
    private TabButton.Folder selectedTab;

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
    private static final ResourceLocation DISCORD_ICON = ResourceLocations.of("blockpops", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = ResourceLocations.of("blockpops", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = ResourceLocations.of("blockpops", "textures/gui/modrinth_icon.png");

    // Icon textures
    private static final ResourceLocation SETTINGS_ICON = ResourceLocations.of("blockpops", "textures/gui/settings_icon.png");
    private static final ResourceLocation KOFI_ICON = ResourceLocations.of("blockpops", "textures/gui/kofi_icon.png");

    // The purple the BlockPops-Hytale project gives its specials
    private static final int SPECIAL_COLOR = 0xFFBB8FCE;

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/blockpops";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/blockpops";

    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId) {
        this(blockPos, currentCollectionId, false);
    }

    /**
     * With plush set this is the gacha machine's menu, which opens on the plush tab. A machine
     * set to a collection of the other kind opens on that kind's tab instead.
     */
    public CollectionSelectionScreen(BlockPos blockPos, String currentCollectionId, boolean plush) {
        super(Component.literal(plush ? "Gacha Machine Configuration" : "Claw Machine Configuration"));
        this.blockPos = blockPos;
        this.plush = currentCollectionId == null ? plush
                : CollectionRegistry.getCollection(currentCollectionId).map(FigureCollection::isPlush).orElse(plush);
        this.selectedCollectionId = currentCollectionId;
        this.collections = collectionsOf(this.plush);

        // Initialize color transition with current background color
        ClientConfig config = ClientConfig.getInstance();
        this.currentColorR = config.backgroundColorR;
        this.currentColorG = config.backgroundColorG;
        this.currentColorB = config.backgroundColorB;
        this.targetColorR = config.backgroundColorR;
        this.targetColorG = config.backgroundColorG;
        this.targetColorB = config.backgroundColorB;
        this.colorTransitionProgress = 1.0f; // Start with no transition

        // A draw left over from a screen that closed without onClose belongs to no screen now
        FigureDraw.reset();

        BlockPopsMod.logDebug("CollectionSelectionScreen opened at {} with current collection: {}",
                blockPos, currentCollectionId);
    }

    /**
     * One tab's collections, the World Players one first and this world's own collections
     * last in the server's order, without the default collection or an archived one.
     */
    private static List<FigureCollection> collectionsOf(boolean plush) {
        List<FigureCollection> list = (plush ? CollectionRegistry.getPlushCollections() : CollectionRegistry.getAllCollections()).stream()
                .filter(c -> !"default".equals(c.getId()))
                .filter(c -> !ClientWorldCollections.isArchived(c.getId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        // A stable sort: the other collections keep their order
        list.sort((c1, c2) -> Integer.compare(sortKey(c1.getId()), sortKey(c2.getId())));
        return list;
    }

    // World Players first, then the others, then World collections in the server's order: an
    // edited one is registered again, so the registry's order is not theirs
    private static int sortKey(String collectionId) {
        if ("world_players".equals(collectionId)) {
            return -1;
        }
        return WorldContentIds.isWorldCollectionId(collectionId) ? 1 + Math.max(0, ClientWorldCollections.order(collectionId)) : 0;
    }

    /**
     * Opens the other tab, with the selection it had when it was left. A tab opened for the first
     * time selects its first collection, as a click on it would.
     */
    private void switchTab(boolean toPlush) {
        if (toPlush == plush || FigureDraw.isBusy()) {
            return;
        }
        String leaving = selectedCollectionId == null ? "" : selectedCollectionId;
        plush = toPlush;
        selectedCollectionId = otherTabSelection;
        otherTabSelection = leaving;
        collections = collectionsOf(plush);
        rebuildWidgets();
        selectFirstCollectionIfNone();
    }

    /** Selects the open tab's first collection, as a click on it would, when nothing is selected. */
    private void selectFirstCollectionIfNone() {
        if ((selectedCollectionId == null || selectedCollectionId.isEmpty())
                && collectionListWidget != null && !collectionListWidget.children().isEmpty()) {
            CollectionEntry first = collectionListWidget.children().get(0);
            collectionListWidget.setSelected(first);
            onCollectionSelected(first);
        }
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
        collectionListWidget.setLeftPos(componentX);
        collectionListWidget.setRenderBackground(false);
        collectionListWidget.setRenderTopAndBottom(false);
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
        figureListWidget.setLeftPos(previewX);
        figureListWidget.setRenderBackground(false);
        figureListWidget.setRenderTopAndBottom(false);
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
                // The screen stays open to play the draw out
                FigureDraw.await(selectedCollectionId, false);
                updateTokenButtonStates();
            }
        }).bounds(fullWidthX, bottomY, buttonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(useRegularButton);

        // Use Guaranteed Token button (right)
        useSpecialButton = Button.builder(Component.literal("Use Guaranteed Token"), button -> {
            if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
                BlockPopsMod.logDebug("Using guaranteed token for collection: {}", selectedCollectionId);
                DropBoxPacket packet = new DropBoxPacket(blockPos, selectedCollectionId, TokenType.GUARANTEED);
                packet.sendToServer();
                // The screen stays open to play the draw out
                FigureDraw.await(selectedCollectionId, true);
                updateTokenButtonStates();
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
            //? if >=26 {
            /*public void onPress(net.minecraft.client.input.InputWithModifiers bpInput) {
            *///? } else {
            public void onPress() {
            //? }
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

        if (plush) {
            // Ko-fi button (left of Modrinth): the special plushes are for supporters
            int kofiButtonX = modrinthButtonX - buttonSize - scaledSpacing;
            this.addRenderableWidget(new LinkButton(
                    kofiButtonX,
                    linkButtonY,
                    buttonSize,
                    buttonSize,
                    KOFI_ICON,
                    SpecialAccess.SUPPORT_URL,
                    Component.literal("Support us on Ko-fi to get the special plushes")
            ));
        }

        // Folder tabs on the panel's top edge: the figure collections and the plush ones
        int panelBg = panelBackgroundColor();
        Component figuresLabel = Component.literal("Figures");
        TabButton.Folder figuresTab = new TabButton.Folder(panelX + scaledPadding, panelY, figuresLabel,
                new ItemStack(ModItems.CLAW_MACHINE_BLOCK_ITEM.get()), !plush, panelBg, button -> switchTab(false));
        TabButton.Folder plushesTab = new TabButton.Folder(figuresTab.right() + 2, panelY, Component.literal("Plushes"),
                new ItemStack(ModItems.GACHA_MACHINE_BLOCK_ITEM.get()), plush, panelBg, button -> switchTab(true));
        this.addRenderableWidget(figuresTab);
        this.addRenderableWidget(plushesTab);
        selectedTab = plush ? plushesTab : figuresTab;

        // A machine without a collection opens on the first one of its tab
        if (!opened) {
            opened = true;
            selectFirstCollectionIfNone();
        }
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
        FigureDraw.reset();
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

    //? if >=1.21 && <1.21.6 {
    /*@Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws its own opaque background before Screen.render, which on
        // 1.21 to 1.21.5 calls this vanilla pass and would blur the panel on top of it.
    }
    *///? }

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
            GuiPose.push(graphics);
            float scale = GuiScaleManager.getRenderScaleFactor();
            GuiPose.scale(graphics, scale, scale);
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
            GuiPose.pop(graphics);
        }

        // Render lists OUTSIDE the scaled pose - they handle their own scaling
        // Pass virtual mouse coordinates (same as mouseClicked) for consistency
        if (collectionListWidget != null) {
            collectionListWidget.render(graphics, adjustedMouseX, adjustedMouseY, partialTick);
        }
        if (figureListWidget != null) {
            figureListWidget.render(graphics, adjustedMouseX, adjustedMouseY, partialTick);
        }

        renderDrawResult(graphics, partialTick);
    }

    /**
     * Shows the figure a draw stopped on, in a card above everything else on the screen, centred
     * over the figure list, which it alone dims; the rest of the screen stays usable. Drawn in the
     * list's own space, which is scaled up when the inverse GUI scale is in use.
     */
    private void renderDrawResult(GuiGraphics graphics, float partialTick) {
        FigureDraw draw = FigureDraw.result();
        if (draw == null || figureListWidget == null) {
            return;
        }
        FigureEntry row = figureListWidget.rowOf(draw.winner().getId());
        if (row == null) {
            return;
        }
        // Everything drawn so far lands below the card: the list's models are drawn with depth,
        // so the card is also lifted in front of them
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);

        float scale = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getRenderScaleFactor() : 1.0f;
        int left = (int) (figureListWidget.left() * scale);
        int top = (int) (figureListWidget.top() * scale);
        int right = (int) (figureListWidget.right() * scale);
        int bottom = (int) (figureListWidget.bottom() * scale);
        graphics.fill(left, top, right, bottom, 0xB0000000);

        String name = draw.winner().getName();
        String label = draw.isHolographic()
                ? (draw.isNew() ? "✦ NEW & HOLOGRAPHIC! ✦" : "✦ HOLOGRAPHIC! ✦")
                : draw.isNew() ? "NEW!" : "Already in your collection";
        String hint = "Click or press Esc to close";
        int line = font.lineHeight + 4;
        int size = (int) Math.max(40, Math.min(120 * scale, bottom - top - 3 * line - 30));
        int cardWidth = Math.max(Math.max(size, font.width(hint)), Math.max(font.width(name), font.width(label))) + 40;
        int cardHeight = size + 3 * line + 20;
        int cardX = (left + right - cardWidth) / 2;
        int cardY = (top + bottom - cardHeight) / 2;
        // A holographic figure's frame and label run through the rainbow
        int holographicColor = 0xFF000000
                | net.minecraft.util.Mth.hsvToRgb((System.currentTimeMillis() % 2000L) / 2000.0F, 0.55F, 1.0F);
        int labelColor = draw.isHolographic() ? holographicColor : draw.isNew() ? 0xFFFFD700 : 0xFFAAAAAA;
        int frameColor = labelColor;
        graphics.fill(cardX - 2, cardY - 2, cardX + cardWidth + 2, cardY + cardHeight + 2, frameColor);
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xFF181818);
        GuiPose.flush(graphics);

        int textY = cardY + 8;
        graphics.drawString(font, label, cardX + (cardWidth - font.width(label)) / 2, textY, labelColor, false);
        row.renderPreview(graphics, draw.winner(), cardX + (cardWidth - size) / 2, textY + line, size, partialTick,
                draw.isHolographic());
        textY += line + size + 4;
        graphics.drawString(font, name, cardX + (cardWidth - font.width(name)) / 2, textY, 0xFFFFFFFF, false);
        textY += line;
        graphics.drawString(font, hint, cardX + (cardWidth - font.width(hint)) / 2, textY, 0xFF888888, false);
        GuiPose.pop(graphics);
    }

    /** The figure list, which a draw lights up. */
    @Nullable
    FigureListWidget figureList() {
        return figureListWidget;
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
                componentX + 8, currentY, 0xFFFFFFFF, false);
        currentY += font.lineHeight + 4;

        // Collection count
        String collectionCount = collectionListWidget.children().size() + " collections available";
        graphics.drawString(this.font, collectionCount,
                componentX + 8, currentY, 0xFFAAAAAA, false);

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
                previewX + 8, currentY, 0xFFFFFFFF, false);
        currentY += font.lineHeight + 4;

        // Figure count
        String figureCount = collection.getRegularFigures().size() + " figures"
                + (collection.hasSpecials() ? " and " + collection.getSpecialFigures().size() + " specials" : "")
                + " in this collection";
        graphics.drawString(this.font, figureCount,
                previewX + 8, currentY, 0xFFAAAAAA, false);

        // Tell a player the server gives this collection's specials to
        if (collection.hasSpecials() && ClientSpecialAccess.has(collection.getId())) {
            graphics.drawString(this.font, "Special access",
                    previewX + 8 + font.width(figureCount) + 8, currentY, SPECIAL_COLOR, false);
        }

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

        //? if <1.21.5 {
        // Finish buffered base fills before the immediate textured quad draws.
        GuiPose.flush(graphics);
        //? }

        // 2. Render the moving star pattern
        renderStarPattern(graphics, partialTick);

        // Close the background layer so the panel and its text draw above it
        GuiPose.nextStratum(graphics);
    }

    /**
     * Render the animated star pattern (OPTIMIZED - pre-tiled texture cache, 1 draw call)
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        int starWidth = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualWidth() : this.width;
        int starHeight = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getVirtualHeight() : this.height;
        drawStarPattern(graphics, starWidth, starHeight, partialTick);
    }

    /**
     * Draws the scrolling star pattern over an area from the top left corner, in the star
     * colour and opacity of the client config. The World collection screens draw it too.
     */
    static void drawStarPattern(GuiGraphics graphics, int starWidth, int starHeight, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        // Calculate smooth scrolling offset
        //? if >=26.2 {
        /*// 26.2 dropped the GUI tick counter; wall time drives the same scroll.
        double smoothTime = net.minecraft.util.Util.getMillis() / 1000.0;
        *///? } else {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        //? }
        double offsetX = (com.theplumteam.util.E2EDeterminism.scrollSeconds(smoothTime) * pixelsPerSecond) % tileSize;

        // Apply star color tint and opacity from config
        ClientConfig config = ClientConfig.getInstance();

        // Use the pre-tiled cached texture
        StarPatternCache.ensureSmoothFiltering();
        ResourceLocation cacheTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();

        //? if >=1.21.6 {
        /*// 1.21.6 selects the GUI shader by render pipeline rather than render type.
        int argb = ((int) (config.starOpacity * 255) << 24)
                | ((int) (config.starColorR * 255) << 16)
                | ((int) (config.starColorG * 255) << 8)
                | (int) (config.starColorB * 255);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, cacheTexture,
                0, 0, (float) offsetX, 0.0f, starWidth, starHeight, cacheWidth, cacheHeight, argb);
        *///? } elif >=1.21.5 {
        /*// 1.21.5 tints and blends inside the GUI render type, so the scrolling quad is
        // one blit with a packed colour instead of a hand-built buffer.
        int argb = ((int) (config.starOpacity * 255) << 24)
                | ((int) (config.starColorR * 255) << 16)
                | ((int) (config.starColorG * 255) << 8)
                | (int) (config.starColorB * 255);
        graphics.blit(RenderType::guiTextured, cacheTexture, 0, 0, (float) offsetX, 0.0f,
                starWidth, starHeight, cacheWidth, cacheHeight, argb);
        *///? } elif >=1.21.2 {
        /*float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);
        // GUI fills leave a different program bound; select the textured 1.21.4 shader.
        RenderSystem.setShader(net.minecraft.client.renderer.CoreShaders.POSITION_TEX);

        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(pose.last().pose(), 0, starHeight, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, starHeight, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        *///? } elif >=1.21 {
        /*float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(pose.last().pose(), 0, starHeight, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, starHeight, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        *///? } else {
        float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(pose.last().pose(), 0, starHeight, 0).uv(u0, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), starWidth, starHeight, 0).uv(u1, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), starWidth, 0, 0).uv(u1, v0).endVertex();
        bufferBuilder.vertex(pose.last().pose(), 0, 0, 0).uv(u0, v0).endVertex();
        tesselator.end();

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        //? }
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent with configurable opacity)
        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                panelBackgroundColor()
        );

        // Panel outline (subtle white), open under the selected tab
        if (selectedTab != null) {
            graphics.fill(panelX, panelY, selectedTab.left() + 1, panelY + 1, 0x60FFFFFF);
            graphics.fill(selectedTab.right() - 1, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        } else {
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        }
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
    }

    /** Black with the configured panel opacity. */
    private static int panelBackgroundColor() {
        int alpha = (int) (ClientConfig.getInstance().panelOpacity * 255);
        return alpha << 24;
    }

    /**
     * Load collections into the list widget
     */
    private void loadCollections() {
        if (collectionListWidget == null) {
            return;
        }

        // Start over: the server can change its hidden collections while the screen is open
        collectionListWidget.clearAllEntries();
        collectionListWidget.setScrollAmount(0);
        shownHiddenRevision = ClientHiddenCollections.getRevision();

        for (FigureCollection collection : collections) {
            if (!ClientHiddenCollections.isHidden(collection.getId())) {
                collectionListWidget.addCollectionEntry(collection);
            }
        }

        // A hidden or archived collection is not offered, so it cannot stay this machine's
        // selection, and neither can a World collection that was removed
        if (selectedCollectionId != null && (ClientHiddenCollections.isHidden(selectedCollectionId)
                || ClientWorldCollections.isArchived(selectedCollectionId)
                || (WorldContentIds.isWorldCollectionId(selectedCollectionId)
                        && CollectionRegistry.getCollection(selectedCollectionId).isEmpty()))) {
            selectedCollectionId = "";
            if (figureListWidget != null) {
                figureListWidget.setCollection(null);
            }
        }

        // An edit registers the collection again: show its figures as they are now
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty() && figureListWidget != null
                && figureListWidget.getCurrentCollection() != null && !FigureDraw.isBusy()) {
            CollectionRegistry.getCollection(selectedCollectionId)
                    .filter(collection -> collection != figureListWidget.getCurrentCollection())
                    .ifPresent(figureListWidget::setCollection);
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
        // One draw at a time: a spent token's draw has to play out first
        boolean hasSelection = selectedCollectionId != null && !selectedCollectionId.isEmpty() && !FigureDraw.isBusy();

        if (useRegularButton != null) {
            useRegularButton.active = hasSelection && ClientTokenManager.getRegularTokens() > 0;
        }

        if (useSpecialButton != null) {
            boolean collectionComplete = hasSelection && isCollectionComplete();
            useSpecialButton.active = hasSelection && ClientTokenManager.hasSpecialToken() && !collectionComplete;
        }
    }

    /**
     * Check if the selected collection is complete (every figure a token can give is discovered)
     */
    private boolean isCollectionComplete() {
        if (selectedCollectionId == null || selectedCollectionId.isEmpty()) {
            return false;
        }

        return CollectionRegistry.getCollection(selectedCollectionId)
                .map(collection -> {
                    for (FigureDefinition figure : ClientSpecialAccess.figuresFor(collection)) {
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
        int blueColor = 0xFF5599FF;  // Blue for regular tokens
        int goldColor = 0xFFFFD700;  // Gold for guaranteed tokens

        // Calculate button positions (same as in init())
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);
        int buttonWidth = (fullComponentWidth - scaledSpacing) / 2;

        // Regular token section (centered above left button)
        int regularTokens = ClientTokenManager.getRegularTokens();
        // Blue tickets can take the count past the 3 the server grants on its own
        String regularText = "Regular Tokens: " + regularTokens + (regularTokens <= 3 ? "/3" : "");
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
        int guaranteedTokens = ClientTokenManager.getGuaranteedTokens();
        String specialText = guaranteedTokens > 1 ? "Guaranteed Tokens: " + guaranteedTokens
                : "Guaranteed Token: " + (guaranteedTokens == 1 ? "Available" : "Used");
        if (guaranteedTokens == 0) {
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
        // Rebuild the list when the server changes its hidden collections
        if (shownHiddenRevision != ClientHiddenCollections.getRevision()) {
            loadCollections();
        }
        // and when its World collections change, which can add, edit or archive one; a
        // draw in progress keeps the figures it is showing until it ends
        if (shownWorldRevision != ClientWorldCollections.revision() && !FigureDraw.isBusy()) {
            shownWorldRevision = ClientWorldCollections.revision();
            collections = collectionsOf(plush);
            loadCollections();
        }
        FigureDraw.tick();
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
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        if (blocksForDraw(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        if (blocksForDraw(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    // While a draw's light moves only Done is clickable. Once it shows its figure, any click puts
    // the figure away; one on the figure list does only that, one elsewhere also does its own job
    private boolean blocksForDraw(double mouseX, double mouseY) {
        if (FigureDraw.result() != null) {
            FigureDraw.dismissResult();
            return isOverFigureList(mouseX, mouseY);
        }
        return FigureDraw.isBusy() && !(doneButton != null && doneButton.isMouseOver(mouseX, mouseY));
    }

    private boolean isOverFigureList(double mouseX, double mouseY) {
        return figureListWidget != null
                && mouseX >= figureListWidget.left() && mouseX < figureListWidget.right()
                && mouseY >= figureListWidget.top() && mouseY < figureListWidget.bottom();
    }

    @Override
    //? if >=26 {
    /*public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent bpEvent) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()));
    }
    *///? } else {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    //? }

    @Override
    //? if >=26 {
    /*public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent bpEvent, double dragX, double dragY) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
            float scale = GuiScaleManager.getMouseScaleFactor();
            dragX = dragX * scale;
            dragY = dragY * scale;
        }
        return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY, bpEvent.buttonInfo()), dragX, dragY);
    }
    *///? } else {
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
    //? }

    @Override
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //? }
        if (GuiScaleManager.isUsingInverseScale()) {
            mouseX = GuiScaleManager.transformMouseX(mouseX);
            mouseY = GuiScaleManager.transformMouseY(mouseY);
        }
        // The figure list holds still while a draw lights its figures or shows the one it drew
        if (FigureDraw.isBusy() || (FigureDraw.result() != null && isOverFigureList(mouseX, mouseY))) {
            return true;
        }
        //? if >=1.21 {
        /*return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
        *///? } else {
        return super.mouseScrolled(mouseX, mouseY, delta);
        //? }
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
    //? if >=26 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent bpEvent) {
        int keyCode = bpEvent.key();
        int scanCode = bpEvent.scancode();
        int modifiers = bpEvent.modifiers();
        // Allow ESC to close, after it has put away a figure a draw shows
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            if (FigureDraw.result() != null) {
                FigureDraw.dismissResult();
            } else {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(bpEvent);
    }
    *///? } else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Allow ESC to close, after it has put away a figure a draw shows
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            if (FigureDraw.result() != null) {
                FigureDraw.dismissResult();
            } else {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //? }
}
