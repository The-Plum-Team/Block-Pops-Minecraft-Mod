package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.platform.PlatformHelper;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
//? if >=1.21.2 {
/*import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import com.theplumteam.util.ResourceLocations;
*///? }

import java.util.HashMap;
import java.util.Map;

public class ModItems {
    //? if >=1.21.2 {
    /*// 1.21.2 made Item.Properties require its registry id up front.
    private static Item.Properties itemProperties(String name) {
        return new Item.Properties().setId(
                ResourceKey.create(Registries.ITEM, ResourceLocations.of(BlockPopsMod.MOD_ID, name)));
    }

    *///? }
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.ITEM);

    // Map of collection ID to box block item (for non-default collections)
    public static final Map<String, RegistrySupplier<Item>> BOX_BLOCK_ITEMS = new HashMap<>();

    // Map of color to box block item (for default collection only)
    public static final Map<PopBlockColor, RegistrySupplier<Item>> DEFAULT_BOX_BLOCK_ITEMS = new HashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        //? if >=1.21.2 {
        /*() -> PlatformHelper.createGeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), itemProperties("claw_machine_block"))
        *///? } else {
        () -> PlatformHelper.createGeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), new Item.Properties())
        //? }
    );

    public static final RegistrySupplier<Item> FIGURE_BLOCK_ITEM = ITEMS.register(
        "figure_block",
        //? if >=1.21.2 {
        /*() -> PlatformHelper.createGeoBlockItem(ModBlocks.FIGURE_BLOCK.get(), itemProperties("figure_block"))
        *///? } else {
        () -> PlatformHelper.createGeoBlockItem(ModBlocks.FIGURE_BLOCK.get(), new Item.Properties())
        //? }
    );

    static {
        // Register 16 color variant box block items
        // All items reference the same BOX_BLOCK but with different colors pre-set in NBT
        for (PopBlockColor color : PopBlockColor.values()) {
            DEFAULT_BOX_BLOCK_ITEMS.put(color, ITEMS.register(
                "box_block_" + color.getSerializedName(),
                //? if >=1.21.2 {
                /*() -> PlatformHelper.createBoxBlockItemForColor(ModBlocks.BOX_BLOCK.get(), itemProperties("box_block_" + color.getSerializedName()), color)
                *///? } else {
                () -> PlatformHelper.createBoxBlockItemForColor(ModBlocks.BOX_BLOCK.get(), new Item.Properties(), color)
                //? }
            ));
        }

        // Register box block items for each collection
        // All items reference the same BOX_BLOCK but with different collections pre-set in NBT
        for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
            BOX_BLOCK_ITEMS.put(collectionId, ITEMS.register(
                "box_block_" + collectionId,
                //? if >=1.21.2 {
                /*() -> PlatformHelper.createBoxBlockItem(ModBlocks.BOX_BLOCK.get(), itemProperties("box_block_" + collectionId), collectionId)
                *///? } else {
                () -> PlatformHelper.createBoxBlockItem(ModBlocks.BOX_BLOCK.get(), new Item.Properties(), collectionId)
                //? }
            ));
        }
    }

    public static void register() {
        ITEMS.register();
    }
}
