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
        //? if >=26 {
        /*// 26.1 keys the component by the block entity type itself rather than by a
        // string id written into the tag.
        net.minecraft.world.item.component.TypedEntityData<
                net.minecraft.world.level.block.entity.BlockEntityType<?>> typed =
                stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return typed != null ? typed.copyTagWithoutId() : null;
        *///? } elif >=1.21 {
        /*CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data != null ? data.copyTag() : null;
        *///? } else {
        return stack.getTagElement("BlockEntityTag");
        //? }
    }

    public static void write(ItemStack stack, CompoundTag tag, String blockEntityTypeId) {
        //? if >=26 {
        /*net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getOptional(net.minecraft.resources.ResourceLocation.parse(blockEntityTypeId))
                .ifPresent(type -> stack.set(DataComponents.BLOCK_ENTITY_DATA,
                        net.minecraft.world.item.component.TypedEntityData.of(type, tag.copy())));
        *///? } elif >=1.21 {
        /*CompoundTag data = tag.copy();
        data.putString("id", blockEntityTypeId);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        *///? } else {
        stack.addTagElement("BlockEntityTag", tag);
        //? }
    }
}
