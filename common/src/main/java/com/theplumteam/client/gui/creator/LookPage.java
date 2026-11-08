package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.DominantColour;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiBoxFrontEditor;
import com.theplumteam.client.gui.creator.ui.UiBoxView;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiColourPicker;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiPopover;
import com.theplumteam.client.gui.creator.ui.UiRangeSlider;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSectionHead;
import com.theplumteam.client.gui.creator.ui.UiSwatches;
import com.theplumteam.client.gui.creator.ui.UiThumbStrip;
import com.theplumteam.client.gui.creator.ui.UiTileRow;
import com.theplumteam.client.gui.creator.ui.UiTileRow.Tile;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.UiViewButton;
import com.theplumteam.client.gui.creator.ui.UiViewport;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.LinkOpening;
import com.theplumteam.client.renderer.LogoLayout;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.BoxMode;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModItems;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's "Look" tab: how the collection looks in the machines, all of it in view at once.
 * On the left a live view of the box holding the collection's first figure, drawn from the
 * editor's preview of the draft, so every change shows before it is saved; on the right the
 * collection's name, then three sections under thin lines, each head saying what it is set to:
 * <ul>
 *   <li><b>Background</b>, the panel behind the figures in the machine: the twelve colours, a
 *       colour of one's own and the logo's main colour, and under them a sample of the machine's
 *       panel in that colour with the collection's first figures on it;</li>
 *   <li><b>Box</b>: five box colours, then "Image" (the collection's own 64×64 picture, with the
 *       line that takes it and a link to the template), "Normal" (the mod's usual box) and "More",
 *       the other colours in a popover;</li>
 *   <li><b>Logo</b>: the logo's zone, and once there is a logo its size and "Position…", which
 *       turns the view into the front of the box, flat, with the logo to drag on it
 *       ({@link UiBoxFrontEditor}).</li>
 * </ul>
 * On a narrow panel the view is a band on top and each section one labelled row under the name,
 * the labels saying the values; the background's other colours are then in a popover too, and the
 * sample is left out. The column is laid out on the page while it fits; on a canvas too low for it
 * the sample goes first, then the column scrolls, never with a model in it.
 *
 * <p>Nothing here sends anything itself: every change goes into the draft, which the editor's
 * saver sends as it sends every change (a draft's, a moment after it settles; its files once the
 * operator moves on; a published collection's with Publish changes). Each click on a tile, each
 * file picked or taken away, each drag of the size or of the logo and "Reset" is one undo step.
 * A box image chosen as the kind with none yet, or a blank name, holds the collection's own
 * changes back, which the view, the box's head, the tab's dot and the status say.
 *
 * <p>The view's "View ⌄" menu shows the box alone or standing in the world, or opens the machine's
 * screen in a dialog ({@link MachineScreenDialog}); two icons spin the box and switch the night
 * light. Tab in the name moves on to the background's tiles; then the arrow keys move along a row,
 * Enter or Space picks, and Up, Down and Tab go from row to row, to the box image, the logo, its
 * size and "Position…".
 */
final class LookPage implements EditorPage {
    private static final ResourceLocation TEMPLATE =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/gui/creator/box_template.png");
    private static final ResourceLocation TEMPLATE_GUIDE =
            ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/gui/creator/box_template_guide.png");

    // The editor builds a page this far under the content panel's top
    private static final int PAGE_TOP = 12;
    // Wide: the view beside a column of this width at least, at most 258 wide
    private static final int COLUMN_WIDTH = 253;
    private static final int VIEW_GAP = 16;
    private static final int MIN_VIEW_WIDTH = 140;
    private static final int MAX_VIEW_WIDTH = 258;
    // Wide: where the name's label, field and problem go, and where the sections start, from the panel's top
    private static final int NAME_LABEL_MIDDLE = 15;
    private static final int COUNTER_TOP = 11;
    private static final int NAME_TOP = 20;
    private static final int STATUS_TOP = 43;
    private static final int SECTIONS_TOP = 55;
    // A section's head, and the room from the end of a section's content to the next head
    private static final int HEAD = UiSectionHead.HEIGHT;
    private static final int AFTER = 10;
    private static final int RING = UiTileRow.RING;
    // The tiles: background colours 13 wide on the full row and 14 on the short one, 4 apart; boxes 20, 5 apart
    private static final int BG_TILE_WIDE = 13;
    private static final int BG_TILE = UiSwatches.COLOUR_TILE;
    private static final int BG_GAP = 4;
    private static final int BOX_TILE = UiSwatches.BOX_TILE;
    private static final int BOX_GAP = 5;
    private static final int POPOVER_GAP = 5;
    // The full background row: twelve colours, the custom tile, a separator and the logo's slot
    private static final int FULL_FONDO_WIDTH = 2 * RING + 13 * BG_TILE_WIDE + 12 * BG_GAP
            + 2 * UiTileRow.SEPARATOR_SIDE + 1 + BG_TILE_WIDE;
    // The box row's colour places, at most
    private static final int BOX_PLACES = 5;
    // The machine sample: a panel this high, its thumbs this big, 6 apart, inside its edges
    private static final int SAMPLE_GAP = 4;
    private static final int SAMPLE_HEIGHT = 64;
    private static final int SAMPLE_PAD = 5;
    private static final int THUMB_WIDTH = 40;
    private static final int THUMB_HEIGHT = 54;
    private static final int THUMB_STEP = THUMB_WIDTH + 6;
    // A line of the form, the image's and the narrow logo's, and the gap above it
    private static final int LINE = UiDropZone.COMPACT_HEIGHT;
    private static final int LINE_GAP = 6;
    private static final int LOGO_EMPTY = UiDropZone.HEIGHT;
    private static final int LOGO_FILLED = 32;
    private static final int SIZE_GAP = 6;
    private static final int LINK_GAP = 8;
    private static final int WIDE_NOTE_LINES = 2;
    // Narrow: the labels' column, the rows and the gaps between them
    private static final int LABEL_WIDTH = 48;
    private static final int LABEL_GAP = 4;
    private static final int VIEW_TOP = 10;
    private static final int NAME_GAP = 5;
    private static final int ROW_GAP = 4;
    private static final int STATUS_ROOM = 10;
    private static final int MIN_NARROW_VIEW = 72;
    private static final int MAX_NARROW_VIEW = 120;
    private static final int NARROW_ROWS = NAME_GAP + 20 + ROW_GAP + (BG_TILE + 2 * RING) + ROW_GAP
            + (BOX_TILE + 2 * RING) + 2 + LINE + ROW_GAP + LINE;
    // The box's share of the view's height: wide it leaves the box room around it, narrow it fills the band
    private static final float WIDE_FILL = 0.58f / 1.08f;
    private static final float NARROW_FILL = 1.0f / 1.12f;
    // Popovers: the box colours in rows of eight, the background colours in rows of seven
    private static final int BOX_POP_PER_ROW = 8;
    private static final int BOX_POP_WIDTH = BOX_POP_PER_ROW * BOX_TILE + (BOX_POP_PER_ROW - 1) * POPOVER_GAP
            + 2 * RING + 2 * DialogFrame.PADDING;
    private static final int BG_POP_PER_ROW = 7;
    private static final int BG_POP_WIDTH = BG_POP_PER_ROW * BG_TILE + (BG_POP_PER_ROW - 1) * POPOVER_GAP
            + 2 * RING + 2 * DialogFrame.PADDING;
    private static final int POPOVER_INSET = 6;
    // A scrolling column reaches this far into the panel's padding, where its bar goes
    private static final int COLUMN_BAR = 8;
    private static final int POPOVER_MIN_WIDTH = 120;
    // A stored logo's colour is asked again this long after an answer that never came
    private static final long ASK_AGAIN_MS = 1_000;

    // The short background row's colours, by index in UiSwatches.BACKGROUNDS: cream, sky, mint,
    // lavender, pink, night, forest and orange; when fewer fit they go in DROP_ORDER, so orange stays
    private static final int[] SHORT_BACKGROUNDS = {0, 1, 2, 3, 5, 7, 8, 10};
    private static final int[] DROP_ORDER = {3, 8, 1, 2, 7, 0, 5};
    // The box row's colours, and the order the popover starts with
    private static final PopBlockColor[] BOX_COLOURS = {PopBlockColor.PURPLE, PopBlockColor.RED, PopBlockColor.BLUE,
            PopBlockColor.GREEN, PopBlockColor.YELLOW};

    // Tile ids
    private static final String BG_CUSTOM = "bg/custom";
    private static final String BG_LOGO = "bg/logo";
    private static final String BG_MORE = "bg/more";
    private static final String BOX_IMAGE = "box/image";
    private static final String BOX_NORMAL = "box/normal";
    private static final String BOX_MORE = "box/more";

    private final CreatorEditorScreen editor;
    private final String previewId;
    private final UiBoxView view = new UiBoxView();
    private final UiViewButton viewButton;
    private final UiBoxFrontEditor front;
    private final Column column = new Column();
    private final UiSectionHead backgroundHead;
    private final UiSectionHead boxHead;
    private final UiSectionHead logoHead;
    private final UiSectionHead nameLabel;
    private final UiSectionHead backgroundLabel;
    private final UiSectionHead boxLabel;
    private final UiSectionHead logoLabel;
    private final UiTileRow backgrounds;
    private final UiTileRow boxes;
    private final UiTileRow popoverBackgrounds;
    private final UiTileRow popoverBoxes;
    private final UiDropZone boxZone;
    private final UiButton templateLink;
    private final Note normalNote = new Note(Theme.MUTED);
    // The logo's zone: empty and wide, filled and wide, and the narrow one-line zone
    private final UiDropZone logoEmpty;
    private final UiDropZone logoFull;
    private final UiDropZone logoLine;
    private final Note sizeLabel = new Note(Theme.MUTED);
    private final UiRangeSlider logoSize;
    private final UiButton positionLink;
    private final Sample sample = new Sample();
    private final UiThumbStrip<FigureDefinition> strip;
    private final UiPopover boxPopover;
    private final UiPopover backgroundPopover;
    private final Note backgroundHint = new Note(Theme.FAINT);
    private final KeyboardRing ring = new KeyboardRing();
    private final Map<PopBlockColor, ItemStack> boxItems = new EnumMap<>(PopBlockColor.class);
    private final ItemStack previewBox;

    // How build() laid the page out
    private boolean narrow;
    private boolean inColumn;
    private boolean sampleShown;
    private int sampleWidth;
    private boolean fullBackgrounds;
    private int[] shortBackgrounds = SHORT_BACKGROUNDS;
    private int boxPlaces = BOX_PLACES;
    private boolean boxCaptions;
    private boolean built;
    private String builtLayout = "";
    @Nullable
    private EditBox nameBox;
    // Where drawContents() draws the name's label, its count and its problem
    private int nameLabelX;
    private int nameLabelWidth;
    private int nameLabelMiddle;
    private int counterY;
    private int statusX;
    private int statusY;
    private int statusWidth;

    // The files the zones show, so a rebuild leaves a refusal message in place
    @Nullable
    private String boxShown;
    @Nullable
    private String logoEmptyShown;
    @Nullable
    private String logoFullShown;
    @Nullable
    private String logoLineShown;

    // The logo's main colour per file, once known: a colour, or none for an image without one
    private final Map<String, OptionalInt> logoColours = new HashMap<>();
    // A logo picked on this computer whose colour is being worked out, and since when
    @Nullable
    private String askedSha;
    private long askedAt;

    // The controls the keyboard moves between, top to bottom, and the one a key moved it to
    private final List<UiNode> controls = new ArrayList<>();
    @Nullable
    private UiNode keyed;
    @Nullable
    private MachineScreenDialog machineScreen;
    // The view takes the keyboard on the tick after Position… opened the front, once the click that
    // opened it has given the keyboard to what it clicked
    private boolean viewTakesKeyboard;

    LookPage(CreatorEditorScreen editor) {
        this.editor = editor;
        this.previewId = CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT);
        this.previewBox = UiViewport.boxStack(this.previewId, null, false);
        this.view.setCollection(this.previewId);
        this.view.stageBackground(true);
        this.viewButton = new UiViewButton(tr("preview.view"), this::openViewMenu);
        this.front = new UiBoxFrontEditor(new FrontModel());
        this.view.frontEditor(this.front);

        this.backgroundHead = new UiSectionHead(tr("look.background"), this::backgroundValue).hint(tr("look.bg_hint"));
        this.boxHead = new UiSectionHead(tr("identity.box"), this::boxValue)
                .valueColour(() -> editor.boxProblem() != null ? Theme.AMBER : Theme.FAINT);
        this.logoHead = new UiSectionHead(tr("identity.logo"), this::logoValue);
        this.nameLabel = new UiSectionHead(tr("name"), this::counter).stacked(true)
                .valueColour(() -> editor.draft().name().length() >= WorldContentLimits.MAX_NAME_LENGTH ? Theme.AMBER
                        : Theme.FAINT);
        this.backgroundLabel = new UiSectionHead(tr("look.background"), this::backgroundShort).stacked(true)
                .hint(tr("look.bg_hint"));
        this.boxLabel = new UiSectionHead(tr("identity.box"), this::boxShort).stacked(true);
        this.logoLabel = new UiSectionHead(tr("identity.logo"), this::logoShort).stacked(true);

        this.backgrounds = new UiTileRow(BG_TILE, BG_GAP, this::backgroundTiles);
        this.boxes = new UiTileRow(BOX_TILE, BOX_GAP, this::boxTiles);
        this.popoverBackgrounds = new UiTileRow(BG_TILE, POPOVER_GAP, this::popoverBackgroundTiles)
                .wrap(BG_POP_PER_ROW);
        this.popoverBoxes = new UiTileRow(BOX_TILE, POPOVER_GAP, this::popoverBoxTiles).wrap(BOX_POP_PER_ROW);

        this.boxZone = UiDropZone.single(editor, FilePicker.Slot.BOX, this::boxPicked)
                .line(this::missingImage, this::boxThumbnail);
        this.templateLink = new TemplateLink(this::exportTemplate);

        this.logoEmpty = UiDropZone.single(editor, FilePicker.Slot.LOGO, tr("look.logo_add"), this::logoPicked)
                .help(tr("wizard.logo_help", WorldContentLimits.LOGO_MIN_SIZE, WorldContentLimits.LOGO_MAX_SIZE));
        this.logoFull = UiDropZone.single(editor, FilePicker.Slot.LOGO, this::logoPicked).onRemove(this::removeLogo)
                .showLabel(false);
        this.logoLine = UiDropZone.single(editor, FilePicker.Slot.LOGO, this::logoPicked).onRemove(this::removeLogo)
                .line(() -> false, this::logoThumbnail).help(tr("look.logo_drop_short"))
                .chips(UiDropZone.Chips.FAILED);
        this.sizeLabel.text = tr("look.logo_size");
        WorldCollectionRecord.LogoLayout layout = editor.draft().logoLayout();
        this.logoSize = new SizeSlider(layout.scalePct());
        this.positionLink = new UiButton(tr("look.position"), UiButton.Style.LINK, this::togglePosition);
        this.positionLink.setTooltip(tr("look.position_tip"));

        this.strip = new UiThumbStrip<FigureDefinition>(THUMB_WIDTH, THUMB_HEIGHT,
                figure -> new UiThumbStrip.Look(figure.getId(), figure.getName(), true, false, List.of(),
                        List.of(Component.literal(figure.getName()), tr("look.bg_hint"))))
                .collection(() -> CollectionRegistry.getCollection(this.previewId).orElse(null))
                .colour(() -> 0xFF000000 | editor.draft().rgb())
                .keys(FigureDefinition::getId)
                .selectable(false)
                .rank(SidebarScreen.ModelBudget.Rank.SHOWCASE);

        this.boxPopover = new UiPopover(tr("look.box_colours"), () -> onScreen(this.boxes.cellOf(BOX_MORE)))
                .accent(DialogFrame.Accent.SETTINGS, PixelIcon.PALETTE)
                .width(BOX_POP_WIDTH)
                .within(editor::contentPanel)
                .content(popover -> popover.node(this.popoverBoxes, this.popoverBoxes.heightFor(
                        popoverBoxTiles().size(), popoverInner(BOX_POP_WIDTH))));
        this.backgroundHint.text = tr("look.bg_hint");
        this.backgroundHint.maxLines = 2;
        this.backgroundPopover = new UiPopover(tr("wizard.colour"), () -> onScreen(this.backgrounds.cellOf(BG_MORE)))
                .accent(DialogFrame.Accent.SETTINGS, PixelIcon.PALETTE)
                .width(BG_POP_WIDTH)
                .within(editor::contentPanel)
                .content(popover -> {
                    int inner = popoverInner(BG_POP_WIDTH);
                    popover.node(this.popoverBackgrounds, this.popoverBackgrounds.heightFor(
                            popoverBackgroundTiles().size(), inner));
                    popover.node(this.backgroundHint, this.backgroundHint.height(inner) + 2);
                });
    }

    private CreatorDraft draft() {
        return this.editor.draft();
    }

    // Building

    @Override
    public void build(UiNode.Rect area) {
        int top = area.y() - PAGE_TOP;
        int bottom = area.bottom();
        this.narrow = this.editor.narrow();
        this.column.clear();
        this.controls.clear();
        this.front.narrow(this.narrow);
        if (draft().logo() == null) {
            this.front.close();
        }
        this.view.corners(this.viewButton, this.narrow).boxFill(this.narrow ? NARROW_FILL : WIDE_FILL)
                .ghost(this::missingImage, this.narrow ? 2.0f : 4.0f, this.narrow ? tr("look.box_missing") : null);
        if (this.narrow) {
            buildNarrow(top, bottom);
        } else {
            buildWide(top, bottom);
        }
        this.editor.add(this.view);
        // The view ranks first in the screen's budget, as the home's big preview does
        this.editor.budgetNode((SidebarScreen.Budgeted) budget -> budget.request(
                SidebarScreen.ModelBudget.Rank.PREVIEW, 1, granted -> { }));
        if (this.inColumn) {
            this.editor.add(this.column);
        }
        place(this.ring);
        this.builtLayout = layoutKey();
        this.built = true;
        // A popover whose tile went with the old layout closes
        if (this.boxPopover.isOpen() && this.boxes.cellOf(BOX_MORE).width() == 0) {
            this.boxPopover.close();
        }
        if (this.backgroundPopover.isOpen() && this.backgrounds.cellOf(BG_MORE).width() == 0) {
            this.backgroundPopover.close();
        }
    }

    /**
     * The wide page: the view on the left, as high as the page, and the column on its right, the
     * name fixed at its top and the three sections under it, laid out on the page while their
     * largest case fits, without the sample when only that fits, else in a scrolling column.
     */
    private void buildWide(int top, int bottom) {
        Font font = Minecraft.getInstance().font;
        CreatorDraft draft = draft();
        int left = this.editor.left();
        int viewWidth = SidebarScreen.clamp(this.editor.inner() - VIEW_GAP - COLUMN_WIDTH, MIN_VIEW_WIDTH,
                MAX_VIEW_WIDTH);
        this.view.at(left, top + PAGE_TOP, viewWidth, Math.max(0, bottom - (top + PAGE_TOP)));
        int x = left + viewWidth + VIEW_GAP;
        int width = Math.max(0, this.editor.right() - x);
        this.nameLabelX = x;
        this.nameLabelWidth = width;
        this.nameLabelMiddle = top + NAME_LABEL_MIDDLE;
        this.counterY = top + COUNTER_TOP;
        this.nameBox = this.editor.field("name", x, top + NAME_TOP, width, WorldContentLimits.MAX_NAME_LENGTH,
                tr("wizard.name_hint"), draft.name(), this::nameTyped);
        this.statusX = x;
        this.statusY = top + STATUS_TOP;
        this.statusWidth = width;

        // What fits on a row, worked out before the heights, which follow from it
        this.fullBackgrounds = width >= FULL_FONDO_WIDTH;
        int backgroundTile = this.fullBackgrounds ? BG_TILE_WIDE : BG_TILE;
        this.shortBackgrounds = shortBackgrounds(width);
        fitBoxes(width, true);

        int sectionsTop = top + SECTIONS_TOP;
        int backgroundBlock = HEAD + backgroundTile + RING + AFTER;
        int sampleBlock = SAMPLE_GAP + SAMPLE_HEIGHT;
        int boxBlock = HEAD + BOX_TILE + (this.boxCaptions ? UiTileRow.CAPTION_HEIGHT : RING)
                + Math.max(LINE_GAP + LINE, LINE_GAP + WIDE_NOTE_LINES * Theme.LINE_HEIGHT - 3) + AFTER;
        int logoBlock = HEAD + Math.max(LOGO_EMPTY, LOGO_FILLED + SIZE_GAP + UiRangeSlider.HEIGHT);
        int worst = backgroundBlock + sampleBlock + boxBlock + logoBlock;
        this.sampleShown = sectionsTop + worst <= bottom;
        this.inColumn = !this.sampleShown && sectionsTop + worst - sampleBlock > bottom;
        if (this.inColumn) {
            // The bar, should the column scroll, sits in the panel's padding
            this.column.at(x, sectionsTop, width + COLUMN_BAR, Math.max(0, bottom - sectionsTop));
        }

        int y = sectionsTop;
        // Background: the tiles, then the machine's panel in that colour under them
        place(this.backgroundHead.stacked(false).at(x, y, width, HEAD));
        y += HEAD;
        this.backgrounds.tile(backgroundTile).captions(false);
        place(this.backgrounds.at(x, y - RING, width, backgroundTile + 2 * RING));
        this.controls.add(this.backgrounds);
        y += backgroundTile + RING;
        if (this.sampleShown) {
            y += SAMPLE_GAP;
            buildSample(x, y, width);
            y += SAMPLE_HEIGHT;
        }
        y += AFTER;

        // Box: the colours, Image, Normal and More, then what the kind needs
        place(this.boxHead.stacked(false).at(x, y, width, HEAD));
        y += HEAD;
        this.boxes.captions(this.boxCaptions);
        int boxRowHeight = BOX_TILE + 2 * RING + (this.boxCaptions ? UiTileRow.CAPTION_HEIGHT : 0);
        place(this.boxes.at(x, y - RING, width, boxRowHeight));
        this.controls.add(this.boxes);
        y += BOX_TILE + (this.boxCaptions ? UiTileRow.CAPTION_HEIGHT : RING);
        y = boxExtras(font, x, y + LINE_GAP, width, false, y);
        y += AFTER;

        // Logo: the zone, and once there is a logo its size and Position…
        place(this.logoHead.stacked(false).at(x, y, width, HEAD));
        y += HEAD;
        if (draft.logo() == null) {
            this.logoEmptyShown = showIn(this.logoEmpty, null, this.logoEmptyShown);
            place(this.logoEmpty.at(x, y, width, LOGO_EMPTY));
            this.controls.add(this.logoEmpty);
            return;
        }
        this.logoFullShown = showIn(this.logoFull, draft.logo(), this.logoFullShown);
        place(this.logoFull.at(x, y, width, LOGO_FILLED));
        this.controls.add(this.logoFull);
        y += LOGO_FILLED + SIZE_GAP;
        int linkWidth = font.width(this.positionLink.label());
        int labelWidth = font.width(this.sizeLabel.text);
        place(this.sizeLabel.at(x, y + 2, labelWidth, Theme.LINE_HEIGHT));
        place(this.logoSize.at(x + labelWidth + 6, y, Math.max(40, width - labelWidth - 6 - 14 - linkWidth),
                UiRangeSlider.HEIGHT));
        place(this.positionLink.at(x + width - linkWidth, y + 1, linkWidth, 10));
        this.controls.add(this.logoSize);
        this.controls.add(this.positionLink);
    }

    // The machine's panel in the background colour, with as many whole figures as fit, centred
    private void buildSample(int x, int y, int width) {
        this.sampleWidth = width;
        List<FigureDefinition> figures = sampleFigures();
        int count = sampleCount(figures);
        place(this.sample.at(x, y, width, SAMPLE_HEIGHT));
        this.strip.setItems(figures.subList(0, count));
        if (count > 0) {
            int stripWidth = count * THUMB_STEP - (THUMB_STEP - THUMB_WIDTH);
            place(this.strip.at(x + (width - stripWidth) / 2, y + SAMPLE_PAD, stripWidth, THUMB_HEIGHT));
            this.editor.budgetNode(this.strip);
        }
    }

    /**
     * What the box's kind needs under its row, from {@code y}: the image's line with the
     * template's link on its right, or the note of the mod's usual box.
     *
     * @return where the section's content ends, or {@code none} for a kind that needs nothing
     */
    private int boxExtras(Font font, int x, int y, int width, boolean narrowRow, int none) {
        CreatorDraft draft = draft();
        switch (draft.boxMode()) {
            case TEXTURE -> {
                this.boxShown = showIn(this.boxZone, draft.boxTexture(), this.boxShown);
                this.boxZone.help(tr(narrowRow ? "look.box_drop_short" : "look.box_drop", WorldContentLimits.BOX_SIZE));
                this.boxZone.chips(narrowRow ? UiDropZone.Chips.SIZE_AND_FAILED : UiDropZone.Chips.ALL);
                int linkWidth = font.width(this.templateLink.label());
                place(this.boxZone.at(x, y, Math.max(40, width - linkWidth - LINK_GAP), LINE));
                place(this.templateLink.at(x + width - linkWidth, y + (LINE - 8) / 2 - 1, linkWidth, 10));
                this.controls.add(this.boxZone);
                return y + LINE;
            }
            case NONE -> {
                this.normalNote.text = tr(narrowRow ? "look.box_normal_note_short" : "look.box_normal_note");
                this.normalNote.maxLines = narrowRow ? 1 : WIDE_NOTE_LINES;
                int lines = this.normalNote.lines(width);
                place(this.normalNote.at(x, y, width, lines * Theme.LINE_HEIGHT));
                return y + lines * Theme.LINE_HEIGHT - 3;
            }
            default -> {
                return none;
            }
        }
    }

    /**
     * The narrow page: the view as a band on top, then one row per section under the name, each
     * with its label and value in a column on its left; the rows go in a column that scrolls only
     * on a canvas smaller than Minecraft's default window gives.
     */
    private void buildNarrow(int top, int bottom) {
        Font font = Minecraft.getInstance().font;
        CreatorDraft draft = draft();
        int left = this.editor.left();
        int inner = this.editor.inner();
        boolean problem = PublishCheck.nameProblem(draft) != null;
        int viewTop = top + VIEW_TOP;
        int rows = NARROW_ROWS + (problem ? STATUS_ROOM : 0);
        int viewHeight = SidebarScreen.clamp(bottom - viewTop - rows, MIN_NARROW_VIEW, MAX_NARROW_VIEW);
        this.view.at(left, viewTop, inner, viewHeight);
        int x = left + LABEL_WIDTH + LABEL_GAP;
        int width = Math.max(0, inner - LABEL_WIDTH - LABEL_GAP);
        int nameY = viewTop + viewHeight + NAME_GAP;
        this.nameBox = this.editor.field("name", x, nameY, width, WorldContentLimits.MAX_NAME_LENGTH,
                tr("wizard.name_hint"), draft.name(), this::nameTyped);
        this.editor.add(this.nameLabel.at(left, nameY, LABEL_WIDTH, 20));
        this.statusX = x;
        this.statusY = nameY + 22;
        this.statusWidth = width;
        this.nameLabelWidth = 0;

        this.fullBackgrounds = false;
        this.shortBackgrounds = shortBackgrounds(width);
        fitBoxes(width, false);
        this.sampleShown = false;
        this.inColumn = true;
        int y = nameY + 20 + ROW_GAP + (problem ? STATUS_ROOM : 0);
        this.column.at(left, y, inner + COLUMN_BAR, Math.max(0, bottom - y));

        // Background and box: a row each, the rings inside the row
        this.backgrounds.tile(BG_TILE).captions(false);
        place(this.backgroundLabel.at(left, y, LABEL_WIDTH, BG_TILE + 2 * RING));
        place(this.backgrounds.at(x, y, width, BG_TILE + 2 * RING));
        this.controls.add(this.backgrounds);
        y += BG_TILE + 2 * RING + ROW_GAP;
        this.boxes.captions(false);
        place(this.boxLabel.at(left, y, LABEL_WIDTH, BOX_TILE + 2 * RING));
        place(this.boxes.at(x, y, width, BOX_TILE + 2 * RING));
        this.controls.add(this.boxes);
        y += BOX_TILE + 2 * RING;
        y = boxExtras(font, x, y + 2, width, true, y) + ROW_GAP;

        // Logo: its line and Position…, or while the front is shown, its size
        place(this.logoLabel.at(left, y, LABEL_WIDTH, LINE));
        if (draft.logo() != null && this.front.isOpen()) {
            int labelWidth = font.width(this.sizeLabel.text);
            place(this.sizeLabel.at(x, y + 4, labelWidth, Theme.LINE_HEIGHT));
            place(this.logoSize.at(x + labelWidth + 6, y + 2, Math.max(40, width - labelWidth - 6),
                    UiRangeSlider.HEIGHT));
            this.controls.add(this.logoSize);
            return;
        }
        this.logoLineShown = showIn(this.logoLine, draft.logo(), this.logoLineShown);
        if (draft.logo() == null) {
            place(this.logoLine.at(x, y, width, LINE));
            this.controls.add(this.logoLine);
            return;
        }
        int linkWidth = font.width(this.positionLink.label());
        place(this.logoLine.at(x, y, Math.max(40, width - linkWidth - LINK_GAP), LINE));
        place(this.positionLink.at(x + width - linkWidth, y + (LINE - 8) / 2 - 1, linkWidth, 10));
        this.controls.add(this.logoLine);
        this.controls.add(this.positionLink);
    }

    // Adds a node of the column: to the scrolling column when it scrolls, else to the page
    private void place(UiNode node) {
        if (this.inColumn) {
            this.column.add(node);
        } else {
            this.editor.add(node);
        }
    }

    // A tile's place on the screen: a tile in the scrolling column moves with it
    private UiNode.Rect onScreen(UiNode.Rect at) {
        if (!this.inColumn || at.width() == 0) {
            return at;
        }
        return new UiNode.Rect(at.x(), at.y() - this.column.scrollY(), at.width(), at.height());
    }

    // The width inside a popover of this width, held in the content panel
    private int popoverInner(int popoverWidth) {
        int width = Math.max(POPOVER_MIN_WIDTH, Math.min(popoverWidth, this.editor.width - 8));
        width = Math.min(width, this.editor.contentPanel().width() - 2 * POPOVER_INSET);
        return Math.max(1, width - 2 * DialogFrame.PADDING);
    }

    /**
     * What makes the page lay itself out again: the narrow page's name problem, the box's kind,
     * whether there is a logo, whether the front of the box shows, and how many figures the
     * machine sample shows, which its strip is laid out and centred for.
     */
    private String layoutKey() {
        CreatorDraft draft = draft();
        boolean problem = this.narrow && PublishCheck.nameProblem(draft) != null;
        int sampled = this.sampleShown ? sampleCount(sampleFigures()) : -1;
        return problem + "/" + draft.boxMode() + "/" + (draft.logo() != null) + "/" + this.front.isOpen() + "/"
                + sampled;
    }

    // The preview's regular figures, the first of which the machine sample shows
    private List<FigureDefinition> sampleFigures() {
        FigureCollection preview = CollectionRegistry.getCollection(this.previewId).orElse(null);
        return preview != null ? preview.getRegularFigures() : List.of();
    }

    // How many whole thumbs the sample shows: the figures there are, as many as fit across it
    private int sampleCount(List<FigureDefinition> figures) {
        return Math.min(figures.size(), Math.max(0, (this.sampleWidth - 4) / THUMB_STEP));
    }

    // The short background row's colours for a row this wide: as many as fit with the custom tile,
    // the logo's slot and "More", the others leaving in their order
    private static int[] shortBackgrounds(int rowWidth) {
        int count = SidebarScreen.clamp((rowWidth - 43) / 18 - 1, 1, SHORT_BACKGROUNDS.length);
        List<Integer> kept = new ArrayList<>();
        for (int index : SHORT_BACKGROUNDS) {
            kept.add(index);
        }
        for (int i = 0; kept.size() > count && i < DROP_ORDER.length; i++) {
            kept.remove(Integer.valueOf(DROP_ORDER[i]));
        }
        int[] shown = new int[kept.size()];
        for (int i = 0; i < shown.length; i++) {
            shown[i] = kept.get(i);
        }
        return shown;
    }

    /**
     * How many colour places the box row has, and whether its captions show: five while they fit
     * in a row this wide, down to three, then the captions go, then the places go down to none.
     */
    private void fitBoxes(int rowWidth, boolean withCaptions) {
        if (withCaptions) {
            for (int places = BOX_PLACES; places >= 3; places--) {
                if (boxRowWidth(places, true) <= rowWidth) {
                    this.boxPlaces = places;
                    this.boxCaptions = true;
                    return;
                }
            }
        }
        this.boxCaptions = false;
        for (int places = withCaptions ? 3 : BOX_PLACES; places >= 0; places--) {
            if (boxRowWidth(places, false) <= rowWidth) {
                this.boxPlaces = places;
                return;
            }
        }
        this.boxPlaces = 0;
    }

    private int boxRowWidth(int places, boolean captions) {
        int saved = this.boxPlaces;
        boolean savedCaptions = this.boxCaptions;
        this.boxPlaces = places;
        this.boxCaptions = captions;
        try {
            return UiTileRow.widthOf(boxTiles(), BOX_TILE, BOX_GAP, captions);
        } finally {
            this.boxPlaces = saved;
            this.boxCaptions = savedCaptions;
        }
    }

    // What the draft is now

    @Override
    public void draftChanged() {
        CreatorDraft draft = draft();
        refreshLogoColour();
        if (draft.logo() == null && this.front.isOpen()) {
            // Taken away, by an undo or another operator: the front goes with it
            this.front.close();
        }
        if (draft.boxMode() == BoxMode.TEXTURE) {
            this.boxShown = showIn(this.boxZone, draft.boxTexture(), this.boxShown);
        }
        if (draft.logo() != null) {
            this.logoFullShown = showIn(this.logoFull, draft.logo(), this.logoFullShown);
            this.logoLineShown = showIn(this.logoLine, draft.logo(), this.logoLineShown);
        }
        this.logoSize.set(draft.logoLayout().scalePct());
        if (this.sampleShown) {
            // The same number of figures, which may be others now; a figure more or fewer lays the
            // page out again below, so the strip is centred and asks for its models anew
            List<FigureDefinition> figures = sampleFigures();
            int count = sampleCount(figures);
            if (count == this.strip.items().size()) {
                this.strip.setItems(figures.subList(0, count));
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (this.built && !layoutKey().equals(this.builtLayout) && minecraft.screen == this.editor) {
            // A kind of box, a logo, a name problem or a figure that needs other rows; a click on a colour does not
            this.editor.rebuildPage();
        }
    }

    /**
     * Shows the file a slot holds in its zone, unless the zone shows it already, and returns the
     * sha it shows. A file still on this computer shows its own name, a stored one a plain label,
     * short in a one-line zone, where the checks share the line, and a file the server lost says so.
     */
    @Nullable
    private String showIn(UiDropZone zone, @Nullable FileRef ref, @Nullable String shown) {
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
        boolean line = zone == this.boxZone || zone == this.logoLine;
        zone.showFile(tr(line ? "look.stored_short" : "identity.stored_file").getString(), sha, info);
        if (ClientWorldCollections.isMissingOnServer(sha)) {
            zone.showError(tr("file_missing"));
        }
        return sha;
    }

    // The tiles, asked for on every frame

    private List<Tile> backgroundTiles() {
        int rgb = draft().rgb() & 0xFFFFFF;
        int named = namedIndex(rgb);
        List<Tile> tiles = new ArrayList<>();
        if (this.fullBackgrounds) {
            for (int i = 0; i < UiSwatches.BACKGROUNDS.size(); i++) {
                tiles.add(presetTile(i, named));
            }
        } else {
            int[] shown = this.shortBackgrounds.clone();
            if (named >= 0 && !contains(shown, named)) {
                // The chosen colour shows: it takes the last place, the others stay where they are
                shown[shown.length - 1] = named;
            }
            for (int index : shown) {
                tiles.add(presetTile(index, named));
            }
        }
        tiles.add(customTile(rgb, named));
        tiles.add(Tile.separator());
        tiles.add(logoTile(rgb, named));
        if (!this.fullBackgrounds) {
            tiles.add(Tile.glyph(BG_MORE, Theme.ICON_MORE, tr("look.bg_all"), this::openBackgroundPopover));
        }
        return tiles;
    }

    // The background popover: every colour, the custom tile and the logo's, in rows of seven
    private List<Tile> popoverBackgroundTiles() {
        int rgb = draft().rgb() & 0xFFFFFF;
        int named = namedIndex(rgb);
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < UiSwatches.BACKGROUNDS.size(); i++) {
            tiles.add(presetTile(i, named));
        }
        tiles.add(customTile(rgb, named));
        tiles.add(logoTile(rgb, named));
        return tiles;
    }

    private Tile presetTile(int index, int named) {
        UiSwatches.Swatch swatch = UiSwatches.BACKGROUNDS.get(index);
        return Tile.colour("bg/" + swatch.key(), swatch.rgb(), index == named,
                tr("wizard.colour_value", swatch.name(), UiColourPicker.hex(swatch.rgb())), () -> {
                    closePopovers();
                    setColour(swatch.rgb());
                });
    }

    // The colour of one's own while it is neither a preset nor the logo's, else a "+" that picks one
    private Tile customTile(int rgb, int named) {
        if (named < 0 && !isLogoColour(rgb)) {
            return Tile.custom(BG_CUSTOM, rgb, true, tr("look.bg_custom_tip", UiColourPicker.hex(rgb)),
                    this::pickCustom);
        }
        return Tile.add(BG_CUSTOM, tr("look.bg_custom_new"), this::pickCustom);
    }

    /**
     * The logo's main colour with the logo on it; before a logo, or while its colour is not known
     * yet, or for an image with none, its place kept dashed, so adding a logo moves no tile.
     */
    private Tile logoTile(int rgb, int named) {
        FileRef logo = draft().logo();
        if (logo == null) {
            return Tile.empty(BG_LOGO, tr("look.bg_logo_empty"));
        }
        OptionalInt colour = this.logoColours.get(logo.sha());
        if (colour == null) {
            return Tile.empty(BG_LOGO, tr("look.bg_from_logo"));
        }
        if (colour.isEmpty()) {
            return Tile.empty(BG_LOGO, tr("wizard.no_colour"));
        }
        int logoRgb = colour.getAsInt() & 0xFFFFFF;
        return Tile.picture(BG_LOGO, logoRgb, this::drawLogo, named < 0 && rgb == logoRgb,
                tr("wizard.colour_value", tr("wizard.from_logo"), UiColourPicker.hex(logoRgb)), () -> {
                    closePopovers();
                    setColour(logoRgb);
                });
    }

    // The logo itself, fitted on its tile
    private void drawLogo(GuiGraphics graphics, int x, int y, int width, int height) {
        UiBoxFrontEditor.Logo logo = logoTexture();
        if (logo == null) {
            return;
        }
        int[] fitted = Draw.fitInside(logo.width(), logo.height(), width, height);
        Draw.texture(graphics, logo.texture(), x + (width - fitted[0]) / 2, y + (height - fitted[1]) / 2, fitted[0],
                fitted[1], 0, 0, logo.width(), logo.height(), logo.width(), logo.height());
    }

    private List<Tile> boxTiles() {
        CreatorDraft draft = draft();
        BoxMode mode = draft.boxMode();
        PopBlockColor chosen = mode == BoxMode.COLOR ? boxColour(draft.boxColor()) : null;
        List<Tile> tiles = new ArrayList<>();
        PopBlockColor[] shown = new PopBlockColor[this.boxPlaces];
        System.arraycopy(BOX_COLOURS, 0, shown, 0, this.boxPlaces);
        if (chosen != null && shown.length > 0 && !contains(shown, chosen)) {
            // The chosen colour takes the last place
            shown[shown.length - 1] = chosen;
        }
        for (PopBlockColor colour : shown) {
            tiles.add(boxTile(colour, chosen));
        }
        if (shown.length > 0) {
            tiles.add(Tile.separator());
        }
        boolean withImage = mode == BoxMode.TEXTURE && draft.boxTexture() != null;
        Component imageTip = tr("look.box_image_tip", WorldContentLimits.BOX_SIZE);
        Tile image = withImage
                ? Tile.item(BOX_IMAGE, this.previewBox, true, imageTip, () -> setBoxMode(BoxMode.TEXTURE))
                : Tile.upload(BOX_IMAGE, mode == BoxMode.TEXTURE, imageTip, () -> setBoxMode(BoxMode.TEXTURE));
        tiles.add(image.withCaption(tr("look.box_image")));
        tiles.add(Tile.item(BOX_NORMAL, boxItem(PopBlockColor.ORIGINAL), mode == BoxMode.NONE,
                tr("look.box_normal_tip"), () -> setBoxMode(BoxMode.NONE)).withCaption(tr("look.box_normal")));
        tiles.add(Tile.glyph(BOX_MORE, Theme.ICON_MORE, tr("look.box_more_tip"), this::openBoxPopover)
                .withCaption(tr("show.more_short")));
        return tiles;
    }

    // The box popover: the five of the row first, then the others in their order, the normal box last when chosen
    private List<Tile> popoverBoxTiles() {
        CreatorDraft draft = draft();
        PopBlockColor chosen = draft.boxMode() == BoxMode.COLOR ? boxColour(draft.boxColor()) : null;
        List<Tile> tiles = new ArrayList<>();
        for (PopBlockColor colour : BOX_COLOURS) {
            tiles.add(boxTile(colour, chosen));
        }
        for (PopBlockColor colour : PopBlockColor.values()) {
            if (colour != PopBlockColor.ORIGINAL && !contains(BOX_COLOURS, colour)) {
                tiles.add(boxTile(colour, chosen));
            }
        }
        if (chosen == PopBlockColor.ORIGINAL) {
            tiles.add(boxTile(PopBlockColor.ORIGINAL, chosen));
        }
        return tiles;
    }

    private Tile boxTile(PopBlockColor colour, @Nullable PopBlockColor chosen) {
        return Tile.item("box/" + colour.getSerializedName(), boxItem(colour), colour == chosen,
                UiSwatches.nameOf(colour), () -> {
                    closePopovers();
                    CreatorDraft draft = draft();
                    draft.checkpoint();
                    draft.setBoxColor(colour.getSerializedName());
                    this.editor.changed();
                });
    }

    private ItemStack boxItem(PopBlockColor colour) {
        return this.boxItems.computeIfAbsent(colour, which -> {
            RegistrySupplier<Item> item = ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(which);
            return item != null ? new ItemStack(item.get()) : ItemStack.EMPTY;
        });
    }

    private static int namedIndex(int rgb) {
        for (int i = 0; i < UiSwatches.BACKGROUNDS.size(); i++) {
            if (UiSwatches.BACKGROUNDS.get(i).rgb() == (rgb & 0xFFFFFF)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(int[] values, int value) {
        for (int each : values) {
            if (each == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(PopBlockColor[] values, PopBlockColor value) {
        for (PopBlockColor each : values) {
            if (each == value) {
                return true;
            }
        }
        return false;
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

    // What the heads and the narrow labels say

    // Whether the background is the logo's own colour, rather than a preset or one picked by hand
    private boolean isLogoColour(int rgb) {
        FileRef logo = draft().logo();
        OptionalInt colour = logo != null ? this.logoColours.get(logo.sha()) : null;
        return colour != null && colour.isPresent() && (colour.getAsInt() & 0xFFFFFF) == (rgb & 0xFFFFFF);
    }

    private Component backgroundValue() {
        int rgb = draft().rgb() & 0xFFFFFF;
        if (namedIndex(rgb) >= 0) {
            return UiSwatches.nameOf(rgb);
        }
        Component which = isLogoColour(rgb) ? tr("look.bg_from_logo") : tr("colour.custom");
        return tr("wizard.colour_value", which, UiColourPicker.hex(rgb));
    }

    private Component backgroundShort() {
        int rgb = draft().rgb() & 0xFFFFFF;
        return namedIndex(rgb) >= 0 ? UiSwatches.nameOf(rgb) : Component.literal(UiColourPicker.hex(rgb));
    }

    private Component boxValue() {
        CreatorDraft draft = draft();
        return switch (draft.boxMode()) {
            case COLOR -> {
                PopBlockColor colour = boxColour(draft.boxColor());
                yield colour != null ? UiSwatches.nameOf(colour) : tr("identity.box_colour_missing");
            }
            case TEXTURE -> draft.boxTexture() != null ? tr("identity.box.image") : tr("look.box_missing");
            case NONE -> tr("look.box_normal");
        };
    }

    private Component boxShort() {
        CreatorDraft draft = draft();
        return switch (draft.boxMode()) {
            case COLOR -> {
                PopBlockColor colour = boxColour(draft.boxColor());
                yield colour != null ? UiSwatches.nameOf(colour) : Component.empty();
            }
            case TEXTURE -> tr("look.box_image");
            case NONE -> tr("look.box_normal");
        };
    }

    private Component logoValue() {
        CreatorDraft draft = draft();
        if (draft.logo() == null) {
            return tr("look.logo_optional");
        }
        WorldCollectionRecord.LogoLayout layout = draft.logoLayout();
        boolean home = layout.offsetX() == 0 && layout.offsetY() == 0;
        return tr("look.logo_value", tr("identity.percent", layout.scalePct()),
                tr(home ? "look.logo_home" : "look.logo_moved"));
    }

    private Component logoShort() {
        CreatorDraft draft = draft();
        return draft.logo() == null ? Component.empty() : tr("identity.percent", draft.logoLayout().scalePct());
    }

    private Component counter() {
        return Component.literal(draft().name().length() + "/" + WorldContentLimits.MAX_NAME_LENGTH);
    }

    // The box image is the kind chosen and there is none yet
    private boolean missingImage() {
        CreatorDraft draft = draft();
        return draft.boxMode() == BoxMode.TEXTURE && draft.boxTexture() == null;
    }

    // Changes, each one undo step of its own

    private void nameTyped(String value) {
        draft().setName(value);
        this.editor.changed();
    }

    private void setColour(int rgb) {
        CreatorDraft draft = draft();
        draft.checkpoint();
        draft.setRgb(rgb & 0xFFFFFF);
        this.editor.changed();
    }

    private void setBoxMode(BoxMode mode) {
        closePopovers();
        CreatorDraft draft = draft();
        draft.checkpoint();
        draft.setBoxMode(mode);
        this.editor.changed();
    }

    private void pickCustom() {
        closePopovers();
        UiColourPicker.open(this.editor, draft().rgb(), this::setColour);
    }

    private void boxPicked(FilePicker.LocalFile file) {
        CreatorDraft draft = draft();
        this.boxShown = file.sha();
        draft.checkpoint();
        draft.setBoxTexture(FileRef.of(file));
        this.editor.changed();
    }

    private void logoPicked(FilePicker.LocalFile file) {
        CreatorDraft draft = draft();
        this.logoEmptyShown = null;
        this.logoFullShown = null;
        this.logoLineShown = null;
        draft.checkpoint();
        draft.setLogo(FileRef.of(file));
        this.editor.changed();
    }

    // "Remove" takes the logo and its place on the box; a background taken from it stays, as a custom colour
    private void removeLogo() {
        CreatorDraft draft = draft();
        this.front.close();
        this.logoEmptyShown = null;
        this.logoFullShown = null;
        this.logoLineShown = null;
        draft.checkpoint();
        draft.setLogo(null);
        this.editor.changed();
    }

    private void placeLogo(int scalePct, int offsetX, int offsetY) {
        CreatorDraft draft = draft();
        if (draft.logo() == null || !WorldCollectionRecord.LogoLayout.isValid(scalePct, offsetX, offsetY)) {
            return;
        }
        draft.setLogoLayout(new WorldCollectionRecord.LogoLayout(scalePct, offsetX, offsetY));
        this.editor.changed();
    }

    // Position…: the view turns into the front of the box, or back; closing it is "Done"
    private void togglePosition() {
        if (this.front.isOpen()) {
            closeFront();
        } else {
            openFront();
        }
    }

    private void openFront() {
        if (draft().logo() == null || this.front.isOpen()) {
            return;
        }
        closePopovers();
        this.editor.closeMenu();
        this.front.open();
        this.editor.rebuildPage();
        // The arrow keys nudge the logo from now on
        this.viewTakesKeyboard = true;
    }

    // "Done": the front closes, and the files waiting go now, as when the operator leaves a sub-view
    private void closeFront() {
        if (!this.front.isOpen()) {
            return;
        }
        this.front.close();
        if (CreatorScreen.hasKeyboard(this.view)) {
            this.editor.focusNode(null);
        }
        this.editor.navigated();
        this.editor.rebuildPage();
    }

    // Popovers, the menu and the machine's screen

    private void openBoxPopover() {
        closePopovers();
        this.editor.openPopover(this.boxPopover);
    }

    private void openBackgroundPopover() {
        closePopovers();
        this.editor.openPopover(this.backgroundPopover);
    }

    private void closePopovers() {
        for (UiPopover popover : new UiPopover[] {this.boxPopover, this.backgroundPopover}) {
            if (popover.isOpen()) {
                popover.close();
            }
        }
    }

    /**
     * "View ⌄": the box alone or in the world, the chosen one checked, then the machine's screen,
     * which opens in a dialog. Under the button, by its right edge on the wide page and its left
     * on the narrow one, so it never covers the icons.
     */
    private void openViewMenu() {
        UiMenu menu = new UiMenu();
        UiBoxView.Mode mode = this.view.mode();
        menu.radio(tr("preview.box"), mode != UiBoxView.Mode.WORLD, () -> this.view.setMode(UiBoxView.Mode.BOX));
        menu.radio(tr("preview.world"), mode == UiBoxView.Mode.WORLD, () -> this.view.setMode(UiBoxView.Mode.WORLD));
        menu.item(tr("preview.screen"), this::openMachineScreen);
        menu.highlight(mode == UiBoxView.Mode.WORLD ? 1 : 0);
        UiNode.Rect button = this.viewButton.bounds();
        menu.place(button, this.editor.width, this.editor.height);
        int menuX = this.narrow ? button.x() : Math.max(0, button.right() - menu.bounds().width());
        this.editor.openMenu(menu, new UiNode.Rect(menuX, button.y(), button.width(), button.height()));
    }

    private void openMachineScreen() {
        this.machineScreen = MachineScreenDialog.open(this.editor);
    }

    // The logo's colour, for the background's logo tile

    /**
     * Works out the logo's main colour once per file: a file picked on this computer on a worker,
     * a stored one from its loaded texture. Until it is known it is asked for again on the next
     * change or tick: a stored logo whose texture is still arriving, and an answer that never came
     * because another screen was shown when it was ready.
     */
    private void refreshLogoColour() {
        FileRef logo = draft().logo();
        if (logo == null || this.logoColours.containsKey(logo.sha())) {
            return;
        }
        String sha = logo.sha();
        if (logo.local() != null) {
            if (Objects.equals(this.askedSha, sha) && Util.getMillis() - this.askedAt < ASK_AGAIN_MS) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen != this.editor) {
                // Its answer would be dropped; asked once the editor is shown again
                return;
            }
            this.askedSha = sha;
            this.askedAt = Util.getMillis();
            DominantColour.ofPngLater(this.editor, logo.local().bytes(), colour -> {
                this.logoColours.put(sha, colour);
                if (sha.equals(this.askedSha)) {
                    this.askedSha = null;
                }
            });
            return;
        }
        ResourceLocation texture = WorldContentIds.texture(sha);
        if (RuntimeTextures.isPending(texture) || RuntimeTextures.image(texture) == null) {
            // Still arriving: not "no colour"
            return;
        }
        this.logoColours.put(sha, DominantColour.ofTexture(texture));
    }

    // Textures the page draws flat

    /** The logo's texture and its image's size, or null while there is none to draw. */
    @Nullable
    private UiBoxFrontEditor.Logo logoTexture() {
        FileRef logo = draft().logo();
        BlobInfo info = logo != null ? logo.info() : null;
        if (logo == null || info == null) {
            return null;
        }
        ResourceLocation texture = WorldContentIds.texture(logo.sha());
        return RuntimeTextures.isPending(texture) ? null
                : new UiBoxFrontEditor.Logo(texture, info.width(), info.height());
    }

    @Nullable
    private UiDropZone.Thumbnail logoThumbnail() {
        UiBoxFrontEditor.Logo logo = logoTexture();
        return logo == null ? null : new UiDropZone.Thumbnail(logo.texture(), 0, 0, logo.width(), logo.height(),
                logo.width(), logo.height());
    }

    // The box image's front face, as its line's thumbnail
    @Nullable
    private UiDropZone.Thumbnail boxThumbnail() {
        FileRef image = draft().boxTexture();
        if (draft().boxMode() != BoxMode.TEXTURE || image == null) {
            return null;
        }
        ResourceLocation texture = WorldContentIds.texture(image.sha());
        return RuntimeTextures.isPending(texture) ? null : new UiDropZone.Thumbnail(texture, LogoLayout.FRONT_U,
                LogoLayout.FRONT_V, LogoLayout.FRONT_WIDTH, LogoLayout.FRONT_HEIGHT, LogoLayout.BOX_TEXTURE_SIZE,
                LogoLayout.BOX_TEXTURE_SIZE);
    }

    // The box texture as chosen now: a plain colour's, the image once it is loaded, else the mod's usual box
    private ResourceLocation boxTexture() {
        CreatorDraft draft = draft();
        ResourceLocation usual =
                WorldCollectionMaterializer.boxColorTexture(PopBlockColor.ORIGINAL.getSerializedName());
        switch (draft.boxMode()) {
            case TEXTURE -> {
                if (draft.boxTexture() == null) {
                    return usual;
                }
                ResourceLocation own = WorldContentIds.texture(draft.boxTexture().sha());
                return RuntimeTextures.isPending(own) ? usual : own;
            }
            case COLOR -> {
                PopBlockColor colour = boxColour(draft.boxColor());
                return colour == null ? usual : WorldCollectionMaterializer.boxColorTexture(colour.getSerializedName());
            }
            default -> {
                return usual;
            }
        }
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
        Font font = Minecraft.getInstance().font;
        CreatorDraft draft = draft();
        if (this.nameLabelWidth > 0) {
            // The wide name's label, and its length on the right of the column
            String count = counter().getString();
            int counterX = this.nameLabelX + this.nameLabelWidth - font.width(count);
            SidebarScreen.smallLabel(graphics, tr("name"), this.nameLabelX, this.nameLabelMiddle,
                    Math.max(0, counterX - 8 - this.nameLabelX), Theme.TEXT_SOFT);
            Draw.string(graphics, font, count, counterX, this.counterY,
                    draft.name().length() >= WorldContentLimits.MAX_NAME_LENGTH ? Theme.AMBER : Theme.FAINT);
        }
        Component problem = PublishCheck.nameProblem(draft);
        if (problem != null) {
            Draw.text(graphics, font, problem.getString(), this.statusX, this.statusY, this.statusWidth, Theme.RED);
        }
    }

    // The keyboard

    /** Tab in the name moves on to the background's tiles. */
    @Override
    public boolean fieldKey(EditBox field, UiInput.Key key) {
        if (field == this.nameBox && key.is(InputConstants.KEY_TAB) && !key.shift() && !this.controls.isEmpty()) {
            focus(this.controls.get(0));
            return true;
        }
        return false;
    }

    /**
     * Up, Down, Tab and Shift+Tab move from control to control while one of the page's has the
     * keyboard; Enter or Space presses "Position…" when it has it.
     */
    @Override
    public boolean shortcut(UiInput.Key key) {
        int at = keyboardControl();
        if (at < 0) {
            return false;
        }
        UiNode control = this.controls.get(at);
        boolean tab = key.is(InputConstants.KEY_TAB);
        if (key.is(InputConstants.KEY_DOWN) || tab && !key.shift()) {
            if (at + 1 < this.controls.size()) {
                focus(this.controls.get(at + 1));
            }
            return true;
        }
        if (key.is(InputConstants.KEY_UP) || tab && key.shift()) {
            if (at > 0) {
                focus(this.controls.get(at - 1));
            } else if (this.nameBox != null) {
                // Back to the name, above the first row
                this.editor.focusNode(null);
                this.keyed = null;
                this.editor.setFocused(this.nameBox);
            }
            return true;
        }
        if (control == this.positionLink && (key.enter() || key.is(InputConstants.KEY_SPACE))) {
            this.positionLink.press();
            return true;
        }
        return false;
    }

    // The page's control that has the keyboard now, by index, or -1
    private int keyboardControl() {
        for (int i = 0; i < this.controls.size(); i++) {
            if (CreatorScreen.hasKeyboard(this.controls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // Gives a control the keyboard from a key, which shows where it is
    private void focus(UiNode control) {
        this.editor.setFocused(null);
        if (this.inColumn) {
            this.editor.focusNode(this.column);
            this.column.focus(control);
            UiNode.Rect at = control.bounds();
            this.column.reveal(at.y(), at.bottom());
        } else {
            this.editor.focusNode(control);
        }
        for (UiNode each : this.controls) {
            if (each instanceof UiTileRow row && row != control) {
                row.hideCursor();
            }
        }
        if (control instanceof UiTileRow row) {
            row.showCursor();
        }
        this.keyed = control;
    }

    /**
     * Escape: the front of the box closes, as "Done"; else a control of the page that has the
     * keyboard lets it go; else the editor goes on to leave.
     */
    @Override
    public boolean escape() {
        if (this.front.isOpen()) {
            closeFront();
            return true;
        }
        if (keyboardControl() >= 0 || CreatorScreen.hasKeyboard(this.view)) {
            this.editor.focusNode(null);
            this.keyed = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean takesFiles() {
        return true;
    }

    @Override
    public void tick() {
        this.view.tick();
        if (this.viewTakesKeyboard) {
            this.viewTakesKeyboard = false;
            if (this.front.isOpen()) {
                this.editor.focusNode(this.view);
                this.keyed = null;
            }
        }
        refreshLogoColour();
        MachineScreenDialog shown = this.machineScreen;
        if (shown != null && !shown.isOpen()) {
            // Closed by a dialog opened over it
            shown.closed();
            this.machineScreen = null;
        }
    }

    /** Another tab is shown: the front of the box closes, and so do the page's popovers. */
    @Override
    public void hide() {
        this.front.close();
        closePopovers();
    }

    @Override
    public void close() {
        this.front.close();
        this.view.close();
        MachineScreenDialog shown = this.machineScreen;
        if (shown != null) {
            shown.close();
            this.machineScreen = null;
        }
    }

    // The front of the box's model: the draft's box and logo, and its changes as undo steps
    private final class FrontModel implements UiBoxFrontEditor.Model {
        @Override
        public ResourceLocation boxTexture() {
            return LookPage.this.boxTexture();
        }

        @Override
        @Nullable
        public UiBoxFrontEditor.Logo logo() {
            return logoTexture();
        }

        @Override
        public WorldCollectionRecord.LogoLayout layout() {
            return draft().logoLayout();
        }

        @Override
        public void moveTo(int offsetX, int offsetY) {
            placeLogo(draft().logoLayout().scalePct(), offsetX, offsetY);
        }

        @Override
        public void beginChange() {
            draft().checkpoint();
        }

        @Override
        public void released() {
            editor.saver().numberReleased();
        }

        @Override
        public void reset() {
            draft().checkpoint();
            moveTo(0, 0);
        }

        @Override
        public void done() {
            closeFront();
        }

        @Override
        public boolean footerPrimary() {
            return editor.footerIsPrimary();
        }
    }

    /**
     * The logo's size: a slider from 50 to 150 % in steps of 5; a press on it, and the first of a
     * run of arrow keys, starts an undo step of its own, whose values then follow one another.
     */
    private final class SizeSlider extends UiRangeSlider {
        private boolean keyRun;

        SizeSlider(int value) {
            super(WorldCollectionRecord.LogoLayout.MIN_SCALE_PCT, WorldCollectionRecord.LogoLayout.MAX_SCALE_PCT, 5,
                    WorldCollectionRecord.LogoLayout.DEFAULT.scalePct(), value,
                    percent -> tr("identity.percent", percent), percent -> {
                        WorldCollectionRecord.LogoLayout now = draft().logoLayout();
                        placeLogo(percent, now.offsetX(), now.offsetY());
                    });
        }

        @Override
        public boolean click(UiInput.Click click) {
            this.keyRun = false;
            if (click.left() && isMouseOver(click.x(), click.y())) {
                draft().checkpoint();
            }
            return super.click(click);
        }

        @Override
        public boolean key(UiInput.Key key) {
            boolean arrow = key.is(InputConstants.KEY_LEFT) || key.is(InputConstants.KEY_RIGHT);
            if (!arrow || !CreatorScreen.hasKeyboard(this)) {
                this.keyRun = false;
                return super.key(key);
            }
            if (!this.keyRun) {
                draft().checkpoint();
                this.keyRun = true;
            }
            return super.key(key);
        }
    }

    /** "Template ↓", whose tooltip says what it writes on one line and what the template shows on the next. */
    private static final class TemplateLink extends UiButton {
        TemplateLink(Runnable onPress) {
            super(tr("look.template"), Style.LINK, onPress);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.draw(graphics, mouseX, mouseY, partialTick);
            if (visible() && hovered(mouseX, mouseY)) {
                UiTooltip.set(List.of(tr("identity.template"), tr("identity.template_note")));
            }
        }
    }

    /** The scrolling column, which can give one of its rows the keyboard, as a key moving on does. */
    private static final class Column extends UiScrollPane {
        void focus(UiNode child) {
            focusChild(child);
        }
    }

    /** Text in one colour, wrapped to its width in up to a number of lines; the whole of it is its tooltip when cut. */
    private static final class Note extends UiNode.Box {
        private Component text = Component.empty();
        private final int colour;
        private int maxLines = 1;

        Note(int colour) {
            this.colour = colour;
        }

        // How many lines it takes in a width, at most its limit
        int lines(int forWidth) {
            return Math.max(1, Draw.fit(Minecraft.getInstance().font, this.text.getString(), forWidth,
                    this.maxLines).size());
        }

        int height(int forWidth) {
            return lines(forWidth) * Theme.LINE_HEIGHT;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String whole = this.text.getString();
            Draw.lines(graphics, font, whole, x, y, width, this.maxLines, this.colour);
            if (hovered(mouseX, mouseY) && Draw.wrap(font, whole, width).size() > this.maxLines) {
                UiTooltip.set(SidebarScreen.tooltipLines(this.text));
            }
        }
    }

    /**
     * The machine's panel behind the figures, in the background colour, which the strip of the
     * first figures stands on; with no figure it says so.
     */
    private final class Sample extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            Draw.panel(graphics, x, y, width, height, 0xFF000000 | draft().rgb(), Surfaces.OUTLINE);
            if (strip.items().isEmpty()) {
                Draw.centred(graphics, Minecraft.getInstance().font, tr("show.no_figures").getString(), x + width / 2,
                        y + (height - 8) / 2, width - 8, Theme.FAINT);
            }
            if (hovered(mouseX, mouseY) && (strip.items().isEmpty() || !strip.bounds().contains(mouseX, mouseY))) {
                UiTooltip.set(SidebarScreen.tooltipLines(tr("look.bg_hint")));
            }
        }
    }

    /**
     * Where the keyboard is, once a key moved it to a control that is not a row of tiles (the
     * rows draw their own cursor): a light line around it.
     */
    private final class KeyboardRing extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            UiNode shown = keyed;
            if (shown == null || shown instanceof UiTileRow || !CreatorScreen.hasKeyboard(shown)) {
                return;
            }
            UiNode.Rect at = shown.bounds();
            Draw.outline(graphics, at.x() - 2, at.y() - 2, at.width() + 4, at.height() + 4, Theme.SELECTED_LIGHT);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return false;
        }
    }
}
