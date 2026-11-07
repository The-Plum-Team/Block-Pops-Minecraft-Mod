package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The creator's named colours and the look of its colour and box tiles, which every row of tiles
 * shares ({@link UiTileRow}), so they all look the same: the twelve named background colours of
 * the mockup and their names, the names of the sixteen plain box colours, and static helpers that
 * draw a colour tile, a box tile (the box at three quarters of its size in a sunk well, as an
 * inventory slot), Quick Skin's blue selection ring around a tile and the light edge of a tile
 * under the mouse.
 */
public final class UiSwatches {
    /** A named colour, with its name under {@code blockpops.world_creator.colour.<key>}. */
    public record Swatch(String key, int rgb) {
        public Component name() {
            return CreatorScreen.tr("colour." + key);
        }
    }

    /** The background colours of the mockup, in its order. */
    public static final List<Swatch> BACKGROUNDS = List.of(
            new Swatch("cream", 0xF3E6C4), new Swatch("sky", 0x8EC9F0), new Swatch("mint", 0x9FE0BD),
            new Swatch("lavender", 0xB9A6E6), new Swatch("peach", 0xF6B89A), new Swatch("pink", 0xF08FC0),
            new Swatch("stone", 0x9A9A9A), new Swatch("night", 0x24283F), new Swatch("forest", 0x2F6B3A),
            new Swatch("brick", 0x9C3B2C), new Swatch("orange", 0xF08A24), new Swatch("ocean", 0x1F5F8B));

    /** The tile size of the background colours, half the mockup's 28 px as every size here. */
    public static final int COLOUR_TILE = 14;
    /** Box tiles are larger, so the box drawn in them can be told apart. */
    public static final int BOX_TILE = 20;
    // The ring sits one pixel off the tile and is two pixels thick, inside the gap between tiles
    private static final int RING_OFFSET = 1;
    private static final int RING = 2;
    /** How far the selection ring reaches outside a tile. */
    static final int RING_REACH = RING_OFFSET + RING;

    private UiSwatches() {
    }

    /** The name of a colour: a swatch's name, or "Custom" with its hex. */
    public static Component nameOf(int rgb) {
        for (Swatch swatch : BACKGROUNDS) {
            if (swatch.rgb() == (rgb & 0xFFFFFF)) {
                return swatch.name();
            }
        }
        return CreatorScreen.tr("colour.custom");
    }

    /** The name of a plain box colour. */
    public static Component nameOf(PopBlockColor colour) {
        return CreatorScreen.tr("box_colour." + colour.getSerializedName());
    }

    // The tiles and the ring, shared by every row of tiles

    /** A colour tile: the colour, opaque, in a black outline. */
    static void drawColourTile(GuiGraphics graphics, int x, int y, int size, int rgb) {
        Draw.panel(graphics, x, y, size, size, 0xFF000000 | rgb, Surfaces.OUTLINE);
    }

    /** A box tile: a sunk well, lighter under the mouse, with the box item at three quarters of its size. */
    static void drawBoxTile(GuiGraphics graphics, int x, int y, int size, ItemStack box, boolean hovered) {
        Draw.well(graphics, x, y, size, size, hovered, false);
        drawItem(graphics, x, y, size, box);
    }

    /** An item at three quarters of a tile's size, centred on it, as a box tile shows its box. */
    static void drawItem(GuiGraphics graphics, int x, int y, int size, ItemStack stack) {
        int shown = size * 3 / 4;
        Draw.item(graphics, stack, x + (size - shown) / 2, y + (size - shown) / 2, shown);
    }

    /** Quick Skin's selection ring around a tile: two units thick, one unit off it. */
    static void drawRing(GuiGraphics graphics, int x, int y, int width, int height) {
        int ring = RING_OFFSET + RING;
        for (int r = 0; r < RING; r++) {
            Draw.outline(graphics, x - ring + r, y - ring + r, width + 2 * (ring - r), height + 2 * (ring - r),
                    Theme.SELECTED);
        }
    }

    /** The light edge of a colour tile under the mouse. */
    static void drawHover(GuiGraphics graphics, int x, int y, int width, int height) {
        Draw.outline(graphics, x - 1, y - 1, width + 2, height + 2, Surfaces.BORDER_SELECTED);
    }
}
