package com.theplumteam.client.gui;

import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adds a figure to a World collection, or edits one: its id (fixed once saved), name,
 * type, size and files. A skin figure is a 64x64 skin on the default figure model; a
 * model figure has its own texture, a {@code .geo.json} model and, optionally, an
 * animation file with its poses.
 *
 * <p>Save uploads the files chosen here and then asks the server to put the figure. The
 * preview shows the figure as the server last synced it, so a change shows once it is
 * saved and synced back.
 */
public class WorldFigureEditorScreen extends WorldCreatorScreen {
    private static final int LABEL_WIDTH = 44;
    private static final int SLOT_BUTTON_WIDTH = 140;
    private static final int DEFAULT_SCALE_INDEX = WorldCollectionRecord.SCALES.indexOf(1.0f);
    /**
     * The panel height the full layout needs: every row, the picker help in up to four
     * lines, then the status line and Back and Save below it. A shorter panel uses the
     * compact layout.
     */
    private static final int FULL_HEIGHT = 324;

    private final String collectionId;
    // The figure's id once it exists on the server; null while a new figure is unsaved
    @Nullable
    private String figureId;
    // The figure the form last followed; a sync replaces it
    @Nullable
    private WorldCollectionRecord.Figure loaded;

    // The form
    private String slug = "";
    private String name = "";
    private int kind = WorldCollectionEdit.KIND_SKIN;
    private int scaleIndex = DEFAULT_SCALE_INDEX;
    @Nullable
    private String textureSha;
    @Nullable
    private FilePicker.LocalFile textureFile;
    @Nullable
    private String textureRefusal;
    @Nullable
    private String modelSha;
    @Nullable
    private FilePicker.LocalFile modelFile;
    @Nullable
    private String modelRefusal;
    @Nullable
    private String animationSha;
    @Nullable
    private FilePicker.LocalFile animationFile;
    @Nullable
    private String animationRefusal;

    @Nullable
    private FigureEntry previewEntry;
    private Button saveButton;
    private Button kindButton;
    private Button scaleButton;
    private Button modelButton;
    private Button animationButton;
    private Button animationClearButton;

    /**
     * @param figureId the figure to edit, or null to add a new one
     */
    public WorldFigureEditorScreen(Screen parent, String collectionId, @Nullable String figureId) {
        super(label("figure_title"), parent, 560, 340);
        this.collectionId = collectionId;
        WorldCollectionRecord record = ClientWorldCollections.record(collectionId);
        WorldCollectionRecord.Figure figure = record != null && figureId != null
                ? record.figure(figureId).orElse(null) : null;
        if (figure != null) {
            this.figureId = figure.id();
            this.loaded = figure;
            load(figure);
        }
    }

    private boolean isNew() {
        return this.figureId == null;
    }

    private boolean isModel() {
        return this.kind == WorldCollectionEdit.KIND_MODEL;
    }

    /** Fills the form from a figure, dropping every unsaved change. */
    private void load(WorldCollectionRecord.Figure figure) {
        this.slug = figure.id();
        this.name = figure.name();
        this.kind = figure.kind() == WorldCollectionRecord.Kind.MODEL
                ? WorldCollectionEdit.KIND_MODEL : WorldCollectionEdit.KIND_SKIN;
        int index = WorldCollectionRecord.scaleIndex(figure.scale());
        this.scaleIndex = index >= 0 ? index : DEFAULT_SCALE_INDEX;
        this.textureSha = figure.texture();
        this.modelSha = figure.model();
        this.animationSha = figure.animation();
        this.textureFile = null;
        this.modelFile = null;
        this.animationFile = null;
        this.textureRefusal = null;
        this.modelRefusal = null;
        this.animationRefusal = null;
    }

    @Nullable
    private String texture() {
        return this.textureFile != null ? this.textureFile.sha() : this.textureSha;
    }

    @Nullable
    private String model() {
        return this.modelFile != null ? this.modelFile.sha() : this.modelSha;
    }

    @Nullable
    private String animation() {
        return this.animationFile != null ? this.animationFile.sha() : this.animationSha;
    }

    /** Whether the form says exactly what the figure says. */
    private boolean matches(@Nullable WorldCollectionRecord.Figure figure) {
        if (figure == null || isNew()) {
            return false;
        }
        boolean model = isModel();
        if (model != (figure.kind() == WorldCollectionRecord.Kind.MODEL)) {
            return false;
        }
        return this.name.equals(figure.name())
                && WorldCollectionRecord.SCALES.get(this.scaleIndex) == figure.scale()
                && Objects.equals(texture(), figure.texture())
                && (!model || (Objects.equals(model(), figure.model()) && Objects.equals(animation(), figure.animation())));
    }

