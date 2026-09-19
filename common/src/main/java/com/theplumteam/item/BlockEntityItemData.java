package com.theplumteam.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
//? if >=1.21 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
*///? }

/** Access to block entity item data; callers must treat returned tags as read-only. */
public final class BlockEntityItemData {
    private BlockEntityItemData() {
    }

    /** Returns null when the stack has no block entity data. */
    public static CompoundTag read(ItemStack stack) {
        //? if >=1.21 {
        /*CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data != null ? data.copyTag() : null;
        *///? } else {
        return stack.getTagElement("BlockEntityTag");
        //? }
    }

    public static void write(ItemStack stack, CompoundTag tag, String blockEntityTypeId) {
        //? if >=1.21 {
        /*CompoundTag data = tag.copy();
        data.putString("id", blockEntityTypeId);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        *///? } else {
        stack.addTagElement("BlockEntityTag", tag);
        //? }
    }
}
