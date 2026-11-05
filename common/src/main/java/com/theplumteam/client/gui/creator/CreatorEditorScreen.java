package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiTabs;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.worldcontent.AutoSaver;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorAutosave;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.registry.ModSounds;
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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The collection editor: one World collection's working copy ({@link CreatorDraft}) shown on
 * three tabs, "Identity & box", "Figures" and "Review & publish", each an {@link EditorPage}.
 *
 * <p>The draft belongs to the collection's saver ({@link AutoSaver}), which outlives the editor
 * until nothing of it waits: a draft collection's changes save themselves a moment after they
 * settle, and its files once the operator moves to another tab or figure, opens a screen of the
 * editor's own, presses Ctrl+S or leaves; a published or archived collection keeps its changes
 * until "Publish changes" sends them all, so players never see half of a change. Opening a
 * collection whose saver is still at work takes up the same draft.
 *
 * <p>The top bar leads back to the collections and names the collection with its state
 * (published, draft or archived); "Undo" (Ctrl+Z) steps back through the draft's own snapshots,
 * even while a save is on its way, and the next save sends the step back. Its one main action is
 * a draft's "Review and publish", which opens the last tab, where a draft is published once its
 * last changes are saved, or a published collection's "Publish changes" (Ctrl+S), which sends its
 * changes; on the last tab, whose own button is the green one, it is grey. A "Next steps" line under the
 * tabs, which can be folded away, says what the collection still needs. The status line at the
 * bottom says how the saving stands: saved, saving soon, uploading, files waiting for the operator
 * to move on, changes that cannot be saved as they are (a click shows the first), a refusal (a
 * click gives the details and a retry), or a published collection's changes not published yet.
 * Leaving with changes that cannot be saved, or with a published collection's changes, asks first.
 *
 * <p>Every change shows at once in the live previews, which draw the draft through the
 * creator's preview slot {@link #PREVIEW_SLOT}, unsent files included. A sync from the server
 * takes the newer collection for every field the operator did not touch
 * ({@link CreatorDraft#rebase}); when the collection is no longer sent at all, because it was
 * deleted or the operator is no longer one, the editor closes and says so. The server checks
 * every edit again; the editor only asks.
 */
public class CreatorEditorScreen extends CreatorScreen {
    /** The editor's tabs, in their order. */
    public enum Tab {
        IDENTITY, FIGURES, REVIEW
    }

    /** The preview slot the editor's pages draw the draft through. */
    static final int PREVIEW_SLOT = 1;
    // The mockup's 1280 by 800 frame, at half its pixel size as every creator size is
    private static final int MAX_PANEL_WIDTH = 640;
    private static final int MAX_PANEL_HEIGHT = 400;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int STEPS_HEIGHT = 15;
    private static final int CRUMB = 0xFF9A9A9A;
    private static final int SHORT_SCREEN = 400;
    // The room the collection's name and badge need after the way back, at least, and the most of
    // a long name's width the bar gives way for
    private static final int MIN_CRUMB = 140;
    private static final int MAX_CRUMB_NAME = 110;
    // The least the buttons leave the crumb, squeezing their labels to do so
    private static final int MIN_SQUEEZED_CRUMB = 60;
    // How long a line set by status() covers the save state, unless the state is urgent
    private static final long MESSAGE_MS = UiToast.LIFETIME_MS;
    // The share of a limit from which the status says the space runs low
    private static final float SPACE_LOW = 0.85f;
    private static final float MIB = 1024.0f * 1024.0f;

    // Folded or not for the rest of the session, as the operator left it
    private static boolean stepsFolded;

    static {
        // A saver left in the background that cannot finish says so, and offers to open its collection
        CreatorAutosave.onBackgroundFailure(CreatorEditorScreen::backgroundFailed);
    }

    private final String collectionId;
    @Nullable
    private final BlockPos machinePos;
    private final AutoSaver saver;
    private final CreatorDraft draft;
    private final Map<Tab, EditorPage> pages = new EnumMap<>(Tab.class);
    private Tab tab;

    // Nodes kept across rebuilds
    private final UiButton backButton;
    private final UiButton undoButton;
    private final UiButton publishChangesButton;
    private final UiButton reviewButton;
    private final UiButton stepsButton;
    private final UiTabs tabs;
    private final SaveLine saveLine = new SaveLine();

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
    // What changed, worked out once per change rather than per frame
    private List<String> changes = List.of();
    // Whether a limit of the collection or the server is nearly used up, with the numbers
    @Nullable
    private Component spaceLow;
    // The line status() set last, and when
    private Component message = Component.empty();
    private int messageColour = Theme.MUTED;
    private long messageAt;
    // Leaving once the published collection's changes are saved, as the leave dialog's Publish changes does
    private boolean leaveWhenPublished;

    // Where build() put what drawContents() draws
    private int barY;
    private int crumbX;
    private int crumbRight;
    private int stepsY = -1;

    /**
     * Opens the editor on a collection this client was sent, taking up the draft of its saver
     * when one is still at work.
     *
     * @param machinePos the claw or gacha machine the creator was opened from, or null
     * @throws IllegalStateException when the client has no such collection
     */
    public CreatorEditorScreen(Screen parent, String collectionId, @Nullable BlockPos machinePos, Tab tab) {
        super(tr("editor.title"), parent);
        if (ClientWorldCollections.record(collectionId) == null) {
            throw new IllegalStateException("No synced World collection " + collectionId);
        }
        this.collectionId = collectionId;
        this.machinePos = machinePos;
        this.saver = CreatorAutosave.attach(collectionId);
        this.draft = this.saver.draft();
        this.tab = tab;
        this.backButton = new UiButton(tr("editor.back"), UiButton.Style.LINK, this::onClose);
        this.undoButton = new UiButton(tr("editor.undo"), UiButton.Style.SECONDARY, this::undo);
        this.undoButton.setTooltip(tr("editor.undo_tip"));
        this.publishChangesButton = new UiButton(tr("publish.button_changes"), UiButton.Style.GREEN,
                () -> publishChanges(false));
        this.reviewButton = new UiButton(tr("editor.review"), UiButton.Style.PRIMARY, () -> selectTab(Tab.REVIEW));
        this.stepsButton = new UiButton(tr("editor.steps"), UiButton.Style.LINK, () -> {
            stepsFolded = !stepsFolded;
            rebuild();
        });
        this.tabs = new UiTabs(UiTabs.Style.FLAT, index -> selectTab(Tab.values()[index]));
        this.pages.put(Tab.IDENTITY, new IdentityPage(this));
        this.pages.put(Tab.FIGURES, new FiguresPage(this));
        this.pages.put(Tab.REVIEW, new PublishPage(this));
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
     * A page changed the draft: the previews show the change, and the counts and buttons follow
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
        // A screen shown over the editor, or its first showing, may have left the slot empty
        this.draft.preview(PREVIEW_SLOT);
        super.init();
    }

    @Override
    protected void build() {
        fitPanel();
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        this.barY = this.panelY + 8;

        // Top bar: the way back on the left, the actions on the right; a draft has no button to
        // save, since it saves itself
        this.backButton.at(left, this.barY + 6, this.font.width(this.backButton.label()), 10);
        addNode(this.backButton);
        this.crumbX = this.backButton.bounds().right() + 4;
        // The bar's main action: a draft's way to the review, or a published collection's
        // Publish changes, which takes the review's place there, so the bar never holds two green
        // buttons. On the review its footer button is the one green action, and the bar's is
        // grey. It is in the pixel font while that leaves the crumb its room, and in Minecraft's
        // narrower font on a narrow panel; a published collection's then says only "Publish".
        boolean hold = !this.saver.isAuto();
        UiButton main = hold ? this.publishChangesButton : this.reviewButton;
        this.publishChangesButton.setVisible(hold);
        this.reviewButton.setVisible(!hold);
        this.publishChangesButton.setLabel(tr("publish.button_changes"));
        List<UiButton.Style> styles = this.tab == Tab.REVIEW ? List.of(UiButton.Style.SECONDARY)
                : List.of(UiButton.Style.PRIMARY, UiButton.Style.GREEN);
        int need = crumbNeed();
        int x = 0;
        for (UiButton.Style style : styles) {
            main.setStyle(style);
            x = place(main, right, 0);
            x = place(this.undoButton, x, 4);
            if (x - 8 - this.crumbX >= need) {
                break;
            }
        }
        if (hold && x - 8 - this.crumbX < need) {
            this.publishChangesButton.setLabel(tr("publish.button"));
            x = place(main, right, 0);
            x = place(this.undoButton, x, 4);
        }
        if (x - 8 - this.crumbX < MIN_SQUEEZED_CRUMB) {
            // Not even that leaves the name a little room, as in a long language on a narrow
            // panel: the buttons give it up in proportion and their labels shrink
            int[] widths = UiButton.fitRow(right - this.crumbX - 8 - MIN_SQUEEZED_CRUMB - 4,
                    buttonWidth(this.font, main), buttonWidth(this.font, this.undoButton));
            x = place(main, right, 0, widths[0]);
            x = place(this.undoButton, x, 4, widths[1]);
        }
        this.crumbRight = x - 8;
        addNode(this.undoButton);
        addNode(this.publishChangesButton);
        addNode(this.reviewButton);

        // Tabs, with the fold of the next steps on their right
        int tabsY = this.barY + UiButton.HEIGHT + 6;
        refreshTabs();
        this.tabs.select(this.tab.ordinal());
        this.tabs.at(left, tabsY, Math.min(this.tabs.contentWidth(), right - left), UiTabs.FLAT_HEIGHT);
        addNode(this.tabs);
        this.stepsButton.setLabel(tr(stepsFolded ? "editor.steps_folded" : "editor.steps"));
        int stepsWidth = this.font.width(this.stepsButton.label());
        this.stepsButton.at(right - stepsWidth, tabsY + 5, stepsWidth, 10);
        this.stepsButton.setVisible(this.tabs.bounds().right() + 8 <= right - stepsWidth);
        addNode(this.stepsButton);
        int top = tabsY + UiTabs.FLAT_HEIGHT + GAP;
        if (stepsFolded && this.stepsButton.visible()) {
            this.stepsY = -1;
        } else {
            this.stepsY = top;
            top += STEPS_HEIGHT + 4;
        }

        UiNode.Rect body = new UiNode.Rect(left, top, right - left, Math.max(0, statusY() - 4 - top));
        page().build(body);
        // The save state on the status row, under the page
        this.saveLine.at(left, statusY() - 2, right - left, 13);
        addNode(this.saveLine);
        refresh();
    }

    /**
     * The room the crumb wants after the way back: the collection's name, whole up to a point,
     * and its state badge after it, which the bar's buttons give way for before the name is cut.
     */
    private int crumbNeed() {
        Component badge = this.draft.archived() ? tr("card.badge.archived")
                : this.draft.isDraftCollection() ? tr("card.badge.draft") : tr("card.badge.published");
        String name = this.draft.name().isBlank() ? tr("home.new").getString() : this.draft.name();
        int nameWidth = Math.min(MAX_CRUMB_NAME, PixelFont.width(Component.literal(name), PixelFont.crispScale(1.0f)));
        return Math.max(MIN_CRUMB, this.font.width("/") + 4 + nameWidth + 6 + Draw.badgeWidth(this.font, badge) + 6);
    }

    // Places a button left of a point, as wide as its label needs; returns its left edge
    private int place(UiButton button, int rightEdge, int gap) {
        return place(button, rightEdge, gap, buttonWidth(this.font, button));
    }

    private int place(UiButton button, int rightEdge, int gap, int width) {
        int buttonX = rightEdge - gap - width;
        button.at(buttonX, this.barY, width, UiButton.HEIGHT);
        return buttonX;
    }

    /**
     * The width a button needs for its label: a main action's label is in the pixel font, which
     * is wider than Minecraft's, so it is measured in that font.
     */
    static int buttonWidth(Font font, UiButton button) {
        return button.naturalWidth();
    }

    @Override
    protected int panelMargin() {
        // A window up to 768 pixels tall, at the menus' GUI scale of 2, needs the room for the sections
        return this.height < SHORT_SCREEN ? Theme.SPACE_S : super.panelMargin();
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

    private void refreshTabs() {
        List<UiTabs.Tab> list = new ArrayList<>();
        list.add(new UiTabs.Tab(tr("editor.tab.identity"), ItemStack.EMPTY, -1, identityProblem() == null));
        list.add(new UiTabs.Tab(tr("editor.tab.figures"), ItemStack.EMPTY, this.draft.figures().size(), false));
        list.add(new UiTabs.Tab(tr("editor.tab.review"), ItemStack.EMPTY, -1, !this.draft.isDraftCollection()));
        this.tabs.setTabs(list);
        this.tabs.select(this.tab.ordinal());
    }

    /**
     * Brings the counts, the tab marks and what the buttons allow up to date with the draft,
     * then lets the page do the same with its own nodes.
     */
    private void refresh() {
        this.changes = this.draft.changes();
        // Undo stays at hand while a save is on its way: the next save sends the step back
        this.undoButton.disable(this.draft.canUndo() ? null : tr("editor.nothing_to_undo"));
        this.publishChangesButton.disable(publishChangesProblem());
        this.spaceLow = spaceLowText();
        refreshTabs();
        page().draftChanged();
    }

    // The collection's rules

    /** Why the name, author, link or box cannot be saved as they are, or null when they can. */
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
     * Why a draft collection cannot be published now, beyond its checklist, or null when it can: a
     * publish already on its way, a save that failed and waits for a retry, a name, author, link or
     * box that cannot be saved, a collection over a cap or budget, or a change the server refused.
     * A figure that cannot be saved as it is is only left out, as a figure missing a file always was.
     */
    @Nullable
    Component publishProblem() {
        if (this.saver.isPublishing()) {
            return tr("editor.saving");
        }
        if (this.saver.hasFailed()) {
            // The saves before it failed: Retry in the status line's details sends them again
            return this.saver.failures().get(0);
        }
        return heldBlocker();
    }

    /**
     * Why a published or archived collection's changes cannot be sent now, or null when they can:
     * a save already on its way or awaiting its sync, nothing to send, or a change that cannot be
     * saved as it is, which holds the whole change back.
     */
    @Nullable
    Component publishChangesProblem() {
        if (this.saver.isAuto()) {
            return null;
        }
        if (this.saver.isPublishing() || this.saver.hasFlight() || this.saver.isBusy()) {
            return tr("editor.saving");
        }
        if (this.changes.isEmpty()) {
            return tr("editor.nothing_to_save");
        }
        Component identity = identityProblem();
        if (identity != null) {
            return identity;
        }
        List<CreatorSave.Held> held = this.saver.held();
        return held.isEmpty() ? null : held.get(0).detail();
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

    // Undo and saving

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

    /** Why one figure cannot be sent on its own now, as the bulk add sends its figures, or null when it can. */
    @Nullable
    Component figureSendProblem() {
        if (this.saver.hasFlight() || this.saver.isBusy() || this.saver.bulkRun() != null) {
            return tr("editor.saving");
        }
        return null;
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

    /**
     * Publishes the draft collection: its saver saves what waits first, files included, checks it
     * once more and publishes it with the changes made meanwhile. The review then offers what
     * comes next; a refusal shows in the status line.
     */
    void publish() {
        Component problem = publishProblem();
        if (problem != null) {
            status(problem, Theme.RED);
            return;
        }
        String name = this.draft.name();
        this.saver.publish(current -> {
            if (PublishCheck.of(current).blocked()) {
                return tr("publish.blocked");
            }
            return heldBlocker();
        }, failure -> published(failure, name));
        refresh();
    }

    // What of the identity or the held changes holds a publish back; a held figure is only left out
    @Nullable
    private Component heldBlocker() {
        Component identity = identityProblem();
        if (identity != null) {
            return identity;
        }
        for (CreatorSave.Held held : this.saver.held()) {
            if (held.reason() != CreatorSave.Reason.SKIN && held.reason() != CreatorSave.Reason.FIGURE) {
                return held.detail();
            }
        }
        return null;
    }

    private void published(@Nullable Component failure, String name) {
        boolean shown = this.minecraft.screen == this;
        if (failure != null) {
            if (shown) {
                status(failure, Theme.RED);
                refresh();
            } else {
                UiToast.show(failure);
            }
            return;
        }
        if (shown && this.tab == Tab.REVIEW && this.pages.get(Tab.REVIEW) instanceof PublishPage publishPage) {
            // A dialog says it is published and offers what comes next
            publishPage.published(name);
        } else {
            UiToast.show(tr("editor.published", name));
        }
        if (shown) {
            refresh();
        }
    }

    /**
     * Sends a published or archived collection's changes, all of them, to the players at once.
     *
     * @param thenLeave goes back to the collections once they are saved, as the leave dialog's
     *                  Publish changes does
     */
    void publishChanges(boolean thenLeave) {
        Component problem = publishChangesProblem();
        if (problem != null) {
            status(problem, Theme.RED);
            return;
        }
        String name = this.draft.name();
        this.leaveWhenPublished = thenLeave;
        status(Component.empty());
        this.saver.publishChanges(failure -> {
            boolean shown = this.minecraft.screen == this;
            boolean leaving = this.leaveWhenPublished;
            this.leaveWhenPublished = false;
            if (failure == null) {
                UiToast.show(tr("publish.changes_done", name));
                if (leaving && shown) {
                    leaveEditor();
                    return;
                }
            } else if (shown) {
                if (isModalOpen()) {
                    // The dialog that waited for it says why, and leaves the choice to the operator again
                    confirmLeave(failure);
                } else {
                    status(failure, Theme.RED);
                }
            } else {
                UiToast.show(failure);
            }
            if (shown) {
                refresh();
            }
        });
        refresh();
    }

    /** Stays once the changes the leave dialog publishes are saved, as its "Keep editing" asks. */
    void keepEditing() {
        this.leaveWhenPublished = false;
    }

    /** Selects figures just added in the figure grid and scrolls to the first. */
    void revealFigures(List<CreatorDraft.FigureDraft> figures) {
        if (this.pages.get(Tab.FIGURES) instanceof FiguresPage figuresPage) {
            figuresPage.reveal(figures);
        }
    }

    /** Takes the operator to a unit held back: its figure, or the identity tab for the collection's own. */
    void revealHeld(CreatorSave.Held held) {
        CreatorDraft.FigureDraft figure = held.figureId() != null ? this.draft.figure(held.figureId()) : null;
        if (figure != null) {
            selectTab(Tab.FIGURES);
            revealFigures(List.of(figure));
        } else if (held.reason() == CreatorSave.Reason.TOO_BIG) {
            selectTab(Tab.REVIEW);
        } else {
            selectTab(Tab.IDENTITY);
        }
    }

    /** Follows a bulk add hidden while its figures are sent, or none once it is over. */
    void bulkHidden(@Nullable BulkAddScreen bulk) {
        this.hiddenBulk = bulk;
    }

    // Syncs and ticking

    @Override
    protected void onSync() {
        this.saver.syncNow();
        WorldCollectionRecord record = ClientWorldCollections.record(this.collectionId);
        if (record == null) {
            // Deleted for good, or a draft no longer sent to a player who is not an operator now
            UiToast.show(tr("editor.gone", this.draft.name()));
            leaveEditor();
            return;
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
        page().tick();
        GuiEventListener focused = getFocused();
        UiNode focusedNode = focusedNode();
        if (focused != this.lastFocused || focusedNode != this.lastFocusedNode) {
            // What is typed into another field, or into the same one after a pause, undoes on its
            // own, and what was typed into the field left is saved at once
            boolean typed = this.lastFocused instanceof EditBox || this.lastFocusedNode != null;
            this.lastFocused = focused;
            this.lastFocusedNode = focusedNode;
            this.draft.checkpoint();
            if (typed) {
                this.saver.textLeft();
            }
        }
        this.saver.setTyping(this.tab == Tab.FIGURES && page() instanceof FiguresPage figures
                ? figures.typingFigureId() : null);
        this.publishChangesButton.disable(publishChangesProblem());
    }

    /** A slider, pad or stepper was let go: the number it set is saved sooner. */
    @Override
    protected void nodeReleased(UiNode node) {
        this.saver.numberReleased();
    }

    // The status line

    @Override
    protected int statusY() {
        return this.panelY + this.panelHeight - 13;
    }

    /** A line set with status() covers the save state on the status row for as long as a toast would show. */
    @Override
    protected void statusSink(Component text, int colour) {
        this.message = text;
        this.messageColour = colour;
        this.messageAt = Util.getMillis();
    }

    @Override
    protected boolean hasStatus() {
        return liveMessage() != null;
    }

    @Nullable
    private Component liveMessage() {
        if (this.message.getString().isEmpty()) {
            return null;
        }
        if (Util.getMillis() - this.messageAt >= MESSAGE_MS) {
            this.message = Component.empty();
            return null;
        }
        return this.message;
    }

    /**
     * The save state as the status line shows it: a short label in a colour, the whole sentence
     * in its tooltip, a click that acts on it, and a bar for work under way. No connection, a
     * refusal and changes that cannot be saved are urgent, so a passing line never covers them.
     */
    SidebarScreen.SideStatus saveStatus() {
        AutoSaver.Status status = this.saver.status();
        return switch (status.state()) {
            case OFFLINE -> SidebarScreen.SideStatus.of(tr("autosave.offline"), Theme.RED)
                    .withTooltip(tr("autosave.offline_tip")).asUrgent();
            case FAILED -> SidebarScreen.SideStatus.of(tr("autosave.failed"), Theme.RED)
                    .withTooltip(status.failures().get(0))
                    .withAction(() -> SaveDetailsDialog.open(this, this.saver.status())).asUrgent();
            case HELD -> heldStatus(status.held());
            case BULK -> bulkStatus();
            case UPLOADING -> uploadStatus(status.upload());
            case SAVING -> SidebarScreen.SideStatus.of(tr("autosave.saving"), Theme.MUTED);
            case BUSY -> {
                long seconds = Math.max(1, (status.waitMillis() + 999) / 1000);
                yield SidebarScreen.SideStatus.of(tr("autosave.busy", seconds), Theme.AMBER)
                        .withTooltip(tr("autosave.busy_tip", seconds));
            }
            case QUEUED -> SidebarScreen.SideStatus.of(tr("autosave.queued"), Theme.AMBER)
                    .withTooltip(tr("autosave.queued_tip"));
            default -> quietStatus(status);
        };
    }

    // The states under the space running low, which takes their place while it does
    private SidebarScreen.SideStatus quietStatus(AutoSaver.Status status) {
        Component space = this.spaceLow;
        if (space != null) {
            return SidebarScreen.SideStatus.of(tr("autosave.space_low"), Theme.AMBER).withTooltip(space)
                    .withAction(() -> selectTab(Tab.REVIEW));
        }
        return switch (status.state()) {
            case FILES_WAITING -> SidebarScreen.SideStatus.of(tr("autosave.files_wait"), Theme.FAINT)
                    .withTooltip(tr("autosave.files_wait_tip")).withAction(this.saver::flushNow);
            case PENDING -> SidebarScreen.SideStatus.of(tr("autosave.pending"), Theme.FAINT)
                    .withAction(this.saver::flushNow);
            case PUBLISHED -> SidebarScreen.SideStatus.of(tr("autosave.published"), Theme.FAINT);
            case ARCHIVED -> SidebarScreen.SideStatus.of(tr("autosave.archived"), Theme.FAINT);
            case UNPUBLISHED -> {
                int count = status.pending();
                Component tip = count == 1 ? tr("autosave.unpublished_tip_one") : tr("autosave.unpublished_tip", count);
                if (this.draft.archived()) {
                    tip = Component.empty().append(tip).append(" · ").append(tr("autosave.archived"));
                }
                yield SidebarScreen.SideStatus.of(count == 1 ? tr("autosave.unpublished_one")
                                : tr("autosave.unpublished", count), Theme.AMBER).withTooltip(tip)
                        .withAction(() -> selectTab(Tab.REVIEW));
            }
            default -> SidebarScreen.SideStatus.of(tr("autosave.saved"), Theme.FAINT);
        };
    }

    // "⚠ Skin missing", "⚠ 3 not saved"... with every reason in the tooltip; a click goes to the first
    private SidebarScreen.SideStatus heldStatus(List<CreatorSave.Held> held) {
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
        // Each reason on a line of its own
        Component tip = Component.empty();
        for (int i = 0; i < local.size(); i++) {
            tip = Component.empty().append(tip).append(i > 0 ? "\n" : "").append(local.get(i).detail());
        }
        return SidebarScreen.SideStatus.of(label, colour).withTooltip(tip)
                .withAction(() -> revealHeld(first)).asUrgent();
    }

    // "Adding 12/40" with a bar; a click shows the bulk add again
    private SidebarScreen.SideStatus bulkStatus() {
        BulkAddScreen bulk = this.hiddenBulk;
        CreatorSave.Run run = this.saver.bulkRun();
        if (bulk != null) {
            return SidebarScreen.SideStatus.of(tr("autosave.bulk", bulk.sentCount(), bulk.sendingCount()), Theme.MUTED)
                    .withTooltip(bulk.progressLine()).withProgress(bulk.sentFraction())
                    .withAction(() -> openChild(bulk));
        }
        CreatorSave.Progress progress = run != null ? run.progress() : null;
        int done = progress != null ? progress.batch() : 0;
        int total = progress != null ? progress.batches() : 0;
        return SidebarScreen.SideStatus.of(tr("autosave.bulk", done, total), Theme.MUTED)
                .withProgress(total > 0 ? done / (float) total : 0.0f);
    }

    // "Uploading 40 %" with a bar, the file in the tooltip
    private SidebarScreen.SideStatus uploadStatus(@Nullable ClientWorldUploads.Progress upload) {
        if (upload == null || upload.total() <= 0) {
            return SidebarScreen.SideStatus.of(tr("autosave.saving"), Theme.MUTED);
        }
        int percent = (int) (100L * upload.sent() / upload.total());
        return SidebarScreen.SideStatus.of(tr("autosave.uploading", percent), Theme.MUTED)
                .withTooltip(tr("autosave.uploading_tip", upload.file(), percent)).withProgress(percent / 100.0f);
    }

    /**
     * The numbers behind "Almost out of space" while a limit is at 85% or more: the collection's
     * files, its definition, or the server's space, orphaned files included as the sync counts
     * them; null while none is.
     */
    @Nullable
    private Component spaceLowText() {
        CreatorDraft.Forecast forecast = this.draft.forecast();
        boolean files = forecast.collectionBytes() >= SPACE_LOW * WorldContentLimits.MAX_COLLECTION_BYTES;
        boolean record = forecast.recordBytes() >= SPACE_LOW * WorldContentLimits.RECORD_BYTES;
        int quotaMax = ClientWorldCollections.quotaMaxKiB();
        boolean server = quotaMax > 0 && ClientWorldCollections.quotaUsedKiB() >= SPACE_LOW * quotaMax;
        if (!files && !record && !server) {
            return null;
        }
        MutableLines lines = new MutableLines();
        if (files) {
            int usedKiB = (int) Math.min(Integer.MAX_VALUE, (forecast.collectionBytes() + 1023) / 1024);
            lines.add(tr("publish.check.files", UiQuotaMeter.text(usedKiB,
                    (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024))));
        }
        if (record) {
            lines.add(tr("publish.check.size", (forecast.recordBytes() + 1023) / 1024,
                    WorldContentLimits.RECORD_BYTES / 1024));
        }
        if (server) {
            lines.add(CollectionActions.quotaText());
        }
        return lines.joined();
    }

    // Lines joined into one tooltip text, a sentence each
    private static final class MutableLines {
        private final List<Component> lines = new ArrayList<>();

        void add(Component line) {
            this.lines.add(line);
        }

        Component joined() {
            Component all = Component.empty();
            for (int i = 0; i < this.lines.size(); i++) {
                if (i > 0) {
                    all = Component.empty().append(all).append(" · ");
                }
                all = Component.empty().append(all).append(this.lines.get(i));
            }
            return all;
        }
    }

    // Drawing

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawCrumb(graphics, mouseX, mouseY);
        if (this.stepsY >= 0) {
            drawSteps(graphics);
        }
        page().drawContents(graphics, mouseX, mouseY, partialTick);
        drawDropHint(graphics);
    }

    /**
     * On the status row's right, on a page with drop zones and while no message takes the row,
     * that files can be dropped on the window, where the save state leaves the room.
     */
    private void drawDropHint(GuiGraphics graphics) {
        int right = this.panelX + this.panelWidth - PAD;
        int left = this.saveLine.textRight() + 16;
        if (!hasStatus() && page().takesFiles()) {
            String hint = Draw.cut(this.font, tr("editor.drop_hint").getString(), Math.max(0, right - left));
            if (this.font.width(hint) >= 60) {
                Draw.string(graphics, this.font, hint, right - this.font.width(hint), statusY(), Theme.FAINT);
            }
        }
    }

    /** After the way back: "/ name", the name in the menus' pixel font as a title, then the state badge, as room allows. */
    private void drawCrumb(GuiGraphics graphics, int mouseX, int mouseY) {
        int textY = this.barY + 6;
        int x = this.crumbX;
        Draw.string(graphics, this.font, "/", x, textY, CRUMB);
        x += this.font.width("/") + 4;

        Component badge;
        Component badgeTip;
        int background;
        int foreground;
        if (this.draft.archived()) {
            badge = tr("card.badge.archived");
            badgeTip = tr("card.badge.archived_tip");
            background = Theme.BADGE_ARCHIVED_BG;
            foreground = Theme.BADGE_ARCHIVED_FG;
        } else if (this.draft.isDraftCollection()) {
            badge = tr("editor.badge.draft");
            badgeTip = tr("card.badge.draft_tip");
            background = Theme.BADGE_DRAFT_BG;
            foreground = Theme.BADGE_DRAFT_FG;
        } else {
            badge = tr("card.badge.published");
            badgeTip = null;
            background = Theme.BADGE_PUBLISHED_BG;
            foreground = Theme.BADGE_PUBLISHED_FG;
        }
        float titleScale = PixelFont.crispScale(1.0f);
        if (this.draft.isDraftCollection() && !this.draft.archived()
                && PixelFont.width(Component.literal(this.draft.name()), titleScale) + Draw.badgeWidth(this.font, badge)
                + 14 > this.crumbRight - x) {
            // A narrow bar says only DRAFT, with the rest in its tooltip
            badge = tr("card.badge.draft");
        }
        int badgeWidth = Draw.badgeWidth(this.font, badge) + 6;
        int nameRoom = this.crumbRight - x - badgeWidth;
        boolean withBadge = nameRoom >= 40;
        if (!withBadge) {
            nameRoom = this.crumbRight - x;
        }
        String name = this.draft.name().isBlank() ? tr("home.new").getString() : this.draft.name();
        x += Draw.heading(graphics, Component.literal(name), x, this.barY + UiButton.HEIGHT / 2.0f,
                Math.max(0, nameRoom - 6), 1.0f, Theme.TEXT) + 6;
        if (withBadge) {
            int width = Draw.badge(graphics, this.font, badge, x, textY - 2, background, foreground);
            if (badgeTip != null && mouseX >= x && mouseX < x + width && mouseY >= textY - 2 && mouseY < textY + 10) {
                UiTooltip.set(badgeTip);
            }
        }
    }

    /** "Next steps": what is done and what the collection still needs, in order. */
    private void drawSteps(GuiGraphics graphics) {
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        // A sunk strip, as the menus' wells
        Draw.well(graphics, left, this.stepsY, right - left, STEPS_HEIGHT, Surfaces.BORDER_DISABLED);
        int textY = this.stepsY + 4;
        int x = left + 6;
        Component heading = tr("editor.steps_heading");
        Draw.string(graphics, this.font, heading, x, textY, Theme.TEXT_SOFT);
        x += this.font.width(heading) + 6;

        int figuresInDraw = 0;
        for (CreatorDraft.FigureDraft figure : this.draft.figures()) {
            if (figure.enabled() && figure.isComplete()) {
                figuresInDraw++;
            }
        }
        List<Step> steps = List.of(
                new Step(identityReady(), tr("editor.step.name")),
                new Step(boxProblem() == null, tr("editor.step.box")),
                new Step(figuresInDraw > 0, figuresInDraw == 0 ? tr("editor.step.figures")
                        : figuresInDraw == 1 ? tr("editor.step.figures_one") : tr("editor.step.figures_done", figuresInDraw)),
                new Step(!this.draft.isDraftCollection(),
                        tr(this.draft.isDraftCollection() ? "editor.step.publish" : "editor.step.published")));
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String text = (step.done() ? "✔ " : "○ ") + step.text().getString();
            if (i > 0) {
                text = "· " + text;
            }
            int width = this.font.width(text);
            if (x + width > right - 6) {
                Draw.text(graphics, this.font, text, x, textY, Math.max(0, right - 6 - x),
                        step.done() ? Theme.GREEN : Theme.MUTED);
                return;
            }
            Draw.string(graphics, this.font, text, x, textY, step.done() ? Theme.GREEN : Theme.MUTED);
            x += width + 5;
        }
    }

    /** Whether the name, author and link can be saved, whatever the box. */
    boolean identityReady() {
        return PublishCheck.identityReady(this.draft);
    }

    private record Step(boolean done, Component text) {
    }

    // Input

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
                publishChanges(false);
            }
            return true;
        }
        return page().shortcut(key);
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
                () -> publishChanges(true), () -> {
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

    // The status row's save state: a label after which the drop hint may follow
    private final class SaveLine extends UiNode.Box {
        // Where the label drawn last ended, for the drop hint after it
        private int textRight;

        int textRight() {
            return Math.max(this.x, this.textRight);
        }

        // The save state, covered for a while by a line status() set unless it is urgent
        private SidebarScreen.SideStatus shown() {
            SidebarScreen.SideStatus state = saveStatus();
            if (state.urgent()) {
                return state;
            }
            Component live = liveMessage();
            return live != null ? SidebarScreen.SideStatus.of(live, messageColour) : state;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            SidebarScreen.SideStatus status = shown();
            Font font = Minecraft.getInstance().font;
            int textY = y + 2;
            String whole = status.label().getString();
            String cut = UiText.fit(font, whole, width);
            Draw.string(graphics, font, cut, x, textY, status.colour());
            int labelWidth = UiText.width(font, cut);
            this.textRight = x + labelWidth;
            boolean over = mouseX >= x && mouseX < x + labelWidth && mouseY >= y && mouseY < y + height;
            if (over && status.action() != null) {
                graphics.fill(x, textY + 9, x + labelWidth, textY + 10, status.colour());
            }
            if (status.progress() >= 0.0f) {
                int barWidth = Math.max(labelWidth, 60);
                Draw.progressBar(graphics, x, textY + 10, barWidth, 1, status.progress(), Surfaces.WELL,
                        status.colour());
                this.textRight = Math.max(this.textRight, x + barWidth);
            }
            if (over) {
                Component tip = status.tooltip() != null ? status.tooltip() : !cut.equals(whole) ? status.label() : null;
                if (tip != null) {
                    // Above the line, which it explains, rather than over it
                    UiTooltip.setAbove(SidebarScreen.tooltipLines(tip), x, y);
                }
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            SidebarScreen.SideStatus status = shown();
            Font font = Minecraft.getInstance().font;
            int labelWidth = UiText.width(font, UiText.fit(font, status.label().getString(), width));
            boolean over = click.x() >= x && click.x() < x + labelWidth && click.y() >= y && click.y() < y + height;
            if (status.action() == null || !click.left() || !over) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            status.action().run();
            return true;
        }
    }
}
