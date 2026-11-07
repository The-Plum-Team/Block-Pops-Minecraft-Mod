package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiSideList;
import com.theplumteam.client.gui.creator.ui.UiSideRow;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.worldcontent.AutoSaver;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorAutosave;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorRuns;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The collection editor, in the layout of Quick Skin's settings ({@link SidebarScreen}): one World
 * collection's working copy ({@link CreatorDraft}) with its three tabs down the sidebar, Figures,
 * Look and Options, and the page of the selected one ({@link EditorPage}) in the content panel.
 *
 * <p>The sidebar's heading names the collection after an arrow that goes back to the collections,
 * with the help beside it. Each tab's row carries a red dot while something of it cannot be saved
 * as it is (or, for the look, while the name or the box is missing), and, on a published or
 * archived collection, an amber dot while it holds changes not published yet; the figures' row
 * counts the figures otherwise. The status slot says how the saving stands: saved, saving soon,
 * uploading, files waiting for the operator to move on, changes that cannot be saved (a click goes
 * to the first), a refusal (a click gives the details and a retry), the space running low, or a
 * published collection's changes not published yet. At the sidebar's foot is the one explicit
 * step, Publish: a draft's "Publish", a published or archived collection's "Publish changes", each
 * opening the publish popover ({@link PublishPopover}), whose own button is then the screen's one
 * green; a published collection with nothing to publish offers its test draw there instead
 * ({@link TestDrawDialog}).
 *
 * <p>The draft belongs to the collection's saver ({@link AutoSaver}), which outlives the editor
 * until nothing of it waits: a draft collection's changes save themselves a moment after they
 * settle, and its files once the operator moves to another tab or figure, opens a screen of the
 * editor's own, presses Ctrl+S or leaves; a published or archived collection keeps its changes
 * until "Publish changes" sends them all, so players never see half of a change. Opening a
 * collection whose saver is still at work takes up the same draft. Ctrl+Z steps back through the
 * draft's own snapshots, even while a save is on its way, and the next save sends the step back.
 * Leaving with changes that cannot be saved, or with a published collection's changes, asks first.
 *
 * <p>Every change shows at once in the live previews, which draw the draft through the creator's
 * preview slot {@link #PREVIEW_SLOT}, unsent files included. A sync from the server takes the
 * newer collection for every field the operator did not touch ({@link CreatorDraft#rebase}); when
 * the collection is no longer sent at all, because it was deleted or the operator is no longer
 * one, the editor closes and says so. The server checks every edit again; the editor only asks.
 */
public class CreatorEditorScreen extends SidebarScreen {
    /** The editor's tabs, in the sidebar's order. */
    public enum Tab {
        FIGURES, LOOK, OPTIONS
    }

    /**
     * Something that holds publishing back.
     *
     * @param fix takes the operator to where it is fixed; the popover that lists it closes first
     */
    record Blocker(Component text, Runnable fix) {
    }

    /** The preview slot the editor's pages draw the draft through. */
    static final int PREVIEW_SLOT = 1;
    // A page starts this far under the content panel's top; the options start under their title
    private static final int PAGE_TOP = 12;
    private static final int OPTIONS_TOP = 40;
    private static final int OPTIONS_TOP_SHORT = 28;
    // Wide enough that the help's paragraph fits the height of Minecraft's default window
    private static final int HELP_WIDTH = 400;
    // The share of a limit from which the status says the space runs low
    private static final float SPACE_LOW = 0.85f;
    // The main action's green, which rings Publish while its popover is open
    private static final int PRESSED_RING = 0xFF48A048;
    // How long the forecast and the check are kept when nothing of the editor's changed them
    private static final long CHECK_MS = 1_000;

    static {
        // A saver left in the background that cannot finish says so, and offers to open its collection
        CreatorAutosave.onBackgroundFailure(CreatorEditorScreen::backgroundFailed);
    }

    private final String collectionId;
    @Nullable
    private final BlockPos machinePos;
    private final AutoSaver saver;
    private final CreatorDraft draft;
    private final CollectionActions actions;
    private final Map<Tab, EditorPage> pages = new EnumMap<>(Tab.class);
    private final Map<Tab, UiSideRow> tabRows = new EnumMap<>(Tab.class);
    private Tab tab;

    // The sidebar's foot: Publish, the same drawn pressed while its popover is open, and the test
    // draw of a published collection with nothing to publish
    private final UiButton publishButton;
    private final UiButton pressedButton;
    private final UiButton testButton;
    private final PressedRing pressedRing = new PressedRing();
    @Nullable
    private PublishPopover popover;
    @Nullable
    private TestDrawDialog testDraw;

    // The import of a data pack over this collection: its dialog, and while it is on its way the
    // token that pauses the saver, when the server applied it and the sync it waits for
    @Nullable
    private PackImportDialog importDialog;
    @Nullable
    private Object importing;
    private long importLandedAt;
    // The revision of the World collections when the server applied the import: the sync after it
    // carries the imported collection
    private int importRevision;
    @Nullable
    private UiModal importingDialog;
    // The operator confirmed deleting the collection for good: its sync then closes the editor
    // quietly. The token holds the saver back meanwhile, which a refusal lets go again
    private boolean purging;
    @Nullable
    private Object purgeHold;
    // What the collection will take on the server, and what holds publishing back, worked out once
    // per change of the draft rather than on every frame or tick, and again at least every second
    @Nullable
    private CreatorDraft.Forecast forecast;
    @Nullable
    private PublishCheck check;
    private long checkedAt;

    // Whether this editor let go of the saver, which then goes on in the background
    private boolean detached;
    // What had the keyboard at the last tick, so typing in another field starts a new undo step
    @Nullable
    private GuiEventListener lastFocused;
    @Nullable
    private UiNode lastFocusedNode;
    // A screen of the editor's own is shown over it, which keeps the previews loaded
    private boolean childOpen;
    // A bulk add of figures hidden while its figures are sent, which the status line follows
    @Nullable
    private BulkAddScreen hiddenBulk;
    // Figures added while another screen was shown over the editor, selected once it is shown again
    @Nullable
    private List<CreatorDraft.FigureDraft> revealOnShow;
    // What changed, worked out once per change rather than per frame
    private List<String> changes = List.of();
    // Whether a limit of the collection or the server is nearly used up, with the numbers
    @Nullable
    private Component spaceLow;
    // Leaving once the published collection's changes are saved, as the leave dialog's Publish changes does
    private boolean leaveWhenPublished;
    // The name the sidebar's heading was built with
    private String shownName = "";

    /**
     * Opens the editor on a collection this client was sent, taking up the draft of its saver
     * when one is still at work.
     *
     * @param machinePos the claw or gacha machine the creator was opened from, or null
     * @throws IllegalStateException when the client has no such collection
     */
    public CreatorEditorScreen(Screen parent, String collectionId, @Nullable BlockPos machinePos, Tab tab) {
        super(tr("side.heading"), parent);
        if (ClientWorldCollections.record(collectionId) == null) {
            throw new IllegalStateException("No synced World collection " + collectionId);
        }
        this.collectionId = collectionId;
        this.machinePos = machinePos;
        this.saver = CreatorAutosave.attach(collectionId);
        this.draft = this.saver.draft();
        this.tab = tab;
        this.actions = new CollectionActions(this, machinePos, id -> {
        });
        this.publishButton = new UiButton(tr("publish.button"), UiButton.Style.PRIMARY, this::openPublish);
        // While the popover is open it takes every click, so this one's press never comes
        this.pressedButton = new UiButton(tr("publish.button"), UiButton.Style.SECONDARY, this::openPublish)
                .pixelLabel();
        this.testButton = new UiButton(tr("publish.test_pull"), UiButton.Style.SECONDARY, () -> openTestDraw(null));
        this.pages.put(Tab.FIGURES, new FiguresPage(this));
        this.pages.put(Tab.LOOK, new LookPage(this));
        this.pages.put(Tab.OPTIONS, new OptionsPage(this));
        refresh();
    }

    // For the pages

    CreatorDraft draft() {
        return draft;
    }

    @Nullable
    BlockPos machinePos() {
        return machinePos;
    }

    <T extends UiNode> T add(T node) {
        return addNode(node);
    }

    EditBox field(String name, int x, int y, int width, int maxLength, Component hint, String value,
                  Consumer<String> responder) {
        return textField(name, x, y, width, maxLength, hint, value, responder);
    }

    void rebuildPage() {
        rebuild();
    }

    /** The content panel's bounds, which a page's popovers stay inside. */
    UiNode.Rect contentPanel() {
        return this.content;
    }

    /** Gives a page's node that draws models its share of the screen's budget; call it while building. */
    <T extends SidebarScreen.Budgeted> T budgetNode(T node) {
        return budget(node);
    }

    /** Draws a page's title at the top of the content panel, as the options draw theirs. */
    void drawTitle(GuiGraphics graphics, Component title) {
        contentTitle(graphics, title, 0);
    }

    /**
     * Whether a change, named as {@link CreatorDraft#changes()} names it, waits to be published.
     * A draft collection's changes save themselves, so none is marked there.
     */
    boolean isChanged(String key) {
        return showsChangeDots() && changes.contains(key);
    }

    /** Whether the pages mark changes that wait: only a published or archived collection's do. */
    boolean showsChangeDots() {
        return !this.saver.isAuto();
    }

    /** Every change not on the server yet, named as {@link CreatorDraft#changes()} names them. */
    List<String> changes() {
        return changes;
    }

    /**
     * Why a unit of the collection's own, as {@link CreatorSave.Held#unitKey()} names it, is
     * held back, as the server refused it alone; null while it is not. The options mark their
     * rows with it.
     */
    @Nullable
    Component heldUnit(String unitKey) {
        for (CreatorSave.Held held : this.saver.held()) {
            if (held.reason() == CreatorSave.Reason.REFUSED && held.figureId() == null
                    && held.unitKey().equals(unitKey)) {
                return held.detail();
            }
        }
        return null;
    }

    /**
     * A page changed the draft: the previews show the change, and the marks and buttons follow
     * it. Once the editor has closed, as when a bulk add hidden behind it ends later, the draft
     * still changes but nothing is shown again, so the creator's preview stays cleared.
     */
    void changed() {
        if (this.detached) {
            return;
        }
        draft.preview(PREVIEW_SLOT);
        refresh();
    }

    /**
     * The operator moved on, to another figure or a part of the page, so what waits is saved now,
     * files included, as a tab switch does.
     */
    void navigated() {
        this.saver.flushNow();
    }

    /** Shows a screen of the editor's own over it, keeping its previews and its draft; what waits is saved first. */
    void openChild(Screen child) {
        this.saver.flushNow();
        this.childOpen = true;
        this.minecraft.setScreen(child);
    }

    /** Shows a tab: what waits is saved first, files included, as when the operator moves on. */
    void selectTab(Tab next) {
        if (next != this.tab) {
            page().hide();
            this.tab = next;
            this.saver.flushNow();
            rebuild();
        }
    }

    private EditorPage page() {
        return this.pages.get(this.tab);
    }

    /** The collection's saver, which the bulk add of figures pauses while it is open. */
    AutoSaver saver() {
        return this.saver;
    }

    // Building

    @Override
    protected void init() {
        this.childOpen = false;
        if (this.detached && !this.saver.isCancelled()) {
            // Shown again after it let go of the saver
            CreatorAutosave.reattach(this.saver);
            this.detached = false;
        }
        List<CreatorDraft.FigureDraft> reveal = this.revealOnShow;
        this.revealOnShow = null;
        if (reveal != null && this.tab != Tab.FIGURES) {
            // Figures were added from another tab, as by the options' import of skins: they show
            page().hide();
            this.tab = Tab.FIGURES;
        }
        // A screen shown over the editor, or its first showing, may have left the slot empty
        this.draft.preview(PREVIEW_SLOT);
        super.init();
        if (reveal != null && this.pages.get(Tab.FIGURES) instanceof FiguresPage figures) {
            figures.reveal(reveal);
        }
    }

    @Override
    protected void buildSidebar() {
        this.shownName = displayName();
        sidebarHeading(Component.literal(this.shownName), tr("side.back_tip"), this::onClose);
        headingGlyph(Theme.ICON_HELP, tr("side.help"), this::openHelp);

        // The tabs, one row each, where a list of the sidebar's starts
        UiNode.Rect list = sideList();
        int y = list.y();
        for (Tab each : Tab.values()) {
            UiSideRow row = this.tabRows.computeIfAbsent(each, this::tabRow);
            row.setSelected(each == this.tab);
            row.at(list.x(), y, list.width(), UiSideRow.HEIGHT);
            addNode(row);
            y += UiSideList.STEP;
        }
        refreshTabRows();

        // Publish at the foot, one of its three faces shown at a time
        sideFooter(this.publishButton);
        sideFooter(this.pressedButton);
        sideFooter(this.testButton);
        UiNode.Rect foot = this.publishButton.bounds();
        this.pressedRing.at(foot.x(), foot.y(), foot.width(), foot.height());
        addNode(this.pressedRing);
        refreshFooter();
    }

    // A tab's row: its icon and name, selected with the tab sound
    private UiSideRow tabRow(Tab which) {
        String[] icon = switch (which) {
            case FIGURES -> PixelIcon.LIST;
            case LOOK -> PixelIcon.PALETTE;
            case OPTIONS -> PixelIcon.GEAR;
        };
        String key = switch (which) {
            case FIGURES -> "side.tab.figures";
            case LOOK -> "side.tab.look";
            case OPTIONS -> "side.tab.options";
        };
        return new UiSideRow(which, tr(key)).glyph(icon).onSelect(() -> selectTab(which));
    }

    @Override
    protected void buildContent() {
        int top = this.content.y() + (this.tab == Tab.OPTIONS ? isShort() ? OPTIONS_TOP_SHORT : OPTIONS_TOP : PAGE_TOP);
        // Down to the bottom of the footer's buttons, which the content panel has none of
        int bottom = footerY() + UiButton.HEIGHT;
        page().build(new UiNode.Rect(left(), top, inner(), Math.max(0, bottom - top)));
        refresh();
    }

    /**
     * The collection's name as the editor's headings show it: the draft's; while it is blank, the
     * one last saved, so an emptied field still reads as this collection; "New collection" only for
     * one that never had a name.
     */
    String displayName() {
        if (!this.draft.name().isBlank()) {
            return this.draft.name();
        }
        String saved = this.draft.base().name();
        return saved == null || saved.isBlank() ? tr("home.new").getString() : saved;
    }

    /**
     * Brings the tab marks, the footer and what the buttons allow up to date with the draft, then
     * lets the page do the same with its own nodes.
     */
    private void refresh() {
        this.changes = this.draft.changes();
        invalidateChecks();
        this.spaceLow = spaceLowText();
        refreshTabRows();
        refreshFooter();
        page().draftChanged();
    }

    // The tabs' marks: a red dot for what cannot be saved, an amber one for changes not published,
    // or else the figures' count
    private void refreshTabRows() {
        for (Tab each : Tab.values()) {
            UiSideRow row = this.tabRows.get(each);
            if (row == null) {
                continue;
            }
            Mark mark = tabMark(each);
            if (mark != null) {
                row.dot(mark.colour(), mark.tooltip());
            } else if (each == Tab.FIGURES) {
                row.count(Component.literal(String.valueOf(this.draft.figures().size())));
            } else {
                row.noEnd();
            }
        }
    }

    /**
     * What the collection will take on the server as the draft is now, worked out once per change
     * and again at least every second, as the pages and the publish popover ask for it on every
     * frame or tick.
     */
    CreatorDraft.Forecast forecast() {
        ensureChecks();
        return this.forecast;
    }

    // The publish check of the draft as it is now, kept as the forecast is
    private PublishCheck check() {
        ensureChecks();
        return this.check;
    }

    private void ensureChecks() {
        long now = Util.getMillis();
        if (this.forecast == null || this.check == null || now - this.checkedAt > CHECK_MS) {
            CreatorDraft.Forecast next = this.draft.forecast();
            this.forecast = next;
            this.check = PublishCheck.of(this.draft, next);
            this.checkedAt = now;
        }
    }

    private void invalidateChecks() {
        this.forecast = null;
        this.check = null;
    }

    /** A dot on a tab's row: its colour and what its tooltip says. */
    private record Mark(int colour, Component tooltip) {
    }

    @Nullable
    private Mark tabMark(Tab which) {
        List<Component> reasons = new ArrayList<>();
        for (CreatorSave.Held held : this.saver.held()) {
            if (tabsOf(held).contains(which)) {
                reasons.add(held.detail());
            }
        }
        if (reasons.isEmpty()) {
            // A problem not held yet, as while the name is being typed, still marks its tab
            Component problem = switch (which) {
                case LOOK -> {
                    Component name = PublishCheck.nameProblem(this.draft);
                    yield name != null ? name : boxProblem();
                }
                case OPTIONS -> PublishCheck.authorProblem(this.draft);
                case FIGURES -> null;
            };
            if (problem != null) {
                reasons.add(problem);
            }
        }
        if (!reasons.isEmpty()) {
            return new Mark(Theme.RED, lines(reasons));
        }
        if (this.saver.isAuto()) {
            // A draft's changes save themselves: nothing waits to be marked
            return null;
        }
        int pending = 0;
        for (String key : this.changes) {
            if (tabOf(key) == which) {
                pending++;
            }
        }
        if (pending == 0) {
            return null;
        }
        return new Mark(Theme.AMBER, pending == 1 ? tr("autosave.unpublished_tip_one")
                : tr("autosave.unpublished_tip", pending));
    }

    /** The tab a change belongs to, by its key as {@link CreatorDraft#changes()} names it. */
    static Tab tabOf(String key) {
        if (key.startsWith(CreatorDraft.FIGURE) || key.equals(CreatorDraft.ORDER)) {
            return Tab.FIGURES;
        }
        return switch (key) {
            case CreatorDraft.NAME, CreatorDraft.COLOUR, CreatorDraft.BOX, CreatorDraft.LOGO,
                    CreatorDraft.LOGO_LAYOUT -> Tab.LOOK;
            // The author and the link are edited from the options' Author row
            default -> Tab.OPTIONS;
        };
    }

    // The tab where a unit held back is fixed: the first of those it marks
    private Tab tabOf(CreatorSave.Held held) {
        return tabsOf(held).iterator().next();
    }

    /**
     * The tabs that hold what a unit held back is made of. The name and the box are the look's,
     * the author and its link the options'. The collection's own units bundle fields of both tabs
     * (the identity holds the name, the box and the author; the extras the logo's place, edited on
     * the look, and the link, the plush flag and the rarities' weights, on the options), so a
     * refusal of one marks the tabs of its fields that wait, the look first.
     */
    private Set<Tab> tabsOf(CreatorSave.Held held) {
        if (held.figureId() != null) {
            return EnumSet.of(Tab.FIGURES);
        }
        return switch (held.reason()) {
            case NAME, BOX -> EnumSet.of(Tab.LOOK);
            case AUTHOR, LINK, TOO_BIG -> EnumSet.of(Tab.OPTIONS);
            default -> switch (held.unitKey()) {
                case "identity" -> {
                    Set<Tab> tabs = EnumSet.noneOf(Tab.class);
                    if (!this.changes.contains(CreatorDraft.AUTHOR) || this.changes.contains(CreatorDraft.NAME)
                            || this.changes.contains(CreatorDraft.BOX) || this.changes.contains(CreatorDraft.COLOUR)
                            || this.changes.contains(CreatorDraft.LOGO)) {
                        tabs.add(Tab.LOOK);
                    }
                    if (this.changes.contains(CreatorDraft.AUTHOR)) {
                        tabs.add(Tab.OPTIONS);
                    }
                    yield tabs;
                }
                case "order" -> EnumSet.of(Tab.FIGURES);
                case "extras" -> {
                    Set<Tab> tabs = EnumSet.noneOf(Tab.class);
                    if (this.changes.contains(CreatorDraft.LOGO_LAYOUT)) {
                        tabs.add(Tab.LOOK);
                    }
                    if (this.changes.contains(CreatorDraft.AUTHOR_URL) || this.changes.contains(CreatorDraft.PLUSH)
                            || this.changes.contains(CreatorDraft.RARITY_WEIGHTS) || tabs.isEmpty()) {
                        tabs.add(Tab.OPTIONS);
                    }
                    yield tabs;
                }
                default -> EnumSet.of(Tab.OPTIONS);
            };
        };
    }

    /**
     * Why the author or its link holds the collection's own changes back, for the options' Author
     * row: an author or a link that cannot be saved, or the server's refusal of a unit while the
     * author or the link waits in it; null while nothing of them is held.
     */
    @Nullable
    Component authorHeld() {
        for (CreatorSave.Held held : this.saver.held()) {
            if (held.figureId() != null) {
                continue;
            }
            boolean refusedAuthor = held.reason() == CreatorSave.Reason.REFUSED
                    && (held.unitKey().equals("identity") && this.changes.contains(CreatorDraft.AUTHOR)
                    || held.unitKey().equals("extras") && this.changes.contains(CreatorDraft.AUTHOR_URL));
            boolean invalid = held.reason() == CreatorSave.Reason.AUTHOR || held.reason() == CreatorSave.Reason.LINK;
            if (invalid || refusedAuthor) {
                return held.detail();
            }
        }
        return PublishCheck.authorProblem(this.draft);
    }

    // Lines joined into one tooltip, each a line of its own
    private static Component lines(List<Component> lines) {
        Component all = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            all = Component.empty().append(all).append(i > 0 ? "\n" : "").append(lines.get(i));
        }
        return all;
    }

    /**
     * Which face of Publish the sidebar's foot shows: pressed while its popover is open, the test
     * draw on a published collection with nothing to publish, else a draft's "Publish" or a
     * published collection's "Publish changes", the words the popover and the leave dialog use,
     * drawn in Minecraft's narrower font where the pixel font has no room for them.
     */
    private void refreshFooter() {
        boolean hold = !this.saver.isAuto();
        boolean open = this.popover != null && this.popover.isOpen();
        boolean test = hold && this.changes.isEmpty() && !open;
        Component label = tr(hold ? "publish.button_changes" : "publish.button");
        this.publishButton.setLabel(label);
        int room = this.publishButton.bounds().width();
        boolean plain = room > 0 && this.publishButton.pixelWidth() > room;
        this.publishButton.setPlainLabel(plain);
        this.pressedButton.setLabel(label);
        this.pressedButton.setPlainLabel(plain);
        this.publishButton.setVisible(!open && !test);
        this.pressedButton.setVisible(open);
        this.pressedRing.setVisible(open);
        this.testButton.setVisible(test);
    }

    /**
     * Whether the sidebar's foot is the screen's green action now: a draft's Publish, or a
     * published collection's Publish changes while it has changes. A page then draws its own main
     * button in grey, so there is one green on the screen.
     */
    boolean footerIsPrimary() {
        return this.saver.isAuto() || !this.changes.isEmpty();
    }

    @Override
    protected boolean toastOverFooter() {
        // The content panel has no buttons on its footer line
        return true;
    }

    // The collection's rules

    /**
     * Why the name, author, link or box cannot be saved as they are, or null when they can; the
     * look's mark takes the name's and the box's, the options' the author's and the link's.
     */
    @Nullable
    Component identityProblem() {
        return PublishCheck.identityProblem(this.draft);
    }

    /** Why the box cannot be saved as chosen, or null when it can. */
    @Nullable
    Component boxProblem() {
        return PublishCheck.boxProblem(this.draft);
    }

    /**
     * What holds publishing back now, in the publish popover's order: a save that failed, the
     * blockers of the check ({@link PublishCheck}) and the units the server refused or that lost a
     * file. A draft's figures that cannot be saved yet are only left out, as a figure missing a
     * file always was; on a published or archived collection every held unit holds the whole
     * change back, while its figures are the server's to judge.
     */
    List<Blocker> publishBlockers() {
        List<Blocker> found = new ArrayList<>();
        boolean hold = !this.saver.isAuto();
        if (this.saver.hasFailed()) {
            found.add(new Blocker(this.saver.failures().get(0),
                    () -> SaveDetailsDialog.open(this, this.saver.status())));
        }
        for (PublishCheck.Item item : check().items()) {
            PublishCheck.Fix fix = item.fix();
            if (!item.blocks() || hold && fix != null && fix.place() != PublishCheck.Place.IDENTITY
                    && fix.place() != PublishCheck.Place.AUTHOR) {
                continue;
            }
            found.add(new Blocker(item.text(), () -> goTo(fix)));
        }
        for (CreatorSave.Held held : this.saver.held()) {
            boolean shownAbove = switch (held.reason()) {
                // The check names these already
                case NAME, BOX, AUTHOR, LINK, TOO_BIG -> true;
                case SKIN, FIGURE -> !hold;
                default -> false;
            };
            if (!shownAbove) {
                found.add(new Blocker(held.detail(), () -> revealHeld(held)));
            }
        }
        return found;
    }

    // Takes the operator to where an item of the check is fixed; one with no place is a limit,
    // which the options show
    private void goTo(@Nullable PublishCheck.Fix fix) {
        if (fix == null) {
            selectTab(Tab.OPTIONS);
            return;
        }
        switch (fix.place()) {
            case IDENTITY -> selectTab(Tab.LOOK);
            case AUTHOR -> openAuthor();
            case FIGURES -> selectTab(Tab.FIGURES);
            case FIGURE -> {
                if (fix.figure() != null) {
                    selectTab(Tab.FIGURES);
                    revealFigures(List.of(fix.figure()));
                }
            }
        }
    }

    /**
     * Why the collection cannot be published now, or null when it can: a publish already on its
     * way, a published collection's save on its way or nothing to publish, or the first blocker.
     */
    @Nullable
    Component publishProblem() {
        return publishProblem(null);
    }

    /**
     * Why the collection cannot be published now, as {@link #publishProblem()}, given the
     * blockers when the caller has just listed them; null lists them here.
     */
    @Nullable
    Component publishProblem(@Nullable List<Blocker> listed) {
        if (this.saver.isPublishing()) {
            return tr("editor.saving");
        }
        if (!this.saver.isAuto()) {
            if (this.saver.hasFlight() || this.saver.isBusy()) {
                return tr("editor.saving");
            }
            if (this.changes.isEmpty()) {
                return tr("editor.nothing_to_save");
            }
        }
        List<Blocker> blockers = listed != null ? listed : publishBlockers();
        return blockers.isEmpty() ? null : blockers.get(0).text();
    }

    /** Why a published or archived collection's changes cannot be sent now, or null; always null for a draft. */
    @Nullable
    Component publishChangesProblem() {
        return this.saver.isAuto() ? null : publishProblem();
    }

    /**
     * Whether a save of the editor's is on its way or waits for the sync that shows it, while
     * the figures it puts must keep their ids.
     */
    boolean isSaving() {
        return this.saver.hasFlight() || this.saver.isBusy();
    }

    /** Whether the save on its way puts or changes the figure, whose id then holds still. */
    boolean isSaving(String figureId) {
        return this.saver.isSaving(figureId);
    }

    /**
     * A new figure's name changed while its put was on its way, so its id held still; it follows
     * the name again once that save is over.
     */
    void renamedWhileSaving(String figureId) {
        this.saver.renamedWhileSaving(figureId);
    }

    // Undo

    /**
     * Takes back the last change, whatever is on its way; the next save sends the step back.
     * Only while the editor is open: its undo steps are forgotten once it closes.
     */
    void undo() {
        if (this.detached) {
            return;
        }
        if (this.draft.undo()) {
            this.draft.preview(PREVIEW_SLOT);
            refresh();
            rebuild();
        }
    }

    /**
     * Says that something was taken out or changed for good, in a toast whose "Undo" takes it
     * back while this editor is the screen shown; from any other screen, where the toast may
     * still show, it does nothing.
     */
    void undoToast(Component text) {
        toast(text, tr("home.undo"), () -> {
            if (this.minecraft.screen == this) {
                undo();
            }
        });
    }

    // The bulk add of figures

    /** Why one figure cannot be sent on its own now, as the bulk add sends its figures, or null when it can. */
    @Nullable
    Component figureSendProblem() {
        if (this.saver.hasFlight() || this.saver.isBusy() || this.saver.bulkRun() != null) {
            return tr("editor.saving");
        }
        return null;
    }

    /**
     * Opens the bulk add of figures over the editor with no files yet, as the options' import of
     * a folder of skins does; what waits is saved first.
     */
    void openBulkAdd() {
        if (this.draft.isFull()) {
            status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        status(Component.empty());
        openChild(new BulkAddScreen(this, List.of()));
    }

    /**
     * Sends figures just added to the draft, such as a bulk add's, and nothing else, in batches
     * of {@link CreatorSave#BULK_FIGURES_PER_BATCH} figures, while the draft's other changes wait.
     * Ask {@link #figureSendProblem()} first.
     *
     * @param listener told how it ended before the editor takes the outcome, so it must not
     *                 change the screen then; answers whether it showed a refusal itself, which
     *                 the editor then does not repeat in a toast
     * @return the save, which may already be finished when it could not be handed over, or
     *         null when none of the figures had anything to send
     */
    @Nullable
    CreatorSave.Run saveFigures(List<String> figureIds, Component message,
                                Predicate<CreatorSave.Outcome> listener) {
        CreatorSave.Plan plan = CreatorSave.planFigures(this.draft, figureIds, CreatorSave.BULK_FIGURES_PER_BATCH);
        if (plan.isEmpty()) {
            return null;
        }
        status(Component.empty());
        return this.saver.saveFigures(figureIds, outcome -> bulkSaved(outcome, message, listener));
    }

    private void bulkSaved(CreatorSave.Outcome outcome, Component message, Predicate<CreatorSave.Outcome> listener) {
        boolean shown = this.minecraft.screen == this;
        // The screen shown over the editor hears first, and may show a refusal itself
        boolean reported = false;
        try {
            reported = listener.test(outcome);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.error("A World collection save listener failed", e);
        }
        if (outcome.ok()) {
            UiToast.show(message);
        } else if (shown) {
            status(outcome.message(), Theme.RED);
        } else if (outcome.ending() != CreatorSave.Ending.CANCELLED && !reported) {
            UiToast.show(outcome.message());
        }
        if (shown) {
            refresh();
        }
    }

    /** Follows a bulk add hidden while its figures are sent, or none once it is over. */
    void bulkHidden(@Nullable BulkAddScreen bulk) {
        this.hiddenBulk = bulk;
    }

    /**
     * Selects figures just added on the figures' tab and scrolls to the first. From another tab,
     * or while a screen of the editor's own is shown over it, the figures' tab shows them once
     * the editor is shown again.
     */
    void revealFigures(List<CreatorDraft.FigureDraft> figures) {
        if (this.tab != Tab.FIGURES && this.minecraft.screen != this) {
            this.revealOnShow = List.copyOf(figures);
            return;
        }
        selectTab(Tab.FIGURES);
        if (this.pages.get(Tab.FIGURES) instanceof FiguresPage figuresPage) {
            figuresPage.reveal(figures);
        }
    }

    /**
     * Takes the operator to a unit held back: its figure, the author's dialog for the author or
     * its link, or the tab where the collection's own is fixed.
     */
    void revealHeld(CreatorSave.Held held) {
        CreatorDraft.FigureDraft figure = held.figureId() != null ? this.draft.figure(held.figureId()) : null;
        if (figure != null) {
            revealFigures(List.of(figure));
        } else if (held.reason() == CreatorSave.Reason.AUTHOR || held.reason() == CreatorSave.Reason.LINK) {
            openAuthor();
        } else {
            selectTab(tabOf(held));
        }
    }

    /** Shows the options and opens the author's dialog over them, as the options' Author row does. */
    void openAuthor() {
        selectTab(Tab.OPTIONS);
        AuthorDialog.open(this);
    }

    // Publishing

    /**
     * Opens the publish popover over the sidebar's foot, which draws Publish pressed while it is
     * open; a draft's changes waiting are saved first. A published collection with nothing to
     * publish opens its test draw instead.
     */
    void openPublish() {
        if (this.popover != null && this.popover.isOpen()) {
            return;
        }
        if (!this.saver.isAuto() && this.changes.isEmpty()) {
            openTestDraw(null);
            return;
        }
        if (this.saver.isAuto()) {
            this.saver.flushNow();
        }
        this.popover = new PublishPopover(this, () -> this.publishButton.bounds());
        // Lined up with the sidebar's edge, as it belongs to the sidebar's foot
        this.popover.popover().leftEdge(this.side.x());
        openPopover(this.popover.popover());
        refreshFooter();
    }

    /** The publish popover closed, whatever closed it: Publish rests again. */
    void publishClosed() {
        refreshFooter();
    }

    /**
     * Publishes the draft collection: its saver saves what waits first, files included, checks it
     * once more and publishes it with the changes made meanwhile.
     *
     * @param done told how it ended: null once published, else what held it back or the refusal
     */
    void publish(Consumer<Component> done) {
        Component problem = publishProblem();
        if (problem != null) {
            done.accept(problem);
            return;
        }
        this.saver.publish(current -> {
            // Checked afresh, whatever the cache holds: this decides what goes to the players
            invalidateChecks();
            List<Blocker> blockers = publishBlockers();
            return blockers.isEmpty() ? null : blockers.get(0).text();
        }, failure -> {
            if (this.minecraft.screen == this) {
                refresh();
            }
            done.accept(failure);
        });
        refresh();
    }

    /**
     * Sends a published or archived collection's changes, all of them, to the players at once.
     *
     * @param thenLeave goes back to the collections once they are saved, as the leave dialog's
     *                  Publish changes does
     * @param listener  told how it ended, null once saved, in place of the editor's own toast and
     *                  status line; null for those
     */
    void publishChanges(boolean thenLeave, @Nullable Consumer<Component> listener) {
        Component problem = publishChangesProblem();
        if (problem != null) {
            if (listener != null) {
                listener.accept(problem);
            } else {
                status(problem, Theme.RED);
            }
            return;
        }
        String name = this.draft.name();
        this.leaveWhenPublished = thenLeave;
        status(Component.empty());
        this.saver.publishChanges(failure -> {
            boolean shown = this.minecraft.screen == this;
            boolean leaving = this.leaveWhenPublished;
            this.leaveWhenPublished = false;
            if (listener != null) {
                listener.accept(failure);
            } else if (failure == null) {
                UiToast.show(tr("publish.changes_done", name));
            } else if (shown && isModalOpen()) {
                // The dialog that waited for it says why, and leaves the choice to the operator again
                confirmLeave(failure);
            } else if (shown) {
                status(failure, Theme.RED);
            } else {
                UiToast.show(failure);
            }
            if (failure == null && leaving && shown) {
                leaveEditor();
                return;
            }
            if (shown) {
                refresh();
            }
        });
        refresh();
    }

    /**
     * Says the collection was just published: a draft's first publish puts it in the machines,
     * with a link that puts it in the machine the creator was opened from, and a published
     * collection's changes reach the players.
     */
    void published(boolean changesOnly, String name) {
        if (changesOnly) {
            UiToast.show(tr("publish.changes_done", name));
            return;
        }
        BlockPos machine = this.machinePos;
        if (machine != null) {
            String id = this.collectionId;
            UiToast.show(tr("publish.done_toast"), tr("home.menu.put"), () -> CollectionActions.put(machine, id, name));
        } else {
            UiToast.show(tr("publish.done_toast"));
        }
    }

    /** Stays once the changes the leave dialog publishes are saved, as its "Keep editing" asks. */
    void keepEditing() {
        this.leaveWhenPublished = false;
    }

    /**
     * Opens the test draw over the editor: the machine's screen as players will see the
     * collection, where a draw is played on this computer alone, and the odds behind it.
     *
     * @param returnTo what its "Back" opens again, such as the publish popover it came from; null
     *                 for a dialog that only closes
     */
    void openTestDraw(@Nullable Runnable returnTo) {
        this.testDraw = TestDrawDialog.open(this, returnTo);
    }

    /** The test draw closed: the screen behind it draws its models again. */
    void testDrawClosed(TestDrawDialog closed) {
        if (this.testDraw == closed) {
            this.testDraw = null;
        }
        coverModels(false);
    }

    // The options' actions on the whole collection

    /** The collection as the server has it, or null once it is gone. */
    @Nullable
    WorldCollectionRecord record() {
        return ClientWorldCollections.record(this.collectionId);
    }

    /**
     * Why archiving or restoring the collection waits now: a save or another run of it on its
     * way, which it would race; null when it can go.
     */
    @Nullable
    Component archiveProblem() {
        return this.saver.hasFlight() || this.saver.isPublishing() || this.saver.isBusy() ? tr("autosave.saving_tip")
                : null;
    }

    /**
     * Asks before archiving the published collection, saying its changes not published yet stay
     * in the editor; the toast after it offers to undo it.
     */
    void confirmArchive() {
        WorldCollectionRecord record = record();
        if (record == null) {
            return;
        }
        int pending = this.changes.size();
        Component note = pending == 0 ? null : pending == 1 ? tr("options.archive_pending_one")
                : tr("options.archive_pending", pending);
        this.actions.confirmArchive(record, note);
    }

    /** Brings the archived collection back into the machines. */
    void restore() {
        WorldCollectionRecord record = record();
        if (record != null) {
            this.actions.setArchived(record, false);
        }
    }

    /**
     * Why deleting the collection waits now, or null when it can go: a bulk add of figures on its
     * way, whose later batches would follow the deletion.
     */
    @Nullable
    Component purgeProblem() {
        return this.saver.bulkRun() != null ? tr("autosave.saving_tip") : null;
    }

    /**
     * Asks before deleting the collection for good, the operator typing its name. Once confirmed,
     * its saver is held back first, so nothing of its own follows the deletion: the batches of a
     * save on its way that have not gone yet are dropped, and nothing new starts. Once the
     * collection is gone the saver ends, and the editor closes without a word of its own, the
     * home saying it was deleted. Refused, or not sent at all, the saver goes on with every change
     * it still holds, a draft's unsent edits or a published collection's changes, as before.
     */
    void confirmPurge() {
        WorldCollectionRecord record = record();
        if (record == null) {
            return;
        }
        this.actions.confirmPurge(record, () -> {
            this.purging = true;
            Object hold = new Object();
            this.purgeHold = hold;
            this.saver.suspend(hold);
        }, refusal -> {
            this.purging = false;
            Object hold = this.purgeHold;
            this.purgeHold = null;
            if (hold != null) {
                this.saver.resume(hold);
            }
            if (!refusal.getString().isEmpty()) {
                UiToast.show(refusal);
            }
        });
    }

    /**
     * Imports a data pack over the collection. A draft's changes waiting are saved first, and the
     * import waits for them; a published collection's changes not published yet are discarded by
     * it, which the dialog says. While it is on its way the saver waits and a dialog says it is
     * importing; once the server applied it and its sync arrived, the draft is the imported
     * collection, its undo steps forgotten. A refusal says why.
     */
    void openImport() {
        WorldCollectionRecord record = record();
        if (record == null) {
            return;
        }
        if (this.saver.isAuto()) {
            this.saver.flushNow();
        }
        this.importDialog = PackImportDialog.forUpdate(this, record, id -> {
        }, new PackImportDialog.UpdateHooks() {
            @Override
            public int pendingChanges() {
                return saver.isAuto() ? 0 : changes.size();
            }

            @Override
            public void sending() {
                importStarted();
            }

            @Override
            public void landed(@Nullable Component refusal) {
                importLanded(refusal);
            }
        });
        this.importDialog.open();
    }

    // The import went: the saver waits, and nothing is edited until it has landed. A published
    // collection's changes not published yet stay until the imported collection's sync arrives,
    // which takes their place (finishImport), so a refused import, or one that never went, leaves
    // them as they were
    private void importStarted() {
        Object token = new Object();
        this.importing = token;
        this.importLandedAt = 0;
        this.saver.pause(token);
        showImporting();
    }

    // The dialog that says the import is on its way, which cannot be closed meanwhile; it opens
    // again whenever another dialog took its place
    private void showImporting() {
        UiModal dialog = new UiModal(tr("home.import_title"), tr("options.importing"))
                .accent(DialogFrame.Accent.CREATE, PixelIcon.PLUS).blocking();
        this.importingDialog = dialog;
        openModal(dialog);
    }

    private void importLanded(@Nullable Component refusal) {
        if (this.importing == null) {
            return;
        }
        if (refusal == null) {
            // Applied: the draft takes it from the sync it causes, which comes next
            this.importLandedAt = Util.getMillis();
            this.importRevision = ClientWorldCollections.revision();
            return;
        }
        endImport();
        if (!refusal.getString().isEmpty() && this.minecraft.screen == this) {
            UiModal dialog = new UiModal(tr("home.import_title"), refusal)
                    .accent(DialogFrame.Accent.DANGER, PixelIcon.UPLOAD);
            dialog.cancelButton(tr("done"));
            openModal(dialog);
        } else if (!refusal.getString().isEmpty()) {
            UiToast.show(refusal);
        }
    }

    // The imported collection arrived, or its sync did not within the wait: the draft is it now
    private void finishImport() {
        this.saver.syncNow();
        this.draft.forgetSent();
        this.draft.discardPending();
        this.draft.clearUndo();
        endImport();
        this.draft.preview(PREVIEW_SLOT);
        refresh();
        if (this.minecraft.screen == this) {
            rebuild();
        }
    }

    private void endImport() {
        Object token = this.importing;
        this.importing = null;
        this.importLandedAt = 0;
        if (token != null) {
            this.saver.resume(token);
        }
        UiModal dialog = this.importingDialog;
        this.importingDialog = null;
        if (dialog != null) {
            dialog.close();
        }
    }

    // The status line

    /**
     * The save state as the status slot shows it: a short label in a colour, the whole sentence
     * in its tooltip, a click that acts on it, and a bar for work under way. No connection, a
     * refusal and changes that cannot be saved are urgent, so a passing line never covers them.
     */
    @Override
    protected SideStatus sideStatus() {
        return saveStatus();
    }

    SideStatus saveStatus() {
        AutoSaver.Status status = this.saver.status();
        if (this.importing != null && status.state() != AutoSaver.State.OFFLINE) {
            // The saver waits for the import, which is what goes on
            return SideStatus.of(tr("options.importing"), Theme.MUTED);
        }
        return switch (status.state()) {
            case OFFLINE -> SideStatus.of(tr("autosave.offline"), Theme.RED)
                    .withTooltip(tr("autosave.offline_tip")).asUrgent();
            case FAILED -> SideStatus.of(tr("autosave.failed"), Theme.RED)
                    .withTooltip(status.failures().get(0))
                    .withAction(() -> SaveDetailsDialog.open(this, this.saver.status())).asUrgent();
            case HELD -> heldStatus(status.held());
            case BULK -> bulkStatus();
            case UPLOADING -> uploadStatus(status.upload());
            case SAVING -> SideStatus.of(tr("autosave.saving"), Theme.MUTED);
            case BUSY -> {
                long seconds = Math.max(1, (status.waitMillis() + 999) / 1000);
                yield SideStatus.of(tr("autosave.busy", seconds), Theme.AMBER)
                        .withTooltip(tr("autosave.busy_tip", seconds));
            }
            case QUEUED -> SideStatus.of(tr("autosave.queued"), Theme.AMBER).withTooltip(tr("autosave.queued_tip"));
            default -> quietStatus(status);
        };
    }

    // The states under the space running low, which takes their place while it does
    private SideStatus quietStatus(AutoSaver.Status status) {
        Component space = this.spaceLow;
        if (space != null) {
            return SideStatus.of(tr("autosave.space_low"), Theme.AMBER).withTooltip(space)
                    .withAction(() -> selectTab(Tab.OPTIONS));
        }
        return switch (status.state()) {
            case FILES_WAITING -> SideStatus.of(tr("autosave.files_wait"), Theme.FAINT)
                    .withTooltip(tr("autosave.files_wait_tip")).withAction(this.saver::flushNow);
            case PENDING -> SideStatus.of(tr("autosave.pending"), Theme.FAINT).withAction(this.saver::flushNow);
            case PUBLISHED -> SideStatus.of(tr("autosave.published"), Theme.FAINT);
            case ARCHIVED -> SideStatus.of(tr("autosave.archived"), Theme.FAINT);
            case UNPUBLISHED -> {
                int count = status.pending();
                Component tip = count == 1 ? tr("autosave.unpublished_tip_one") : tr("autosave.unpublished_tip", count);
                if (this.draft.archived()) {
                    tip = Component.empty().append(tip).append(" · ").append(tr("autosave.archived"));
                }
                yield SideStatus.of(count == 1 ? tr("autosave.unpublished_one") : tr("autosave.unpublished", count),
                        Theme.AMBER).withTooltip(tip).withAction(this::openPublish);
            }
            default -> SideStatus.of(tr("autosave.saved"), Theme.FAINT);
        };
    }

    // "⚠ Skin missing", "⚠ 3 not saved"... with every reason in the tooltip; a click goes to the first
    private SideStatus heldStatus(List<CreatorSave.Held> held) {
        List<CreatorSave.Held> local = new ArrayList<>();
        for (CreatorSave.Held unit : held) {
            if (unit.reason() != CreatorSave.Reason.REFUSED) {
                local.add(unit);
            }
        }
        CreatorSave.Held first = local.get(0);
        Component label;
        int colour = Theme.AMBER;
        if (local.size() > 1) {
            label = tr("autosave.held_many", local.size());
        } else {
            label = switch (first.reason()) {
                case NAME -> tr("autosave.held.name");
                case AUTHOR -> tr("autosave.held.author");
                case BOX -> tr("autosave.held.box");
                case LINK -> tr("autosave.held.link");
                case SKIN -> tr("autosave.held.skin");
                case TOO_BIG -> tr("autosave.too_big");
                default -> tr("autosave.held_many", 1);
            };
            if (first.reason() == CreatorSave.Reason.TOO_BIG) {
                colour = Theme.RED;
            }
        }
        List<Component> reasons = new ArrayList<>();
        for (CreatorSave.Held unit : local) {
            reasons.add(unit.detail());
        }
        return SideStatus.of(label, colour).withTooltip(lines(reasons)).withAction(() -> revealHeld(first)).asUrgent();
    }

    // "Adding 12/40" with a bar; a click shows the bulk add again
    private SideStatus bulkStatus() {
        BulkAddScreen bulk = this.hiddenBulk;
        CreatorSave.Run run = this.saver.bulkRun();
        if (bulk != null) {
            return SideStatus.of(tr("autosave.bulk", bulk.sentCount(), bulk.sendingCount()), Theme.MUTED)
                    .withTooltip(bulk.progressLine()).withProgress(bulk.sentFraction())
                    .withAction(() -> openChild(bulk));
        }
        CreatorSave.Progress progress = run != null ? run.progress() : null;
        int done = progress != null ? progress.batch() : 0;
        int total = progress != null ? progress.batches() : 0;
        return SideStatus.of(tr("autosave.bulk", done, total), Theme.MUTED)
                .withProgress(total > 0 ? done / (float) total : 0.0f);
    }

    // "Uploading 40 %" with a bar, the file in the tooltip
    private SideStatus uploadStatus(@Nullable ClientWorldUploads.Progress upload) {
        if (upload == null || upload.total() <= 0) {
            return SideStatus.of(tr("autosave.saving"), Theme.MUTED);
        }
        int percent = (int) (100L * upload.sent() / upload.total());
        return SideStatus.of(tr("autosave.uploading", percent), Theme.MUTED)
                .withTooltip(tr("autosave.uploading_tip", upload.file(), percent)).withProgress(percent / 100.0f);
    }

    /**
     * The numbers behind "Almost out of space" while a limit is at 85% or more: the collection's
     * files, its definition, or the server's space, orphaned files included as the sync counts
     * them; null while none is.
     */
    @Nullable
    private Component spaceLowText() {
        CreatorDraft.Forecast forecast = forecast();
        boolean files = forecast.collectionBytes() >= SPACE_LOW * WorldContentLimits.MAX_COLLECTION_BYTES;
        boolean definition = forecast.recordBytes() >= SPACE_LOW * WorldContentLimits.RECORD_BYTES;
        int quotaMax = ClientWorldCollections.quotaMaxKiB();
        boolean server = quotaMax > 0 && ClientWorldCollections.quotaUsedKiB() >= SPACE_LOW * quotaMax;
        if (!files && !definition && !server) {
            return null;
        }
        Component all = Component.empty();
        String separator = "";
        if (files) {
            int usedKiB = (int) Math.min(Integer.MAX_VALUE, (forecast.collectionBytes() + 1023) / 1024);
            all = Component.empty().append(all).append(tr("publish.check.files", UiQuotaMeter.text(usedKiB,
                    (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024))));
            separator = " · ";
        }
        if (definition) {
            all = Component.empty().append(all).append(separator).append(tr("publish.check.size",
                    (forecast.recordBytes() + 1023) / 1024, WorldContentLimits.RECORD_BYTES / 1024));
            separator = " · ";
        }
        if (server) {
            all = Component.empty().append(all).append(separator).append(CollectionActions.quotaText());
        }
        return all;
    }

    // Syncs and ticking

    @Override
    protected void onSync() {
        this.saver.syncNow();
        WorldCollectionRecord record = record();
        if (record == null) {
            if (this.purging) {
                // Deleted for good from here: nothing of its saver's is heard after it
                CreatorAutosave.cancel(this.collectionId);
            } else {
                // Deleted for good elsewhere, or a draft no longer sent to a player who is not an operator now
                UiToast.show(tr("editor.gone", this.draft.name()));
            }
            leaveEditor();
            return;
        }
        if (this.importing != null && this.importLandedAt != 0
                && ClientWorldCollections.revision() != this.importRevision) {
            // The first sync after the server applied the import carries the imported collection
            finishImport();
        }
        this.draft.preview(PREVIEW_SLOT);
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.screen != this) {
            return;
        }
        if (this.saver.isCancelled() && !this.purging && record() != null) {
            // The saver ended for a deletion the server refused: the collection opens again as it is
            this.minecraft.setScreen(new CreatorEditorScreen(this.parent, this.collectionId, this.machinePos,
                    this.tab));
            return;
        }
        page().tick();
        trackFocus();
        if (!this.shownName.equals(displayName()) && !(getFocused() instanceof EditBox)) {
            // Renamed: the heading follows once the name is no longer being typed, so the
            // rebuild does not move the field's cursor
            rebuild();
        }
        this.saver.setTyping(this.tab == Tab.FIGURES && page() instanceof FiguresPage figures
                ? figures.typingFigureId() : null);
        // What the saver is doing changes the marks without a change of the draft
        refreshTabRows();
        refreshFooter();
        tickDialogs();
    }

    // Typing into another field, or into the same one after a pause, undoes on its own, and what
    // was typed into the field left is saved at once. A press gives the keyboard to the node it
    // lands on; while it lasts, as through a drag that is one undo step from its start, the move
    // is left to be noticed after the release
    private void trackFocus() {
        if (nodePressed()) {
            return;
        }
        GuiEventListener focused = getFocused();
        UiNode focusedNode = focusedNode();
        if (focused != this.lastFocused || focusedNode != this.lastFocusedNode) {
            boolean typed = this.lastFocused instanceof EditBox || this.lastFocusedNode != null;
            this.lastFocused = focused;
            this.lastFocusedNode = focusedNode;
            this.draft.checkpoint();
            if (typed) {
                this.saver.textLeft();
            }
        }
    }

    // The dialogs that follow the saver or wait for something
    private void tickDialogs() {
        TestDrawDialog shownDraw = this.testDraw;
        if (shownDraw != null && !shownDraw.isOpen()) {
            // Closed by a dialog opened over it
            shownDraw.closed();
        }
        PackImportDialog shownImport = this.importDialog;
        if (shownImport != null) {
            if (shownImport.isOpen()) {
                shownImport.recheck();
            } else if (this.importing == null) {
                this.importDialog = null;
            }
        }
        if (this.importing != null) {
            if (this.importLandedAt != 0 && Util.getMillis() - this.importLandedAt >= CreatorRuns.SYNC_WAIT_MS) {
                finishImport();
            } else if (!isModalOpen()) {
                // Nothing else is edited until the import has landed
                showImporting();
            }
        }
    }

    /** A slider, pad or stepper was let go: the number it set is saved sooner. */
    @Override
    protected void nodeReleased(UiNode node) {
        this.saver.numberReleased();
    }

    // Drawing

    @Override
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        page().drawContents(graphics, mouseX, mouseY, partialTick);
    }

    // Input

    /**
     * Ctrl+Z undoes, Ctrl+S saves a draft now or opens a published collection's Publish, Ctrl+1,
     * 2 and 3 pick the tabs, and Escape lets the page go back a step before the editor leaves;
     * then the page's own shortcuts.
     */
    @Override
    protected boolean shortcut(UiInput.Key key) {
        if (key.ctrl() && key.is(InputConstants.KEY_Z)) {
            undo();
            return true;
        }
        if (key.ctrl() && key.is(InputConstants.KEY_S)) {
            if (this.saver.isAuto()) {
                this.saver.flushNow();
            } else {
                openPublish();
            }
            return true;
        }
        if (key.ctrl() && (key.is(InputConstants.KEY_1) || key.is(InputConstants.KEY_2)
                || key.is(InputConstants.KEY_3))) {
            selectTab(Tab.values()[key.key() - InputConstants.KEY_1]);
            return true;
        }
        if (key.escape()) {
            // Unused, it reaches the nodes, which may clear a selection, and then leaves
            return page().escape();
        }
        return page().shortcut(key);
    }

    /**
     * Escape in a text field only takes the keyboard from it; the next one leaves. Other keys are
     * the page's to take first, such as Tab moving on from the look's name to its tiles.
     */
    @Override
    protected boolean fieldKey(EditBox field, UiInput.Key key) {
        if (key.escape()) {
            setFocused(null);
            return true;
        }
        return page().fieldKey(field, key);
    }

    /**
     * The editor's help: how saving works here in one paragraph, a draft's or a published
     * collection's, then its keys, one a row.
     */
    private void openHelp() {
        Component body = tr(this.saver.isAuto() ? "editor.help_body_auto" : "editor.help_body_hold");
        UiModal dialog = new UiModal(tr("editor.help_title"), body).width(HELP_WIDTH)
                .accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
        KeyList keys = new KeyList(tr("editor.help_keys"));
        dialog.node(keys, keys.height());
        dialog.cancelButton(tr("home.help_close"));
        openModal(dialog);
    }

    /**
     * The help's keys, one a row: the key on the left in the text's colour, what it does on the
     * right in grey, from lines of "key, a tab, what it does".
     */
    private static final class KeyList extends UiNode.Box {
        private static final int COLUMN_GAP = 10;
        private final List<String[]> rows = new ArrayList<>();

        KeyList(Component text) {
            for (String line : text.getString().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                this.rows.add(tab < 0 ? new String[] {line.strip(), ""}
                        : new String[] {line.substring(0, tab).strip(), line.substring(tab + 1).strip()});
            }
        }

        int height() {
            return this.rows.size() * Theme.LINE_HEIGHT;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int keyWidth = 0;
            for (String[] row : this.rows) {
                keyWidth = Math.max(keyWidth, font.width(row[0]));
            }
            // The keys keep at most two fifths of the row
            keyWidth = Math.min(keyWidth, width * 2 / 5);
            int top = y;
            for (String[] row : this.rows) {
                Draw.text(graphics, font, row[0], x, top, keyWidth, Theme.TEXT);
                Draw.text(graphics, font, row[1], x + keyWidth + COLUMN_GAP, top,
                        Math.max(0, width - keyWidth - COLUMN_GAP), Theme.MUTED);
                top += Theme.LINE_HEIGHT;
            }
        }
    }

    // Leaving

    /**
     * Leaving a draft collection saves what waits and goes at once, the saver finishing in the
     * background, unless some changes cannot be saved as they are: then it asks whether to stay or
     * to leave without them. Leaving a published or archived collection with changes asks whether
     * to publish them, discard them or stay.
     */
    @Override
    public void onClose() {
        if (this.saver.isAuto()) {
            this.saver.flushNow();
            List<CreatorSave.Held> held = this.saver.held();
            if (held.isEmpty()) {
                leaveEditor();
                return;
            }
            SaveDetailsDialog.openLeave(this, held, () -> {
                this.saver.revertHeld();
                leaveEditor();
            });
            return;
        }
        if (this.changes.isEmpty()) {
            leaveEditor();
            return;
        }
        confirmLeave(null);
    }

    /**
     * Asks before leaving a published or archived collection with changes: publish them and
     * leave, discard them and leave, or keep editing.
     *
     * @param refusal why the last try to publish them failed, shown under the question; null for none
     */
    private void confirmLeave(@Nullable Component refusal) {
        SaveDetailsDialog.openLeaveUnpublished(this, this.changes.size(), refusal, publishChangesProblem(),
                () -> publishChanges(true, null), () -> {
                    this.saver.discard();
                    leaveEditor();
                });
    }

    private void leaveEditor() {
        if (this.childOpen && this.minecraft.screen != this) {
            // A sync heard through a screen shown over the editor: the editor is never removed
            // itself then, so it closes first, before the next screen shows its own previews
            this.childOpen = false;
            closePages();
        }
        leave();
    }

    @Override
    public void removed() {
        super.removed();
        if (this.childOpen) {
            // The editor's own screen is shown over it, and the editor comes back after it
            return;
        }
        closePages();
    }

    // Lets go of what the editor showed, and of the saver, which goes on in the background
    private void closePages() {
        TestDrawDialog shownDraw = this.testDraw;
        if (shownDraw != null) {
            shownDraw.close();
        }
        for (EditorPage editorPage : this.pages.values()) {
            editorPage.close();
        }
        CreatorPreview.clearAll();
        // Files only the editor used are freed; the ones a synced collection names stay
        ClientPreviewAssets.release();
        if (!this.detached) {
            this.detached = true;
            CreatorAutosave.detach(this.saver);
        }
    }

    /**
     * A saver left in the background could not save everything, because the server refused it or
     * some changes cannot be saved as they are: a toast says how many changes wait, with a link
     * that opens the collection again on the draft that holds them.
     */
    private static void backgroundFailed(AutoSaver saver) {
        String name = saver.draft().name().isBlank() ? saver.draft().slug() : saver.draft().name();
        int count = Math.max(1, saver.draft().changes().size());
        Component text = count == 1 ? tr("autosave.background_failed_one", name)
                : tr("autosave.background_failed", name, count);
        String id = saver.collectionId();
        UiToast.show(text, tr("show.open"), () -> reopen(id));
    }

    // Opens a collection's editor over the creator screen shown, as the toast's link does
    private static void reopen(String collectionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ClientWorldCollections.record(collectionId) == null) {
            return;
        }
        Screen shown = minecraft.screen;
        if (shown instanceof CreatorEditorScreen editor && editor.collectionId.equals(collectionId)) {
            return;
        }
        BlockPos machine = shown instanceof CreatorHomeScreen home ? home.machinePos() : null;
        Screen parent = shown instanceof CreatorScreen ? shown : new CreatorHomeScreen(shown, null);
        minecraft.setScreen(new CreatorEditorScreen(parent, collectionId, machine, Tab.FIGURES));
    }

    /**
     * Publish's ring while its popover is open: the main action's green around the button drawn
     * in the grey face, so the popover's own button is the screen's one green and Publish never
     * looks disabled.
     */
    private static final class PressedRing extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (visible() && width > 2) {
                Draw.outline(graphics, x + 1, y + 1, width - 2, height - 2 - Surfaces.LIP_HEIGHT, PRESSED_RING);
            }
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            // A picture: the button under it keeps its clicks
            return false;
        }
    }
}
