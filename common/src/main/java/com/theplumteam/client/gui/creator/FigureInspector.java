package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiFigureView;
import com.theplumteam.client.gui.creator.ui.UiFoldRow;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.BulkPlan;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.client.worldcontent.OwnSkin;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.ModelBounds;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The figure inspector: the column of the editor's Figures tab, beside the big preview, which
 * shows and edits the figures selected in the strip. Everything in it scrolls as one where the
 * canvas is short.
 *
 * <p>One figure selected: its name under a small "NAME" label, typed in place, its id in the
 * field's tooltip (a new figure's follows the name, fixed once the server has the figure; while a
 * save that puts it is on its way it holds still and follows again once the save is over), and a
 * red line under the field while the name cannot be saved. Then its files: a skin figure's skin,
 * picked with "Choose PNG…" (the file dialog of a zone the column keeps unseen over that part,
 * with the typed path where no dialog can be shown) or "Use my skin" ({@link OwnSkin}); a model
 * figure's model, texture and optional poses, each in its own one-line zone that names its file
 * and shows a chip only for a check it fails, the rest in its tooltip. Under them one line says
 * what the files are, in green with a check, or the first thing wrong with them; where it is
 * short of room it leaves out its last details first. Then the rarity, a row with the figure's
 * chance in one draw that opens a menu of the four rarities, each with the chance it would give;
 * and "More settings" ({@link FigureExtrasPanel}), which takes the column's place for the size,
 * the type, the variants, the face on the box and duplicating. Their hints take a short form, or
 * none, where the column is narrow.
 *
 * <p>Under the column, on the footer's line at the right, one red link says what leaving the
 * figure means here: "Discard" for a figure never saved, "Delete figure" for a draft
 * collection's, "Retire from the draw" for a published or archived collection's, whose players
 * may own it, and, for a retired one, an ordinary link that brings it back. Each of the red ones
 * takes a toast that undoes it.
 *
 * <p>Several figures selected: how many, and rows that change them together (their size, a
 * duplicate of each, and taking them out of the draw or back), each one undo step. With none
 * selected the page hides the preview and the column and says how to pick one.
 *
 * <p>The preview fits a model figure by its bounds, as tall and as wide, turned, as it is drawn,
 * and keeps its "View ⌄" menu inside the content panel.
 *
 * <p>A file dropped on the preview goes to the figure shown, through a hidden zone laid over the
 * preview that hands a skin to its skin, and a model's files each to its own zone by its kind; the
 * which-slot menu names only the zones the column shows. A skin picked for a new figure still
 * called "New figure" names it after its file ("krilin_base" becomes "Krilin Base"), or after the
 * player for "Use my skin", and its id follows. Every change goes to the editor's draft, which
 * the collection's saver sends, and the editor's Undo takes it back. The inspector lives as long
 * as the page and keeps its nodes across rebuilds.
 */
final class FigureInspector {
    private static final int LABEL = 7;
    private static final int FIELD = 20;
    private static final int LINE = 10;
    private static final int LINK = 12;
    // The gaps of the column, as Quick Skin's figure mock-up spaces them
    private static final int NAME_TOP = 10;
    private static final int PROBLEM_TOP = 34;
    private static final int AFTER_NAME = 33;
    private static final int FILES_LABEL = 11;
    private static final int FILES_TOP = 19;
    private static final int BUTTON_GAP = 5;
    private static final int STACKED_GAP = 4;
    private static final int CHECK_TOP = 7;
    private static final int ROWS_TOP = 9;
    private static final int ZONE_GAP = 3;
    // A chance this small or smaller shows two decimals, this large or larger none
    private static final double SMALL_CHANCE = 0.1;
    private static final double LARGE_CHANCE = 9.95;

    private final CreatorEditorScreen editor;
    private final FiguresPage page;
    private final UiFigureView view = new UiFigureView();
    private final Column column = new Column();
    private final FigureExtrasPanel extras;
    // One figure
    private final SmallLabel nameLabel = new SmallLabel(() -> tr("name"));
    private final FigureExtrasPanel.NameField nameField;
    private final TextLine problemLine = new TextLine();
    private final SmallLabel filesLabel = new SmallLabel(() -> tr(this.shownKind() == Kind.SKIN
            ? "bulk.type.skin" : "figures.files"));
    private final UiButton choosePng;
    private final UiButton ownSkin;
    private final CheckLine checkLine = new CheckLine();
    private final UiFoldRow rarityRow;
    private final UiFoldRow moreRow;
    private final UiButton footer;
    // Several figures, or none
    private final Message message = new Message();
    private final UiFoldRow sizeAll;
    private final UiFoldRow duplicateAll;
    private final UiFoldRow stateAll;
    // The model turned as the preview first shows it, a quarter of the way round from its front
    private static final double TURN = Math.toRadians(25.0);
    // How a model is fitted to the collection screen's cell, in its pixels, as the materializer does
    private static final double FIT_HEIGHT = 15.0;
    private static final double FIT_WIDTH = 9.0;
    private static final double MAX_FIT = 4.0;
    // The zone laid over the preview, which hands a dropped file to the zone of its kind
    private final UiDropZone previewZone;
    // Set while "Use my skin" reads the skin, so a second press waits for it
    private boolean readingOwnSkin;

