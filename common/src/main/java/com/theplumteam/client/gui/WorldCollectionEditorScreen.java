package com.theplumteam.client.gui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Edits one World collection: its name, author, background colour, box and logo on the
 * left, and its figures on the right, where each can be opened in the figure editor,
 * retired or restored.
 *
 * <p>The form keeps what the operator changed until Save, which uploads the files chosen
 * here and then asks the server to apply the whole form. A sync that arrives meanwhile
 * refreshes the figures, and the form too while it holds no unsaved change. Figure
 * previews show the figures as the server last synced them.
 */
public class WorldCollectionEditorScreen extends WorldCreatorScreen {
    private static final int LEFT_WIDTH = 262;
    private static final int FIELD_OFFSET = 44;
    private static final int TILES_PER_ROW = 4;
    private static final int TILE_ROWS = 2;
    private static final int TILE_GAP = 6;
    private static final int TILE_SELECTED = 0xFFFFD700;
    private static final String DEFAULT_BOX_COLOR = PopBlockColor.RED.getSerializedName();
    /**
     * The panel height the full layout needs: the left column with the picker help in up to
     * four lines, then the status line and Back and Save below it. A shorter panel uses the
     * compact layout.
     */
    private static final int FULL_HEIGHT = 290;

    private final String collectionId;
    // The record the form last followed; a sync replaces it
    @Nullable
    private WorldCollectionRecord loaded;

    // The form
    private String name = "";
    private String author = "";
    private String red = "";
    private String green = "";
    private String blue = "";
    private int boxMode = WorldCollectionEdit.BOX_NONE;
    private String boxColor = DEFAULT_BOX_COLOR;
    @Nullable
    private String boxSha;
    @Nullable
    private FilePicker.LocalFile boxFile;
    @Nullable
    private String boxRefusal;
    @Nullable
    private String logoSha;
    @Nullable
    private FilePicker.LocalFile logoFile;
    @Nullable
    private String logoRefusal;

    // The figures
    private int figurePage;
    @Nullable
    private String selectedFigure;
    private final List<WorldCollectionRecord.Figure> visibleFigures = new ArrayList<>();
    private final List<Button> tiles = new ArrayList<>();
    @Nullable
    private FigureEntry previewEntry;

    private Button saveButton;
    private Button boxColorButton;
    private Button boxClearButton;
    private Button logoClearButton;
    private Button addFigureButton;
    private Button editFigureButton;
    private Button retireButton;

    public WorldCollectionEditorScreen(Screen parent, String collectionId) {
        super(label("editor_title"), parent, 620, 360);
        this.collectionId = collectionId;
        this.loaded = ClientWorldCollections.record(collectionId);
        if (this.loaded != null) {
            load(this.loaded);
        }
    }

    /** Fills the form from a record, dropping every unsaved change. */
    private void load(WorldCollectionRecord record) {
        this.name = record.name();
        this.author = record.author();
        this.red = String.valueOf(record.red());
        this.green = String.valueOf(record.green());
        this.blue = String.valueOf(record.blue());
        this.boxMode = boxMode(record);
        this.boxColor = record.boxColor() != null ? record.boxColor() : DEFAULT_BOX_COLOR;
        this.boxSha = record.boxTexture();
        this.boxFile = null;
        this.boxRefusal = null;
        this.logoSha = record.logo();
        this.logoFile = null;
        this.logoRefusal = null;
    }

    private static int boxMode(WorldCollectionRecord record) {
        return record.boxTexture() != null ? WorldCollectionEdit.BOX_TEXTURE
                : record.boxColor() != null ? WorldCollectionEdit.BOX_COLOR
                : WorldCollectionEdit.BOX_NONE;
    }

    /** Whether the form says exactly what the record says. */
    private boolean matches(WorldCollectionRecord record) {
        if (this.boxFile != null || this.logoFile != null || this.boxMode != boxMode(record)) {
            return false;
        }
        boolean sameBox = switch (this.boxMode) {
            case WorldCollectionEdit.BOX_TEXTURE -> Objects.equals(this.boxSha, record.boxTexture());
            case WorldCollectionEdit.BOX_COLOR -> this.boxColor.equals(record.boxColor());
            default -> true;
        };
        return sameBox && this.name.equals(record.name()) && this.author.equals(record.author())
                && channel(this.red) == record.red() && channel(this.green) == record.green()
                && channel(this.blue) == record.blue() && Objects.equals(this.logoSha, record.logo());
    }