    @Nullable
    private WorldCollectionRecord record() {
        return ClientWorldCollections.record(this.collectionId);
    }

    @Nullable
    private static BlobInfo info(@Nullable FilePicker.LocalFile file, @Nullable String sha) {
        return file != null ? file.info() : sha != null ? ClientWorldCollections.blob(sha) : null;
    }

    /** Why the form cannot be saved, or null when it can. */
    @Nullable
    private String formProblem() {
        WorldCollectionRecord record = record();
        if (record == null) {
            return "";
        }
        if (isNew()) {
            if (!WorldContentIds.isSlug(this.slug)) {
                return tr("figure_id_rule");
            }
            if (record.figure(this.slug).isPresent()) {
                return tr("figure_id_taken", this.slug);
            }
            if (record.figures().size() >= WorldContentLimits.MAX_FIGURES) {
                return tr("too_many_figures", WorldContentLimits.MAX_FIGURES);
            }
        }
        if (this.name.isBlank() || !WorldCollectionRecord.isPlainText(this.name)) {
            return tr("name_rule");
        }
        if (info(this.textureFile, this.textureSha) == null) {
            return tr("texture_required");
        }
        if (isModel() && info(this.modelFile, this.modelSha) == null) {
            return tr("model_required");
        }
        return fileMismatch();
    }

    /**
     * Why the files chosen so far do not fit this figure or each other, or null when they
     * do or are not all chosen yet.
     */
    @Nullable
    private String fileMismatch() {
        BlobInfo texture = info(this.textureFile, this.textureSha);
        if (texture == null) {
            return null;
        }
        try {
            (isModel() ? PngValidator.Role.MODEL_TEXTURE : PngValidator.Role.SKIN).check(texture);
        } catch (WorldContentException e) {
            return e.getMessage();
        }
        if (!isModel()) {
            return null;
        }
        BlobInfo model = info(this.modelFile, this.modelSha);
        if (model == null) {
            return null;
        }
        if (model.type() != BlobType.GEO) {
            return tr("model_required");
        }
        // The texture must have the proportions the model's UV layout was made for
        if ((long) texture.width() * model.texHeight() != (long) texture.height() * model.texWidth()) {
            return tr("aspect_rule", texture.width(), texture.height(), model.texWidth(), model.texHeight());
        }
        BlobInfo animation = info(this.animationFile, this.animationSha);
        if (animation() != null && (animation == null || animation.type() != BlobType.ANIMATION
                || !animation.hasPose(BlobInfo.POSE_STAND))) {
            return tr("animation_rule");
        }
        return null;
    }

    @Override
    protected void onSynced() {
        WorldCollectionRecord record = record();
        if (record == null) {
            // Purged by a command; the collection editor goes back too
            this.minecraft.setScreen(this.parent);
            return;
        }
        if (this.figureId == null) {
            return;
        }
        WorldCollectionRecord.Figure current = record.figure(this.figureId).orElse(null);
        // A form nobody touched follows the server; one with unsaved changes keeps them. A new
        // figure saved here has followed nothing yet, so it follows only while the form still
        // says what was saved.
        boolean untouched = this.loaded != null ? matches(this.loaded) : matches(current);
        this.loaded = current;
        if (untouched && current != null) {
            load(current);
        }
    }

    @Override
    protected String heading() {
        WorldCollectionRecord record = record();
        String collection = record != null ? record.name() : this.collectionId;
        return isNew() ? tr("figure_new_heading", collection)
                : tr("figure_edit_heading", this.loaded != null ? this.loaded.name() : this.figureId, collection);
    }

    // Layout

    private int leftX() {
        return this.panelX + 12;
    }

    private int previewSize() {
        return Math.max(60, Math.min(140, this.panelWidth / 3 - 12));
    }

    private int leftWidth() {
        return this.panelWidth - 36 - previewSize();
    }

    private int previewX() {
        return this.panelX + this.panelWidth - 12 - previewSize();
    }

    /**
     * Whether the panel is too short for the full layout, as at the automatic GUI scale of
     * common screens: each file's line then sits beside its button, the picker help and
     * the rules are left out, the status line follows the path row, and Back and Save go
     * under the preview.
     */
    private boolean compact() {
        return this.panelHeight < FULL_HEIGHT;
    }

