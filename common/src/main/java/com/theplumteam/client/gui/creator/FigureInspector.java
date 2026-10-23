package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiFigureView;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.client.worldcontent.OwnSkin;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The figure inspector: the side panel of the editor's Figures tab, which shows the figure
 * selected in the grid and edits it in place.
 *
 * <p>At the top, "‹ 3 of 8 ›" steps through the cards the grid shows, as the left and right
 * arrow keys do while no field has the keyboard. Under it a live view shows the figure loose
 * beside the default figure at 1x, in its box, or in a cell of the machine's grid, discovered or
 * not, standing or sitting, and turns it when dragged; it draws the editor's preview of the
 * draft, so a file picked here shows before anything is saved. Sitting is disabled for a model
 * whose poses have no sitting one; a skin figure uses the shared poses, which always have it.
 *
 * <p>The name field renames the figure. A figure never saved takes the id its name makes, unique
 * in the collection, which the top line shows and which is fixed once the figure is saved. Under
 * the field, the size slider, the choice between a Minecraft skin and a Blockbench model, and one
 * drop zone for each file the figure's kind needs, each with its checks and the first that fails
 * in red, scroll as one, with "Use my skin" under a skin figure's skin (see {@link OwnSkin}) and
 * the rarity, skin variants and box face under the files (see {@link FigureExtrasPanel}). The
 * bottom row retires the figure from the draw or restores it, discards
 * a figure never saved, deletes a draft collection's figure for good, duplicates it, and saves
 * this figure alone; "Save figure" lists what is still wrong while it cannot be used.
 *
 * <p>Every change goes to the editor's draft as the grid's own do, and the editor's Undo takes
 * it back. The panel lives as long as the page and keeps its nodes across rebuilds.
 */
final class FigureInspector {
    private static final int PAD = 8;
    private static final int GAP = 5;
    private static final int LABEL = 11;
    private static final int FIELD = 20;
    private static final int ROW_GAP = 5;
    private static final int MIN_VIEW = 72;
    // A short panel, as on the smallest canvas, lets the view shrink to this, a quarter of its height
    private static final int SMALLEST_VIEW = 44;
    private static final int MAX_VIEW = 150;
    // The room under the view kept for the sections: the size and the type, and a first file
    private static final int MIN_SECTIONS = 88;
    // The darker face of a dialog's sidebar, as Quick Skin's settings draw it
    private static final int PANEL_FILL = Surfaces.PANEL_FACE;
    private static final int PANEL_OUTLINE = Surfaces.BORDER_DISABLED;
    private static final int LABEL_COLOUR = 0xFFE6E6E6;
    private static final int HELP = 0xFFA8A8A8;

    private final CreatorEditorScreen editor;
    private final FiguresPage page;
    private final UiButton previous;
    private final UiButton next;
    private final UiFigureView view = new UiFigureView();
    private final UiScrollPane sections = new UiScrollPane();
    private final Label sizeLabel;
    private final UiSizeSlider size;
    private final Label typeLabel;
    private final KindCards kinds = new KindCards();
    private final Label filesLabel;
    private final SharedPoses sharedPoses = new SharedPoses();
    private final UiButton ownSkin;
    private final FigureExtrasPanel extras;
    // Set while "Use my skin" reads the skin, so a second press waits for it
    private boolean readingOwnSkin;
    // The figure the view last showed, whose variant it keeps showing until another is selected
    @Nullable
    private FigureDraft viewFigure;
    private final UiButton retire;
    private final UiButton delete;
    private final UiButton duplicate;
    private final UiButton save;
    @Nullable
    private EditBox nameBox;
    // Set while the inspector itself puts a name in the field, which its responder then ignores
    private boolean settingName;

    // The figure shown and where its card is among those the grid shows; null while none or several are selected
    @Nullable
    private FigureDraft figure;
    private int position;
    private int count;
    private int selectedCount;

    // The file zones of the figure shown. They are made again for another figure or kind, so a
    // file still being read when the operator moves on goes to the figure it was picked for; a
    // rename, which changes a new figure's id, keeps them.
    @Nullable
    private FigureDraft zonesFigure;
    @Nullable
    private Kind zonesKind;
    // Counts the zone sets made, so the sections are laid out again for a new one
    private int zonesMade;
    private List<FileSlot> slots = List.of();
    private int slotLabelWidth;
    // The zones and bounds the sections were last laid out for. Laying them out again takes the
    // press and the keyboard from their children, so it waits for the figure, its kind or the
    // room to change, and a dragged size slider goes on working.
    @Nullable
    private String laidOut;

    // Where build() and layout() put what drawContents() draws
    private UiNode.Rect area = UiNode.Rect.EMPTY;
    private int fieldY;
    // The column of the labels left of the name field, the size and the type
    private int labelColumn;