    /** A colour channel the operator typed, or -1 when it is not 0 to 255. */
    private static int channel(String typed) {
        if (typed.isEmpty() || typed.length() > 3 || !typed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        int value = Integer.parseInt(typed);
        return value <= 255 ? value : -1;
    }

    /** Why the form cannot be saved, or null when it can. */
    @Nullable
    private String formProblem() {
        if (this.name.isBlank() || !WorldCollectionRecord.isPlainText(this.name)) {
            return tr("name_rule");
        }
        if (!WorldCollectionRecord.isPlainText(this.author)) {
            return tr("author_rule");
        }
        if (channel(this.red) < 0 || channel(this.green) < 0 || channel(this.blue) < 0) {
            return tr("color_rule");
        }
        if (this.boxMode == WorldCollectionEdit.BOX_TEXTURE && this.boxFile == null && this.boxSha == null) {
            return tr("box_rule");
        }
        return null;
    }

    @Nullable
    private WorldCollectionRecord record() {
        return ClientWorldCollections.record(this.collectionId);
    }

    @Override
    protected void onSynced() {
        WorldCollectionRecord current = record();
        if (current == null) {
            // Purged by a command; there is nothing left to edit
            this.minecraft.setScreen(this.parent);
            return;
        }
        // A form nobody touched follows the server; one with unsaved changes keeps them
        boolean untouched = this.loaded == null || matches(this.loaded);
        this.loaded = current;
        if (untouched) {
            load(current);
        }
        if (this.selectedFigure != null && current.figure(this.selectedFigure).isEmpty()) {
            this.selectedFigure = null;
        }
    }

    @Override
    protected String heading() {
        WorldCollectionRecord record = record();
        return record != null ? tr("editor_heading", record.name()) : this.title.getString();
    }

    // Layout

    private int leftX() {
        return this.panelX + 12;
    }

    private int leftWidth() {
        return Math.min(LEFT_WIDTH, (this.panelWidth - 36) / 2 + 40);
    }

    private int fieldX() {
        return leftX() + FIELD_OFFSET;
    }

    private int fieldWidth() {
        return leftWidth() - FIELD_OFFSET;
    }

    private int rightX() {
        return leftX() + leftWidth() + 12;
    }

    private int rightWidth() {
        return this.panelX + this.panelWidth - 12 - rightX();
    }

    private int tileSize() {
        // Small enough that the figure buttons end above Back and Save
        int byHeight = (this.panelHeight - 130) / 2;
        return Math.max(24, Math.min(Math.min(60, byHeight),
                (rightWidth() - (TILES_PER_ROW - 1) * TILE_GAP) / TILES_PER_ROW));
    }

    /**
     * Whether the panel is too short for the full layout, as at the automatic GUI scale of
     * common screens: the picker help is left out, the status line follows the path row,
     * and Back and Save go under the figures.
     */
    private boolean compact() {
        return this.panelHeight < FULL_HEIGHT;
    }

    private int pathY() {
        return this.panelY + 180;
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

    private int tileX(int index) {
        return rightX() + (index % TILES_PER_ROW) * (tileSize() + TILE_GAP);
    }

    private int tileY(int index) {
        return this.panelY + 46 + (index / TILES_PER_ROW) * (tileSize() + TILE_GAP);
    }

    private int figureInfoY() {
        return tileY(TILES_PER_ROW * TILE_ROWS - 1) + tileSize() + 4;
    }

    @Override
    protected void build() {
        int x = fieldX();
        int width = fieldWidth();
        int top = this.panelY;

        textField("name", x, top + 34, width, WorldContentLimits.MAX_NAME_LENGTH, this.name, label("name_hint"),
                value -> this.name = value);
        textField("author", x, top + 58, width, WorldContentLimits.MAX_AUTHOR_LENGTH, this.author,
                label("author_hint"), value -> this.author = value);
        textField("red", x, top + 82, 36, 3, this.red, Component.literal("R"), value -> this.red = value);
        textField("green", x + 40, top + 82, 36, 3, this.green, Component.literal("G"), value -> this.green = value);
        textField("blue", x + 80, top + 82, 36, 3, this.blue, Component.literal("B"), value -> this.blue = value);

        slotButton(label("box_texture"), () -> FilePicker.Slot.BOX, x, top + 106, 64);
        this.boxColorButton = Button.builder(Component.empty(), button -> cycleBoxColor())
                .bounds(x + 68, top + 106, width - 68 - 48, 20)
                .build();
        this.addRenderableWidget(this.boxColorButton);
        this.boxClearButton = Button.builder(label("clear"), button -> {
                    this.boxMode = WorldCollectionEdit.BOX_NONE;
                    this.boxSha = null;
                    this.boxFile = null;
                    this.boxRefusal = null;
                    updateButtons();
                })
                .bounds(x + width - 44, top + 106, 44, 20)
                .build();
        this.addRenderableWidget(this.boxClearButton);

        slotButton(label("logo"), () -> FilePicker.Slot.LOGO, x, top + 142, width - 48);
        this.logoClearButton = Button.builder(label("clear"), button -> {
                    this.logoSha = null;
                    this.logoFile = null;
                    this.logoRefusal = null;
                    updateButtons();
                })
                .bounds(x + width - 44, top + 142, 44, 20)
                .build();
        this.addRenderableWidget(this.logoClearButton);

        pathRow(leftX(), pathY(), leftWidth());

        buildFigures();

        int buttonsY = this.panelY + this.panelHeight - 28;
        int centerX = this.panelX + this.panelWidth / 2;
        Button.Builder back = Button.builder(label("back"), button -> this.onClose());
        Button.Builder save = Button.builder(label("save"), button -> save());
        if (compact()) {
            // Under the figures, clear of the left column
            int half = (rightWidth() - 4) / 2;
            back.bounds(rightX(), buttonsY, half, 20);
            save.bounds(rightX() + rightWidth() - half, buttonsY, half, 20);
        } else {
            back.bounds(centerX - 104, buttonsY, 100, 20);
            save.bounds(centerX + 4, buttonsY, 100, 20);
        }
        this.addRenderableWidget(back.build());
        this.saveButton = save.build();
        this.addRenderableWidget(this.saveButton);
    }

    private void buildFigures() {
        this.visibleFigures.clear();
        this.tiles.clear();
        WorldCollectionRecord record = record();
        List<WorldCollectionRecord.Figure> figures = record != null ? record.figures() : List.of();
        int perPage = TILES_PER_ROW * TILE_ROWS;
        int pages = Math.max(1, (figures.size() + perPage - 1) / perPage);
        this.figurePage = Math.max(0, Math.min(this.figurePage, pages - 1));
        int first = this.figurePage * perPage;
        int size = tileSize();
        for (int i = first; i < Math.min(first + perPage, figures.size()); i++) {
            WorldCollectionRecord.Figure figure = figures.get(i);
            this.visibleFigures.add(figure);
            int index = i - first;
            // An empty tile: the figure is drawn over it once the widgets are drawn
            Button tile = Button.builder(Component.empty(), button -> {
                        this.selectedFigure = figure.id();
                        updateButtons();
                    })
                    .bounds(tileX(index), tileY(index), size, size)
                    .build();
            this.addRenderableWidget(tile);
            this.tiles.add(tile);
        }
        FigureCollection collection = CollectionRegistry.getCollection(this.collectionId).orElse(null);
        this.previewEntry = collection != null ? new FigureEntry(collection.getFigures(), this.collectionId) : null;
        if (this.previewEntry != null) {
            // The camera of the machine screen's figure list
            this.previewEntry.setConfiguration(1.0f, 0.0f, 150.0f, 0.0f, 14.0f, 15.0f, 0.0f);
        }

        int buttonsY = figureInfoY() + 24;
        int x = rightX();
        int width = rightWidth();
        int actionWidth = (width - 40 - 4 * 4) / 3;
        Button previous = Button.builder(Component.literal("<"), button -> {
                    this.figurePage--;
                    rebuild();
                })
                .bounds(x, buttonsY, 20, 20)
                .build();
        previous.active = this.figurePage > 0;
        this.addRenderableWidget(previous);
        this.addFigureButton = Button.builder(label("add_figure"),
                        button -> this.minecraft.setScreen(new WorldFigureEditorScreen(this, this.collectionId, null)))
                .bounds(x + 24, buttonsY, actionWidth, 20)
                .build();
        this.addRenderableWidget(this.addFigureButton);
        this.editFigureButton = Button.builder(label("edit_figure"), button -> {
                    if (this.selectedFigure != null) {
                        this.minecraft.setScreen(new WorldFigureEditorScreen(this, this.collectionId, this.selectedFigure));
                    }
                })
                .bounds(x + 28 + actionWidth, buttonsY, actionWidth, 20)
                .build();
        this.addRenderableWidget(this.editFigureButton);
        this.retireButton = Button.builder(label("retire"), button -> toggleSelectedFigure())
                .bounds(x + 32 + 2 * actionWidth, buttonsY, actionWidth, 20)
                .build();
        this.addRenderableWidget(this.retireButton);
        Button next = Button.builder(Component.literal(">"), button -> {
                    this.figurePage++;
                    rebuild();
                })
                .bounds(x + width - 20, buttonsY, 20, 20)
                .build();
        next.active = this.figurePage + 1 < pages;
        this.addRenderableWidget(next);
    }

    @Override
    protected void refreshButtons() {
        if (this.saveButton == null) {
            return;
        }
        WorldCollectionRecord record = record();
        this.saveButton.active = record != null && !isWaiting() && formProblem() == null && !matches(record);
        this.boxColorButton.setMessage(this.boxMode == WorldCollectionEdit.BOX_COLOR
                ? label("box_color", colorName(this.boxColor)) : label("box_color_off"));
        this.boxClearButton.active = this.boxMode != WorldCollectionEdit.BOX_NONE;
        this.logoClearButton.active = this.logoSha != null || this.logoFile != null;

        WorldCollectionRecord.Figure selected = selected();
        this.addFigureButton.active = record != null && record.figures().size() < WorldContentLimits.MAX_FIGURES;
        this.editFigureButton.active = selected != null;
        this.retireButton.active = selected != null && !isWaiting();
        this.retireButton.setMessage(label(selected != null && !selected.enabled() ? "restore" : "retire"));
    }

    @Nullable
    private WorldCollectionRecord.Figure selected() {
        WorldCollectionRecord record = record();
        return record != null && this.selectedFigure != null ? record.figure(this.selectedFigure).orElse(null) : null;
    }

    private void cycleBoxColor() {
        if (this.boxMode != WorldCollectionEdit.BOX_COLOR) {
            this.boxMode = WorldCollectionEdit.BOX_COLOR;
        } else {
            PopBlockColor[] colors = PopBlockColor.values();
            int index = 0;
            for (int i = 0; i < colors.length; i++) {
                if (colors[i].getSerializedName().equals(this.boxColor)) {
                    index = i;
                }
            }
            this.boxColor = colors[(index + 1) % colors.length].getSerializedName();
        }
        this.boxFile = null;
        this.boxRefusal = null;
        updateButtons();
    }

    /** A box colour's name for people: "light_blue" reads "Light blue". */
    static String colorName(String serializedName) {
        String spaced = serializedName.replace('_', ' ');
        return spaced.isEmpty() ? spaced : spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    @Override
    protected void accepted(FilePicker.Slot slot, FilePicker.LocalFile file) {
        if (slot == FilePicker.Slot.BOX) {
            this.boxFile = file;
            this.boxMode = WorldCollectionEdit.BOX_TEXTURE;
            this.boxRefusal = null;
        } else if (slot == FilePicker.Slot.LOGO) {
            this.logoFile = file;
            this.logoRefusal = null;
        }
    }

    @Override
    protected void refused(FilePicker.Slot slot, String reason) {
        if (slot == FilePicker.Slot.BOX) {
            this.boxRefusal = reason;
        } else if (slot == FilePicker.Slot.LOGO) {
            this.logoRefusal = reason;
        }
    }

    private void save() {
        WorldCollectionRecord record = record();
        if (record == null || formProblem() != null) {
            return;
        }
        List<FilePicker.LocalFile> files = new ArrayList<>();
        String box;
        FilePicker.LocalFile sentBox = null;
        if (this.boxMode == WorldCollectionEdit.BOX_TEXTURE) {
            sentBox = this.boxFile;
            box = sentBox != null ? sentBox.sha() : this.boxSha;
            if (sentBox != null) {
                files.add(sentBox);
            }
        } else {
            box = this.boxMode == WorldCollectionEdit.BOX_COLOR ? this.boxColor : "";
        }
        FilePicker.LocalFile sentLogo = this.logoFile;
        String logo = sentLogo != null ? sentLogo.sha() : this.logoSha != null ? this.logoSha : "";
        if (sentLogo != null) {
            files.add(sentLogo);
        }
        WorldCollectionEdit edit = new WorldCollectionEdit.SetCollection(this.collectionId, this.name, this.author,
                channel(this.red), channel(this.green), channel(this.blue), this.boxMode, box, logo);
        FilePicker.LocalFile uploadedBox = sentBox;
        submit(files, edit, outcome -> {
            showOutcome(outcome, tr("saved", this.name));
            if (outcome.ok()) {
                // The server has the files now; the form names them by their sha like the record will
                if (uploadedBox != null && this.boxFile == uploadedBox) {
                    this.boxSha = uploadedBox.sha();
                    this.boxFile = null;
                }
                if (sentLogo != null && this.logoFile == sentLogo) {
                    this.logoSha = sentLogo.sha();
                    this.logoFile = null;
                }
            }
        });
    }

    private void toggleSelectedFigure() {
        WorldCollectionRecord.Figure figure = selected();
        WorldCollectionRecord record = record();
        if (figure == null || record == null) {
            return;
        }
        boolean enable = !figure.enabled();
        submit(List.of(), new WorldCollectionEdit.SetFigureEnabled(this.collectionId, figure.id(), enable),
                outcome -> showOutcome(outcome, Component.translatable("blockpops.world_collections."
                        + (enable ? "figure_restored" : "figure_retired"), figure.name(), record.name()).getString()));
    }

    @Override
    protected String idleStatus() {
        WorldCollectionRecord record = record();
        if (record == null) {
            return "";
        }
        String problem = formProblem();
        if (problem != null) {
            return problem;
        }
        return matches(record) ? "" : tr("unsaved");
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        WorldCollectionRecord record = record();
        if (record == null) {
            return;
        }
        int top = this.panelY;
        String subtitle = record.archived() ? tr("editor_subtitle_archived", record.id(), record.revision())
                : tr("editor_subtitle", record.id(), record.revision());
        graphics.drawCenteredString(this.font, cut(subtitle, this.panelWidth - 16),
                this.panelX + this.panelWidth / 2, top + 20, record.archived() ? WARNING : MUTED);

        // Left column
        int labelX = leftX();
        int x = fieldX();
        int width = fieldWidth();
        graphics.drawString(this.font, tr("name"), labelX, top + 40, TEXT);
        graphics.drawString(this.font, tr("author"), labelX, top + 64, TEXT);
        graphics.drawString(this.font, tr("color"), labelX, top + 88, TEXT);
        int swatchX = x + 122;
        int r = channel(this.red);
        int g = channel(this.green);
        int b = channel(this.blue);
        if (swatchX + 8 < x + width) {
            frame(graphics, swatchX, top + 82, x + width - swatchX, 20, 0xFFFFFFFF);
            if (r >= 0 && g >= 0 && b >= 0) {
                graphics.fill(swatchX + 1, top + 83, x + width - 1, top + 101, 0xFF000000 | (r << 16) | (g << 8) | b);
            } else {
                drawCut(graphics, "?", swatchX + 4, top + 88, x + width - swatchX - 6, ERROR);
            }
        }
        graphics.drawString(this.font, tr("box"), labelX, top + 112, TEXT);
        Line box = this.boxRefusal != null ? refusalLine(this.boxRefusal, this.boxFile, this.boxSha, tr("box_plain"))
                : switch (this.boxMode) {
            case WorldCollectionEdit.BOX_TEXTURE -> fileLine(this.boxFile, this.boxSha, tr("box_plain"));
            case WorldCollectionEdit.BOX_COLOR -> new Line(tr("box_colored", colorName(this.boxColor)), MUTED);
            default -> new Line(tr("box_plain"), MUTED);
        };
        drawCut(graphics, box.text(), x, top + 130, width, box.color());
        graphics.drawString(this.font, tr("logo_label"), labelX, top + 148, TEXT);
        Line logo = refusalLine(this.logoRefusal, this.logoFile, this.logoSha, tr("logo_none"));
        drawCut(graphics, logo.text(), x, top + 166, width, logo.color());
        if (!compact()) {
            drawPickerHelp(graphics, leftX(), pathY() + 24, leftWidth(), linesBetween(pathY() + 24, statusY() - 2));
        }

        drawFigures(graphics, record, partialTicks);
    }

    private void drawFigures(GuiGraphics graphics, WorldCollectionRecord record, float partialTicks) {
        int x = rightX();
        int width = rightWidth();
        long retired = record.figures().stream().filter(figure -> !figure.enabled()).count();
        int perPage = TILES_PER_ROW * TILE_ROWS;
        int pages = Math.max(1, (record.figures().size() + perPage - 1) / perPage);
        drawCut(graphics, tr("figures_header", record.figures().size(), retired), x, this.panelY + 34, width, TEXT);
        String page = tr("figures_page", this.figurePage + 1, pages);
        graphics.drawString(this.font, page, x + width - this.font.width(page), this.panelY + 34, MUTED);

        if (this.visibleFigures.isEmpty()) {
            drawCut(graphics, tr("no_figures"), x, tileY(0) + 6, width, MUTED);
        }
        FigureCollection collection = CollectionRegistry.getCollection(this.collectionId).orElse(null);
        int size = tileSize();
        for (int i = 0; i < this.visibleFigures.size(); i++) {
            WorldCollectionRecord.Figure figure = this.visibleFigures.get(i);
            int tileX = tileX(i);
            int tileY = tileY(i);
            if (figure.id().equals(this.selectedFigure)) {
                frame(graphics, tileX - 1, tileY - 1, size + 2, size + 2, TILE_SELECTED);
            }
            FigureDefinition definition = collection != null ? collection.getFigure(figure.id()).orElse(null) : null;
            if (definition != null && this.previewEntry != null) {
                this.previewEntry.renderPreview(graphics, definition, tileX + 2, tileY + 2, size - 4, partialTicks);
            }
            if (!figure.enabled()) {
                drawCut(graphics, tr("tag_retired"), tileX + 3, tileY + size - 11, size - 6, ERROR);
            } else if (hasFileProblem(figure)) {
                drawCut(graphics, "!", tileX + size - 7, tileY + 3, 6, ERROR);
            }
        }

        int infoY = figureInfoY();
        WorldCollectionRecord.Figure selected = selected();
        if (selected == null) {
            drawCut(graphics, tr("select_figure"), x, infoY, width, MUTED);
            return;
        }
        drawCut(graphics, selected.name() + " (" + selected.id() + ")", x, infoY, width, selected.enabled() ? TEXT : MUTED);
        String kind = tr(selected.kind() == WorldCollectionRecord.Kind.SKIN ? "kind_skin" : "kind_model");
        String details = tr("figure_details", kind, scaleText(selected.scale()))
                + (selected.enabled() ? "" : " - " + tr("tag_retired"));
        int detailsColor = MUTED;
        if (hasFileProblem(selected)) {
            details = tr("figure_files_problem");
            detailsColor = ERROR;
        }
        drawCut(graphics, details, x, infoY + 11, width, detailsColor);
    }

    /** Whether a file the figure names is lost on the server or could not be loaded here. */
    static boolean hasFileProblem(WorldCollectionRecord.Figure figure) {
        for (String sha : new String[] {figure.texture(), figure.model(), figure.animation()}) {
            if (sha != null && (ClientWorldCollections.isMissingOnServer(sha) || ClientWorldCollections.failedToLoad(sha))) {
                return true;
            }
        }
        return false;
    }

    /** A figure size for people, such as "1.25x". */
    static String scaleText(float scale) {
        String text = String.format(Locale.ROOT, "%.2f", scale);
        // 1.00 reads 1.0 and 0.50 reads 0.5; 1.25 and 0.75 keep both decimals
        if (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "x";
    }
}
