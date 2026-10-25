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
 * The live preview of one figure, as the figure inspector shows it, in one of three views:
 * <ul>
 *   <li>{@link Mode#LOOSE}: the figure standing on a pedestal, with the default figure at size
 *       1x beside it in black and white to show its size;</li>
 *   <li>{@link Mode#IN_BOX}: the figure in its collection's box, as the machine hands it out;</li>
 *   <li>{@link Mode#IN_COLLECTION}: the figure in a cell of the machine's figure grid, in colour
 *       once discovered and in black and white before, which a switch picks.</li>
 * </ul>
 * Switches at the top pick the view and the pose, standing or sitting; sitting is disabled for
 * a model whose poses have no sitting one. A figure with skin variants also gets "‹ name ›" on
 * the right under the top switches, which steps through its own skin and each variant, in every
 * view.
 * Dragging turns the figure (see {@link UiViewport}).
 *
 * <p>The figure is looked up by collection and figure id on every frame, through the collection
 * registry, so the view follows the creator's preview of a collection (see {@link CreatorPreview})
 * as soon as the screen shows it again. Figures are drawn with the machine screen's camera.
 */
public class UiFigureView extends UiViewport {
    /** What the view shows the figure in. */
    public enum Mode {
        LOOSE, IN_BOX, IN_COLLECTION
    }

    // The loose view draws figures at half the size the machine's grid would in a cell as tall,
    // so a figure of the largest size, twice the usual, still fits
    private static final float LOOSE_SCALE = 0.5f;
    // The machine screen's camera: turned 150 degrees, set down in an 80 unit cell
    private static final float CAMERA_YAW = 150.0f;
    private static final float CAMERA_X = 0.0f;
    private static final float CAMERA_Y = 15.0f;
    // A plush is about twice as wide as a figure, as the machine's grid shrinks it
    private static final float PLUSH_SCALE = 0.6f;
    private static final int PEDESTAL = 0xFF3A3A3A;
    private static final int PEDESTAL_TOP = 0xFF4A4A4A;
    private static final int BOX_BACKGROUND = 0xFF10141B;
    // The machine grid's cell look, from FigureEntry
    private static final int CELL_DISCOVERED = 0x30FFFFFF;
    private static final int CELL_UNDISCOVERED = 0x50000000;
    private static final int BORDER_DISCOVERED = 0x80FFFFFF;
    private static final int BORDER_UNDISCOVERED = 0x60808080;
    private static final int SPECIAL_BORDER = 0xE0BB8FCE;
    private static final int SPECIAL_BORDER_UNDISCOVERED = 0x80BB8FCE;
    private static final int MAX_CELL = 100;
    // How tall a box seen from the front and a little above is, against the size it is drawn at
    static final float BOX_HEIGHT = 1.15f;

    private final UiSegmented modes;
    private final UiSegmented poses;
    private final UiSegmented discovery;
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
    private final VariantSwitch variantSwitch = new VariantSwitch();
    private boolean showReference = true;
    @Nullable
    private Component caption;
    private Component emptyText = CreatorScreen.tr("preview.no_figure");
    // Draws the figures; made again for another collection, since it names its collection
    @Nullable
    private FigureEntry entry;
    @Nullable
    private String entryCollectionId;
    private ItemStack box = ItemStack.EMPTY;
    @Nullable
    private String boxKey;

    public UiFigureView() {
        this.modes = new UiSegmented(0, index -> this.mode = Mode.values()[index],
                CreatorScreen.tr("preview.loose"), CreatorScreen.tr("preview.in_box"),
                CreatorScreen.tr("preview.in_collection"));
        this.poses = new UiSegmented(0, index -> this.sitting = index == 1,
                CreatorScreen.tr("preview.stand"), CreatorScreen.tr("preview.sit"));
        this.discovery = new UiSegmented(0, index -> this.discovered = index == 0,
                CreatorScreen.tr("preview.discovered"), CreatorScreen.tr("preview.undiscovered"));
        this.controls.add(this.modes);
        this.controls.add(this.poses);
        this.controls.add(this.discovery);
        this.controls.add(this.variantSwitch);
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
        this.modes.select(mode.ordinal());
    }

    /** Hides or shows the view switch, for a screen that shows only one view. */
    public void setModeSwitch(boolean shown) {
        this.modes.setVisible(shown);
    }

    public boolean isSitting() {
        return sitting;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
        this.poses.select(sitting ? 1 : 0);
    }

    /** In the collection view, whether the figure shows as discovered, in colour. */
    public void setDiscovered(boolean discovered) {
        this.discovered = discovered;
        this.discovery.select(discovered ? 0 : 1);
    }

    /**
     * The names of the figure's skin variants, in order, for the switch that steps through them;
     * an empty list hides it. The skin shown stays when it still exists, else the figure's own.
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

    /** Whether the loose view shows the default figure at 1x beside the figure. */
    public void setShowReference(boolean showReference) {
        this.showReference = showReference;
    }

    /**
     * A note in the bottom right corner, left of the "Rotate" and "Light" buttons when they show,
     * such as "preview before saving"; null for none.
     */
    public void setCaption(@Nullable Component caption) {
        this.caption = caption;
    }

    /** What the view says while it has no figure to show, such as one that misses a file. */
    public void setEmptyText(Component emptyText) {
        this.emptyText = emptyText;
    }

    // Resolving

    @Nullable
    private FigureCollection collection() {
        return collectionId != null ? CollectionRegistry.getCollection(collectionId).orElse(null) : null;
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
        // A switch wider than the view narrows and cuts its labels rather than spill out of it
        int room = Math.max(0, width - 2 * INSET);
        modes.at(x + INSET, y + INSET, Math.min(modes.naturalWidth(), room), UiSegmented.HEIGHT);
        int posesWidth = Math.min(poses.naturalWidth(), room);
        poses.at(x + width - INSET - posesWidth, y + INSET, posesWidth, UiSegmented.HEIGHT);
        // A narrow view keeps the view switch and puts the pose switch under it
        if (modes.visible() && poses.bounds().x() < modes.bounds().right() + 4) {
            poses.at(x + INSET, y + INSET + UiSegmented.HEIGHT + 3, posesWidth, UiSegmented.HEIGHT);
        }
        // The discovery switch sits beside the buttons where it fits, otherwise above them
        int discoveryWidth = Math.min(discovery.naturalWidth(), room);
        int discoveryY = x + INSET + discoveryWidth + 4 <= buttonsLeft() ? y + height - INSET - UiSegmented.HEIGHT
                : buttonsTop() - 3 - UiSegmented.HEIGHT;
        discovery.at(x + INSET, discoveryY, discoveryWidth, UiSegmented.HEIGHT);
        discovery.setVisible(mode == Mode.IN_COLLECTION);
        // The variant switch sits on the right, beside the pose switch when that moved under the
        // view switch and leaves room, otherwise under it; the bottom corners keep the 1x label,
        // the discovery switch and the caption
        int switchWidth = Math.min(variantSwitch.naturalWidth(), room);
        int switchX = x + width - INSET - switchWidth;
        Rect pose = poses.bounds();
        boolean besidePoses = pose.y() > y + INSET && switchX >= pose.right() + 4;
        variantSwitch.at(switchX, besidePoses ? pose.y() + (UiSegmented.HEIGHT - VariantSwitch.HEIGHT) / 2
                : pose.bottom() + 3, switchWidth, VariantSwitch.HEIGHT);
        variantSwitch.setVisible(!variants.isEmpty());
        FigureDefinition figure = currentFigure();
        boolean canSit = figure != null && !figure.getRenderOptions().poseLocked();
        poses.disable(1, canSit ? null : CreatorScreen.tr("preview.no_sit"));
        poses.select(sitting && canSit ? 1 : 0);
    }

    // The room between the switches at the top and those at the bottom, for the models
    private int sceneTop() {
        int top = y + INSET;
        if (modes.visible()) {
            top = Math.max(top, modes.bounds().bottom());
        }
        if (variantSwitch.visible()) {
            top = Math.max(top, variantSwitch.bounds().bottom());
        }
        return Math.max(top, poses.bounds().bottom()) + 4;
    }

    private int sceneBottom() {
        int bottom = buttonsTop();
        if (caption != null) {
            // The caption sits in the bottom right corner, which the box would otherwise reach
            bottom = Math.min(bottom, y + height - INSET - 12);
        }
        if (discovery.visible()) {
            bottom = Math.min(bottom, discovery.bounds().y());
        }
        return bottom - 4;
    }

    @Nullable
    private FigureDefinition currentFigure() {
        FigureCollection collection = collection();
        return collection != null && figureId != null ? collection.getFigure(figureId).orElse(null) : null;
    }

    // Drawing

    @Override
    protected void drawScene(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FigureCollection collection = collection();
        FigureDefinition figure = collection != null && figureId != null
                ? collection.getFigure(figureId).orElse(null) : null;
        int colour = collectionColour(collection, 0xFF3A3A3A);
        if (collection == null || figure == null) {
            graphics.fill(x, y, x + width, y + height, BACKGROUND);
            Font font = Minecraft.getInstance().font;
            Draw.centred(graphics, font, emptyText.getString(), x + width / 2, y + height / 2 - 4, width - 8,
                    Theme.MUTED);
            return;
        }
        FigureEntry figures = entry(collection);
        switch (mode) {
            case LOOSE -> drawLoose(graphics, collection, figure, figures, colour, partialTick);
            case IN_BOX -> drawInBox(graphics, collection, figure, colour);
            case IN_COLLECTION -> drawInCollection(graphics, figure, figures, colour, mouseX, mouseY, partialTick);
        }
    }

    // A soft light of the collection's colour behind the figure, fading to near black
    private void drawGlow(GuiGraphics graphics, int colour, int background) {
        graphics.fill(x, y, x + width, y + height, background);
        graphics.fillGradient(x, y, x + width, y + height * 3 / 4, darken(colour, 0.45f), background);
    }

    private void drawLoose(GuiGraphics graphics, FigureCollection collection, FigureDefinition figure,
                           FigureEntry figures, int colour, float partialTick) {
        drawGlow(graphics, colour, BACKGROUND);
        int cell = Math.max(16, height - 4);
        int cellY = y + 2;
        // The figure stands where the machine's camera puts feet in a cell: lower than its middle
        int feetY = cellY + Math.round(cell * 0.6f + CAMERA_Y * cell / 80.0f);
        graphics.fill(x, feetY, x + width, feetY + 5, PEDESTAL);
        graphics.fill(x, feetY, x + width, feetY + 1, PEDESTAL_TOP);

        int centerX = x + width / 2;
        FigureDefinition reference = collection.getFigure(CreatorPreview.REFERENCE_FIGURE).orElse(null);
        boolean withReference = showReference && reference != null && !reference.getId().equals(figure.getId());
        int figureCenter = withReference ? centerX + cell / 8 : centerX;
        figures.setConfiguration(LOOSE_SCALE, 0.0f, CAMERA_YAW, 0.0f, CAMERA_X * LOOSE_SCALE, CAMERA_Y, 0.0f);
        figures.setPreviewYaw(yaw());
        if (withReference) {
            int referenceCenter = centerX - cell * 3 / 8;
            figures.setPreviewPose(0);
            figures.setPreviewSkin(0);
            drawFigure(graphics, figures, reference, referenceCenter - cell / 2, cellY, cell, partialTick, true);
            Font font = Minecraft.getInstance().font;
            String label = CreatorScreen.tr("preview.reference").getString();
            Draw.string(graphics, font, label, referenceCenter - font.width(label) / 2, feetY + 7, Theme.MUTED);
        }
        figures.setPreviewPose(pose(figure));
        figures.setPreviewSkin(skinIndex);
        drawFigure(graphics, figures, figure, figureCenter - cell / 2, cellY, cell, partialTick, greyed);
    }

    private void drawInBox(GuiGraphics graphics, FigureCollection collection, FigureDefinition figure, int colour) {
        drawGlow(graphics, colour, BOX_BACKGROUND);
        int top = sceneTop();
        int bottom = sceneBottom();
        // A box seen from a little above stands a little taller than the size it is drawn at
        float size = Math.max(8.0f, Math.min(width * 0.6f, (bottom - top) / BOX_HEIGHT));
        drawBoxFront(graphics, box(collection, figure), yaw(), pose(figure), x + width / 2.0f,
                (top + bottom) / 2.0f, size);
    }

    // One cell of the machine's figure grid, on the collection's colour as the machine shows it
    private void drawInCollection(GuiGraphics graphics, FigureDefinition figure, FigureEntry figures, int colour,
                                  int mouseX, int mouseY, float partialTick) {
        graphics.fill(x, y, x + width, y + height, colour);
        int top = sceneTop();
        int bottom = sceneBottom();
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
        figures.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, CAMERA_X, CAMERA_Y, 0.0f);
        figures.setPreviewYaw(yaw());
        figures.setPreviewPose(pose(figure));
        figures.setPreviewSkin(skinIndex);
        drawFigure(graphics, figures, figure, cellX, cellY, cell, partialTick, !shown);
        Font font = Minecraft.getInstance().font;
        String name = Draw.cut(font, figure.getName(), cell - 4);
        graphics.drawString(font, name, cellX + (cell - font.width(name)) / 2, cellY + cell - font.lineHeight - 2,
                shown ? 0xFFFFFFFF : 0xFFC0C0C0, true);
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (caption == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int right = Math.min(buttonsLeft() - 6, x + width - INSET - 2);
        int left = right - font.width(caption);
        // The 1x figure's label and the discovery switch keep the bottom left
        int clear = discovery.visible() ? discovery.bounds().right() + 6 : x + INSET + 2;
        if (left >= clear) {
            label(graphics, font, caption, left, y + height - INSET - 10, right - left, Theme.MUTED);
        }
    }

    /**
     * "‹ name ›": which skin the figure wears, its own or one of its variants. The arrows step
     * back and forward, going round at the ends, and a click on the name steps forward.
     */
    private final class VariantSwitch extends UiNode.Box {
        static final int HEIGHT = 14;
        private static final int ARROW = 12;

        private Component current() {
            return skinIndex == 0 ? CreatorScreen.tr("preview.variant_own")
                    : Component.literal(variants.get(skinIndex - 1));
        }

        int naturalWidth() {
            Font font = Minecraft.getInstance().font;
            int widest = font.width(CreatorScreen.tr("preview.variant_own"));
            for (String name : variants) {
                widest = Math.max(widest, font.width(name));
            }
            return 2 * ARROW + Math.min(widest, 110) + 8;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 2 * ARROW) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            // A dark see-through strip in the menus' border and outline, over the scene
            boolean over = hovered(mouseX, mouseY);
            graphics.fill(x, y, x + width, y + height, Surfaces.OUTLINE);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, over ? Surfaces.BORDER_HOVER : Surfaces.BORDER);
            graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, Surfaces.WELL);
            boolean overLeft = over && mouseX < x + ARROW;
            boolean overRight = over && mouseX >= x + width - ARROW;
            int textY = y + (height - 8) / 2 + 1;
            Draw.string(graphics, font, "‹", x + 4, textY, overLeft ? Theme.SELECTED_LIGHT : Theme.TEXT);
            Draw.string(graphics, font, "›", x + width - ARROW + 3, textY,
                    overRight ? Theme.SELECTED_LIGHT : Theme.TEXT);
            Draw.centred(graphics, font, current().getString(), x + width / 2, textY, width - 2 * ARROW,
                    skinIndex == 0 ? Theme.TEXT_SOFT : Theme.SELECTED_LIGHT);
            if (over) {
                UiTooltip.set(CreatorScreen.tr("preview.variant_tip", variants.size()));
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            int skins = variants.size() + 1;
            int step = click.x() < x + ARROW ? -1 : 1;
            skinIndex = ((skinIndex + step) % skins + skins) % skins;
            return true;
        }
    }
}
