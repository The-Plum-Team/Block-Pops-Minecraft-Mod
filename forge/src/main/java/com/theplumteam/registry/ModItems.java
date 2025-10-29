package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
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

    // Map of collection ID to box block item
    public static final Map<String, RegistrySupplier<Item>> BOX_BLOCK_ITEMS = new HashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        () -> new GeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), new Item.Properties())
    );

    static {
        // Register one box block item for each collection
        for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
            BOX_BLOCK_ITEMS.put(collectionId, ITEMS.register(
                "box_block_" + collectionId,
                () -> new GeoBlockItem(ModBlocks.BOX_BLOCKS.get(collectionId).get(), new Item.Properties())
            ));
        }
    }

    public static void register() {
        ITEMS.register();
    }
}