    private int slotButtonWidth() {
        return compact() ? Math.min(SLOT_BUTTON_WIDTH, leftWidth() / 2) : SLOT_BUTTON_WIDTH;
    }

    private int textureY() {
        return this.panelY + 106;
    }

    private int modelY() {
        return this.panelY + (compact() ? 128 : 142);
    }

    private int animationY() {
        return this.panelY + (compact() ? 150 : 178);
    }

    private int pathY() {
        return this.panelY + (compact() ? 172 : 214);
    }

    /** The side of the preview square, which leaves room under it for its caption and, compact, Back and Save. */
    private int previewDrawSize() {
        return compact() ? Math.max(24, Math.min(previewSize(), this.panelHeight - 110)) : previewSize();
    }

    @Override
    protected int statusCenterX() {
        return compact() ? leftX() + leftWidth() / 2 : super.statusCenterX();
    }

    @Override
    protected int statusY() {
        return compact() ? pathY() + 24 : super.statusY();
    }

    @Override
    protected int statusWidth() {
        return compact() ? leftWidth() : super.statusWidth();
    }

    @Override
    protected int statusLines() {
        // Compact, the status line has the left column below the path row to itself
        return compact() ? linesBetween(statusY(), this.panelY + this.panelHeight - 8) : super.statusLines();
    }

    @Override
    protected void build() {
        int x = leftX();
        int width = leftWidth();
        int fieldX = x + LABEL_WIDTH;
        int fieldWidth = width - LABEL_WIDTH;
        int top = this.panelY;

        if (isNew()) {
            textField("id", fieldX, top + 34, fieldWidth, 24, this.slug, label("figure_id_hint"),
                    value -> this.slug = value);
        }
        textField("name", fieldX, top + 58, fieldWidth, WorldContentLimits.MAX_NAME_LENGTH, this.name,
                label("figure_name_hint"), value -> this.name = value);

        int kindWidth = (width - 4) * 3 / 5;
        this.kindButton = Button.builder(Component.empty(), button -> {
                    this.kind = isModel() ? WorldCollectionEdit.KIND_SKIN : WorldCollectionEdit.KIND_MODEL;
                    if (!isModel()) {
                        disarm(FilePicker.Slot.MODEL);
                        disarm(FilePicker.Slot.ANIMATION);
                    }
                    updateButtons();
                })
                .bounds(x, top + 82, kindWidth, 20)
                .build();
        this.addRenderableWidget(this.kindButton);
        this.scaleButton = Button.builder(Component.empty(), button -> {
                    this.scaleIndex = (this.scaleIndex + 1) % WorldCollectionRecord.SCALES.size();
                    updateButtons();
                })
                .bounds(x + kindWidth + 4, top + 82, width - kindWidth - 4, 20)
                .build();
        this.addRenderableWidget(this.scaleButton);

        int slotWidth = slotButtonWidth();
        slotButton(label("texture"), () -> isModel() ? FilePicker.Slot.MODEL_TEXTURE : FilePicker.Slot.SKIN,
                x, textureY(), slotWidth);
        this.modelButton = slotButton(label("model"), () -> FilePicker.Slot.MODEL, x, modelY(), slotWidth);
        this.animationButton = slotButton(label("animation"), () -> FilePicker.Slot.ANIMATION,
                x, animationY(), slotWidth);
        this.animationClearButton = Button.builder(label("clear"), button -> {
                    this.animationSha = null;
                    this.animationFile = null;
                    this.animationRefusal = null;
                    updateButtons();
                })
                .bounds(x + slotWidth + 4, animationY(), 44, 20)
                .build();
        this.addRenderableWidget(this.animationClearButton);

        pathRow(x, pathY(), width);

        FigureCollection collection = CollectionRegistry.getCollection(this.collectionId).orElse(null);
        this.previewEntry = collection != null ? new FigureEntry(collection.getFigures(), this.collectionId) : null;
        if (this.previewEntry != null) {
            // The camera of the machine screen's figure list
            this.previewEntry.setConfiguration(1.0f, 0.0f, 150.0f, 0.0f, 0.0f, 15.0f, 0.0f);
        }

        int buttonsY = this.panelY + this.panelHeight - 28;
        int centerX = this.panelX + this.panelWidth / 2;
        Button.Builder back = Button.builder(label("back"), button -> this.onClose());
        Button.Builder save = Button.builder(label("save"), button -> save());
        if (compact()) {
            // Under the preview, clear of every row of the form
            back.bounds(previewX(), buttonsY - 22, previewSize(), 20);
            save.bounds(previewX(), buttonsY, previewSize(), 20);
        } else {
            back.bounds(centerX - 104, buttonsY, 100, 20);
            save.bounds(centerX + 4, buttonsY, 100, 20);
        }
        this.addRenderableWidget(back.build());
        this.saveButton = save.build();
        this.addRenderableWidget(this.saveButton);
    }

