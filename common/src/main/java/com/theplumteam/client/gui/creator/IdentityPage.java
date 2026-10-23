package com.theplumteam.client.gui.creator;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.DominantColour;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiBoxView;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCheckbox;
import com.theplumteam.client.gui.creator.ui.UiColourPicker;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiRangeSlider;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSegmented;
import com.theplumteam.client.gui.creator.ui.UiSwatches;
import com.theplumteam.client.gui.creator.ui.UiXYPad;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.util.LinkOpening;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.BoxMode;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's first tab, "Identity & box": the collection's name, author and author link, its
 * background colour, its box and its logo, beside a live view of the result.
 *
 * <p>The view on the left shows the box with the collection's first figure in it, the box
 * standing in the world, or the claw machine's screen; it is drawn from the editor's preview of
 * the draft, so every change shows before it is saved, files not yet uploaded included.
 *
 * <p>On the right, the text fields stay at the top and the other sections scroll under them,
 * each heading in the menus' pixel font and marked with an amber dot while it holds unsaved
 * changes. The background colour
 * comes from the swatches, a custom colour or the logo's main colour. The box is a plain box of
 * one of the sixteen box colours, the collection's own 64×64 image, or the mod's usual box;
 * "Download box template" writes a template with its faces marked into the game folder and
 * shows that folder. The logo can be moved and resized on the box.
 */
final class IdentityPage implements EditorPage {
    private static final ResourceLocation TEMPLATE =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/gui/creator/box_template.png");
    private static final ResourceLocation TEMPLATE_GUIDE =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/gui/creator/box_template_guide.png");
    // The view takes the mockup's 516 of 1232 units, a little less on a narrow panel
    private static final int MAX_VIEW_WIDTH = 258;
    private static final int MIN_VIEW_WIDTH = 140;
    private static final int COLUMN_GAP = 14;
    private static final int LABEL = 11;
    // A section's heading, in the pixel font, whose capitals are taller than Minecraft's
    private static final int HEADING = 13;
    private static final int FIELD = 20;
    private static final int SECTION_GAP = 12;
    private static final int PAD_SIDE = 46;
    private static final int PAD_WIDTH = 84;
    // Eight box tiles of 20 with their gaps of 5 and the selection ring's margin of 3 each side
    private static final int BOX_SWATCHES_WIDTH = 8 * UiSwatches.BOX_TILE + 7 * 5 + 2 * 3;
    private static final int LABEL_COLOUR = 0xFFE6E6E6;
    private static final int HELP = 0xFFA8A8A8;
    private static final BoxMode[] BOX_MODES = {BoxMode.COLOR, BoxMode.TEXTURE, BoxMode.NONE};

    private final CreatorEditorScreen editor;
    private final UiBoxView view = new UiBoxView();
    private final UiScrollPane sections = new UiScrollPane();
    private final UiCheckbox playerName;
    private final Label colourHeading;
    private final Label colourHelp;
    private final UiSwatches colours;
    private final UiButton fromLogo;
    private final UiButton customColour;
    private final Label boxHeading;
    private final UiSegmented boxModes;
    private final UiSwatches boxColours;
    private final UiDropZone boxZone;
    private final UiButton templateLink;
    private final Label templateNote;
    private final Label boxNote;
    private final Label logoHeading;
    private final UiDropZone logoZone;
    private final Label sizeLabel;
    private final UiRangeSlider logoSize;
    private final Label positionLabel;
    private final UiXYPad logoPosition;
    @Nullable
    private EditBox authorBox;
    // The files the zones show, so a rebuild leaves a refusal message in place
    @Nullable
    private String boxShown;
    @Nullable
    private String logoShown;
    private int swatchesHeight;
    private int swatchesWidth;

    // Where build() put what drawContents() draws
    private int fieldsX;
    private int fieldsWidth;
    private int half;
    private int nameLabelY = -1;
    private int nameStatusY;
    private int linkLabelY;

    IdentityPage(CreatorEditorScreen editor) {
        this.editor = editor;
        CreatorDraft draft = editor.draft();
        this.view.setCollection(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT));
        this.playerName = new UiCheckbox(tr("identity.use_player_name"), isPlayerName(draft.author()),
                this::usePlayerName);

