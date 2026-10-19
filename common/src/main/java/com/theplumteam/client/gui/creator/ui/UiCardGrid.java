package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A scrolling grid of cards of one size, as many to a row as the width holds, such as the
 * collections of the overview or the figures of a collection. A {@link CardRenderer} draws each
 * card; the grid draws only the rows in sight, so a long list costs no more than a short one.
 *
 * <p>A click selects a card, Ctrl and a click adds or removes one, and Shift and a click selects
 * every card from the last one clicked. While the grid has the keyboard, the arrow keys move
 * through the cards with the same Shift rule, Ctrl+A selects them all, Escape clears the
 * selection, and Enter opens the current card, as a double click opens any. Cards are told
 * apart across a rebuild by {@link #keys}, so the selection survives the screen building its
 * cards again.
 *
 * <p>With {@link #onReorder} set, dragging a card more than 6 units shows a gold line where it
 * would land, and letting go there moves it; dragging near the top or bottom edge scrolls. An
 * optional last cell, such as a drop zone for more files, fills the rest of the last row.
 *
 * @param <T> what a card shows
 */
public class UiCardGrid<T> extends UiScrollPane {
    /** Draws one card. */
    @FunctionalInterface
    public interface CardRenderer<T> {
        void draw(GuiGraphics graphics, T item, Card card);
    }

    /** Gets first look at a click on a card, such as one on the card's own "..." button. */
    @FunctionalInterface
    public interface CardClick<T> {
        /** Returns whether the card used the click, which then leaves the selection alone. */
        boolean click(T item, Card card, UiInput.Click click);
    }

    /** Moves a card to a new place in the list. */
    @FunctionalInterface
    public interface Reorder {
        /**
         * @param from the card's index before the move
         * @param to   the card's index after it, counted in the list with the card moved
         */
        void move(int from, int to);
    }

    /**
     * Where a card is drawn and how it stands, in the grid's unscrolled units, as are the
     * pointer's coordinates.
     *
     * @param current  the card the arrow keys move from
     * @param dragged  the card being dragged to a new place
     * @param scrolled how far the grid is scrolled, which {@link #screenBounds} takes off
     */
    public record Card(int index, int x, int y, int width, int height, int mouseX, int mouseY, boolean hovered,
                       boolean selected, boolean current, boolean dragged, int scrolled) {
        public Rect bounds() {
            return new Rect(x, y, width, height);
        }

        /** The card on the screen, to anchor a menu or a tooltip to it. */
        public Rect screenBounds() {
            return new Rect(x, y - scrolled, width, height);
        }
    }

    private static final int DRAG_THRESHOLD = 6;
    private static final int EDGE = 12;
    private static final int EDGE_SPEED = 4;

    private final int cardWidth;
    private final int cardHeight;
    private final CardRenderer<T> renderer;
    private final List<T> items = new ArrayList<>();
    private final Set<Object> selectedKeys = new LinkedHashSet<>();
    private int gap = 4;
    private Function<T, ?> keyOf = item -> item;
    @Nullable
    private Object anchorKey;
    private int cursor = -1;
    @Nullable
    private Consumer<T> onActivate;
    @Nullable
    private Consumer<List<T>> onSelection;
    @Nullable
    private CardClick<T> onCardClick;
    @Nullable
    private Reorder onReorder;
    @Nullable
    private UiNode.Box lastCell;
    // A press on a card, which a long enough drag turns into moving it
    private int pressedIndex = -1;
    private double pressX;
    private double pressY;
    private boolean dragging;
    // Where the dragging pointer is on the screen, kept so scrolling moves the drop line with it
    private double dragScreenX;
    private double dragScreenY;

    public UiCardGrid(int cardWidth, int cardHeight, CardRenderer<T> renderer) {
        this.cardWidth = cardWidth;
        this.cardHeight = cardHeight;
        this.renderer = renderer;
    }

    /** The space between cards; 4 unless set. */
    public UiCardGrid<T> gap(int gap) {
        this.gap = Math.max(0, gap);
        return this;
    }

    /** How cards are told apart across a rebuild, such as a figure's id; the item itself unless set. */
    public UiCardGrid<T> keys(Function<T, ?> keyOf) {
        this.keyOf = keyOf;
        return this;
    }

    /** What Enter or a double click on a card does. */
    public UiCardGrid<T> onActivate(@Nullable Consumer<T> onActivate) {
        this.onActivate = onActivate;
        return this;
    }

    /** Told the selected cards, in grid order, whenever the operator changes them. */
    public UiCardGrid<T> onSelection(@Nullable Consumer<List<T>> onSelection) {
        this.onSelection = onSelection;
        return this;
    }

    public UiCardGrid<T> onCardClick(@Nullable CardClick<T> onCardClick) {
        this.onCardClick = onCardClick;
        return this;
    }

    /** Lets cards be dragged to a new place; null turns that off. */
    public UiCardGrid<T> onReorder(@Nullable Reorder onReorder) {
        this.onReorder = onReorder;
        return this;
    }

    /** A last cell after the cards, filling the rest of their last row, or a row of its own. */
    public UiCardGrid<T> lastCell(@Nullable UiNode.Box cell) {
        if (lastCell != null) {
            remove(lastCell);
        }
        lastCell = cell;
        if (cell != null) {
            add(cell);
        }
        return this;
    }

    @Override
    public void remove(UiNode child) {
        super.remove(child);
        if (child == lastCell) {
            lastCell = null;
        }
    }

    @Override
    public void clear() {
        super.clear();
        // The last cell was a child too, so the grid no longer saves room for it
        lastCell = null;
    }

    /**
     * Shows a new list of cards, keeping the selection of every card still in it. The screen is
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
            // The current card is gone: the arrow keys go on from its old place
            cursor = Math.min(items.size() - 1, oldCursor);
        }
        pressedIndex = -1;
        dragging = false;
    }

    public List<T> items() {
        return List.copyOf(items);
    }

    /** The selected cards in grid order. */
    public List<T> selected() {
        List<T> chosen = new ArrayList<>();
        for (T item : items) {
            if (selectedKeys.contains(keyOf.apply(item))) {
                chosen.add(item);
            }
        }
        return chosen;
    }

    /** Selects these cards without telling the screen; the first becomes the current one. */
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

    /** The card the arrow keys move from, or null. */
    @Nullable
    public T current() {
        return cursor >= 0 && cursor < items.size() ? items.get(cursor) : null;
    }

    /** Scrolls the card into sight. */
    public void reveal(T item) {
        int index = indexOfKey(keyOf.apply(item));
        if (index >= 0) {
            revealIndex(index);
        }
    }

    /** How many cards a row holds at the present width. */
    public int columns() {
        return Math.max(1, (contentWidth() + gap) / (cardWidth + gap));
    }

    private int indexOfKey(Object key) {
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(keyOf.apply(items.get(i)), key)) {
                return i;
            }
        }
        return -1;
    }

    // Layout

    private int cardX(int index) {
        return x + (index % columns()) * (cardWidth + gap);
    }

    private int cardY(int index) {
        return y + (index / columns()) * (cardHeight + gap);
    }

    private int rows(int cells) {
        return (cells + columns() - 1) / columns();
    }

    private boolean showsLastCell() {
        return lastCell != null && lastCell.visible();
    }

    // Puts the last cell after the cards, as wide as what is left of their last row
    private void placeLastCell() {
        if (lastCell == null) {
            return;
        }
        int columns = columns();
        int column = items.size() % columns;
        int cellWidth = (columns - column) * (cardWidth + gap) - gap;
        lastCell.at(cardX(items.size()), cardY(items.size()), cellWidth, cardHeight);
    }

    @Override
    protected int contentHeight() {
        int cells = items.size() + (showsLastCell() ? 1 : 0);
        return cells == 0 ? 0 : rows(cells) * (cardHeight + gap) - gap;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseY < y) {
            return -1;
        }
        int column = (int) ((mouseX - x) / (cardWidth + gap));
        int row = (int) ((mouseY - y) / (cardHeight + gap));
        if (column >= columns() || mouseX - x - column * (cardWidth + gap) >= cardWidth
                || mouseY - y - row * (cardHeight + gap) >= cardHeight) {
            return -1;
        }
        int index = row * columns() + column;
        return index < items.size() ? index : -1;
    }

    // Where a dragged card would land: the gap nearest the pointer, from 0 before the first card
    // to the card count after the last
    private int slotAt(double mouseX, double mouseY) {
        int columns = columns();
        int rows = Math.max(1, rows(items.size()));
        int row = (int) Math.floor((mouseY - y) / (cardHeight + gap));
        row = Math.max(0, Math.min(rows - 1, row));
        int column = (int) Math.round((mouseX - x) / (double) (cardWidth + gap));
        column = Math.max(0, Math.min(columns, column));
        return Math.min(items.size(), row * columns + column);
    }

    private void revealIndex(int index) {
        int top = cardY(index);
        reveal(top, top + cardHeight);
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (dragging && visible()) {
            // Near an edge, a dragged card carries the grid along
            if (dragScreenY < y + EDGE) {
                scrollTo(scrollY() - EDGE_SPEED, true);
            } else if (dragScreenY > y + height - EDGE) {
                scrollTo(scrollY() + EDGE_SPEED, true);
            }
        }
        placeLastCell();
        super.draw(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int shift = scrollY();
        int pitch = cardHeight + gap;
        int columns = columns();
        int firstRow = Math.max(0, shift / pitch);
        int lastRow = (shift + height) / pitch;
        int from = firstRow * columns;
        int to = Math.min(items.size(), (lastRow + 1) * columns);
        int hovered = dragging ? -1 : indexAt(mouseX, mouseY);
        for (int i = from; i < to; i++) {
            T item = items.get(i);
            Card card = new Card(i, cardX(i), cardY(i), cardWidth, cardHeight, mouseX, mouseY, i == hovered,
                    selectedKeys.contains(keyOf.apply(item)), i == cursor, dragging && i == pressedIndex, shift);
            renderer.draw(graphics, item, card);
        }
        super.drawContent(graphics, mouseX, mouseY, partialTick);
        if (dragging) {
            drawDropLine(graphics, shift);
        }
    }

    private void drawDropLine(GuiGraphics graphics, int shift) {
        int slot = slotAt(dragScreenX, dragScreenY + shift);
        int columns = columns();
        int row;
        int column;
        if (slot == items.size() && slot > 0 && slot % columns == 0) {
            // After the last card of a full row: at that row's end, not the next row's start
            row = slot / columns - 1;
            column = columns;
        } else {
            row = slot / columns;
            column = slot % columns;
        }
        int lineX = x + column * (cardWidth + gap) - gap / 2 - 1;
        int lineY = y + row * (cardHeight + gap);
        graphics.fill(lineX, lineY - 2, lineX + 2, lineY + cardHeight + 2, Theme.GOLD);
        graphics.fill(lineX - 2, lineY - 2, lineX + 4, lineY, Theme.GOLD);
        graphics.fill(lineX - 2, lineY + cardHeight, lineX + 4, lineY + cardHeight + 2, Theme.GOLD);
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        pressedIndex = -1;
        dragging = false;
        placeLastCell();
        return super.click(click);
    }

    @Override
    protected boolean clickContent(UiInput.Click click) {
        int index = indexAt(click.x(), click.y());
        if (index < 0) {
            return super.clickContent(click);
        }
        T item = items.get(index);
        // The keyboard goes to the grid itself, not to the last cell clicked before
        focusChild(null);
        if (onCardClick != null) {
            Card card = new Card(index, cardX(index), cardY(index), cardWidth, cardHeight, (int) click.x(),
                    (int) click.y(), true, selectedKeys.contains(keyOf.apply(item)), index == cursor, false,
                    scrollY());
            if (onCardClick.click(item, card, click)) {
                return true;
            }
        }
        if (!click.left()) {
            return true;
        }
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

    @Override
    protected boolean dragContent(UiInput.Click click, double dragX, double dragY) {
        if (pressedIndex < 0) {
            return super.dragContent(click, dragX, dragY);
        }
        if (onReorder == null) {
            return true;
        }
        dragScreenX = click.x();
        dragScreenY = click.y() - scrollY();
        if (!dragging && Math.hypot(click.x() - pressX, click.y() - pressY) > DRAG_THRESHOLD) {
            dragging = true;
        }
        return true;
    }

    @Override
    protected boolean releaseContent(UiInput.Click click) {
        if (pressedIndex < 0) {
            return super.releaseContent(click);
        }
        int from = pressedIndex;
        boolean moved = dragging;
        pressedIndex = -1;
        dragging = false;
        if (moved && onReorder != null) {
            int slot = slotAt(click.x(), click.y());
            // The card leaves its own place first, so a later slot is one lower
            int to = slot > from ? slot - 1 : slot;
            if (to != from) {
                // Moved here at once, so the card stays where it was dropped and stays current
                // while the screen takes the move; its next list replaces this one either way
                items.add(to, items.remove(from));
                cursor = to;
                onReorder.move(from, to);
            }
        }
        return true;
    }

    @Override
    protected boolean keyContent(UiInput.Key key) {
        // Only the grid that has the keyboard, so arrows meant for another control move no card
        if (items.isEmpty() || !CreatorScreen.hasKeyboard(this)) {
            return super.keyContent(key);
        }
        int step = 0;
        if (key.is(InputConstants.KEY_LEFT)) {
            step = -1;
        } else if (key.is(InputConstants.KEY_RIGHT)) {
            step = 1;
        } else if (key.is(InputConstants.KEY_UP)) {
            step = -columns();
        } else if (key.is(InputConstants.KEY_DOWN)) {
            step = columns();
        }
        if (step != 0) {
            int next = cursor < 0 ? 0 : Math.max(0, Math.min(items.size() - 1, cursor + step));
            choose(next, false, key.shift());
            revealIndex(next);
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
        if (key.escape() && !selectedKeys.isEmpty()) {
            clearSelection();
            selectionChanged();
            return true;
        }
        return super.keyContent(key);
    }
}
