package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCardGrid;
import com.theplumteam.client.gui.creator.ui.UiDropdown;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiTabs;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.registry.ModItems;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The creator's first screen: every World collection of this world as a grid of cards (see
 * {@link CollectionCard}), with a card to start a new one. Tabs pick all of them or only the
 * published ones, the drafts or the archived ones, each with its count; a search field filters
 * by name, author or slug, and a dropdown sorts by the last edit, by name or by the number of
 * figures. The header shows how much of the server's file space the collections use and how
 * many of the world's collections exist.
 *
 * <p>A card opens in the collection editor ({@link CreatorEditorScreen}) with its "Edit" button,
 * a double click or Enter. Its "..." menu, also opened with a right click, holds the collection's
 * own actions ({@link CollectionActions}): it puts the collection in the machine the creator was
 * opened from, gives the operator every figure to try it out, duplicates it into a new draft,
 * archives or restores it, and deletes a draft or an archived collection for good after the
 * operator types its name. Archiving asks first and its message offers an undo. The footer
 * imports a collection that a data pack on the server ships ({@link PackImportDialog}). The
 * new-collection card, Ctrl+N and the empty state's button open {@link CreatorWizardScreen} over
 * this screen.
 *
 * <p>Every action only asks the server, which checks it again; the cards follow the collections
 * the server syncs back. A world with no collection shows a single invitation to create one.
 */
public class CreatorHomeScreen extends CreatorScreen {
    // The mockup's 1280 by 800 frame, at half its pixel size as every creator size is
    private static final int MAX_PANEL_WIDTH = 640;
    private static final int MAX_PANEL_HEIGHT = 400;
    private static final int PAD = 12;
    private static final int GAP = 8;
    private static final int MIN_CARD_WIDTH = 130;
    private static final int MAX_COLUMNS = 4;
    private static final int QUOTA_WIDTH = 110;
    private static final int SEARCH_WIDTH = 120;
    private static final int MIN_SEARCH_WIDTH = 60;
    private static final int MIN_SORT_WIDTH = 70;
    // The title's pixel font, larger when the header has the room
    private static final float TITLE_SCALE = 1.5f;
    // From 1.21.6 every figure preview is drawn into a render target of its own
    private static final int MAX_CARDS_WITH_HEADS = 15;
    private static final int SUBTITLE = Theme.MUTED;
    private static final int HINT = Theme.FAINT;

    /** Which collections the grid shows. */
    private enum Filter {
        ALL, PUBLISHED, DRAFTS, ARCHIVED;

        boolean accepts(WorldCollectionRecord record) {
            return switch (this) {
                case ALL -> true;
                case PUBLISHED -> !record.draft() && !record.archived();
                case DRAFTS -> record.draft() && !record.archived();
                case ARCHIVED -> record.archived();
            };
        }
    }

    /** The grid's order. */
    private enum Sort {
        EDITED, NAME, FIGURES
    }

    // Kept for the rest of the session, so the overview opens as it was left
    private static Filter lastFilter = Filter.ALL;
    private static Sort lastSort = Sort.EDITED;

    @Nullable
    private final BlockPos machinePos;
    private final CollectionActions actions;
    private Filter filter = lastFilter;
    private Sort sort = lastSort;
    private String search = "";
    // A collection this screen asked for, selected and scrolled to once the server syncs it
    @Nullable
    private String revealWhenSynced;
    private float partialTick;
    // Whether the cards draw their figure heads this frame
    private boolean headsShown;

    // Nodes kept across rebuilds, so the tab, the selection and the scroll stay
    private final UiTabs tabs;
    private final UiDropdown sortDropdown;
    private final UiQuotaMeter quota = new UiQuotaMeter();
    private final UiButton helpButton;
    private final UiButton importButton;
    private final UiButton doneButton;
    private final UiButton emptyCreateButton;
    private final CollectionCard newCard = CollectionCard.newCollection();
    @Nullable
    private UiCardGrid<CollectionCard> grid;
    private int gridCardWidth;
    private final Map<String, CollectionCard> cards = new LinkedHashMap<>();
    @Nullable
    private EditBox searchBox;
    private final ItemStack headerIcon = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
    private final ItemStack machineIcon = new ItemStack(ModItems.CLAW_MACHINE_BLOCK_ITEM.get());

