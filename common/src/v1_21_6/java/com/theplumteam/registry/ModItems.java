package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.item.BoxBlockItem;
import com.theplumteam.item.GeoBlockItem;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.ITEM);

    // In 1.21.2+, Item.Properties requires setId() to be called
    private static ResourceKey<Item> itemKey(String name) {
        return ResourceKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, name));
    }

    // Map of collection ID to box block item (for non-default collections)
    public static final Map<String, RegistrySupplier<Item>> BOX_BLOCK_ITEMS = new HashMap<>();

    // Map of color to box block item (for default collection only)
    public static final Map<PopBlockColor, RegistrySupplier<Item>> DEFAULT_BOX_BLOCK_ITEMS = new HashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        () -> new GeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(),
            new Item.Properties().setId(itemKey("claw_machine_block")))
    );

    public static final RegistrySupplier<Item> FIGURE_BLOCK_ITEM = ITEMS.register(
        "figure_block",
        () -> new GeoBlockItem(ModBlocks.FIGURE_BLOCK.get(),
            new Item.Properties().setId(itemKey("figure_block")))
    );

    static {
        // Register 16 color variant box block items
        // All items reference the same BOX_BLOCK but with different colors pre-set in NBT
        for (PopBlockColor color : PopBlockColor.values()) {
            final String itemName = "box_block_" + color.getSerializedName();
            final PopBlockColor finalColor = color;
            DEFAULT_BOX_BLOCK_ITEMS.put(color, ITEMS.register(
                itemName,
                () -> new BoxBlockItem(ModBlocks.BOX_BLOCK.get(),
                    new Item.Properties().setId(itemKey(itemName)), finalColor)
            ));
        }

        // Register box block items for each collection
        // All items reference the same BOX_BLOCK but with different collections pre-set in NBT
        for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
            final String itemName = "box_block_" + collectionId;
            final String finalCollectionId = collectionId;
            BOX_BLOCK_ITEMS.put(collectionId, ITEMS.register(
                itemName,
                () -> new BoxBlockItem(ModBlocks.BOX_BLOCK.get(),
                    new Item.Properties().setId(itemKey(itemName)), finalCollectionId)
            ));
        }
    }

    public static void register() {
        ITEMS.register();
    }
}
