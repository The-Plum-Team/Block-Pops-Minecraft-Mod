package com.theplumteam.client.gui;

import com.theplumteam.client.gui.style.HalftoneTransition;
import com.theplumteam.client.gui.style.MenuGuiScale;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.TokenTicket;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
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
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.blockentity.GachaMachineBlockEntity;
import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.ClientServerSettings;
import com.theplumteam.client.ClientSpecialAccess;
import com.theplumteam.client.ClientSupportCheckout;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
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
import com.theplumteam.figure.DrawRules;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.TokenType;
import com.theplumteam.registry.ModItems;
import com.theplumteam.registry.ModSounds;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.theplumteam.figure.LocalNames;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The claw and gacha machines' menu, laid out as Quick Skin's menus are: no panel, but groups of
 * controls standing on the machine's starry background. The figure and plush tabs and the link
 * buttons share the top row; under them the collections and the selected collection's figures
 * stand in two columns, each on a translucent backdrop; the token buttons and Done run along the
 * bottom, with the token counts above their buttons.
 *
 * <p>The menu draws at its own GUI scale, chosen from the window size ({@link MenuGuiScale}), so
 * it looks the same whatever scale the player picked, and lays itself out from the canvas that
 * gives: the two columns stand in a block centred on it, the figure column as wide as a grid of
 * up to four cells needs.
 *
 * <p>It opens with a {@link HalftoneTransition}: a ring of black halftone dots travels from the
 * middle outward, with the world the menu opened over beyond it and the menu inside it. The world there
 * is the frame captured when the menu opened, so the menu takes its own scale at once under it
 * and the hotbar never changes size on screen. Where that frame is read back from the GPU, from
 * 1.21.5, the menu is laid out a frame or two later, in a tick, which comes before the frame it
 * draws.
 */
