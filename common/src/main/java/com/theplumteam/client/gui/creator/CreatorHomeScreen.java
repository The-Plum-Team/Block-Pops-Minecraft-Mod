package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiSideList;
import com.theplumteam.client.gui.creator.ui.UiSideRow;
import com.theplumteam.client.gui.creator.ui.UiStage;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.UiViewport;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorAutosave;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModItems;
import com.theplumteam.registry.ModSounds;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The creator's first screen, in the layout of Quick Skin's settings ({@link SidebarScreen}): the
 * world's World collections down the sidebar, and the one picked shown off in the content panel
 * with one green "Open".
 *
 * <p>The sidebar lists the published collections and the drafts, the last edited first, each
 * with its box and, at its right end, its number of figures, a draft's gold dot and word, or a
 * red count of the files the server lost. The archived ones fold under a row of their own,
 * which stays open or closed for the rest of the session and opens whenever the selected
 * collection is in it; folding it over the selected row selects the first collection shown. A
 * search row, shown by itself once the world has more than eight collections or by Ctrl+F,
 * filters by name, author or slug over every collection, archived ones included, as one flat
 * list, and selects its first match when it hides the selected row. The heading holds the help,
 * which also gives the shortcuts and the server's file space, and a cross that leaves the
 * creator; the status slot speaks up only when the server's space runs low or the world has
 * every collection it may; the sidebar's button starts a new collection.
 *
 * <p>A new collection starts from its name alone, in a form the content panel shows while a
 * temporary "New" row at the top of the list is selected ({@link CreateForm}); selecting another
 * row, Cancel or Escape closes it and the selection it left returns. From "Create" until the
 * server has synced the new collection, the home is locked: the rows, the sidebar's button, the
 * help, the search and the shortcuts wait, and only Cancel, Escape and the cross act. The new
 * collection then opens in the editor.
 *
 * <p>The content shows the selected collection: its name with a badge unless it is simply
 * published, its number of figures and its author, its box and as many of its figures as the
 * panel holds on a stage in its colour ({@link UiStage}), how many of its figures in the draw
 * are of each rarity, and a line that says where players find it, which offers to put it in the
 * machine the creator was opened from; the stage and its lines are centred in the height the
 * panel leaves them. "Open" (and a double click or Enter on its row) opens it
 * in the collection editor ({@link CreatorEditorScreen}) on its figures; "More", or a right
 * click on its row, holds the collection's own actions ({@link CollectionActions}). A world with
 * no collection shows a single invitation to create one, or to import one from a data pack.
 *
 * <p>The selected collection is kept for the rest of the session. Every action only asks the
 * server, which checks it again; the screen follows the collections the server syncs back.
 */
public class CreatorHomeScreen extends SidebarScreen {
    // The search row shows by itself once the world has more collections than this
    private static final int SEARCH_ALWAYS = 8;
    // The search row under the sidebar's heading, and how far it moves the list down
    private static final int SEARCH_TOP = 36;
    private static final int SEARCH_SHIFT = 24;
    // The showcase, from the content panel's top: the subtitle and the stage, raised on a short canvas
    private static final int SUBTITLE_Y = 38;
    private static final int SUBTITLE_Y_SHORT = 26;
    private static final int STAGE_TOP = 50;
    private static final int STAGE_TOP_SHORT = 38;
    private static final int MIN_STAGE = 70;
    private static final int MAX_STAGE = 260;
    // A stage grows past the height its figures need by at most this share of it
    private static final int STAGE_GROW_SHARE = 5;
    // The lines under the stage: the rarities, then where players find the collection; a line's
    // text ends this much above the next line's top
    private static final int INFO_GAP = 9;
    private static final int INFO_LINE = 14;
    private static final int INFO_TAIL = 4;
    // The stage and its lines keep this far above the buttons
    private static final int BLOCK_BOTTOM_GAP = 8;
    private static final int DOT_ROOM = 9;
    private static final int MIN_OPEN_WIDTH = 78;
    private static final int MORE_SIZE = 20;
    // The empty state: the machine and a box, the invitation, its button, the help and the import link
    private static final int EMPTY_ART = 64;
    private static final int EMPTY_BOX = 40;
    // A box's middle above the line it stands on, against its size, as on the stage
    private static final float EMPTY_BOX_MIDDLE = 0.56f;
    private static final int EMPTY_BUTTON_WIDTH = 180;
    private static final int EMPTY_BUTTON_HEIGHT = 24;
    private static final int EMPTY_HELP_LINES = 3;
    private static final int EMPTY_GAP = 10;
    // The room the status slot keeps for its glyph, before the label
    private static final int STATUS_GLYPH_ROOM = 12;
    // The server's file space counts as running low from this share of it
    private static final float SPACE_LOW = 0.85f;
    // The key of the archived fold's own row, which no collection's id can be
    private static final Object ARCHIVED_FOLD = new Object();
    // The key of the new collection's temporary row, which no collection's id can be either
    private static final Object NEW_ROW = new Object();
    // A toast keeps this far from the lines under the stage, and where it cannot, stands this far
    // up the stage, on its floor
    private static final int TOAST_CLEARANCE = 3;
    private static final int TOAST_FLOOR_INSET = 3;

    // Kept for the rest of the session, so the home opens on the collection it was left on
    @Nullable
    private static String lastSelected;
    private static boolean archivedOpen;

