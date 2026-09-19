package com.theplumteam.item;

import com.theplumteam.block.PopBlockColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * A specialized BlockItem for box blocks that pre-sets collection/color data in NBT.
 * This allows multiple different box items to all reference the same box block,
 * with the difference stored in NBT rather than as separate block registrations.
 */
public class BoxBlockItem extends GeoBlockItem {
    @Nullable
    private final String collectionId;
    @Nullable
    private final PopBlockColor color;

    /**
     * Creates a box block item for a specific collection
     */
    public BoxBlockItem(Block block, Properties properties, String collectionId) {
        super(block, properties);
        this.collectionId = collectionId;
        this.color = null;
    }

    /**
     * Creates a box block item for a specific color (default collection)
     */
    public BoxBlockItem(Block block, Properties properties, PopBlockColor color) {
        super(block, properties);
        this.collectionId = null;
        this.color = color;
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();

        // Pre-set the NBT data for this box's collection or color
        CompoundTag blockEntityTag = new CompoundTag();

        if (collectionId != null) {
            blockEntityTag.putString("CollectionId", collectionId);
        }

        if (color != null) {
            blockEntityTag.putString("Color", color.getSerializedName());
        }

        if (!blockEntityTag.isEmpty()) {
            BlockEntityItemData.write(stack, blockEntityTag, "blockpops:box_block");
        }

        return stack;
    }

    @Nullable
    public String getCollectionId() {
        return collectionId;
    }

    @Nullable
    public PopBlockColor getColor() {
        return color;
    }
}
