package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCardGrid;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's second tab, "Figures": every figure of the draft as a grid of cards (see
 * {@link FigureCard}), drawn from the editor's live preview, so figures and files not yet saved
 * show at once.
 *
 * <p>The toolbar adds a figure with no files yet, adds several from files, and searches by name
 * or id; chips under it show all the figures or only the skins, the 3D models, the retired ones
 * or those with a problem, each with its count, beside how many figures and how much file space
 * the collection uses. "Add several…", and files dropped anywhere on the grid or chosen through
 * its last cell, open the bulk add ({@link BulkAddScreen}) over the editor, which shows the
 * figures the files make before adding and uploading them.
 *
 * <p>Ctrl or Shift and a click pick several cards, and a bar under the grid then retires,
 * restores, resizes or duplicates them together. With every figure shown and no search,
 * dragging a card moves the figure. All of these only change the draft, and the editor's Save
 * sends them.
 *
 * <p>The side panel is the {@link FigureInspector}, which shows and edits the one selected figure;
 * the left and right arrow keys step through the cards while no field has the keyboard, and a
 * double click or Enter on a card puts the keyboard in the figure's name.
 */
final class FiguresPage implements EditorPage {
    // The grid takes the mockup's 676 of 1244 units; the panel beside it the rest
    private static final int GRID_SHARE = 54;
    private static final int MIN_GRID_WIDTH = 220;
    private static final int MIN_PANEL_WIDTH = 120;
    private static final int GAP = 4;
    private static final int COLUMN_GAP = 9;
    private static final int ROW_GAP = 6;
    private static final int COLUMNS = 6;
    private static final int MIN_CARD_WIDTH = 46;
    private static final int SEARCH_MIN = 50;
    private static final int BOTTOM_ROW = UiButton.SMALL_HEIGHT;
    private static final int HINT = 0xFFA8A8A8;

    /** Which figures the grid shows. */
    private enum Filter {
        ALL, SKINS, MODELS, RETIRED, ERRORS
    }

    private final CreatorEditorScreen editor;
    private final FigureCard cards = new FigureCard();
    private final UiButton addButton;
    private final UiButton severalButton;
    private final FilterChips chips;
    private final UiDropZone dropZone;
    private final DropCell dropCell;
    // The selection bar
    private final UiButton retireAll;
    private final UiButton restoreAll;
    private final UiButton sizeAll;
    private final UiButton duplicateAll;
    private final UiButton clearSelection;
    private final FigureInspector inspector;
    @Nullable
    private UiCardGrid<FigureDraft> grid;
    private int gridCardWidth;
    @Nullable
    private EditBox searchBox;

    private Filter filter = Filter.ALL;
    private String search = "";
    private final int[] counts = new int[Filter.values().length];
    private Set<String> dirtyIds = Set.of();
    private Map<String, List<Component>> problems = Map.of();
    // How many figures and how much file space, worked out once per change rather than per frame
    private Component counter = Component.empty();
    // A double click on a card asks for the name field, which gets the keyboard at the next tick:
    // the screen gives it to the clicked grid once the click is handled
    private boolean focusNameSoon;
    private float partialTick;

    // Where build() put what drawContents() draws
    private UiNode.Rect gridArea = UiNode.Rect.EMPTY;
    private UiNode.Rect panel = UiNode.Rect.EMPTY;
    private int chipsY;
    private int bottomY;