    /**
     * @param parent     the screen to go back to; null when the creator was opened by its
     *                   command, so that leaving it returns to the game
     * @param machinePos the claw or gacha machine the creator was opened from, which the cards
     *                   offer to put a collection in; null when there is none
     */
    public CreatorHomeScreen(@Nullable Screen parent, @Nullable BlockPos machinePos) {
        super(tr("home.title"), parent);
        this.machinePos = machinePos;
        this.actions = new CollectionActions(this, machinePos, this::reveal);
        this.tabs = new UiTabs(UiTabs.Style.FLAT, index -> {
            this.filter = Filter.values()[index];
            lastFilter = this.filter;
            refreshCards();
        });
        this.sortDropdown = new UiDropdown(tr("home.sort"),
                List.of(tr("home.sort.edited"), tr("home.sort.name"), tr("home.sort.figures")), sort.ordinal(),
                index -> {
                    this.sort = Sort.values()[index];
                    lastSort = this.sort;
                    refreshCards();
                });
        this.helpButton = new UiButton(Component.empty(), UiButton.Style.SECONDARY, this.actions::openHelp);
        this.helpButton.setGlyph(Theme.ICON_HELP);
        this.helpButton.setTooltip(tr("home.help_title"));
        this.importButton = new UiButton(tr("home.import"), UiButton.Style.SECONDARY, this.actions::openImport);
        this.doneButton = new UiButton(tr("done"), UiButton.Style.PRIMARY, this::onClose);
        this.emptyCreateButton = new UiButton(tr("home.empty_create"), UiButton.Style.PRIMARY, this::newCollection);
    }

    // Building

    @Override
    protected void build() {
        fitPanel();
        List<WorldCollectionRecord> records = ClientWorldCollections.records();
        showRevealed(records);
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        int top = headerTop();

        // Header: the help button on the right, the file space and collection count before it
        int helpWidth = Math.max(20, UiButton.widthFor(this.font, this.helpButton.label(), false));
        this.helpButton.at(right - helpWidth, top + 3, helpWidth, 20);
        addNode(this.helpButton);
        this.quota.suffix(tr("home.collections", records.size(), WorldContentLimits.MAX_COLLECTIONS));
        this.quota.at(right - helpWidth - GAP - QUOTA_WIDTH, top + 4, QUOTA_WIDTH, UiQuotaMeter.HEIGHT);
        addNode(this.quota);

        // Filter bar: tabs on the left, then the search field and the sort order on the right
        int barY = barTop();
        this.tabs.setTabs(List.of(tab("home.filter.all", records, Filter.ALL),
                tab("home.filter.published", records, Filter.PUBLISHED),
                tab("home.filter.drafts", records, Filter.DRAFTS),
                tab("home.filter.archived", records, Filter.ARCHIVED)));
        this.tabs.select(this.filter.ordinal());
        int tabsWidth = this.tabs.contentWidth();
        int sortWidth = this.sortDropdown.naturalWidth();
        int searchWidth = Math.min(SEARCH_WIDTH, right - sortWidth - 6 - (left + tabsWidth + GAP));
        if (searchWidth < MIN_SEARCH_WIDTH) {
            // A narrow screen narrows the sort order first, which cuts its label, then the tabs
            sortWidth = Math.max(MIN_SORT_WIDTH, sortWidth - (MIN_SEARCH_WIDTH - searchWidth));
            searchWidth = Math.max(MIN_SEARCH_WIDTH,
                    Math.min(SEARCH_WIDTH, right - sortWidth - 6 - (left + tabsWidth + GAP)));
            tabsWidth = Math.min(tabsWidth, right - sortWidth - 6 - searchWidth - GAP - left);
        }
        this.tabs.at(left, barY, Math.max(0, tabsWidth), UiTabs.FLAT_HEIGHT);
        this.sortDropdown.at(right - sortWidth, barY + 2, sortWidth, UiDropdown.HEIGHT);
        this.sortDropdown.select(this.sort.ordinal());
        this.searchBox = textField("search", right - sortWidth - 6 - searchWidth, barY, searchWidth,
                WorldContentLimits.MAX_NAME_LENGTH, tr("home.search_hint"), this.search, value -> {
                    this.search = value;
                    refreshCards();
                });
        addNode(this.tabs);
        addNode(this.sortDropdown);

        // Footer: import on the left, Done on the right, the shortcuts between them
        int footerY = footerTop();
        this.importButton.at(left, footerY, UiButton.widthFor(this.font, this.importButton.label(), false),
                UiButton.HEIGHT);
        int doneWidth = Math.max(100, this.doneButton.naturalWidth());
        this.doneButton.at(right - doneWidth, footerY, doneWidth, UiButton.HEIGHT);
        addNode(this.importButton);
        addNode(this.doneButton);

        this.cards.clear();
        for (WorldCollectionRecord record : records) {
            this.cards.put(record.id(), CollectionCard.of(record, () -> edit(record),
                    anchor -> openCardMenu(record, anchor)));
        }
        Component newReason = CollectionActions.tooManyCollections();
        if (records.isEmpty()) {
            int buttonWidth = Math.min(this.panelWidth - 2 * PAD, Math.max(180, this.emptyCreateButton.naturalWidth()));
            this.emptyCreateButton.at(this.panelX + (this.panelWidth - buttonWidth) / 2, emptyTop() + 64 + 30,
                    buttonWidth, 24);
            this.emptyCreateButton.disable(newReason);
            addNode(this.emptyCreateButton);
            // No card is left to keep a selection or a scroll for, or for Enter to open
            this.grid = null;
            return;
        }
        UiCardGrid<CollectionCard> cardGrid = grid(right - left);
        cardGrid.at(left, gridTop(), right - left, Math.max(0, footerY - GAP - gridTop()));
        addNode(cardGrid);
        refreshCards();
        if (this.revealWhenSynced != null) {
            CollectionCard card = this.cards.get(this.revealWhenSynced);
            if (card != null) {
                this.revealWhenSynced = null;
                cardGrid.select(List.of(card));
                cardGrid.reveal(card);
            }
        }
    }