    FigureInspector(CreatorEditorScreen editor, FiguresPage page) {
        this.editor = editor;
        this.page = page;
        this.previous = new UiButton(Component.literal("‹"), UiButton.Style.SECONDARY, () -> page.step(-1));
        this.previous.setTooltip(tr("inspector.previous"));
        this.next = new UiButton(Component.literal("›"), UiButton.Style.SECONDARY, () -> page.step(1));
        this.next.setTooltip(tr("inspector.next"));
        this.view.setFigure(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT), null);
        // As in the mockup, the inspector's view has no "Rotate" or "Light", leaving its room to the figure
        this.view.setButtons(false);

        this.sizeLabel = new Label(() -> tr("inspector.size"), () -> changed("scale"));
        this.size = new UiSizeSlider(WorldCollectionRecord.scaleIndex(1.0f), this::resize);
        this.typeLabel = new Label(() -> tr("inspector.type"), () -> changed("kind"));
        this.filesLabel = Label.heading(() -> tr("inspector.files"),
                () -> changed("texture") || changed("model") || changed("animation"));
        this.ownSkin = new UiButton(tr("inspector.use_my_skin"), UiButton.Style.SECONDARY, this::useOwnSkin);
        this.ownSkin.setTooltip(tr("inspector.use_my_skin_tip"));
        this.extras = new FigureExtrasPanel(editor, this, this.view);

