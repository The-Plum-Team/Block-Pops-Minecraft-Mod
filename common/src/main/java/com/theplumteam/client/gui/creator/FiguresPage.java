package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiSideRow;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiThumbStrip;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.registry.ModSounds;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's first tab, "Figures", in the look of Quick Skin's figure editor: a strip of the
 * collection's figures across the top ({@link UiThumbStrip}), and under it the selected figure's
 * big preview on the left and its column on the right ({@link FigureInspector}). Everything is
 * drawn from the editor's live preview, so figures and files not yet saved show at once.
 *
 * <p>The strip shows every figure in the draft's order, then a "+" tile that adds an empty figure
 * and takes files dropped on it, or anywhere over the strip, into the bulk add
 * ({@link BulkAddScreen}). A figure that needs a look carries a red "!" (its problems, or a change
 * of it that cannot be saved, in its tooltip), one that cannot be shown yet a "?", and a retired
 * one shows in black and white. The "!" marks follow the rules the collection's saver holds a
 * figure back by, so every figure it holds has one, and the "With errors" filter lists and counts
 * exactly those. A click selects a figure, Ctrl and Shift pick several, the arrow keys step
 * through them, and Enter or a double click puts the keyboard in the figure's name. With every
 * figure shown and no search, dragging one moves it, as one undo step.
 *
 * <p>The search button at the strip's right opens a menu: how many figures and how much file space
 * the collection uses, "Search…" (Ctrl+F), which opens a search row over the strip, the button
 * then at the row's end, and the filters, all the figures or only the skins, the 3D models, the
 * retired ones or those with a problem, each with its count. While one of them is on the button is
 * lit and says which. An empty collection has nothing to search, so it shows no button.
 *
 * <p>The page opens on the first figure, and a figure a filter or the search hides gives its place
 * to the first one shown. Adding, duplicating and the bulk add's figures show every figure again,
 * select the new ones and scroll to them; a single new one gets the keyboard in its name. An
 * empty collection shows how to add the first figure in place of the preview and the column, a
 * strip with none selected how to pick one, and a search or filter that matches nothing says so
 * over a link that shows every figure again.
 * Every change goes to the editor's draft, which the collection's saver sends, and the editor's
 * Undo takes it back; moving to another figure counts as moving on, so what waits is saved, files
 * included.
 */
final class FiguresPage implements EditorPage {
    // The tools button's side, and the room it takes at the strip's right
    private static final int TOOLS = 22;
    private static final int TOOLS_ROOM = 28;
    // The search row over the strip, and the gap under the strip
    private static final int SEARCH_ROW = 24;
    private static final int UNDER_STRIP = 12;
    // A thumbnail's height on a tall, an ordinary and a short canvas, and its width against it
    private static final int TALL_THUMB = 52;
    private static final int THUMB = 44;
    private static final int SHORT_THUMB = 36;
    private static final int TALL_CANVAS = 420;
    private static final float THUMB_RATIO = 0.77f;
    // The preview's share of the page and its limits, on a narrow and a wide content panel
    private static final float NARROW_PREVIEW = 0.38f;
    private static final float WIDE_PREVIEW = 0.45f;
    private static final int NARROW_GAP = 12;
    private static final int WIDE_GAP = 16;

    /** Which figures the strip shows. */
    private enum Filter {
        ALL("figures.filter.all"), SKINS("figures.filter.skins"), MODELS("figures.filter.models"),
        RETIRED("figures.filter.retired"), ERRORS("figures.filter.errors");

        final String key;

        Filter(String key) {
            this.key = key;
        }
    }

    private final CreatorEditorScreen editor;
    private final UiDropZone filesZone;
    private final ToolsButton tools = new ToolsButton();
    private final FigureInspector inspector;
    // Under "No figure matches": clears the search and the filter
    private final UiButton clearLink;
    @Nullable
    private UiThumbStrip<FigureDraft> strip;
    private int stripThumbWidth;
    private int stripThumbHeight;
    @Nullable
    private EditBox searchBox;