        this.colourHeading = new Label(() -> tr("wizard.colour"), LABEL_COLOUR,
                () -> editor.isChanged(CreatorDraft.COLOUR),
                () -> tr("wizard.colour_value", UiSwatches.nameOf(draft.rgb()), UiColourPicker.hex(draft.rgb())));
        this.colourHelp = new Label(() -> tr("identity.colour_help"), HELP, null, null);
        this.colours = UiSwatches.colours(draft.rgb(), this::setColour);
        this.fromLogo = new UiButton(tr("wizard.from_logo"), UiButton.Style.LINK, this::colourFromLogo);
        this.customColour = new UiButton(tr("wizard.custom"), UiButton.Style.LINK,
                () -> UiColourPicker.open(editor, draft.rgb(), this::setColour));

        this.boxHeading = new Label(() -> tr("identity.box"), LABEL_COLOUR, () -> editor.isChanged(CreatorDraft.BOX),
                () -> {
                    Component problem = editor.boxProblem();
                    return problem != null ? problem : Component.empty();
                }, Theme.RED);
        this.boxModes = new UiSegmented(0, index -> setBoxMode(BOX_MODES[index]), tr("identity.box.colour"),
                tr("identity.box.image"), tr("identity.box.none"));
        this.boxColours = UiSwatches.boxColours(null, colour -> {
            draft.setBoxColor(colour.getSerializedName());
            editor.changed();
        });
        this.boxZone = UiDropZone.single(editor, FilePicker.Slot.BOX, file -> {
            this.boxShown = file.sha();
            draft.setBoxTexture(FileRef.of(file));
            editor.changed();
        }).help(tr("identity.box_help", WorldContentLimits.BOX_SIZE));
        this.templateLink = new UiButton(tr("identity.template"), UiButton.Style.LINK, this::exportTemplate);
        this.templateNote = new Label(() -> tr("identity.template_note"), HELP, null, null);
        this.boxNote = new Label(() -> tr("identity.box_default"), HELP, null, null);