        this.retire = new UiButton(tr("figures.retire_one"), UiButton.Style.DANGER_TEXT, page::toggleRetired);
        this.delete = new UiButton(tr("inspector.delete"), UiButton.Style.DANGER_TEXT, this::delete);
        this.duplicate = new UiButton(tr("figures.duplicate"), UiButton.Style.SECONDARY, page::duplicateSelected);
        this.duplicate.at(0, 0, 0, UiButton.SMALL_HEIGHT);
        this.save = new UiButton(tr("inspector.save"), UiButton.Style.GREEN, () -> {
            FigureDraft shown = this.figure;
            if (shown != null) {
                editor.saveFigure(shown.id());
            }
        });
        this.save.setTooltip(tr("inspector.save_tip"));
        this.save.at(0, 0, 0, UiButton.SMALL_HEIGHT);
    }

    // Whether a field of the figure shown is among the editor's unsaved changes
    private boolean changed(String field) {
        FigureDraft shown = this.figure;
        return shown != null && this.editor.isChanged(CreatorDraft.FIGURE + shown.id() + "/" + field);
    }

    // Building

    /** Adds the inspector's nodes and name field for the panel it fills. */
    void build(UiNode.Rect panel) {
        this.area = panel;
        this.laidOut = null;
        Font font = Minecraft.getInstance().font;
        int left = panel.x() + PAD;
        int width = Math.max(0, panel.width() - 2 * PAD);
        this.labelColumn = Math.max(font.width(tr("inspector.name")),
                Math.max(font.width(tr("inspector.size")), font.width(tr("inspector.type")))) + 14;
        int y = panel.y() + PAD + UiButton.SMALL_HEIGHT + GAP;

        // The view takes what the fields, the footer and the first sections leave; the footer may
        // need a row of links above its buttons
        int fixed = (y - panel.y()) + ROW_GAP + FIELD + ROW_GAP
                + ROW_GAP + 10 + ROW_GAP + UiButton.SMALL_HEIGHT + PAD;
        int minView = Math.max(SMALLEST_VIEW, Math.min(MIN_VIEW, panel.height() / 4));
        int viewHeight = Math.max(minView, Math.min(MAX_VIEW, panel.height() - fixed - MIN_SECTIONS));
        this.view.at(left, y, width, viewHeight);
        y += viewHeight + ROW_GAP;

        this.fieldY = y;
        FigureDraft shown = this.figure;
        this.nameBox = this.editor.field("figure_name", left + this.labelColumn, y,
                Math.max(20, width - this.labelColumn), WorldContentLimits.MAX_NAME_LENGTH, tr("inspector.name_hint"),
                shown != null ? shown.name() : "", this::renamed);
        this.slotLabelWidth = 0;
        for (FilePicker.Slot slot : FilePicker.Slot.values()) {
            this.slotLabelWidth = Math.max(this.slotLabelWidth, font.width(UiDropZone.defaultLabel(slot)) + 8);
        }
        this.slotLabelWidth = Math.max(this.slotLabelWidth, font.width(tr("inspector.poses")) + 8);
        this.slotLabelWidth = Math.min(this.slotLabelWidth, Math.max(30, width / 2));

        for (UiNode node : List.of(this.previous, this.next, this.view, this.sections, this.retire, this.delete,
                this.duplicate, this.save)) {
            this.editor.add(node);
        }
        layout();
    }

    /**
     * Shows a figure, or nothing while none or several cards are selected.
     *
     * @param shown    the one figure selected, or null
     * @param index    where its card is among the shown cards, from 0
     * @param shownOf  how many cards the grid shows
     * @param selected how many cards are selected
     */
    void show(@Nullable FigureDraft shown, int index, int shownOf, int selected) {
        this.figure = shown;
        this.position = index;
        this.count = shownOf;
        this.selectedCount = selected;
        boolean one = shown != null;
        for (UiNode.Box node : List.of(this.previous, this.next, this.view, this.sections, this.duplicate, this.save)) {
            node.setVisible(one);
        }
        EditBox box = this.nameBox;
        if (box != null) {
            box.visible = one;
            if (!one && this.editor.getFocused() == box) {
                this.editor.setFocused(null);
            }
        }
        if (shown == null) {
            this.retire.setVisible(false);
            this.delete.setVisible(false);
            return;
        }
        this.view.setFigure(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT), shown.id());
        if (shown != this.viewFigure && (this.viewFigure == null || !this.viewFigure.id().equals(shown.id()))) {
            // Another figure starts in its own skin; an undo, which puts a copy in its place, keeps the variant
            this.view.setSkinIndex(0);
        }
        this.viewFigure = shown;
        this.view.setGreyed(!shown.enabled());
        this.view.setCaption(shown.isDirty() ? tr("inspector.preview_note") : null);
        this.view.setEmptyText(tr(shown.isComplete() ? "inspector.no_preview" : "inspector.needs_files"));
        if (box != null && !box.getValue().equals(shown.name())) {
            this.settingName = true;
            try {
                box.setValue(shown.name());
            } finally {
                this.settingName = false;
            }
        }
        this.size.select(WorldCollectionRecord.scaleIndex(shown.scale()));
        this.previous.disable(shownOf > 1 ? null : tr("inspector.only_one"));
        this.next.disable(shownOf > 1 ? null : tr("inspector.only_one"));
        refreshFooter(shown);
        refreshZones(shown);
        this.extras.show(shown);
        this.ownSkin.disable(this.readingOwnSkin ? tr("inspector.use_my_skin_reading") : null);
        layout();
    }

    /** Gives the name field the keyboard, as a double click or Enter on a card does. */
    void focusName() {
        EditBox box = this.nameBox;
        if (box != null && box.visible) {
            this.editor.focusNode(null);
            this.editor.setFocused(box);
        }
    }

    private void refreshFooter(FigureDraft shown) {
        CreatorDraft draft = this.editor.draft();
        boolean retired = !shown.enabled();
        if (shown.isNew()) {
            // Never saved: it just goes, and there is nothing to retire
            this.retire.setVisible(false);
            this.delete.setLabel(tr("figures.discard"));
            this.delete.setTooltip(tr("figures.discard_tip"));
            this.delete.setVisible(true);
        } else {
            this.retire.setVisible(true);
            this.retire.setLabel(tr(retired ? "figures.restore_one" : "figures.retire_one"));
            // Retiring is the red link, as in the mockup; bringing a figure back is an ordinary one
            this.retire.setStyle(retired ? UiButton.Style.LINK : UiButton.Style.DANGER_TEXT);
            this.retire.setTooltip(retired ? null : tr("figures.retire_tip"));
            // Players own boxes of a published collection's figures, which therefore only retire
            this.delete.setLabel(tr("inspector.delete"));
            this.delete.setTooltip(tr("inspector.delete_tip"));
            this.delete.setVisible(draft.isDraftCollection());
        }
        this.duplicate.disable(draft.isFull() ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null);
        this.save.disable(saveProblem(shown));
    }

    /**
     * Why "Save figure" cannot be used, or null when it can: a save already on its way, the
     * list of what is fine and what is wrong while anything is, or no change to save.
     */
    @Nullable
    private Component saveProblem(FigureDraft shown) {
        Component sending = this.editor.figureSendProblem();
        if (sending != null) {
            return sending;
        }
        List<String> marks = new ArrayList<>();
        boolean skin = shown.kind() == Kind.SKIN;
        boolean ready = mark(marks, !shown.name().isBlank() && WorldCollectionRecord.isPlainText(shown.name()),
                "inspector.need.name");
        BlobInfo model = shown.model() != null ? shown.model().info() : null;
        ready &= mark(marks, fileOk(shown.texture(),
                info -> skin ? FileChecks.skin(info) : FileChecks.modelTexture(info, model)),
                skin ? "inspector.need.skin" : "inspector.need.texture");
        if (!skin) {
            ready &= mark(marks, fileOk(shown.model(), FileChecks::geo), "inspector.need.model");
            if (shown.animation() != null) {
                ready &= mark(marks, fileOk(shown.animation(), FileChecks::animation), "inspector.need.poses");
            }
        }
        if (!shown.variants().isEmpty()) {
            Function<BlobInfo, List<FileChecks.Check>> variantChecks = FigureExtrasPanel.textureChecks(shown.kind(), model);
            boolean variantsOk = true;
            for (CreatorDraft.VariantDraft variant : shown.variants()) {
                variantsOk &= fileOk(variant.texture(), variantChecks);
            }
            ready &= mark(marks, variantsOk, "inspector.need.variants");
        }
        WorldCollectionRecord.BoxFace face = shown.boxFace();
        if (!skin && face != null && face.show() && face.uv() != null) {
            BlobInfo texture = shown.texture() != null ? shown.texture().info() : null;
            ready &= mark(marks, texture == null || FigureExtrasPanel.fits(face.uv(), texture), "inspector.need.face");
        }
        if (!ready) {
            return Component.literal(String.join(" · ", marks));
        }
        return shown.isDirty() ? null : tr("inspector.nothing_to_save");
    }

    private static boolean mark(List<String> marks, boolean ok, String key) {
        marks.add((ok ? "✔ " : "✖ ") + tr(key).getString());
        return ok;
    }

    // A file the figure has, that the server still holds when it is stored there, and that passes its checks
    private static boolean fileOk(@Nullable FileRef file, Function<BlobInfo, List<FileChecks.Check>> checks) {
        if (file == null) {
            return false;
        }
        if (file.local() == null && ClientWorldCollections.isMissingOnServer(file.sha())) {
            return false;
        }
        BlobInfo info = file.info();
        // A stored file the server did not describe is still its own to check
        return info == null || FileChecks.allOk(checks.apply(info));
    }

    // The file zones

    /** One drop zone of the figure shown, and what it shows now. */
    private static final class FileSlot {
        final FilePicker.Slot slot;
        final UiDropZone zone;
        @Nullable
        String shown;

        FileSlot(FilePicker.Slot slot, UiDropZone zone) {
            this.slot = slot;
            this.zone = zone;
        }
    }

    private void refreshZones(FigureDraft shown) {
        if (shown != this.zonesFigure || shown.kind() != this.zonesKind) {
            this.zonesFigure = shown;
            this.zonesKind = shown.kind();
            this.zonesMade++;
            String figureId = shown.id();
            List<FileSlot> made = new ArrayList<>();
            if (shown.kind() == Kind.SKIN) {
                made.add(slot(shown, figureId, FilePicker.Slot.SKIN));
            } else {
                made.add(slot(shown, figureId, FilePicker.Slot.MODEL));
                made.add(slot(shown, figureId, FilePicker.Slot.MODEL_TEXTURE));
                FileSlot poses = slot(shown, figureId, FilePicker.Slot.ANIMATION);
                poses.zone.help(tr("inspector.poses_help")).onRemove(() -> removePoses(shown, figureId));
                made.add(poses);
            }
            this.slots = made;
        }
        BlobInfo model = shown.model() != null ? shown.model().info() : null;
        for (FileSlot slot : this.slots) {
            switch (slot.slot) {
                case SKIN -> showIn(slot, shown.texture(), "", FileChecks::skin);
                case MODEL -> showIn(slot, shown.model(), "", FileChecks::geo);
                case MODEL_TEXTURE -> {
                    // The texture's proportions are checked against the model the figure has now
                    Function<BlobInfo, List<FileChecks.Check>> checks = info -> FileChecks.modelTexture(info, model);
                    slot.zone.checks(checks);
                    showIn(slot, shown.texture(), shown.model() != null ? shown.model().sha() : "", checks);
                }
                case ANIMATION -> showIn(slot, shown.animation(), "", FileChecks::animation);
                default -> {
                }
            }
        }
    }

    private FileSlot slot(FigureDraft figure, String figureId, FilePicker.Slot slot) {
        UiDropZone zone = UiDropZone.single(this.editor, slot, file -> picked(figure, figureId, slot, file))
                .labelWidth(this.slotLabelWidth);
        zone.at(0, 0, 0, UiDropZone.COMPACT_HEIGHT);
        return new FileSlot(slot, zone);
    }

    /**
     * Shows a file in its zone unless the zone shows it already, as against the same model and
     * with the same news from the server, so a refusal message the zone shows stays. A local file
     * shows its own name, a stored one a plain label; a file the server lost or could not load
     * says so, and so does the first check a file fails, which a narrow zone would otherwise
     * leave to its tooltip.
     */
    private static void showIn(FileSlot slot, @Nullable FileRef ref, String against,
                               Function<BlobInfo, List<FileChecks.Check>> checks) {
        // A sync may describe a stored file later, or report it lost or broken, under the same sha
        String key = ref == null ? "/" + against
                : ref.sha() + "/" + against + "/" + (ref.info() != null) + "/"
                + ClientWorldCollections.isMissingOnServer(ref.sha()) + "/"
                + ClientWorldCollections.failedToLoad(ref.sha());
        if (key.equals(slot.shown)) {
            return;
        }
        slot.shown = key;
        UiDropZone zone = slot.zone;
        if (ref == null) {
            zone.showEmpty();
            return;
        }
        BlobInfo info = ref.info();
        if (ref.local() != null) {
            zone.showFile(ref.local());
        } else if (info == null) {
            zone.showEmpty();
            zone.showError(tr("file_unknown"));
            return;
        } else {
            zone.showFile(tr("identity.stored_file").getString(), ref.sha(), info);
            if (ClientWorldCollections.isMissingOnServer(ref.sha())) {
                zone.showError(tr("file_missing"));
                return;
            }
            if (ClientWorldCollections.failedToLoad(ref.sha())) {
                zone.showError(tr("figures.problem.failed", UiDropZone.slotName(slot.slot)));
                return;
            }
        }
        for (FileChecks.Check check : checks.apply(info)) {
            if (!check.ok()) {
                zone.showError(check.text());
                return;
            }
        }
    }

    /**
     * The figure a zone was made for as the draft holds it now: the same figure, though a rename
     * gave it another id, or, after an undo put a copy of it in its place, the figure with its id.
     */
    @Nullable
    FigureDraft live(FigureDraft figure, String figureId) {
        CreatorDraft draft = this.editor.draft();
        for (FigureDraft each : draft.figures()) {
            if (each == figure) {
                return each;
            }
        }
        return draft.figure(figureId);
    }

    // A file picked for a figure, which may no longer be the one shown
    private void picked(FigureDraft figure, String figureId, FilePicker.Slot slot, FilePicker.LocalFile file) {
        CreatorDraft draft = this.editor.draft();
        FigureDraft target = live(figure, figureId);
        if (target == null) {
            return;
        }
        boolean skin = target.kind() == Kind.SKIN;
        boolean fits = switch (slot) {
            case SKIN -> skin;
            case MODEL, MODEL_TEXTURE, ANIMATION -> !skin;
            default -> false;
        };
        if (!fits) {
            // The figure changed kind while the file was read
            return;
        }
        // Each file is an undo step of its own, even a second one for the same slot
        draft.checkpoint();
        FileRef ref = FileRef.of(file);
        switch (slot) {
            case MODEL -> target.setModel(ref);
            case ANIMATION -> target.setAnimation(ref);
            default -> target.setTexture(ref);
        }
        this.editor.changed();
    }

    /**
     * Puts the player's own skin, or the default skin when the skin cache has none, into the
     * skin figure shown, as if it had been picked; the status line says which it was.
     */
    private void useOwnSkin() {
        FigureDraft shown = this.figure;
        if (shown == null || shown.kind() != Kind.SKIN || this.readingOwnSkin) {
            return;
        }
        String figureId = shown.id();
        this.readingOwnSkin = true;
        this.ownSkin.disable(tr("inspector.use_my_skin_reading"));
        OwnSkin.read(result -> {
            this.readingOwnSkin = false;
            this.ownSkin.disable(null);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen != this.editor) {
                // The editor closed, or a screen of its own is open over it, while the skin was read
                return;
            }
            FilePicker.PickResult pick = result.pick();
            if (pick.kind() != FilePicker.PickResult.Kind.ACCEPTED || pick.file() == null) {
                this.editor.status(Component.literal(pick.message()), Theme.RED);
                return;
            }
            FigureDraft target = live(shown, figureId);
            if (target == null || target.kind() != Kind.SKIN) {
                // The figure went away or became a model while the skin was read
                return;
            }
            picked(shown, figureId, FilePicker.Slot.SKIN, pick.file());
            Component note = result.note();
            if (result.own() || note == null) {
                this.editor.status(tr("inspector.use_my_skin_done"));
            } else {
                this.editor.status(tr("inspector.use_my_skin_default", note), Theme.AMBER);
            }
        });
    }

    private void removePoses(FigureDraft figure, String figureId) {
        FigureDraft target = live(figure, figureId);
        if (target != null && target.kind() == Kind.MODEL && target.animation() != null) {
            this.editor.draft().checkpoint();
            target.setAnimation(null);
            this.editor.changed();
        }
    }

    // Changes

    private void renamed(String value) {
        FigureDraft shown = this.figure;
        if (this.settingName || shown == null) {
            return;
        }
        if (this.editor.isSaving()) {
            // The save may be sending the figure under its id, which the sync then brings back,
            // so the id holds still until then
            shown.setName(value);
        } else {
            shown.rename(value);
            // A new figure's id follows its name, and the grid tells its cards apart by id
            this.page.keepSelected(shown);
        }
        this.editor.changed();
    }

    private void resize(int index) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        shown.setScale(WorldCollectionRecord.SCALES.get(index));
        this.editor.changed();
    }

    private void setKind(Kind kind) {
        FigureDraft shown = this.figure;
        if (shown == null || shown.kind() == kind) {
            return;
        }
        // A skin keeps no model or poses; Undo brings them back
        this.editor.draft().checkpoint();
        shown.setKind(kind);
        this.editor.changed();
    }

    private void delete() {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        if (shown.isNew()) {
            this.page.discard();
            return;
        }
        if (!this.editor.draft().isDraftCollection()) {
            return;
        }
        String name = shown.name().isBlank() ? shown.id() : shown.name();
        int index = this.position;
        if (this.editor.draft().removeFigure(shown.id())) {
            this.editor.changed();
            this.page.selectNear(index);
            this.editor.toast(tr("inspector.deleted", name));
        }
    }

    // Layout

    /** Places the nodes whose place depends on the figure shown: the header, the footer and the sections. */
    private void layout() {
        UiNode.Rect panel = this.area;
        if (panel.width() <= 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int left = panel.x() + PAD;
        int right = panel.right() - PAD;
        int headerY = panel.y() + PAD;
        String counter = tr("inspector.position", this.position + 1, this.count).getString();
        int navWidth = UiButton.widthFor(font, this.next.label(), false);
        int nextX = right - navWidth;
        this.next.at(nextX, headerY, navWidth, UiButton.SMALL_HEIGHT);
        this.previous.at(nextX - 8 - font.width(counter) - navWidth, headerY, navWidth, UiButton.SMALL_HEIGHT);

        // Footer: the buttons on the right, the links on their left, or on a row above them
        int buttonsY = panel.bottom() - PAD - UiButton.SMALL_HEIGHT;
        int saveWidth = UiButton.widthFor(font, this.save.label(), false);
        int duplicateWidth = UiButton.widthFor(font, this.duplicate.label(), false);
        if (saveWidth + 4 + duplicateWidth > right - left) {
            // A narrow panel shares its row between the two, which cut their labels
            saveWidth = Math.max(0, (right - left - 4) / 2);
            duplicateWidth = saveWidth;
        }
        this.save.at(right - saveWidth, buttonsY, saveWidth, UiButton.SMALL_HEIGHT);
        this.duplicate.at(this.save.bounds().x() - 4 - duplicateWidth, buttonsY, duplicateWidth,
                UiButton.SMALL_HEIGHT);
        List<UiButton> links = new ArrayList<>();
        for (UiButton link : List.of(this.retire, this.delete)) {
            if (link.visible()) {
                links.add(link);
            }
        }
        int linksWidth = 0;
        for (UiButton link : links) {
            linksWidth += font.width(link.label()) + 10;
        }
        boolean oneRow = left + linksWidth + 4 <= this.duplicate.bounds().x();
        int linkY = oneRow ? buttonsY + (UiButton.SMALL_HEIGHT - 10) / 2 : buttonsY - ROW_GAP - 10;
        int x = left;
        for (UiButton link : links) {
            int linkWidth = Math.min(font.width(link.label()), Math.max(0, right - x));
            link.at(x, linkY, linkWidth, 10);
            x += linkWidth + 10;
        }
        int footerTop = oneRow ? buttonsY : linkY;

        int top = this.fieldY + FIELD + ROW_GAP;
        int sectionsWidth = Math.max(0, right - left);
        int sectionsHeight = Math.max(0, footerTop - 6 - top);
        String key = this.zonesMade + "|" + this.extras.layoutKey() + "|" + left + "," + top + "," + sectionsWidth
                + "," + sectionsHeight;
        if (this.figure != null && !key.equals(this.laidOut)) {
            this.laidOut = key;
            this.sections.at(left, top, sectionsWidth, sectionsHeight);
            layoutSections(left, top, this.sections.contentWidth());
        }
    }

    private void layoutSections(int x, int top, int width) {
        this.sections.clear();
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        // The size and the type each on a row after their label, then the files under theirs
        int column = Math.min(this.labelColumn, width / 3);
        int y = top;
        this.sizeLabel.at(x, y + 2, column, LABEL);
        this.size.at(x + column, y, Math.max(0, width - column), UiSizeSlider.HEIGHT);
        y += UiSizeSlider.HEIGHT + 2;
        this.typeLabel.at(x, y + 8, column, LABEL);
        this.kinds.at(x + column, y, Math.max(0, width - column), KindCards.HEIGHT);
        y += KindCards.HEIGHT + ROW_GAP + 1;
        this.filesLabel.at(x, y, width, Label.HEADING);
        y += Label.HEADING + 1;
        for (UiNode node : List.of(this.sizeLabel, this.size, this.typeLabel, this.kinds, this.filesLabel)) {
            this.sections.add(node);
        }
        for (FileSlot slot : this.slots) {
            slot.zone.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
            this.sections.add(slot.zone);
            y += UiDropZone.COMPACT_HEIGHT + 3;
        }
        if (shown.kind() == Kind.SKIN) {
            // "Use my skin" under the skin's zone, then the shared poses
            Font font = Minecraft.getInstance().font;
            int ownWidth = Math.min(width, UiButton.widthFor(font, this.ownSkin.label(), false));
            this.ownSkin.at(x, y, ownWidth, UiButton.SMALL_HEIGHT);
            this.sections.add(this.ownSkin);
            y += UiButton.SMALL_HEIGHT + 3;
            this.sharedPoses.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
            this.sections.add(this.sharedPoses);
            y += UiDropZone.COMPACT_HEIGHT + 3;
        }
        this.extras.layout(this.sections, x, y, width, this.slotLabelWidth);
    }

    // Drawing

    /** Draws the panel and its labels, under the nodes. */
    void drawContents(GuiGraphics graphics) {
        UiNode.Rect box = this.area;
        if (box.width() < 40) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        EditBox field = this.nameBox;
        drawBackground(graphics, box, field != null && field.visible ? field : null);
        int x = box.x() + PAD;
        int width = box.width() - 2 * PAD;
        FigureDraft shown = this.figure;
        if (shown == null) {
            // What the selection is, until it is one figure
            String heading = this.selectedCount == 0 ? tr("figures.select").getString()
                    : tr("figures.several", this.selectedCount).getString();
            String hint = tr(this.selectedCount == 0 ? "figures.select_hint" : "figures.several_hint").getString();
            int top = box.centerY() - 16;
            float crisp = PixelFont.crispScale(1.0f);
            Component title = Component.literal(heading);
            if (PixelFont.width(title, crisp) <= width) {
                // The heading in the menus' pixel font, centred, while it fits
                int titleWidth = PixelFont.width(title, crisp);
                Draw.heading(graphics, title, box.centerX() - titleWidth / 2, top + 3.5f, width, 1.0f, Theme.TEXT);
            } else {
                Draw.centred(graphics, font, heading, box.centerX(), top, width, Theme.TEXT);
            }
            for (String line : Draw.fit(font, hint, width, 4)) {
                top += Theme.LINE_HEIGHT;
                Draw.centred(graphics, font, line, box.centerX(), top, width, Theme.MUTED);
            }
            return;
        }

        // The header: the id the figure is saved under, or what is wrong with its name, then
        // where its card is between the arrows
        int headerY = box.y() + PAD;
        String counter = tr("inspector.position", this.position + 1, this.count).getString();
        Draw.string(graphics, font, counter, this.previous.bounds().right() + 4, headerY + 4, Theme.TEXT_SOFT);
        Component problem = shown.name().isBlank() ? tr("inspector.name_empty")
                : !WorldCollectionRecord.isPlainText(shown.name()) ? tr("name_rule") : null;
        Component idLine = tr(shown.isNew() ? "figures.id_new" : "figures.id", shown.id());
        Draw.text(graphics, font, (problem != null ? problem : idLine).getString(), x, headerY + 4,
                Math.max(0, this.previous.bounds().x() - 6 - x), problem != null ? Theme.RED : HELP);

        // The name's label left of its field, with an amber dot while the name is not saved
        Component label = tr("inspector.name");
        int labelY = this.fieldY + 6;
        Draw.string(graphics, font, label, x, labelY, LABEL_COLOUR);
        if (changed("name") || shown.isNew()) {
            Draw.dot(graphics, x + font.width(label) + 5, labelY + 4, 4, Theme.AMBER);
        }
    }

    // The panel's fill leaves out the name field, which is drawn before this and would be covered
    private static void drawBackground(GuiGraphics graphics, UiNode.Rect box, @Nullable EditBox field) {
        if (field == null) {
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), PANEL_FILL);
        } else {
            // The creator's field draws its well around its text area; a vanilla one its border a unit outside it
            int left = field instanceof UiField well ? well.wellX() : field.getX() - 1;
            int right = field instanceof UiField well ? well.wellX() + well.wellWidth()
                    : field.getX() + field.getWidth() + 1;
            int top = field instanceof UiField ? field.getY() : field.getY() - 1;
            int bottom = field instanceof UiField ? field.getY() + field.getHeight()
                    : field.getY() + field.getHeight() + 1;
            graphics.fill(box.x(), box.y(), box.right(), top, PANEL_FILL);
            graphics.fill(box.x(), bottom, box.right(), box.bottom(), PANEL_FILL);
            graphics.fill(box.x(), top, left, bottom, PANEL_FILL);
            graphics.fill(right, top, box.right(), bottom, PANEL_FILL);
        }
        Draw.outline(graphics, box.x(), box.y(), box.width(), box.height(), PANEL_OUTLINE);
    }

    /**
     * A label in the scrolling sections, with an amber dot while its section holds unsaved changes:
     * a row's name in Minecraft's font, or a section's heading in the menus' pixel font, which
     * falls back to Minecraft's narrower font where the panel is too narrow for it.
     */
    static final class Label extends UiNode.Box {
        /** The height of a heading's row, for the pixel font's taller capitals. */
        static final int HEADING = 13;

        private final Supplier<Component> text;
        private final BooleanSupplier changed;
        private boolean heading;

        Label(Supplier<Component> text, BooleanSupplier changed) {
            this.text = text;
            this.changed = changed;
        }

        /** A section's heading, in the pixel font where it fits. */
        static Label heading(Supplier<Component> text, BooleanSupplier changed) {
            Label made = new Label(text, changed);
            made.heading = true;
            return made;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            Component label = this.text.get();
            int room = Math.max(0, width - 9);
            int end;
            int middle = y + Math.min(height, HEADING) / 2;
            if (this.heading && PixelFont.width(label, PixelFont.crispScale(1.0f)) <= room) {
                end = x + Draw.heading(graphics, label, x, y + Math.min(height, HEADING) / 2.0f, room, 1.0f,
                        Theme.TEXT);
            } else {
                int textY = this.heading ? middle - 4 : y;
                String shown = Draw.cut(font, label.getString(), room);
                Draw.string(graphics, font, shown, x, textY, this.heading ? Theme.TEXT : LABEL_COLOUR);
                end = x + font.width(shown);
                middle = textY + 4;
            }
            if (this.changed.getAsBoolean()) {
                Draw.dot(graphics, end + 5, middle, 4, Theme.AMBER);
            }
        }
    }

    /**
     * The two kinds a figure can be, as two cards side by side: a Minecraft skin, which uses the
     * standard figure, and a Blockbench model. Each is a small raised card; the figure's own kind
     * stands on the slate of a selected tab inside the blue selection border.
     */
    private final class KindCards extends UiNode.Box {
        static final int HEIGHT = 24;
        private static final int CARD_GAP = 4;

        private int cardWidth() {
            return Math.max(0, (width - CARD_GAP) / 2);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            FigureDraft shown = figure;
            if (!visible() || shown == null) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int cardWidth = cardWidth();
            Kind[] order = {Kind.SKIN, Kind.MODEL};
            for (int i = 0; i < order.length; i++) {
                int left = x + i * (cardWidth + CARD_GAP);
                boolean on = shown.kind() == order[i];
                boolean over = mouseX >= left && mouseX < left + cardWidth && mouseY >= y && mouseY < y + height;
                boolean lit = over && !on;
                Draw.raised(graphics, left, y, cardWidth, height,
                        on ? Theme.SELECTED : lit ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                        on ? Theme.HIGHLIGHT : lit ? Surfaces.FACE_HOVER : Surfaces.FACE, 1);
                boolean skin = order[i] == Kind.SKIN;
                Draw.text(graphics, font, tr(skin ? "figures.kind_skin" : "figures.kind_model").getString(), left + 5,
                        y + 3, cardWidth - 10, on || lit ? Theme.TEXT : Theme.TEXT_SOFT);
                Component note = tr(skin ? "inspector.kind_skin_note" : "inspector.kind_model_note");
                String noteText = Draw.cut(font, note.getString(), cardWidth - 10);
                Draw.string(graphics, font, noteText, left + 5, y + 13, on ? Theme.TEXT_SOFT : HELP);
                if (over && !noteText.equals(note.getString())) {
                    UiTooltip.set(note);
                }
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            int cardWidth = cardWidth();
            if (click.x() < x + cardWidth) {
                setKind(Kind.SKIN);
            } else if (click.x() >= x + cardWidth + CARD_GAP) {
                setKind(Kind.MODEL);
            }
            return true;
        }
    }

    /**
     * The row under a skin figure's skin zone: a skin figure uses the poses every figure shares,
     * which stand and sit, so there is no file to choose.
     */
    private final class SharedPoses extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            // A quiet well, as a zone with nothing to choose
            Draw.well(graphics, x, y, width, height, Surfaces.BORDER_DISABLED);
            int textY = y + (height - 8) / 2;
            int left = x + 5;
            Draw.text(graphics, font, tr("inspector.poses").getString(), left, textY, slotLabelWidth - 4,
                    Theme.CONTROL_TEXT);
            left += slotLabelWidth;
            FileChecks.Check check = new FileChecks.Check(FileChecks.Mark.OK, tr("inspector.poses_shared_check"));
            int chip = UiDropZone.chipWidth(font, check);
            int right = x + width - 5;
            boolean chipFits = right - chip - 6 >= left + 20;
            Draw.text(graphics, font, tr("inspector.poses_shared").getString(), left, textY,
                    Math.max(0, (chipFits ? right - chip - 6 : right) - left), Theme.TEXT);
            if (chipFits) {
                UiDropZone.drawCheck(graphics, font, check, right - chip, y + (height - 11) / 2);
            }
            if (hovered(mouseX, mouseY)) {
                UiTooltip.set(tr("inspector.poses_shared_tip"));
            }
        }
    }
}