    FiguresPage(CreatorEditorScreen editor) {
        this.editor = editor;
        this.addButton = new UiButton(tr("figures.add"), UiButton.Style.GREEN, this::addFigure);
        this.dropZone = UiDropZone.many(editor, tr("figures.drop"), this::openBulkAdd).help(tr("figures.drop_help"));
        this.dropCell = new DropCell(this.dropZone);
        this.severalButton = new UiButton(tr("figures.add_several"), UiButton.Style.SECONDARY,
                () -> openBulkAdd(List.of()));
        this.chips = new FilterChips(this::chipLabels, index -> {
            this.filter = Filter.values()[index];
            refreshItems();
        });

        this.retireAll = small(tr("figures.retire"), UiButton.Style.SECONDARY, () -> setEnabled(false));
        this.retireAll.setTooltip(tr("figures.retire_tip"));
        this.restoreAll = small(tr("figures.restore"), UiButton.Style.SECONDARY, () -> setEnabled(true));
        this.sizeAll = small(tr("figures.size"), UiButton.Style.SECONDARY, this::openSizeMenu);
        this.duplicateAll = small(tr("figures.duplicate"), UiButton.Style.SECONDARY, this::duplicateSelected);
        this.clearSelection = small(Component.literal("✕"), UiButton.Style.LINK, this::clearSelection);
        this.clearSelection.setTooltip(tr("figures.clear_tip"));
        this.inspector = new FigureInspector(editor, this);
    }

    private static UiButton small(Component label, UiButton.Style style, Runnable action) {
        UiButton button = new UiButton(label, style, action);
        button.at(0, 0, 0, UiButton.SMALL_HEIGHT);
        return button;
    }

    // Building

    @Override
    public void build(UiNode.Rect area) {
        Font font = Minecraft.getInstance().font;
        int gridWidth = Math.max(MIN_GRID_WIDTH, Math.min(area.width() - COLUMN_GAP - MIN_PANEL_WIDTH,
                area.width() * GRID_SHARE / 100));
        gridWidth = Math.min(gridWidth, area.width());
        int left = area.x();
        int right = left + gridWidth;
        int y = area.y();

        // Toolbar: the two ways to add, then the search filling the rest
        int addWidth = UiButton.widthFor(font, this.addButton.label(), false);
        this.addButton.at(left, y, addWidth, UiButton.HEIGHT);
        int severalWidth = UiButton.widthFor(font, this.severalButton.label(), false);
        this.severalButton.at(left + addWidth + GAP, y, severalWidth, UiButton.HEIGHT);
        int searchX = this.severalButton.bounds().right() + GAP + 2;
        this.severalButton.setVisible(right - searchX >= SEARCH_MIN);
        if (!this.severalButton.visible()) {
            searchX = this.addButton.bounds().right() + GAP + 2;
        }
        this.searchBox = this.editor.field("figure_search", searchX, y, Math.max(SEARCH_MIN, right - searchX),
                WorldContentLimits.MAX_NAME_LENGTH, tr("figures.search_hint"), this.search, value -> {
                    this.search = value;
                    refreshItems();
                });
        this.editor.add(this.addButton);
        this.editor.add(this.severalButton);
        y += UiButton.HEIGHT + ROW_GAP;

        // The chips, with the counters beside them when there is room
        this.chipsY = y;
        this.chips.at(left, y, gridWidth, FilterChips.HEIGHT);
        this.editor.add(this.chips);
        y += FilterChips.HEIGHT + ROW_GAP;

        this.bottomY = area.bottom() - BOTTOM_ROW;
        this.gridArea = new UiNode.Rect(left, y, gridWidth, Math.max(0, this.bottomY - 4 - y));
        UiCardGrid<FigureDraft> cardGrid = grid(gridWidth);
        cardGrid.at(this.gridArea.x(), this.gridArea.y(), this.gridArea.width(), this.gridArea.height());
        this.editor.add(cardGrid);

        for (UiButton button : List.of(this.retireAll, this.restoreAll, this.sizeAll, this.duplicateAll,
                this.clearSelection)) {
            this.editor.add(button);
        }

        // The side panel: the inspector of the selected figure
        int panelX = right + COLUMN_GAP;
        this.panel = new UiNode.Rect(panelX, area.y(), Math.max(0, area.right() - panelX), area.height());
        this.inspector.build(this.panel);
        refreshItems();
    }