    private Filter filter = Filter.ALL;
    private String search = "";
    private boolean searchOpen;
    private final int[] counts = new int[Filter.values().length];
    // What is wrong with each figure, worked out once per change, and what of each cannot be saved
    private Map<String, List<Component>> problems = Map.of();
    private Map<String, List<Component>> held = Map.of();
    // How many figures and how much file space, worked out once per change rather than per frame
    private Component counter = Component.empty();
    // The figure the column showed last, and its id, which tell a move to another figure apart
    @Nullable
    private FigureDraft inspected;
    @Nullable
    private String inspectedId;
    // Enter or a double click asks for the name field, which gets the keyboard at the next tick:
    // the screen gives it to the clicked strip once the click is handled
    private boolean focusNameSoon;
    private boolean focusSearchSoon;

    // Where build() put what drawContents() draws
    private UiNode.Rect below = UiNode.Rect.EMPTY;

    FiguresPage(CreatorEditorScreen editor) {
        this.editor = editor;
        this.filesZone = UiDropZone.many(editor, tr("bulk.title_short"), this::openBulkAdd);
        this.inspector = new FigureInspector(editor, this);
        this.clearLink = new UiButton(tr("figures.clear_search"), UiButton.Style.LINK, this::showEvery);
    }

    // Building

    @Override
    public void build(UiNode.Rect area) {
        boolean narrow = this.editor.narrow();
        int thumbHeight = this.editor.height >= TALL_CANVAS ? TALL_THUMB : this.editor.isShort() ? SHORT_THUMB : THUMB;
        int thumbWidth = Math.round(thumbHeight * THUMB_RATIO);
        int left = area.x();
        int inner = area.width();
        int right = left + inner;
        int stripY = area.y();
        if (this.searchOpen) {
            this.searchBox = this.editor.field("figure_search", left, stripY, inner - TOOLS_ROOM,
                    WorldContentLimits.MAX_NAME_LENGTH, tr("figures.search_hint"), this.search, value -> {
                        this.search = value;
                        refreshItems();
                    });
            // The button at the search row's end, in line with the field and the strip's right edge
            this.tools.at(right - TOOLS, stripY + (UiField.HEIGHT - TOOLS) / 2, TOOLS, TOOLS);
            stripY += SEARCH_ROW;
        } else {
            this.searchBox = null;
        }
        UiThumbStrip<FigureDraft> row = strip(thumbWidth, thumbHeight);
        row.at(left, stripY, inner - TOOLS_ROOM, thumbHeight);
        this.editor.add(row);
        this.editor.budgetNode(row);
        if (!this.searchOpen) {
            this.tools.at(right - TOOLS, stripY + (thumbHeight - TOOLS) / 2, TOOLS, TOOLS);
        }
        this.editor.add(this.tools);

        // The preview on the left, as tall as the page, and the column beside it
        int top = stripY + thumbHeight + UNDER_STRIP;
        int footerY = this.editor.footerY();
        int previewWidth = narrow ? SidebarScreen.clamp(Math.round(inner * NARROW_PREVIEW), 88, 150)
                : SidebarScreen.clamp(Math.round(inner * WIDE_PREVIEW), 150, 280);
        int columnX = left + previewWidth + (narrow ? NARROW_GAP : WIDE_GAP);
        this.below = new UiNode.Rect(left, top, inner, Math.max(0, area.bottom() - top));
        this.editor.add(this.clearLink);
        this.inspector.build(new UiNode.Rect(left, top, previewWidth, Math.max(0, area.bottom() - top)),
                new UiNode.Rect(columnX, top, Math.max(0, right - columnX), Math.max(0, footerY - 6 - top)), footerY,
                right);
        // With nothing selected yet, the first figure is
        refreshItems();
    }