    @Nullable
    private final BlockPos machinePos;
    private final CollectionActions actions;
    // Kept across rebuilds, so the selection, the folds and the scroll stay
    private final UiSideList list = new UiSideList();
    private final ItemStack boxIcon = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
    private final ItemStack machineIcon = new ItemStack(ModItems.CLAW_MACHINE_BLOCK_ITEM.get());
    @Nullable
    private String selectedId = lastSelected;
    private String search = "";
    // Whether Ctrl+F showed the search row, which a world of few collections leaves out
    private boolean searchAsked;
    // A collection this screen asked for, selected and scrolled to once the server syncs it
    @Nullable
    private String revealWhenSynced;
    // Whether the list takes the keyboard the next time the screen is shown
    private boolean focusListOnShow = true;
    @Nullable
    private EditBox searchBox;
    // The collections as the list shows them, the archived ones apart, as last built
    private List<WorldCollectionRecord> active = List.of();
    private List<WorldCollectionRecord> archived = List.of();
    // What the content shows, as last built
    @Nullable
    private CollectionStatus shown;
    private int stageTop;
    private int stageHeight;
    // The state line as laid out, in one line or two, and whether that cut it
    private List<String> stateText = List.of();
    private boolean stateCut;
    private int emptyTop;
    private int emptyHelpY;
    private List<String> emptyHelp = List.of();
    // The empty state's box, holding a figure of the game's own collection, and whether the
    // budget lets it be drawn as a model rather than as the flat item
    private ItemStack emptyBox = ItemStack.EMPTY;
    private int emptyBoxModels;
    // The new collection's form while it is open; the content shows it in place of the selection
    @Nullable
    private CreateForm form;
    // The collections whose rows said "saving" when the list was last filled
    private Set<String> shownSaving = Set.of();

    /**
     * @param parent     the screen to go back to; null when the creator was opened by its
     *                   command, so that leaving it returns to the game
     * @param machinePos the claw or gacha machine the creator was opened from, which the home
     *                   offers to put a collection in; null when there is none
     */
    public CreatorHomeScreen(@Nullable Screen parent, @Nullable BlockPos machinePos) {
        super(tr("side.heading"), parent);
        this.machinePos = machinePos;
        this.actions = new CollectionActions(this, machinePos, this::reveal);
    }

    // The sidebar

    @Override
    protected void buildSidebar() {
        List<WorldCollectionRecord> records = ClientWorldCollections.records();
        splitForList(records);
        String revealed = takeReveal();
        keepSelection();

        // While a new collection is being created only the cross, Cancel and Escape act
        Component lock = creating() ? tr("wizard.creating") : null;
        sidebarHeading(tr("side.heading"));
        // Each glyph goes left of the one before: the cross at the far right, the help before it
        headingGlyph(PixelIcon.CLOSE, tr("side.close"), this::closeCreator);
        headingGlyph(Theme.ICON_HELP, tr("side.help"), this.actions::openHelp).disable(lock);

        UiNode.Rect area = sideList();
        this.searchBox = null;
        if (searchShown(records.size())) {
            this.searchBox = textField("search", this.side.x() + 6, this.side.y() + SEARCH_TOP,
                    Math.max(0, this.side.width() - 12), WorldContentLimits.MAX_NAME_LENGTH, tr("home.search_hint"),
                    this.search, this::searchTyped);
            this.searchBox.active = lock == null;
            area = new UiNode.Rect(area.x(), area.y() + SEARCH_SHIFT, area.width(),
                    Math.max(0, area.height() - SEARCH_SHIFT));
        }
        this.list.at(area.x(), area.y(), area.width(), area.height());
        this.list.lock(lock);
        fillList();
        addNode(this.list);
        // A search shows only some rows, and clearing it every row: ask for the boxes of them all
        this.list.reserveModels(records.size());
        budget(this.list);
        if (revealed != null) {
            this.list.reveal(revealed);
        }

        UiButton create = new UiButton(tr("side.new"), UiButton.Style.SECONDARY, this::newCollection);
        create.setGlyph(PixelIcon.PLUS);
        Component full = lock != null ? lock : CollectionActions.tooManyCollections();
        if (full != null) {
            create.disable(full);
        } else {
            create.setTooltip(CollectionActions.quotaText());
        }
        sideFooter(create);

        if (this.focusListOnShow && this.minecraft.screen == this) {
            // The list takes the keyboard whenever the screen is shown, so the arrows move through
            // it, unless the new collection's form is open, whose name takes it
            this.focusListOnShow = false;
            if (this.form != null) {
                this.form.focusName();
            } else {
                focusNode(this.list);
            }
        }
    }

