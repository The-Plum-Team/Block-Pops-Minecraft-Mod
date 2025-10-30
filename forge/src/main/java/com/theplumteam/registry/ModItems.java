package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.item.GeoBlockItem;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.ITEM);

    // Map of collection ID to box block item (for non-default collections)
    public static final Map<String, RegistrySupplier<Item>> BOX_BLOCK_ITEMS = new HashMap<>();

    // Map of color to box block item (for default collection only)
    public static final Map<PopBlockColor, RegistrySupplier<Item>> DEFAULT_BOX_BLOCK_ITEMS = new HashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        () -> new GeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), new Item.Properties())
    );

    static {
        // Register box block items for each collection
        for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
            if (collectionId.equals("default")) {
                // For default collection, register 16 color variants
                for (PopBlockColor color : PopBlockColor.values()) {
                    DEFAULT_BOX_BLOCK_ITEMS.put(color, ITEMS.register(
                        "box_block_" + color.getSerializedName(),
                        () -> new GeoBlockItem(ModBlocks.DEFAULT_BOX_BLOCKS.get(color).get(), new Item.Properties())
                    ));
                }
            } else {
                // For other collections, register one item per collection
                BOX_BLOCK_ITEMS.put(collectionId, ITEMS.register(
                    "box_block_" + collectionId,
                    () -> new GeoBlockItem(ModBlocks.BOX_BLOCKS.get(collectionId).get(), new Item.Properties())
                ));
            }
        }
    }

    public static void register() {
        ITEMS.register();
    }
}