    /**
     * The strip, made again only when its thumbnails change size, as when the window is resized,
     * so a rebuild keeps the selection and the scroll.
     */
    private UiThumbStrip<FigureDraft> strip(int thumbWidth, int thumbHeight) {
        if (this.strip != null && this.stripThumbWidth == thumbWidth && this.stripThumbHeight == thumbHeight) {
            return this.strip;
        }
        List<FigureDraft> items = this.strip != null ? this.strip.items() : List.of();
        List<FigureDraft> selected = this.strip != null ? this.strip.selected() : List.of();
        UiThumbStrip<FigureDraft> made = new UiThumbStrip<>(thumbWidth, thumbHeight, this::look)
                .collection(() -> CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT))
                .colour(() -> 0xFF000000 | this.editor.draft().rgb())
                .keys(FigureDraft::id)
                .onSelection(chosen -> updateSelection())
                .onActivate(figure -> this.focusNameSoon = true)
                .addTile(this::addFigure, tr("figures.add_tip"))
                .filesZone(this.filesZone);
        made.setItems(items);
        made.select(selected);
        this.strip = made;
        this.stripThumbWidth = thumbWidth;
        this.stripThumbHeight = thumbHeight;
        return made;
    }

    // How a figure's thumbnail looks: its marks and its tooltip, "Name · Skin · 1.5x"
    private UiThumbStrip.Look look(FigureDraft figure) {
        List<Component> wrong = new ArrayList<>(this.problems.getOrDefault(figure.id(), List.of()));
        wrong.addAll(this.held.getOrDefault(figure.id(), List.of()));
        String name = figure.name().isBlank() ? figure.id() : figure.name();
        Component tooltip = Component.literal(name + " · ")
                .append(tr(figure.kind() == Kind.SKIN ? "bulk.type.skin" : "bulk.type.model"))
                .append(" · " + UiSizeSlider.scaleText(figure.scale()));
        List<Component> lines = figure.enabled() ? List.of(tooltip) : List.of(tooltip, tr("figures.retired_ribbon"));
        return new UiThumbStrip.Look(figure.id(), name, figure.isComplete(), !figure.enabled(), wrong, lines,
                figure.scale());
    }

    // Following the draft

    @Override
    public void draftChanged() {
        CreatorDraft draft = this.editor.draft();
        Map<String, List<Component>> found = new HashMap<>();
        for (FigureDraft figure : draft.figures()) {
            List<Component> wrong = problemsOf(figure);
            if (!wrong.isEmpty()) {
                found.put(figure.id(), wrong);
            }
        }
        this.problems = found;
        refreshHeld();
        recount();
        long bytes = this.editor.forecast().collectionBytes();
        int usedKiB = (int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024);
        int maxKiB = (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024);
        // Under a mebibyte the files read in kibibytes, so a few small skins never read as 0
        this.counter = tr("card.joined", tr("figures.count", draft.figures().size(), WorldContentLimits.MAX_FIGURES),
                usedKiB < 1024 ? tr("figures.space_kib", usedKiB, CreatorScreen.decimal(maxKiB / 1024.0, 1))
                        : UiQuotaMeter.text(usedKiB, maxKiB));
        Component full = draft.isFull() ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null;
        this.filesZone.disable(full);
        if (this.strip != null) {
            this.strip.disableAdd(full);
        }
        refreshItems();
    }

    /**
     * The changes of each figure the collection's saver holds back that the figure's own problems
     * do not already say: every refusal of the server's, and any other held figure whose problems
     * missed why, as a file the server dropped from its table, so every held figure has its "!".
     * Asked again on every tick, since the saver learns them without a change.
     *
     * @return whether the figures held changed
     */
    private boolean refreshHeld() {
        Map<String, List<Component>> found = new HashMap<>();
        for (CreatorSave.Held unit : this.editor.saver().held()) {
            String figureId = unit.figureId();
            if (figureId == null || unit.reason() != CreatorSave.Reason.REFUSED && this.problems.containsKey(figureId)) {
                continue;
            }
            found.computeIfAbsent(figureId, id -> new ArrayList<>()).add(unit.detail());
        }
        boolean changed = !found.keySet().equals(this.held.keySet());
        this.held = found;
        return changed;
    }

    // Whether a figure has a problem or a change held back: its "!", and the "With errors" filter
    private boolean hasProblem(FigureDraft figure) {
        return this.problems.containsKey(figure.id()) || this.held.containsKey(figure.id());
    }

    // The filters' counts, the figures with errors from the same marks the strip shows
    private void recount() {
        Arrays.fill(this.counts, 0);
        for (FigureDraft figure : this.editor.draft().figures()) {
            boolean wrong = hasProblem(figure);
            for (Filter which : Filter.values()) {
                if (accepts(which, figure, wrong)) {
                    this.counts[which.ordinal()]++;
                }
            }
        }
    }

    private static boolean accepts(Filter which, FigureDraft figure, boolean hasProblem) {
        return switch (which) {
            case ALL -> true;
            case SKINS -> figure.kind() == Kind.SKIN;
            case MODELS -> figure.kind() == Kind.MODEL;
            case RETIRED -> !figure.enabled();
            case ERRORS -> hasProblem;
        };
    }

    /**
     * Everything that keeps a figure from being saved or shown as it should, by the rules the
     * collection's saver holds a figure back by ({@link CreatorSave#figureHeld}): its name, a file
     * it still needs, a file the server lost or could not load, a file that breaks a rule, a skin
     * variant's name or texture, and a box face outside the texture.
     */
    private static List<Component> problemsOf(FigureDraft figure) {
        List<Component> found = new ArrayList<>();
        if (figure.name().isBlank()) {
            found.add(tr("inspector.name_empty"));
        } else if (!WorldCollectionRecord.isPlainText(figure.name())) {
            found.add(tr("name_rule"));
        }
        boolean skin = figure.kind() == Kind.SKIN;
        if (figure.texture() == null || !skin && figure.model() == null) {
            found.add(tr(skin ? "figures.problem.no_skin"
                    : figure.texture() == null && figure.model() == null ? "figures.problem.no_files"
                    : figure.model() == null ? "figures.problem.no_model" : "figures.problem.no_texture"));
        }
        BlobInfo model = figure.model() != null ? figure.model().info() : null;
        Function<BlobInfo, List<FileChecks.Check>> textureChecks = FigureExtrasPanel.textureChecks(figure.kind(), model);
        FilePicker.Slot textureSlot = skin ? FilePicker.Slot.SKIN : FilePicker.Slot.MODEL_TEXTURE;
        fileProblems(found, figure.texture(), textureSlot, textureChecks);
        if (!skin) {
            fileProblems(found, figure.model(), FilePicker.Slot.MODEL, FileChecks::geo);
            fileProblems(found, figure.animation(), FilePicker.Slot.ANIMATION, FileChecks::animation);
        }
        // A variant's texture gets its figure's checks, as one the model no longer fits
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            if (variant.name().isBlank() || !WorldCollectionRecord.isPlainText(variant.name())) {
                found.add(tr("extras.variant_name_rule"));
            }
            fileProblems(found, variant.texture(), textureSlot, textureChecks);
        }
        WorldCollectionRecord.BoxFace face = figure.boxFace();
        BlobInfo texture = figure.texture() != null ? figure.texture().info() : null;
        if (!skin && face != null && face.show() && face.uv() != null && texture != null
                && !FigureExtrasPanel.fits(face.uv(), texture)) {
            found.add(tr("extras.face_outside", texture.width(), texture.height()));
        }
        return found;
    }

    private static void fileProblems(List<Component> found, @Nullable FileRef file, FilePicker.Slot slot,
                                     Function<BlobInfo, List<FileChecks.Check>> checks) {
        if (file == null) {
            return;
        }
        // A file picked here goes up again with the next save, so only a stored one stays lost
        if (file.local() == null && ClientWorldCollections.isMissingOnServer(file.sha())) {
            found.add(tr("figures.problem.missing", UiDropZone.slotName(slot)));
            return;
        }
        if (file.local() == null && ClientWorldCollections.failedToLoad(file.sha())) {
            found.add(tr("figures.problem.failed", UiDropZone.slotName(slot)));
            return;
        }
        BlobInfo info = file.info();
        if (info == null) {
            return;
        }
        for (FileChecks.Check check : checks.apply(info)) {
            if (!check.ok()) {
                found.add(check.text());
            }
        }
    }

    /** Shows the figures the filter and the search pick, in the draft's order. */
    private void refreshItems() {
        UiThumbStrip<FigureDraft> row = this.strip;
        if (row == null) {
            return;
        }
        String query = this.search.strip().toLowerCase(Locale.ROOT);
        List<FigureDraft> shown = new ArrayList<>();
        for (FigureDraft figure : this.editor.draft().figures()) {
            if (accepts(this.filter, figure, hasProblem(figure)) && (query.isEmpty()
                    || figure.name().toLowerCase(Locale.ROOT).contains(query) || figure.id().contains(query))) {
                shown.add(figure);
            }
        }
        List<FigureDraft> before = row.items();
        FigureDraft was = single();
        row.setItems(shown);
        keepRenamed(before, was, shown);
        if (row.selected().isEmpty() && !shown.isEmpty()) {
            // The figure shown left the strip, as a filter or the search hid it: the first one takes its place
            row.select(List.of(shown.get(0)));
        }
        // Only the whole list in its own order can be reordered by dragging
        row.onReorder(whole() ? this::moveFigure : null, tr("figures.hint_filtered"));
        // An empty collection has nothing to search or filter
        this.tools.setVisible(!this.editor.draft().figures().isEmpty());
        updateSelection();
    }

    // Whether every figure shows, in its own order
    private boolean whole() {
        return this.filter == Filter.ALL && this.search.strip().isEmpty();
    }

    /**
     * Keeps the one selected figure selected when it came back under another id, as when Undo
     * takes back the rename of a new figure: the strip tells figures apart by id, so it lost the
     * figure. Only the same number of figures, with an id at its place that none had before,
     * counts as the same figure.
     */
    private void keepRenamed(List<FigureDraft> before, @Nullable FigureDraft was, List<FigureDraft> shown) {
        UiThumbStrip<FigureDraft> row = this.strip;
        if (was == null || row == null || !row.selected().isEmpty() || before.size() != shown.size()) {
            return;
        }
        int index = before.indexOf(was);
        if (index < 0) {
            return;
        }
        FigureDraft now = shown.get(index);
        for (FigureDraft old : before) {
            if (old.id().equals(now.id())) {
                return;
            }
        }
        row.select(List.of(now));
    }

    // Selection

    private List<FigureDraft> selected() {
        return this.strip != null ? this.strip.selected() : List.of();
    }

    @Nullable
    private FigureDraft single() {
        List<FigureDraft> chosen = selected();
        return chosen.size() == 1 ? chosen.get(0) : null;
    }

    /**
     * Shows the selection in the preview and the column, or, with none, hides them for the page's
     * own line, and saves what waits when it is another figure.
     */
    private void updateSelection() {
        List<FigureDraft> chosen = selected();
        // Shown first, so the column's footer link follows it
        this.inspector.setVisible(!chosen.isEmpty());
        this.inspector.show(chosen);
        // A search or filter that matches nothing offers to show every figure again
        boolean noMatch = noMatch();
        this.clearLink.setLabel(tr(this.search.strip().isEmpty() ? "figures.show_all" : "figures.clear_search"));
        if (noMatch) {
            Font font = Minecraft.getInstance().font;
            int linkWidth = Math.min(this.below.width(), font.width(this.clearLink.label()) + 2);
            this.clearLink.at(this.below.centerX() - linkWidth / 2, messageTop() + Theme.LINE_HEIGHT + 6, linkWidth, 12);
        }
        this.clearLink.setVisible(noMatch);
        // Another figure, or none: the operator moved on, so what waits is saved, files included.
        // A rename keeps the figure and an undo its id, so neither counts.
        FigureDraft figure = chosen.size() == 1 ? chosen.get(0) : null;
        String figureId = figure != null ? figure.id() : null;
        if (figure != this.inspected && !Objects.equals(figureId, this.inspectedId)) {
            this.editor.navigated();
        }
        this.inspected = figure;
        this.inspectedId = figureId;
    }

    /** The new figure whose name field has the keyboard, or null. */
    @Nullable
    String typingFigureId() {
        return this.inspector.typingFigureId();
    }

    /** Keeps a figure selected after its id changed, since the strip tells figures apart by id. */
    void keepSelected(FigureDraft figure) {
        if (this.strip != null) {
            this.strip.select(List.of(figure));
        }
    }

    /** After a figure left the draft, selects the one that took its place, or the last. */
    private void selectNear(int index) {
        UiThumbStrip<FigureDraft> row = this.strip;
        if (row == null) {
            return;
        }
        List<FigureDraft> shown = row.items();
        if (!shown.isEmpty()) {
            FigureDraft next = shown.get(Math.max(0, Math.min(index, shown.size() - 1)));
            row.select(List.of(next));
            row.reveal(next);
        }
        updateSelection();
    }

    // Changing the figures

    private void addFigure() {
        CreatorDraft draft = this.editor.draft();
        FigureDraft figure = draft.addFigure(tr("figures.new_name").getString());
        if (figure == null) {
            this.editor.status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        this.editor.changed();
        reveal(List.of(figure));
    }

    /**
     * Selects figures just added and scrolls to the first, showing every figure again if a filter
     * or the search hid them; a single new one gets the keyboard in its name.
     */
    void reveal(List<FigureDraft> added) {
        UiThumbStrip<FigureDraft> row = this.strip;
        if (added.isEmpty() || row == null) {
            return;
        }
        if (this.filter != Filter.ALL || !this.search.isEmpty()) {
            this.filter = Filter.ALL;
            this.search = "";
            if (this.searchBox != null) {
                // The field's responder shows every figure again
                this.searchBox.setValue("");
            }
            refreshItems();
        }
        row.select(added);
        row.reveal(added.get(0));
        updateSelection();
        if (added.size() == 1 && added.get(0).isNew()) {
            this.focusNameSoon = true;
        }
    }

    // Whether the draft has figures but the filter and the search show none of them
    private boolean noMatch() {
        return !this.editor.draft().figures().isEmpty() && this.strip != null && this.strip.items().isEmpty();
    }

    // Shows every figure again, the search and the filter cleared, as the link under "No figure matches" does
    private void showEvery() {
        this.filter = Filter.ALL;
        this.search = "";
        if (this.searchBox != null) {
            // The field's responder shows the figures again
            this.searchBox.setValue("");
        }
        refreshItems();
    }

    /** Takes the selected figures out of the draw, or puts them back, as one undo step. */
    void setEnabled(boolean enabled) {
        List<FigureDraft> chosen = selected();
        if (chosen.isEmpty()) {
            return;
        }
        this.editor.draft().change(enabled ? "figures/restore" : "figures/retire", () -> {
            for (FigureDraft figure : chosen) {
                figure.setEnabled(enabled);
            }
        });
        this.editor.changed();
        if (!enabled) {
            // Taking figures out of the draw is the change to undo, which the toast offers
            FigureDraft first = chosen.get(0);
            this.editor.undoToast(chosen.size() == 1
                    ? tr("figures.retired_toast_one", first.name().isBlank() ? first.id() : first.name())
                    : tr("figures.retired_toast", chosen.size()));
        }
    }

    /** Gives figures one size, as one undo step. */
    void setScale(List<FigureDraft> chosen, float scale) {
        this.editor.draft().change("figures/scale", () -> {
            for (FigureDraft figure : chosen) {
                // A figure an undo replaced meanwhile is looked up again
                FigureDraft current = this.editor.draft().figure(figure.id());
                if (current != null) {
                    current.setScale(scale);
                }
            }
        });
        this.editor.changed();
    }

    /** Adds a copy of each selected figure after the others, as one undo step, and selects the copies. */
    void duplicateSelected() {
        List<FigureDraft> chosen = selected();
        if (chosen.isEmpty()) {
            return;
        }
        CreatorDraft draft = this.editor.draft();
        if (draft.figures().size() + chosen.size() > WorldContentLimits.MAX_FIGURES) {
            // Checked first, so a refused duplicate leaves no empty undo step
            this.editor.status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        List<FigureDraft> copies = new ArrayList<>();
        draft.change("figures/duplicate", () -> {
            for (FigureDraft figure : chosen) {
                FigureDraft copy = draft.duplicateFigure(figure.id());
                if (copy != null) {
                    copies.add(copy);
                }
            }
        });
        if (copies.isEmpty()) {
            this.editor.status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        this.editor.changed();
        reveal(copies);
    }

    /** Drops the one selected figure if it was never saved, which just goes; the toast takes it back. */
    void discard() {
        FigureDraft figure = single();
        if (figure == null || !figure.isNew()) {
            return;
        }
        remove(figure, tr("inspector.deleted", figure.name().isBlank() ? figure.id() : figure.name()));
    }

    /** Deletes a draft collection's one selected figure for good; the toast takes it back. */
    void deleteSelected() {
        FigureDraft figure = single();
        if (figure == null || figure.isNew() || !this.editor.draft().isDraftCollection()) {
            return;
        }
        remove(figure, tr("figures.deleted", figure.name().isBlank() ? figure.id() : figure.name()));
    }

    private void remove(FigureDraft figure, Component toast) {
        int index = this.strip != null ? this.strip.items().indexOf(figure) : -1;
        if (this.editor.draft().removeFigure(figure.id())) {
            this.editor.changed();
            selectNear(index);
            // Its files go with it, so the toast offers to take it back
            this.editor.undoToast(toast);
        }
    }

    private void moveFigure(int from, int to) {
        // Each drag is an undo step of its own
        this.editor.draft().checkpoint();
        this.editor.draft().moveFigure(from, to);
        this.editor.changed();
    }

    /** Opens the bulk add over the editor with the files dropped on the strip or its "+" tile. */
    private void openBulkAdd(List<FilePicker.PickResult> results) {
        if (this.editor.draft().isFull()) {
            this.editor.status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        this.editor.status(Component.empty());
        this.editor.openChild(new BulkAddScreen(this.editor, results));
    }

    // The search and the filters

    /**
     * The search button's menu: the collection's counters, "Search…", and the filters with their
     * counts, the one on ticked. Under the button, by its right edge.
     */
    private void openToolsMenu() {
        UiMenu menu = new UiMenu();
        menu.heading(this.counter);
        menu.item(tr("figures.search"), Component.literal("Ctrl+F"), this::openSearch);
        menu.separator();
        menu.heading(tr("figures.show"));
        for (Filter each : Filter.values()) {
            menu.radio(tr(each.key, this.counts[each.ordinal()]), this.filter == each, () -> {
                this.filter = each;
                refreshItems();
            });
        }
        menu.highlight(4 + this.filter.ordinal());
        UiNode.Rect button = this.tools.bounds();
        menu.place(button, this.editor.width, this.editor.height);
        int menuX = Math.max(0, button.right() - menu.bounds().width());
        this.editor.openMenu(menu, new UiNode.Rect(menuX, button.y(), button.width(), button.height()));
    }

    // Opens the search row over the strip, or puts the keyboard back in it
    private void openSearch() {
        if (!this.searchOpen) {
            this.searchOpen = true;
            this.editor.rebuildPage();
        }
        this.focusSearchSoon = true;
        focusSearch();
    }

    private void focusSearch() {
        EditBox box = this.searchBox;
        if (box != null) {
            this.focusSearchSoon = false;
            this.editor.focusNode(null);
            this.editor.setFocused(box);
            UiField.cursorToEnd(box);
        }
    }

    // What the search button's tooltip says: what it does, or which filter or search is on
    private Component toolsTooltip() {
        if (!this.search.strip().isEmpty()) {
            return tr("card.joined", tr("figures.search"), Component.literal("«" + this.search.strip() + "»"));
        }
        if (this.filter != Filter.ALL) {
            return tr(this.filter.key, this.counts[this.filter.ordinal()]);
        }
        return tr("figures.tools");
    }

    // Drawing

    @Override
    public boolean takesFiles() {
        return true;
    }

    @Override
    public void tick() {
        if (refreshHeld()) {
            // The saver held a figure back, or let one go: its "!" and the errors filter follow
            recount();
            if (this.filter == Filter.ERRORS) {
                refreshItems();
            }
        }
        if (this.focusSearchSoon) {
            focusSearch();
        }
        if (this.focusNameSoon) {
            this.focusNameSoon = false;
            this.inspector.focusName();
        }
    }

    /**
     * Escape: "More settings" goes up a level; else the search is cleared, or its empty row
     * closed; else a selection of several comes back to its first figure.
     */
    @Override
    public boolean escape() {
        if (this.inspector.escape()) {
            return true;
        }
        if (this.searchOpen) {
            if (!this.search.isEmpty() && this.searchBox != null) {
                this.searchBox.setValue("");
            } else {
                this.searchOpen = false;
                this.search = "";
                this.editor.rebuildPage();
                refreshItems();
            }
            return true;
        }
        List<FigureDraft> chosen = selected();
        if (chosen.size() > 1 && this.strip != null) {
            FigureDraft first = this.strip.current() != null && chosen.contains(this.strip.current())
                    ? this.strip.current() : chosen.get(0);
            this.strip.select(List.of(first));
            updateSelection();
            return true;
        }
        return false;
    }

    // Where the page's own line starts when the preview and the column are hidden
    private int messageTop() {
        return this.below.y() + this.below.height() / 3 - Theme.LINE_HEIGHT;
    }

    @Override
    public void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean empty = this.editor.draft().figures().isEmpty();
        if (!empty && !selected().isEmpty()) {
            return;
        }
        // Where the preview and the column will be: how to add the first figure, how to pick one,
        // or that the search and the filter show none
        Font font = Minecraft.getInstance().font;
        int width = Math.min(this.below.width() - 16, 280);
        int top = messageTop();
        if (!empty) {
            boolean none = noMatch();
            Draw.centred(graphics, font, tr(none ? "figures.no_match" : "figures.select").getString(),
                    this.below.centerX(), top, width, none ? Theme.MUTED : Theme.TEXT);
            if (none) {
                return;
            }
            top += Theme.LINE_HEIGHT + 3;
        }
        String hint = tr(empty ? "figures.empty_hint" : "figures.select_hint").getString();
        for (String line : Draw.fit(font, hint, width, 4)) {
            Draw.centred(graphics, font, line, this.below.centerX(), top, width, Theme.MUTED);
            top += Theme.LINE_HEIGHT;
        }
    }

    // Input

    @Override
    public boolean shortcut(UiInput.Key key) {
        if (key.ctrl() && key.is(InputConstants.KEY_F)) {
            if (!this.editor.draft().figures().isEmpty()) {
                openSearch();
            }
            return true;
        }
        UiThumbStrip<FigureDraft> row = this.strip;
        // Only asked while no field or node has the keyboard, which keeps the arrows of each
        if (!key.ctrl() && !key.shift() && (key.is(InputConstants.KEY_LEFT) || key.is(InputConstants.KEY_RIGHT))
                && row != null && !row.items().isEmpty()) {
            row.step(key.is(InputConstants.KEY_LEFT) ? -1 : 1);
            return true;
        }
        if (!key.ctrl() && !key.shift() && key.is(InputConstants.KEY_DELETE) && single() != null) {
            removeSelected();
            return true;
        }
        if (key.enter() && single() != null) {
            this.inspector.focusName();
            return true;
        }
        return false;
    }

    /**
     * Delete on the one selected figure: what the column's footer link does with it, in the
     * link's order. A figure never saved is discarded, a retired one put back in the draw, a
     * draft collection's saved figure deleted, and a published or archived collection's figure,
     * which players may own, only retired from the draw; each that takes something away shows a
     * toast that undoes it.
     */
    private void removeSelected() {
        FigureDraft figure = single();
        if (figure == null) {
            return;
        }
        if (figure.isNew()) {
            discard();
        } else if (!figure.enabled()) {
            setEnabled(true);
        } else if (this.editor.draft().isDraftCollection()) {
            deleteSelected();
        } else {
            setEnabled(false);
        }
    }

    /**
     * The button at the strip's right that opens the search and the filters: a magnifying glass
     * and a chevron on a raised face, lit in the selection's slate while a filter or a search hides
     * some figures, and saying which in its tooltip.
     */
    private final class ToolsButton extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            boolean hovered = hovered(mouseX, mouseY);
            boolean on = !whole();
            int border = on ? Theme.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
            int face = on ? Theme.HIGHLIGHT : hovered ? Surfaces.FACE_HOVER : Surfaces.FACE;
            int faceBottom = Draw.raised(graphics, x, y, width, height, border, face);
            int colour = on ? Theme.TEXT : hovered ? Theme.TEXT : Theme.TEXT_SOFT;
            int iconWidth = PixelIcon.width(Theme.ICON_SEARCH);
            int chevronWidth = PixelIcon.width(UiSideRow.CHEVRON_DOWN);
            int left = x + (width - iconWidth - 2 - chevronWidth) / 2;
            int middle = (y + faceBottom) / 2;
            PixelIcon.draw(graphics, Theme.ICON_SEARCH, left, middle - Theme.ICON_SEARCH.length / 2, colour);
            PixelIcon.draw(graphics, UiSideRow.CHEVRON_DOWN, left + iconWidth + 2, middle, colour);
            if (hovered) {
                UiTooltip.set(SidebarScreen.tooltipLines(toolsTooltip()));
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!visible() || !click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            openToolsMenu();
            return true;
        }
    }
}