    /**
     * Shows every collection again when the tab or the search would hide the one this screen
     * is waiting to select, such as a draft duplicated from the Published tab.
     */
    private void showRevealed(List<WorldCollectionRecord> records) {
        if (this.revealWhenSynced == null) {
            return;
        }
        String query = this.search.strip().toLowerCase(Locale.ROOT);
        for (WorldCollectionRecord record : records) {
            if (record.id().equals(this.revealWhenSynced) && !(this.filter.accepts(record) && matches(record, query))) {
                this.filter = Filter.ALL;
                lastFilter = Filter.ALL;
                this.search = "";
            }
        }
    }

    // The panel is the mockup's frame at most, centred on a larger screen
    private void fitPanel() {
        int panelW = Math.min(this.panelWidth, MAX_PANEL_WIDTH);
        int panelH = Math.min(this.panelHeight, MAX_PANEL_HEIGHT);
        this.panelX = (this.width - panelW) / 2;
        this.panelY = (this.height - panelH) / 2;
        this.panelWidth = panelW;
        this.panelHeight = panelH;
    }

    private int headerTop() {
        return this.panelY + 10;
    }

    private int barTop() {
        return headerTop() + 34;
    }

    private int gridTop() {
        return barTop() + UiTabs.FLAT_HEIGHT + GAP;
    }

    private int footerTop() {
        return this.panelY + this.panelHeight - 10 - UiButton.HEIGHT;
    }

    private int emptyTop() {
        int room = footerTop() - gridTop();
        return gridTop() + Math.max(0, (room - (64 + 30 + 24)) / 2);
    }

    private UiTabs.Tab tab(String key, List<WorldCollectionRecord> records, Filter which) {
        int count = 0;
        for (WorldCollectionRecord record : records) {
            if (which.accepts(record)) {
                count++;
            }
        }
        return new UiTabs.Tab(tr(key), ItemStack.EMPTY, count, false);
    }