    @Override
    protected void refreshButtons() {
        if (this.saveButton == null) {
            return;
        }
        boolean model = isModel();
        this.kindButton.setMessage(label(model ? "kind_toggle_model" : "kind_toggle_skin"));
        this.scaleButton.setMessage(label("scale_toggle",
                WorldCollectionEditorScreen.scaleText(WorldCollectionRecord.SCALES.get(this.scaleIndex))));
        // Only a model figure has a model and poses of its own
        this.modelButton.visible = model;
        this.modelButton.active &= model;
        this.animationButton.visible = model;
        this.animationButton.active &= model;
        this.animationClearButton.visible = model;
        this.animationClearButton.active = model && animation() != null;
        this.saveButton.active = !isWaiting() && formProblem() == null && !matches(this.loaded);
    }

    @Override
    protected void accepted(FilePicker.Slot slot, FilePicker.LocalFile file) {
        switch (slot) {
            case SKIN, MODEL_TEXTURE -> {
                this.textureFile = file;
                this.textureRefusal = null;
            }
            case MODEL -> {
                this.modelFile = file;
                this.modelRefusal = null;
            }
            case ANIMATION -> {
                this.animationFile = file;
                this.animationRefusal = null;
            }
            default -> {
            }
        }
        // A file that fits on its own may still not fit the rest of the figure
        String mismatch = fileMismatch();
        if (mismatch != null) {
            setStatus(mismatch, WARNING);
        }
    }

    @Override
    protected void refused(FilePicker.Slot slot, String reason) {
        switch (slot) {
            case SKIN, MODEL_TEXTURE -> this.textureRefusal = reason;
            case MODEL -> this.modelRefusal = reason;
            case ANIMATION -> this.animationRefusal = reason;
            default -> {
            }
        }
    }

    private void save() {
        if (formProblem() != null) {
            return;
        }
        boolean model = isModel();
        String id = isNew() ? this.slug : this.figureId;
        FilePicker.LocalFile sentTexture = this.textureFile;
        FilePicker.LocalFile sentModel = model ? this.modelFile : null;
        FilePicker.LocalFile sentAnimation = model ? this.animationFile : null;
        List<FilePicker.LocalFile> files = new ArrayList<>();
        for (FilePicker.LocalFile file : new FilePicker.LocalFile[] {sentTexture, sentModel, sentAnimation}) {
            if (file != null) {
                files.add(file);
            }
        }
        String animation = model ? animation() : null;
        WorldCollectionEdit edit = new WorldCollectionEdit.PutFigure(this.collectionId, id, this.name, this.kind,
                texture(), model ? model() : "", animation != null ? animation : "", this.scaleIndex);
        submit(files, edit, outcome -> {
            showOutcome(outcome, tr("figure_saved", this.name));
            if (!outcome.ok()) {
                return;
            }
            // The server has the files now; the form names them by their sha like the record will
            if (sentTexture != null && this.textureFile == sentTexture) {
                this.textureSha = sentTexture.sha();
                this.textureFile = null;
            }
            if (sentModel != null && this.modelFile == sentModel) {
                this.modelSha = sentModel.sha();
                this.modelFile = null;
            }
            if (sentAnimation != null && this.animationFile == sentAnimation) {
                this.animationSha = sentAnimation.sha();
                this.animationFile = null;
            }
            if (this.figureId == null) {
                // The id is fixed from now on, so its field goes away
                this.figureId = id;
                if (this.minecraft.screen == this) {
                    rebuild();
                }
            }
        });
    }

