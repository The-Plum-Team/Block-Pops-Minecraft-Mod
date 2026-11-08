package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiFigureView;
import com.theplumteam.client.gui.creator.ui.UiFoldRow;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiSizeSlider;
import com.theplumteam.client.gui.creator.ui.UiToggleRow;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorDraft.VariantDraft;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * "More settings", the figure inspector's sub-view: what a figure has besides its name, its
 * files and its rarity, which the inspector's column shows in place of its own rows while it is
 * open, under a "‹ More settings" link back and the figure's id in faint text.
 *
 * <ul>
 *   <li><b>Size</b>, with the size it has: a menu of the six sizes, from Mini to Giant, the
 *       figure's own ticked.</li>
 *   <li><b>Type</b>, a Minecraft skin or a Blockbench model: a menu of the two, each saying on a
 *       faint line of its own what it means. A skin figure uses the poses every figure shares, as
 *       the row's tooltip says. Changing it drops what the old kind had, which its toast offers to
 *       undo.</li>
 *   <li><b>Skin variants</b>, with how many it has: a sub-view of their rows, each a name, a
 *       remove button and its texture's zone (a 64×64 skin for a skin figure, a texture in the
 *       model's proportions for a model figure), then a zone that adds one. Its counters, the
 *       figure's own cap, the collection's and how much of its definition the draft fills, show
 *       once one of them is three quarters used. The preview's "View ⌄" menu picks which skin
 *       it wears, so each variant shows before it is saved.</li>
 *   <li><b>Box face</b>: a sub-view with whether the box shows the figure's face, a toggle row
 *       whose label wraps rather than being cut, and a note that says what it shows. A skin
 *       figure only turns it on or off; a model figure names the face's region in its texture,
 *       chosen by dragging across the texture shown there, snapped to its pixels, and then moved
 *       or resized pixel by pixel with the arrow keys. A change shows the figure in its box.</li>
 *   <li><b>Duplicate</b>, which adds a copy of the figure after the others.</li>
 * </ul>
 *
 * In a narrow column the list's rows keep their values, such as the count of variants, and cut
 * their labels instead, and the type shows its short name.
 * Escape and the link back go up one level, to the list and then to the inspector's own rows;
 * leaving a sub-view counts as moving on, so what waits is saved, files included. Every change
 * goes to the editor's draft, which the collection's saver sends, and the editor's Undo takes it
 * back. A variant name left empty is not taken: its field shows red and goes back to the name the
 * draft keeps when it loses the keyboard.
 */
final class FigureExtrasPanel {
    /** Which part of "More settings" shows, if any. */
    enum Level {
        NONE, LIST, VARIANTS, FACE
    }

    private static final int ROW_GAP = 5;
    private static final int LINK = 10;
    private static final int NOTE = 20;
    // A limit counts as nearly reached, and the variants' counters show, from this share of it
    private static final float NEAR_LIMIT = 0.75f;
    // The tallest the face region picker draws its texture
    private static final int MAX_PICKER = 192;

    private final CreatorEditorScreen editor;
    private final FigureInspector inspector;
    private final UiFigureView view;
    private Level level = Level.NONE;

    // The list
    private final UiButton back;
    private final Line idLine = new Line();
    private final UiFoldRow sizeRow;
    private final UiFoldRow kindRow;
    private final UiFoldRow variantsRow;
    private final UiFoldRow faceRow;
    private final UiFoldRow duplicateRow;

    // The variants' sub-view
    private final Note variantsCount = new Note(2);
    @Nullable
    private FigureDraft rowsFigure;
    @Nullable
    private Kind rowsKind;
    private int rowsCount = -1;
    private List<VariantRow> rows = List.of();
    @Nullable
    private UiDropZone addZone;
    private int zoneLabelWidth = 40;
    private boolean countShown;

    // The box face's sub-view
    private final UiToggleRow face;
    private final UvPicker picker = new UvPicker();
    private final Note faceNote = new Note(Integer.MAX_VALUE);

    @Nullable
    private FigureDraft figure;

    FigureExtrasPanel(CreatorEditorScreen editor, FigureInspector inspector, UiFigureView view) {
        this.editor = editor;
        this.inspector = inspector;
        this.view = view;
        this.back = new UiButton(Component.empty(), UiButton.Style.LINK, this::back);
        // The values are short, so in a narrow column the labels give way to them
        this.sizeRow = new UiFoldRow(tr("inspector.size"), null, this::openSizeMenu).lineAbove(true).keepHint(true);
        this.kindRow = new UiFoldRow(tr("inspector.type"), null, this::openKindMenu);
        this.variantsRow = new UiFoldRow(tr("extras.variants"), null, () -> openLevel(Level.VARIANTS)).keepHint(true);
        this.faceRow = new UiFoldRow(tr("extras.face"), null, () -> openLevel(Level.FACE));
        this.duplicateRow = new UiFoldRow(tr("figures.duplicate"), null, inspector::duplicate).chevron(false);
        this.face = new UiToggleRow(tr("extras.face_show"), false, this::setFaceShown).lineAbove(true)
                .wrapLabel(true);
    }

