package com.theplumteam.item;

import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.Holographic;
import com.theplumteam.registry.ModItems;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The item a draw or an unlock hands out for a figure of a plush collection.
 *
 * A bundled plush is its own block, registered at start-up for each bundled plush id. A World
 * plush collection cannot add blocks at run time, so its figures are handed out as figure blocks
 * that name the collection and the figure and draw the figure's model, as the figure taken out of
 * a box does. Any other plush figure without a registered plush block has no item at all.
 */
public final class PlushPrizes {
    private PlushPrizes() {
    }

    /** The item for the figure, or null when there is none to hand out. */
    @Nullable
    public static ItemStack of(String collectionId, FigureDefinition figure) {
        return of(collectionId, figure, false);
    }

    /**
     * The item for the figure, carrying {@link Holographic#KEY} when it came out holographic, or
     * null when there is none to hand out.
     */
    @Nullable
    public static ItemStack of(String collectionId, FigureDefinition figure, boolean holographic) {
        if (WorldContentIds.isWorldCollectionId(collectionId)) {
            ItemStack figureBlockItem = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
            CompoundTag blockEntityTag = new CompoundTag();
            blockEntityTag.putString("FigureId", figure.getId());
            blockEntityTag.putString("CollectionId", collectionId);
            if (holographic) {
                blockEntityTag.putBoolean(Holographic.KEY, true);
            }
            BlockEntityItemData.write(figureBlockItem, blockEntityTag, "blockpops:figure_block");
            return figureBlockItem;
        }
        var plushItem = ModItems.PLUSH_ITEMS.get(figure.getId());
        if (plushItem == null) {
            return null;
        }
        ItemStack plush = new ItemStack(plushItem.get());
        if (holographic) {
            CompoundTag plushTag = new CompoundTag();
            plushTag.putBoolean(Holographic.KEY, true);
            BlockEntityItemData.write(plush, plushTag, "blockpops:plush_" + figure.getId());
        }
        return plush;
    }

    /**
     * Whether every figure of the pool has an item, so a draw from it can never spend a token
     * and then have nothing to give.
     */
    public static boolean canGiveAll(String collectionId, List<FigureDefinition> pool) {
        if (WorldContentIds.isWorldCollectionId(collectionId)) {
            return true;
        }
        for (FigureDefinition figure : pool) {
            if (!ModItems.PLUSH_ITEMS.containsKey(figure.getId())) {
                return false;
            }
        }
        return true;
    }
}
