package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The showcase of a collection: its box and a row of its figures standing on a ledge, as the
 * machine hands them out, on a glow of the collection's colour that fades into the dark of a
 * well. The box comes first, turned a little, holding a figure and as tall as the figures, then
 * as many figures as the width holds; when the collection has more, the last place is a raised
 * "+N" tile for the rest, which the screen can open. The figures are sized so that five stand
 * beside the box (three when the content is narrow), and no taller than the stage lets them; the
 * row starts at the stage's left, a collection with fewer figures leaving the room on the right.
 * {@link #preferredHeight} gives a screen the height at which such a row fills the stage.
 *
 * <p>The models come only from the collection the game has registered, so a collection not
 * registered shows the glow alone, and one with no figure its box alone with a faint note beside
 * it. An archived collection is shown greyed: the screen gives its grey band colour, the
 * figures are drawn in black and white under a wash that leaves them recognisable, and the box
 * is the plain light grey one, empty, since a box cannot be drawn in black and white. The box
 * takes the screen's first model and the figures the next ones
 * ({@link SidebarScreen.ModelBudget}); with none left, a model is not drawn.
 */
public class UiStage extends UiNode.Box implements SidebarScreen.Budgeted {
    /** The box's turn, a little to the left of its front, as in the machine's showcase. */
    public static final float BOX_YAW = 335.0f;
    private static final int MAX_FIGURES = 8;
    // How many figures the size is chosen for, beside the box, on a wide and a narrow content
    private static final int WIDE_FIGURES = 5;
    private static final int NARROW_FIGURES = 3;
    // A cell's size against the box's (which then stands as tall as a figure) and against its step
    private static final float BOX_RATIO = 0.66f;
    private static final float STEP_RATIO = 0.45f;
    // Five figures by the box reach the widest content panel's width at about 234; a tall
    // canvas lets the figures grow with the height up to that
    private static final int MIN_CELL = 60;
    private static final int MAX_CELL = 240;
    private static final int MAX_NARROW_CELL = 110;
    // A cell is at most this much shorter than the stage, which leaves the figures' heads clear
    private static final int HEAD_ROOM = 12;
    // The stage's sides, and the gap between the box and the first figure
    private static final int SIDE_ROOM = 16;
    private static final int BOX_GAP = 12;
    private static final int FLOOR_INSET = 6;
    private static final int FLOOR = 0xFF262729;
    private static final int FLOOR_EDGE = 0xFF3C3D40;
    // Light enough that the figures in black and white stay recognisable, as a retired thumbnail's
    private static final int GREYED_WASH = 0x66000000;
    // The machine screen's camera, as the figure grid draws figures
    private static final float CAMERA_YAW = 150.0f;
    private static final float CAMERA_Y = 15.0f;
    private static final float PLUSH_SCALE = 0.6f;
    // Where a figure's feet are in its cell, and a box's middle above the floor, against their size
    private static final float FEET = 0.80f;
    private static final float BOX_MIDDLE = 0.56f;

    private final String collectionId;
    @Nullable
    private final String boxFigureId;
    private final List<String> figureIds;
    private final int colour;
    private boolean narrow;
    private boolean greyed;
    @Nullable
    private Component emptyNote;
    private IntFunction<Component> moreLabel = count -> Component.literal("+" + count);
    @Nullable
    private Component moreTooltip;
    @Nullable
    private Runnable onMore;
    @Nullable
    private List<Component> tooltip;
    private int boxModels = 1;
    private int figureModels = Integer.MAX_VALUE;
    private ItemStack box = ItemStack.EMPTY;
    @Nullable
    private FigureEntry entry;
    // The "+N" tile as last drawn, for its clicks and tooltip
    private Rect moreTile = Rect.EMPTY;

    /**
     * @param boxFigureId the figure the box holds, or null for an empty box
     * @param figureIds   the figures in the order they stand, those still in the draw first
     * @param colour      the collection's colour, opaque, or its grey for an archived one
     */
    public UiStage(String collectionId, @Nullable String boxFigureId, List<String> figureIds, int colour) {
        this.collectionId = collectionId;
        this.boxFigureId = boxFigureId;
        this.figureIds = List.copyOf(figureIds);
        this.colour = colour;
    }

    /** Draws the models a little smaller, for a narrow content panel. */
    public UiStage narrow(boolean narrow) {
        this.narrow = narrow;
        return this;
    }

    /** Shows the collection greyed, as an archived one. */
    public UiStage greyed(boolean greyed) {
        this.greyed = greyed;
        return this;
    }

    /** The faint note beside the box of a collection with no figure. */
    public UiStage emptyNote(@Nullable Component note) {
        this.emptyNote = note;
        return this;
    }

    /**
     * What the "+N" tile says, given how many figures it stands for, its tooltip and what a
     * click on it does, such as opening the collection's figures.
     */
    public UiStage more(IntFunction<Component> label, @Nullable Component tooltip, @Nullable Runnable onMore) {
        this.moreLabel = label;
        this.moreTooltip = tooltip;
        this.onMore = onMore;
        return this;
    }

    /** The stage's tooltip, such as the collection's size. */
    public UiStage tooltip(@Nullable List<Component> lines) {
        this.tooltip = lines == null ? null : List.copyOf(lines);
        return this;
    }

    // Layout: the size lets five figures (three when narrow) stand by the box within the width,
    // no taller than the height lets them; the figures that fit then follow the width

    // The figures' cell for a width alone, as large as five (or three) of them by the box allow
    private static int cellForWidth(int width, boolean narrow) {
        float row = BOX_RATIO + (narrow ? NARROW_FIGURES : WIDE_FIGURES) * STEP_RATIO;
        return (int) ((width - SIDE_ROOM - BOX_GAP) / row);
    }

    /**
     * The height at which a stage of this width shows its figures as large as its width lets
     * them, with room over their heads; a screen keeps its stage about this tall (a fifth
     * taller at most), so the glow over the figures stays a glow rather than an empty band.
     */
    public static int preferredHeight(int width, boolean narrow) {
        return SidebarScreen.clamp(cellForWidth(width, narrow), MIN_CELL, narrow ? MAX_NARROW_CELL : MAX_CELL)
                + HEAD_ROOM;
    }

    private int cell() {
        return SidebarScreen.clamp(Math.min(height - HEAD_ROOM, cellForWidth(width, narrow)), MIN_CELL,
                narrow ? MAX_NARROW_CELL : MAX_CELL);
    }

    // Rounded down, as the cell for the width is, so the places it was chosen for always fit
    private int boxSize() {
        return Math.max(24, (int) (cell() * BOX_RATIO));
    }

    private int step() {
        return Math.max(1, (int) (cell() * STEP_RATIO));
    }

    /** How many places the width holds after the box, from 0 to 8. */
    public int places() {
        return SidebarScreen.clamp((width - SIDE_ROOM - BOX_GAP - boxSize()) / step(), 0, MAX_FIGURES);
    }

    // The figures standing: all of them when they fit, else one place fewer and the "+N" tile
    private List<String> standing() {
        int places = places();
        if (figureIds.size() <= places) {
            return figureIds;
        }
        return figureIds.subList(0, Math.max(0, places - 1));
    }

    // The figures the "+N" tile stands for; a stage with no place for the tile shows the box alone
    private int hiddenCount() {
        return places() == 0 ? 0 : figureIds.size() - standing().size();
    }

    @Override
    public void requestModels(SidebarScreen.ModelBudget budget) {
        budget.request(SidebarScreen.ModelBudget.Rank.PREVIEW, 1, granted -> this.boxModels = granted);
        budget.request(SidebarScreen.ModelBudget.Rank.SHOWCASE, standing().size(),
                granted -> this.figureModels = granted);
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible() || width <= 0 || height <= 0) {
            return;
        }
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x, y, right, bottom, Surfaces.WELL);
        graphics.fillGradient(x, y, right, y + height * 3 / 4, UiViewport.darken(colour, 0.5f), Surfaces.WELL);
        int floor = bottom - FLOOR_INSET;
        graphics.fill(x + 2, floor, right - 2, bottom - 2, FLOOR);
        graphics.fill(x + 2, floor, right - 2, floor + 1, FLOOR_EDGE);

        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        List<String> standing = standing();
        int hidden = hiddenCount();
        int boxSize = boxSize();
        int step = step();
        int cell = cell();
        // The row starts at the left, on the places the width holds, the room they leave shared
        // on both sides; a collection with fewer figures leaves the places on the right empty
        int places = places();
        int gridWidth = boxSize + (places > 0 ? BOX_GAP + places * step : 0);
        int rowLeft = x + SIDE_ROOM / 2 + Math.max(0, (width - SIDE_ROOM - gridWidth) / 2);
        int figuresLeft = rowLeft + boxSize + BOX_GAP;
        moreTile = Rect.EMPTY;
        if (collection != null) {
            UiScrollPane.clip(graphics, x + 2, y + 2, right - 2, bottom - 2);
            try {
                if (boxModels > 0) {
                    if (box.isEmpty()) {
                        box = greyed ? greyBox(collectionId) : UiViewport.boxStack(collectionId, boxFigureId, false);
                    }
                    UiViewport.drawBoxFront(graphics, box, BOX_YAW, 0, rowLeft + boxSize / 2.0f,
                            floor + 2 - boxSize * BOX_MIDDLE, boxSize);
                }
                drawFigures(graphics, collection, standing, figuresLeft, floor, cell, step, partialTick);
            } finally {
                graphics.disableScissor();
            }
        }

        // The wash, the tile and the note go above the models: a later stratum from 1.21.6,
        // depth before it, where a model would otherwise cover them
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        try {
            if (greyed) {
                graphics.fill(x + 2, y + 2, right - 2, bottom - 2, GREYED_WASH);
            }
            Font font = Minecraft.getInstance().font;
            if (collection != null && hidden > 0) {
                drawMore(graphics, figuresLeft + standing.size() * step, floor, step, cell, hidden, mouseX, mouseY);
            }
            if (collection != null && figureIds.isEmpty() && emptyNote != null) {
                // Beside the box, level with its middle
                int noteRoom = Math.max(1, right - 8 - figuresLeft);
                Draw.lines(graphics, font, emptyNote.getString(), figuresLeft,
                        Math.round(floor + 2 - boxSize * BOX_MIDDLE) - 4, noteRoom, 2, Theme.FAINT);
            }
            Draw.outline(graphics, x, y, width, height, Surfaces.OUTLINE);
            Draw.outline(graphics, x + 1, y + 1, width - 2, height - 2, Surfaces.BORDER);
        } finally {
            GuiPose.pop(graphics);
        }

        if (hovered(mouseX, mouseY) && !moreTile.contains(mouseX, mouseY) && tooltip != null && !tooltip.isEmpty()) {
            UiTooltip.set(tooltip);
        }
    }

    // A box of the collection in the plain light grey, which its own art and colour give way to,
    // holding no figure: the box and the figure inside it have no black-and-white texture
    private static ItemStack greyBox(String collectionId) {
        ItemStack stack = UiViewport.boxStack(collectionId, null, false);
        CompoundTag tag = BlockEntityItemData.read(stack);
        tag = tag != null ? tag.copy() : new CompoundTag();
        tag.putString("Color", PopBlockColor.LIGHT_GRAY.getSerializedName());
        BlockEntityItemData.write(stack, tag, "blockpops:box_block");
        return stack;
    }

    // Each figure stands in a place of the row, its feet on the ledge, as many as the budget allows
    private void drawFigures(GuiGraphics graphics, FigureCollection collection, List<String> standing, int left,
                             int floor, int cell, int step, float partialTick) {
        if (entry == null) {
            entry = new FigureEntry(List.of(), collectionId);
            entry.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, 0.0f, CAMERA_Y, 0.0f);
        }
        entry.setPreviewScale(collection.isPlush() ? PLUSH_SCALE : 1.0f);
        int models = figureModels;
        int cellY = floor + 1 - Math.round(cell * FEET);
        for (int i = 0; i < standing.size() && models > 0; i++) {
            FigureDefinition figure = collection.getFigure(standing.get(i)).orElse(null);
            if (figure == null) {
                continue;
            }
            int centre = left + i * step + step / 2;
            UiViewport.drawFigure(graphics, entry, figure, centre - cell / 2, cellY, cell, partialTick, greyed);
            models--;
        }
    }

    // A raised tile standing on the ledge in the last place, for the figures not shown
    private void drawMore(GuiGraphics graphics, int left, int floor, int step, int cell, int count, int mouseX,
                          int mouseY) {
        int size = SidebarScreen.clamp(step - 10, 26, 64);
        int tileX = left + (step - size) / 2;
        int tileY = floor - 2 - Math.min(size, Math.round(cell * 0.45f));
        int tileHeight = floor - 2 - tileY;
        moreTile = new Rect(tileX, tileY, size, tileHeight);
        boolean over = onMore != null && moreTile.contains(mouseX, mouseY);
        int faceBottom = Draw.raised(graphics, tileX, tileY, size, tileHeight,
                over ? Surfaces.BORDER_HOVER : Surfaces.BORDER, over ? Surfaces.FACE_HOVER : Surfaces.FACE);
        PixelFont.drawCentered(graphics, moreLabel.apply(count), tileX + size / 2, (tileY + faceBottom) / 2.0f,
                size - 6, 1.0f, Theme.TEXT, true);
        if (moreTile.contains(mouseX, mouseY) && moreTooltip != null) {
            UiTooltip.set(SidebarScreen.tooltipLines(moreTooltip));
        }
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !click.left() || onMore == null || !moreTile.contains(click.x(), click.y())) {
            return false;
        }
        UiSounds.play(ModSounds.CLICK);
        onMore.run();
        return true;
    }
}