    @Override
    protected String idleStatus() {
        String problem = formProblem();
        if (problem != null) {
            return problem;
        }
        return matches(this.loaded) ? "" : tr("unsaved");
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = leftX();
        int width = leftWidth();
        int fieldX = x + LABEL_WIDTH;
        int top = this.panelY;
        graphics.drawCenteredString(this.font, cut(this.collectionId
                        + (isNew() ? "" : ":" + this.figureId), this.panelWidth - 16),
                this.panelX + this.panelWidth / 2, top + 20, MUTED);

        graphics.drawString(this.font, tr("figure_id"), x, top + 40, TEXT);
        if (!isNew()) {
            drawCut(graphics, this.figureId + "  " + tr("figure_id_fixed"), fieldX, top + 40, width - LABEL_WIDTH, MUTED);
        }
        graphics.drawString(this.font, tr("name"), x, top + 64, TEXT);

        boolean model = isModel();
        int slotWidth = slotButtonWidth();
        int noteX = x + slotWidth + 6;
        int noteWidth = width - slotWidth - 6;
        if (compact()) {
            // Each file's line beside its button; the status line gives a refused file's rule
            drawSlotLine(graphics, this.textureRefusal, this.textureFile, this.textureSha, tr("none_chosen"),
                    noteX, textureY() + 6, noteWidth);
            if (model) {
                drawSlotLine(graphics, this.modelRefusal, this.modelFile, this.modelSha, tr("none_chosen"),
                        noteX, modelY() + 6, noteWidth);
                drawSlotLine(graphics, this.animationRefusal, this.animationFile, this.animationSha,
                        tr("animation_none"), noteX + 48, animationY() + 6, noteWidth - 48);
            } else {
                drawCut(graphics, tr("skin_note"), x, modelY() + 6, width, MUTED);
            }
        } else {
            drawCut(graphics, tr(model ? "texture_rule_model" : "texture_rule_skin"), noteX, textureY() + 6, noteWidth,
                    MUTED);
            drawSlotLine(graphics, this.textureRefusal, this.textureFile, this.textureSha, tr("none_chosen"),
                    x, textureY() + 24, width);
            if (model) {
                drawCut(graphics, tr("model_rule"), noteX, modelY() + 6, noteWidth, MUTED);
                drawSlotLine(graphics, this.modelRefusal, this.modelFile, this.modelSha, tr("none_chosen"),
                        x, modelY() + 24, width);
                drawCut(graphics, tr("animation_note"), noteX + 48, animationY() + 6, noteWidth - 48, MUTED);
                drawSlotLine(graphics, this.animationRefusal, this.animationFile, this.animationSha,
                        tr("animation_none"), x, animationY() + 24, width);
            } else {
                drawCut(graphics, tr("skin_note"), x, modelY() + 6, width, MUTED);
            }
            drawPickerHelp(graphics, x, pathY() + 24, width, linesBetween(pathY() + 24, statusY() - 2));
        }

        drawPreview(graphics, partialTicks);
    }

    private void drawSlotLine(GuiGraphics graphics, @Nullable String refusal, @Nullable FilePicker.LocalFile file,
                              @Nullable String sha, String none, int x, int y, int width) {
        Line line = refusalLine(refusal, file, sha, none);
        drawCut(graphics, line.text(), x, y, width, line.color());
    }

    private void drawPreview(GuiGraphics graphics, float partialTicks) {
        int size = previewDrawSize();
        int x = previewX() + (previewSize() - size) / 2;
        int y = this.panelY + 34;
        graphics.fill(x, y, x + size, y + size, 0x30FFFFFF);
        frame(graphics, x, y, size, size, 0x80FFFFFF);

        FigureCollection collection = CollectionRegistry.getCollection(this.collectionId).orElse(null);
        FigureDefinition figure = collection != null && this.figureId != null
                ? collection.getFigure(this.figureId).orElse(null) : null;
        String caption;
        if (figure == null || this.previewEntry == null) {
            caption = tr("preview_after_saving");
        } else {
            this.previewEntry.renderPreview(graphics, figure, x + 2, y + 2, size - 4, partialTicks);
            caption = tr(matches(this.loaded) ? "preview_saved" : "preview_saved_changed");
        }
        // The caption wraps under the preview, above Back and Save when they sit there too
        int captionY = y + size + 4;
        int bottom = compact() ? this.panelY + this.panelHeight - 52 : statusY() - 4;
        for (String part : fit(caption, previewSize() + 8, linesBetween(captionY, bottom))) {
            graphics.drawCenteredString(this.font, part, x + size / 2, captionY, MUTED);
            captionY += LINE_HEIGHT;
        }
    }
}