    // Levels

    Level level() {
        return this.level;
    }

    /** Opens the list, as the inspector's "More settings" row does. */
    void open() {
        openLevel(Level.LIST);
    }

    private void openLevel(Level next) {
        this.level = next;
        this.inspector.relayout(true);
    }

    /**
     * Goes up one level, from a sub-view to the list and from the list to the inspector's rows,
     * as Escape and the link back do; leaving counts as moving on, so what waits is saved.
     *
     * @return false when nothing was open
     */
    boolean back() {
        if (this.level == Level.NONE) {
            return false;
        }
        this.level = this.level == Level.LIST ? Level.NONE : Level.LIST;
        this.editor.navigated();
        this.inspector.relayout(true);
        return true;
    }

    /** Closes every level at once, as when another figure is selected. */
    void close() {
        this.level = Level.NONE;
    }

    // Showing

    /** Shows a figure's settings; called whenever the draft or the selection changes. */
    void show(FigureDraft shown) {
        this.figure = shown;
        boolean skin = shown.kind() == Kind.SKIN;
        this.idLine.text = tr(shown.isNew() ? "figures.id_follows" : "figures.id_tip", shown.id());
        this.sizeRow.hint(Component.literal(UiSizeSlider.scaleText(shown.scale())));
        this.kindRow.hint(tr(skin ? "figures.kind_skin" : "figures.kind_model"),
                tr(skin ? "bulk.type.skin" : "bulk.type.model"));
        this.kindRow.tooltip(skin ? tr("inspector.poses_shared_tip") : null);
        this.variantsRow.hint(Component.literal(String.valueOf(shown.variants().size())));
        this.duplicateRow.disable(this.editor.draft().isFull()
                ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null);
        refreshVariants(shown);
        refreshFace(shown);
    }

