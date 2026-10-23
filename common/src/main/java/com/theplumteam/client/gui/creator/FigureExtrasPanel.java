package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCheckbox;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiFigureView;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSegmented;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorDraft.VariantDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.figure.DrawOdds;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The part of the figure inspector under a figure's files that sets how it is drawn and how it
 * looks besides its main texture: its rarity, its skin variants and its face on the box.
 *
 * <ul>
 *   <li><b>Rarity</b>: Common, Rare, Epic or Legendary, with the chance the figure now has in
 *       one draw of the machine, worked out by {@link DrawOdds} from the editor's preview of the
 *       collection, which holds every figure the machine would offer with the draft as it is.
 *       While every figure is common they are all drawn equally, as the line under it says.</li>
 *   <li><b>Skin variants</b>: up to {@link WorldContentLimits#MAX_VARIANTS_PER_FIGURE} other
 *       textures, each with a name and a file: a 64×64 skin for a skin figure, or a texture in
 *       the model's proportions for a model figure. Dropping or choosing a file in the last zone
 *       adds one; each row renames its variant, replaces its texture or removes it. The counter
 *       shows the collection's own cap and how much of its definition the draft fills. The
 *       inspector's view steps through the variants, so each one shows before it is saved.</li>
 *   <li><b>Box face</b>: whether the box shows the figure's face. A skin figure only turns it on
 *       or off; a model figure names the face's region in its texture, which is chosen by dragging
 *       across the texture shown here, snapped to its pixels, and then moved or resized pixel by
 *       pixel with the arrow keys. Changing it shows the figure in its box.</li>
 * </ul>
 *
 * Every change goes to the editor's draft, which "Save" and "Save figure" send as one
 * SetFigureExtras edit, and the editor's Undo takes it back. A variant name left empty is not
 * taken: its field shows red and goes back to the name the draft keeps when it loses the keyboard.
 */
final class FigureExtrasPanel {
    private static final int LABEL = 11;
    private static final int ROW_GAP = 5;
    private static final int NOTE = 20;
    private static final int HELP = 0xFFA8A8A8;
    // The tallest the face region picker draws its texture
    private static final int MAX_PICKER = 192;

    private final CreatorEditorScreen editor;
    private final FigureInspector inspector;
    private final UiFigureView view;

    private final FigureInspector.Label rarityLabel;
    private final UiSegmented rarity;
    private final Note chance = new Note(2);
    private final FigureInspector.Label variantsLabel;
    private final Note variantsCount = new Note(2);
    private final FigureInspector.Label faceLabel;
    private final UiCheckbox face;
    private final UvPicker picker = new UvPicker();
    private final Note faceNote = new Note(2);

    @Nullable
    private FigureDraft figure;
    // The variant rows, made again for another figure, kind or number of variants
    @Nullable
    private FigureDraft rowsFigure;
    @Nullable
    private Kind rowsKind;
    private int rowsCount = -1;
    private int rowsMade;
    private List<VariantRow> rows = List.of();
    @Nullable
    private UiDropZone addZone;
    private int zoneLabelWidth = 40;

    FigureExtrasPanel(CreatorEditorScreen editor, FigureInspector inspector, UiFigureView view) {
        this.editor = editor;
        this.inspector = inspector;
        this.view = view;
        this.rarityLabel = new FigureInspector.Label(() -> tr("extras.rarity"), () -> changed("rarity"));
        this.rarity = new UiSegmented(0, this::setRarity, tr("extras.rarity.common"), tr("extras.rarity.rare"),
                tr("extras.rarity.epic"), tr("extras.rarity.legendary"));
        this.variantsLabel = new FigureInspector.Label(() -> tr("extras.variants"), () -> changed("variants"));
        this.faceLabel = new FigureInspector.Label(() -> tr("extras.face"), () -> changed("box_face"));
        this.face = new UiCheckbox(tr("extras.face_show"), false, this::setFaceShown);
    }

    private boolean changed(String field) {
        FigureDraft shown = this.figure;
        return shown != null && this.editor.isChanged(CreatorDraft.FIGURE + shown.id() + "/" + field);
    }

    /**
     * What the layout depends on besides the figure's kind: the variant rows made, and whether a
     * model figure's face region picker shows and the proportions of the texture it shows, which
     * set its height. The inspector lays its sections out again when it changes.
     */
    String layoutKey() {
        BlobInfo texture = this.picker.info;
        return this.rowsMade + "/" + this.picker.visible()
                + (texture != null ? "/" + texture.width() + "x" + texture.height() : "");
    }

    // Showing

    /** Shows a figure's extras; called whenever the draft or the selection changes. */
    void show(FigureDraft shown) {
        this.figure = shown;
        this.rarity.select(shown.rarity().ordinal());
        refreshChance(shown);
        refreshVariants(shown);
        refreshFace(shown);
    }

    private void refreshChance(FigureDraft shown) {
        if (!shown.enabled()) {
            this.chance.set(tr("extras.chance_retired"), HELP);
            return;
        }
        FigureCollection preview = CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT);
        Double odds = preview != null ? DrawOdds.chances(preview.getEnabledFigures()).get(shown.id()) : null;
        if (odds == null) {
            this.chance.set(tr("extras.chance_needs_files"), HELP);
            return;
        }
        double percent = odds * 100.0;
        String shownPercent = String.format(Locale.ROOT, percent >= 0.1 || percent == 0 ? "%.1f" : "%.2f", percent);
        String name = shown.name().isBlank() ? shown.id() : shown.name();
        Component line = tr("extras.chance", name, shownPercent);
        // While every figure is common they all weigh the same, and the draw stays uniform
        boolean even = true;
        int first = -1;
        for (FigureDefinition each : preview.getEnabledFigures()) {
            int weight = DrawOdds.weight(each);
            even &= first < 0 || weight == first;
            first = weight;
        }
        this.chance.set(even ? tr("card.joined", line, tr("extras.even")) : line, Theme.TEXT_SOFT);
    }

    private void refreshVariants(FigureDraft shown) {
        List<VariantDraft> variants = shown.variants();
        if (shown != this.rowsFigure || shown.kind() != this.rowsKind || variants.size() != this.rowsCount) {
            this.rowsFigure = shown;
            this.rowsKind = shown.kind();
            this.rowsCount = variants.size();
            this.rowsMade++;
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
        int recordBytes = WorldCollectionRecord.encodedSize(this.editor.draft().toRecord());
        Component count = tr("extras.variants_count", variants.size(), WorldContentLimits.MAX_VARIANTS_PER_FIGURE,
                inCollection, WorldContentLimits.MAX_VARIANTS,
                tr("extras.record_size", (recordBytes + 1023) / 1024, WorldContentLimits.RECORD_BYTES / 1024));
        this.variantsCount.set(count, recordBytes > WorldContentLimits.RECORD_BYTES
                || inCollection > WorldContentLimits.MAX_VARIANTS ? Theme.RED : HELP);
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
        this.face.setChecked(shown.showsFace());
        BlobInfo texture = shown.texture() != null ? shown.texture().info() : null;
        if (skin) {
            this.face.disable(null);
            this.picker.setVisible(false);
            this.faceNote.set(tr("extras.face_skin"), HELP);
            return;
        }
        this.face.disable(texture == null ? tr("extras.face_needs_texture") : null);
        WorldCollectionRecord.BoxFace boxFace = shown.boxFace();
        WorldCollectionRecord.Uv uv = boxFace != null && boxFace.show() ? boxFace.uv() : null;
        this.picker.show(shown.texture(), texture, uv);
        this.picker.setVisible(uv != null && texture != null);
        if (uv != null && texture != null && !fits(uv, texture)) {
            this.faceNote.set(tr("extras.face_outside", texture.width(), texture.height()), Theme.RED);
        } else if (uv != null) {
            this.faceNote.set(tr("extras.face_region", uv.u(), uv.v(), uv.w(), uv.h()), HELP);
        } else {
            this.faceNote.set(tr("extras.face_model"), HELP);
        }
    }

    /** Whether a face region lies inside a texture. */
    static boolean fits(WorldCollectionRecord.Uv uv, BlobInfo texture) {
        return uv.u() >= 0 && uv.v() >= 0 && uv.w() >= 1 && uv.h() >= 1
                && (long) uv.u() + uv.w() <= texture.width() && (long) uv.v() + uv.h() <= texture.height();
    }

    // Layout

    /** Adds the extras to the inspector's scrolling sections, under a height. */
    void layout(UiScrollPane sections, int x, int top, int width, int slotLabelWidth) {
        this.zoneLabelWidth = slotLabelWidth;
        Font font = Minecraft.getInstance().font;
        int y = top + ROW_GAP;
        this.rarityLabel.at(x, y, width, LABEL);
        y += LABEL + 1;
        this.rarity.at(x, y, width, UiSegmented.HEIGHT);
        y += UiSegmented.HEIGHT + 3;
        this.chance.at(x, y, width, NOTE);
        y += NOTE + ROW_GAP;
        for (UiNode node : List.of(this.rarityLabel, this.rarity, this.chance)) {
            sections.add(node);
        }

        this.variantsLabel.at(x, y, width, LABEL);
        y += LABEL + 1;
        this.variantsCount.at(x, y, width, NOTE);
        y += NOTE + 2;
        sections.add(this.variantsLabel);
        sections.add(this.variantsCount);
        for (VariantRow row : this.rows) {
            y = row.layout(sections, x, y, width);
        }
        UiDropZone add = this.addZone;
        if (add != null) {
            add.labelWidth(slotLabelWidth);
            add.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
            sections.add(add);
            y += UiDropZone.COMPACT_HEIGHT + ROW_GAP;
        }

        this.faceLabel.at(x, y, width, LABEL);
        y += LABEL + 1;
        this.face.at(x, y, Math.min(width, UiCheckbox.widthFor(font, tr("extras.face_show"))), UiCheckbox.BOX + 2);
        y += UiCheckbox.BOX + 5;
        sections.add(this.faceLabel);
        sections.add(this.face);
        this.faceNote.at(x, y, width, NOTE);
        sections.add(this.faceNote);
        y += NOTE;
        if (this.picker.visible()) {
            this.picker.at(x, y, width, this.picker.heightFor(width));
            sections.add(this.picker);
        }
    }

    // Changes

    private void setRarity(int index) {
        FigureDraft shown = this.figure;
        if (shown == null) {
            return;
        }
        this.editor.draft().checkpoint();
        shown.setRarity(Rarity.values()[index]);
        this.editor.changed();
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
        if (next.size() >= WorldContentLimits.MAX_VARIANTS_PER_FIGURE || inCollection >= WorldContentLimits.MAX_VARIANTS) {
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
            this.name = new NameField(this::renamed, this::startedTyping);
            this.remove = new UiButton(Component.literal("✕"), UiButton.Style.SECONDARY, this::removed);
            this.remove.setTooltip(tr("extras.variant_remove"));
            FilePicker.Slot slot = figureMade.kind() == Kind.SKIN ? FilePicker.Slot.SKIN : FilePicker.Slot.MODEL_TEXTURE;
            this.zone = UiDropZone.single(editor, slot, tr("extras.variant_texture"), this::replaced)
                    .labelWidth(zoneLabelWidth);
        }

        int layout(UiScrollPane sections, int x, int y, int width) {
            Font font = Minecraft.getInstance().font;
            int removeWidth = UiButton.widthFor(font, this.remove.label(), false);
            this.name.at(x, y, Math.max(20, width - removeWidth - 4), NameField.HEIGHT);
            this.remove.at(x + width - removeWidth, y + (NameField.HEIGHT - UiButton.SMALL_HEIGHT) / 2, removeWidth,
                    UiButton.SMALL_HEIGHT);
            y += NameField.HEIGHT + 2;
            this.zone.labelWidth(zoneLabelWidth);
            this.zone.at(x, y, width, UiDropZone.COMPACT_HEIGHT);
            sections.add(this.name);
            sections.add(this.remove);
            sections.add(this.zone);
            return y + UiDropZone.COMPACT_HEIGHT + ROW_GAP;
        }

        void show(VariantDraft variant, Function<BlobInfo, List<FileChecks.Check>> checks) {
            this.name.show(variant.name());
            this.zone.checks(checks);
            FileRef ref = variant.texture();
            String key = ref.sha() + "/" + (ref.info() != null) + "/" + ClientWorldCollections.isMissingOnServer(ref.sha())
                    + "/" + (figure != null && figure.model() != null ? figure.model().sha() : "");
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
            next.remove(this.index);
            editor.draft().checkpoint();
            target.setVariants(next);
            editor.changed();
        }
    }

    /** A line or two of text in the sections, cut to fit with the rest in its tooltip. */
    private static final class Note extends UiNode.Box {
        private final int maxLines;
        private Component text = Component.empty();
        private int colour = HELP;

        Note(int maxLines) {
            this.maxLines = maxLines;
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
                graphics.drawString(font, line, x, lineY, this.colour, false);
                lineY += Theme.LINE_HEIGHT - 1;
            }
            if (hovered(mouseX, mouseY) && !String.join(" ", lines).equals(full)) {
                UiTooltip.set(this.text);
            }
        }
    }

    /**
     * A variant's name: the node's own vanilla text field, which it draws and hands input to
     * itself so it works inside the scrolling sections, as {@code UiStepper} does with its number.
     * A name the receiver refuses outlines the field in red, and the field shows the name the
     * draft keeps again when it loses the keyboard. Enter and Escape leave the field.
     */
    private static final class NameField extends UiNode.Box {
        static final int HEIGHT = 20;

        private final EditBox field;
        // Told each typed name; answers whether it was taken
        private final Function<String, Boolean> onName;
        private final Runnable onStart;
        private String kept = "";
        private boolean valid = true;
        private boolean syncing;
        private boolean typing;
        private boolean pressedField;

        NameField(Function<String, Boolean> onName, Runnable onStart) {
            this.onName = onName;
            this.onStart = onStart;
            this.height = HEIGHT;
            Font font = Minecraft.getInstance().font;
            this.field = new EditBox(font, 0, 0, 40, 20, tr("extras.variant_name_hint"));
            this.field.setMaxLength(WorldContentLimits.MAX_NAME_LENGTH);
            this.field.setValue("");
            this.field.setResponder(this::typedText);
        }

        /** Shows the name the draft keeps, unless the field is being typed in. */
        void show(String name) {
            this.kept = name;
            if (!this.typing && !this.field.getValue().equals(name)) {
                showKept();
            }
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
            this.field.setX(x);
            this.field.setY(y);
            int fieldWidth = Math.max(16, width);
            if (fieldWidth != this.field.getWidth()) {
                this.field.setWidth(fieldWidth);
                if (!this.typing) {
                    // The field scrolled its text for the width it had; showing it again from
                    // empty starts it at its first letter
                    this.syncing = true;
                    this.field.setValue("");
                    this.syncing = false;
                    showKept();
                }
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
                Draw.outline(graphics, this.field.getX() - 1, this.field.getY() - 1, this.field.getWidth() + 2, 22,
                        Theme.RED);
                if (hovered(mouseX, mouseY)) {
                    UiTooltip.set(tr("extras.variant_name_rule"));
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
            graphics.fill(image.x(), image.y(), image.right(), image.bottom(), 0xFF1D1D1D);
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
                        Theme.GOLD);
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