    /** Sorts the collections as the list shows them, the last edited first, and keeps the archived ones apart. */
    private void splitForList(List<WorldCollectionRecord> records) {
        // A stable sort, so collections edited at the same time keep the server's order
        List<WorldCollectionRecord> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparingLong(WorldCollectionRecord::editedAt).reversed());
        List<WorldCollectionRecord> shownRecords = new ArrayList<>();
        List<WorldCollectionRecord> archivedRecords = new ArrayList<>();
        for (WorldCollectionRecord record : ordered) {
            (record.archived() ? archivedRecords : shownRecords).add(record);
        }
        this.active = List.copyOf(shownRecords);
        this.archived = List.copyOf(archivedRecords);
    }

    /**
     * Takes the collection this screen asked to show once the server has synced it: it becomes
     * the selected one, its fold opens and a search that would hide it is cleared. While the new
     * collection's form is open it waits, and is taken once the form closes.
     *
     * @return its id, for the list to scroll to, or null when there is none to show yet
     */
    @Nullable
    private String takeReveal() {
        if (this.form != null) {
            // The form keeps the list's selection until it closes, and the collection waits
            return null;
        }
        String id = this.revealWhenSynced;
        WorldCollectionRecord record = id == null ? null : ClientWorldCollections.record(id);
        if (record == null) {
            return null;
        }
        this.revealWhenSynced = null;
        setSelected(id);
        if (record.archived()) {
            archivedOpen = true;
        }
        if (!matches(record, query())) {
            this.search = "";
        }
        return id;
    }

    /**
     * Keeps the selected collection while it exists, else selects the first row: the first
     * collection shown, or the first archived one when every one is. Whenever the selected
     * collection is archived, its fold opens as the list is filled ({@link #fillList}), so its
     * row stays in sight.
     */
    private void keepSelection() {
        if (selectedRecord() != null) {
            return;
        }
        if (!this.active.isEmpty()) {
            setSelected(this.active.get(0).id());
        } else if (!this.archived.isEmpty()) {
            setSelected(this.archived.get(0).id());
        } else {
            this.selectedId = null;
        }
    }

    private void setSelected(String id) {
        this.selectedId = id;
        lastSelected = id;
    }

    @Nullable
    private WorldCollectionRecord selectedRecord() {
        return this.selectedId == null ? null : ClientWorldCollections.record(this.selectedId);
    }

    /**
     * The archived fold opened or closed by the operator. Closing it over the selected row
     * selects the first collection shown instead, so a row in sight is always the selected one;
     * with no other collection to show, the selection stays and the fold opens again at the
     * next build.
     */
    private void archivedToggled(boolean open) {
        archivedOpen = open;
        WorldCollectionRecord selected = selectedRecord();
        if (!open && selected != null && selected.archived() && !this.active.isEmpty()) {
            reveal(this.active.get(0).id());
        }
    }

    /** Whether the search row shows: in a world of many collections, after Ctrl+F, or while it holds a search. */
    private boolean searchShown(int collections) {
        return collections > SEARCH_ALWAYS || this.searchAsked || !this.search.isEmpty();
    }

    /**
     * Fills the list again from the collections as last built: those shown and the fold of the
     * archived ones, or while searching every match as one flat list, archived ones included.
     * A search that hides the selected row selects its first match, so the content shows a
     * collection the list shows; one that matches nothing keeps the selection. Without a search,
     * a selected collection that is archived opens the fold, so its row is in sight: one archived
     * meanwhile, one found by a search just cleared, or the one the home was left on. While the
     * new collection's form is open, its own row comes first and is the one selected, and a
     * search leaves the selection alone, so the one the form left returns when it closes.
     */
    private void fillList() {
        this.list.clearRows();
        CreateForm open = this.form;
        if (open != null) {
            // The new collection's row, first and selected while its form is open
            this.list.add(new UiSideRow(NEW_ROW, tr("side.new_row")).glyph(PixelIcon.PLUS)
                    .onActivate(open::focusNameNow));
        }
        String query = query();
        boolean searching = !query.isEmpty();
        this.list.setEmptyText(searching ? tr("home.no_match") : null);
        if (searching) {
            List<WorldCollectionRecord> found = new ArrayList<>();
            for (WorldCollectionRecord record : this.active) {
                if (matches(record, query)) {
                    found.add(record);
                }
            }
            for (WorldCollectionRecord record : this.archived) {
                if (matches(record, query)) {
                    found.add(record);
                }
            }
            if (open == null && !found.isEmpty()
                    && found.stream().noneMatch(record -> record.id().equals(this.selectedId))) {
                setSelected(found.get(0).id());
            }
            for (WorldCollectionRecord record : found) {
                this.list.add(row(record));
            }
        } else {
            for (WorldCollectionRecord record : this.active) {
                this.list.add(row(record));
            }
            if (!this.archived.isEmpty()) {
                WorldCollectionRecord selected = selectedRecord();
                if (selected != null && selected.archived()) {
                    archivedOpen = true;
                }
                UiSideRow header = new UiSideRow(ARCHIVED_FOLD, tr("side.archived")).faint(true)
                        .count(Component.literal(String.valueOf(this.archived.size())));
                UiSideList.Fold fold = this.list.fold(header, archivedOpen, this::archivedToggled);
                for (WorldCollectionRecord record : this.archived) {
                    fold.add(row(record));
                }
            }
        }
        Object selectedRow = open != null ? NEW_ROW : this.selectedId;
        if (!Objects.equals(this.list.selectedKey(), selectedRow)) {
            this.list.select(selectedRow);
        }
    }

    // A collection's row: its box, its name and its state, faint while it is archived
    private UiSideRow row(WorldCollectionRecord record) {
        CollectionStatus status = CollectionStatus.of(record);
        String id = record.id();
        UiSideRow row = new UiSideRow(id, Component.literal(displayName(record)))
                .box(UiViewport.boxStack(id, status.boxFigureId(), false), status.bandColour())
                .faint(record.archived())
                .onSelect(() -> {
                    setSelected(id);
                    CreateForm open = this.form;
                    if (open != null) {
                        // Choosing a collection leaves the new one's form, as Cancel does
                        closeForm(open);
                    } else {
                        rebuild();
                    }
                })
                .onActivate(() -> open(id))
                .onMenu(anchor -> openMenuFor(id, anchor));
        status.showOnRow(row, id.equals(this.selectedId));
        return row;
    }

    private void searchTyped(String value) {
        this.search = value;
        String before = this.selectedId;
        // Only the rows change, unless the search moved the selection: a rebuild makes the field
        // again, which keeps the keyboard but puts its cursor at the end, where typing leaves it
        fillList();
        if (!Objects.equals(before, this.selectedId)) {
            rebuild();
        }
    }

    private String query() {
        return this.search.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(WorldCollectionRecord record, String query) {
        return query.isEmpty() || record.name().toLowerCase(Locale.ROOT).contains(query)
                || record.author().toLowerCase(Locale.ROOT).contains(query) || record.slug().contains(query);
    }

    private static String displayName(WorldCollectionRecord record) {
        return record.name().isBlank() ? record.slug() : record.name();
    }

    /**
     * The server's file space while it runs low, in amber from 85% and in red once it is full,
     * or the collection count once the world has every collection it may; nothing otherwise.
     */
    @Override
    @Nullable
    protected SideStatus sideStatus() {
        int used = ClientWorldCollections.quotaUsedKiB();
        int limit = ClientWorldCollections.quotaMaxKiB();
        float fraction = limit <= 0 ? 0.0f : used / (float) limit;
        if (fraction >= 1.0f) {
            return spaceStatus(tr("side.space_full"), Theme.RED, PixelIcon.CLOSE);
        }
        int collections = ClientWorldCollections.records().size();
        if (collections >= WorldContentLimits.MAX_COLLECTIONS) {
            return spaceStatus(tr("side.collections_full", collections, WorldContentLimits.MAX_COLLECTIONS),
                    Theme.AMBER, Theme.ICON_WARNING);
        }
        if (fraction >= SPACE_LOW) {
            return spaceStatus(tr("side.space_low"), Theme.AMBER, Theme.ICON_WARNING);
        }
        return null;
    }

    // A state of the space, with the whole of the quota in its tooltip, after the label when the
    // slot is too narrow to show all of it
    private SideStatus spaceStatus(Component label, int colour, String[] glyph) {
        Component quota = CollectionActions.quotaText();
        int room = this.side.width() - 20 - STATUS_GLYPH_ROOM;
        Component tooltip = this.font.width(label) <= room ? quota
                : Component.empty().append(label).append(" · ").append(quota);
        return SideStatus.of(label, colour).withGlyph(glyph).withTooltip(tooltip);
    }

    // The content

    @Override
    protected void buildContent() {
        this.shown = null;
        if (this.form != null) {
            this.form.build(this.content);
            return;
        }
        if (ClientWorldCollections.records().isEmpty()) {
            buildEmpty();
            return;
        }
        WorldCollectionRecord record = this.selectedId == null ? null : ClientWorldCollections.record(this.selectedId);
        if (record == null) {
            return;
        }
        CollectionStatus status = CollectionStatus.of(record);
        this.shown = status;
        String id = record.id();
        layoutShowcase(status);
        UiStage stage = new UiStage(id, status.boxFigureId(), status.figureOrder(), status.bandColour())
                .narrow(narrow())
                .greyed(record.archived())
                .emptyNote(tr("show.no_figures"))
                .more(count -> tr("show.more_figures", count), tr("show.see_all"), () -> open(id))
                .tooltip(stageTooltip(status));
        stage.at(left(), this.stageTop, inner(), this.stageHeight);
        addNode(stage);
        budget(stage);

        UiButton openButton = new UiButton(tr("show.open"), UiButton.Style.PRIMARY, () -> open(id));
        openButton.at(left(), footerY(), Math.max(MIN_OPEN_WIDTH, openButton.naturalWidth()), UiButton.HEIGHT);
        addNode(openButton);
        // The button names itself in its own action, which a local cannot do yet
        UiButton[] more = new UiButton[1];
        more[0] = new UiButton(narrow() ? Component.empty() : tr("show.more"), UiButton.Style.SECONDARY,
                () -> openMoreMenu(id, more[0].bounds()));
        if (narrow()) {
            more[0].setGlyph(Theme.ICON_MORE);
            more[0].setTooltip(tr("show.more_short"));
            more[0].at(right() - MORE_SIZE, footerY(), MORE_SIZE, UiButton.HEIGHT);
        } else {
            int moreWidth = more[0].naturalWidth();
            more[0].at(right() - moreWidth, footerY(), moreWidth, UiButton.HEIGHT);
        }
        addNode(more[0]);
        buildPutLink(record, status);
    }

    /**
     * Places the stage and the lines under it. The stage fills the room the lines leave, about
     * as tall as its figures need, and the whole block is centred in the height left between
     * the subtitle and the buttons, so a tall canvas has no empty band under it. The state line
     * takes a second line when it does not fit in one and that room is there, before the stage
     * takes any of it.
     */
    private void layoutShowcase(CollectionStatus status) {
        int top = this.content.y() + (isShort() ? STAGE_TOP_SHORT : STAGE_TOP);
        int end = footerY() - BLOCK_BOTTOM_GAP;
        int lines = isShort() ? 1 : 2;
        this.stageHeight = Math.min(clamp(end - top - linesHeight(lines), MIN_STAGE, MAX_STAGE),
                UiStage.preferredHeight(inner(), narrow()));
        String state = status.stateLine().text().getString();
        int stateWidth = Math.max(1, inner() - DOT_ROOM);
        int leftover = end - top - this.stageHeight - linesHeight(lines);
        boolean wraps = this.font.width(state) > stateWidth && leftover >= INFO_LINE;
        if (wraps) {
            leftover -= INFO_LINE;
        }
        this.stateText = Draw.fit(this.font, state, stateWidth, wraps ? 2 : 1);
        this.stateCut = Draw.wrap(this.font, state, stateWidth).size() > this.stateText.size();
        // The stage takes some of what is left as room over the figures, up to a fifth of its
        // height, so the gaps around the block stay small; the rest is shared above and below
        int grow = clamp(Math.min(leftover, MAX_STAGE - this.stageHeight), 0, this.stageHeight / STAGE_GROW_SHARE);
        this.stageHeight += grow;
        leftover -= grow;
        this.stageTop = top + Math.max(0, leftover / 2);
    }

    // The lines under the stage, from its bottom to the bottom of the last line's text
    private static int linesHeight(int lines) {
        return INFO_GAP + lines * INFO_LINE - INFO_TAIL;
    }

    /**
     * The faint link after the state line that puts the collection in the machine the creator
     * was opened from, when there is one, the collection can go in it and the line has the room.
     */
    private void buildPutLink(WorldCollectionRecord record, CollectionStatus status) {
        BlockPos machine = this.machinePos;
        if (machine == null || CollectionActions.putProblem(record) != null || this.stateText.isEmpty()) {
            return;
        }
        Component link = tr("show.put_link");
        String last = this.stateText.get(this.stateText.size() - 1);
        int linkX = left() + DOT_ROOM + this.font.width(last) + this.font.width(" ");
        int linkWidth = this.font.width(link);
        if (this.stateCut || linkX + linkWidth > right()) {
            // "More" still offers it
            return;
        }
        String id = record.id();
        String name = record.name();
        TextLink put = new TextLink(link, () -> CollectionActions.put(machine, id, name), () -> {
            WorldCollectionRecord now = ClientWorldCollections.record(id);
            return now != null && CollectionActions.putProblem(now) == null;
        });
        put.at(linkX, stateLineY() + (this.stateText.size() - 1) * INFO_LINE - 1, linkWidth, Theme.LINE_HEIGHT);
        addNode(put);
    }

    // What the stage says when the pointer is over it: the collection's size, and on a short
    // canvas the rarities its lines leave out
    private List<Component> stageTooltip(CollectionStatus status) {
        List<Component> lines = new ArrayList<>();
        lines.add(status.size());
        Component rarities = status.raritySummary();
        if (isShort() && !rarities.getString().isEmpty()) {
            lines.add(rarities);
        }
        return lines;
    }

    private int stateLineY() {
        return this.stageTop + this.stageHeight + INFO_GAP + (isShort() ? 0 : INFO_LINE);
    }

    // The empty state: the machine and a box, the invitation and its button, the help, and the import
    private void buildEmpty() {
        int centre = this.content.x() + this.content.width() / 2;
        this.emptyHelp = Draw.fit(this.font, tr("home.subtitle").getString(), inner(), EMPTY_HELP_LINES);
        int blockHeight = EMPTY_ART + EMPTY_GAP + Theme.LINE_HEIGHT + EMPTY_GAP + EMPTY_BUTTON_HEIGHT + 8
                + this.emptyHelp.size() * Theme.LINE_HEIGHT + 8 + Theme.LINE_HEIGHT;
        this.emptyTop = this.content.y() + Math.max(Theme.SPACE_S, (bottom() - this.content.y() - blockHeight) / 2);
        int buttonY = this.emptyTop + EMPTY_ART + EMPTY_GAP + Theme.LINE_HEIGHT + EMPTY_GAP;
        UiButton create = new UiButton(tr("home.empty_create"), UiButton.Style.PRIMARY, this::newCollection);
        int buttonWidth = Math.min(inner(), Math.max(EMPTY_BUTTON_WIDTH, create.naturalWidth()));
        create.at(centre - buttonWidth / 2, buttonY, buttonWidth, EMPTY_BUTTON_HEIGHT);
        create.disable(CollectionActions.tooManyCollections());
        addNode(create);
        this.emptyHelpY = buttonY + EMPTY_BUTTON_HEIGHT + 8;
        Component link = tr("create.import");
        int linkWidth = Math.min(inner(), this.font.width(link));
        TextLink importLink = new TextLink(link, this.actions::openImport, null);
        importLink.at(centre - linkWidth / 2, this.emptyHelpY + this.emptyHelp.size() * Theme.LINE_HEIGHT + 8 - 1,
                linkWidth, Theme.LINE_HEIGHT);
        addNode(importLink);
        this.emptyBox = sampleBox();
        if (!this.emptyBox.isEmpty()) {
            budget((SidebarScreen.Budgeted) models -> models.request(SidebarScreen.ModelBudget.Rank.PREVIEW, 1,
                    granted -> this.emptyBoxModels = granted));
        }
    }

    /**
     * A closed box holding a figure, as the stage shows a collection's box, so the empty state's
     * box reads as a box: the default collection's (the world's players) when it has a figure,
     * else the first loaded collection's that has one, else the default collection's box alone;
     * empty while no collection is loaded.
     */
    private static ItemStack sampleBox() {
        FigureCollection fallback = CollectionRegistry.getDefaultCollection().orElse(null);
        List<FigureCollection> candidates = new ArrayList<>();
        if (fallback != null) {
            candidates.add(fallback);
        }
        candidates.addAll(CollectionRegistry.getAllCollections());
        for (FigureCollection collection : candidates) {
            List<FigureDefinition> figures = collection.getEnabledFigures();
            if (!figures.isEmpty()) {
                return UiViewport.boxStack(collection.getId(), figures.get(0).getId(), false);
            }
        }
        return fallback == null ? ItemStack.EMPTY : UiViewport.boxStack(fallback.getId(), null, false);
    }

    @Override
    protected void onSync() {
        // build() reads the new records; nothing here outlives a sync
    }

    // Drawing

    @Override
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.form != null) {
            contentTitle(graphics, tr("home.new"), 0);
            this.form.draw(graphics, mouseX, mouseY);
            return;
        }
        if (ClientWorldCollections.records().isEmpty()) {
            drawEmpty(graphics);
            return;
        }
        CollectionStatus status = this.shown;
        if (status == null) {
            return;
        }
        drawTitle(graphics, status, mouseX, mouseY);
        int subtitleY = this.content.y() + (isShort() ? SUBTITLE_Y_SHORT : SUBTITLE_Y);
        drawLine(graphics, status.subtitle().getString(), left(), subtitleY, inner(), Theme.MUTED, mouseX, mouseY);
        if (!isShort()) {
            drawRarities(graphics, status, this.stageTop + this.stageHeight + INFO_GAP, mouseX, mouseY);
        }
        drawState(graphics, status, stateLineY(), mouseX, mouseY);
    }

    // The name in the pixel font, with the badge of a collection that is not simply published after it
    private void drawTitle(GuiGraphics graphics, CollectionStatus status, int mouseX, int mouseY) {
        Component name = Component.literal(displayName(status.record()));
        Component badge = status.badge();
        int badgeWidth = badge == null ? 0 : Draw.badgeWidth(this.font, badge, inner());
        int titleEnd = contentTitle(graphics, name, badgeWidth);
        int middle = contentTitleMiddle();
        if (mouseX >= left() && mouseX < titleEnd && mouseY >= middle - 6 && mouseY < middle + 6
                && PixelFont.width(name, PixelFont.crispScale(1.0f)) > titleEnd - left()) {
            UiTooltip.set(name);
        }
        if (badge == null) {
            return;
        }
        // Outlined only, as quiet as the subtitle: the state line says the rest
        int badgeX = titleEnd + 8;
        int badgeY = middle - 6;
        Draw.badge(graphics, this.font, badge, badgeX, badgeY, inner(), Theme.PANEL, Theme.TEXT_SOFT);
        Component tooltip = status.badgeTooltip();
        if (tooltip != null && mouseX >= badgeX && mouseX < badgeX + badgeWidth && mouseY >= badgeY
                && mouseY < badgeY + 12) {
            UiTooltip.set(tooltipLines(tooltip));
        }
    }

    /**
     * How many figures in the draw are of each rarity, each after a dot in its colour; when the
     * words do not fit, the numbers alone, with the words in the line's tooltip.
     */
    private void drawRarities(GuiGraphics graphics, CollectionStatus status, int lineY, int mouseX, int mouseY) {
        int wordsWidth = -14;
        for (Rarity rarity : Rarity.values()) {
            int count = status.inDraw(rarity);
            if (count > 0) {
                wordsWidth += DOT_ROOM + this.font.width(CollectionStatus.rarityText(rarity, count)) + 14;
            }
        }
        if (wordsWidth < 0) {
            // No figure is in the draw
            return;
        }
        boolean words = wordsWidth <= inner();
        int x = left();
        for (Rarity rarity : Rarity.values()) {
            int count = status.inDraw(rarity);
            if (count == 0) {
                continue;
            }
            String label = words ? CollectionStatus.rarityText(rarity, count).getString() : String.valueOf(count);
            Draw.dot(graphics, x + 2, lineY + 3, 5, CollectionStatus.rarityColour(rarity));
            Draw.string(graphics, this.font, label, x + DOT_ROOM, lineY, Theme.TEXT_SOFT);
            x += DOT_ROOM + this.font.width(label) + (words ? 14 : 10);
        }
        if (!words && mouseX >= left() && mouseX < x && mouseY >= lineY - 1 && mouseY < lineY + 10) {
            UiTooltip.set(status.raritySummary());
        }
    }

    // Where players find the collection, after a dot in the state's colour, on one line or two,
    // with the whole of it in its tooltip when it was cut; the put link follows it
    private void drawState(GuiGraphics graphics, CollectionStatus status, int lineY, int mouseX, int mouseY) {
        CollectionStatus.StateLine line = status.stateLine();
        Draw.dot(graphics, left() + 2, lineY + 3, 4, line.dotColour());
        int y = lineY;
        for (String text : this.stateText) {
            Draw.string(graphics, this.font, text, left() + DOT_ROOM, y, line.textColour());
            y += INFO_LINE;
        }
        if (this.stateCut && mouseX >= left() && mouseX < right() && mouseY >= lineY - 1 && mouseY < y - 4) {
            UiTooltip.set(tooltipLines(line.text()));
        }
    }

    // A line of text cut to a width, with the whole of it in its tooltip when it was cut
    private void drawLine(GuiGraphics graphics, String text, int x, int y, int width, int colour, int mouseX,
                          int mouseY) {
        Draw.text(graphics, this.font, text, x, y, width, colour);
        if (this.font.width(text) > width && mouseX >= x && mouseX < x + width && mouseY >= y - 1 && mouseY < y + 10) {
            UiTooltip.set(tooltipLines(Component.literal(text)));
        }
    }

    private void drawEmpty(GuiGraphics graphics) {
        int centre = this.content.x() + this.content.width() / 2;
        int top = this.emptyTop;
        Draw.item(graphics, this.machineIcon, centre - 52, top, EMPTY_ART);
        if (!this.emptyBox.isEmpty() && this.emptyBoxModels > 0) {
            // Turned as the stage turns a box, standing on the machine's floor line
            UiViewport.drawBoxFront(graphics, this.emptyBox, UiStage.BOX_YAW, 0, centre + 16 + EMPTY_BOX / 2.0f,
                    top + EMPTY_ART - EMPTY_BOX * EMPTY_BOX_MIDDLE, EMPTY_BOX);
        } else {
            Draw.item(graphics, this.boxIcon, centre + 16, top + EMPTY_ART - EMPTY_BOX, EMPTY_BOX);
        }
        Draw.centred(graphics, this.font, tr("home.empty").getString(), centre, top + EMPTY_ART + EMPTY_GAP, inner(),
                Theme.TEXT);
        int y = this.emptyHelpY;
        for (String line : this.emptyHelp) {
            Draw.centred(graphics, this.font, line, centre, y, inner(), Theme.MUTED);
            y += Theme.LINE_HEIGHT;
        }
    }

    /** A line {@link #status} sets is a toast on the home, whose status slot speaks only of the server's space. */
    @Override
    protected void statusSink(Component text, int colour) {
        if (!text.getString().isEmpty()) {
            UiToast.show(text);
        }
    }

    /**
     * Where a toast stands on the home. Over the showcase it keeps clear of the lines under the
     * stage, which say the state a message may be about, and of the buttons: under the lines
     * where it fits, else over the stage's floor. With no collection the content has no buttons
     * on its footer line, so it stands at the panel's foot. Over the new collection's form, under
     * the form.
     */
    @Override
    protected int toastBottom() {
        int belowLines = super.toastBottom();
        if (this.form != null) {
            return belowLines;
        }
        if (ClientWorldCollections.records().isEmpty()) {
            return bottom() - 4;
        }
        if (this.shown == null) {
            return belowLines;
        }
        int linesBottom = stateLineY() + this.stateText.size() * INFO_LINE - INFO_TAIL;
        if (belowLines - UiToast.height(toastWidth()) >= linesBottom + TOAST_CLEARANCE) {
            return belowLines;
        }
        return this.stageTop + this.stageHeight - TOAST_FLOOR_INSET;
    }

    @Override
    protected void filesDroppedNowhere(List<Path> paths) {
        toast(tr("drop.nowhere"));
    }

    // Input

    @Override
    protected boolean shortcut(UiInput.Key key) {
        CreateForm open = this.form;
        if (open != null && open.locked()) {
            // Nothing but Cancel, Escape and the cross until the new collection arrives
            return false;
        }
        if (key.ctrl() && key.is(InputConstants.KEY_N)) {
            newCollection();
            return true;
        }
        if (key.ctrl() && key.is(InputConstants.KEY_F)) {
            focusSearch();
            return true;
        }
        if (open != null) {
            // The form has the content: Enter creates, and the arrows leave it for a collection
            if (key.enter()) {
                open.submit();
                return true;
            }
            if (key.is(InputConstants.KEY_UP) || key.is(InputConstants.KEY_DOWN) || key.is(InputConstants.KEY_HOME)
                    || key.is(InputConstants.KEY_END)) {
                focusNode(this.list);
                return this.list.key(key);
            }
            return false;
        }
        WorldCollectionRecord selected = this.selectedId == null ? null : ClientWorldCollections.record(this.selectedId);
        if (selected == null) {
            return false;
        }
        if (key.enter()) {
            open(selected.id());
            return true;
        }
        if (key.is(InputConstants.KEY_DELETE)) {
            if (CollectionActions.canPurge(selected) && CollectionActions.savingProblem(selected.id()) == null) {
                this.actions.confirmPurge(selected);
            }
            return true;
        }
        if (key.is(InputConstants.KEY_UP) || key.is(InputConstants.KEY_DOWN) || key.is(InputConstants.KEY_HOME)
                || key.is(InputConstants.KEY_END)) {
            // The arrows always move through the list, whichever button was clicked last
            focusNode(this.list);
            return this.list.key(key);
        }
        return false;
    }

    @Override
    protected boolean fieldKey(EditBox field, UiInput.Key key) {
        return this.form != null && this.form.fieldKey(field, key);
    }

    /** Shows the search row, when a world of few collections leaves it out, and gives it the keyboard. */
    private void focusSearch() {
        if (ClientWorldCollections.records().isEmpty()) {
            return;
        }
        if (this.searchBox == null) {
            this.searchAsked = true;
            rebuild();
        }
        if (this.searchBox != null) {
            focusNode(null);
            setFocused(this.searchBox);
        }
    }

    /**
     * Escape, once no menu or dialog is open: closes the new collection's form first, as Cancel
     * does, then clears the search, then hides the search row Ctrl+F showed, and then leaves the
     * creator. The heading's cross leaves at once ({@link #closeCreator}).
     */
    @Override
    public void onClose() {
        if (this.form != null) {
            this.form.cancel();
            return;
        }
        if (!this.search.isEmpty()) {
            if (this.searchBox != null) {
                // Its responder fills the list again
                this.searchBox.setValue("");
            } else {
                this.search = "";
                fillList();
            }
            return;
        }
        if (this.searchAsked && ClientWorldCollections.records().size() <= SEARCH_ALWAYS) {
            this.searchAsked = false;
            rebuild();
            focusNode(this.list);
            return;
        }
        leave();
    }

    /** The heading's cross: leaves the creator, cancelling a new collection as Cancel would. */
    private void closeCreator() {
        if (this.form != null) {
            this.form.cancel();
        }
        leave();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.screen != this) {
            return;
        }
        if (this.form != null) {
            this.form.tick();
        }
        Set<String> saving = CreatorAutosave.savingIds();
        if (!saving.equals(this.shownSaving)) {
            // A collection's changes started or finished saving: its row says so
            this.shownSaving = saving;
            fillList();
        }
    }

    @Override
    public void removed() {
        super.removed();
        this.focusListOnShow = true;
        if (this.form != null) {
            // Shown again when the home is, with what was typed
            this.form.hide();
        }
    }

    // Actions

    /**
     * Opens the new collection's form in the content panel, with its name taking the keyboard, or
     * says why a collection cannot be added; an open form only takes the keyboard again.
     */
    private void newCollection() {
        if (creating()) {
            return;
        }
        Component reason = CollectionActions.tooManyCollections();
        if (reason != null) {
            toast(reason);
            return;
        }
        if (this.form == null) {
            this.form = new CreateForm(this);
            rebuild();
        }
        // Its row is at the top of the list, which may be scrolled down
        this.list.reveal(NEW_ROW);
        // After the rebuild, which gives the keyboard back to the field that had it
        this.form.focusNameNow();
    }

    // Whether a new collection is being created, from Create until it has arrived
    private boolean creating() {
        return this.form != null && this.form.locked();
    }

    // The new collection's form

    /** Whether this screen is the one shown, rather than under or behind another. */
    boolean isShown() {
        return this.minecraft.screen == this;
    }

    <T extends UiNode> T add(T node) {
        return addNode(node);
    }

    EditBox field(String name, int x, int y, int width, int maxLength, Component hint, String value,
                  Consumer<String> responder) {
        return textField(name, x, y, width, maxLength, hint, value, responder);
    }

    /** Asks the screen's budget for the form's box, which ranks with the big previews. */
    void budgetPreview(IntConsumer grant) {
        budget((SidebarScreen.Budgeted) models -> models.request(SidebarScreen.ModelBudget.Rank.PREVIEW, 1, grant));
    }

    /** Gives a text field the keyboard, taking it from the node that had it, such as the list. */
    void focusField(EditBox field) {
        focusNode(null);
        setFocused(field);
    }

    /**
     * The form changed what it shows, as when it starts or stops creating or opens the id's
     * field: the screen is built again, the field the form asks for takes the keyboard, and
     * while it is locked no text field keeps it, so nothing types into them.
     */
    void formChanged(CreateForm changed) {
        if (this.form != changed || !isShown()) {
            return;
        }
        rebuild();
        changed.rebuilt();
        if (changed.locked() && getFocused() instanceof EditBox) {
            setFocused(null);
        }
    }

    /**
     * Closes the form if it is the open one: its preview goes, and the selection it left returns,
     * or a collection asked for meanwhile, scrolled into sight; the list takes the keyboard again.
     */
    void closeForm(CreateForm closed) {
        if (this.form != closed) {
            return;
        }
        this.form = null;
        closed.dispose();
        if (isShown()) {
            rebuild();
            if (this.selectedId != null) {
                this.list.reveal(this.selectedId);
            }
            focusNode(this.list);
        }
    }

    /** The new collection arrived: the form closes, the list selects it and the editor opens on its figures. */
    void created(CreateForm done, String collectionId) {
        closeForm(done);
        reveal(collectionId);
        open(collectionId);
    }

    /** Opens a collection in the editor, on its figures. */
    private void open(String collectionId) {
        if (ClientWorldCollections.record(collectionId) == null) {
            // Gone in a sync since the screen was built
            return;
        }
        this.minecraft.setScreen(new CreatorEditorScreen(this, collectionId, this.machinePos,
                CreatorEditorScreen.Tab.FIGURES));
    }

    /** Opens a collection's menu of its own actions at an anchor, such as the pointer on its row. */
    private void openMenuFor(String collectionId, UiNode.Rect anchor) {
        WorldCollectionRecord record = ClientWorldCollections.record(collectionId);
        if (record != null) {
            openMenu(this.actions.menu(record), anchor);
        }
    }

    /** Opens the selected collection's menu from "More", its right edge on the button's. */
    private void openMoreMenu(String collectionId, UiNode.Rect button) {
        WorldCollectionRecord record = ClientWorldCollections.record(collectionId);
        if (record == null) {
            return;
        }
        UiMenu menu = this.actions.menu(record);
        // Laid out once to learn its width
        menu.place(button, this.width, this.height);
        int menuWidth = menu.bounds().width();
        openMenu(menu, new UiNode.Rect(Math.max(0, button.right() - menuWidth), button.y(), button.width(),
                button.height()));
    }

    /** The claw or gacha machine the creator was opened from, or null. */
    @Nullable
    BlockPos machinePos() {
        return this.machinePos;
    }

    /**
     * Selects a collection this screen asked for and scrolls to it, unfolding the archived ones
     * and clearing a search that hides it, now if the server already synced it, which can come
     * before its answer, or else once it does.
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

    /**
     * A quiet link in faint text, which brightens and is underlined under the pointer, such as
     * the state line's offer to put the collection in the machine.
     */
    private static final class TextLink extends UiNode.Box {
        private final Component text;
        private final Runnable action;
        // Whether the link shows now; null for always
        @Nullable
        private final BooleanSupplier when;

        TextLink(Component text, Runnable action, @Nullable BooleanSupplier when) {
            this.text = text;
            this.action = action;
            this.when = when;
        }

        @Override
        public boolean visible() {
            return super.visible() && (this.when == null || this.when.getAsBoolean());
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean over = hovered(mouseX, mouseY);
            int colour = over ? Theme.TEXT_SOFT : Theme.FAINT;
            String shown = Draw.cut(font, this.text.getString(), this.width);
            Draw.string(graphics, font, shown, this.x, this.y + 1, colour);
            if (over) {
                graphics.fill(this.x, this.y + 10, this.x + font.width(shown), this.y + 11, colour);
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            this.action.run();
            return true;
        }
    }
}