        this.logoHeading = new Label(() -> tr("identity.logo"), LABEL_COLOUR,
                () -> editor.isChanged(CreatorDraft.LOGO) || editor.isChanged(CreatorDraft.LOGO_LAYOUT), null);
        this.logoZone = UiDropZone.single(editor, FilePicker.Slot.LOGO, tr("wizard.logo"), file -> {
            this.logoShown = file.sha();
            draft.setLogo(FileRef.of(file));
            editor.changed();
        }).help(tr("wizard.logo_help", WorldContentLimits.LOGO_MIN_SIZE, WorldContentLimits.LOGO_MAX_SIZE))
                .onRemove(() -> {
                    this.logoShown = null;
                    draft.setLogo(null);
                    editor.changed();
                });
        this.sizeLabel = new Label(() -> tr("identity.logo_size"), HELP, null, null);
        LogoLayout layout = draft.logoLayout();
        this.logoSize = new UiRangeSlider(LogoLayout.MIN_SCALE_PCT, LogoLayout.MAX_SCALE_PCT, 5,
                LogoLayout.DEFAULT.scalePct(), layout.scalePct(), value -> tr("identity.percent", value),
                this::logoResized);
        this.positionLabel = new Label(() -> tr("identity.logo_position"), HELP, null, null);
        this.logoPosition = new UiXYPad(LogoLayout.MAX_OFFSET, layout.offsetX(), layout.offsetY(),
                (offsetX, offsetY) -> placeLogo(this.logoSize.value(), offsetX, offsetY));
    }

    // Building

    @Override
    public void build(UiNode.Rect area) {
        CreatorDraft draft = this.editor.draft();
        int viewWidth = Math.min(MAX_VIEW_WIDTH, Math.max(MIN_VIEW_WIDTH, (area.width() - COLUMN_GAP) * 42 / 100));
        this.view.at(area.x(), area.y(), viewWidth, area.height());
        this.editor.add(this.view);

        // Name and author side by side, then the author link, fixed above the sections
        int x = area.x() + viewWidth + COLUMN_GAP;
        int width = Math.max(0, area.right() - x);
        this.fieldsX = x;
        this.fieldsWidth = width;
        this.half = (width - 8) / 2;
        int y = area.y();
        this.nameLabelY = y;
        this.editor.field("name", x, y + LABEL, this.half, WorldContentLimits.MAX_NAME_LENGTH, tr("wizard.name_hint"),
                draft.name(), value -> {
                    draft.setName(value);
                    this.editor.changed();
                });
        int authorX = x + this.half + 8;
        this.authorBox = this.editor.field("author", authorX, y + LABEL, width - this.half - 8,
                WorldContentLimits.MAX_AUTHOR_LENGTH, tr("wizard.author_hint"), draft.author(), value -> {
                    draft.setAuthor(value);
                    this.playerName.setChecked(isPlayerName(value));
                    this.editor.changed();
                });
        y += LABEL + FIELD + 3;
        this.nameStatusY = y + 1;
        this.playerName.setChecked(isPlayerName(draft.author()));
        this.playerName.at(authorX, y, Math.min(width - this.half - 8,
                UiCheckbox.widthFor(Minecraft.getInstance().font, tr("identity.use_player_name"))), UiCheckbox.BOX + 2);
        this.editor.add(this.playerName);
        y += UiCheckbox.BOX + 6;
        this.linkLabelY = y;
        this.editor.field("author_url", x, y + LABEL, width, WorldContentLimits.MAX_AUTHOR_URL_LENGTH,
                tr("identity.url_hint"), draft.authorUrl(), value -> {
                    draft.setAuthorUrl(value);
                    this.editor.changed();
                });
        y += LABEL + FIELD + 8;

        this.sections.clear();
        this.sections.at(x, y, width, Math.max(0, area.bottom() - y));
        layoutSections(x, y, this.sections.contentWidth());
        this.editor.add(this.sections);
    }

    private void layoutSections(int x, int top, int width) {
        CreatorDraft draft = this.editor.draft();
        Font font = Minecraft.getInstance().font;
        int y = top;

        // Background colour
        this.colourHeading.at(x, y, width, HEADING);
        y += HEADING;
        this.colourHelp.at(x, y, width, LABEL);
        y += LABEL;
        this.colours.select(draft.rgb());
        this.swatchesWidth = width;
        this.swatchesHeight = this.colours.heightFor(width);
        this.colours.at(x, y, width, this.swatchesHeight);
        y += this.swatchesHeight + 2;
        int fromLogoWidth = font.width(this.fromLogo.label());
        this.fromLogo.at(x, y, fromLogoWidth, 10);
        this.customColour.at(x + fromLogoWidth + 12, y, font.width(this.customColour.label()), 10);
        y += LABEL + SECTION_GAP;
        addAll(this.colourHeading, this.colourHelp, this.colours, this.fromLogo, this.customColour);

        // Box: one of three kinds, each with what it needs below the switch
        this.boxHeading.at(x, y, width, HEADING);
        y += HEADING + 1;
        this.boxModes.select(boxModeIndex(draft.boxMode()));
        this.boxModes.at(x, y, Math.min(width, this.boxModes.naturalWidth()), UiSegmented.HEIGHT);
        y += UiSegmented.HEIGHT + 5;
        addAll(this.boxHeading, this.boxModes);
        switch (draft.boxMode()) {
            case COLOR -> {
                this.boxColours.select(boxColour(draft.boxColor()));
                // Two rows of eight, as the mockup lays out the sixteen box colours
                int boxesWidth = Math.min(width, BOX_SWATCHES_WIDTH);
                int height = this.boxColours.heightFor(boxesWidth);
                this.boxColours.at(x, y, boxesWidth, height);
                this.sections.add(this.boxColours);
                y += height;
            }
            case TEXTURE -> {
                this.boxShown = showIn(this.boxZone, draft.boxTexture(), this.boxShown);
                this.boxZone.at(x, y, width, UiDropZone.HEIGHT);
                y += UiDropZone.HEIGHT + 4;
                int linkWidth = font.width(this.templateLink.label());
                this.templateLink.at(x, y, linkWidth, 10);
                this.templateNote.at(x + linkWidth + 6, y, Math.max(0, width - linkWidth - 6), 10);
                y += LABEL;
                addAll(this.boxZone, this.templateLink, this.templateNote);
            }
            case NONE -> {
                int lines = Draw.fit(font, tr("identity.box_default").getString(), width, 2).size();
                this.boxNote.at(x, y, width, lines * Theme.LINE_HEIGHT);
                this.sections.add(this.boxNote);
                y += lines * Theme.LINE_HEIGHT;
            }
        }
        y += SECTION_GAP;

        // Logo, then its size and place on the box
        this.logoHeading.at(x, y, width, HEADING);
        y += HEADING;
        this.logoShown = showIn(this.logoZone, draft.logo(), this.logoShown);
        this.logoZone.at(x, y, width, UiDropZone.HEIGHT);
        y += UiDropZone.HEIGHT + 6;
        int padX = x + width - PAD_WIDTH;
        int sliderWidth = Math.max(40, padX - 12 - x);
        this.sizeLabel.at(x, y, sliderWidth, LABEL);
        this.logoSize.at(x, y + LABEL + 2, sliderWidth, UiRangeSlider.HEIGHT);
        this.positionLabel.at(padX, y, PAD_WIDTH, LABEL);
        this.logoPosition.at(padX, y + LABEL, PAD_WIDTH, PAD_SIDE + Theme.LINE_HEIGHT + 2);
        addAll(this.logoHeading, this.logoZone, this.sizeLabel, this.logoSize, this.positionLabel, this.logoPosition);
    }

    private void addAll(UiNode... nodes) {
        for (UiNode node : nodes) {
            this.sections.add(node);
        }
    }

    /**
     * Shows the file a slot holds in its zone, unless the zone shows it already, and returns
     * the sha it shows. A file still on this computer shows its own name, a stored one a plain
     * label, and a file the server lost says so.
     */
    @Nullable
    private static String showIn(UiDropZone zone, @Nullable FileRef ref, @Nullable String shown) {
        String sha = ref != null ? ref.sha() : null;
        if (Objects.equals(sha, shown)) {
            return shown;
        }
        if (ref == null) {
            zone.showEmpty();
            return null;
        }
        if (ref.local() != null) {
            zone.showFile(ref.local());
            return sha;
        }
        BlobInfo info = ref.info();
        if (info == null) {
            zone.showEmpty();
            zone.showError(tr("file_unknown"));
            return sha;
        }
        zone.showFile(tr("identity.stored_file").getString(), sha, info);
        if (ClientWorldCollections.isMissingOnServer(sha)) {
            zone.showError(tr("file_missing"));
        }
        return sha;
    }

    @Override
    public void draftChanged() {
        CreatorDraft draft = this.editor.draft();
        Component noLogo = draft.logo() == null ? tr("wizard.no_logo") : null;
        this.fromLogo.disable(noLogo);
        this.logoSize.disable(noLogo);
        this.logoPosition.disable(noLogo);
        this.colours.select(draft.rgb());
        this.boxColours.select(boxColour(draft.boxColor()));
        this.playerName.setChecked(isPlayerName(draft.author()));
        // The view is too narrow for the machine's grid, which the review tab shows full size
        int figures = draft.figures().size();
        this.view.replica().setCompactNote(figures == 1 ? tr("identity.screen_note_one")
                : tr("identity.screen_note", figures));
        // A removed logo takes its placement with it
        LogoLayout layout = draft.logoLayout();
        this.logoSize.set(layout.scalePct());
        this.logoPosition.set(layout.offsetX(), layout.offsetY());
    }

    // Changes

    private void setColour(int rgb) {
        this.editor.draft().setRgb(rgb & 0xFFFFFF);
        this.colours.select(rgb & 0xFFFFFF);
        this.editor.changed();
        if (this.colours.heightFor(this.swatchesWidth) != this.swatchesHeight) {
            // A custom colour's own tile went onto a row of its own, or left it
            this.editor.rebuildPage();
        }
    }

    private void colourFromLogo() {
        FileRef logo = this.editor.draft().logo();
        if (logo == null) {
            return;
        }
        if (logo.local() != null) {
            DominantColour.ofPngLater(this.editor, logo.local().bytes(), this::takeColour);
        } else {
            // A stored logo is loaded already under its content-addressed id
            takeColour(DominantColour.ofTexture(WorldContentIds.texture(logo.sha())));
        }
    }

    private void takeColour(OptionalInt colour) {
        if (colour.isPresent()) {
            setColour(colour.getAsInt());
        } else {
            this.editor.status(tr("wizard.no_colour"), Theme.RED);
        }
    }

    private void setBoxMode(BoxMode mode) {
        this.editor.draft().setBoxMode(mode);
        this.editor.changed();
        // Each kind of box shows its own controls
        this.editor.rebuildPage();
    }

    private void logoResized(int scalePct) {
        placeLogo(scalePct, this.logoPosition.offsetX(), this.logoPosition.offsetY());
    }

    private void placeLogo(int scalePct, int offsetX, int offsetY) {
        if (this.editor.draft().logo() == null || !LogoLayout.isValid(scalePct, offsetX, offsetY)) {
            return;
        }
        this.editor.draft().setLogoLayout(new LogoLayout(scalePct, offsetX, offsetY));
        this.editor.changed();
    }

    private static boolean isPlayerName(String author) {
        return !author.isEmpty() && author.equals(playerName());
    }

    private static String playerName() {
        return CreatorHomeScreen.cut(Minecraft.getInstance().getUser().getName(), WorldContentLimits.MAX_AUTHOR_LENGTH);
    }

    private void usePlayerName(boolean use) {
        EditBox box = this.authorBox;
        String next = use ? playerName() : "";
        if (box != null) {
            // The field's responder sets the author and tells the editor
            box.setValue(next);
        } else {
            this.editor.draft().setAuthor(next);
            this.editor.changed();
        }
    }

    private static int boxModeIndex(BoxMode mode) {
        for (int i = 0; i < BOX_MODES.length; i++) {
            if (BOX_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    @Nullable
    private static PopBlockColor boxColour(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (PopBlockColor colour : PopBlockColor.values()) {
            if (colour.getSerializedName().equals(name)) {
                return colour;
            }
        }
        return null;
    }

    /**
     * Writes the box template and its labelled guide, both shipped with the mod, into
     * {@code blockpops/templates} in the game folder and shows that folder. Nothing else is
     * written, and only the game's resources are read: the mod's own images, or a resource
     * pack's replacements for them.
     */
    private void exportTemplate() {
        Minecraft minecraft = Minecraft.getInstance();
        Path folder = minecraft.gameDirectory.toPath().resolve("blockpops").resolve("templates");
        try {
            Files.createDirectories(folder);
            copyResource(minecraft, TEMPLATE, folder.resolve("box_template.png"));
            copyResource(minecraft, TEMPLATE_GUIDE, folder.resolve("box_template_guide.png"));
        } catch (IOException | RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Could not write the box template to {}", folder, e);
            this.editor.status(tr("identity.template_failed"), Theme.RED);
            return;
        }
        this.editor.toast(tr("identity.template_saved"));
        try {
            LinkOpening.openFolder(folder);
        } catch (RuntimeException e) {
            // The files are written; the toast says where, if the file manager cannot be opened
            BlockPopsMod.LOGGER.warn("Could not show the folder {}", folder, e);
        }
    }

    private static void copyResource(Minecraft minecraft, ResourceLocation id, Path target) throws IOException {
        try (InputStream in = minecraft.getResourceManager().getResource(id).orElseThrow().open()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Drawing

    @Override
    public void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.nameLabelY < 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        CreatorDraft draft = this.editor.draft();
        int x = this.fieldsX;

        // Name, with its length on the right of its column and whether it can be used under it
        int nameEnd = drawLabel(graphics, font, tr("name"), x, this.nameLabelY, this.editor.isChanged(CreatorDraft.NAME));
        String counter = draft.name().length() + "/" + WorldContentLimits.MAX_NAME_LENGTH;
        int counterX = x + this.half - font.width(counter);
        if (counterX > nameEnd + 4) {
            Draw.string(graphics, font, counter, counterX, this.nameLabelY,
                    draft.name().length() >= WorldContentLimits.MAX_NAME_LENGTH ? Theme.AMBER : HELP);
        }
        Component nameProblem = draft.name().isBlank() ? tr("wizard.name_empty")
                : !WorldCollectionRecord.isPlainText(draft.name()) ? tr("name_rule") : null;
        Draw.text(graphics, font, (nameProblem != null ? nameProblem : tr("wizard.name_ok")).getString(), x,
                this.nameStatusY, this.half, nameProblem != null ? Theme.RED : Theme.GREEN);

        // Author, saying when it cannot be used
        int authorX = x + this.half + 8;
        int authorEnd = drawLabel(graphics, font, tr("author"), authorX, this.nameLabelY,
                this.editor.isChanged(CreatorDraft.AUTHOR));
        if (!WorldCollectionRecord.isPlainText(draft.author())) {
            Draw.text(graphics, font, tr("wizard.author_rule").getString(), authorEnd + 6, this.nameLabelY,
                    Math.max(0, x + this.fieldsWidth - authorEnd - 6), Theme.RED);
        }

        // Author link, with why it cannot be used on the right of its label
        int linkEnd = drawLabel(graphics, font, tr("identity.url"), x, this.linkLabelY,
                this.editor.isChanged(CreatorDraft.AUTHOR_URL));
        if (!draft.authorUrl().isEmpty() && !WorldCollectionRecord.isValidAuthorUrl(draft.authorUrl())) {
            Draw.text(graphics, font, tr("identity.url_rule").getString(), linkEnd + 6, this.linkLabelY,
                    Math.max(0, x + this.fieldsWidth - linkEnd - 6), Theme.RED);
        }
    }

    // A field's label, with an amber dot after it while it holds unsaved changes; returns its end
    private static int drawLabel(GuiGraphics graphics, Font font, Component label, int x, int y, boolean changed) {
        Draw.string(graphics, font, label, x, y, LABEL_COLOUR);
        int end = x + font.width(label);
        if (changed) {
            Draw.dot(graphics, end + 5, y + 4, 4, Theme.AMBER);
            end += 9;
        }
        return end;
    }

    @Override
    public boolean takesFiles() {
        return true;
    }

    @Override
    public void close() {
        this.view.close();
    }

    /**
     * A line of text in the scrolling sections: a heading in the pixel font with an amber dot while
     * its section holds unsaved changes and a value on the right, or a grey note wrapped over its
     * height.
     */
    private static final class Label extends UiNode.Box {
        private final Supplier<Component> text;
        private final int colour;
        @Nullable
        private final BooleanSupplier changed;
        @Nullable
        private final Supplier<Component> value;
        private final int valueColour;

        Label(Supplier<Component> text, int colour, @Nullable BooleanSupplier changed,
              @Nullable Supplier<Component> value) {
            this(text, colour, changed, value, Theme.TEXT_SOFT);
        }

        Label(Supplier<Component> text, int colour, @Nullable BooleanSupplier changed,
              @Nullable Supplier<Component> value, int valueColour) {
            this.text = text;
            this.colour = colour;
            this.changed = changed;
            this.value = value;
            this.valueColour = valueColour;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int right = x + width;
            if (this.changed != null) {
                // A heading: the value on its right in Minecraft's font, level with the capitals
                int headingWidth = PixelFont.width(this.text.get(), PixelFont.crispScale(1.0f));
                int textY = y + (height - 8) / 2 + 1;
                if (this.value != null) {
                    // The heading keeps its room; the value is cut first
                    int room = Math.max(0, width - headingWidth - 20);
                    String shown = Draw.cut(font, this.value.get().getString(), room);
                    if (!shown.isEmpty()) {
                        int valueX = right - font.width(shown);
                        Draw.string(graphics, font, shown, valueX, textY, this.valueColour);
                        right = valueX - 8;
                    }
                }
                int end = x + Draw.heading(graphics, this.text.get(), x, y + height / 2.0f, Math.max(0, right - x - 9),
                        1.0f, this.colour);
                if (this.changed.getAsBoolean()) {
                    Draw.dot(graphics, end + 5, y + height / 2, 4, Theme.AMBER);
                }
                return;
            }
            Draw.lines(graphics, font, this.text.get().getString(), x, y, Math.max(0, right - x),
                    Math.max(1, height / Theme.LINE_HEIGHT), this.colour);
        }
    }
}