    // The figures selected, and the one shown when it is one
    private List<FigureDraft> selected = List.of();
    @Nullable
    private FigureDraft figure;
    // The figure the view last showed, whose skin it keeps showing until another is selected
    @Nullable
    private FigureDraft viewFigure;
    // The chance the shown figure would have at each rarity, worked out once per change
    private final Map<Rarity, Double> chances = new EnumMap<>(Rarity.class);

    // The file zones of the figure shown. They are made again for another figure or kind, so a
    // file still being read when the operator moves on goes to the figure it was picked for; a
    // rename, which changes a new figure's id, keeps them.
    @Nullable
    private FigureDraft zonesFigure;
    @Nullable
    private Kind zonesKind;
    private List<FileSlot> slots = List.of();
    private int slotLabelWidth;

    // Where build() put the column, and the footer's line
    private UiNode.Rect area = UiNode.Rect.EMPTY;
    private int footerY;
    private int right;

    FigureInspector(CreatorEditorScreen editor, FiguresPage page) {
        this.editor = editor;
        this.page = page;
        this.view.setFigure(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT), null);
        this.extras = new FigureExtrasPanel(editor, this, this.view);
        this.nameField = new FigureExtrasPanel.NameField(tr("inspector.name_hint"), tr("name_rule"), this::renamed,
                () -> this.editor.draft().checkpoint()).tooltip(this::idTooltip);
        this.choosePng = new UiButton(tr("figures.choose_png"), UiButton.Style.SECONDARY, this::chooseSkin);
        this.choosePng.setTooltip(tr("drop.label.skin"));
        this.ownSkin = new UiButton(tr("inspector.use_my_skin"), UiButton.Style.SECONDARY, this::useOwnSkin);
        this.ownSkin.setTooltip(tr("inspector.use_my_skin_tip"));
        this.rarityRow = new UiFoldRow(Component.empty(), null, this::openRarityMenu).lineAbove(true);
        this.moreRow = new UiFoldRow(tr("figures.more_settings"), null, this.extras::open)
                .hint(tr("figures.more_hint"), Component.empty());
        this.footer = new UiButton(Component.empty(), UiButton.Style.DANGER_TEXT, this::footerAction);
        this.sizeAll = new UiFoldRow(tr("inspector.size"), null, this::openSizeAllMenu).lineAbove(true);
        this.duplicateAll = new UiFoldRow(tr("figures.duplicate"), null, page::duplicateSelected).chevron(false);
        this.stateAll = new UiFoldRow(Component.empty(), null, this::stateAllAction).chevron(false);
        this.previewZone = UiDropZone.routing(editor, tr("figures.files"), this::zoneFor);
        this.view.dropZone(this.previewZone);
        this.view.within(editor::contentPanel);
    }

    // Building

    /**
     * Adds the preview, the column and the footer's link for the room the page gives them.
     *
     * @param preview the big preview's bounds
     * @param column  the column's bounds, its content as wide as given, the scroll bar beyond it
     * @param footerY the content's footer line, where the link sits
     * @param right   the content's right edge, which the link keeps to
     */
    void build(UiNode.Rect preview, UiNode.Rect column, int footerY, int right) {
        this.area = column;
        this.footerY = footerY;
        this.right = right;
        Font font = Minecraft.getInstance().font;
        this.slotLabelWidth = 0;
        for (FilePicker.Slot slot : List.of(FilePicker.Slot.MODEL, FilePicker.Slot.MODEL_TEXTURE,
                FilePicker.Slot.ANIMATION)) {
            this.slotLabelWidth = Math.max(this.slotLabelWidth, font.width(slotLabel(slot)) + 8);
        }
        this.slotLabelWidth = Math.min(this.slotLabelWidth, Math.max(30, column.width() / 2));
        this.view.at(preview.x(), preview.y(), preview.width(), preview.height());
        this.editor.add(this.view);
        this.editor.budgetNode(this.view);
        this.column.at(column.x(), column.y(), column.width() + UiScrollPane.BAR + 2, column.height());
        this.editor.add(this.column);
        this.editor.add(this.footer);
        relayout(false);
    }

    /** Shows or hides the preview, the column and the footer's link, as an empty collection does. */
    void setVisible(boolean visible) {
        this.view.setVisible(visible);
        this.column.setVisible(visible);
        if (!visible) {
            this.footer.setVisible(false);
        }
    }

    /**
     * Shows the selection: one figure to edit, or several to change together; with none the page
     * hides the inspector. Called whenever the draft or the selection changes.
     */
    void show(List<FigureDraft> chosen) {
        FigureDraft before = this.figure;
        this.selected = List.copyOf(chosen);
        FigureDraft shown = chosen.size() == 1 ? chosen.get(0) : null;
        boolean another = shown != before && (shown == null || before == null || !before.id().equals(shown.id()));
        if (another) {
            // Another figure starts on its own rows, at their top
            this.extras.close();
        }
        this.figure = shown;
        FigureDraft previewed = chosen.isEmpty() ? null : chosen.get(0);
        this.view.setFigure(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT),
                previewed != null ? previewed.id() : null);
        if (previewed != this.viewFigure && (this.viewFigure == null || previewed == null
                || !this.viewFigure.id().equals(previewed.id()))) {
            // Another figure starts in its own skin; an undo, which puts a copy in its place, keeps it
            this.view.setSkinIndex(0);
            this.view.setVariants(List.of());
        }
        this.viewFigure = previewed;
        if (previewed != null) {
            this.view.setGreyed(!previewed.enabled());
            this.view.setFigureScale(previewed.scale());
            float[] extent = extent(previewed);
            this.view.setExtent(extent[0], extent[1]);
            this.view.setEmptyText(tr(previewed.isComplete() ? "inspector.no_preview" : "inspector.needs_files"));
        } else {
            this.view.setEmptyText(Component.empty());
        }
        if (shown != null) {
            this.nameField.show(shown.name());
            refreshZones(shown);
            refreshRarity(shown);
            refreshFooter(shown);
            this.ownSkin.disable(this.readingOwnSkin ? tr("inspector.use_my_skin_reading") : null);
            this.extras.show(shown);
        } else {
            this.footer.setVisible(false);
            refreshSeveral(chosen);
        }
        relayout(another);
    }

    /** Gives the name field the keyboard, as Enter or a double click on a figure does. */
    void focusName() {
        if (this.figure == null || !this.column.visible()) {
            return;
        }
        if (this.extras.level() != FigureExtrasPanel.Level.NONE) {
            this.extras.close();
            relayout(true);
        }
        this.editor.setFocused(null);
        this.editor.focusNode(this.column);
        this.column.focus(this.nameField);
        this.nameField.startTyping();
        UiNode.Rect at = this.nameField.bounds();
        this.column.reveal(at.y(), at.bottom());
    }

    /** The new figure whose name field has the keyboard, or null. */
    @Nullable
    String typingFigureId() {
        FigureDraft shown = this.figure;
        return shown != null && shown.isNew() && this.nameField.isTyping() ? shown.id() : null;
    }

    /** Escape: "More settings" goes up one level; false when it was not open. */
    boolean escape() {
        return this.extras.back();
    }

    // The column's layout

    /**
     * Lays the column out again for what it shows now. Nodes that stay keep their press and the
     * keyboard, so a name being typed goes on being typed when its problem line comes or goes.
     *
     * @param top whether to scroll back to the top, as when a sub-view opens or closes
     */
    void relayout(boolean top) {
        UiNode.Rect box = this.area;
        if (box.width() <= 0) {
            return;
        }
        List<UiNode> nodes = new ArrayList<>();
        int x = box.x();
        int width = box.width();
        FigureDraft shown = this.figure;
        if (shown == null) {
            layoutSeveral(nodes, x, box.y(), width);
        } else if (this.extras.level() != FigureExtrasPanel.Level.NONE) {
            this.extras.layout(nodes, x, box.y(), width);
        } else {
            layoutFigure(nodes, shown, x, box.y(), width);
        }
        this.column.setChildren(nodes);
        if (top) {
            this.column.scrollTo(0, true);
        }
        // The footer's link for one figure, kept to the right; a label wider than the column is cut
        Font font = Minecraft.getInstance().font;
        int linkWidth = Math.min(width, font.width(this.footer.label()));
        this.footer.at(this.right - linkWidth, this.footerY + 6, linkWidth, LINK);
        this.footer.setVisible(shown != null && this.column.visible());
    }

    private void layoutFigure(List<UiNode> nodes, FigureDraft shown, int x, int top, int width) {
        this.nameLabel.at(x, top, width, LABEL);
        nodes.add(this.nameLabel);
        this.nameField.at(x, top + NAME_TOP, width, FIELD);
        nodes.add(this.nameField);
        int next = top + AFTER_NAME;
        Component problem = nameProblem(shown);
        if (problem != null) {
            this.problemLine.set(problem, Theme.RED);
            this.problemLine.at(x, top + PROBLEM_TOP, width, LINE);
            nodes.add(this.problemLine);
            next = top + PROBLEM_TOP + LINE + 2;
        }
        this.filesLabel.at(x, next + FILES_LABEL - 3, width, LABEL);
        nodes.add(this.filesLabel);
        int y = next + FILES_TOP;
        if (shown.kind() == Kind.SKIN) {
            int chooseWidth = Math.min(width, this.choosePng.naturalWidth());
            int ownWidth = Math.min(width, this.ownSkin.naturalWidth());
            boolean side = chooseWidth + BUTTON_GAP + ownWidth <= width;
            this.choosePng.at(x, y, chooseWidth, UiButton.HEIGHT);
            this.ownSkin.at(side ? x + chooseWidth + BUTTON_GAP : x, side ? y : y + UiButton.HEIGHT + STACKED_GAP,
                    ownWidth, UiButton.HEIGHT);
            int buttonsBottom = this.ownSkin.bounds().bottom();
            this.checkLine.at(x, buttonsBottom + CHECK_TOP, width, LINE);
            UiDropZone skin = this.slots.isEmpty() ? null : this.slots.get(0).zone;
            if (skin != null) {
                // The skin's zone lies unseen over its part of the column, under its buttons
                skin.at(x, next, width, buttonsBottom + CHECK_TOP + LINE - next);
                nodes.add(skin);
            }
            nodes.add(this.choosePng);
            nodes.add(this.ownSkin);
            nodes.add(this.checkLine);
            y = buttonsBottom + CHECK_TOP + LINE + ROWS_TOP;
        } else {
            for (FileSlot slot : this.slots) {
                slot.zone.labelWidth(this.slotLabelWidth);
                slot.zone.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
                nodes.add(slot.zone);
                y += UiDropZone.COMPACT_HEIGHT + ZONE_GAP;
            }
            this.checkLine.at(x, y - ZONE_GAP + CHECK_TOP, width, LINE);
            nodes.add(this.checkLine);
            y += CHECK_TOP - ZONE_GAP + LINE + ROWS_TOP;
        }
        this.rarityRow.at(x, y, width, UiFoldRow.HEIGHT);
        nodes.add(this.rarityRow);
        y += UiFoldRow.HEIGHT;
        this.moreRow.at(x, y, width, UiFoldRow.HEIGHT);
        nodes.add(this.moreRow);
    }

    private void layoutSeveral(List<UiNode> nodes, int x, int top, int width) {
        int height = this.message.heightFor(width);
        this.message.at(x, top, width, height);
        nodes.add(this.message);
        if (this.selected.size() < 2) {
            return;
        }
        int y = top + height + 6;
        for (UiFoldRow row : List.of(this.sizeAll, this.duplicateAll, this.stateAll)) {
            if (row.visible()) {
                row.at(x, y, width, UiFoldRow.HEIGHT);
                nodes.add(row);
                y += UiFoldRow.HEIGHT;
            }
        }
    }

    // One figure

    // Why the name cannot be saved, or null when it can
    @Nullable
    private static Component nameProblem(FigureDraft shown) {
        if (shown.name().isBlank()) {
            return tr("inspector.name_empty");
        }
        return WorldCollectionRecord.isPlainText(shown.name()) ? null : tr("name_rule");
    }

    @Nullable
    private Kind shownKind() {
        FigureDraft shown = this.figure;
        return shown != null ? shown.kind() : null;
    }

    // The name field's tooltip: the id the figure is saved under, and whether it still follows the name
    private Component idTooltip() {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return Component.empty();
        }
        return tr(shown.isNew() ? "figures.id_follows" : "figures.id_tip", shown.id());
    }

    private void refreshRarity(FigureDraft shown) {
        Rarity rarity = shown.rarity();
        this.rarityRow.setLabel(tr("extras.rarity." + rarity.name().toLowerCase(Locale.ROOT)));
        this.rarityRow.dot(CollectionStatus.rarityColour(rarity), null);
        workOutChances(shown);
        Double chance = this.chances.get(rarity);
        // Where the column is narrow, each gives way to a short form
        if (!shown.enabled()) {
            this.rarityRow.hint(tr("extras.chance_retired"), tr("figures.chance_retired_short"));
        } else if (chance == null) {
            this.rarityRow.hint(tr("extras.chance_needs_files"), tr("figures.chance_no_files_short"));
        } else {
            this.rarityRow.hint(tr("figures.chance_row", percent(chance)), percent(chance));
        }
    }

    /**
     * A model figure's height and its width turned as the preview first shows it, against the
     * cell the collection screen draws it in at size 1: fitted from its bounds as the
     * materializer fits it, at most 15 pixels of 16 tall and 9 wide. Zeros for a skin, or a model
     * whose bounds are not known yet, which the preview fits as usual.
     */
    private static float[] extent(FigureDraft figure) {
        BlobInfo model = figure.kind() == Kind.MODEL && figure.model() != null ? figure.model().info() : null;
        if (model == null || model.bounds().equals(ModelBounds.NONE)) {
            return new float[] {0.0f, 0.0f};
        }
        ModelBounds bounds = model.bounds();
        double height = bounds.height();
        double width = bounds.width();
        double depth = bounds.depth();
        double fit = Math.min(height > 0 ? FIT_HEIGHT / height : Double.POSITIVE_INFINITY,
                width > 0 ? FIT_WIDTH / width : Double.POSITIVE_INFINITY);
        fit = Double.isInfinite(fit) ? 1.0 : Math.min(fit, MAX_FIT);
        double turned = width * Math.cos(TURN) + depth * Math.sin(TURN);
        return new float[] {(float) (fit * height / 16.0), (float) (fit * turned / 16.0)};
    }

    /**
     * The chance the figure would have in one draw at each rarity, the others' rarities as they
     * are, from the editor's preview of the collection: what the machine would offer with the
     * draft as it is. None for a retired figure or one that misses a file, which is in no draw.
     */
    private void workOutChances(FigureDraft shown) {
        this.chances.clear();
        FigureCollection preview = CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT);
        if (preview == null || !shown.enabled()) {
            return;
        }
        CreatorDraft draft = this.editor.draft();
        WorldCollectionRecord.RarityWeights weights = draft.rarityWeights();
        long others = 0;
        boolean inDraw = false;
        for (FigureDefinition each : preview.getEnabledFigures()) {
            FigureDraft drafted = draft.figure(each.getId());
            if (drafted == null) {
                continue;
            }
            if (drafted.id().equals(shown.id())) {
                inDraw = true;
            } else {
                others += weights.weight(drafted.rarity());
            }
        }
        if (!inDraw) {
            return;
        }
        for (Rarity rarity : Rarity.values()) {
            int weight = weights.weight(rarity);
            this.chances.put(rarity, weight / (double) (others + weight));
        }
    }

    // A chance as "12 %", "4.5 %" or "0.25 %", in the language's way of writing a percentage and
    // its decimals, with no trailing zero: one decimal from 0.1, none from 10
    private static Component percent(double chance) {
        double percent = chance * 100.0;
        int decimals = percent >= LARGE_CHANCE ? 0 : percent >= SMALL_CHANCE || percent == 0 ? 1 : 2;
        return tr("identity.percent", CreatorScreen.decimal(percent, decimals));
    }

    // The four rarities, the figure's own ticked, each with the chance it would give
    private void openRarityMenu(UiNode.Rect anchor) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        UiMenu menu = new UiMenu();
        if (!shown.enabled() || this.chances.isEmpty()) {
            menu.heading(tr(!shown.enabled() ? "extras.chance_retired" : "extras.chance_needs_files"));
        }
        for (Rarity rarity : Rarity.values()) {
            Double chance = this.chances.get(rarity);
            menu.radio(tr("extras.rarity." + rarity.name().toLowerCase(Locale.ROOT)),
                    chance != null ? percent(chance) : null, shown.rarity() == rarity, () -> setRarity(shown, rarity));
        }
        menu.highlight(shown.rarity().ordinal() + (!shown.enabled() || this.chances.isEmpty() ? 1 : 0));
        this.editor.openMenu(menu, anchor);
    }

    private void setRarity(FigureDraft figureMade, Rarity rarity) {
        FigureDraft target = live(figureMade, figureMade.id());
        if (target == null || target.rarity() == rarity) {
            return;
        }
        // Each choice is an undo step of its own
        this.editor.draft().checkpoint();
        target.setRarity(rarity);
        this.editor.changed();
    }

    // The footer's link: what leaving this figure means here
    private void refreshFooter(FigureDraft shown) {
        if (shown.isNew()) {
            this.footer.setLabel(tr("figures.discard"));
            this.footer.setTooltip(tr("figures.discard_tip"));
            this.footer.setStyle(UiButton.Style.DANGER_TEXT);
        } else if (!shown.enabled()) {
            // Bringing a figure back is an ordinary link
            this.footer.setLabel(tr("figures.restore_one"));
            this.footer.setTooltip(null);
            this.footer.setStyle(UiButton.Style.LINK);
        } else if (this.editor.draft().isDraftCollection()) {
            this.footer.setLabel(tr("figures.delete"));
            this.footer.setTooltip(tr("inspector.delete_tip"));
            this.footer.setStyle(UiButton.Style.DANGER_TEXT);
        } else {
            this.footer.setLabel(tr("figures.retire_one"));
            this.footer.setTooltip(tr("figures.retire_tip"));
            this.footer.setStyle(UiButton.Style.DANGER_TEXT);
        }
    }

    // What the footer's link does for the one figure shown
    private void footerAction() {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        if (shown.isNew()) {
            this.page.discard();
        } else if (!shown.enabled()) {
            this.page.setEnabled(true);
        } else if (this.editor.draft().isDraftCollection()) {
            this.page.deleteSelected();
        } else {
            this.page.setEnabled(false);
        }
    }

    /** Duplicates the figures selected, as "More settings" › Duplicate does. */
    void duplicate() {
        this.page.duplicateSelected();
    }

    // Several figures

    private void refreshSeveral(List<FigureDraft> chosen) {
        if (chosen.isEmpty()) {
            // The page hides the column and says how to pick one
            return;
        }
        this.message.set(tr("figures.several", chosen.size()), tr("figures.several_hint"));
        // The size they share, or a dash
        float common = chosen.get(0).scale();
        boolean same = true;
        boolean anyEnabled = false;
        boolean allRetired = true;
        for (FigureDraft each : chosen) {
            same &= each.scale() == common;
            anyEnabled |= each.enabled();
            allRetired &= !each.enabled();
        }
        this.sizeAll.hint(Component.literal(same ? UiSizeSlider.scaleText(common) : "—"));
        this.duplicateAll.disable(this.editor.draft().figures().size() + chosen.size() > WorldContentLimits.MAX_FIGURES
                ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null);
        if (allRetired) {
            this.stateAll.setLabel(tr("figures.restore_one")).danger(false).disable(null);
            this.stateAll.setVisible(true);
        } else if (!this.editor.draft().isDraftCollection()) {
            this.stateAll.setLabel(tr("figures.retire_one")).danger(true)
                    .disable(anyEnabled ? null : tr("figures.none_to_retire"));
            this.stateAll.setVisible(true);
        } else {
            this.stateAll.setVisible(false);
        }
    }

    private void openSizeAllMenu(UiNode.Rect anchor) {
        List<FigureDraft> chosen = this.selected;
        if (chosen.isEmpty()) {
            return;
        }
        UiMenu menu = new UiMenu();
        List<Float> scales = WorldCollectionRecord.SCALES;
        int common = -1;
        for (int i = 0; i < scales.size(); i++) {
            float scale = scales.get(i);
            boolean all = true;
            for (FigureDraft each : chosen) {
                all &= each.scale() == scale;
            }
            if (all) {
                common = i;
            }
            menu.radio(UiSizeSlider.label(i), all, () -> this.page.setScale(chosen, scale));
        }
        menu.highlight(Math.max(0, common));
        this.editor.openMenu(menu, anchor);
    }

    private void stateAllAction() {
        boolean allRetired = true;
        for (FigureDraft each : this.selected) {
            allRetired &= !each.enabled();
        }
        this.page.setEnabled(allRetired);
    }

    // The file zones

    /** One zone of the figure shown, and what it shows now. */
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
            String figureId = shown.id();
            List<FileSlot> made = new ArrayList<>();
            if (shown.kind() == Kind.SKIN) {
                // Unseen: "Choose PNG…" opens its dialog, and the check line says what it holds
                FileSlot skin = slot(shown, figureId, FilePicker.Slot.SKIN);
                skin.zone.hidden();
                made.add(skin);
            } else {
                // Each named shortly under "Files", saying what it takes while it is empty
                // The file's name first: a chip only for a check it fails, the rest in its tooltip
                // and the check line under the zones
                FileSlot model = slot(shown, figureId, FilePicker.Slot.MODEL);
                model.zone.help(UiDropZone.defaultLabel(FilePicker.Slot.MODEL)).chips(UiDropZone.Chips.FAILED);
                made.add(model);
                FileSlot texture = slot(shown, figureId, FilePicker.Slot.MODEL_TEXTURE);
                texture.zone.help(UiDropZone.defaultLabel(FilePicker.Slot.MODEL_TEXTURE)).chips(UiDropZone.Chips.FAILED);
                made.add(texture);
                FileSlot poses = slot(shown, figureId, FilePicker.Slot.ANIMATION);
                poses.zone.help(tr("inspector.poses_help")).onRemove(() -> removePoses(shown, figureId))
                        .chips(UiDropZone.Chips.FAILED);
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
        UiDropZone zone = UiDropZone.single(this.editor, slot, slotLabel(slot), file -> picked(figure, figureId, slot,
                file)).labelWidth(this.slotLabelWidth);
        zone.at(0, 0, 0, UiDropZone.COMPACT_HEIGHT);
        return new FileSlot(slot, zone);
    }

    // A file's short name in the column, under its "Files" label, and in the which-slot menu
    private static Component slotLabel(FilePicker.Slot slot) {
        return switch (slot) {
            case MODEL -> tr("inspector.need.model");
            case MODEL_TEXTURE -> tr("inspector.need.texture");
            case ANIMATION -> tr("inspector.need.poses");
            default -> tr("inspector.need.skin");
        };
    }

    /**
     * The zone of the figure shown that a file dropped on the preview goes to, by its kind: a
     * skin figure's skin for a PNG, or a model figure's model, texture or poses; null for none.
     */
    @Nullable
    private UiDropZone zoneFor(Path path) {
        FigureDraft shown = this.figure;
        Path name = path.getFileName();
        if (shown == null || name == null) {
            return null;
        }
        String file = name.toString().toLowerCase(Locale.ROOT);
        FilePicker.Slot wanted;
        if (shown.kind() == Kind.SKIN) {
            wanted = file.endsWith(".png") ? FilePicker.Slot.SKIN : null;
        } else if (file.endsWith(".animation.json")) {
            wanted = FilePicker.Slot.ANIMATION;
        } else if (file.endsWith(".json")) {
            wanted = FilePicker.Slot.MODEL;
        } else {
            wanted = file.endsWith(".png") ? FilePicker.Slot.MODEL_TEXTURE : null;
        }
        for (FileSlot slot : this.slots) {
            if (slot.slot == wanted) {
                return slot.zone;
            }
        }
        return null;
    }

    /**
     * Shows a file in its zone unless the zone shows it already, as against the same model and
     * with the same news from the server, so a refusal message the zone shows stays. A local file
     * shows its own name, a stored one a plain label; a file the server lost or could not load
     * says so, and so does the first check a file fails.
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

    // "Choose PNG…": the skin zone's file dialog, or the typed path where there is none
    private void chooseSkin() {
        if (!this.slots.isEmpty() && this.slots.get(0).slot == FilePicker.Slot.SKIN) {
            this.slots.get(0).zone.choose();
        }
    }

    // A file picked for a figure, which may no longer be the one shown
    private void picked(FigureDraft figure, String figureId, FilePicker.Slot slot, FilePicker.LocalFile file) {
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
        String named = slot == FilePicker.Slot.ANIMATION ? null : BulkPlan.figureName(file.name());
        put(target, slot, FileRef.of(file), named);
    }

    /**
     * Puts a file in its slot of a figure as one undo step of its own, even a second one for the
     * same slot. A new figure still called "New figure" takes the name given with it, and its id
     * follows, in the same step.
     */
    private void put(FigureDraft target, FilePicker.Slot slot, FileRef ref, @Nullable String name) {
        CreatorDraft draft = this.editor.draft();
        boolean rename = name != null && !name.isBlank() && target.isNew()
                && target.name().equals(tr("figures.new_name").getString());
        // A put of the figure on its way holds its id still, which follows the name once it is over
        boolean held = rename && this.editor.isSaving(target.id());
        String field = slot == FilePicker.Slot.MODEL ? "model" : slot == FilePicker.Slot.ANIMATION ? "animation"
                : "texture";
        draft.change(CreatorDraft.FIGURE + target.id() + "/" + field, () -> {
            switch (slot) {
                case MODEL -> target.setModel(ref);
                case ANIMATION -> target.setAnimation(ref);
                default -> target.setTexture(ref);
            }
            if (held) {
                target.setName(name);
            } else if (rename) {
                target.rename(name);
            }
        });
        if (held) {
            this.editor.renamedWhileSaving(target.id());
        } else if (rename) {
            // The strip tells figures apart by id, which followed the name
            this.page.keepSelected(target);
        }
        this.editor.changed();
    }

    /**
     * Puts the player's own skin, or the default skin when the skin cache has none, into the
     * skin figure shown, as if it had been picked; a new figure still called "New figure" takes
     * the player's name. The status line says which skin it was.
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
            String player = minecraft.getUser().getName();
            boolean nameable = player.length() <= WorldContentLimits.MAX_NAME_LENGTH
                    && WorldCollectionRecord.isPlainText(player);
            put(target, FilePicker.Slot.SKIN, FileRef.of(pick.file()), nameable ? player : null);
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

    // A name typed into the field, which the draft takes whatever it is; the red line says what is wrong
    private boolean renamed(String value) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return true;
        }
        if (this.editor.isSaving(shown.id())) {
            // The save on its way puts the figure under its id, which the sync then brings back,
            // so the id holds still until that save is over, and then follows the name
            shown.setName(value);
            this.editor.renamedWhileSaving(shown.id());
        } else {
            shown.rename(value);
            // A new figure's id follows its name, and the strip tells figures apart by id
            this.page.keepSelected(shown);
        }
        this.editor.changed();
        return true;
    }

    // The nodes

    /** The column: a scrolling pane whose children the inspector replaces as a whole. */
    private static final class Column extends UiScrollPane {
        /**
         * Puts these children in the pane, in order. The same children only move, keeping their
         * press and the keyboard; others replace them, the keyboard staying with the child that
         * had it when it is still among them.
         */
        void setChildren(List<UiNode> next) {
            if (next.equals(this.children)) {
                return;
            }
            UiNode focused = focusedChild();
            clear();
            for (UiNode child : next) {
                add(child);
            }
            if (focused != null && next.contains(focused)) {
                focusChild(focused);
            }
        }

        void focus(UiNode child) {
            focusChild(child);
        }
    }

    /** A small label in the pixel font, over what it names, such as "NAME". */
    private static final class SmallLabel extends UiNode.Box {
        private final Supplier<Component> text;

        SmallLabel(Supplier<Component> text) {
            this.text = text;
            this.height = LABEL;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (visible() && width > 0) {
                SidebarScreen.smallLabel(graphics, this.text.get(), x, y + 3, width, Theme.TEXT_SOFT);
            }
        }
    }

    /** One line of text in a colour, cut to fit with the whole of it in its tooltip. */
    private static final class TextLine extends UiNode.Box {
        private Component text = Component.empty();
        private int colour = Theme.MUTED;

        void set(Component text, int colour) {
            this.text = text;
            this.colour = colour;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String full = this.text.getString();
            String shown = Draw.cut(font, full, width);
            Draw.string(graphics, font, shown, x, y + 1, this.colour);
            if (hovered(mouseX, mouseY) && !shown.equals(full)) {
                UiTooltip.set(SidebarScreen.tooltipLines(this.text));
            }
        }
    }

    /**
     * The files' line, under a skin figure's buttons or a model figure's zones: in green after a
     * check when every file passes every check, saying what they are (a skin's name and size, a
     * model's cubes, its texture's size and its poses); else the first thing wrong in red, what a
     * zone waits for, or, with a file still missing, which in amber. Short of room it leaves out
     * its last details before it cuts the rest, and a stored skin's "Saved on the server" takes
     * its short form; the whole line is then its tooltip.
     */
    private final class CheckLine extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            FigureDraft shown = figure;
            if (!visible() || width <= 0 || shown == null || slots.isEmpty()) {
                return;
            }
            List<String> parts = new ArrayList<>();
            String[] shortFirst = new String[1];
            int[] colour = {Theme.GREEN};
            Component problem = shown.kind() == Kind.SKIN ? skinLine(shown, parts, shortFirst, colour)
                    : modelLine(shown, parts, colour);
            if (problem != null && problem.getString().isEmpty()) {
                // A model's zone says what is wrong itself
                return;
            }
            boolean ok = problem == null;
            Font font = Minecraft.getInstance().font;
            int textX = x;
            if (ok) {
                PixelIcon.draw(graphics, PixelIcon.CHECK, x, y, Theme.GREEN);
                textX += PixelIcon.width(PixelIcon.CHECK) + 3;
            }
            int room = Math.max(0, x + width - textX);
            String full = ok ? String.join(" · ", parts) : problem.getString();
            String cut;
            if (ok && !parts.isEmpty()) {
                List<String> kept = new ArrayList<>(parts);
                while (kept.size() > 1 && font.width(String.join(" · ", kept)) > room) {
                    kept.remove(kept.size() - 1);
                }
                if (shortFirst[0] != null && kept.size() == 1 && font.width(kept.get(0)) > room) {
                    kept.set(0, shortFirst[0]);
                }
                cut = Draw.cut(font, String.join(" · ", kept), room);
            } else {
                cut = Draw.cut(font, full, room);
            }
            Draw.string(graphics, font, cut, textX, y + 1, ok ? Theme.GREEN : colour[0]);
            if (hovered(mouseX, mouseY) && !cut.equals(full)) {
                UiTooltip.set(SidebarScreen.tooltipLines(Component.literal(full)));
            }
        }

        // A skin figure's line: null with its parts when the skin passes, else what is wrong
        @Nullable
        private Component skinLine(FigureDraft shown, List<String> parts, String[] shortFirst, int[] colour) {
            UiDropZone zone = slots.get(0).zone;
            FileRef ref = shown.texture();
            BlobInfo info = ref != null ? ref.info() : null;
            colour[0] = Theme.RED;
            if (zone.error() != null) {
                return zone.error();
            }
            if (zone.waitingNote() != null) {
                colour[0] = Theme.PENDING;
                return zone.waitingNote();
            }
            if (ref == null) {
                colour[0] = Theme.AMBER;
                return tr("figures.problem.no_skin");
            }
            Component wrong = fileWrong(ref, FilePicker.Slot.SKIN, FileChecks::skin);
            if (wrong != null) {
                return wrong;
            }
            boolean stored = ref.local() == null;
            parts.add(stored ? tr("identity.stored_file").getString() : ref.local().name());
            parts.add(tr("check.size", info.width(), info.height()).getString());
            shortFirst[0] = stored ? tr("figures.file_stored_short").getString() : null;
            return null;
        }

        // A model figure's line: null with its parts when every file passes, else what is wrong,
        // or nothing to say when a zone shows it on its own line
        @Nullable
        private Component modelLine(FigureDraft shown, List<String> parts, int[] colour) {
            colour[0] = Theme.RED;
            for (FileSlot slot : slots) {
                if (slot.zone.error() != null) {
                    return Component.empty();
                }
            }
            for (FileSlot slot : slots) {
                if (slot.zone.waitingNote() != null) {
                    colour[0] = Theme.PENDING;
                    return slot.zone.waitingNote();
                }
            }
            if (shown.model() == null || shown.texture() == null) {
                colour[0] = Theme.AMBER;
                return tr(shown.texture() == null && shown.model() == null ? "figures.problem.no_files"
                        : shown.model() == null ? "figures.problem.no_model" : "figures.problem.no_texture");
            }
            BlobInfo model = shown.model().info();
            Component wrong = fileWrong(shown.model(), FilePicker.Slot.MODEL, FileChecks::geo);
            if (wrong == null) {
                wrong = fileWrong(shown.texture(), FilePicker.Slot.MODEL_TEXTURE,
                        info -> FileChecks.modelTexture(info, model));
            }
            if (wrong == null && shown.animation() != null) {
                wrong = fileWrong(shown.animation(), FilePicker.Slot.ANIMATION, FileChecks::animation);
            }
            if (wrong != null) {
                return wrong;
            }
            // Its cubes, its texture's size, then the poses it has
            parts.add(FileChecks.geo(model).get(0).text().getString());
            parts.add(FileChecks.modelTexture(shown.texture().info(), model).get(0).text().getString());
            if (shown.animation() != null) {
                List<String> poses = new ArrayList<>();
                for (FileChecks.Check check : FileChecks.animation(shown.animation().info())) {
                    if (check.mark() == FileChecks.Mark.OK) {
                        poses.add(check.text().getString());
                    }
                }
                parts.add(String.join(", ", poses));
            }
            return null;
        }

        // What is wrong with one file: lost, not loaded, not described, or its first failing check
        @Nullable
        private Component fileWrong(FileRef ref, FilePicker.Slot slot, Function<BlobInfo, List<FileChecks.Check>> checks) {
            if (ref.local() == null && ClientWorldCollections.isMissingOnServer(ref.sha())) {
                return slot == FilePicker.Slot.SKIN ? tr("file_missing")
                        : tr("figures.problem.missing", UiDropZone.slotName(slot));
            }
            if (ref.local() == null && ClientWorldCollections.failedToLoad(ref.sha())) {
                return tr("figures.problem.failed", UiDropZone.slotName(slot));
            }
            BlobInfo info = ref.info();
            if (info == null) {
                return tr("file_unknown");
            }
            for (FileChecks.Check check : checks.apply(info)) {
                if (!check.ok()) {
                    return check.text();
                }
            }
            return null;
        }
    }

    /** A heading over a grey hint, as the column says how many figures are selected, or how to pick one. */
    private static final class Message extends UiNode.Box {
        private static final int MAX_LINES = 4;
        private Component heading = Component.empty();
        private Component hint = Component.empty();

        void set(Component heading, Component hint) {
            this.heading = heading;
            this.hint = hint;
        }

        int heightFor(int forWidth) {
            Font font = Minecraft.getInstance().font;
            int lines = Math.max(1, Draw.fit(font, this.hint.getString(), forWidth, MAX_LINES).size());
            return Theme.LINE_HEIGHT + 3 + lines * Theme.LINE_HEIGHT;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            Draw.text(graphics, font, this.heading.getString(), x, y + 1, width, Theme.TEXT);
            Draw.lines(graphics, font, this.hint.getString(), x, y + Theme.LINE_HEIGHT + 4, width, MAX_LINES,
                    Theme.MUTED);
        }
    }
}