public class CollectionSelectionScreen extends Screen implements MenuGuiScale.Scaled {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionSelectionScreen.class);

    private final BlockPos blockPos;
    // Which tab is open: the plush collections or the figure ones. Either machine shows both.
    private boolean plush;
    private String selectedCollectionId;
    // The collection the machine itself was last known to hold
    private String machineCollectionId;
    // The selection the other tab keeps until it opens again
    private String otherTabSelection = "";
    // Whether init has run once; later runs (a resize, a tab switch) keep the selection
    private boolean opened;
    // The black the menu opens behind, and whether it has been laid out behind it yet
    private final HalftoneTransition opening = new HalftoneTransition();
    private boolean menuShown;
    // Which version each figure's cell shows, by "collection:figure", kept while the menu is open
    // so a resize or a tab switch, which make the figure list again, leaves them as they were
    private final Map<String, Integer> shownSkins = new HashMap<>();
    private List<FigureCollection> collections;
    // Revision of the server's hidden collections the list was last built from
    private int shownHiddenRevision;
    // Revision of the server's World collections the list was last built from
    private int shownWorldRevision = ClientWorldCollections.revision();
    // Whether the creator's button was shown, to rebuild only when it comes or goes
    private boolean shownCreator;

    // Widgets
    @Nullable
    private CollectionListWidget collectionListWidget;
    @Nullable
    private FigureListWidget figureListWidget;
    private Button useRegularButton;
    private Button useSpecialButton;
    private Button doneButton;

    // The layout, from the canvas: the two columns between the top row and the token row
    private int bodyTop;
    private int bodyBottom;
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private int tokenInfoY;
    // The top row's free stretch, between the tabs and the link buttons, and its link buttons' top
    private int tabsRight;
    private int linksLeft;
    private int linksTop;
    private int regularCenterX;
    private int specialCenterX;
    private int tokenButtonWidth;
    // Where each token line's info icon was last drawn, its left and top
    private int regularInfoX, regularInfoY, specialInfoX, specialInfoY;

    private static final int MARGIN = 8;
    private static final int GAP = 6;
    // A column's header: its title over a line of detail, then a hairline
    private static final int COLUMN_HEADER = 30;
    private static final int LINK_SIZE = 24;
    private static final int LINK_SPACING = 3;
    private static final float BUTTON_HEIGHT_SHARE = 0.075f;
    private static final int MIN_BUTTON_HEIGHT = 22;
    private static final int MAX_BUTTON_HEIGHT = 28;
    // The collection cards, as Quick Skin's skin cards: the card and the gap under it
    private static final int COLLECTION_ROW = 48;
    // The figure cells: four columns of the usual size, shrinking in a narrow window to keep
    // four, or three, columns
    private static final int MIN_CELL_FOR_FOUR = 60;
    // Room the figure list takes beside its cells: the row's inset and the scroll bar
    private static final int GRID_CHROME = 24;
    private static final int MIN_CELL_FOR_THREE = 56;
    private static final int ROW_GAP = 10;

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

    // Icon textures
    private static final ResourceLocation DISCORD_ICON = ResourceLocations.of("blockpops", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = ResourceLocations.of("blockpops", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = ResourceLocations.of("blockpops", "textures/gui/modrinth_icon.png");
    private static final ResourceLocation PALETTE_ICON = ResourceLocations.of("blockpops", "textures/gui/palette_icon.png");
    private static final ResourceLocation SETTINGS_ICON = ResourceLocations.of("blockpops", "textures/gui/settings_icon.png");
    // A cup with a heart: support us
    private static final ResourceLocation SUPPORT_ICON = ResourceLocations.of("blockpops", "textures/gui/support_icon.png");

    // The purple the BlockPops-Hytale project gives its specials
    private static final int SPECIAL_COLOR = 0xFFBB8FCE;
    // The light purple of the supporter messages
    private static final int SUPPORT_STATUS_COLOR = 0xFFE8D5F5;
    private static final int REGULAR_TOKEN_COLOR = 0xFF6FA0FF;
    private static final int GUARANTEED_TOKEN_COLOR = 0xFFFFC23A;
    // An "i" at the end of each token line that explains the token under the mouse
    private static final String[] ICON_INFO = {
            ".#####.", "###.###", "#######", "##..###", "###.###", "###.###", "##...##", ".#####."};
    private static final int HAIRLINE = 0x30FFFFFF;

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
        super(Component.translatable(plush ? "blockpops.machine.title.gacha" : "blockpops.machine.title.claw"));
        this.blockPos = blockPos;
        this.plush = currentCollectionId == null ? plush
                : CollectionRegistry.getCollection(currentCollectionId).map(FigureCollection::isPlush).orElse(plush);
        this.selectedCollectionId = currentCollectionId;
        this.machineCollectionId = currentCollectionId;
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
     * last in the server's order, without the default collection or one that is archived or a draft.
     */
    private static List<FigureCollection> collectionsOf(boolean plush) {
        List<FigureCollection> list = (plush ? CollectionRegistry.getPlushCollections() : CollectionRegistry.getAllCollections()).stream()
                .filter(c -> !"default".equals(c.getId()))
                .filter(c -> ClientWorldCollections.isOffered(c.getId()))
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
        // The collection the tab kept from the last visit takes back its background; a first
        // visit selects the first one, as a click would
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
            CollectionRegistry.getCollection(selectedCollectionId).ifPresent(this::fadeBackgroundTo);
        }
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

    /**
     * Selects the collection the machine holds now when someone else set it while this screen
     * waited under another, as the creator's "Put in this machine" does, so a token is not spent
     * on the collection the screen showed before. The tab follows the collection's kind.
     */
    private void followMachine() {
        if (this.minecraft == null || this.minecraft.level == null || FigureDraw.isBusy()) {
            return;
        }
        BlockEntity entity = this.minecraft.level.getBlockEntity(blockPos);
        String held = entity instanceof ClawMachineBlockEntity claw ? claw.getCollectionId()
                : entity instanceof GachaMachineBlockEntity gacha ? gacha.getCollectionId() : null;
        if (held == null || held.isEmpty() || held.equals(machineCollectionId)) {
            return;
        }
        machineCollectionId = held;
        FigureCollection collection = CollectionRegistry.getCollection(held).orElse(null);
        if (collection == null || held.equals(selectedCollectionId)) {
            return;
        }
        if (collection.isPlush() != plush) {
            otherTabSelection = selectedCollectionId == null ? "" : selectedCollectionId;
            plush = collection.isPlush();
            collections = collectionsOf(plush);
        }
        selectedCollectionId = held;
        fadeBackgroundTo(collection);
    }

    @Override
    protected void init() {
        // Nothing of the menu exists until the frame it opens over is captured; tick() lays it
        // out then
        if (!opening.ready()) {
            return;
        }
        menuShown = true;
        // The machine sounds as its menu opens, once: a resize lays the menu out again
        if (opening.begin()) {
            UiSounds.play(ModSounds.MENU_OPEN);
        }
        // The guaranteed token's ticket says when it renews, at the server's hour
        if (ClientServerSettings.getGuaranteedResetHour() == null) {
            ClientServerSettings.request();
        }
        // The menu draws at its own GUI scale, chosen from the window size, so it looks the same
        // whatever scale the player picked; a window resize runs init() again
        if (this.minecraft != null) {
            MenuGuiScale.enter(this.minecraft, this);
        }
        if (shownWorldRevision != ClientWorldCollections.revision() && !FigureDraw.isBusy()) {
            // Back from the creator, straight or through the settings: it may have published,
            // archived or edited a collection, and the machine may now hold a new one
            shownWorldRevision = ClientWorldCollections.revision();
            collections = collectionsOf(plush);
        }
        followMachine();
        // Pre-initialize the shared figure renderer to avoid lag on first render
        FigureWidgetRenderer.ensureInitialized();

        super.init();
        clearWidgets();

        // The menu is a block centred on the canvas: the collections column, and the figures
        // column as wide as its grid of up to four cells of the usual size needs
        int available = this.width - 2 * MARGIN;
        leftWidth = Mth.clamp(Math.round((available - GAP) * 0.40f), 170, 280);
        int gridRoom = available - leftWidth - GAP - GRID_CHROME;
        int columns = 4;
        int cell = Math.min(FigureEntry.FIGURE_SIZE, (gridRoom - 3 * FigureEntry.GRID_SPACING) / 4);
        if (cell < MIN_CELL_FOR_FOUR) {
            columns = 3;
            cell = Math.min(FigureEntry.FIGURE_SIZE, (gridRoom - 2 * FigureEntry.GRID_SPACING) / 3);
            if (cell < MIN_CELL_FOR_THREE) {
                columns = 2;
                cell = Math.min(FigureEntry.FIGURE_SIZE, (gridRoom - FigureEntry.GRID_SPACING) / 2);
            }
        }
        rightWidth = Math.min(available - leftWidth - GAP, FigureListWidget.rowWidth(cell, columns) + GRID_CHROME);
        int contentWidth = leftWidth + GAP + rightWidth;
        int contentLeft = (this.width - contentWidth) / 2;
        int contentRight = contentLeft + contentWidth;
        leftX = contentLeft;
        rightX = leftX + leftWidth + GAP;

        // The bottom row: the token buttons, blue and gold as their counts, then the creator's
        // button for an operator in a development game, then Done; the token counts stand on a
        // line above their buttons
        int buttonHeight = Mth.clamp(Math.round(this.height * BUTTON_HEIGHT_SHARE), MIN_BUTTON_HEIGHT, MAX_BUTTON_HEIGHT);
        int footTop = this.height - MARGIN - buttonHeight;
        tokenInfoY = footTop - 4 - font.lineHeight;
        shownCreator = SettingsScreen.canOpenCreator();
        int doneWidth = Mth.clamp(Math.round(contentWidth * 0.16f), 70, 110);
        int creatorWidth = shownCreator ? Mth.clamp(Math.round(contentWidth * 0.15f), 70, 100) : 0;
        int tokensWidth = contentWidth - doneWidth - GAP - (shownCreator ? creatorWidth + GAP : 0);
        tokenButtonWidth = (tokensWidth - GAP) / 2;

        useRegularButton = new StyledButton(contentLeft, footTop, tokenButtonWidth, buttonHeight,
                Component.translatable("blockpops.machine.use_regular"), button -> useToken(TokenType.REGULAR))
                .tone(StyledButton.Tone.BLUE).pixelLabel().sound(ModSounds.DRAW_TICKET);
        this.addRenderableWidget(useRegularButton);
        int specialX = contentLeft + tokenButtonWidth + GAP;
        useSpecialButton = new StyledButton(specialX, footTop, tokenButtonWidth, buttonHeight,
                Component.translatable("blockpops.machine.use_guaranteed"), button -> useToken(TokenType.GUARANTEED))
                .tone(StyledButton.Tone.GOLD).pixelLabel().sound(ModSounds.DRAW_TICKET);
        this.addRenderableWidget(useSpecialButton);
        regularCenterX = contentLeft + tokenButtonWidth / 2;
        specialCenterX = specialX + tokenButtonWidth / 2;

        int doneX = contentRight - doneWidth;
        if (shownCreator) {
            this.addRenderableWidget(new StyledButton(doneX - GAP - creatorWidth, footTop, creatorWidth, buttonHeight,
                    Component.translatable("blockpops.world_creator.machine_button"),
                    button -> SettingsScreen.openCreator(this, blockPos)));
        }
        doneButton = new StyledButton(doneX, footTop, doneWidth, buttonHeight, CommonComponents.GUI_DONE,
                button -> onClose()).tone(StyledButton.Tone.PRIMARY).pixelLabel();
        this.addRenderableWidget(doneButton);
        updateTokenButtonStates();

        // The top row: the figure and plush tabs on the left, the link buttons on the right
        TabButton.Folder figuresTab = new TabButton.Folder(contentLeft, MARGIN, Component.translatable("blockpops.machine.tab.figures"),
                new ItemStack(ModItems.CLAW_MACHINE_BLOCK_ITEM.get()), !plush, button -> switchTab(false));
        TabButton.Folder plushesTab = new TabButton.Folder(figuresTab.right() + GAP, MARGIN, Component.translatable("blockpops.machine.tab.plushes"),
                new ItemStack(ModItems.GACHA_MACHINE_BLOCK_ITEM.get()), plush, button -> switchTab(true));
        this.addRenderableWidget(figuresTab);
        this.addRenderableWidget(plushesTab);
        tabsRight = plushesTab.right();
        addLinkButtons(contentRight, MARGIN + (TabButton.Folder.HEIGHT - LINK_SIZE) / 2);

        // The two columns between the rows: the collections, then the figures
        bodyTop = MARGIN + TabButton.Folder.HEIGHT + GAP;
        bodyBottom = tokenInfoY - GAP;
        int listTop = bodyTop + COLUMN_HEADER;
        int listHeight = Math.max(COLLECTION_ROW, bodyBottom - 2 - listTop);

        collectionListWidget = new CollectionListWidget(this, this.minecraft, leftWidth - 4, listHeight, listTop,
                COLLECTION_ROW);
        collectionListWidget.setLeftPos(leftX + 2);
        collectionListWidget.setRenderBackground(false);
        collectionListWidget.setRenderTopAndBottom(false);
        // Added for input only: the screen draws the lists between its backdrops and its widgets
        this.addWidget(collectionListWidget);
        loadCollections();

        figureListWidget = new FigureListWidget(this.minecraft, rightWidth - 4, listHeight, listTop, cell + ROW_GAP);
        figureListWidget.setLeftPos(rightX + 2);
        figureListWidget.setGrid(cell, columns);
        figureListWidget.setShownSkins(shownSkins);
        figureListWidget.setRenderBackground(false);
        figureListWidget.setRenderTopAndBottom(false);
        this.addWidget(figureListWidget);

        // Update preview if there's a selected collection
        if (selectedCollectionId != null && !selectedCollectionId.isEmpty()) {
            CollectionRegistry.getCollection(selectedCollectionId).ifPresent(collection -> {
                if (figureListWidget != null) {
                    figureListWidget.setCollection(collection);
                }
            });
        }

        // A machine without a collection opens on the first one of its tab
        if (!opened) {
            opened = true;
            selectFirstCollectionIfNone();
        }
    }

    // Settings at the right edge, then Discord, CurseForge, Modrinth, the supporter button and the
    // player's favourite colour, on both tabs
    private void addLinkButtons(int right, int y) {
        int x = right - LINK_SIZE;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, SETTINGS_ICON,
                null, // No URL: it opens the settings
                Component.translatable("blockpops.machine.settings")) {
            @Override
            //? if >=26 {
            /*public void onPress(net.minecraft.client.input.InputWithModifiers bpInput) {
            *///? } else {
            public void onPress() {
            //? }
                openSettingsScreen();
            }
        });
        x -= LINK_SIZE + LINK_SPACING;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, DISCORD_ICON, DISCORD_URL,
                Component.translatable("blockpops.machine.link.discord")));
        x -= LINK_SIZE + LINK_SPACING;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, CURSEFORGE_ICON, CURSEFORGE_URL,
                Component.translatable("blockpops.machine.link.curseforge")));
        x -= LINK_SIZE + LINK_SPACING;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, MODRINTH_ICON, MODRINTH_URL,
                Component.translatable("blockpops.machine.link.modrinth")));
        // The special plushes are for supporters: the button opens a checkout made for this player
        x -= LINK_SIZE + LINK_SPACING;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, SUPPORT_ICON,
                null, // No URL: the server gets the checkout and sends it back
                Component.translatable("blockpops.machine.link.supporter")) {
            @Override
            //? if >=26 {
            /*public void onPress(net.minecraft.client.input.InputWithModifiers bpInput) {
            *///? } else {
            public void onPress() {
            //? }
                ClientSupportCheckout.request();
            }
        });
        x -= LINK_SIZE + LINK_SPACING;
        this.addRenderableWidget(new LinkButton(x, y, LINK_SIZE, LINK_SIZE, PALETTE_ICON,
                null, // No URL: it opens the favourite colour's screen, which comes back here
                Component.translatable("blockpops.machine.link.favorite_color")) {
            @Override
            //? if >=26 {
            /*public void onPress(net.minecraft.client.input.InputWithModifiers bpInput) {
            *///? } else {
            public void onPress() {
            //? }
                CollectionSelectionScreen.this.minecraft.setScreen(new FavoriteColorSelectionScreen(CollectionSelectionScreen.this));
            }
        });
        linksLeft = x;
        linksTop = y;
    }

    // What the supporter button is doing, or why no checkout opened, beside the link buttons
    private void renderSupportStatus(GuiGraphics graphics) {
        String status = ClientSupportCheckout.status();
        if (status == null) {
            return;
        }
        int right = linksLeft - 6;
        int room = right - tabsRight - 2 * GAP;
        if (room < 40) {
            return;
        }
        String shown = UiText.fit(this.font, status, room);
        UiText.draw(graphics, this.font, shown, right - this.font.width(shown),
                linksTop + (LINK_SIZE - this.font.lineHeight) / 2 + 1, SUPPORT_STATUS_COLOR);
    }

    // Spends a token on the selected collection; the screen stays open to play the draw out
    private void useToken(TokenType type) {
        if (selectedCollectionId == null || selectedCollectionId.isEmpty()) {
            return;
        }
        BlockPopsMod.logDebug("Using {} token for collection: {}", type, selectedCollectionId);
        new DropBoxPacket(blockPos, selectedCollectionId, type).sendToServer();
        FigureDraw.await(selectedCollectionId, type == TokenType.GUARANTEED);
        updateTokenButtonStates();
    }

    @Override
    public void removed() {
        super.removed();
        // A menu closed during its opening lets go of the frame it captured
        opening.close();
        // The next screen, a dialog or the world opens at the player's own scale
        if (this.minecraft != null) {
            MenuGuiScale.restore(this.minecraft);
        }
    }

    @Override
    public void onClose() {
        FigureDraw.reset();
        super.onClose();
    }

    //? if >=1.21 {
    /*@Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws its own opaque background, and the world stays sharp under the
        // opening dots; vanilla's pass would blur it, from 1.21.6 before render() and on
        // 1.21 to 1.21.5 from Screen.render, over this screen's own background.
    }
    *///? }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The world shows until the menu is laid out; then the opening dots spread over the frame
        // captured of it, and cover everything until the first of them shrink
        if (!menuShown || opening.hidesMenu()) {
            opening.draw(graphics);
            return;
        }

        // The machine's colour and its scrolling stars, then a backdrop under each column
        renderBackgroundEffects(graphics, partialTick);
        int backdropAlpha = (int) (ClientConfig.getInstance().panelOpacity * 200);
        Surfaces.backdrop(graphics, leftX, bodyTop, leftX + leftWidth, bodyBottom, backdropAlpha);
        Surfaces.backdrop(graphics, rightX, bodyTop, rightX + rightWidth, bodyBottom, backdropAlpha);

        if (collectionListWidget != null) {
            collectionListWidget.render(graphics, mouseX, mouseY, partialTick);
        }
        if (figureListWidget != null) {
            figureListWidget.render(graphics, mouseX, mouseY, partialTick);
        }
        renderCollectionListHeader(graphics);
        renderFigurePanelHeader(graphics);
        renderTokenInfo(graphics, mouseX, mouseY);

        // The buttons, tabs and links
        super.render(graphics, mouseX, mouseY, partialTick);
        renderSupportStatus(graphics);

        renderDrawResult(graphics, partialTick);
        renderTokenTooltip(graphics, mouseX, mouseY);
        // The opening ring of dots, with the world beyond it, over everything
        opening.draw(graphics);
    }

    /**
     * Shows the figure a draw stopped on, in a card above everything else on the screen, centred
     * over the figure list (see {@link FigureDraw#drawResultCard}).
     */
    private void renderDrawResult(GuiGraphics graphics, float partialTick) {
        if (figureListWidget != null) {
            FigureDraw.drawResultCard(graphics, font, figureListWidget, partialTick, null);
        }
    }

    /** The figure list, which a draw lights up. */
    @Nullable
    FigureListWidget figureList() {
        return figureListWidget;
    }

    /** The collections column's header: its title, how many there are, and a hairline. */
    private void renderCollectionListHeader(GuiGraphics graphics) {
        if (collectionListWidget == null) {
            return;
        }
        int x = leftX + 8;
        int y = bodyTop + 6;
        int room = leftWidth - 16;
        UiText.drawFitted(graphics, this.font, Component.translatable("blockpops.machine.collections").getString(),
                x, y, room, Surfaces.TEXT);
        UiText.drawFitted(graphics, this.font, Component.translatable("blockpops.machine.collections_available",
                        collectionListWidget.children().size()).getString(),
                x, y + font.lineHeight + 2, room, Surfaces.TEXT_DIM);
        graphics.fill(x, bodyTop + COLUMN_HEADER - 3, leftX + leftWidth - 8, bodyTop + COLUMN_HEADER - 2, HAIRLINE);
    }

    /** The figures column's header: the collection's name, its figure count, and a hairline. */
    private void renderFigurePanelHeader(GuiGraphics graphics) {
        if (figureListWidget == null) {
            return;
        }
        FigureCollection collection = figureListWidget.getCurrentCollection();
        if (collection == null) {
            return;
        }
        int x = rightX + 8;
        int y = bodyTop + 6;
        int room = rightWidth - 16;
        UiText.drawFitted(graphics, this.font, LocalNames.collection(collection).getString(), x, y, room, Surfaces.TEXT);

        String figureCount = (collection.hasSpecials()
                ? Component.translatable("blockpops.machine.figures_specials", collection.getRegularFigures().size(),
                        collection.getSpecialFigures().size())
                : Component.translatable("blockpops.machine.figures", collection.getRegularFigures().size()))
                .getString();
        // Tell a player the server gives this collection's specials to
        boolean specialAccess = collection.hasSpecials() && ClientSpecialAccess.has(collection.getId());
        String access = Component.translatable("blockpops.machine.special_access").getString();
        int countRoom = specialAccess ? room - font.width(access) - 8 : room;
        String shownCount = UiText.fit(this.font, figureCount, countRoom);
        UiText.draw(graphics, this.font, shownCount, x, y + font.lineHeight + 2, Surfaces.TEXT_DIM);
        if (specialAccess) {
            UiText.draw(graphics, this.font, access, x + font.width(shownCount) + 8, y + font.lineHeight + 2,
                    SPECIAL_COLOR);
        }
        graphics.fill(x, bodyTop + COLUMN_HEADER - 3, rightX + rightWidth - 8, bodyTop + COLUMN_HEADER - 2, HAIRLINE);
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
        graphics.fill(0, 0, this.width, this.height, bgColor);

        //? if <1.21.5 {
        // Finish buffered base fills before the immediate textured quad draws.
        GuiPose.flush(graphics);
        //? }

        // 2. Render the moving star pattern
        drawStarPattern(graphics, this.width, this.height, partialTick);

        // Close the background layer so the columns and their text draw above it
        GuiPose.nextStratum(graphics);
    }

    /**
     * Draws the scrolling star pattern over an area from the top left corner, in the star
     * colour and opacity of the client config. The World collection screens draw it too.
     */
    public static void drawStarPattern(GuiGraphics graphics, int starWidth, int starHeight, float partialTick) {
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

        // A hidden, archived or draft collection is not offered, so it cannot stay this machine's
        // selection, and neither can a World collection that was removed
        if (selectedCollectionId != null && (ClientHiddenCollections.isHidden(selectedCollectionId)
                || !ClientWorldCollections.isOffered(selectedCollectionId)
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
            fadeBackgroundTo(collection);

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
     * Fades the background to the collection's own colour, when it has one, or switches to it at
     * once when the player turned the transition off.
     */
    private void fadeBackgroundTo(FigureCollection collection) {
        if (!collection.hasBackgroundColor()) {
            return;
        }
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
    private void renderTokenInfo(GuiGraphics graphics, int mouseX, int mouseY) {
        // Regular tokens, above their button. Blue tickets can take the count past the 3 the
        // server grants on its own
        int regularTokens = ClientTokenManager.getRegularTokens();
        String regularCount = regularTokens + (regularTokens <= 3 ? "/3" : "");
        String regularText = (regularTokens < 3
                ? Component.translatable("blockpops.machine.regular_tokens_next", regularCount,
                        ClientTokenManager.formatNextRegularTime())
                : Component.translatable("blockpops.machine.regular_tokens", regularCount)).getString();
        regularInfoX = drawTokenLine(graphics, regularText, regularCenterX, REGULAR_TOKEN_COLOR, mouseX, mouseY);
        regularInfoY = tokenInfoY;

        // The guaranteed token, above its button
        int guaranteedTokens = ClientTokenManager.getGuaranteedTokens();
        String specialText = (guaranteedTokens > 1
                ? Component.translatable("blockpops.machine.guaranteed_tokens", guaranteedTokens)
                : guaranteedTokens == 1 ? Component.translatable("blockpops.machine.guaranteed_available")
                : Component.translatable("blockpops.machine.guaranteed_used",
                        ClientTokenManager.formatNextSpecialResetTime())).getString();
        specialInfoX = drawTokenLine(graphics, specialText, specialCenterX, GUARANTEED_TOKEN_COLOR, mouseX, mouseY);
        specialInfoY = tokenInfoY;
    }

    // A token count centred above its button with its info icon after it, on a dark strip so it
    // reads on any collection colour; returns where the icon is
    private int drawTokenLine(GuiGraphics graphics, String text, int centerX, int color, int mouseX, int mouseY) {
        int iconWidth = PixelIcon.width(ICON_INFO);
        String shown = UiText.fit(this.font, text, tokenButtonWidth - 8 - iconWidth - 4);
        int width = font.width(shown) + 4 + iconWidth;
        int left = centerX - width / 2;
        graphics.fill(left - 4, tokenInfoY - 2, left + width + 4, tokenInfoY + font.lineHeight + 1,
                (int) (ClientConfig.getInstance().panelOpacity * 200) << 24 | 0x101012);
        UiText.draw(graphics, this.font, shown, left, tokenInfoY, color);
        int iconX = left + width - iconWidth;
        PixelIcon.draw(graphics, ICON_INFO, iconX, tokenInfoY - 1,
                overTokenInfo(iconX, tokenInfoY, mouseX, mouseY) ? Surfaces.TEXT : color);
        return iconX;
    }

    private boolean overTokenInfo(int iconX, int iconY, int mouseX, int mouseY) {
        // A pixel of slack round the small icon
        return mouseX >= iconX - 1 && mouseX < iconX + PixelIcon.width(ICON_INFO) + 1
                && mouseY >= iconY - 2 && mouseY < iconY + ICON_INFO.length;
    }

    // The ticket of the token whose info icon is under the mouse
    private void renderTokenTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (overTokenInfo(regularInfoX, regularInfoY, mouseX, mouseY)) {
            TokenTicket.draw(graphics, this.font, regularTokenView(), regularInfoX, tokenInfoY, this.width);
        } else if (overTokenInfo(specialInfoX, specialInfoY, mouseX, mouseY)) {
            TokenTicket.draw(graphics, this.font, guaranteedTokenView(), specialInfoX, tokenInfoY, this.width);
        }
    }

    private static TokenTicket.View regularTokenView() {
        int count = ClientTokenManager.getRegularTokens();
        boolean full = count >= DrawRules.MAX_REGULAR_TOKENS;
        return new TokenTicket.View(true, count, full ? null : ClientTokenManager.formatNextRegularTime(), null);
    }

    // The guaranteed one renews at the server's hour, said in the player's own time
    private static TokenTicket.View guaranteedTokenView() {
        Integer hour = ClientServerSettings.getGuaranteedResetHour();
        String resetAt = hour == null ? null : String.format("%02d:00", SettingsScreen.convertUtcToLocal(hour));
        return new TokenTicket.View(false, ClientTokenManager.getGuaranteedTokens(),
                ClientTokenManager.formatNextSpecialResetTime(), resetAt);
    }

    @Override
    public void tick() {
        super.tick();
        // Once the frame the menu opens over is captured, the menu is laid out under it at its own
        // scale. A tick comes before the frame it draws, so that frame is all at the new scale.
        if (!menuShown && opening.ready()) {
            rebuildWidgets();
        }
        // Another mod can put the player's scale back on the window after init(), as some do
        // whenever a screen opens; take the menu's scale back and lay the menu out again
        if (menuShown && this.minecraft != null && MenuGuiScale.reapply(this.minecraft, this)) {
            rebuildWidgets();
        }
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
        // The creator's button comes and goes with operator status and a server that syncs
        // World collections; a draw keeps its screen as it is until it ends
        if (shownCreator != SettingsScreen.canOpenCreator() && !FigureDraw.isBusy()) {
            rebuildWidgets();
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
        // The machine's own update follows; until then this screen is not told to follow it
        machineCollectionId = selectedCollectionId;
        ClawMachineCollectionPacket packet = new ClawMachineCollectionPacket(blockPos, selectedCollectionId);
        packet.sendToServer();
    }

    /**
     * The claw or gacha machine this screen configures, which the creator screens opened from
     * here offer as the place to try a collection. Null when the screen was opened without one.
     */
    @Nullable
    public BlockPos machinePos() {
        return this.blockPos;
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
        // The menu takes no clicks while the opening dots still hide it
        if (opening.hidesMenu() || blocksForDraw(bpEvent.x(), bpEvent.y())) {
            return true;
        }
        return super.mouseClicked(bpEvent, bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The menu takes no clicks while the opening dots still hide it
        if (opening.hidesMenu() || blocksForDraw(mouseX, mouseY)) {
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
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //? }
        // The figure list holds still while a draw lights its figures or shows the one it drew,
        // and the menu while the opening dots hide it
        if (opening.hidesMenu() || FigureDraw.isBusy()
                || (FigureDraw.result() != null && isOverFigureList(mouseX, mouseY))) {
            return true;
        }
        //? if >=1.21 {
        /*return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
        *///? } else {
        return super.mouseScrolled(mouseX, mouseY, delta);
        //? }
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