    /**
     * The grid, made again only when the cards change width, as when the window is resized, so
     * a rebuild keeps the selection and the scroll.
     */
    private UiCardGrid<CollectionCard> grid(int width) {
        int contentWidth = width - UiScrollPane.BAR - 2;
        int columns = Math.max(1, Math.min(MAX_COLUMNS, (contentWidth + GAP) / (MIN_CARD_WIDTH + GAP)));
        int cardWidth = Math.max(MIN_CARD_WIDTH / 2, (contentWidth - (columns - 1) * GAP) / columns);
        if (this.grid != null && this.gridCardWidth == cardWidth) {
            return this.grid;
        }
        List<CollectionCard> selected = this.grid != null ? this.grid.selected() : List.of();
        UiCardGrid<CollectionCard> cardGrid = new UiCardGrid<CollectionCard>(cardWidth, CollectionCard.HEIGHT,
                (graphics, card, at) -> card.draw(graphics, at, this.headsShown, CollectionActions.tooManyCollections(),
                        this.partialTick))
                .gap(GAP)
                .keys(CollectionCard::key)
                .onActivate(this::activate)
                .onCardClick(this::cardClicked);
        cardGrid.select(selected);
        this.grid = cardGrid;
        this.gridCardWidth = cardWidth;
        return cardGrid;
    }

