package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCheckbox;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiDropdown;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.UiViewport;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.BulkPlan;
import com.theplumteam.client.worldcontent.BulkPlan.Row;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The creator's fifth screen: a card over the collection editor that turns a folder or a pile
 * of files into figures at once. Files dropped on the window or chosen in the dialog are listed
 * as the figures they make ({@link BulkPlan}): each row shows a small preview drawn from the
 * files themselves, its files, a name and a size that can be changed in place, its kind and
 * whether it is ready, a warning (its id is taken, so another is used) or an error that skips it.
 * Names come from the file names, with or without their numbers, and every row starts at the
 * default size; a model, a texture and poses with the same name pair into one 3D model figure.
 * Tab moves between the name and size cells, and a row shows "Remove" under the pointer.
 *
 * <p>Under the table, the capacity line says how many figures and how much file space the
 * collection would use, against its caps, and a summary counts the rows. "Add" puts the ready
 * rows in the draft as one undo step and sends them at once, in batches of
 * {@link CreatorSave#BULK_FIGURES_PER_BATCH} figures, each batch saved whole by the server; the
 * other unsaved changes of the draft stay unsaved. While they are sent a bar shows the files
 * going up, which can be paused between batches or stopped after the current one, the figures
 * already saved staying saved and the others staying in the draft, unsaved. When the server
 * answers that it is busy, as it does once the player's upload or edit limit is used up, the
 * rest goes on by itself a little later. Hiding the card leaves the
 * upload going, and the editor's status row shows how far it is.
 */
public class BulkAddScreen extends CreatorScreen {
    /** The preview slot the rows are drawn through: a collection holding one figure per row. */
    static final int PREVIEW_SLOT = 2;
    // The mockup's 1040 by 640 dialog at about half its pixel size, as every creator size is
    private static final int MAX_CARD_WIDTH = 520;
    private static final int MAX_CARD_HEIGHT = 320;
    private static final int SCREEN_MARGIN = 8;
    private static final int STATUS_GAP = 4;
    private static final int STATUS_ROOM = STATUS_GAP + 9;
    private static final int PAD = 12;
    private static final int HEADER = 24;
    private static final int ZONE_HEIGHT = 40;
    private static final int GAP = 6;
    private static final int TABLE_HEAD = 12;
    private static final int CAPACITY_BAR = 4;
    private static final int SHADOW = 0x80000000;
    private static final int HEAD_TEXT = 0xFF9A9A9A;
    private static final int TABLE_EDGE = 0xFF232323;
    // How long to wait before going on when the server was busy, and how often in a row
    private static final long RETRY_MS = 15_000;
    private static final int MAX_RETRIES = 8;
    //? if >=1.21.5 && <1.21.6 {
    /*// In front of everything the editor draws on 1.21.5, whose cards lift their badges and buttons 400
    private static final float EDITOR_DEPTH = 1000.0f;
    *///? }

    /** Where the card is in its work. */
    private enum Phase {
        /** Files are chosen and rows changed. */
        CHOOSING,
        /** The figures were added and are being sent. */
        SENDING,
        /** The server was busy, as when an upload or edit limit is used up; the rest goes on at {@link #retryAt}. */
        WAITING,
        /** The server refused a batch, or it could not be sent. */
        FAILED,
        /** The operator stopped the rest. */
        STOPPED
    }

    private final CreatorEditorScreen editor;
    private final BulkPlan plan = new BulkPlan();

    // Nodes kept across rebuilds
    private final UiDropZone zone;
    private final UiDropdown nameFrom;
    private final UiDropdown defaultSize;
    private final UiCheckbox pairBox;
    private final Table table = new Table();
    private final UiButton cancelButton;
    private final UiButton addButton;
    private final UiButton pauseButton;
    private final UiButton stopButton;
    private final UiButton hideButton;
    private final UiButton retryButton;
    private final UiButton closeButton;

    // What changes with the rows, worked out once per change rather than per frame
    private Component capacity = Component.empty();
    private float currentShare;
    private float newShare;
    private boolean overCapacity;
    @Nullable
    private Component addProblem;
    private int adding;

    // Sending
    private Phase phase = Phase.CHOOSING;
    @Nullable
    private CreatorSave.Run run;
    @Nullable
    private CreatorSave.Plan sendingPlan;
    private final List<String> sending = new ArrayList<>();
    // The figure id each added row got, by row
    private final Map<Row, String> rowIds = new HashMap<>();
    private final Set<String> savedIds = new HashSet<>();
    private Map<String, Integer> batchOf = Map.of();
    private int filesBefore;
    private int filesTotal;
    // When the current send started and how many files were done then, for the time left
    private long startedAt;
    private int filesAtStart;
    private long retryAt;
    private int retries;
    private boolean stopping;
    private boolean closeSoon;
    private Component message = Component.empty();

    // Where build() put what drawContents() draws
    private int footerY;
    private int footerRight;

    /**
     * @param results files read from a drop on the editor's grid, or none
     */
    BulkAddScreen(CreatorEditorScreen editor, List<FilePicker.PickResult> results) {
        super(tr("bulk.title_short"), editor);
        this.editor = editor;
        this.zone = UiDropZone.many(this, tr("bulk.drop"), this::filesChosen).help(tr("bulk.drop_help"));
        List<Component> names = List.of(tr("bulk.name_from.file"), tr("bulk.name_from.no_numbers"));
        this.nameFrom = new UiDropdown(tr("bulk.name_from"), names, 0, index -> {
            this.plan.setNameMode(BulkPlan.NameMode.values()[index]);
            rowsChanged(false);
        });
        List<Component> sizes = new ArrayList<>();
        for (int i = 0; i < WorldCollectionRecord.SCALES.size(); i++) {
            sizes.add(UiSizeSlider.label(i));
        }
        this.defaultSize = new UiDropdown(tr("bulk.default_size"), sizes,
                WorldCollectionRecord.scaleIndex(this.plan.defaultScale()), index -> {
            this.plan.setDefaultScale(WorldCollectionRecord.SCALES.get(index));
            rowsChanged(true);
        });
        this.pairBox = new UiCheckbox(tr("bulk.pair"), this.plan.pairs(), checked -> {
            this.table.stopEditing();
            this.plan.setPair(checked);
            rowsChanged(true);
        });
        this.cancelButton = new UiButton(tr("cancel"), UiButton.Style.SECONDARY, this::leave);
        this.addButton = new UiButton(tr("bulk.add_none"), UiButton.Style.GREEN, this::add);
        this.pauseButton = new UiButton(tr("bulk.pause"), UiButton.Style.LINK, this::togglePause);
        this.stopButton = new UiButton(tr("bulk.stop"), UiButton.Style.DANGER_TEXT, this::stopRest);
        this.hideButton = new UiButton(tr("bulk.hide"), UiButton.Style.SECONDARY, this::hide);
        this.hideButton.setTooltip(tr("bulk.hide_tip"));
        this.retryButton = new UiButton(tr("bulk.retry"), UiButton.Style.GREEN, this::retryRest);
        this.closeButton = new UiButton(tr("bulk.close"), UiButton.Style.SECONDARY, this::leave);
        tooMany(this.plan.add(results));
        rowsChanged(true);
    }

    // Building

    @Override
    protected void init() {
        // Only the screen shown is told the window changed size, and the editor is drawn under this one
        if (this.parent.width != this.width || this.parent.height != this.height) {
            //? if >=26 {
            /*this.parent.resize(this.width, this.height);
            *///? } else {
            this.parent.resize(this.minecraft, this.width, this.height);
            //? }
        }
        super.init();
    }

    @Override
    protected void build() {
        fitCard();
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        int width = right - left;
        boolean choosing = this.phase == Phase.CHOOSING;
        Component busy = choosing ? null : tr("bulk.busy");

        int y = this.panelY + HEADER;
        this.zone.at(left, y, width, ZONE_HEIGHT);
        this.zone.disable(busy);
        addNode(this.zone);
        y += ZONE_HEIGHT + GAP;

        // The options on one row: the two choices at their natural width, the box with the rest
        int x = left;
        int nameWidth = Math.min(this.nameFrom.naturalWidth(), width * 2 / 5);
        this.nameFrom.at(x, y, nameWidth, UiDropdown.HEIGHT);
        this.nameFrom.disable(busy);
        x += nameWidth + GAP;
        int sizeWidth = Math.min(this.defaultSize.naturalWidth(), width / 4);
        this.defaultSize.at(x, y, sizeWidth, UiDropdown.HEIGHT);
        this.defaultSize.disable(busy);
        x += sizeWidth + GAP + 2;
        this.pairBox.at(x, y + 2, Math.max(0, right - x), UiCheckbox.BOX + 2);
        this.pairBox.disable(busy);
        addNode(this.nameFrom);
        addNode(this.defaultSize);
        addNode(this.pairBox);
        y += UiDropdown.HEIGHT + GAP;

        this.footerY = this.panelY + this.panelHeight - 8 - UiButton.HEIGHT;
        int tableTop = y + TABLE_HEAD;
        this.table.at(left, tableTop, width, Math.max(0, this.footerY - GAP - tableTop));
        this.table.layout();
        addNode(this.table);

        buildFooter(left, right);
    }

    // The buttons of the footer, which change with the phase, from the right edge
    private void buildFooter(int left, int right) {
        int x = right;
        List<UiButton> shown = new ArrayList<>();
        switch (this.phase) {
            case CHOOSING -> {
                updateAddButton();
                shown.add(this.addButton);
                shown.add(this.cancelButton);
            }
            case SENDING, WAITING -> {
                shown.add(this.hideButton);
                shown.add(this.stopButton);
                if (this.phase == Phase.SENDING && !this.stopping) {
                    this.pauseButton.setLabel(tr(this.run != null && this.run.isPaused() ? "bulk.resume" : "bulk.pause"));
                    shown.add(this.pauseButton);
                }
            }
            case FAILED, STOPPED -> {
                shown.add(this.closeButton);
                if (this.phase == Phase.FAILED && !remaining().isEmpty()) {
                    shown.add(this.retryButton);
                }
            }
        }
        for (UiButton button : shown) {
            boolean text = button == this.pauseButton || button == this.stopButton;
            int buttonWidth = text ? this.font.width(button.label())
                    : Math.max(button == this.addButton ? 110 : 60, UiButton.widthFor(this.font, button.label(), false));
            x -= buttonWidth;
            button.at(x, text ? this.footerY + 6 : this.footerY, buttonWidth, text ? 10 : UiButton.HEIGHT);
            addNode(button);
            x -= text ? 10 : GAP;
        }
        this.footerRight = Math.max(left, x - 6);
    }

    private void fitCard() {
        int cardWidth = Math.min(MAX_CARD_WIDTH, this.width - 2 * SCREEN_MARGIN);
        int cardHeight = Math.min(MAX_CARD_HEIGHT, this.height - 2 * SCREEN_MARGIN - STATUS_ROOM);
        this.panelWidth = Math.max(0, cardWidth);
        this.panelHeight = Math.max(0, cardHeight);
        this.panelX = (this.width - this.panelWidth) / 2;
        int highest = this.height - SCREEN_MARGIN - STATUS_ROOM - this.panelHeight;
        this.panelY = Math.max(SCREEN_MARGIN, Math.min((this.height - this.panelHeight) / 2, highest));
    }

    // Following the rows

    private void filesChosen(List<FilePicker.PickResult> results) {
        if (this.phase != Phase.CHOOSING) {
            return;
        }
        this.table.stopEditing();
        status(Component.empty());
        tooMany(this.plan.add(results));
        rowsChanged(true);
    }

    // Says how many files were left out because the list was full
    private void tooMany(int leftOut) {
        if (leftOut > 0) {
            status(tr("bulk.too_many", leftOut, FilePicker.MAX_FILES), Theme.AMBER);
        }
    }

    /**
     * The rows or the draft changed: their ids and states, the capacity line and what Add
     * allows follow, and the preview when the files or sizes changed.
     */
    private void rowsChanged(boolean preview) {
        CreatorDraft draft = this.editor.draft();
        this.plan.refresh(draft);
        if (preview) {
            showPreview(draft);
        }
        List<Row> rows = this.plan.toAdd();
        this.adding = rows.size();
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>();
        Set<FilePicker.LocalFile> files = new LinkedHashSet<>();
        for (Row row : rows) {
            WorldCollectionRecord.Figure figure = row.toFigure(row.id());
            if (figure != null) {
                figures.add(figure);
                files.addAll(row.localFiles());
            }
        }
        CreatorDraft.Forecast before = draft.forecast();
        CreatorDraft.Forecast after = figures.isEmpty() ? before : draft.forecastWith(figures, files);
        int current = draft.figures().size();
        int total = current + this.adding;
        this.currentShare = current / (float) WorldContentLimits.MAX_FIGURES;
        this.newShare = this.adding / (float) WorldContentLimits.MAX_FIGURES;
        long addedBytes = Math.max(0, after.collectionBytes() - before.collectionBytes());
        int afterKiB = (int) Math.min(Integer.MAX_VALUE, (after.collectionBytes() + 1023) / 1024);
        this.overCapacity = !after.fits() || after.collectionBytes() > WorldContentLimits.MAX_COLLECTION_BYTES;
        this.capacity = tr("card.joined",
                tr("bulk.capacity", this.adding, current, total, WorldContentLimits.MAX_FIGURES),
                tr("bulk.bytes", String.format(Locale.ROOT, "%.1f", addedBytes / (1024.0 * 1024.0)),
                        UiQuotaMeter.text(afterKiB, (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024))));
        if (this.adding == 0) {
            this.addProblem = tr(this.plan.isEmpty() ? "bulk.no_files" : "bulk.nothing");
        } else if (after.recordBytes() > WorldContentLimits.RECORD_BYTES) {
            this.addProblem = tr("editor.too_big", (after.recordBytes() + 1023) / 1024,
                    WorldContentLimits.RECORD_BYTES / 1024);
        } else if (after.budgetProblem() != null) {
            this.addProblem = Component.literal(after.budgetProblem());
        } else {
            this.addProblem = null;
        }
        this.table.layout();
        updateAddButton();
    }

    private void updateAddButton() {
        this.addButton.setLabel(this.adding == 0 ? tr("bulk.add_none")
                : this.adding == 1 ? tr("bulk.add_one") : tr("bulk.add", this.adding));
        Component problem = this.addProblem;
        if (problem == null) {
            // A save of the editor's may be on its way, or wait for its sync
            problem = this.editor.figureSendProblem();
        }
        this.addButton.disable(problem);
    }

    /** Shows every row whose files make a figure in the preview slot, each under an id of its own. */
    private void showPreview(CreatorDraft draft) {
        List<WorldCollectionRecord.Figure> figures = new ArrayList<>();
        Map<String, BlobInfo> blobs = new HashMap<>();
        for (Row row : this.plan.rows()) {
            WorldCollectionRecord.Figure figure = row.toFigure(previewId(row));
            if (figure == null) {
                continue;
            }
            figures.add(figure);
            for (FilePicker.LocalFile file : row.localFiles()) {
                ClientPreviewAssets.use(file);
                blobs.put(file.sha(), file.info());
            }
        }
        if (figures.isEmpty()) {
            CreatorPreview.clear(PREVIEW_SLOT);
            return;
        }
        CreatorPreview.show(PREVIEW_SLOT, draft.toRecord().withFigures(figures), blobs);
    }

    // A row's figure id in the preview, which stays while the row does, so renaming it keeps its pose
    private static String previewId(Row row) {
        return "row_" + row.serial();
    }

    // Adding and sending

    private void add() {
        if (this.phase != Phase.CHOOSING) {
            return;
        }
        updateAddButton();
        if (!this.addButton.enabled()) {
            return;
        }
        this.table.stopEditing();
        CreatorDraft draft = this.editor.draft();
        List<Row> rows = this.plan.toAdd();
        List<FigureDraft> added = new ArrayList<>();
        Map<Row, String> ids = new HashMap<>();
        draft.change("figures/bulk_add", () -> {
            for (Row row : rows) {
                FilePicker.LocalFile model = row.model();
                FilePicker.LocalFile poses = row.animation();
                FigureDraft figure = draft.addFigure(row.name(), row.kind(), FileRef.of(row.texture()),
                        model != null ? FileRef.of(model) : null, poses != null ? FileRef.of(poses) : null,
                        row.scale());
                if (figure != null) {
                    added.add(figure);
                    ids.put(row, figure.id());
                }
            }
        });
        if (added.isEmpty()) {
            return;
        }
        this.editor.changed();
        this.editor.revealFigures(added);
        this.sending.clear();
        for (FigureDraft figure : added) {
            this.sending.add(figure.id());
        }
        this.rowIds.clear();
        this.rowIds.putAll(ids);
        this.savedIds.clear();
        this.filesBefore = 0;
        this.retries = 0;
        startSending(List.copyOf(this.sending));
    }

    private void startSending(List<String> figureIds) {
        this.phase = Phase.SENDING;
        this.stopping = false;
        this.message = Component.empty();
        this.startedAt = Util.getMillis();
        this.filesAtStart = this.filesBefore;
        status(Component.empty());
        String name = this.editor.draft().name();
        Component done = this.sending.size() == 1 ? tr("bulk.added_one", name) : tr("bulk.added", this.sending.size(), name);
        CreatorSave.Run started = this.editor.saveFigures(figureIds, done, this::finished);
        if (started == null) {
            // Nothing of them differs from what the server has
            this.savedIds.addAll(figureIds);
            this.closeSoon = true;
            return;
        }
        if (started.isFinished()) {
            // It could not be handed over, and finished() has said so
            rebuild();
            return;
        }
        this.run = started;
        this.sendingPlan = started.plan();
        Map<String, Integer> batches = new HashMap<>();
        List<CreatorSave.Submission> submissions = this.sendingPlan.submissions();
        for (int i = 0; i < submissions.size(); i++) {
            for (String figureId : figureIdsOf(submissions.get(i).edit())) {
                batches.putIfAbsent(figureId, i);
            }
        }
        this.batchOf = batches;
        this.filesTotal = this.filesBefore + this.sendingPlan.fileCount();
        rebuild();
    }

    // The figures a batch puts, in order
    private static List<String> figureIdsOf(WorldCollectionEdit edit) {
        List<String> ids = new ArrayList<>();
        List<WorldCollectionEdit> edits = edit instanceof WorldCollectionEdit.Batch batch ? batch.edits() : List.of(edit);
        for (WorldCollectionEdit one : edits) {
            if (one instanceof WorldCollectionEdit.PutFigure put) {
                ids.add(put.figure());
            }
        }
        return ids;
    }

    /**
     * Hears how a send ended, before the editor does. Answers whether this card showed a
     * refusal itself, which it does only while it is shown.
     */
    private boolean finished(CreatorSave.Outcome outcome) {
        CreatorSave.Plan sent = this.sendingPlan;
        if (sent != null) {
            List<CreatorSave.Submission> submissions = sent.submissions();
            for (int i = 0; i < Math.min(outcome.saved(), submissions.size()); i++) {
                this.savedIds.addAll(figureIdsOf(submissions.get(i).edit()));
                this.filesBefore += submissions.get(i).files().size();
            }
        }
        if (outcome.saved() > 0) {
            // A batch went through, so a wait that follows starts a new count
            this.retries = 0;
        }
        this.run = null;
        this.sendingPlan = null;
        boolean shown = this.minecraft.screen == this;
        int saved = savedCount();
        switch (outcome.ending()) {
            case SAVED -> {
                this.savedIds.addAll(this.sending);
                // Left on the next tick: the editor takes the outcome first
                this.closeSoon = true;
                return false;
            }
            case CANCELLED -> {
                this.phase = Phase.STOPPED;
                this.message = tr("bulk.stopped", saved, this.sending.size());
            }
            default -> {
                boolean limited = outcome.ending() == CreatorSave.Ending.REFUSED
                        && outcome.status().result() == WorldContentStatus.Result.BUSY;
                if (limited && shown && this.retries < MAX_RETRIES && !remaining().isEmpty()) {
                    this.retries++;
                    this.phase = Phase.WAITING;
                    this.retryAt = Util.getMillis() + RETRY_MS;
                } else {
                    this.phase = Phase.FAILED;
                    this.message = tr("bulk.failed", saved, this.sending.size(), outcome.message());
                }
            }
        }
        if (shown) {
            rebuild();
        }
        return shown;
    }

    private int savedCount() {
        int count = 0;
        for (String figureId : this.sending) {
            if (this.savedIds.contains(figureId)) {
                count++;
            }
        }
        return count;
    }

    /** The figures of this add not saved yet that the draft still holds with something to send. */
    private List<String> remaining() {
        List<String> rest = new ArrayList<>();
        CreatorDraft draft = this.editor.draft();
        for (String figureId : this.sending) {
            FigureDraft figure = draft.figure(figureId);
            if (!this.savedIds.contains(figureId) && figure != null && figure.isComplete() && figure.isDirty()) {
                rest.add(figureId);
            }
        }
        return rest;
    }

    private void retryRest() {
        if (this.phase != Phase.WAITING && this.phase != Phase.FAILED) {
            return;
        }
        List<String> rest = remaining();
        if (rest.isEmpty()) {
            this.closeSoon = true;
            return;
        }
        Component problem = this.editor.figureSendProblem();
        if (problem != null) {
            this.phase = Phase.FAILED;
            this.message = problem;
            rebuild();
            return;
        }
        startSending(rest);
    }

    private void togglePause() {
        CreatorSave.Run sendingRun = this.run;
        if (sendingRun == null) {
            return;
        }
        if (sendingRun.isPaused()) {
            sendingRun.resume();
        } else {
            sendingRun.pause();
        }
        rebuild();
    }

    private void stopRest() {
        if (this.phase == Phase.WAITING) {
            this.phase = Phase.STOPPED;
            this.message = tr("bulk.stopped", savedCount(), this.sending.size());
            rebuild();
            return;
        }
        CreatorSave.Run sendingRun = this.run;
        if (sendingRun == null || this.stopping) {
            return;
        }
        this.stopping = true;
        boolean paused = sendingRun.isPaused();
        // A batch held by the pause has not started, so it is dropped and the send ends at once
        sendingRun.cancel();
        if (paused) {
            sendingRun.resume();
        }
        if (this.phase == Phase.SENDING) {
            // Pausing means nothing any more
            rebuild();
        }
    }

    /** Leaves the card while the figures are sent; the editor shows how far they are. */
    private void hide() {
        CreatorSave.Run sendingRun = this.run;
        if (sendingRun != null && sendingRun.isPaused()) {
            // The editor has no way to resume it
            sendingRun.resume();
        }
        if (this.phase == Phase.WAITING) {
            int rest = remaining().size();
            UiToast.show(tr("bulk.left_unsaved", rest));
        }
        leave();
    }

    // Syncs and ticking

    @Override
    protected void onSync() {
        // The editor is not ticked while this card is shown, so it hears the sync from here
        this.editor.onSync();
        if (this.minecraft.screen != this) {
            return;
        }
        if (this.phase == Phase.CHOOSING) {
            rowsChanged(true);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.screen != this) {
            return;
        }
        if (this.closeSoon) {
            leave();
            return;
        }
        if (this.phase == Phase.WAITING && Util.getMillis() >= this.retryAt) {
            retryRest();
        }
        if (this.phase == Phase.CHOOSING) {
            updateAddButton();
        }
    }

    // Drawing

    //? if >=1.21.5 && <1.21.6 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // Ends the lift drawBackground started
        GuiPose.pop(graphics);
    }
    *///? }

    /**
     * The editor this card opened over, dimmed. The card has to cover the editor's items and
     * figures, which each version needs done its own way, as the new-collection wizard does it.
     */
    @Override
    protected void drawBackground(GuiGraphics graphics, float partialTick) {
        // No pointer, so nothing under the card lights up or shows a tooltip
        this.parent.render(graphics, -1, -1, partialTick);
        GuiPose.flush(graphics);
        //? if >=1.21.6 {
        /*GuiPose.nextStratum(graphics);
        *///? } elif >=1.21.5 {
        /*// 1.21.5 removed the global depth state, so the card is pushed forward in the pose
        GuiPose.push(graphics);
        GuiPose.lift(graphics, EDITOR_DEPTH);
        *///? } elif >=1.21.2 {
        /*RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256);
        *///? } else {
        RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256, false);
        //? }
        graphics.fill(0, 0, this.width, this.height, Theme.MODAL_DIM);
        GuiPose.nextStratum(graphics);
    }

    @Override
    protected void drawPanel(GuiGraphics graphics) {
        graphics.fill(this.panelX + 4, this.panelY + this.panelHeight, this.panelX + this.panelWidth + 4,
                this.panelY + this.panelHeight + 5, SHADOW);
        Draw.panel(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, Theme.DIALOG,
                Theme.DIALOG_OUTLINE);
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        String name = this.editor.draft().name().isBlank() ? tr("home.new").getString() : this.editor.draft().name();
        // Bold letters are wider, so the title is cut with room to spare
        String title = Draw.cut(this.font, tr("bulk.title", name).getString(), Math.max(0, right - left - 12));
        graphics.drawString(this.font, Component.literal(title).withStyle(ChatFormatting.BOLD), left, this.panelY + 9,
                Theme.TEXT, false);

        this.table.drawHead(graphics);
        switch (this.phase) {
            case CHOOSING -> drawChoosingFooter(graphics, left, mouseX, mouseY);
            case SENDING, WAITING -> drawSendingFooter(graphics, left, mouseX, mouseY);
            case FAILED, STOPPED -> Draw.lines(graphics, this.font, this.message.getString(), left, this.footerY + 1,
                    Math.max(0, this.footerRight - left), 2, this.phase == Phase.FAILED ? Theme.RED : Theme.TEXT_SOFT);
        }
    }

    // The summary of the rows on top; under it the capacity bar and line
    private void drawChoosingFooter(GuiGraphics graphics, int left, int mouseX, int mouseY) {
        int right = this.footerRight;
        List<Component> parts = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        int ready = this.plan.count(BulkPlan.State.READY);
        int warnings = this.plan.count(BulkPlan.State.WARNING);
        int errors = this.plan.count(BulkPlan.State.ERROR);
        int leftOut = this.plan.count(BulkPlan.State.LEFT_OUT);
        if (ready > 0 || this.plan.isEmpty()) {
            parts.add(tr("bulk.summary.ready", ready));
            colours.add(Theme.GREEN);
        }
        if (warnings > 0) {
            parts.add(warnings == 1 ? tr("bulk.summary.warning_one") : tr("bulk.summary.warnings", warnings));
            colours.add(0xFFFFCC66);
        }
        if (errors > 0) {
            parts.add(errors == 1 ? tr("bulk.summary.error_one") : tr("bulk.summary.errors", errors));
            colours.add(0xFFFF8A8A);
        }
        if (leftOut > 0) {
            parts.add(tr("bulk.summary.left_out", leftOut));
            colours.add(Theme.MUTED);
        }
        int x = left;
        int summaryY = this.footerY + 1;
        boolean cut = false;
        for (int i = 0; i < parts.size(); i++) {
            String text = (i > 0 ? "· " : "") + parts.get(i).getString();
            int textWidth = this.font.width(text);
            cut |= x + textWidth > right;
            if (x < right) {
                Draw.text(graphics, this.font, text, x, summaryY, right - x, colours.get(i));
            }
            x += textWidth + 4;
        }
        if (cut && inside(mouseX, mouseY, left, summaryY - 1, right - left, 10)) {
            UiTooltip.set(parts);
        }

        int barWidth = Math.min(80, Math.max(0, (right - left) / 4));
        int barY = this.footerY + 14;
        graphics.fill(left, barY, left + barWidth, barY + CAPACITY_BAR, Theme.TRACK);
        int currentWidth = Math.round(barWidth * Math.min(1.0f, this.currentShare));
        int newWidth = Math.round(barWidth * Math.min(1.0f, this.currentShare + this.newShare)) - currentWidth;
        graphics.fill(left, barY, left + currentWidth, barY + CAPACITY_BAR, 0xFF8A8A8A);
        if (newWidth > 0) {
            graphics.fill(left + currentWidth, barY, left + currentWidth + newWidth, barY + CAPACITY_BAR,
                    this.overCapacity ? Theme.RED : Theme.GREEN);
        }
        String capacityText = this.capacity.getString();
        int textX = left + barWidth + 6;
        int textY = this.footerY + 12;
        int textRoom = Math.max(0, right - textX);
        Draw.text(graphics, this.font, capacityText, textX, textY, textRoom, this.overCapacity ? Theme.RED : HEAD_TEXT);
        if (this.font.width(capacityText) > textRoom && inside(mouseX, mouseY, left, textY - 1, right - left, 10)) {
            UiTooltip.set(this.capacity);
        }
    }

    private static boolean inside(int mouseX, int mouseY, int left, int top, int width, int height) {
        return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
    }

    // "Uploading 4 of 14 files · gohan.png · about 30 s", the bar, and the server's limit
    private void drawSendingFooter(GuiGraphics graphics, int left, int mouseX, int mouseY) {
        int room = Math.max(0, this.footerRight - left);
        Component text;
        float fraction;
        CreatorSave.Run sendingRun = this.run;
        if (this.phase == Phase.WAITING) {
            long seconds = Math.max(0, (this.retryAt - Util.getMillis() + 999) / 1000);
            text = tr("bulk.limit_wait", WorldContentLimits.UPLOAD_BEGINS_PER_MINUTE, seconds);
            fraction = this.filesTotal > 0 ? this.filesBefore / (float) this.filesTotal : 0.0f;
        } else if (sendingRun == null) {
            text = tr("editor.saving");
            fraction = 1.0f;
        } else {
            CreatorSave.Progress progress = sendingRun.progress();
            ClientWorldUploads.Progress upload = progress.upload();
            int done = this.filesBefore;
            List<CreatorSave.Submission> submissions = sendingRun.plan().submissions();
            for (int i = 0; i < Math.min(progress.batch(), submissions.size()); i++) {
                done += submissions.get(i).files().size();
            }
            if (upload != null) {
                done += upload.filesDone();
            }
            done = Math.min(done, this.filesTotal);
            if (this.stopping) {
                text = tr("bulk.stopping");
            } else if (sendingRun.isPaused() && upload == null) {
                text = tr("bulk.paused", done, this.filesTotal);
            } else if (sendingRun.isPaused()) {
                text = tr("bulk.pausing");
            } else if (this.filesTotal == 0 || upload != null && upload.file().isEmpty()) {
                text = tr("bulk.saving_part", Math.min(progress.batch() + 1, progress.batches()), progress.batches());
            } else {
                String file = upload != null ? upload.file() : "";
                Component counted = tr("bulk.uploading", Math.min(done + 1, this.filesTotal), this.filesTotal);
                if (!file.isEmpty()) {
                    counted = tr("card.joined", counted, file);
                }
                Component eta = eta(done);
                text = eta != null ? tr("card.joined", counted, eta) : counted;
            }
            fraction = this.filesTotal > 0 ? done / (float) this.filesTotal
                    : progress.batches() > 0 ? progress.batch() / (float) progress.batches() : 1.0f;
        }
        // The text on top, and under it the bar with the server's limit beside it
        String shown = text.getString();
        Draw.text(graphics, this.font, shown, left, this.footerY + 1, room,
                this.phase == Phase.WAITING ? Theme.AMBER : Theme.TEXT_SOFT);
        if (this.font.width(shown) > room && inside(mouseX, mouseY, left, this.footerY, room, 10)) {
            UiTooltip.set(text);
        }
        String limit = tr("bulk.limit", WorldContentLimits.UPLOAD_BEGINS_PER_MINUTE).getString();
        int barWidth = Math.min(180, room / 2);
        Draw.progressBar(graphics, left, this.footerY + 14, barWidth, 4, fraction, Theme.TRACK, Theme.GREEN);
        Draw.text(graphics, this.font, limit, left + barWidth + 6, this.footerY + 12,
                Math.max(0, room - barWidth - 6), HEAD_TEXT);
    }

    // About how long the rest takes, from how fast the files of this send went so far
    @Nullable
    private Component eta(int done) {
        long elapsed = Util.getMillis() - this.startedAt;
        int sent = done - this.filesAtStart;
        int left = this.filesTotal - done;
        if (sent <= 0 || left <= 0 || elapsed < 1000) {
            return null;
        }
        long seconds = Math.max(1, elapsed * left / sent / 1000);
        return seconds < 60 ? tr("bulk.eta_seconds", seconds) : tr("bulk.eta_minutes", (seconds + 59) / 60);
    }

    @Override
    protected int statusY() {
        // Under the card, on the dimmed editor, where fitCard() left room for it
        return this.panelY + this.panelHeight + STATUS_GAP;
    }

    @Override
    protected int toastBottom() {
        return this.panelY >= 24 ? this.panelY - 4 : super.toastBottom();
    }

    // Input

    @Override
    protected boolean shortcut(UiInput.Key key) {
        if (key.enter() && this.phase == Phase.CHOOSING) {
            return this.addButton.press();
        }
        return false;
    }

    // Closing

    @Override
    public void onClose() {
        if (this.phase == Phase.SENDING || this.phase == Phase.WAITING) {
            hide();
        } else {
            leave();
        }
    }

    @Override
    public void removed() {
        super.removed();
        CreatorSave.Run sendingRun = this.run;
        if (sendingRun != null && sendingRun.isPaused()) {
            // Nothing but this card could resume it
            sendingRun.resume();
        }
        CreatorPreview.clear(PREVIEW_SLOT);
    }

    /**
     * The table of rows: a scrolling list drawn row by row, with one text field that moves onto
     * the name being typed. A click on a row's box leaves it out or puts it back, on its name
     * starts typing it, and on its size opens the sizes; Tab and Shift+Tab move through the name
     * and size cells, and on a size cell the left and right arrow keys change it. Nothing can be
     * changed while the figures are sent, and then each row says whether it was saved.
     */
    private final class Table extends UiScrollPane {
        static final int ROW_HEIGHT = 24;
        private static final int CELL = 20;
        private static final int COLUMN_GAP = 4;
        private static final int CHECK = 12;
        private static final int VIEW = CELL;
        // The machine screen's camera, as the figure grid draws figures
        private static final float CAMERA_YAW = 150.0f;
        private static final float CAMERA_X = 0.0f;
        private static final float CAMERA_Y = 15.0f;
        private static final float PLUSH_SCALE = 0.6f;
        private static final int ROW_ERROR = 0xFF1C0F0F;
        private static final int ROW_WARNING = 0xFF1B170C;
        private static final int ROW_HOVER = 0xFF181818;
        private static final int ROW_LINE = 0xFF1F1F1F;
        private static final int FIELD_EDGE = 0xFF7A7A7A;
        private static final int STATUS_READY = 0xFF9CFF8A;
        private static final int STATUS_WARNING = 0xFFFFCC66;
        private static final int STATUS_ERROR = 0xFFFF8A8A;

        // The columns' left edges and widths: box, view, files, name, type, size, status
        private final int[] columnX = new int[7];
        private final int[] columnWidth = new int[7];
        private final NameCell nameCell = new NameCell();
        @Nullable
        private FigureEntry entry;
        @Nullable
        private String entryCollectionId;
        // The row whose name is typed, or whose size has the keyboard
        @Nullable
        private Row typing;
        @Nullable
        private Row sizeFocus;

        Table() {
            add(this.nameCell);
            this.nameCell.setVisible(false);
        }

        /** Lays the columns out for the width and measures the rows. */
        void layout() {
            int width = contentWidth();
            int name = 84;
            int type = 44;
            int size = 36;
            int flexible = width - CHECK - VIEW - name - type - size - 6 * COLUMN_GAP;
            if (flexible < 120) {
                name = 64;
                type = 36;
                flexible = Math.max(40, width - CHECK - VIEW - name - type - size - 6 * COLUMN_GAP);
            }
            int files = flexible * 2 / 5;
            int[] widths = {CHECK, VIEW, files, name, type, size, flexible - files};
            int left = x;
            for (int i = 0; i < widths.length; i++) {
                this.columnX[i] = left;
                this.columnWidth[i] = widths[i];
                left += widths[i] + COLUMN_GAP;
            }
            setContentHeight(BulkAddScreen.this.plan.rows().size() * ROW_HEIGHT);
            List<Row> rows = BulkAddScreen.this.plan.rows();
            if (this.typing != null && !rows.contains(this.typing)) {
                stopEditing();
            }
            if (this.sizeFocus != null && !rows.contains(this.sizeFocus)) {
                this.sizeFocus = null;
            }
            placeNameCell();
        }

        private boolean editable() {
            return BulkAddScreen.this.phase == Phase.CHOOSING;
        }

        // The column titles above the table, with its outline
        void drawHead(GuiGraphics graphics) {
            Font font = BulkAddScreen.this.font;
            int headY = y - TABLE_HEAD + 2;
            String[] keys = {"", "bulk.column.view", "bulk.column.files", "bulk.column.name", "bulk.column.type",
                    "bulk.column.size", "bulk.column.status"};
            for (int i = 1; i < keys.length; i++) {
                Draw.text(graphics, font, tr(keys[i]).getString(), this.columnX[i], headY, this.columnWidth[i],
                        HEAD_TEXT);
            }
            graphics.fill(x, y - 1, x + width, y, 0xFF2C2C2C);
            Draw.outline(graphics, x - 1, y - TABLE_HEAD - 2, width + 2, height + TABLE_HEAD + 3, TABLE_EDGE);
            if (BulkAddScreen.this.plan.isEmpty()) {
                int lineY = y + Math.max(4, height / 2 - 10);
                for (String line : Draw.fit(font, tr("bulk.empty").getString(), width - 20, 2)) {
                    Draw.centred(graphics, font, line, x + width / 2, lineY, width - 20, Theme.MUTED);
                    lineY += Theme.LINE_HEIGHT;
                }
            }
        }

        @Override
        protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (this.typing != null && !CreatorScreen.hasKeyboard(this)) {
                // Another node took the keyboard: the name typed so far stays
                stopEditing();
            }
            if (this.sizeFocus != null && !CreatorScreen.hasKeyboard(this)) {
                this.sizeFocus = null;
            }
            List<Row> rows = BulkAddScreen.this.plan.rows();
            if (rows.isEmpty()) {
                super.drawContent(graphics, mouseX, mouseY, partialTick);
                return;
            }
            int first = Math.max(0, scrollY() / ROW_HEIGHT);
            int last = Math.min(rows.size() - 1, (scrollY() + height) / ROW_HEIGHT);
            int hovered = mouseX >= x && mouseX < x + contentWidth() && mouseY >= y
                    ? (mouseY - y) / ROW_HEIGHT : -1;
            Font font = BulkAddScreen.this.font;

            // Each row's ground, then the figures, then the text above them
            for (int i = first; i <= last; i++) {
                drawGround(graphics, rows.get(i), y + i * ROW_HEIGHT, i == hovered);
            }
            FigureCollection preview = CreatorPreview.collection(PREVIEW_SLOT);
            if (preview != null) {
                FigureEntry figures = entry(preview);
                figures.setPreviewScale(preview.isPlush() ? PLUSH_SCALE : 1.0f);
                for (int i = first; i <= last; i++) {
                    Row row = rows.get(i);
                    FigureDefinition shown = row.usable() ? preview.getFigure(previewId(row)).orElse(null) : null;
                    if (shown != null) {
                        UiViewport.drawFigure(graphics, figures, shown, this.columnX[1], y + i * ROW_HEIGHT + 2, CELL,
                                partialTick, !row.adds() && !sent(row));
                    }
                }
            }
            // The text goes above the figures: a later stratum from 1.21.6, depth before it
            GuiPose.flush(graphics);
            GuiPose.nextStratum(graphics);
            GuiPose.push(graphics);
            GuiPose.lift(graphics, 400.0f);
            try {
                for (int i = first; i <= last; i++) {
                    drawText(graphics, font, rows.get(i), y + i * ROW_HEIGHT, i == hovered, mouseX, mouseY);
                }
                super.drawContent(graphics, mouseX, mouseY, partialTick);
            } finally {
                GuiPose.pop(graphics);
            }
        }

        private FigureEntry entry(FigureCollection preview) {
            if (this.entry == null || !preview.getId().equals(this.entryCollectionId)) {
                this.entry = new FigureEntry(List.of(), preview.getId());
                this.entry.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, CAMERA_X, CAMERA_Y, 0.0f);
                this.entryCollectionId = preview.getId();
            }
            return this.entry;
        }

        // Whether the row's figure was added and sent, so it shows in colour whatever its state now
        private boolean sent(Row row) {
            return BulkAddScreen.this.phase != Phase.CHOOSING && BulkAddScreen.this.rowIds.containsKey(row);
        }

        private void drawGround(GuiGraphics graphics, Row row, int top, boolean hovered) {
            int right = x + contentWidth();
            int ground = row.state() == BulkPlan.State.ERROR && !sent(row) ? ROW_ERROR
                    : row.state() == BulkPlan.State.WARNING ? ROW_WARNING : hovered ? ROW_HOVER : 0;
            if (ground != 0) {
                graphics.fill(x, top, right, top + ROW_HEIGHT, ground);
            }
            graphics.fill(x, top + ROW_HEIGHT - 1, right, top + ROW_HEIGHT, ROW_LINE);
            graphics.fill(this.columnX[1], top + 2, this.columnX[1] + CELL, top + 2 + CELL, 0xFF161616);
        }

        private void drawText(GuiGraphics graphics, Font font, Row row, int top, boolean hovered, int mouseX,
                              int mouseY) {
            int textY = top + (ROW_HEIGHT - 8) / 2;
            boolean editable = editable() && row.usable();

            // The box: ticked while the row is added
            int boxX = this.columnX[0] + 1;
            int boxY = top + (ROW_HEIGHT - UiCheckbox.BOX) / 2;
            boolean ticked = row.adds() || sent(row);
            Draw.panel(graphics, boxX, boxY, UiCheckbox.BOX, UiCheckbox.BOX, 0xFF000000,
                    editable ? Theme.FIELD_OUTLINE : Theme.CONTROL_OUTLINE);
            if (ticked) {
                graphics.fill(boxX + 2, boxY + 2, boxX + UiCheckbox.BOX - 2, boxY + UiCheckbox.BOX - 2,
                        editable ? Theme.GOLD : Theme.CONTROL_OUTLINE);
            }
            if (inside(mouseX, mouseY, boxX - 1, top, CHECK + 2, ROW_HEIGHT)) {
                UiTooltip.set(row.usable() ? tr("bulk.include_tip") : row.status());
            }
            if (!row.usable()) {
                Draw.centred(graphics, font, "?", this.columnX[1] + CELL / 2, top + 8, CELL, Theme.FAINT);
            }

            // The files, cut, with every name in the tooltip
            String files = row.files();
            String cutFiles = Draw.cut(font, files, this.columnWidth[2]);
            graphics.drawString(font, cutFiles, this.columnX[2], textY, 0xFFC8C8C8, false);
            if (!cutFiles.equals(files) && inside(mouseX, mouseY, this.columnX[2], top, this.columnWidth[2], ROW_HEIGHT)) {
                UiTooltip.set(Component.literal(files));
            }

            // The name: a field to type in for a usable row, while the text field is elsewhere
            int nameX = this.columnX[3];
            int nameWidth = this.columnWidth[3];
            if (row != this.typing) {
                if (row.usable()) {
                    Draw.panel(graphics, nameX, top + 3, nameWidth, ROW_HEIGHT - 6, 0xFF000000,
                            editable && inside(mouseX, mouseY, nameX, top, nameWidth, ROW_HEIGHT) ? Theme.TEXT
                                    : FIELD_EDGE);
                    Draw.text(graphics, font, row.name(), nameX + 4, textY, nameWidth - 8,
                            editable ? Theme.TEXT : Theme.MUTED);
                } else {
                    graphics.drawString(font, "—", nameX + 4, textY, Theme.FAINT, false);
                }
            }

            // The kind, and the size with its arrow
            Kind kind = row.kind();
            String type = kind == null ? "—" : tr(kind == Kind.SKIN ? "bulk.type.skin" : "bulk.type.model").getString();
            Draw.text(graphics, font, type, this.columnX[4], textY, this.columnWidth[4], 0xFFDDDDDD);
            int sizeX = this.columnX[5];
            int sizeWidth = this.columnWidth[5];
            if (row.usable()) {
                boolean focused = row == this.sizeFocus;
                boolean over = editable && inside(mouseX, mouseY, sizeX, top, sizeWidth, ROW_HEIGHT);
                Draw.panel(graphics, sizeX, top + 4, sizeWidth, ROW_HEIGHT - 8, Theme.CONTROL,
                        focused ? Theme.GOLD : over ? 0xFF777777 : Theme.CONTROL_OUTLINE);
                String size = FigureCard.scaleText(row.scale());
                graphics.drawString(font, size, sizeX + 4, textY, editable ? Theme.CONTROL_TEXT : Theme.MUTED, false);
                int arrowX = sizeX + sizeWidth - 4 - font.width("▾");
                if (editable && sizeX + 4 + font.width(size) + 2 <= arrowX) {
                    graphics.drawString(font, "▾", arrowX, textY, Theme.CONTROL_TEXT, false);
                }
            } else {
                graphics.drawString(font, "—", sizeX + 4, textY, Theme.FAINT, false);
            }

            // The state, with "Remove" over its end under the pointer
            Component status = statusOf(row);
            int statusX = this.columnX[6];
            int statusWidth = this.columnWidth[6];
            int removeWidth = font.width(tr("bulk.remove")) + 6;
            boolean withRemove = hovered && editable() && statusWidth > removeWidth + 30;
            int statusRoom = withRemove ? statusWidth - removeWidth : statusWidth;
            String statusText = status.getString();
            String cutStatus = Draw.cut(font, statusText, statusRoom);
            graphics.drawString(font, cutStatus, statusX, textY, statusColour(row), false);
            if (!cutStatus.equals(statusText) && inside(mouseX, mouseY, statusX, top, statusRoom, ROW_HEIGHT)) {
                UiTooltip.set(status);
            }
            if (withRemove) {
                int removeX = statusX + statusWidth - removeWidth + 6;
                boolean over = inside(mouseX, mouseY, removeX - 3, top, removeWidth, ROW_HEIGHT);
                graphics.drawString(font, tr("bulk.remove"), removeX, textY, over ? 0xFFFFC8C8 : 0xFFFF9A9A, false);
                if (over) {
                    graphics.fill(removeX, textY + 9, removeX + removeWidth - 6, textY + 10, 0xFFFFC8C8);
                }
            }
        }

        // While the figures are sent, an added row says how far its figure is
        private Component statusOf(Row row) {
            if (!sent(row)) {
                return row.status();
            }
            String figureId = BulkAddScreen.this.rowIds.get(row);
            if (BulkAddScreen.this.savedIds.contains(figureId)) {
                return tr("bulk.status.saved");
            }
            if (BulkAddScreen.this.phase == Phase.FAILED || BulkAddScreen.this.phase == Phase.STOPPED) {
                return tr("bulk.status.not_saved");
            }
            // A batch before the one being sent was saved; the one being sent is uploading
            // unless the pause holds it
            CreatorSave.Run sendingRun = BulkAddScreen.this.run;
            Integer batch = BulkAddScreen.this.batchOf.get(figureId);
            if (sendingRun == null || batch == null) {
                return tr("bulk.status.waiting");
            }
            CreatorSave.Progress progress = sendingRun.progress();
            if (batch < progress.batch()) {
                return tr("bulk.status.saved");
            }
            boolean held = sendingRun.isPaused() && progress.upload() == null;
            return tr(batch == progress.batch() && !held ? "bulk.status.sending" : "bulk.status.waiting");
        }

        private int statusColour(Row row) {
            if (sent(row)) {
                String figureId = BulkAddScreen.this.rowIds.get(row);
                CreatorSave.Run sendingRun = BulkAddScreen.this.run;
                Integer batch = BulkAddScreen.this.batchOf.get(figureId);
                boolean saved = BulkAddScreen.this.savedIds.contains(figureId)
                        || sendingRun != null && batch != null && batch < sendingRun.progress().batch();
                return saved ? STATUS_READY
                        : BulkAddScreen.this.phase == Phase.FAILED || BulkAddScreen.this.phase == Phase.STOPPED
                        ? STATUS_WARNING : Theme.TEXT_SOFT;
            }
            return switch (row.state()) {
                case READY -> STATUS_READY;
                case WARNING -> STATUS_WARNING;
                case ERROR -> STATUS_ERROR;
                case LEFT_OUT -> Theme.MUTED;
            };
        }

        // Clicks

        @Override
        protected boolean clickContent(UiInput.Click click) {
            if (this.nameCell.visible() && super.clickContent(click)) {
                return true;
            }
            List<Row> rows = BulkAddScreen.this.plan.rows();
            int index = click.y() >= y ? (int) ((click.y() - y) / ROW_HEIGHT) : -1;
            stopEditing();
            this.sizeFocus = null;
            if (index < 0 || index >= rows.size() || !click.left() || !editable()) {
                return false;
            }
            Row row = rows.get(index);
            int top = y + index * ROW_HEIGHT;
            int mouseX = (int) click.x();
            Font font = BulkAddScreen.this.font;
            int removeWidth = font.width(tr("bulk.remove")) + 6;
            int removeX = this.columnX[6] + this.columnWidth[6] - removeWidth + 3;
            if (this.columnWidth[6] > removeWidth + 30 && mouseX >= removeX && mouseX < x + contentWidth()) {
                BulkAddScreen.this.plan.remove(row);
                rowsChanged(true);
                return true;
            }
            if (!row.usable()) {
                return true;
            }
            if (mouseX >= this.columnX[0] && mouseX < this.columnX[1]) {
                BulkAddScreen.this.plan.include(row, !row.included());
                rowsChanged(false);
            } else if (mouseX >= this.columnX[3] && mouseX < this.columnX[3] + this.columnWidth[3]) {
                startEditing(row);
                UiInput.forwardClick(this.nameCell.field, click);
            } else if (mouseX >= this.columnX[5] && mouseX < this.columnX[5] + this.columnWidth[5]) {
                this.sizeFocus = row;
                openSizes(row, top);
            }
            return true;
        }

        // Typing names and sizes

        private void startEditing(Row row) {
            this.typing = row;
            this.sizeFocus = null;
            this.nameCell.open(row.name());
            placeNameCell();
            int index = BulkAddScreen.this.plan.rows().indexOf(row);
            reveal(y + index * ROW_HEIGHT, y + (index + 1) * ROW_HEIGHT);
        }

        void stopEditing() {
            this.typing = null;
            this.nameCell.close();
        }

        private void placeNameCell() {
            Row row = this.typing;
            int index = row != null ? BulkAddScreen.this.plan.rows().indexOf(row) : -1;
            if (index < 0) {
                this.nameCell.setVisible(false);
                return;
            }
            this.nameCell.at(this.columnX[3], y + index * ROW_HEIGHT + 2, this.columnWidth[3], NameCell.HEIGHT);
            this.nameCell.setVisible(true);
        }

        private void renamed(Row row, String name) {
            BulkAddScreen.this.plan.rename(row, name);
            rowsChanged(false);
        }

        private void openSizes(Row row, int top) {
            UiMenu menu = new UiMenu();
            List<Float> scales = WorldCollectionRecord.SCALES;
            for (int i = 0; i < scales.size(); i++) {
                float scale = scales.get(i);
                menu.item(UiSizeSlider.label(i), () -> resize(row, scale));
            }
            menu.highlight(Math.max(0, WorldCollectionRecord.scaleIndex(row.scale())));
            openMenu(menu, new UiNode.Rect(this.columnX[5], top + 4 - scrollY(), this.columnWidth[5], ROW_HEIGHT - 8));
        }

        private void resize(Row row, float scale) {
            BulkAddScreen.this.plan.resize(row, scale);
            rowsChanged(true);
        }

        /** Moves the keyboard to the next name or size cell, or back with Shift. */
        private void move(boolean back) {
            List<Row> usable = new ArrayList<>();
            for (Row row : BulkAddScreen.this.plan.rows()) {
                if (row.usable()) {
                    usable.add(row);
                }
            }
            // Cells in order: each usable row's name, then its size
            int cell;
            if (this.typing != null) {
                cell = usable.indexOf(this.typing) * 2;
            } else if (this.sizeFocus != null) {
                cell = usable.indexOf(this.sizeFocus) * 2 + 1;
            } else {
                cell = back ? usable.size() * 2 : -1;
            }
            int next = cell + (back ? -1 : 1);
            stopEditing();
            this.sizeFocus = null;
            if (next < 0 || next >= usable.size() * 2) {
                return;
            }
            Row row = usable.get(next / 2);
            if (next % 2 == 0) {
                startEditing(row);
            } else {
                this.sizeFocus = row;
                int index = BulkAddScreen.this.plan.rows().indexOf(row);
                reveal(y + index * ROW_HEIGHT, y + (index + 1) * ROW_HEIGHT);
            }
        }

        @Override
        public boolean key(UiInput.Key key) {
            if (!editable()) {
                return super.key(key);
            }
            if (key.is(InputConstants.KEY_TAB) && (this.typing != null || this.sizeFocus != null)) {
                move(key.shift());
                return true;
            }
            Row typed = this.typing;
            if (typed != null) {
                if (key.escape() || key.enter()) {
                    stopEditing();
                    return true;
                }
                boolean used = UiInput.forwardKey(this.nameCell.field, key);
                // Every key is the field's while it types, except a Ctrl shortcut it leaves
                return used || !key.ctrl();
            }
            Row sized = this.sizeFocus;
            if (sized != null) {
                int index = WorldCollectionRecord.scaleIndex(sized.scale());
                if (key.escape()) {
                    this.sizeFocus = null;
                    return true;
                }
                if (key.is(InputConstants.KEY_LEFT) || key.is(InputConstants.KEY_RIGHT)) {
                    int next = Math.max(0, Math.min(WorldCollectionRecord.SCALES.size() - 1,
                            index + (key.is(InputConstants.KEY_LEFT) ? -1 : 1)));
                    if (next != index) {
                        resize(sized, WorldCollectionRecord.SCALES.get(next));
                    }
                    return true;
                }
                if (key.enter() || key.is(InputConstants.KEY_SPACE)) {
                    openSizes(sized, y + BulkAddScreen.this.plan.rows().indexOf(sized) * ROW_HEIGHT);
                    return true;
                }
            }
            return super.key(key);
        }

        @Override
        public boolean typed(String text) {
            if (this.typing != null && editable()) {
                UiInput.forwardTyped(this.nameCell.field, text);
                return true;
            }
            return false;
        }

        /**
         * The text field that moves onto the name being typed. It is drawn and handed input by
         * the table, which tells the row each change.
         */
        private final class NameCell extends UiNode.Box {
            static final int HEIGHT = 20;
            final EditBox field;
            private boolean syncing;
            private boolean pressed;

            NameCell() {
                this.height = HEIGHT;
                this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 40, 20, tr("bulk.column.name"));
                this.field.setMaxLength(WorldContentLimits.MAX_NAME_LENGTH);
                this.field.setValue("");
                this.field.setResponder(this::changed);
            }

            void open(String name) {
                this.syncing = true;
                this.field.setValue(name);
                this.syncing = false;
                this.field.setFocused(true);
            }

            void close() {
                this.field.setFocused(false);
                this.pressed = false;
                setVisible(false);
            }

            private void changed(String text) {
                Row row = Table.this.typing;
                if (!this.syncing && row != null) {
                    Table.this.renamed(row, text);
                }
            }

            @Override
            public Box at(int x, int y, int width, int height) {
                super.at(x, y, width, height);
                this.field.setX(x);
                this.field.setY(y);
                this.field.setWidth(Math.max(16, width));
                return this;
            }

            @Override
            public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                if (visible()) {
                    this.field.render(graphics, mouseX, mouseY, partialTick);
                }
            }

            @Override
            public boolean click(UiInput.Click click) {
                this.pressed = false;
                if (!isMouseOver(click.x(), click.y())) {
                    return false;
                }
                this.pressed = click.left();
                UiInput.forwardClick(this.field, click);
                return true;
            }

            @Override
            public boolean drag(UiInput.Click click, double dragX, double dragY) {
                return this.pressed && UiInput.forwardDrag(this.field, click, dragX, dragY);
            }

            @Override
            public boolean release(UiInput.Click click) {
                boolean used = this.pressed;
                this.pressed = false;
                if (used) {
                    UiInput.forwardRelease(this.field, click);
                }
                return used;
            }
        }
    }
}