    private void refreshVariants(FigureDraft shown) {
        List<VariantDraft> variants = shown.variants();
        if (shown != this.rowsFigure || shown.kind() != this.rowsKind || variants.size() != this.rowsCount) {
            this.rowsFigure = shown;
            this.rowsKind = shown.kind();
            this.rowsCount = variants.size();
            String figureId = shown.id();
            List<VariantRow> made = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                made.add(new VariantRow(shown, figureId, i));
            }
            this.rows = made;
            FilePicker.Slot slot = shown.kind() == Kind.SKIN ? FilePicker.Slot.SKIN : FilePicker.Slot.MODEL_TEXTURE;
            Kind kind = shown.kind();
            this.addZone = UiDropZone.single(this.editor, slot, tr("extras.variant_add"),
                    file -> addVariant(shown, figureId, kind, file)).labelWidth(this.zoneLabelWidth)
                    .help(tr("extras.variant_add_help"));
            this.addZone.at(0, 0, 0, UiDropZone.COMPACT_HEIGHT);
        }
        BlobInfo model = shown.model() != null ? shown.model().info() : null;
        Function<BlobInfo, List<FileChecks.Check>> checks = textureChecks(shown.kind(), model);
        for (int i = 0; i < this.rows.size() && i < variants.size(); i++) {
            this.rows.get(i).show(variants.get(i), checks);
        }
        int inCollection = 0;
        for (FigureDraft each : this.editor.draft().figures()) {
            inCollection += each.variants().size();
        }
        Component full = variants.size() >= WorldContentLimits.MAX_VARIANTS_PER_FIGURE
                ? tr("extras.variants_full", WorldContentLimits.MAX_VARIANTS_PER_FIGURE)
                : inCollection >= WorldContentLimits.MAX_VARIANTS
                ? tr("extras.variants_collection_full", WorldContentLimits.MAX_VARIANTS) : null;
        UiDropZone add = this.addZone;
        if (add != null) {
            add.disable(full);
            add.checks(checks);
        }
        int recordBytes = this.editor.forecast().recordBytes();
        // The counters only matter near a limit
        this.countShown = variants.size() >= NEAR_LIMIT * WorldContentLimits.MAX_VARIANTS_PER_FIGURE
                || inCollection >= NEAR_LIMIT * WorldContentLimits.MAX_VARIANTS
                || recordBytes >= NEAR_LIMIT * WorldContentLimits.RECORD_BYTES;
        Component count = tr("extras.variants_count", variants.size(), WorldContentLimits.MAX_VARIANTS_PER_FIGURE,
                inCollection, WorldContentLimits.MAX_VARIANTS,
                tr("extras.record_size", (recordBytes + 1023) / 1024, WorldContentLimits.RECORD_BYTES / 1024));
        this.variantsCount.set(count, recordBytes > WorldContentLimits.RECORD_BYTES
                || inCollection > WorldContentLimits.MAX_VARIANTS ? Theme.RED : Theme.FAINT);
        List<String> names = new ArrayList<>(variants.size());
        for (VariantDraft variant : variants) {
            names.add(variant.name());
        }
        this.view.setVariants(names);
    }

    /** The checks a variant's texture gets: a skin's, or a model texture's against the model. */
    static Function<BlobInfo, List<FileChecks.Check>> textureChecks(Kind kind, @Nullable BlobInfo model) {
        return kind == Kind.SKIN ? FileChecks::skin : info -> FileChecks.modelTexture(info, model);
    }

    private void refreshFace(FigureDraft shown) {
        boolean skin = shown.kind() == Kind.SKIN;
        this.face.setValue(shown.showsFace());
        BlobInfo texture = shown.texture() != null ? shown.texture().info() : null;
        this.faceRow.dot(0, null);
        if (skin) {
            this.face.disable(null);
            this.picker.setVisible(false);
            this.faceNote.set(tr("extras.face_skin"), Theme.MUTED);
            return;
        }
        this.face.disable(texture == null ? tr("extras.face_needs_texture") : null);
        WorldCollectionRecord.BoxFace boxFace = shown.boxFace();
        WorldCollectionRecord.Uv uv = boxFace != null && boxFace.show() ? boxFace.uv() : null;
        this.picker.show(shown.texture(), texture, uv);
        this.picker.setVisible(uv != null && texture != null);
        if (uv != null && texture != null && !fits(uv, texture)) {
            Component outside = tr("extras.face_outside", texture.width(), texture.height());
            this.faceNote.set(outside, Theme.RED);
            this.faceRow.dot(Theme.RED, outside);
        } else if (uv != null) {
            this.faceNote.set(tr("extras.face_region", uv.u(), uv.v(), uv.w(), uv.h()), Theme.MUTED);
        } else {
            this.faceNote.set(tr("extras.face_model"), Theme.MUTED);
        }
    }

    /** Whether a face region lies inside a texture. */
    static boolean fits(WorldCollectionRecord.Uv uv, BlobInfo texture) {
        return uv.u() >= 0 && uv.v() >= 0 && uv.w() >= 1 && uv.h() >= 1
                && (long) uv.u() + uv.w() <= texture.width() && (long) uv.v() + uv.h() <= texture.height();
    }

    // Layout

    /**
     * Places the open level's nodes from {@code top} and lists them, in order, for the
     * inspector's column.
     *
     * @return where the level's content ends
     */
    int layout(List<UiNode> column, int x, int top, int width) {
        Font font = Minecraft.getInstance().font;
        // The variants' zones line up on their own labels, at most half the column
        this.zoneLabelWidth = Math.min(Math.max(30, width / 2), Math.max(font.width(tr("extras.variant_texture")),
                font.width(tr("extras.variant_add"))) + 8);
        Component title = switch (this.level) {
            case VARIANTS -> tr("extras.variants");
            case FACE -> tr("extras.face");
            default -> tr("figures.more_settings");
        };
        this.back.setLabel(Component.literal("‹ ").append(title));
        int y = top;
        this.back.at(x, y, Math.min(width, font.width(this.back.label()) + 2), LINK);
        column.add(this.back);
        y += LINK + 6;
        switch (this.level) {
            case LIST -> {
                this.idLine.at(x, y, width, LINK);
                column.add(this.idLine);
                y += LINK + 8;
                for (UiFoldRow row : List.of(this.sizeRow, this.kindRow, this.variantsRow, this.faceRow,
                        this.duplicateRow)) {
                    row.at(x, y, width, UiFoldRow.HEIGHT);
                    column.add(row);
                    y += UiFoldRow.HEIGHT;
                }
            }
            case VARIANTS -> {
                this.variantsCount.setVisible(this.countShown);
                if (this.countShown) {
                    this.variantsCount.at(x, y, width, NOTE);
                    column.add(this.variantsCount);
                    y += NOTE + 2;
                }
                for (VariantRow row : this.rows) {
                    y = row.layout(column, x, y, width);
                }
                UiDropZone add = this.addZone;
                if (add != null) {
                    add.labelWidth(this.zoneLabelWidth);
                    add.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
                    column.add(add);
                    y += UiDropZone.COMPACT_HEIGHT;
                }
            }
            case FACE -> {
                this.face.at(x, y, width, UiToggleRow.HEIGHT);
                column.add(this.face);
                y += this.face.rowHeight() + 5;
                int noteHeight = this.faceNote.heightFor(width);
                this.faceNote.at(x, y, width, noteHeight);
                column.add(this.faceNote);
                y += noteHeight + 4;
                if (this.picker.visible()) {
                    int pickerHeight = this.picker.heightFor(width);
                    this.picker.at(x, y, width, pickerHeight);
                    column.add(this.picker);
                    y += pickerHeight;
                }
            }
            default -> {
            }
        }
        return y;
    }

    // Changes

    // The six sizes, the figure's own ticked
    private void openSizeMenu(UiNode.Rect anchor) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        UiMenu menu = new UiMenu();
        List<Float> scales = WorldCollectionRecord.SCALES;
        for (int i = 0; i < scales.size(); i++) {
            float scale = scales.get(i);
            menu.radio(UiSizeSlider.label(i), shown.scale() == scale, () -> resize(shown, scale));
        }
        menu.highlight(Math.max(0, WorldCollectionRecord.scaleIndex(shown.scale())));
        this.editor.openMenu(menu, anchor);
    }

    private void resize(FigureDraft figureMade, float scale) {
        FigureDraft target = this.inspector.live(figureMade, figureMade.id());
        if (target == null || target.scale() == scale) {
            return;
        }
        // Each choice is an undo step of its own
        this.editor.draft().checkpoint();
        target.setScale(scale);
        this.editor.changed();
    }

    // The two kinds, each with what it means in its tooltip
    private void openKindMenu(UiNode.Rect anchor) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        UiMenu menu = new UiMenu();
        // What each means on a faint line of its own, which covers nothing of the menu
        menu.radio(tr("figures.kind_skin"), shown.kind() == Kind.SKIN, () -> setKind(shown, Kind.SKIN))
                .note(tr("inspector.kind_skin_note"));
        menu.radio(tr("figures.kind_model"), shown.kind() == Kind.MODEL, () -> setKind(shown, Kind.MODEL))
                .note(tr("inspector.kind_model_note"));
        menu.highlight(shown.kind() == Kind.SKIN ? 0 : 1);
        this.editor.openMenu(menu, anchor);
    }

    private void setKind(FigureDraft figureMade, Kind kind) {
        FigureDraft target = this.inspector.live(figureMade, figureMade.id());
        if (target == null || target.kind() == kind) {
            return;
        }
        // A skin keeps no model or poses, and the variants and the face go with the old kind;
        // Undo brings them back, which the toast offers
        this.editor.draft().checkpoint();
        target.setKind(kind);
        this.editor.changed();
        String name = target.name().isBlank() ? target.id() : target.name();
        this.editor.undoToast(tr("inspector.kind_changed", name,
                tr(kind == Kind.SKIN ? "figures.kind_skin" : "figures.kind_model")));
    }

    // A variant texture picked into the last zone, for a figure that may no longer be the one shown
    private void addVariant(FigureDraft figureMade, String figureId, Kind kind, FilePicker.LocalFile file) {
        FigureDraft target = this.inspector.live(figureMade, figureId);
        // A file read while the figure changed kind fits it no longer
        if (target == null || target.kind() != kind) {
            return;
        }
        List<VariantDraft> next = new ArrayList<>(target.variants());
        int inCollection = 0;
        for (FigureDraft each : this.editor.draft().figures()) {
            inCollection += each.variants().size();
        }
        if (next.size() >= WorldContentLimits.MAX_VARIANTS_PER_FIGURE
                || inCollection >= WorldContentLimits.MAX_VARIANTS) {
            return;
        }
        next.add(new VariantDraft(tr("extras.variant_name", next.size() + 1).getString(), FileRef.of(file)));
        this.editor.draft().checkpoint();
        target.setVariants(next);
        this.editor.changed();
        // The new variant shows at once
        if (target == this.figure) {
            this.view.setVariants(names(next));
            this.view.setSkinIndex(next.size());
        }
    }

    private static List<String> names(List<VariantDraft> variants) {
        List<String> names = new ArrayList<>(variants.size());
        for (VariantDraft variant : variants) {
            names.add(variant.name());
        }
        return names;
    }

    private void setFaceShown(boolean shown) {
        FigureDraft target = this.figure;
        if (target == null) {
            return;
        }
        WorldCollectionRecord.Uv uv = null;
        if (shown && target.kind() == Kind.MODEL) {
            BlobInfo texture = target.texture() != null ? target.texture().info() : null;
            if (texture == null) {
                return;
            }
            WorldCollectionRecord.BoxFace previous = target.boxFace();
            uv = previous != null && previous.uv() != null && fits(previous.uv(), texture) ? previous.uv()
                    : defaultRegion(texture);
        }
        this.editor.draft().checkpoint();
        target.setFace(shown, uv);
        this.view.setMode(UiFigureView.Mode.IN_BOX);
        this.editor.changed();
    }

    /**
     * Where a model figure's face starts out: where a skin keeps its face, scaled to the texture,
     * as many Blockbench models made from a skin keep it too. The operator drags it elsewhere.
     */
    private static WorldCollectionRecord.Uv defaultRegion(BlobInfo texture) {
        int w = Math.max(1, texture.width() / 8);
        int h = Math.max(1, texture.height() / 8);
        return new WorldCollectionRecord.Uv(Math.min(w, texture.width() - w), Math.min(h, texture.height() - h), w, h);
    }

    // A number kept between two limits, the lower one winning when they cross
    private static int within(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    // The nodes

    /** One variant: its name and remove button on a row, its texture's zone under them. */
    private final class VariantRow {
        private final FigureDraft figureMade;
        private final String figureId;
        private final int index;
        private final NameField name;
        private final UiButton remove;
        private final UiDropZone zone;
        @Nullable
        private String shownKey;

        VariantRow(FigureDraft figureMade, String figureId, int index) {
            this.figureMade = figureMade;
            this.figureId = figureId;
            this.index = index;
            this.name = new NameField(tr("extras.variant_name_hint"), tr("extras.variant_name_rule"), this::renamed,
                    this::startedTyping);
            this.remove = new UiButton(Component.literal("✕"), UiButton.Style.SECONDARY, this::removed);
            this.remove.setTooltip(tr("extras.variant_remove"));
            FilePicker.Slot slot = figureMade.kind() == Kind.SKIN ? FilePicker.Slot.SKIN
                    : FilePicker.Slot.MODEL_TEXTURE;
            this.zone = UiDropZone.single(editor, slot, tr("extras.variant_texture"), this::replaced)
                    .labelWidth(zoneLabelWidth);
        }

        int layout(List<UiNode> column, int x, int y, int width) {
            Font font = Minecraft.getInstance().font;
            int removeWidth = UiButton.widthFor(font, this.remove.label(), false);
            this.name.at(x, y, Math.max(20, width - removeWidth - 4), NameField.HEIGHT);
            this.remove.at(x + width - removeWidth, y + (NameField.HEIGHT - UiButton.SMALL_HEIGHT) / 2, removeWidth,
                    UiButton.SMALL_HEIGHT);
            y += NameField.HEIGHT + 2;
            this.zone.labelWidth(zoneLabelWidth);
            this.zone.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
            column.add(this.name);
            column.add(this.remove);
            column.add(this.zone);
            return y + UiDropZone.COMPACT_HEIGHT + ROW_GAP;
        }

        void show(VariantDraft variant, Function<BlobInfo, List<FileChecks.Check>> checks) {
            this.name.show(variant.name());
            this.zone.checks(checks);
            FileRef ref = variant.texture();
            String key = ref.sha() + "/" + (ref.info() != null) + "/"
                    + ClientWorldCollections.isMissingOnServer(ref.sha()) + "/"
                    + (figure != null && figure.model() != null ? figure.model().sha() : "");
            if (key.equals(this.shownKey)) {
                return;
            }
            this.shownKey = key;
            BlobInfo info = ref.info();
            if (ref.local() != null) {
                this.zone.showFile(ref.local());
            } else if (info == null) {
                this.zone.showEmpty();
                this.zone.showError(tr("file_unknown"));
                return;
            } else {
                this.zone.showFile(tr("identity.stored_file").getString(), ref.sha(), info);
                if (ClientWorldCollections.isMissingOnServer(ref.sha())) {
                    this.zone.showError(tr("file_missing"));
                    return;
                }
            }
            for (FileChecks.Check check : checks.apply(info)) {
                if (!check.ok()) {
                    this.zone.showError(check.text());
                    return;
                }
            }
        }

        @Nullable
        private FigureDraft target() {
            FigureDraft live = inspector.live(this.figureMade, this.figureId);
            return live != null && this.index < live.variants().size() ? live : null;
        }

        private void startedTyping() {
            // Each visit to the field is an undo step of its own
            editor.draft().checkpoint();
        }

        private boolean renamed(String value) {
            FigureDraft target = target();
            if (target == null) {
                return false;
            }
            if (value.isBlank() || !WorldCollectionRecord.isPlainText(value)
                    || value.length() > WorldContentLimits.MAX_NAME_LENGTH) {
                return false;
            }
            List<VariantDraft> next = new ArrayList<>(target.variants());
            VariantDraft old = next.get(this.index);
            if (!old.name().equals(value)) {
                next.set(this.index, new VariantDraft(value, old.texture()));
                target.setVariants(next);
                editor.changed();
            }
            return true;
        }

        private void replaced(FilePicker.LocalFile file) {
            FigureDraft target = target();
            if (target == null || target.kind() != this.figureMade.kind()) {
                return;
            }
            List<VariantDraft> next = new ArrayList<>(target.variants());
            next.set(this.index, new VariantDraft(next.get(this.index).name(), FileRef.of(file)));
            editor.draft().checkpoint();
            target.setVariants(next);
            editor.changed();
        }

        private void removed() {
            FigureDraft target = target();
            if (target == null) {
                return;
            }
            List<VariantDraft> next = new ArrayList<>(target.variants());
            VariantDraft gone = next.remove(this.index);
            editor.draft().checkpoint();
            target.setVariants(next);
            editor.changed();
            // Its file goes with it, so the toast offers to take it back
            editor.undoToast(tr("extras.variant_removed", gone.name()));
        }
    }

    /** One faint line, cut to fit with the whole of it in its tooltip, as the figure's id. */
    private static final class Line extends UiNode.Box {
        private Component text = Component.empty();

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String full = this.text.getString();
            String shown = Draw.cut(font, full, width);
            Draw.string(graphics, font, shown, x, y + 1, Theme.FAINT);
            if (hovered(mouseX, mouseY) && !shown.equals(full)) {
                UiTooltip.set(this.text);
            }
        }
    }

    /** A line or two of text, cut to fit with the rest in its tooltip. */
    private static final class Note extends UiNode.Box {
        private final int maxLines;
        private Component text = Component.empty();
        private int colour = Theme.MUTED;

        Note(int maxLines) {
            this.maxLines = maxLines;
        }

        /** How tall the note is at a width, every line it needs up to its most. */
        int heightFor(int forWidth) {
            Font font = Minecraft.getInstance().font;
            int lines = Math.max(1, Draw.fit(font, this.text.getString(), Math.max(1, forWidth), this.maxLines).size());
            return lines * (Theme.LINE_HEIGHT - 1);
        }

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
            List<String> lines = Draw.fit(font, full, width, this.maxLines);
            int lineY = y;
            for (String line : lines) {
                Draw.string(graphics, font, line, x, lineY, this.colour);
                lineY += Theme.LINE_HEIGHT - 1;
            }
            if (hovered(mouseX, mouseY) && !String.join(" ", lines).equals(full)) {
                UiTooltip.set(this.text);
            }
        }
    }

    /**
     * A name typed in place, such as a figure's or a variant's: the node's own vanilla text
     * field, which it draws and hands input to itself so it works inside a scrolling column, as
     * {@code UiStepper} does with its number. It is the creator's well ({@link UiField}) with its
     * hint cut to fit. A name the receiver refuses turns the well's border red, with the rule in
     * its tooltip, and the field shows the name the draft keeps again when it loses the keyboard.
     * Enter and Escape leave the field.
     */
    static final class NameField extends UiNode.Box {
        static final int HEIGHT = 20;

        private final UiField field;
        private final Component rule;
        @Nullable
        private Supplier<Component> tooltip;
        // Told each typed name; answers whether it was taken
        private final Function<String, Boolean> onName;
        private final Runnable onStart;
        private String kept = "";
        private boolean valid = true;
        private boolean syncing;
        private boolean typing;
        private boolean pressedField;

        /**
         * @param hint    what the empty field shows
         * @param rule    why a name was refused, in the red field's tooltip
         * @param onName  told each typed name; answers whether it was taken
         * @param onStart told when the field starts taking the keyboard, for an undo step of its own
         */
        NameField(Component hint, Component rule, Function<String, Boolean> onName, Runnable onStart) {
            this.rule = rule;
            this.onName = onName;
            this.onStart = onStart;
            this.height = HEIGHT;
            Font font = Minecraft.getInstance().font;
            this.field = new UiField(font, 0, 0, 40, HEIGHT, hint);
            this.field.setMaxLength(WorldContentLimits.MAX_NAME_LENGTH);
            this.field.setValue("");
            this.field.setFittedHint(hint);
            this.field.setResponder(this::typedText);
        }

        /** What the field's tooltip says while its name is taken, such as the id it gives; null for none. */
        NameField tooltip(@Nullable Supplier<Component> text) {
            this.tooltip = text;
            return this;
        }

        /**
         * Shows the name the draft keeps. While the field is being typed in it shows only a name
         * that changed from outside, as one a dropped skin gave a new figure, or an undo: the
         * names it took as they were typed, and those it refused, leave the typing alone.
         */
        void show(String name) {
            boolean outside = !name.equals(this.kept) && !name.equals(this.field.getValue());
            this.kept = name;
            if ((!this.typing || outside) && !this.field.getValue().equals(name)) {
                showKept();
                if (this.typing) {
                    UiField.cursorToEnd(this.field);
                }
            }
        }

        /** Whether the field has the keyboard and takes what is typed. */
        boolean isTyping() {
            return this.typing;
        }

        /**
         * Starts typing with the cursor after the last letter, as Enter or a double click on a
         * figure does; the screen gives this node the keyboard first.
         */
        void startTyping() {
            if (!this.typing) {
                this.onStart.run();
            }
            this.typing = true;
            this.field.setFocused(true);
            UiField.cursorToEnd(this.field);
        }

        private void showKept() {
            this.syncing = true;
            this.field.setValue(this.kept);
            this.syncing = false;
            this.valid = true;
        }

        @Override
        public Box at(int x, int y, int width, int height) {
            super.at(x, y, width, height);
            int fieldWidth = Math.max(16, width);
            boolean resized = fieldWidth != this.field.wellWidth();
            this.field.place(x, y, fieldWidth);
            if (resized && !this.typing) {
                // The field scrolled its text for the width it had; showing it again from
                // empty starts it at its first letter
                this.syncing = true;
                this.field.setValue("");
                this.syncing = false;
                showKept();
            }
            return this;
        }

        private void followKeyboard() {
            boolean now = CreatorScreen.hasKeyboard(this) && (this.typing || this.pressedField);
            if (this.typing && !now) {
                // Leaving the field: a name that was not taken gives way to the kept one
                showKept();
            }
            if (!this.typing && now) {
                this.onStart.run();
            }
            this.typing = now;
            if (this.field.isFocused() != now) {
                this.field.setFocused(now);
            }
        }

        @Override
        public void tick() {
            followKeyboard();
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            followKeyboard();
            this.field.render(graphics, mouseX, mouseY, partialTick);
            if (!this.valid) {
                // Over the well's own border
                Draw.outline(graphics, this.field.wellX() + 1, this.field.getY() + 1, this.field.wellWidth() - 2,
                        HEIGHT - 2, Theme.RED);
                if (hovered(mouseX, mouseY)) {
                    UiTooltip.set(this.rule);
                }
            } else if (this.tooltip != null && hovered(mouseX, mouseY)) {
                Component text = this.tooltip.get();
                if (!text.getString().isEmpty()) {
                    UiTooltip.set(text);
                }
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            this.pressedField = false;
            if (!isMouseOver(click.x(), click.y())) {
                return false;
            }
            if (!click.left()) {
                return true;
            }
            this.pressedField = true;
            if (!this.typing) {
                this.onStart.run();
            }
            this.typing = true;
            this.field.setFocused(true);
            UiInput.forwardClick(this.field, click);
            return true;
        }

        @Override
        public boolean drag(UiInput.Click click, double dragX, double dragY) {
            return this.pressedField && UiInput.forwardDrag(this.field, click, dragX, dragY);
        }

        @Override
        public boolean release(UiInput.Click click) {
            boolean used = this.pressedField;
            this.pressedField = false;
            if (used) {
                UiInput.forwardRelease(this.field, click);
            }
            return used;
        }

        @Override
        public boolean key(UiInput.Key key) {
            if (!this.typing) {
                return false;
            }
            if (key.escape() || key.enter()) {
                CreatorScreen.releaseKeyboard(this);
                followKeyboard();
                return true;
            }
            if (key.is(InputConstants.KEY_TAB)) {
                return false;
            }
            boolean used = UiInput.forwardKey(this.field, key);
            // Every key is the field's while it types, except a Ctrl shortcut it leaves, such as Ctrl+S
            return used || !key.ctrl();
        }

        @Override
        public boolean typed(String text) {
            if (!this.typing) {
                return false;
            }
            UiInput.forwardTyped(this.field, text);
            return true;
        }

        private void typedText(String text) {
            if (this.syncing) {
                return;
            }
            this.valid = this.onName.apply(text);
        }
    }

    /**
     * A model figure's texture, with the face region outlined in gold over it. Dragging across
     * the texture chooses a new region, snapped to whole texture pixels, which the box shows as
     * it changes; the drag is one undo step. A texture drawn smaller than one screen pixel per
     * texture pixel cannot be pointed at pixel by pixel, so after a click the arrow keys move the
     * region by one texture pixel and, with Shift, make it wider, narrower, taller or shorter.
     */
    private final class UvPicker extends UiNode.Box {
        @Nullable
        private FileRef texture;
        @Nullable
        private BlobInfo info;
        @Nullable
        private WorldCollectionRecord.Uv uv;
        private boolean dragging;
        private int startU;
        private int startV;

        void show(@Nullable FileRef ref, @Nullable BlobInfo textureInfo, @Nullable WorldCollectionRecord.Uv region) {
            this.texture = ref;
            this.info = textureInfo;
            if (!this.dragging) {
                this.uv = region;
            }
        }

        /** How tall the picker is at a width: the texture's proportions, at most {@link #MAX_PICKER}. */
        int heightFor(int width) {
            BlobInfo textureInfo = this.info;
            if (textureInfo == null || textureInfo.width() <= 0) {
                return MAX_PICKER;
            }
            return Draw.fitInside(textureInfo.width(), textureInfo.height(), width, MAX_PICKER)[1];
        }

        // Where the texture is drawn: fitted inside the node, against its left edge
        private Rect image() {
            BlobInfo textureInfo = this.info;
            if (textureInfo == null || textureInfo.width() <= 0 || textureInfo.height() <= 0) {
                return new Rect(x, y, 0, 0);
            }
            int[] fitted = Draw.fitInside(textureInfo.width(), textureInfo.height(), width, height);
            return new Rect(x, y, fitted[0], fitted[1]);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            BlobInfo textureInfo = this.info;
            FileRef ref = this.texture;
            if (!visible() || textureInfo == null || ref == null) {
                return;
            }
            Rect image = image();
            graphics.fill(image.x(), image.y(), image.right(), image.bottom(), Surfaces.WELL);
            ResourceLocation id = WorldContentIds.texture(ref.sha());
            if (!RuntimeTextures.isPending(id)) {
                Draw.texture(graphics, id, image.x(), image.y(), image.width(), image.height(), textureInfo.width(),
                        textureInfo.height());
            }
            WorldCollectionRecord.Uv region = this.uv;
            if (region != null && image.width() > 0) {
                int left = image.x() + region.u() * image.width() / textureInfo.width();
                int top = image.y() + region.v() * image.height() / textureInfo.height();
                int right = image.x() + (region.u() + region.w()) * image.width() / textureInfo.width();
                int bottom = image.y() + (region.v() + region.h()) * image.height() / textureInfo.height();
                // Darken all but the region, then outline it
                graphics.fill(image.x(), image.y(), image.right(), top, 0x90000000);
                graphics.fill(image.x(), bottom, image.right(), image.bottom(), 0x90000000);
                graphics.fill(image.x(), top, left, bottom, 0x90000000);
                graphics.fill(right, top, image.right(), bottom, 0x90000000);
                Draw.outline(graphics, left - 1, top - 1, Math.max(1, right - left) + 2, Math.max(1, bottom - top) + 2,
                        Theme.SELECTED);
            }
            Draw.outline(graphics, image.x() - 1, image.y() - 1, image.width() + 2, image.height() + 2,
                    Theme.CONTROL_OUTLINE);
            if (image.contains(mouseX, mouseY)) {
                int[] at = texel(mouseX, mouseY);
                UiTooltip.set(tr("extras.face_pick_tip", at[0], at[1]));
            }
        }

        // The texture pixel under a point, kept inside the texture
        private int[] texel(double mouseX, double mouseY) {
            BlobInfo textureInfo = this.info;
            Rect image = image();
            if (textureInfo == null || image.width() <= 0 || image.height() <= 0) {
                return new int[] {0, 0};
            }
            int u = (int) Math.floor((mouseX - image.x()) * textureInfo.width() / image.width());
            int v = (int) Math.floor((mouseY - image.y()) * textureInfo.height() / image.height());
            return new int[] {Math.max(0, Math.min(textureInfo.width() - 1, u)),
                    Math.max(0, Math.min(textureInfo.height() - 1, v))};
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !visible() || !image().contains(click.x(), click.y())) {
                return false;
            }
            int[] at = texel(click.x(), click.y());
            this.startU = at[0];
            this.startV = at[1];
            this.dragging = true;
            // The whole drag is one undo step
            editor.draft().checkpoint();
            select(at);
            return true;
        }

        @Override
        public boolean drag(UiInput.Click click, double dragX, double dragY) {
            if (!this.dragging) {
                return false;
            }
            select(texel(click.x(), click.y()));
            return true;
        }

        @Override
        public boolean release(UiInput.Click click) {
            boolean used = this.dragging;
            this.dragging = false;
            if (used) {
                editor.draft().checkpoint();
            }
            return used;
        }

        @Override
        public boolean key(UiInput.Key key) {
            WorldCollectionRecord.Uv region = this.uv;
            BlobInfo textureInfo = this.info;
            if (!visible() || region == null || textureInfo == null || !CreatorScreen.hasKeyboard(this)) {
                return false;
            }
            if (key.escape() || key.enter()) {
                CreatorScreen.releaseKeyboard(this);
                return true;
            }
            int dx = key.is(InputConstants.KEY_LEFT) ? -1 : key.is(InputConstants.KEY_RIGHT) ? 1 : 0;
            int dy = key.is(InputConstants.KEY_UP) ? -1 : key.is(InputConstants.KEY_DOWN) ? 1 : 0;
            if (dx == 0 && dy == 0) {
                return false;
            }
            int textureWidth = textureInfo.width();
            int textureHeight = textureInfo.height();
            WorldCollectionRecord.Uv next;
            if (key.shift()) {
                next = new WorldCollectionRecord.Uv(region.u(), region.v(),
                        within(region.w() + dx, 1, textureWidth - region.u()),
                        within(region.h() + dy, 1, textureHeight - region.v()));
            } else {
                next = new WorldCollectionRecord.Uv(within(region.u() + dx, 0, textureWidth - region.w()),
                        within(region.v() + dy, 0, textureHeight - region.h()), region.w(), region.h());
            }
            if (fits(next, textureInfo)) {
                choose(next);
            }
            return true;
        }

        // The region from where the drag began to the texel under the pointer, both included
        private void select(int[] at) {
            choose(new WorldCollectionRecord.Uv(Math.min(this.startU, at[0]), Math.min(this.startV, at[1]),
                    Math.abs(at[0] - this.startU) + 1, Math.abs(at[1] - this.startV) + 1));
        }

        // Makes a region the figure's face, showing the figure in its box
        private void choose(WorldCollectionRecord.Uv next) {
            if (next.equals(this.uv)) {
                return;
            }
            this.uv = next;
            FigureDraft target = figure;
            if (target == null || target.kind() != Kind.MODEL) {
                return;
            }
            target.setFace(true, next);
            view.setMode(UiFigureView.Mode.IN_BOX);
            editor.changed();
        }
    }
}
