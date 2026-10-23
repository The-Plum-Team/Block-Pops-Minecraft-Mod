package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.registry.ModItems;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A row of colour tiles to pick from, wrapping onto more rows when it runs out of width. The
 * chosen tile wears Quick Skin's blue selection ring and every tile names its colour in a
 * tooltip.
 *
 * <p>It comes in two kinds. {@link #colours} offers the twelve named background colours of the
 * mockup, and when the chosen colour is none of them it adds a thirteenth tile showing that
 * custom colour. {@link #boxColours} offers the sixteen plain box colours, each tile showing that
 * box at three quarters of its size in a sunk well, as an inventory slot.
 */
public class UiSwatches extends UiNode.Box {
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
    private static final int GAP = 5;
    // The ring sits one pixel off the tile and is two pixels thick, inside the gap between tiles
    private static final int RING_OFFSET = 1;
    private static final int RING = 2;
    private static final int MARGIN = RING_OFFSET + RING;

    private final boolean boxes;
    private final int tile;
    @Nullable
    private final IntConsumer onColour;
    @Nullable
    private final Consumer<PopBlockColor> onBox;
    private int selectedRgb;
    @Nullable
    private PopBlockColor selectedBox;
    private boolean enabled = true;

    private UiSwatches(boolean boxes, int tile, @Nullable IntConsumer onColour, @Nullable Consumer<PopBlockColor> onBox) {
        this.boxes = boxes;
        this.tile = tile;
        this.onColour = onColour;
        this.onBox = onBox;
    }

    /** The twelve background colours, with a colour as 0xRRGGBB chosen. */
    public static UiSwatches colours(int selectedRgb, IntConsumer onPick) {
        UiSwatches swatches = new UiSwatches(false, COLOUR_TILE, onPick, null);
        swatches.selectedRgb = selectedRgb & 0xFFFFFF;
        return swatches;
    }

    /** The sixteen box colours, with one or none chosen. */
    public static UiSwatches boxColours(@Nullable PopBlockColor selected, Consumer<PopBlockColor> onPick) {
        UiSwatches swatches = new UiSwatches(true, BOX_TILE, null, onPick);
        swatches.selectedBox = selected;
        return swatches;
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

    /** The height the tiles need in a width, for a screen laying them out. */
    public int heightFor(int width) {
        int perRow = perRow(width);
        int rows = (count() + perRow - 1) / perRow;
        return 2 * MARGIN + rows * tile + (rows - 1) * GAP;
    }

    public void select(int rgb) {
        this.selectedRgb = rgb & 0xFFFFFF;
    }

    public void select(@Nullable PopBlockColor colour) {
        this.selectedBox = colour;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private int perRow(int width) {
        return Math.max(1, (width - 2 * MARGIN + GAP) / (tile + GAP));
    }

    // A custom colour gets a tile of its own after the named ones
    private boolean hasCustomTile() {
        return !boxes && customIndex() < 0;
    }

    private int customIndex() {
        for (int i = 0; i < BACKGROUNDS.size(); i++) {
            if (BACKGROUNDS.get(i).rgb() == selectedRgb) {
                return i;
            }
        }
        return -1;
    }

    private int count() {
        return boxes ? PopBlockColor.values().length : BACKGROUNDS.size() + (hasCustomTile() ? 1 : 0);
    }

    private int selectedIndex() {
        if (boxes) {
            return selectedBox == null ? -1 : selectedBox.ordinal();
        }
        int index = customIndex();
        return index >= 0 ? index : BACKGROUNDS.size();
    }

    private Rect tileAt(int index) {
        int perRow = perRow(width);
        int column = index % perRow;
        int row = index / perRow;
        return new Rect(x + MARGIN + column * (tile + GAP), y + MARGIN + row * (tile + GAP), tile, tile);
    }

    private int indexAt(double mouseX, double mouseY) {
        for (int i = 0; i < count(); i++) {
            if (tileAt(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        int hovered = indexAt(mouseX, mouseY);
        int selected = selectedIndex();
        for (int i = 0; i < count(); i++) {
            Rect cell = tileAt(i);
            if (boxes) {
                Draw.well(graphics, cell.x(), cell.y(), tile, tile, i == hovered && enabled, false);
                int size = tile * 3 / 4;
                Draw.item(graphics, boxStack(PopBlockColor.values()[i]), cell.x() + (tile - size) / 2,
                        cell.y() + (tile - size) / 2, size);
            } else {
                int rgb = i < BACKGROUNDS.size() ? BACKGROUNDS.get(i).rgb() : selectedRgb;
                Draw.panel(graphics, cell.x(), cell.y(), tile, tile, 0xFF000000 | rgb, Surfaces.OUTLINE);
            }
            if (i == selected) {
                int ring = RING_OFFSET + RING;
                for (int r = 0; r < RING; r++) {
                    Draw.outline(graphics, cell.x() - ring + r, cell.y() - ring + r, tile + 2 * (ring - r),
                            tile + 2 * (ring - r), Theme.SELECTED);
                }
            } else if (i == hovered && enabled) {
                Draw.outline(graphics, cell.x() - 1, cell.y() - 1, tile + 2, tile + 2, Surfaces.BORDER_SELECTED);
            }
            if (!enabled) {
                graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), 0x90000000);
            }
        }
        if (hovered >= 0) {
            UiTooltip.set(tooltip(hovered));
        }
    }

    private Component tooltip(int index) {
        if (boxes) {
            return nameOf(PopBlockColor.values()[index]);
        }
        int rgb = index < BACKGROUNDS.size() ? BACKGROUNDS.get(index).rgb() : selectedRgb;
        Component name = index < BACKGROUNDS.size() ? BACKGROUNDS.get(index).name() : CreatorScreen.tr("colour.custom");
        return Component.empty().append(name).append(" · " + UiColourPicker.hex(rgb));
    }

    private static ItemStack boxStack(PopBlockColor colour) {
        RegistrySupplier<Item> item = ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(colour);
        return item != null ? new ItemStack(item.get()) : ItemStack.EMPTY;
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        int index = indexAt(click.x(), click.y());
        if (index >= 0 && enabled) {
            choose(index);
        }
        return true;
    }

    private void choose(int index) {
        if (boxes) {
            PopBlockColor colour = PopBlockColor.values()[index];
            selectedBox = colour;
            if (onBox != null) {
                onBox.accept(colour);
            }
        } else if (index < BACKGROUNDS.size()) {
            selectedRgb = BACKGROUNDS.get(index).rgb();
            if (onColour != null) {
                onColour.accept(selectedRgb);
            }
        }
    }
}