    /**
     * The grid, made again only when the cards change width, as when the window is resized, so
     * a rebuild keeps the selection and the scroll.
     */
    private UiCardGrid<FigureDraft> grid(int width) {
        int contentWidth = width - UiScrollPane.BAR - 2;
        int columns = Math.max(1, Math.min(COLUMNS, (contentWidth + GAP) / (MIN_CARD_WIDTH + GAP)));
        int cardWidth = Math.max(MIN_CARD_WIDTH / 2, (contentWidth - (columns - 1) * GAP) / columns);
        if (this.grid != null && this.gridCardWidth == cardWidth) {
            return this.grid;
        }
        List<FigureDraft> selected = this.grid != null ? this.grid.selected() : List.of();
        UiCardGrid<FigureDraft> cardGrid = new UiCardGrid<FigureDraft>(cardWidth, FigureCard.HEIGHT,
                (graphics, figure, at) -> this.cards.draw(graphics, figure, at,
                        CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT),
                        0xFF000000 | this.editor.draft().rgb(), this.dirtyIds.contains(figure.id()),
                        this.problems.getOrDefault(figure.id(), List.of()), this.partialTick)) {
            // Files dropped anywhere on the grid go to the bulk add, even with its last cell scrolled
            // out of sight, where the window would otherwise find no zone to take them
            @Override
            @Nullable
            public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
                UiDropZone under = super.dropZones(mouseX, mouseY, zones);
                if (!visible()) {
                    return under;
                }
                UiDropZone figureFiles = FiguresPage.this.dropZone;
                if (!zones.contains(figureFiles)) {
                    zones.add(figureFiles);
                }
                return under == null && bounds().contains(mouseX, mouseY) ? figureFiles : under;
            }
        }
                .gap(GAP)
                .keys(FigureDraft::id)
                .onActivate(figure -> this.focusNameSoon = true)
                .onSelection(chosen -> updateSelection());
        cardGrid.lastCell(this.dropCell);
        cardGrid.select(selected);
        this.grid = cardGrid;
        this.gridCardWidth = cardWidth;
        return cardGrid;
    }

    // Following the draft

    @Override
    public void draftChanged() {
        CreatorDraft draft = this.editor.draft();
        Set<String> dirty = new HashSet<>();
        for (String change : this.editor.changes()) {
            if (change.startsWith(CreatorDraft.FIGURE)) {
                int end = change.indexOf('/', CreatorDraft.FIGURE.length());
                dirty.add(end < 0 ? change.substring(CreatorDraft.FIGURE.length())
                        : change.substring(CreatorDraft.FIGURE.length(), end));
            }
        }
        this.dirtyIds = dirty;
        Map<String, List<Component>> found = new HashMap<>();
        Arrays.fill(this.counts, 0);
        for (FigureDraft figure : draft.figures()) {
            List<Component> wrong = problemsOf(figure);
            if (!wrong.isEmpty()) {
                found.put(figure.id(), wrong);
            }
            for (Filter which : Filter.values()) {
                if (accepts(which, figure, !wrong.isEmpty())) {
                    this.counts[which.ordinal()]++;
                }
            }
        }
        this.problems = found;
        long bytes = draft.forecast().collectionBytes();
        this.counter = tr("card.joined", tr("figures.count", draft.figures().size(), WorldContentLimits.MAX_FIGURES),
                UiQuotaMeter.text((int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024),
                        (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024)));
        Component full = draft.isFull() ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null;
        this.addButton.disable(full);
        this.severalButton.disable(full);
        this.dropZone.disable(full);
        refreshItems();
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
     * Everything that keeps a figure from being saved or shown as it should: a file it still
     * needs, a file the server lost or could not load, and a picked file that breaks a rule.
     */
    private static List<Component> problemsOf(FigureDraft figure) {
        List<Component> found = new ArrayList<>();
        boolean skin = figure.kind() == Kind.SKIN;
        if (figure.texture() == null || !skin && figure.model() == null) {
            found.add(tr(skin ? "figures.problem.no_skin"
                    : figure.texture() == null && figure.model() == null ? "figures.problem.no_files"
                    : figure.model() == null ? "figures.problem.no_model" : "figures.problem.no_texture"));
        }
        BlobInfo model = figure.model() != null ? figure.model().info() : null;
        fileProblems(found, figure.texture(), skin ? FilePicker.Slot.SKIN : FilePicker.Slot.MODEL_TEXTURE,
                info -> skin ? FileChecks.skin(info) : FileChecks.modelTexture(info, model));
        if (!skin) {
            fileProblems(found, figure.model(), FilePicker.Slot.MODEL, FileChecks::geo);
            fileProblems(found, figure.animation(), FilePicker.Slot.ANIMATION, FileChecks::animation);
        }
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            fileProblems(found, variant.texture(), skin ? FilePicker.Slot.SKIN : FilePicker.Slot.MODEL_TEXTURE,
                    info -> List.of());
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

    /** Shows the figures the chip and the search pick, in the draft's order. */
    private void refreshItems() {
        if (this.grid == null) {
            return;
        }
        String query = this.search.strip().toLowerCase(Locale.ROOT);
        List<FigureDraft> shown = new ArrayList<>();
        for (FigureDraft figure : this.editor.draft().figures()) {
            if (accepts(this.filter, figure, this.problems.containsKey(figure.id())) && (query.isEmpty()
                    || figure.name().toLowerCase(Locale.ROOT).contains(query) || figure.id().contains(query))) {
                shown.add(figure);
            }
        }
        List<FigureDraft> before = this.grid.items();
        FigureDraft was = single();
        this.grid.setItems(shown);
        keepRenamed(before, was, shown);
        // Only the whole list in its own order can be reordered by dragging
        boolean whole = this.filter == Filter.ALL && query.isEmpty();
        this.grid.onReorder(whole ? this::moveFigure : null);
        this.chips.select(this.filter.ordinal());
        updateSelection();
    }

    /**
     * Keeps the one selected card selected when its figure came back under another id, as when
     * Undo takes back the rename of a new figure: the grid tells cards apart by id, so it lost
     * the card. Only the same number of cards, with an id at its place that none had before,
     * counts as the same figure.
     */
    private void keepRenamed(List<FigureDraft> before, @Nullable FigureDraft was, List<FigureDraft> shown) {
        if (was == null || this.grid == null || !this.grid.selected().isEmpty() || before.size() != shown.size()) {
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
        this.grid.select(List.of(now));
    }

    private List<Component> chipLabels() {
        return List.of(tr("figures.filter.all", this.counts[0]), tr("figures.filter.skins", this.counts[1]),
                tr("figures.filter.models", this.counts[2]), tr("figures.filter.retired", this.counts[3]),
                tr("figures.filter.errors", this.counts[4]));
    }

    // Selection

    private List<FigureDraft> selected() {
        return this.grid != null ? this.grid.selected() : List.of();
    }

    @Nullable
    private FigureDraft single() {
        List<FigureDraft> chosen = selected();
        return chosen.size() == 1 ? chosen.get(0) : null;
    }

    /** Shows the bar for several figures, or the panel's buttons for one, as the selection now is. */
    private void updateSelection() {
        List<FigureDraft> chosen = selected();
        Font font = Minecraft.getInstance().font;

        // The bar under the grid, after its "n selected"
        boolean several = chosen.size() >= 2;
        int x = this.gridArea.x() + font.width(tr("figures.selected", chosen.size())) + 8;
        boolean anyEnabled = false;
        boolean anyRetired = false;
        for (FigureDraft figure : chosen) {
            anyEnabled |= figure.enabled();
            anyRetired |= !figure.enabled();
        }
        this.retireAll.disable(anyEnabled ? null : tr("figures.none_to_retire"));
        this.restoreAll.disable(anyRetired ? null : tr("figures.none_to_restore"));
        Component noRoom = this.editor.draft().figures().size() + chosen.size() > WorldContentLimits.MAX_FIGURES
                ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null;
        this.duplicateAll.disable(noRoom);
        for (UiButton button : List.of(this.retireAll, this.restoreAll, this.sizeAll, this.duplicateAll,
                this.clearSelection)) {
            int width = button == this.clearSelection ? font.width(button.label()) + 6
                    : UiButton.widthFor(font, button.label(), false);
            button.at(x, this.bottomY, width, UiButton.SMALL_HEIGHT);
            button.setVisible(several && x + width <= this.gridArea.right());
            x += width + 3;
        }

        // The inspector shows the one figure selected, and where its card is among those shown
        FigureDraft figure = chosen.size() == 1 ? chosen.get(0) : null;
        List<FigureDraft> shown = this.grid != null ? this.grid.items() : List.of();
        this.inspector.show(figure, figure != null ? Math.max(0, shown.indexOf(figure)) : 0, shown.size(),
                chosen.size());
    }

    private void clearSelection() {
        if (this.grid != null) {
            this.grid.clearSelection();
            updateSelection();
        }
    }

    /**
     * Selects the card before or after the one selected among those shown, going round at the
     * ends, as the inspector's arrows and the arrow keys do.
     */
    void step(int delta) {
        FigureDraft figure = single();
        if (figure == null || this.grid == null) {
            return;
        }
        List<FigureDraft> shown = this.grid.items();
        int index = shown.indexOf(figure);
        if (index < 0 || shown.size() < 2) {
            return;
        }
        FigureDraft next = shown.get(Math.floorMod(index + delta, shown.size()));
        this.grid.select(List.of(next));
        this.grid.reveal(next);
        updateSelection();
    }

    /** Keeps a figure selected after its id changed, since the grid tells its cards apart by id. */
    void keepSelected(FigureDraft figure) {
        if (this.grid != null) {
            this.grid.select(List.of(figure));
        }
    }

    /** After a figure left the draft, selects the one that took its place, or the last. */
    void selectNear(int index) {
        if (this.grid == null) {
            return;
        }
        List<FigureDraft> shown = this.grid.items();
        if (!shown.isEmpty()) {
            FigureDraft next = shown.get(Math.max(0, Math.min(index, shown.size() - 1)));
            this.grid.select(List.of(next));
            this.grid.reveal(next);
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

    /** Selects figures just added and scrolls to the first, showing every figure if a filter hid them. */
    void reveal(List<FigureDraft> added) {
        if (added.isEmpty() || this.grid == null) {
            return;
        }
        if (this.filter != Filter.ALL || !this.search.isEmpty()) {
            this.filter = Filter.ALL;
            if (this.searchBox != null) {
                // The field's responder shows every figure again
                this.searchBox.setValue("");
            }
            this.search = "";
            refreshItems();
        }
        this.grid.select(added);
        this.grid.reveal(added.get(0));
        updateSelection();
    }

    /** Retires the one selected figure from the draw, or restores it. */
    void toggleRetired() {
        FigureDraft figure = single();
        if (figure != null) {
            setEnabled(!figure.enabled());
        }
    }

    private void setEnabled(boolean enabled) {
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
    }

    private void openSizeMenu() {
        List<FigureDraft> chosen = selected();
        UiMenu menu = new UiMenu();
        List<Float> scales = WorldCollectionRecord.SCALES;
        int common = -1;
        for (int i = 0; i < scales.size(); i++) {
            float scale = scales.get(i);
            menu.item(UiSizeSlider.label(i), () -> setScale(chosen, scale));
            boolean all = !chosen.isEmpty();
            for (FigureDraft figure : chosen) {
                all &= figure.scale() == scale;
            }
            if (all) {
                common = i;
            }
        }
        if (common >= 0) {
            menu.highlight(common);
        }
        this.editor.openMenu(menu, this.sizeAll.bounds());
    }

    private void setScale(List<FigureDraft> chosen, float scale) {
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

    void duplicateSelected() {
        List<FigureDraft> chosen = selected();
        if (chosen.isEmpty()) {
            return;
        }
        CreatorDraft draft = this.editor.draft();
        if (draft.isFull()) {
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

    /** Drops the one selected figure if it was never saved, which just goes. */
    void discard() {
        FigureDraft figure = single();
        if (figure == null || !figure.isNew()) {
            return;
        }
        int index = this.grid != null ? this.grid.items().indexOf(figure) : -1;
        if (this.editor.draft().removeFigure(figure.id())) {
            this.editor.changed();
            selectNear(index);
        }
    }

    private void moveFigure(int from, int to) {
        // Each drag is an undo step of its own
        this.editor.draft().checkpoint();
        this.editor.draft().moveFigure(from, to);
        this.editor.changed();
    }

    // Adding figures from files

    /**
     * Opens the bulk add over the editor, with the files dropped on the grid or chosen through
     * its last cell, or with none for "Add several…".
     */
    private void openBulkAdd(List<FilePicker.PickResult> results) {
        if (this.editor.draft().isFull()) {
            this.editor.status(tr("figures.full", WorldContentLimits.MAX_FIGURES), Theme.RED);
            return;
        }
        this.editor.status(Component.empty());
        this.editor.openChild(new BulkAddScreen(this.editor, results));
    }

    // Drawing

    @Override
    public boolean takesFiles() {
        return true;
    }

    @Override
    public void tick() {
        if (this.focusNameSoon) {
            this.focusNameSoon = false;
            this.inspector.focusName();
        }
    }

    @Override
    public void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.partialTick = partialTick;
        Font font = Minecraft.getInstance().font;
        CreatorDraft draft = this.editor.draft();

        // The counters, beside the chips or else at the end of the bottom row
        Component counter = this.counter;
        int counterWidth = font.width(counter);
        int chipsRight = this.chips.bounds().x() + this.chips.naturalWidth();
        boolean besideChips = chipsRight + 8 + counterWidth <= this.gridArea.right();
        this.chips.setRoom(besideChips ? this.chips.naturalWidth() : this.gridArea.width());
        List<FigureDraft> chosen = selected();
        int bottomRight = this.gridArea.right();
        if (besideChips) {
            graphics.drawString(font, counter, this.gridArea.right() - counterWidth, this.chipsY + 4, HINT, false);
        } else if (chosen.size() < 2) {
            // The selection bar takes the whole row while it shows
            graphics.drawString(font, counter, bottomRight - counterWidth, this.bottomY + 4, HINT, false);
            bottomRight -= counterWidth + 8;
        }

        int textY = this.bottomY + 4;
        if (chosen.size() >= 2) {
            graphics.drawString(font, tr("figures.selected", chosen.size()), this.gridArea.x(), textY, Theme.GOLD,
                    false);
        } else {
            boolean whole = this.filter == Filter.ALL && this.search.strip().isEmpty();
            Draw.text(graphics, font, tr(whole ? "figures.hint" : "figures.hint_filtered").getString(),
                    this.gridArea.x(), textY, Math.max(0, bottomRight - this.gridArea.x()), Theme.FAINT);
        }
        if (this.grid != null && this.grid.items().isEmpty() && !draft.figures().isEmpty()) {
            Draw.centred(graphics, font, tr("figures.no_match").getString(), this.gridArea.centerX(),
                    this.gridArea.y() + FigureCard.HEIGHT + 12, this.gridArea.width(), Theme.MUTED);
        }
        this.inspector.drawContents(graphics);
    }

    // Input

    @Override
    public boolean shortcut(UiInput.Key key) {
        if (key.ctrl() && key.is(InputConstants.KEY_F) && this.searchBox != null) {
            this.editor.focusNode(null);
            this.editor.setFocused(this.searchBox);
            return true;
        }
        // Only asked while no field or node has the keyboard, which keeps the arrows of each
        if (!key.ctrl() && !key.shift() && (key.is(InputConstants.KEY_LEFT) || key.is(InputConstants.KEY_RIGHT))
                && single() != null) {
            step(key.is(InputConstants.KEY_LEFT) ? -1 : 1);
            return true;
        }
        return false;
    }

    /**
     * The chips that pick which figures the grid shows: dark boxes, the chosen one outlined and
     * written in gold. Given less room than their labels need, they share it and cut them.
     */
    private static final class FilterChips extends UiNode.Box {
        static final int HEIGHT = 16;
        private static final int PADDING = 6;
        private static final int CHIP_GAP = 3;

        private final Supplier<List<Component>> labels;
        private final IntConsumer onSelect;
        private int selected;
        private int room = -1;

        FilterChips(Supplier<List<Component>> labels, IntConsumer onSelect) {
            this.labels = labels;
            this.onSelect = onSelect;
            this.height = HEIGHT;
        }

        void select(int index) {
            this.selected = index;
        }

        /** How much of the node's width the chips may take; the rest is left to the counters. */
        void setRoom(int room) {
            this.room = room;
        }

        int naturalWidth() {
            Font font = Minecraft.getInstance().font;
            int total = 0;
            for (Component label : labels.get()) {
                total += font.width(label) + 2 * PADDING + CHIP_GAP;
            }
            return Math.max(0, total - CHIP_GAP);
        }

        private int[] widths(List<Component> shown) {
            Font font = Minecraft.getInstance().font;
            int available = room < 0 ? width : Math.min(width, room);
            int[] widths = new int[shown.size()];
            int natural = naturalWidth();
            for (int i = 0; i < shown.size(); i++) {
                int own = font.width(shown.get(i)) + 2 * PADDING;
                widths[i] = natural <= available ? own
                        : Math.max(12, own * (available - CHIP_GAP * (shown.size() - 1)) / Math.max(1, natural
                        - CHIP_GAP * (shown.size() - 1)));
            }
            return widths;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            List<Component> shown = labels.get();
            int[] widths = widths(shown);
            int left = x;
            for (int i = 0; i < shown.size(); i++) {
                boolean on = i == selected;
                boolean over = mouseX >= left && mouseX < left + widths[i] && mouseY >= y && mouseY < y + height;
                graphics.fill(left, y, left + widths[i], y + height, Theme.CONTROL);
                Draw.outline(graphics, left, y, widths[i], height,
                        on ? Theme.GOLD : over ? 0xFF777777 : Theme.CONTROL_OUTLINE);
                String text = Draw.cut(font, shown.get(i).getString(), widths[i] - 6);
                graphics.drawString(font, text, left + (widths[i] - font.width(text)) / 2, y + 4,
                        on ? Theme.GOLD : Theme.CONTROL_TEXT, false);
                if (over && !text.equals(shown.get(i).getString())) {
                    UiTooltip.set(shown.get(i));
                }
                left += widths[i] + CHIP_GAP;
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            int[] widths = widths(labels.get());
            int left = x;
            for (int i = 0; i < widths.length; i++) {
                if (click.x() >= left && click.x() < left + widths[i]) {
                    selected = i;
                    onSelect.accept(i);
                    return true;
                }
                left += widths[i] + CHIP_GAP;
            }
            return true;
        }
    }

    /**
     * The grid's last cell: the zone for figure files. Given the room, it is the zone as drawn
     * anywhere else; in a narrow cell it shows a short label instead, and still takes drops and
     * clicks.
     */
    private static final class DropCell extends UiNode.Box {
        private static final int MIN_FULL_WIDTH = 150;
        private final UiDropZone zone;

        DropCell(UiDropZone zone) {
            this.zone = zone;
        }

        @Override
        public UiNode.Box at(int x, int y, int width, int height) {
            super.at(x, y, width, height);
            zone.at(x, y, width, height);
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (width >= MIN_FULL_WIDTH) {
                zone.draw(graphics, mouseX, mouseY, partialTick);
                return;
            }
            Font font = Minecraft.getInstance().font;
            boolean over = hovered(mouseX, mouseY);
            graphics.fill(x, y, x + width, y + height, over ? 0x18FFD84A : 0x0DFFD84A);
            Draw.dashed(graphics, x, y, width, height, over ? Theme.GOLD : 0xFF8A7A3A);
            Draw.centred(graphics, font, "+", x + width / 2, y + height / 2 - 14, width - 4, Theme.GOLD);
            String text = tr(FilePicker.isReading() ? "drop.reading_many" : "figures.drop_short").getString();
            int top = y + height / 2 - 2;
            for (String line : Draw.fit(font, text, width - 6, 3)) {
                Draw.centred(graphics, font, line, x + width / 2, top, width - 4, 0xFFE8D9A0);
                top += Theme.LINE_HEIGHT;
            }
            if (over) {
                UiTooltip.set(List.of(tr("figures.drop"), tr("figures.drop_help")));
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            return zone.click(click);
        }

        @Override
        @Nullable
        public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
            return zone.dropZones(mouseX, mouseY, zones);
        }
    }
}
