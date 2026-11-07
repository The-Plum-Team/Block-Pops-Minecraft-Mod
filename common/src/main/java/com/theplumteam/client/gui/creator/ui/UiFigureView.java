package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The big live preview of one figure, as the figure editor shows it beside its strip: the figure
 * alone on a stage, in the look of the home's showcase ({@link UiStage}): the collection's colour
 * fading into the dark of a well over three quarters of its height, a ledge the figure stands on
 * and the well's double outline. The figure's full height fills {@value #FILL_PERCENT} percent
 * of the stage, its feet on the ledge, turned a quarter of the way round from its front, and it
 * is never wider than the stage allows: a model is fitted by the height and the turned width its
 * host gives ({@link #setExtent}). Dragging turns it, and a double click brings it back to that
 * turn (see {@link UiViewport}).
 *
 * <p>The only control is "View ⌄" in the top right corner ({@link UiViewButton}), whose menu
 * picks how the figure is shown:
 * <ul>
 *   <li>{@link Mode#LOOSE}: standing on the ledge. A figure of another size than 1x has the
 *       default figure at 1x beside it, in its default skin and labelled "1x" over its head, the
 *       two side by side across the stage, so its size reads at a glance;</li>
 *   <li>{@link Mode#IN_BOX}: in its collection's box, standing on the ledge, as the machine
 *       hands it out;</li>
 *   <li>{@link Mode#IN_COLLECTION}: in a cell of the machine's figure grid, in colour once
 *       discovered and in black and white before, which the menu also picks;</li>
 * </ul>
 * standing or sitting (sitting disabled for a model whose poses have no sitting one), the light
 * by day or at night, spinning by itself or not, and, for a figure with skin variants, which of
 * its skins it wears: its own, the base, or a variant by name. The menu keeps inside the panel the
 * host names ({@link #within}); where the skins would make it taller than the room there, they
 * move to a menu of their own behind one "Variant" item.
 *
 * <p>The figure is looked up by collection and figure id on every frame, through the collection
 * registry, so the view follows the creator's preview of a collection (see {@link CreatorPreview})
 * as soon as the screen shows it again. Its models are the screen's first
 * ({@link SidebarScreen.ModelBudget.Rank#PREVIEW}): the figure, then the 1x figure; with none
 * left, as under a dialog that draws models of its own, the stage shows empty.
 *
 * <p>A host may lay a {@link UiDropZone#hidden hidden} zone over the view ({@link #dropZone}),
 * so a file dropped on the figure goes to the figure, without the view naming a slot of its own.
 */
public class UiFigureView extends UiViewport implements SidebarScreen.Budgeted {
    /** What the view shows the figure in. */
    public enum Mode {
        LOOSE, IN_BOX, IN_COLLECTION
    }

    /** How much of the stage's height a figure's full height fills, in percent. */
    public static final int FILL_PERCENT = 70;
    private static final float FILL = FILL_PERCENT / 100.0f;
    // A skin figure of size 1 is about this tall against the cell it is drawn in, with this camera,
    // and about this wide against its height; a model is fitted to stand at most 15 of the cell's
    // 16 pixels tall and 9 wide at size 1 (see WorldCollectionMaterializer)
    private static final float SKIN_HEIGHT = 0.71f;
    private static final float SKIN_WIDTH = 0.56f;
    private static final float MODEL_HEIGHT = 15.0f / 16.0f;
    private static final float MODEL_WIDTH = 9.0f / 16.0f;
    // The share of the stage's width a figure may take alone, and the two figures side by side,
    // with the gap between them
    private static final float WIDTH_ALONE = 0.86f;
    private static final float WIDTH_PAIR = 0.88f;
    private static final int PAIR_GAP = 4;
    // The machine screen's camera height, turned a quarter of the way round from the figure's front
    private static final float FRONT_TURN = 155.0f;
    private static final float CAMERA_Y = 15.0f;
    // A plush is about twice as wide as a figure, as the machine's grid shrinks it
    private static final float PLUSH_SCALE = 0.6f;
    // Where a figure's feet are in its cell with this camera (0.6 of the cell, and the camera's
    // height in an 80 unit cell), and a box's middle above the ledge, against their size
    private static final float FEET = 0.6f + CAMERA_Y / 80.0f;
    private static final float BOX_MIDDLE = 0.56f;
    // How far into the ledge a figure's feet stand, so it reads as standing on it
    private static final int ON_LEDGE = 2;
    private static final int FLOOR_INSET = 6;
    private static final int FLOOR = 0xFF262729;
    private static final int FLOOR_EDGE = 0xFF3C3D40;
    // The machine grid's cell look, from FigureEntry
    private static final int CELL_DISCOVERED = 0x30FFFFFF;
    private static final int CELL_UNDISCOVERED = 0x50000000;
    private static final int BORDER_DISCOVERED = 0x80FFFFFF;
    private static final int BORDER_UNDISCOVERED = 0x60808080;
    private static final int SPECIAL_BORDER = 0xE0BB8FCE;
    private static final int SPECIAL_BORDER_UNDISCOVERED = 0x80BB8FCE;
    private static final int MAX_CELL = 160;
    // The room the view button keeps at the top of the stage
    private static final int TOP_ROOM = UiButton.SMALL_HEIGHT + 4;
    // How tall a box seen from the front and a little above is, against the size it is drawn at
    static final float BOX_HEIGHT = 1.15f;

    private final UiViewButton viewButton;
    @Nullable
    private String collectionId;
    @Nullable
    private String figureId;
    private Mode mode = Mode.LOOSE;
    private boolean sitting;
    private boolean discovered = true;
    private boolean greyed;
    // The names of the figure's variants, and which skin is shown: 0 its own, 1 onwards a variant
    private List<String> variants = List.of();
    private int skinIndex;
    private boolean showReference = true;
    // The figure's size as the operator chose it, 1 for the usual size
    private float figureScale = 1.0f;
    private Component emptyText = CreatorScreen.tr("preview.no_figure");
    // The figure's height and turned width against its cell at size 1, as the host knows them for
    // a model; 0 for a skin's own shape or a model's usual fit
    private float extentHeight;
    private float extentWidth;
    // The panel the menu keeps inside, or null for the screen
    @Nullable
    private java.util.function.Supplier<Rect> within;
    // Where the last frame put the 1x figure's label: its middle and its top
    private int referenceLabelX;
    private int referenceLabelY;
    @Nullable
    private UiDropZone dropZone;
    // The models the screen's budget gives the view: the figure or the box, then the 1x figure
    private int models = 2;
    // Draws the figures; made again for another collection, since it names its collection
    @Nullable
    private FigureEntry entry;
    @Nullable
    private String entryCollectionId;
    private ItemStack box = ItemStack.EMPTY;
    @Nullable
    private String boxKey;

    public UiFigureView() {
        this.viewButton = new UiViewButton(CreatorScreen.tr("preview.view"), this::openMenu);
        this.controls.add(this.viewButton);
        // The menu switches the light and the spinning, so the buttons for them stay hidden
        setButtons(false);
    }

    /**
     * Shows a figure of a collection, both by id: a creator preview's id or any registered
     * collection's. Either null shows nothing.
     */
    public void setFigure(@Nullable String collectionId, @Nullable String figureId) {
        this.collectionId = collectionId;
        this.figureId = figureId;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isSitting() {
        return sitting;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    /** In the collection view, whether the figure shows as discovered, in colour. */
    public void setDiscovered(boolean discovered) {
        this.discovered = discovered;
    }

    /**
     * The names of the figure's skin variants, in order, which the menu lets the operator pick;
     * an empty list leaves them out. The skin shown stays when it still exists, else the figure's
     * own.
     */
    public void setVariants(List<String> names) {
        this.variants = List.copyOf(names);
        if (skinIndex > variants.size()) {
            skinIndex = 0;
        }
    }

    /** Which skin is shown: 0 the figure's own, 1 onwards its variants in order. */
    public int skinIndex() {
        return skinIndex;
    }

    public void setSkinIndex(int index) {
        this.skinIndex = Math.max(0, Math.min(index, variants.size()));
    }

    /** Draws the figure in black and white in every view, as a retired figure is shown. */
    public void setGreyed(boolean greyed) {
        this.greyed = greyed;
    }

    /** Whether the loose view may show the default figure at 1x beside a figure of another size. */
    public void setShowReference(boolean showReference) {
        this.showReference = showReference;
    }

    /**
     * The figure's size as the operator chose it, such as 1 or 1.5: the loose view sizes the
     * stage for it, and shows the 1x figure beside any other size.
     */
    public void setFigureScale(float scale) {
        this.figureScale = scale > 0 ? scale : 1.0f;
    }

    /**
     * A model figure's height and its width at the view's turn, against the cell it is drawn in
     * at size 1, as its bounds give them; 0 for either keeps the usual fit, as for a skin.
     */
    public void setExtent(float height, float width) {
        this.extentHeight = Math.max(0.0f, height);
        this.extentWidth = Math.max(0.0f, width);
    }

    /** The panel "View ⌄"'s menu keeps inside, such as the editor's content panel; null for the screen. */
    public void within(@Nullable java.util.function.Supplier<Rect> panel) {
        this.within = panel;
    }

    /** What the view says while it has no figure to show, such as one that misses a file. */
    public void setEmptyText(Component emptyText) {
        this.emptyText = emptyText;
    }

    /**
     * A hidden zone laid over the whole view, which takes a file dropped on the figure; null for
     * none. It is not drawn, and the which-slot menu never names it.
     */
    public void dropZone(@Nullable UiDropZone zone) {
        this.dropZone = zone;
    }

    @Override
    public void requestModels(SidebarScreen.ModelBudget budget) {
        budget.request(SidebarScreen.ModelBudget.Rank.PREVIEW, 2, granted -> this.models = granted);
    }

    // The menu

    /**
     * Opens "View ⌄"'s menu under the button, by its right edge: the three views, the pose, the
     * discovery in the collection view, the light, the spinning and the skins, kept inside the
     * host's panel; skins that would not fit there go behind one item of their own.
     */
    public void openMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreatorScreen screen)) {
            return;
        }
        Rect button = this.viewButton.bounds();
        Rect panel = within != null ? within.get() : null;
        UiMenu menu = menu(false);
        if (panel != null && !variants.isEmpty()) {
            int below = panel.bottom() - 4 - (button.bottom() + 2);
            int above = button.y() - 2 - (panel.y() + 4);
            if (menu.fullHeight() > Math.max(below, above)) {
                menu = menu(true);
            }
        }
        open(screen, menu, button, panel);
    }

    // Opens a menu under the view button, by its right edge, inside the panel when there is one
    private static void open(CreatorScreen screen, UiMenu menu, Rect button, @Nullable Rect panel) {
        menu.within(panel);
        menu.place(button, screen.width, screen.height);
        int menuX = Math.max(0, button.right() - menu.bounds().width());
        screen.openMenu(menu, new Rect(menuX, button.y(), button.width(), button.height()));
    }

    /**
     * "View ⌄"'s menu, with the skins as a group of their own or, folded, behind one item that
     * opens them.
     */
    private UiMenu menu(boolean foldSkins) {
        UiMenu menu = new UiMenu();
        menu.radio(CreatorScreen.tr("preview.loose"), mode == Mode.LOOSE, () -> setMode(Mode.LOOSE));
        menu.radio(CreatorScreen.tr("preview.in_box"), mode == Mode.IN_BOX, () -> setMode(Mode.IN_BOX));
        menu.radio(CreatorScreen.tr("preview.in_collection"), mode == Mode.IN_COLLECTION,
                () -> setMode(Mode.IN_COLLECTION));
        FigureDefinition figure = currentFigure();
        if (figure != null) {
            menu.separator();
            boolean canSit = !figure.getRenderOptions().poseLocked();
            menu.radio(CreatorScreen.tr("preview.stand"), !sitting || !canSit, () -> setSitting(false));
            if (canSit) {
                menu.radio(CreatorScreen.tr("preview.sit"), sitting, () -> setSitting(true));
            } else {
                menu.radioDisabled(CreatorScreen.tr("preview.sit"), false, CreatorScreen.tr("preview.no_sit"));
            }
        }
        if (mode == Mode.IN_COLLECTION) {
            menu.separator();
            menu.radio(CreatorScreen.tr("preview.discovered"), discovered, () -> setDiscovered(true));
            menu.radio(CreatorScreen.tr("preview.undiscovered"), !discovered, () -> setDiscovered(false));
        }
        menu.separator();
        menu.radio(CreatorScreen.tr("preview.light_day"), !isNight(), () -> setNight(false));
        menu.radio(CreatorScreen.tr("preview.light_night"), isNight(), () -> setNight(true));
        menu.radio(CreatorScreen.tr("preview.spin"), isSpinning(), () -> setSpinning(!isSpinning()));
        if (!variants.isEmpty()) {
            menu.separator();
            if (foldSkins) {
                Component current = skinIndex == 0 ? CreatorScreen.tr("figures.variant_base")
                        : Component.literal(variants.get(skinIndex - 1));
                menu.item(CreatorScreen.tr("figures.variant"), current, this::openSkinMenu);
            } else {
                skinItems(menu);
            }
        }
        menu.highlight(mode.ordinal());
        return menu;
    }

    // The skins: a heading, the base, then each variant by name, the one shown checked
    private void skinItems(UiMenu menu) {
        menu.heading(CreatorScreen.tr("figures.variant"));
        menu.radio(CreatorScreen.tr("figures.variant_base"), skinIndex == 0, () -> setSkinIndex(0));
        for (int i = 0; i < variants.size(); i++) {
            int index = i + 1;
            menu.radio(Component.literal(variants.get(i)), skinIndex == index, () -> setSkinIndex(index));
        }
    }

    // The skins' own menu, opened from the folded "Variant" item where the whole would not fit
    private void openSkinMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreatorScreen screen)) {
            return;
        }
        UiMenu menu = new UiMenu();
        skinItems(menu);
        menu.highlight(skinIndex + 1);
        open(screen, menu, this.viewButton.bounds(), within != null ? within.get() : null);
    }

    // Resolving

    @Nullable
    private FigureCollection collection() {
        return collectionId != null ? CollectionRegistry.getCollection(collectionId).orElse(null) : null;
    }

    @Nullable
    private FigureDefinition currentFigure() {
        FigureCollection collection = collection();
        return collection != null && figureId != null ? collection.getFigure(figureId).orElse(null) : null;
    }

    private FigureEntry entry(FigureCollection collection) {
        if (entry == null || !collection.getId().equals(entryCollectionId)) {
            entry = new FigureEntry(List.of(), collection.getId());
            entryCollectionId = collection.getId();
        }
        entry.setPreviewScale(collection.isPlush() ? PLUSH_SCALE : 1.0f);
        return entry;
    }

    private ItemStack box(FigureCollection collection, FigureDefinition figure) {
        String key = collection.getId() + ":" + figure.getId() + "#" + skinIndex;
        if (!key.equals(boxKey)) {
            box = boxStack(collection.getId(), figure.getId(), false, skinIndex);
            boxKey = key;
        }
        return box;
    }

    // The pose to show: sitting is the last of a figure's poses, and a model without one stands
    private int pose(FigureDefinition figure) {
        FigureDefinition.RenderOptions options = figure.getRenderOptions();
        return sitting && !options.poseLocked() ? options.poseCount() - 1 : 0;
    }

    // Layout

    @Override
    protected void layout() {
        super.layout();
        int buttonWidth = Math.min(viewButton.naturalWidth(), Math.max(0, width - 2 * INSET));
        viewButton.at(x + width - INSET - buttonWidth, y + INSET, buttonWidth, UiButton.SMALL_HEIGHT);
    }

    private int floor() {
        return y + height - FLOOR_INSET;
    }

    // Drawing

    @Override
    protected void drawScene(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FigureCollection collection = collection();
        FigureDefinition figure = currentFigure();
        int colour = collectionColour(collection, 0xFF3A3A3A);
        if (mode == Mode.IN_COLLECTION && collection != null && figure != null) {
            drawInCollection(graphics, figure, entry(collection), colour, mouseX, mouseY, partialTick);
            return;
        }
        drawStage(graphics, colour);
        if (collection == null || figure == null) {
            Font font = Minecraft.getInstance().font;
            // In the middle of the room under the view button
            int middle = (y + TOP_ROOM + floor()) / 2;
            for (String line : Draw.fit(font, emptyText.getString(), width - 12, 3)) {
                Draw.centred(graphics, font, line, x + width / 2, middle - 4, width - 8, Theme.MUTED);
                middle += Theme.LINE_HEIGHT;
            }
            return;
        }
        if (models <= 0) {
            return;
        }
        if (mode == Mode.IN_BOX) {
            drawInBox(graphics, collection, figure);
        } else {
            drawLoose(graphics, collection, figure, entry(collection), partialTick);
        }
    }

    // The showcase's stage: the colour fading into the well, and the ledge along the bottom
    private void drawStage(GuiGraphics graphics, int colour) {
        graphics.fill(x, y, x + width, y + height, Surfaces.WELL);
        graphics.fillGradient(x, y, x + width, y + height * 3 / 4, darken(colour, 0.5f), Surfaces.WELL);
        int floor = floor();
        graphics.fill(x + 2, floor, x + width - 2, y + height - 2, FLOOR);
        graphics.fill(x + 2, floor, x + width - 2, floor + 1, FLOOR_EDGE);
    }

    // Whether the 1x figure stands beside the figure: in the loose view, beside one of another size
    private boolean referenceShown(FigureCollection collection, FigureDefinition figure) {
        if (!showReference || mode != Mode.LOOSE || models < 2) {
            return false;
        }
        FigureDefinition reference = collection.getFigure(CreatorPreview.REFERENCE_FIGURE).orElse(null);
        return reference != null && !reference.getId().equals(figure.getId()) && Math.abs(figureScale - 1.0f) > 0.01f;
    }

    // The figure's height against its cell at size 1 and its own size: a skin's shape, a model's
    // extent as its host gives it, or else the most a model is fitted to
    private float figureHeight(FigureDefinition figure) {
        boolean skin = FigureDefinition.DEFAULT_MODEL.equals(figure.getModelPath());
        return (skin ? SKIN_HEIGHT : extentHeight > 0 ? extentHeight : MODEL_HEIGHT) * figureScale;
    }

    private float figureWidth(FigureDefinition figure) {
        boolean skin = FigureDefinition.DEFAULT_MODEL.equals(figure.getModelPath());
        return (skin ? SKIN_WIDTH * SKIN_HEIGHT : extentWidth > 0 ? extentWidth : MODEL_WIDTH) * figureScale;
    }

    /**
     * The size of the loose view's figures, as the cell of the machine's grid they would fill: as
     * large as lets the taller of the figure and the 1x figure fill {@value #FILL_PERCENT} percent
     * of the stage, and no larger than lets the figure, or the two side by side, keep within the
     * stage's width. A skin is the default figure's shape; a model is as tall and as wide, turned,
     * as its host says, or else as the most it is fitted to: 15 pixels of 16 tall and 9 wide.
     */
    private int looseCell(FigureCollection collection, FigureDefinition figure, boolean withReference) {
        float previewScale = collection.isPlush() ? PLUSH_SCALE : 1.0f;
        float height = figureHeight(figure);
        float wide = figureWidth(figure);
        float tallest = Math.max(height, withReference ? SKIN_HEIGHT : 0.0f) * previewScale;
        float byHeight = FILL * (floor() - y) / Math.max(0.01f, tallest);
        float pair = (wide + SKIN_WIDTH * SKIN_HEIGHT) * previewScale;
        float byWidth = withReference ? Math.max(1.0f, width * WIDTH_PAIR - PAIR_GAP) / Math.max(0.01f, pair)
                : width * WIDTH_ALONE / Math.max(0.01f, wide * previewScale);
        return Math.max(16, Math.round(Math.min(byHeight, byWidth)));
    }

    private void drawLoose(GuiGraphics graphics, FigureCollection collection, FigureDefinition figure,
                           FigureEntry figures, float partialTick) {
        boolean withReference = referenceShown(collection, figure);
        int floor = floor();
        int size = looseCell(collection, figure, withReference);
        // A figure is cut to its cell, so the cell reaches the stage's top above the feet, and the
        // figures are drawn smaller in it as much: one of twice the usual size keeps its head
        int cell = Math.max(size, Math.round((floor + ON_LEDGE - y) / FEET));
        int cellY = floor + ON_LEDGE - Math.round(cell * FEET);
        figures.setConfiguration(size / (float) cell, 0.0f, FRONT_TURN, 0.0f, 0.0f, CAMERA_Y, 0.0f);
        figures.setPreviewYaw(yaw());
        int figureCenter = x + width / 2;
        if (withReference) {
            // Side by side across the stage, the 1x figure on the left, neither over the other
            float previewScale = collection.isPlush() ? PLUSH_SCALE : 1.0f;
            int referenceWidth = Math.round(SKIN_WIDTH * SKIN_HEIGHT * previewScale * size);
            int figureWidth = Math.round(figureWidth(figure) * previewScale * size);
            int left = x + (width - referenceWidth - PAIR_GAP - figureWidth) / 2;
            int referenceCenter = left + referenceWidth / 2;
            figureCenter = left + referenceWidth + PAIR_GAP + figureWidth / 2;
            FigureDefinition reference = collection.getFigure(CreatorPreview.REFERENCE_FIGURE).orElseThrow();
            figures.setPreviewPose(0);
            figures.setPreviewSkin(0);
            // In its own colours, neither greyed nor washed: a measure, not a figure of the collection
            drawFigure(graphics, figures, reference, referenceCenter - cell / 2, cellY, cell, partialTick, false);
            this.referenceLabelX = referenceCenter;
            this.referenceLabelY = floor + ON_LEDGE - Math.round(SKIN_HEIGHT * previewScale * size) - 13;
        }
        figures.setPreviewPose(pose(figure));
        figures.setPreviewSkin(skinIndex);
        drawFigure(graphics, figures, figure, figureCenter - cell / 2, cellY, cell, partialTick, greyed);
    }

    // The box standing on the ledge, as large as the stage under the view button allows
    private void drawInBox(GuiGraphics graphics, FigureCollection collection, FigureDefinition figure) {
        int floor = floor();
        float size = Math.max(8.0f, Math.min((width - 2 * INSET) * 0.8f, (floor - 4 - (y + TOP_ROOM)) / BOX_HEIGHT));
        drawBoxFront(graphics, box(collection, figure), yaw(), pose(figure), x + width / 2.0f,
                floor + 2 - size * BOX_MIDDLE, size);
    }

    // One cell of the machine's figure grid, on the collection's colour as the machine shows it
    private void drawInCollection(GuiGraphics graphics, FigureDefinition figure, FigureEntry figures, int colour,
                                  int mouseX, int mouseY, float partialTick) {
        graphics.fill(x, y, x + width, y + height, colour);
        int top = y + TOP_ROOM;
        int bottom = y + height - INSET;
        int cell = Math.max(16, Math.min(MAX_CELL, Math.min(bottom - top, width - 2 * INSET)));
        int cellX = x + (width - cell) / 2;
        int cellY = (top + bottom - cell) / 2;
        boolean shown = discovered && !greyed;
        boolean hovered = mouseX >= cellX && mouseX < cellX + cell && mouseY >= cellY && mouseY < cellY + cell;
        graphics.fill(cellX, cellY, cellX + cell, cellY + cell, shown ? CELL_DISCOVERED : CELL_UNDISCOVERED);
        if (hovered) {
            graphics.fill(cellX, cellY, cellX + cell, cellY + cell, shown ? 0x40FFFFFF : 0x60000000);
        }
        int border = figure.isSpecial() ? (shown ? SPECIAL_BORDER : SPECIAL_BORDER_UNDISCOVERED)
                : (shown ? BORDER_DISCOVERED : BORDER_UNDISCOVERED);
        Draw.outline(graphics, cellX, cellY, cell, cell, border);
        if (models > 0) {
            figures.setConfiguration(1.0f, 0.0f, FRONT_TURN, 0.0f, 0.0f, CAMERA_Y, 0.0f);
            figures.setPreviewYaw(yaw());
            figures.setPreviewPose(pose(figure));
            figures.setPreviewSkin(skinIndex);
            drawFigure(graphics, figures, figure, cellX, cellY, cell, partialTick, !shown);
        }
        Font font = Minecraft.getInstance().font;
        String name = Draw.cut(font, figure.getName(), cell - 4);
        graphics.drawString(font, name, cellX + (cell - font.width(name)) / 2, cellY + cell - font.lineHeight - 2,
                shown ? 0xFFFFFFFF : 0xFFC0C0C0, true);
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FigureCollection collection = collection();
        FigureDefinition figure = currentFigure();
        if (collection == null || figure == null || !referenceShown(collection, figure)) {
            return;
        }
        // The 1x figure's label over its head, below the view button where the two would meet
        Font font = Minecraft.getInstance().font;
        Component label = CreatorScreen.tr("preview.reference");
        int labelWidth = font.width(label);
        int labelX = Math.max(x + INSET + 2,
                Math.min(x + width - INSET - 2 - labelWidth, this.referenceLabelX - labelWidth / 2));
        int labelY = Math.max(y + INSET + 2, this.referenceLabelY);
        Rect button = this.viewButton.bounds();
        if (labelX + labelWidth + 2 > button.x() && labelY - 2 < button.bottom() + 2) {
            labelY = button.bottom() + 4;
        }
        label(graphics, font, label, labelX, labelY, labelWidth, Theme.MUTED);
    }

    // Files dropped on the view go to its hidden zone, laid over it

    @Override
    @Nullable
    public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        UiDropZone zone = this.dropZone;
        if (!visible() || zone == null) {
            return null;
        }
        zone.at(x, y, width, height);
        return zone.dropZones(mouseX, mouseY, zones);
    }
}
