package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A row of tiles to pick from, in the look of {@link UiSwatches} (its tiles, its two-unit blue
 * selection ring and the light edge of a colour under the mouse), built from a list of
 * {@link Tile}s asked for on every frame, so the row follows what it picks without being rebuilt.
 * A tile is a colour, a colour of one's own with a small "+" in its corner, a "+" that adds one, a
 * colour with a picture on it, an empty dashed place kept for a tile still to come, an item in a
 * sunk well, an upload glyph in a dashed well, or a glyph such as "More"; thin separators group
 * them.
 *
 * <p>The rings stay inside the row: the tiles start {@value #RING} units in, and the widths
 * {@link #widthOf} gives count both rings. A tile may carry a caption under it, which widens its
 * place to the caption's width, up to {@value #MAX_CAPTION_SLOT} units, and is cut to it. A row
 * may wrap onto more rows after a number of tiles, or sooner when its width holds fewer.
 *
 * <p>Every tile has a tooltip. A click plays the click sound and runs the tile's action, and
 * gives the row the keyboard. While the row has the keyboard, Left, Right, Home and End move a
 * light cursor from tile to tile, over separators and places with nothing to do, and Enter or
 * Space acts as a click; other keys, such as Up, Down and Tab, are left to the screen, which moves
 * on to the next control. The cursor is a light line around the tile, or on the chosen tile its
 * ring lit up and closed onto the tile; it shows once a key moved it, or once the screen gave
 * the row the keyboard from a key ({@link #showCursor}), not after a click, whose ring already
 * says which tile was picked.
 */
public class UiTileRow extends UiNode.Box {
    /** How far the selection ring reaches outside a tile; a row keeps it inside its bounds. */
    public static final int RING = UiSwatches.RING_REACH;
    /** The room a separator takes on each side of its line. */
    public static final int SEPARATOR_SIDE = 4;
    /** The height a caption adds under a row's tiles: its gap and its line. */
    public static final int CAPTION_HEIGHT = 11;
    private static final int CAPTION_GAP = 3;
    /** The widest a captioned tile's place grows to hold its caption. */
    private static final int MAX_CAPTION_SLOT = 48;
    private static final int EMPTY_EDGE = 0xFF55565A;
    private static final int BADGE = 7;
    private static final int BADGE_PLUS = 0xFFF2F2F2;
    private static final String[] PLUS = {"..#..", "..#..", "#####", "..#..", "..#.."};

    /** What a tile shows. */
    public enum Kind {
        /** A colour. */
        COLOUR,
        /** A colour of one's own, with a small "+" in its corner. */
        CUSTOM,
        /** A sunk well with a "+", which adds a colour of one's own. */
        ADD,
        /** A colour with a picture drawn on it, such as a logo on its main colour. */
        PICTURE,
        /** A dashed place kept for a tile still to come, which does nothing. */
        EMPTY,
        /** An item in a sunk well, such as a box. */
        ITEM,
        /** A sunk well with a dashed edge and the upload glyph, for a file still to choose. */
        UPLOAD,
        /** A sunk well with a pixel glyph, such as "More". */
        GLYPH,
        /** A thin line between groups of tiles. */
        SEPARATOR
    }

    /** Draws a picture on a tile, fitted to the rectangle given. */
    @FunctionalInterface
    public interface Picture {
        void draw(GuiGraphics graphics, int x, int y, int width, int height);
    }

    /**
     * One tile of a row.
     *
     * @param id       tells the tile apart in its row, for {@link #cellOf} and the keyboard's cursor
     * @param rgb      the colour of a colour tile, as 0xRRGGBB
     * @param item     the item of an item tile
     * @param glyph    the pixel glyph of a glyph tile
     * @param picture  the picture of a picture tile
     * @param selected whether the tile wears the selection ring
     * @param caption  the line under the tile, shown when the row shows captions; null for none
     * @param action   what a click does; null for a tile that does nothing
     */
    public record Tile(Kind kind, String id, int rgb, ItemStack item, @Nullable String[] glyph,
                       @Nullable Picture picture, boolean selected, Component tooltip, @Nullable Component caption,
                       @Nullable Runnable action) {
        public static Tile colour(String id, int rgb, boolean selected, Component tooltip, Runnable action) {
            return new Tile(Kind.COLOUR, id, rgb, ItemStack.EMPTY, null, null, selected, tooltip, null, action);
        }

        public static Tile custom(String id, int rgb, boolean selected, Component tooltip, Runnable action) {
            return new Tile(Kind.CUSTOM, id, rgb, ItemStack.EMPTY, null, null, selected, tooltip, null, action);
        }

        public static Tile add(String id, Component tooltip, Runnable action) {
            return new Tile(Kind.ADD, id, 0, ItemStack.EMPTY, null, null, false, tooltip, null, action);
        }

        public static Tile picture(String id, int rgb, Picture picture, boolean selected, Component tooltip,
                                   Runnable action) {
            return new Tile(Kind.PICTURE, id, rgb, ItemStack.EMPTY, null, picture, selected, tooltip, null, action);
        }

        public static Tile empty(String id, Component tooltip) {
            return new Tile(Kind.EMPTY, id, 0, ItemStack.EMPTY, null, null, false, tooltip, null, null);
        }

        public static Tile item(String id, ItemStack item, boolean selected, Component tooltip, Runnable action) {
            return new Tile(Kind.ITEM, id, 0, item, null, null, selected, tooltip, null, action);
        }

        public static Tile upload(String id, boolean selected, Component tooltip, Runnable action) {
            return new Tile(Kind.UPLOAD, id, 0, ItemStack.EMPTY, null, null, selected, tooltip, null, action);
        }

        public static Tile glyph(String id, String[] glyph, Component tooltip, Runnable action) {
            return new Tile(Kind.GLYPH, id, 0, ItemStack.EMPTY, glyph, null, false, tooltip, null, action);
        }

        public static Tile separator() {
            return new Tile(Kind.SEPARATOR, "", 0, ItemStack.EMPTY, null, null, false, Component.empty(), null, null);
        }

        /** The same tile with a caption under it. */
        public Tile withCaption(Component text) {
            return new Tile(kind, id, rgb, item, glyph, picture, selected, tooltip, text, action);
        }

        /** Whether a click or the keyboard can act on it. */
        public boolean usable() {
            return action != null && kind != Kind.SEPARATOR && kind != Kind.EMPTY;
        }

        // A colour tile lights its edge under the mouse; wells light their own border
        boolean colourLike() {
            return kind == Kind.COLOUR || kind == Kind.CUSTOM || kind == Kind.PICTURE;
        }
    }

    private final Supplier<List<Tile>> source;
    private final int gap;
    private int tile;
    private boolean captions;
    private int maxPerRow;
    // The tile the keyboard is on, by id, and whether its cursor shows
    @Nullable
    private String cursorId;
    private boolean cursorShown;

    /**
     * @param tile   the tiles' size
     * @param gap    the room between two tiles
     * @param source the tiles, asked for on every frame and input
     */
    public UiTileRow(int tile, int gap, Supplier<List<Tile>> source) {
        this.tile = tile;
        this.gap = gap;
        this.source = source;
    }

    /** Changes the tiles' size, as a narrow form uses larger ones than a wide one. */
    public UiTileRow tile(int size) {
        this.tile = size;
        return this;
    }

    public int tileSize() {
        return tile;
    }

    /** Shows the tiles' captions under them, or not. */
    public UiTileRow captions(boolean shown) {
        this.captions = shown;
        return this;
    }

    /**
     * Wraps onto a new row after this many tiles, or sooner where the width holds fewer; 0 never
     * wraps.
     */
    public UiTileRow wrap(int maxPerRow) {
        this.maxPerRow = maxPerRow;
        return this;
    }

    /** The tiles shown now. */
    public List<Tile> tiles() {
        return source.get();
    }

    // Measuring

    /**
     * The width a row of these tiles takes on one line, both rings included: what a screen
     * compares with its room to choose how many tiles to show.
     */
    public static int widthOf(List<Tile> tiles, int tile, int gap, boolean captions) {
        Font font = Minecraft.getInstance().font;
        int width = 2 * RING;
        Tile previous = null;
        for (Tile shown : tiles) {
            if (previous != null) {
                width += shown.kind() == Kind.SEPARATOR || previous.kind() == Kind.SEPARATOR ? SEPARATOR_SIDE : gap;
            }
            width += slot(font, shown, tile, captions);
            previous = shown;
        }
        return width;
    }

    /** The height the row takes with this many tiles in a width, rings and captions included. */
    public int heightFor(int count, int forWidth) {
        int perRow = perRow(forWidth);
        int rows = perRow > 0 ? Math.max(1, (count + perRow - 1) / perRow) : 1;
        return rows * tile + (rows - 1) * gap + 2 * RING + (captions ? CAPTION_HEIGHT : 0);
    }

    // How many tiles a row holds in a width, or 0 for a row that never wraps
    private int perRow(int forWidth) {
        if (maxPerRow <= 0) {
            return 0;
        }
        int fits = Math.max(1, (forWidth - 2 * RING + gap) / (tile + gap));
        return Math.min(maxPerRow, fits);
    }

    // A tile's place: its own size, or its caption's width up to the most a caption may take
    private static int slot(Font font, Tile shown, int tile, boolean captions) {
        if (shown.kind() == Kind.SEPARATOR) {
            return 1;
        }
        if (captions && shown.caption() != null) {
            return Math.min(MAX_CAPTION_SLOT, Math.max(tile, font.width(shown.caption()) + 2));
        }
        return tile;
    }

    // Where each tile is: a separator one unit wide, a tile centred in its place
    private List<Rect> cells(List<Tile> tiles) {
        Font font = Minecraft.getInstance().font;
        int perRow = perRow(width);
        List<Rect> cells = new ArrayList<>(tiles.size());
        int left = x + RING;
        int top = y + RING;
        int inRow = 0;
        Tile previous = null;
        for (Tile shown : tiles) {
            if (perRow > 0 && inRow == perRow) {
                left = x + RING;
                top += tile + gap;
                inRow = 0;
                previous = null;
            }
            if (previous != null) {
                left += shown.kind() == Kind.SEPARATOR || previous.kind() == Kind.SEPARATOR ? SEPARATOR_SIDE : gap;
            }
            int place = slot(font, shown, tile, captions);
            int cellWidth = shown.kind() == Kind.SEPARATOR ? 1 : tile;
            cells.add(new Rect(left + (place - cellWidth) / 2, top, cellWidth, tile));
            left += place;
            inRow++;
            previous = shown;
        }
        return cells;
    }

    /** Where the tile of an id is, in the units the row was placed in, or an empty rectangle. */
    public Rect cellOf(String id) {
        List<Tile> tiles = source.get();
        List<Rect> cells = cells(tiles);
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).kind() != Kind.SEPARATOR && tiles.get(i).id().equals(id)) {
                return cells.get(i);
            }
        }
        return Rect.EMPTY;
    }

    /** Where the last tile's ring ends, for a screen checking the row fits. */
    public int contentRight() {
        int right = x;
        for (Rect cell : cells(source.get())) {
            right = Math.max(right, cell.right() + RING);
        }
        return right;
    }

    private int indexAt(List<Tile> tiles, List<Rect> cells, double mouseX, double mouseY) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).kind() != Kind.SEPARATOR && cells.get(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<Tile> tiles = source.get();
        List<Rect> cells = cells(tiles);
        int hovered = indexAt(tiles, cells, mouseX, mouseY);
        int cursor = cursorShown && CreatorScreen.hasKeyboard(this) ? cursorIndex(tiles) : -1;
        for (int i = 0; i < tiles.size(); i++) {
            Tile shown = tiles.get(i);
            Rect cell = cells.get(i);
            boolean over = i == hovered;
            drawTile(graphics, shown, cell, over);
            if (shown.kind() == Kind.SEPARATOR) {
                continue;
            }
            if (captions && shown.caption() != null) {
                int place = slot(font, shown, tile, true);
                Draw.centred(graphics, font, shown.caption().getString(), cell.centerX(), cell.bottom() + CAPTION_GAP,
                        place, shown.selected() || over ? Theme.TEXT_SOFT : Theme.MUTED);
            }
            if (shown.selected()) {
                UiSwatches.drawRing(graphics, cell.x(), cell.y(), cell.width(), cell.height());
            } else if (over && shown.colourLike()) {
                UiSwatches.drawHover(graphics, cell.x(), cell.y(), cell.width(), cell.height());
            }
            if (i == cursor) {
                // The keyboard's cursor: a light line where the ring's outer edge goes, which
                // stands apart from another tile's ring; on the chosen tile the ring lights up and
                // fills its gap to the tile, a solid light frame no ring of a pick looks like
                int lines = shown.selected() ? RING : 1;
                for (int r = 0; r < lines; r++) {
                    Draw.outline(graphics, cell.x() - RING + r, cell.y() - RING + r, cell.width() + 2 * (RING - r),
                            cell.height() + 2 * (RING - r), Theme.SELECTED_LIGHT);
                }
            }
        }
        if (hovered >= 0) {
            UiTooltip.set(SidebarScreen.tooltipLines(tiles.get(hovered).tooltip()));
        }
    }

    private void drawTile(GuiGraphics graphics, Tile shown, Rect c, boolean over) {
        switch (shown.kind()) {
            case SEPARATOR -> graphics.fill(c.x(), c.y() + 1, c.x() + 1, c.bottom() - 1, Theme.SEPARATOR);
            case COLOUR -> UiSwatches.drawColourTile(graphics, c.x(), c.y(), c.width(), shown.rgb());
            case CUSTOM -> {
                UiSwatches.drawColourTile(graphics, c.x(), c.y(), c.width(), shown.rgb());
                // A small "+" in the corner: this tile opens the colour picker
                graphics.fill(c.right() - BADGE, c.bottom() - BADGE, c.right(), c.bottom(), Surfaces.OUTLINE);
                graphics.fill(c.right() - 6, c.bottom() - 4, c.right() - 1, c.bottom() - 3, BADGE_PLUS);
                graphics.fill(c.right() - 4, c.bottom() - 6, c.right() - 3, c.bottom() - 1, BADGE_PLUS);
            }
            case ADD -> {
                Draw.well(graphics, c.x(), c.y(), c.width(), c.height(), over, false);
                PixelIcon.drawCentered(graphics, PLUS, c.x(), c.y(), c.width(), c.height(),
                        over ? Theme.TEXT : Theme.SELECTED_LIGHT);
            }
            case PICTURE -> {
                UiSwatches.drawColourTile(graphics, c.x(), c.y(), c.width(), shown.rgb());
                if (shown.picture() != null) {
                    shown.picture().draw(graphics, c.x() + 1, c.y() + 1, c.width() - 2, c.height() - 2);
                }
            }
            case EMPTY -> Draw.dashed(graphics, c.x(), c.y(), c.width(), c.height(),
                    over ? Theme.MUTED : EMPTY_EDGE, 2);
            case ITEM -> UiSwatches.drawBoxTile(graphics, c.x(), c.y(), c.width(), shown.item(), over);
            case UPLOAD -> {
                Draw.well(graphics, c.x(), c.y(), c.width(), c.height(), over, false);
                Draw.dashed(graphics, c.x() + 2, c.y() + 2, c.width() - 4, c.height() - 4,
                        over ? Theme.SELECTED : EMPTY_EDGE, 2);
                PixelIcon.drawCentered(graphics, PixelIcon.UPLOAD, c.x(), c.y(), c.width(), c.height(),
                        over ? Theme.TEXT : Theme.MUTED);
            }
            case GLYPH -> {
                Draw.well(graphics, c.x(), c.y(), c.width(), c.height(), over, false);
                if (shown.glyph() != null) {
                    PixelIcon.drawCentered(graphics, shown.glyph(), c.x(), c.y(), c.width(), c.height(),
                            over ? Theme.TEXT : Theme.MUTED);
                }
            }
        }
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        List<Tile> tiles = source.get();
        int index = indexAt(tiles, cells(tiles), click.x(), click.y());
        // A click hides the cursor: the ring says what was picked
        this.cursorShown = false;
        if (index >= 0 && tiles.get(index).usable()) {
            this.cursorId = tiles.get(index).id();
            activate(tiles.get(index));
        }
        return true;
    }

    /**
     * Shows the keyboard's cursor, as when the screen gives the row the keyboard from a key such
     * as Tab: on the chosen tile, or the first one that does something.
     */
    public void showCursor() {
        this.cursorId = null;
        this.cursorShown = true;
    }

    /** Hides the cursor, as when the keyboard moves on. */
    public void hideCursor() {
        this.cursorShown = false;
    }

    // The tile the cursor is on: the one it was moved to, else the chosen one, else the first that acts
    private int cursorIndex(List<Tile> tiles) {
        int chosen = -1;
        int first = -1;
        for (int i = 0; i < tiles.size(); i++) {
            Tile shown = tiles.get(i);
            if (!shown.usable()) {
                continue;
            }
            if (Objects.equals(shown.id(), this.cursorId)) {
                return i;
            }
            if (shown.selected() && chosen < 0) {
                chosen = i;
            }
            if (first < 0) {
                first = i;
            }
        }
        return chosen >= 0 ? chosen : first;
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (!visible() || !CreatorScreen.hasKeyboard(this)) {
            return false;
        }
        List<Tile> tiles = source.get();
        int at = cursorIndex(tiles);
        if (at < 0) {
            return false;
        }
        int next = -1;
        if (key.is(InputConstants.KEY_LEFT)) {
            next = step(tiles, at, -1);
        } else if (key.is(InputConstants.KEY_RIGHT)) {
            next = step(tiles, at, 1);
        } else if (key.is(InputConstants.KEY_HOME)) {
            next = step(tiles, -1, 1);
        } else if (key.is(InputConstants.KEY_END)) {
            next = step(tiles, tiles.size(), -1);
        } else if (key.enter() || key.is(InputConstants.KEY_SPACE)) {
            this.cursorShown = true;
            activate(tiles.get(at));
            return true;
        } else {
            return false;
        }
        this.cursorShown = true;
        if (next >= 0) {
            this.cursorId = tiles.get(next).id();
        }
        return true;
    }

    // The next tile that acts from an index, in a direction, or -1 at the end
    private static int step(List<Tile> tiles, int from, int direction) {
        for (int i = from + direction; i >= 0 && i < tiles.size(); i += direction) {
            if (tiles.get(i).usable()) {
                return i;
            }
        }
        return -1;
    }

    private void activate(Tile tile) {
        if (tile.action() != null) {
            UiSounds.play(ModSounds.CLICK);
            tile.action().run();
        }
    }
}