    /** Shows the cards the tab and the search pick, in the chosen order, after the new-collection card. */
    private void refreshCards() {
        if (this.grid == null) {
            return;
        }
        List<WorldCollectionRecord> records = ClientWorldCollections.records();
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            order.put(records.get(i).id(), i);
        }
        String query = this.search.strip().toLowerCase(Locale.ROOT);
        List<WorldCollectionRecord> shown = new ArrayList<>();
        for (WorldCollectionRecord record : records) {
            if (this.filter.accepts(record) && matches(record, query)) {
                shown.add(record);
            }
        }
        Comparator<WorldCollectionRecord> serverOrder = Comparator.comparingInt(record -> order.get(record.id()));
        Comparator<WorldCollectionRecord> comparator = switch (this.sort) {
            case EDITED -> Comparator.comparingLong(WorldCollectionRecord::editedAt).reversed();
            case NAME -> Comparator.comparing(WorldCollectionRecord::name, String.CASE_INSENSITIVE_ORDER);
            case FIGURES -> Comparator.comparingInt((WorldCollectionRecord record) -> record.figures().size()).reversed();
        };
        shown.sort(comparator.thenComparing(serverOrder));
        List<CollectionCard> items = new ArrayList<>();
        items.add(this.newCard);
        for (WorldCollectionRecord record : shown) {
            CollectionCard card = this.cards.get(record.id());
            if (card != null) {
                items.add(card);
            }
        }
        this.grid.setItems(items);
    }

    private static boolean matches(WorldCollectionRecord record, String query) {
        return query.isEmpty() || record.name().toLowerCase(Locale.ROOT).contains(query)
                || record.author().toLowerCase(Locale.ROOT).contains(query) || record.slug().contains(query);
    }

    /**
     * Whether the cards draw their figure heads: from 1.21.6 each head costs a render of its own,
     * so a grid that can show more cards than {@link #MAX_CARDS_WITH_HEADS} draws none. It counts
     * the cards the grid's height can show at any scroll, a part of a row at both edges, so the
     * heads do not come and go while it scrolls.
     */
    private boolean showsHeads() {
        if (this.grid == null) {
            return false;
        }
        int pitch = CollectionCard.HEIGHT + GAP;
        int rows = (this.grid.bounds().height() + pitch - 1) / pitch + 1;
        // The new-collection card has no heads
        int collections = this.grid.items().size() - 1;
        return Math.min(collections, rows * this.grid.columns()) <= MAX_CARDS_WITH_HEADS;
    }

    @Override
    protected void onSync() {
        // build() reads the new records; nothing here outlives a sync
    }

    // Drawing

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.partialTick = partialTick;
        this.headsShown = showsHeads();
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        int top = headerTop();
        Draw.item(graphics, this.headerIcon, left, top, 26);
        int textX = left + 32;
        int quotaText = this.font.width(UiQuotaMeter.text(ClientWorldCollections.quotaUsedKiB(),
                ClientWorldCollections.quotaMaxKiB()).getString() + tr("home.collections",
                ClientWorldCollections.records().size(), WorldContentLimits.MAX_COLLECTIONS).getString());
        int textRight = Math.min(this.quota.bounds().x(), this.quota.bounds().right() - quotaText) - GAP;
        int textWidth = Math.max(0, textRight - textX);
        // The title in the pixel font, smaller when the header is narrow
        float scale = PixelFont.width(this.title, PixelFont.crispScale(TITLE_SCALE)) <= textWidth ? TITLE_SCALE : 1.0f;
        Draw.heading(graphics, this.title, textX, top + 7, textWidth, scale, Theme.TEXT);
        Draw.text(graphics, this.font, tr("home.subtitle").getString(), textX, top + 17, textWidth, SUBTITLE);

        int footerY = footerTop();
        int hintLeft = this.importButton.bounds().right() + GAP;
        int hintRight = this.doneButton.bounds().x() - GAP;
        Draw.centred(graphics, this.font, tr("home.shortcuts").getString(), (hintLeft + hintRight) / 2,
                footerY + 6, Math.max(0, hintRight - hintLeft), HINT);

        if (ClientWorldCollections.records().isEmpty()) {
            drawEmptyState(graphics);
        } else if (this.grid != null && this.grid.items().size() == 1) {
            Draw.centred(graphics, this.font, tr("home.no_match").getString(), this.panelX + this.panelWidth / 2,
                    gridTop() + CollectionCard.HEIGHT + 16, right - left, Theme.MUTED);
        }
    }

    private void drawEmptyState(GuiGraphics graphics) {
        int centerX = this.panelX + this.panelWidth / 2;
        int top = emptyTop();
        Draw.item(graphics, this.machineIcon, centerX - 52, top, 64);
        Draw.item(graphics, this.headerIcon, centerX + 16, top + 24, 40);
        Draw.centred(graphics, this.font, tr("home.empty").getString(), centerX, top + 64 + 12,
                this.panelWidth - 2 * PAD, Theme.TEXT);
    }

    @Override
    protected void filesDroppedNowhere(List<Path> paths) {
        toast(tr("drop.nowhere"));
    }

    // Input

    @Override
    protected boolean shortcut(UiInput.Key key) {
        if (key.ctrl() && key.is(InputConstants.KEY_N)) {
            newCollection();
            return true;
        }
        if (key.ctrl() && key.is(InputConstants.KEY_F) && this.searchBox != null) {
            focusNode(null);
            setFocused(this.searchBox);
            return true;
        }
        if (key.enter() && this.grid != null) {
            // Only a card the operator picked: after a tab change the current card can be another
            CollectionCard current = this.grid.current();
            if (current != null && this.grid.selected().contains(current)) {
                activate(current);
                return true;
            }
        }
        return false;
    }

    private void activate(CollectionCard card) {
        WorldCollectionRecord record = card.record();
        if (record == null) {
            newCollection();
        } else {
            edit(record);
        }
    }

    private boolean cardClicked(CollectionCard card, UiCardGrid.Card at, UiInput.Click click) {
        if (card.isNewCard()) {
            if (click.left() && !click.ctrl() && !click.shift()) {
                newCollection();
                return true;
            }
            return false;
        }
        if (card.click(at, click)) {
            return true;
        }
        WorldCollectionRecord record = card.record();
        if (click.right() && record != null) {
            openCardMenu(record, new UiNode.Rect((int) click.x(), (int) click.y() - at.scrolled(), 0, 0));
            return true;
        }
        return false;
    }

    // Actions

    /** Opens the new-collection wizard over this screen, or says why a collection cannot be added. */
    private void newCollection() {
        Component reason = CollectionActions.tooManyCollections();
        if (reason != null) {
            toast(reason);
            return;
        }
        this.minecraft.setScreen(new CreatorWizardScreen(this));
    }

    private void edit(WorldCollectionRecord record) {
        if (ClientWorldCollections.record(record.id()) == null) {
            // Gone in a sync since the card was built
            return;
        }
        this.minecraft.setScreen(new CreatorEditorScreen(this, record.id(), this.machinePos,
                CreatorEditorScreen.Tab.IDENTITY));
    }

    /** The claw or gacha machine the creator was opened from, or null. */
    @Nullable
    BlockPos machinePos() {
        return this.machinePos;
    }

    /** Opens a collection's menu of its own actions under an anchor, such as its "..." button. */
    private void openCardMenu(WorldCollectionRecord record, UiNode.Rect anchor) {
        openMenu(this.actions.menu(record), anchor);
    }

    /**
     * Selects a collection this screen asked for and scrolls to it, now if the server already
     * synced it, which can come before its answer, or else once it does.
     */
    void reveal(String collectionId) {
        this.revealWhenSynced = collectionId;
        if (ClientWorldCollections.record(collectionId) != null && this.minecraft.screen == this) {
            rebuild();
        }
    }

    // At most a number of characters, never splitting a pair of surrogates
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end).stripTrailing();
    }
}
