package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiTabs;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
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
 * <p>The top bar leads back to the collections, names the collection with its state (published,
 * draft or archived) and counts the unsaved changes; "Undo" (Ctrl+Z) steps back through the
 * draft's own snapshots, "Save" (Ctrl+S) sends only what changed, and "Review and publish" opens
 * the last tab. A "Next steps" line under the tabs, which can be folded away, says what the
 * collection still needs. While a save is sent, the status line at the bottom shows its upload.
 * Leaving with unsaved changes asks first whether to save them, discard them or stay.
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
    private static final int STEPS_HEIGHT = 14;
    // How long a saved change may take to come back in a sync before Save is offered again
    private static final long SYNC_TIMEOUT_MS = 10_000;
    private static final int PILL = 0xFFFFAA00;
    private static final int CRUMB = 0xFF9A9A9A;
    private static final int PROGRESS_WIDTH = 100;
    private static final int SHORT_SCREEN = 400;

    // Folded or not for the rest of the session, as the operator left it
    private static boolean stepsFolded;

    private final String collectionId;
    @Nullable
    private final BlockPos machinePos;
    private final CreatorDraft draft;
    private final Map<Tab, EditorPage> pages = new EnumMap<>(Tab.class);
    private Tab tab;

    // Nodes kept across rebuilds
    private final UiButton backButton;
    private final UiButton undoButton;
    private final UiButton saveButton;
    private final UiButton reviewButton;
    private final UiButton stepsButton;
    private final UiTabs tabs;

    // The save in flight, and when the last one was saved while its sync has not come back yet
    @Nullable
    private CreatorSave.Run run;
    private boolean leaveAfterSave;
    private long savedAt;
    // Whether the draft changed after the last save was started, which that save does not carry
    private boolean editedSinceSave;
    // The text field that had the keyboard at the last tick, so typing in another starts a new undo step
    @Nullable
    private GuiEventListener lastFocused;
    // A screen of the editor's own is shown over it, which keeps the previews loaded
    private boolean childOpen;
    // What changed, worked out once per change rather than per frame
    private List<String> changes = List.of();

    // Where build() put what drawContents() draws
    private int barY;
    private int crumbX;
    private int crumbRight;
    private int stepsY = -1;

    /**
     * Opens the editor on a collection this client was sent.
     *
     * @param machinePos the claw or gacha machine the creator was opened from, or null
     * @throws IllegalStateException when the client has no such collection
     */
    public CreatorEditorScreen(Screen parent, String collectionId, @Nullable BlockPos machinePos, Tab tab) {
        super(tr("editor.title"), parent);
        WorldCollectionRecord record = ClientWorldCollections.record(collectionId);
        if (record == null) {
            throw new IllegalStateException("No synced World collection " + collectionId);
        }
        this.collectionId = collectionId;
        this.machinePos = machinePos;
        this.draft = CreatorDraft.of(record);
        this.tab = tab;
        this.backButton = new UiButton(tr("editor.back"), UiButton.Style.LINK, this::onClose);
        this.undoButton = new UiButton(tr("editor.undo"), UiButton.Style.SECONDARY, this::undo);
        this.undoButton.setTooltip(tr("editor.undo_tip"));
        this.saveButton = new UiButton(tr("save"), UiButton.Style.GREEN, () -> save(false, false));
        this.saveButton.setTooltip(tr("editor.save_tip"));
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

    /** Whether a change, named as {@link CreatorDraft#changes()} names it, is unsaved. */
    boolean isChanged(String key) {
        return changes.contains(key);
    }

    /** Every unsaved change, named as {@link CreatorDraft#changes()} names them. */
    List<String> changes() {
        return changes;
    }

    /**
     * A page changed the draft: the previews show the change, and the counts and the Save
     * button follow it.
     */
    void changed() {
        this.editedSinceSave = true;
        draft.preview(PREVIEW_SLOT);
        refresh();
    }

    /** Shows a screen of the editor's own over it, keeping its previews and its draft. */
    void openChild(Screen child) {
        this.childOpen = true;
        this.minecraft.setScreen(child);
    }

    void selectTab(Tab next) {
        if (next != this.tab) {
            page().hide();
            this.tab = next;
            rebuild();
        }
    }

    private EditorPage page() {
        return this.pages.get(this.tab);
    }

    // Building

    @Override
    protected void init() {
        this.childOpen = false;
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

        // Top bar: the way back on the left, the actions on the right
        this.backButton.at(left, this.barY + 6, this.font.width(this.backButton.label()), 10);
        addNode(this.backButton);
        int x = right;
        x = place(this.reviewButton, x, 0);
        x = place(this.saveButton, x, GAP);
        x = place(this.undoButton, x, 4);
        this.crumbX = this.backButton.bounds().right() + 4;
        this.crumbRight = x - 8;
        addNode(this.undoButton);
        addNode(this.saveButton);
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
        refresh();
    }

    // Places a button left of a point, as wide as its label needs; returns its left edge
    private int place(UiButton button, int rightEdge, int gap) {
        int width = UiButton.widthFor(this.font, button.label(), false);
        int buttonX = rightEdge - gap - width;
        button.at(buttonX, this.barY, width, UiButton.HEIGHT);
        return buttonX;
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
        // Undoing while a save is sent would take back figures the server is about to receive
        this.undoButton.disable(this.run != null ? tr("editor.saving")
                : this.draft.canUndo() ? null : tr("editor.nothing_to_undo"));
        this.saveButton.disable(saveProblem());
        refreshTabs();
        page().draftChanged();
    }

    // The collection's rules

    /** Why the name, author, link or box cannot be saved as they are, or null when they can. */
    @Nullable
    Component identityProblem() {
        if (this.draft.name().isBlank()) {
            return tr("wizard.name_empty");
        }
        if (!WorldCollectionRecord.isPlainText(this.draft.name())) {
            return tr("name_rule");
        }
        if (!WorldCollectionRecord.isPlainText(this.draft.author())) {
            return tr("wizard.author_rule");
        }
        if (!this.draft.authorUrl().isEmpty() && !WorldCollectionRecord.isValidAuthorUrl(this.draft.authorUrl())) {
            return tr("identity.url_rule");
        }
        return boxProblem();
    }

    /** Why the box cannot be saved as chosen, or null when it can. */
    @Nullable
    Component boxProblem() {
        return switch (this.draft.boxMode()) {
            case COLOR -> this.draft.boxColor() == null ? tr("identity.box_colour_missing") : null;
            case TEXTURE -> this.draft.boxTexture() == null ? tr("identity.box_image_missing") : null;
            case NONE -> null;
        };
    }

    /** Why the draft cannot be sent now, with or without a publish, or null when it can. */
    @Nullable
    Component sendProblem() {
        if (this.run != null) {
            return tr("editor.saving");
        }
        if (isAwaitingSync()) {
            return tr("saving");
        }
        Component identity = identityProblem();
        if (identity != null) {
            return identity;
        }
        CreatorDraft.Forecast forecast = this.draft.forecast();
        if (forecast.recordBytes() > WorldContentLimits.RECORD_BYTES) {
            return tr("editor.too_big", (forecast.recordBytes() + 1023) / 1024, WorldContentLimits.RECORD_BYTES / 1024);
        }
        if (forecast.budgetProblem() != null) {
            return Component.literal(forecast.budgetProblem());
        }
        return null;
    }

    /** Why Save cannot be used now, having nothing to save included, or null when it can. */
    @Nullable
    Component saveProblem() {
        if (this.changes.isEmpty() && this.run == null && !isAwaitingSync()) {
            return tr("editor.nothing_to_save");
        }
        return sendProblem();
    }

    /**
     * Whether a save is on its way or waits for the server to send the saved collection back,
     * while the figures it names must keep their ids.
     */
    boolean isSaving() {
        return this.run != null || isAwaitingSync();
    }

    // Saved, but the server has not sent the saved collection back yet
    private boolean isAwaitingSync() {
        return this.savedAt != 0 && Util.getMillis() - this.savedAt < SYNC_TIMEOUT_MS;
    }

    // Undo and saving

    private void undo() {
        if (this.run == null && this.draft.undo()) {
            this.editedSinceSave = true;
            this.draft.preview(PREVIEW_SLOT);
            refresh();
            rebuild();
        }
    }

    /**
     * Sends everything that changed, and publishes the collection when asked.
     *
     * @param thenLeave goes back to the collections once it is saved, as the leave dialog's Save does
     */
    void save(boolean publish, boolean thenLeave) {
        if (!publish && !thenLeave && this.changes.isEmpty()) {
            return;
        }
        Component problem = publish || thenLeave ? sendProblem() : saveProblem();
        if (problem != null) {
            status(problem, Theme.RED);
            return;
        }
        CreatorSave.Plan plan = CreatorSave.plan(this.draft, publish);
        int skipped = plan.skipped().size();
        if (plan.isEmpty()) {
            // Only figures that miss a file changed, and those wait for their files
            status(skipped > 0 ? tr("editor.nothing_saved", skipped) : Component.empty(), Theme.AMBER);
            if (thenLeave && skipped == 0) {
                leaveEditor();
            }
            return;
        }
        this.leaveAfterSave = thenLeave;
        this.editedSinceSave = false;
        String name = this.draft.name();
        Component message = publish ? tr("editor.published", name)
                : skipped > 0 ? tr("editor.saved_skipped", name, skipped) : tr("editor.saved", name);
        status(Component.empty());
        CreatorSave.Run started = CreatorSave.save(this.draft, publish, outcome -> saved(outcome, message, publish));
        // A save that could not be handed over has already told saved() before it returns
        this.run = started.isFinished() ? null : started;
        refresh();
    }

    /** Why one figure cannot be sent on its own now, or null when it can. */
    @Nullable
    Component figureSendProblem() {
        if (this.run != null) {
            return tr("editor.saving");
        }
        if (isAwaitingSync()) {
            return tr("saving");
        }
        // A collection not created yet is saved whole along with the figure
        return this.draft.isNew() ? sendProblem() : null;
    }

    /**
     * Sends one figure's changes and nothing else, as the figure inspector's "Save figure"
     * does; the draft's other changes stay unsaved.
     */
    void saveFigure(String figureId) {
        CreatorDraft.FigureDraft figure = this.draft.figure(figureId);
        if (figure == null || !figure.isDirty()) {
            return;
        }
        Component problem = figureSendProblem();
        if (problem != null) {
            status(problem, Theme.RED);
            return;
        }
        CreatorSave.Plan plan = CreatorSave.planFigure(this.draft, figureId);
        if (plan.isEmpty()) {
            status(plan.skipped().isEmpty() ? Component.empty() : tr("editor.nothing_saved", plan.skipped().size()),
                    Theme.AMBER);
            return;
        }
        this.leaveAfterSave = false;
        // The leave dialog still asks about every change this save leaves out
        String prefix = CreatorDraft.FIGURE + figureId + "/";
        this.editedSinceSave = !this.draft.isNew()
                && this.changes.stream().anyMatch(change -> !change.startsWith(prefix));
        Component message = tr("inspector.saved", figure.name().isBlank() ? figureId : figure.name());
        status(Component.empty());
        CreatorSave.Run started = CreatorSave.saveFigure(this.draft, figureId,
                outcome -> saved(outcome, message, false));
        this.run = started.isFinished() ? null : started;
        refresh();
    }

    /**
     * Sends figures just added to the draft, such as a bulk add's, and nothing else, in batches
     * of {@link CreatorSave#BULK_FIGURES_PER_BATCH} figures; the draft's other changes stay
     * unsaved. Ask {@link #figureSendProblem()} first.
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
        this.leaveAfterSave = false;
        List<String> prefixes = new ArrayList<>();
        for (String figureId : figureIds) {
            prefixes.add(CreatorDraft.FIGURE + figureId + "/");
        }
        // The leave dialog still asks about every change this save leaves out
        this.editedSinceSave = !this.draft.isNew() && this.changes.stream()
                .anyMatch(change -> prefixes.stream().noneMatch(change::startsWith));
        status(Component.empty());
        CreatorSave.Run started = CreatorSave.saveFigures(this.draft, figureIds, CreatorSave.BULK_FIGURES_PER_BATCH,
                outcome -> saved(outcome, message, false, listener));
        this.run = started.isFinished() ? null : started;
        refresh();
        return started;
    }

    /** Selects figures just added in the figure grid and scrolls to the first. */
    void revealFigures(List<CreatorDraft.FigureDraft> figures) {
        if (this.pages.get(Tab.FIGURES) instanceof FiguresPage figuresPage) {
            figuresPage.reveal(figures);
        }
    }

    private void saved(CreatorSave.Outcome outcome, Component message, boolean published) {
        saved(outcome, message, published, null);
    }

    private void saved(CreatorSave.Outcome outcome, Component message, boolean published,
                       @Nullable Predicate<CreatorSave.Outcome> listener) {
        this.run = null;
        boolean shown = this.minecraft.screen == this;
        // The screen shown over the editor hears first, and may show a refusal itself
        boolean reported = false;
        if (listener != null) {
            try {
                reported = listener.test(outcome);
            } catch (RuntimeException e) {
                BlockPopsMod.LOGGER.error("A World collection save listener failed", e);
            }
        }
        if (outcome.ok()) {
            // Until the sync brings the saved collection back, the draft still counts it as changed
            this.savedAt = this.draft.isDirty() || published ? Util.getMillis() : 0;
            if (this.leaveAfterSave && shown) {
                UiToast.show(message);
                leaveEditor();
                return;
            }
            if (published && shown && this.pages.get(Tab.REVIEW) instanceof PublishPage publishPage) {
                // A dialog says it is published and offers what comes next
                publishPage.published(this.draft.name());
            } else {
                UiToast.show(message);
            }
        } else {
            this.leaveAfterSave = false;
            if (shown) {
                status(outcome.message(), Theme.RED);
            } else if (outcome.ending() != CreatorSave.Ending.CANCELLED && !reported) {
                UiToast.show(outcome.message());
            }
        }
        if (shown) {
            refresh();
        }
    }

    // Syncs and ticking

    @Override
    protected void onSync() {
        WorldCollectionRecord record = ClientWorldCollections.record(this.collectionId);
        if (record == null) {
            // Deleted for good, or a draft no longer sent to a player who is not an operator now
            UiToast.show(tr("editor.gone", this.draft.name()));
            leaveEditor();
            return;
        }
        if (record.equals(this.draft.base())) {
            // A sync of another collection; a save of this one is still on its way back
            return;
        }
        this.draft.rebase(record);
        this.savedAt = 0;
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
        if (focused != this.lastFocused) {
            // What is typed into another field, or into the same one after a pause, undoes on its own
            this.lastFocused = focused;
            this.draft.checkpoint();
        }
        if (this.run != null && hasStatus()) {
            // While a save is sent, the status row shows its progress instead
            status(Component.empty());
        }
        if (this.savedAt != 0 && !isAwaitingSync()) {
            // The sync never came; Save is offered again, and sends whatever still differs
            this.savedAt = 0;
            refresh();
        }
    }

    // Drawing

    @Override
    protected int statusY() {
        return this.panelY + this.panelHeight - 13;
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawCrumb(graphics, mouseX, mouseY);
        if (this.stepsY >= 0) {
            drawSteps(graphics);
        }
        page().drawContents(graphics, mouseX, mouseY, partialTick);
        drawStatusRow(graphics);
    }

    /**
     * The status row, at the bottom: how far a save is sent, with a bar, on the left, and on the
     * right, on a page with drop zones and while no message takes the row, that files can be
     * dropped on the window.
     */
    private void drawStatusRow(GuiGraphics graphics) {
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        int y = statusY();
        CreatorSave.Run sending = this.run;
        if (sending != null) {
            ClientWorldUploads.Progress upload = sending.progress().upload();
            Component text;
            float fraction;
            if (upload != null && !upload.file().isEmpty() && upload.total() > 0) {
                int percent = (int) (100L * upload.sent() / upload.total());
                text = tr("editor.uploading", upload.file(), percent);
                fraction = percent / 100.0f;
            } else {
                text = tr("editor.saving");
                fraction = 1.0f;
            }
            int textRoom = Math.max(0, right - left - PROGRESS_WIDTH - 8);
            String shown = Draw.cut(this.font, text.getString(), textRoom);
            graphics.drawString(this.font, shown, left, y, Theme.TEXT_SOFT, false);
            int barX = left + this.font.width(shown) + 8;
            Draw.progressBar(graphics, barX, y + 2, Math.min(PROGRESS_WIDTH, Math.max(0, right - barX)), 4, fraction,
                    Theme.TRACK, Theme.GREEN);
            left = barX + PROGRESS_WIDTH + 12;
        }
        if (!hasStatus() && page().takesFiles()) {
            String hint = Draw.cut(this.font, tr("editor.drop_hint").getString(), Math.max(0, right - left));
            if (this.font.width(hint) >= 60) {
                graphics.drawString(this.font, hint, right - this.font.width(hint), y, Theme.FAINT, false);
            }
        }
    }

    /** After the way back: "/ name", the state badge and the unsaved count, as room allows. */
    private void drawCrumb(GuiGraphics graphics, int mouseX, int mouseY) {
        int textY = this.barY + 6;
        int x = this.crumbX;
        graphics.drawString(this.font, "/", x, textY, CRUMB, false);
        x += this.font.width("/") + 4;

        Component pill = this.changes.isEmpty() ? null : this.changes.size() == 1 ? tr("editor.unsaved_one")
                : tr("editor.unsaved", this.changes.size());
        int pillWidth = pill != null ? this.font.width(pill) + 8 + 6 : 0;
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
        if (this.draft.isDraftCollection() && !this.draft.archived()
                && this.font.width(this.draft.name()) + Draw.badgeWidth(this.font, badge) + pillWidth + 14
                > this.crumbRight - x) {
            // A narrow bar says only DRAFT, with the rest in its tooltip
            badge = tr("card.badge.draft");
        }
        int badgeWidth = Draw.badgeWidth(this.font, badge) + 6;
        int nameRoom = this.crumbRight - x - badgeWidth - pillWidth;
        boolean withBadge = nameRoom >= 40;
        if (!withBadge) {
            nameRoom = this.crumbRight - x - pillWidth;
        }
        boolean withPill = pill != null && nameRoom >= 30;
        if (!withPill) {
            nameRoom += pillWidth;
        }
        String name = this.draft.name().isBlank() ? tr("home.new").getString() : this.draft.name();
        String shown = Draw.cut(this.font, name, Math.max(0, nameRoom - 6));
        Component bold = Component.literal(shown).withStyle(ChatFormatting.BOLD);
        graphics.drawString(this.font, bold, x, textY, Theme.TEXT, false);
        x += this.font.width(bold) + 6;
        if (withBadge) {
            int width = Draw.badge(graphics, this.font, badge, x, textY - 2, background, foreground);
            if (badgeTip != null && mouseX >= x && mouseX < x + width && mouseY >= textY - 2 && mouseY < textY + 10) {
                UiTooltip.set(badgeTip);
            }
            x += width + 6;
        }
        if (withPill) {
            Draw.pill(graphics, this.font, pill, x, textY - 2, PILL, 0xFF000000);
        }
    }

    /** "Next steps": what is done and what the collection still needs, in order. */
    private void drawSteps(GuiGraphics graphics) {
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        graphics.fill(left, this.stepsY, right, this.stepsY + STEPS_HEIGHT, 0xFF151515);
        Draw.outline(graphics, left, this.stepsY, right - left, STEPS_HEIGHT, Theme.SEPARATOR);
        int textY = this.stepsY + 3;
        int x = left + 6;
        Component heading = tr("editor.steps_heading");
        graphics.drawString(this.font, heading, x, textY, Theme.TEXT_SOFT, false);
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
            graphics.drawString(this.font, text, x, textY, step.done() ? Theme.GREEN : Theme.MUTED, false);
            x += width + 5;
        }
    }

    /** Whether the name, author and link can be saved, whatever the box. */
    boolean identityReady() {
        return !this.draft.name().isBlank() && WorldCollectionRecord.isPlainText(this.draft.name())
                && WorldCollectionRecord.isPlainText(this.draft.author())
                && (this.draft.authorUrl().isEmpty() || WorldCollectionRecord.isValidAuthorUrl(this.draft.authorUrl()));
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
            save(false, false);
            return true;
        }
        return page().shortcut(key);
    }

    // Leaving

    /**
     * Leaving with unsaved changes asks first, unless the save being sent, or just saved, holds
     * all of them.
     */
    @Override
    public void onClose() {
        if (this.changes.isEmpty() || (this.run != null || isAwaitingSync()) && !this.editedSinceSave) {
            leaveEditor();
            return;
        }
        UiModal dialog = new UiModal(tr("editor.leave_title"),
                this.changes.size() == 1 ? tr("editor.leave_body_one") : tr("editor.leave_body", this.changes.size()));
        dialog.cancelButton(tr("cancel"));
        dialog.button(tr("editor.discard"), UiButton.Style.DANGER, this::leaveEditor);
        UiButton saveAndLeave = dialog.button(tr("save"), UiButton.Style.GREEN, () -> save(false, true));
        saveAndLeave.disable(sendProblem());
        openModal(dialog);
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

    // Lets go of what the editor showed
    private void closePages() {
        for (EditorPage editorPage : this.pages.values()) {
            editorPage.close();
        }
        CreatorPreview.clearAll();
        // Files only the editor used are freed; the ones a synced collection names stay
        ClientPreviewAssets.release();
    }
}
