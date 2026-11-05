package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A row of a collection's figures as small raised thumbnails, each showing its figure as the
 * machine does on a cell of the collection's colour, followed by a dashed "+" tile that adds a
 * figure. When the thumbnails do not fit, small arrows at the ends scroll the row, and so does the
 * mouse wheel over it, a thumbnail at a time; between the arrows only whole thumbnails show, the
 * room left over shared on both sides, and only the thumbnails in sight are drawn.
 *
 * <p>A thumbnail marks only what needs the operator: a red "!" for a problem, whose tooltip lists
 * them, a "?" for a figure that cannot be shown yet, and a retired figure in black and white on a
 * neutral grey cell under a light wash, still recognisable. The selected one has Quick Skin's
 * blue border.
 *
 * <p>Selection follows {@link UiCardGrid} on one row: a click selects a thumbnail, Ctrl and a
 * click adds or removes one, and Shift and a click selects every one from the last clicked. While
 * the strip has the keyboard, Left and Right move through them, going round at the ends (with
 * Shift they extend the selection instead), Ctrl+A selects them all, Enter or a double click
 * activates the current one and Escape brings a selection of several back to the current one.
 * Thumbnails are told apart across a rebuild by {@link #keys}. With {@link #onReorder} set,
 * dragging one more than 6 units shows a blue line where it would land, carrying the row along
 * near its ends at a steady speed, whatever the frame rate, and letting go there moves it.
 *
 * <p>Files dropped anywhere over the strip go to its {@link #filesZone}, the bulk add's, which
 * the strip lays over itself without drawing. Its models are the strip's share of the screen's
 * ({@link SidebarScreen.ModelBudget}): the selected thumbnail first, then the others in sight
 * from the left; one left without draws its figure's initial on its cell instead.
 *
 * @param <T> what a thumbnail shows, such as a figure of the draft
 */
public class UiThumbStrip<T> extends UiNode.Box implements SidebarScreen.Budgeted {
    /**
     * How one thumbnail looks.
     *
     * @param figureId the figure in the strip's collection to draw
     * @param name     the figure's name, whose initial a thumbnail without a model shows
     * @param complete whether the figure has every file it needs to be shown
     * @param retired  whether the figure is out of the draw
     * @param problems what is wrong with the figure, which a red "!" lists; empty when nothing is
     * @param tooltip  the thumbnail's tooltip, such as its name, kind and size
     */
    public record Look(String figureId, String name, boolean complete, boolean retired, List<Component> problems,
                       List<Component> tooltip) {
    }

    /** Moves a thumbnail to a new place in the row. */
    @FunctionalInterface
    public interface Reorder {
        /**
         * @param from the thumbnail's index before the move
         * @param to   its index after it, counted in the row with it moved
         */
        void move(int from, int to);
    }

    private static final String[] ARROW_LEFT = {"..#", ".#.", "#..", ".#.", "..#"};
    private static final int ARROW_WIDTH = 12;
    private static final int ARROW_GAP = 2;
    private static final int GAP = 6;
    private static final int DRAG_THRESHOLD = 6;
    private static final int EDGE = 12;
    // How fast a dragged thumbnail near an end carries the row, in units a second
    private static final double EDGE_SPEED = 160.0;
    private static final int LIP = 2;
    private static final int PROBLEM = 0xFFE04040;
    // A retired figure's cell: a neutral grey, and a wash that leaves its figure readable
    private static final int RETIRED_CELL = 0xFF5C5D61;
    private static final int RETIRED_WASH = 0x40000000;
    private static final int DRAGGED_WASH = 0x80000000;
    private static final int ADD_FACE = 0x28000000;
    private static final int ADD_FACE_HOVER = 0x40000000;
    // The machine screen's camera, as the figure grid draws figures
    private static final float CAMERA_YAW = 150.0f;
    private static final float CAMERA_Y = 15.0f;
    private static final float PLUSH_SCALE = 0.6f;
    // A figure is drawn larger than its cell and cut to it, so it fills the thumbnail
    private static final float FIGURE_SCALE = 1.3f;
    private static final float FEET = 0.80f;

    private final int thumbWidth;
    private final int thumbHeight;
    private final Function<T, Look> look;
    private Supplier<FigureCollection> collection = () -> null;
    private IntSupplier colour = () -> Surfaces.FACE;
    private Function<T, ?> keyOf = item -> item;
    private final List<T> items = new ArrayList<>();
    private final Set<Object> selectedKeys = new LinkedHashSet<>();
    @Nullable
    private Object anchorKey;
    private int cursor = -1;
    @Nullable
    private Consumer<List<T>> onSelection;
    @Nullable
    private Consumer<T> onActivate;
    @Nullable
    private Reorder onReorder;
    @Nullable
    private Component reorderBlocked;
    @Nullable
    private Runnable onAdd;
    @Nullable
    private Component addTooltip;
    @Nullable
    private Component addDisabled;
    @Nullable
    private UiDropZone filesZone;
    private int scroll;
    private int grantedModels = Integer.MAX_VALUE;
    // A press on a thumbnail, which a long enough drag turns into moving it
    private int pressedIndex = -1;
    private double pressX;
    private double pressY;
    private boolean dragging;
    private double dragX;
    // The time of the last frame, and the part of a unit the edge has carried the row so far
    private long lastFrame;
    private double edgeCarry;
    @Nullable
    private FigureEntry entry;
    @Nullable
    private String entryCollectionId;

    /**
     * @param look how each item's thumbnail looks, asked as it is drawn
     */
    public UiThumbStrip(int thumbWidth, int thumbHeight, Function<T, Look> look) {
        this.thumbWidth = thumbWidth;
        this.thumbHeight = thumbHeight;
        this.look = look;
        this.height = thumbHeight;
    }

    // Setting up

    /** The collection the thumbnails' figures are drawn from, such as the editor's preview of its draft. */
    public UiThumbStrip<T> collection(Supplier<FigureCollection> collection) {
        this.collection = collection;
        return this;
    }

    /** The collection's colour, opaque, which the thumbnails' cells are a dark shade of. */
    public UiThumbStrip<T> colour(IntSupplier colour) {
        this.colour = colour;
        return this;
    }

    /** How thumbnails are told apart across a rebuild, such as a figure's id; the item itself unless set. */
    public UiThumbStrip<T> keys(Function<T, ?> keyOf) {
        this.keyOf = keyOf;
        return this;
    }

    /** Told the selected items, in row order, whenever the operator changes them. */
    public UiThumbStrip<T> onSelection(@Nullable Consumer<List<T>> onSelection) {
        this.onSelection = onSelection;
        return this;
    }

    /** What Enter or a double click on a thumbnail does, such as giving its name field the keyboard. */
    public UiThumbStrip<T> onActivate(@Nullable Consumer<T> onActivate) {
        this.onActivate = onActivate;
        return this;
    }

    /**
     * Lets thumbnails be dragged to a new place, or, with null, says why they cannot be, in the
     * tooltip a drag shows instead.
     */
    public UiThumbStrip<T> onReorder(@Nullable Reorder onReorder, @Nullable Component whyNot) {
        this.onReorder = onReorder;
        this.reorderBlocked = onReorder == null ? whyNot : null;
        return this;
    }

    /** The "+" tile after the thumbnails, which adds a figure when clicked; null takes it away. */
    public UiThumbStrip<T> addTile(@Nullable Runnable onAdd, @Nullable Component tooltip) {
        this.onAdd = onAdd;
        this.addTooltip = tooltip;
        return this;
    }

    /** Disables the "+" tile and says why, as when the collection is full; null enables it again. */
    public void disableAdd(@Nullable Component reason) {
        this.addDisabled = reason;
    }

    /** The zone that takes files dropped anywhere over the strip, such as the bulk add's; null for none. */
    public UiThumbStrip<T> filesZone(@Nullable UiDropZone zone) {
        this.filesZone = zone;
        return this;
    }

    // Items and selection

    /**
     * Shows a new list of items, keeping the selection of every item still in it. The screen is
     * not told of a selection that shrank this way, since it made the change itself.
     */
    public void setItems(List<T> newItems) {
        Object currentKey = cursor >= 0 && cursor < items.size() ? keyOf.apply(items.get(cursor)) : null;
        items.clear();
        items.addAll(newItems);
        Set<Object> present = new LinkedHashSet<>();
        for (T item : items) {
            present.add(keyOf.apply(item));
        }
        selectedKeys.retainAll(present);
        if (anchorKey != null && !present.contains(anchorKey)) {
            anchorKey = null;
        }
        int oldCursor = cursor;
        cursor = currentKey == null ? -1 : indexOfKey(currentKey);
        if (cursor < 0 && currentKey != null) {
            // The current item is gone: the arrow keys go on from its old place
            cursor = Math.min(items.size() - 1, oldCursor);
        }
        pressedIndex = -1;
        dragging = false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    public List<T> items() {
        return List.copyOf(items);
    }

    /** The selected items in row order. */
    public List<T> selected() {
        List<T> chosen = new ArrayList<>();
        for (T item : items) {
            if (selectedKeys.contains(keyOf.apply(item))) {
                chosen.add(item);
            }
        }
        return chosen;
    }

    /** Selects these items without telling the screen; the first becomes the current one. */
    public void select(List<T> chosen) {
        selectedKeys.clear();
        for (T item : chosen) {
            selectedKeys.add(keyOf.apply(item));
        }
        if (!chosen.isEmpty()) {
            cursor = indexOfKey(keyOf.apply(chosen.get(0)));
            anchorKey = keyOf.apply(chosen.get(0));
        }
    }

    public void clearSelection() {
        selectedKeys.clear();
        anchorKey = null;
    }

    /** The item the arrow keys move from, or null. */
    @Nullable
    public T current() {
        return cursor >= 0 && cursor < items.size() ? items.get(cursor) : null;
    }

    /** Scrolls an item's thumbnail into sight. */
    public void reveal(T item) {
        int index = indexOfKey(keyOf.apply(item));
        if (index >= 0) {
            revealIndex(index);
        }
    }

    /**
     * Selects the thumbnail before or after the current one, going round at the ends, and tells
     * the screen, as the arrow keys do.
     */
    public void step(int delta) {
        step(delta, false);
    }

    private int indexOfKey(Object key) {
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(keyOf.apply(items.get(i)), key)) {
                return i;
            }
        }
        return -1;
    }

    // Layout: the thumbnails one step apart, then the "+" tile, scrolled within the view

    private int step() {
        return thumbWidth + GAP;
    }

    private int cells() {
        return items.size() + (onAdd != null ? 1 : 0);
    }

    private int rowWidth() {
        return cells() == 0 ? 0 : cells() * step() - GAP;
    }

    private boolean overflows() {
        return rowWidth() > width;
    }

    // The room between the arrows of a row too long for the strip
    private int arrowRoom() {
        return Math.max(0, width - 2 * (ARROW_WIDTH + ARROW_GAP));
    }

    // The view's width: all of the strip, or as many whole thumbnails as fit between the arrows
    private int viewWidth() {
        if (!overflows()) {
            return width;
        }
        int whole = Math.max(1, (arrowRoom() + GAP) / step());
        return Math.min(arrowRoom(), whole * step() - GAP);
    }

    private int viewLeft() {
        return overflows() ? x + ARROW_WIDTH + ARROW_GAP + (arrowRoom() - viewWidth()) / 2 : x;
    }

    private int viewRight() {
        return viewLeft() + viewWidth();
    }

    private int maxScroll() {
        return Math.max(0, rowWidth() - viewWidth());
    }

    private void scrollBy(int amount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + amount));
    }

    // The scroll at the nearest whole thumbnail, so none shows cut at an end
    private int snapped(int position) {
        int whole = Math.round(position / (float) step()) * step();
        return Math.max(0, Math.min(maxScroll(), whole));
    }

    private int cellX(int index) {
        return viewLeft() - scroll + index * step();
    }

    private boolean inSight(int index) {
        int left = cellX(index);
        return left + thumbWidth > viewLeft() && left < viewRight();
    }

    private void revealIndex(int index) {
        int left = index * step();
        int view = viewRight() - viewLeft();
        if (left < scroll) {
            scroll = left;
        } else if (left + thumbWidth > scroll + view) {
            scroll = left + thumbWidth - view;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    // The cell under a point: an item's index, the item count for the "+" tile, or -1
    private int cellAt(double mouseX, double mouseY) {
        if (mouseY < y || mouseY >= y + thumbHeight || mouseX < viewLeft() || mouseX >= viewRight()) {
            return -1;
        }
        double along = mouseX - viewLeft() + scroll;
        int index = (int) Math.floor(along / step());
        if (index < 0 || index >= cells() || along - index * step() >= thumbWidth) {
            return -1;
        }
        if (index == items.size() && mouseY >= y + thumbHeight - 3) {
            // The "+" tile is a little shorter than the thumbnails
            return -1;
        }
        return index;
    }

    // Where a dragged thumbnail would land: the gap nearest the pointer, from 0 before the first
    // to the item count after the last
    private int slotAt(double mouseX) {
        int slot = (int) Math.round((mouseX - viewLeft() + scroll) / (double) step());
        return Math.max(0, Math.min(items.size(), slot));
    }

    @Override
    public void requestModels(SidebarScreen.ModelBudget budget) {
        // No more than can be in sight at once, partly cut at both ends
        int inSight = width / step() + 2;
        budget.request(SidebarScreen.ModelBudget.Rank.STRIP, Math.min(items.size(), inSight),
                granted -> this.grantedModels = granted);
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        long now = Util.getMillis();
        double seconds = lastFrame == 0 ? 0.0 : Math.min(0.1, (now - lastFrame) / 1000.0);
        lastFrame = now;
        int direction = !dragging ? 0 : dragX < viewLeft() + EDGE ? -1 : dragX > viewRight() - EDGE ? 1 : 0;
        if (direction != 0) {
            // Near an end, a dragged thumbnail carries the row along, by the time since the last frame
            edgeCarry += direction * EDGE_SPEED * seconds;
            int whole = (int) edgeCarry;
            if (whole != 0) {
                scrollBy(whole);
                edgeCarry -= whole;
            }
        } else {
            edgeCarry = 0.0;
        }
        scroll = dragging ? Math.max(0, Math.min(maxScroll(), scroll)) : snapped(scroll);
        Font font = Minecraft.getInstance().font;
        boolean[] models = modelsInSight();
        int hovered = dragging ? -1 : cellAt(mouseX, mouseY);
        UiScrollPane.clip(graphics, viewLeft(), y - 2, viewRight(), y + thumbHeight + 2);
        try {
            for (int i = 0; i < items.size(); i++) {
                if (inSight(i)) {
                    drawThumb(graphics, font, i, i == hovered, models[i], mouseX, mouseY, partialTick);
                }
            }
            if (onAdd != null && inSight(items.size())) {
                drawAdd(graphics, cellX(items.size()), hovered == items.size());
            }
            if (dragging && onReorder != null) {
                drawDropLine(graphics);
            }
        } finally {
            graphics.disableScissor();
        }
        if (overflows()) {
            drawArrow(graphics, x, ARROW_LEFT, scroll > 0, mouseX, mouseY);
            drawArrow(graphics, x + width - ARROW_WIDTH, UiSideRow.CHEVRON_RIGHT, scroll < maxScroll(), mouseX, mouseY);
        }
        if (dragging && reorderBlocked != null) {
            UiTooltip.set(SidebarScreen.tooltipLines(reorderBlocked));
        }
    }

    // Which thumbnails in sight draw their model: the selected one first, then the others from the left
    private boolean[] modelsInSight() {
        boolean[] allowed = new boolean[items.size()];
        int left = grantedModels;
        int first = -1;
        if (cursor >= 0 && cursor < items.size() && isSelected(cursor) && inSight(cursor)) {
            first = cursor;
        } else {
            for (int i = 0; i < items.size(); i++) {
                if (isSelected(i) && inSight(i)) {
                    first = i;
                    break;
                }
            }
        }
        if (first >= 0 && left > 0) {
            allowed[first] = true;
            left--;
        }
        for (int i = 0; i < items.size() && left > 0; i++) {
            if (!allowed[i] && inSight(i)) {
                allowed[i] = true;
                left--;
            }
        }
        return allowed;
    }

    private boolean isSelected(int index) {
        return selectedKeys.contains(keyOf.apply(items.get(index)));
    }

    private void drawThumb(GuiGraphics graphics, Font font, int index, boolean hovered, boolean withModel,
                           int mouseX, int mouseY, float partialTick) {
        Look shown = look.apply(items.get(index));
        boolean selected = isSelected(index);
        int left = cellX(index);
        int border = selected ? Theme.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        Draw.raised(graphics, left, y, thumbWidth, thumbHeight, border, hovered ? Surfaces.FACE_HOVER : Surfaces.FACE,
                LIP);
        if (selected) {
            Draw.outline(graphics, left + 1, y + 1, thumbWidth - 2, thumbHeight - 4, Theme.SELECTED);
        }
        int cellX = left + 3;
        int cellY = y + 3;
        int cellWidth = thumbWidth - 6;
        int cellHeight = thumbHeight - 9;
        graphics.fill(cellX, cellY, cellX + cellWidth, cellY + cellHeight,
                shown.retired() ? RETIRED_CELL : UiViewport.darken(colour.getAsInt(), 0.4f));
        FigureCollection from = collection.get();
        FigureDefinition figure = from != null ? from.getFigure(shown.figureId()).orElse(null) : null;
        boolean drawable = figure != null && shown.complete();
        if (drawable && withModel) {
            FigureEntry figures = entry(from);
            figures.setPreviewScale(from.isPlush() ? PLUSH_SCALE : 1.0f);
            int size = Math.round(cellHeight * FIGURE_SCALE);
            int feet = cellY + cellHeight - 2;
            UiScrollPane.clip(graphics, cellX, cellY, cellX + cellWidth, cellY + cellHeight);
            try {
                UiViewport.drawFigure(graphics, figures, figure, left + (thumbWidth - size) / 2,
                        feet - Math.round(size * FEET), size, partialTick, shown.retired());
            } finally {
                graphics.disableScissor();
            }
        }

        // The marks go above the model: a later stratum from 1.21.6, depth before it
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        try {
            int middleX = cellX + cellWidth / 2;
            float middleY = cellY + cellHeight / 2.0f;
            if (!drawable) {
                Draw.centred(graphics, font, "?", middleX, Math.round(middleY) - 4, cellWidth, Theme.FAINT);
            } else if (!withModel) {
                PixelFont.drawCentered(graphics, Component.literal(initial(shown.name())), middleX, middleY,
                        cellWidth - 2, 1.0f, shown.retired() ? Theme.FAINT : Theme.TEXT_SOFT, true);
            }
            if (shown.retired()) {
                graphics.fill(cellX, cellY, cellX + cellWidth, cellY + cellHeight, RETIRED_WASH);
            }
            boolean overProblem = false;
            if (!shown.problems().isEmpty()) {
                int markX = cellX;
                int markY = cellY + cellHeight - 9;
                graphics.fill(markX, markY, markX + 9, markY + 9, PROBLEM);
                Draw.string(graphics, font, "!", markX + 4 - font.width("!") / 2 + 1, markY + 1, Theme.TEXT);
                overProblem = hovered && mouseX >= markX && mouseX < markX + 9 && mouseY >= markY
                        && mouseY < markY + 9;
            }
            if (dragging && index == pressedIndex) {
                graphics.fill(left, y, left + thumbWidth, y + thumbHeight, DRAGGED_WASH);
            }
            if (hovered) {
                if (overProblem) {
                    UiTooltip.set(shown.problems());
                } else if (!shown.tooltip().isEmpty()) {
                    UiTooltip.set(shown.tooltip());
                }
            }
        } finally {
            GuiPose.pop(graphics);
        }
    }

    // The first letter of a name, in capitals, for a thumbnail without a model
    private static String initial(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return "?";
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    private FigureEntry entry(FigureCollection from) {
        if (entry == null || !from.getId().equals(entryCollectionId)) {
            entry = new FigureEntry(List.of(), from.getId());
            entry.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, 0.0f, CAMERA_Y, 0.0f);
            entryCollectionId = from.getId();
        }
        return entry;
    }

    // The dashed "+" tile, a little shorter than the thumbnails
    private void drawAdd(GuiGraphics graphics, int left, boolean hovered) {
        boolean enabled = addDisabled == null;
        boolean lit = hovered && enabled;
        int tileHeight = thumbHeight - 3;
        graphics.fill(left, y, left + thumbWidth, y + tileHeight, lit ? ADD_FACE_HOVER : ADD_FACE);
        Draw.dashed(graphics, left, y, thumbWidth, tileHeight,
                !enabled ? Surfaces.BORDER_DISABLED : lit ? Surfaces.BORDER_HOVER : Surfaces.BORDER, 3);
        PixelIcon.drawCentered(graphics, PixelIcon.PLUS, left, y, thumbWidth, tileHeight,
                !enabled ? Theme.TEXT_DISABLED : lit ? Theme.TEXT : Theme.MUTED);
        if (hovered) {
            Component tip = enabled ? addTooltip : addDisabled;
            if (tip != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(tip));
            }
        }
    }

    private void drawDropLine(GuiGraphics graphics) {
        int slot = slotAt(dragX);
        int lineX = viewLeft() - scroll + slot * step() - GAP / 2 - 1;
        graphics.fill(lineX, y - 2, lineX + 2, y + thumbHeight + 2, Theme.SELECTED);
        graphics.fill(lineX - 2, y - 2, lineX + 4, y, Theme.SELECTED);
        graphics.fill(lineX - 2, y + thumbHeight, lineX + 4, y + thumbHeight + 2, Theme.SELECTED);
    }

    // A small raised button at one end of a row too long for the strip, greyed at that end
    private void drawArrow(GuiGraphics graphics, int left, String[] glyph, boolean enabled, int mouseX, int mouseY) {
        boolean lit = enabled && mouseX >= left && mouseX < left + ARROW_WIDTH && mouseY >= y
                && mouseY < y + thumbHeight;
        int border = !enabled ? Surfaces.BORDER_DISABLED : lit ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        int face = !enabled ? Surfaces.FACE_DISABLED : lit ? Surfaces.FACE_HOVER : Surfaces.FACE;
        int faceBottom = Draw.raised(graphics, left, y, ARROW_WIDTH, thumbHeight, border, face, LIP);
        PixelIcon.drawCentered(graphics, glyph, left, y, ARROW_WIDTH, faceBottom - y,
                enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        pressedIndex = -1;
        dragging = false;
        if (!visible() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        boolean onArrow = click.x() < x + ARROW_WIDTH || click.x() >= x + width - ARROW_WIDTH;
        if (overflows() && click.left() && onArrow) {
            int amount = click.x() < x + ARROW_WIDTH ? -step() : step();
            if (amount < 0 ? scroll > 0 : scroll < maxScroll()) {
                UiSounds.play(ModSounds.CLICK);
                scrollBy(amount);
            }
            return true;
        }
        int index = cellAt(click.x(), click.y());
        if (index < 0 || !click.left()) {
            return true;
        }
        if (index == items.size()) {
            if (onAdd != null && addDisabled == null) {
                UiSounds.play(ModSounds.CLICK);
                onAdd.run();
            }
            return true;
        }
        T item = items.get(index);
        if (click.doubleClick() && !click.ctrl() && !click.shift()) {
            if (onActivate != null) {
                onActivate.accept(item);
            }
            return true;
        }
        choose(index, click.ctrl(), click.shift());
        if (!click.ctrl() && !click.shift()) {
            pressedIndex = index;
            pressX = click.x();
            pressY = click.y();
            dragX = click.x();
        }
        return true;
    }

    // Selects as a click or an arrow key does: alone, toggled with Ctrl, a range with Shift
    private void choose(int index, boolean ctrl, boolean shift) {
        Object key = keyOf.apply(items.get(index));
        int anchor = anchorKey == null ? -1 : indexOfKey(anchorKey);
        if (shift && anchor >= 0) {
            if (!ctrl) {
                selectedKeys.clear();
            }
            for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
                selectedKeys.add(keyOf.apply(items.get(i)));
            }
        } else if (ctrl) {
            if (!selectedKeys.remove(key)) {
                selectedKeys.add(key);
            }
            anchorKey = key;
        } else {
            selectedKeys.clear();
            selectedKeys.add(key);
            anchorKey = key;
        }
        cursor = index;
        selectionChanged();
    }

    private void selectionChanged() {
        if (onSelection != null) {
            onSelection.accept(selected());
        }
    }

    private void step(int delta, boolean extend) {
        if (items.isEmpty()) {
            return;
        }
        int from = cursor >= 0 && cursor < items.size() ? cursor : -1;
        int next;
        if (from < 0) {
            next = delta < 0 ? items.size() - 1 : 0;
        } else if (extend) {
            next = Math.max(0, Math.min(items.size() - 1, from + delta));
        } else {
            next = Math.floorMod(from + delta, items.size());
        }
        choose(next, false, extend);
        revealIndex(next);
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (pressedIndex < 0) {
            return false;
        }
        this.dragX = click.x();
        if (!dragging && Math.hypot(click.x() - pressX, click.y() - pressY) > DRAG_THRESHOLD) {
            dragging = true;
        }
        return true;
    }

    @Override
    public boolean release(UiInput.Click click) {
        if (pressedIndex < 0) {
            return false;
        }
        int from = pressedIndex;
        boolean moved = dragging;
        pressedIndex = -1;
        dragging = false;
        scroll = snapped(scroll);
        if (moved && onReorder != null && from < items.size()) {
            int slot = slotAt(click.x());
            // The thumbnail leaves its own place first, so a later slot is one lower
            int to = slot > from ? slot - 1 : slot;
            if (to != from) {
                // Moved here at once, so it stays where it was dropped and stays current while the
                // screen takes the move; its next list replaces this one either way
                items.add(to, items.remove(from));
                cursor = to;
                onReorder.move(from, to);
            }
        }
        return true;
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (!visible() || !overflows() || amount == 0) {
            return false;
        }
        // The vertical wheel, the one every version reports, moves the row a thumbnail at a time
        scrollBy(amount > 0 ? -step() : step());
        return true;
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (items.isEmpty() || !CreatorScreen.hasKeyboard(this)) {
            return false;
        }
        if (key.is(InputConstants.KEY_LEFT) || key.is(InputConstants.KEY_RIGHT)) {
            step(key.is(InputConstants.KEY_LEFT) ? -1 : 1, key.shift());
            return true;
        }
        if (key.selectAll()) {
            for (T item : items) {
                selectedKeys.add(keyOf.apply(item));
            }
            selectionChanged();
            return true;
        }
        if (key.enter()) {
            T item = current();
            if (item != null && onActivate != null) {
                onActivate.accept(item);
            }
            return item != null;
        }
        if (key.escape() && selectedKeys.size() > 1) {
            T item = current();
            if (item == null) {
                clearSelection();
                selectionChanged();
            } else {
                choose(cursor, false, false);
            }
            return true;
        }
        return false;
    }

    /** Files dropped anywhere over the strip go to its zone, which is laid over the strip unseen. */
    @Override
    @Nullable
    public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        UiDropZone zone = filesZone;
        if (!visible() || zone == null) {
            return null;
        }
        zone.at(x, y, width, height);
        if (!zones.contains(zone)) {
            zones.add(zone);
        }
        return bounds().contains(mouseX, mouseY) ? zone : null;
    }
}
