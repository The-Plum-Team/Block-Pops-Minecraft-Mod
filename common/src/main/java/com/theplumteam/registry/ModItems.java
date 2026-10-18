package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.BuiltInCollections;
import com.theplumteam.item.TicketItem;
import com.theplumteam.platform.PlatformHelper;
import com.theplumteam.plush.BuiltInPlushes;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
//? if >=1.21.2 {
/*import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import com.theplumteam.util.ResourceLocations;
*///? }

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModItems {
    //? if >=1.21.2 {
    /*// 1.21.2 made Item.Properties require its registry id up front. It also stopped naming a
    // block item after its block: without the block prefix every item here would be named
    // item.blockpops.<id>, a key the language files never had.
    private static Item.Properties itemProperties(String name) {
        return plainItemProperties(name).useBlockDescriptionPrefix();
    }

    private static Item.Properties plainItemProperties(String name) {
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

    // Map of plush ID to its block item, in creative tab order
    public static final Map<String, RegistrySupplier<Item>> PLUSH_ITEMS = new LinkedHashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        //? if >=1.21.2 {
        /*() -> PlatformHelper.createGeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), itemProperties("claw_machine_block"))
        *///? } else {
        () -> PlatformHelper.createGeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), new Item.Properties())
        //? }
    );

    // A plain block item: its icon is a flat sprite, so it needs no GeckoLib item renderer.
    public static final RegistrySupplier<Item> GACHA_MACHINE_BLOCK_ITEM = ITEMS.register(
        "gacha_machine_block",
        //? if >=1.21.2 {
        /*() -> new BlockItem(ModBlocks.GACHA_MACHINE_BLOCK.get(), itemProperties("gacha_machine_block"))
        *///? } else {
        () -> new BlockItem(ModBlocks.GACHA_MACHINE_BLOCK.get(), new Item.Properties())
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

    public static final RegistrySupplier<Item> BLUE_TICKET = registerTicket("blue_ticket");
    public static final RegistrySupplier<Item> GOLD_TICKET = registerTicket("gold_ticket");

    // A ticket is a plain item, drawn from its flat sprite, that keeps the item.blockpops.<id>
    // name and says in its tooltip what it is for.
    private static RegistrySupplier<Item> registerTicket(String name) {
        return ITEMS.register(
            name,
            //? if >=1.21.2 {
            /*() -> new TicketItem(plainItemProperties(name), name)
            *///? } else {
            () -> new TicketItem(new Item.Properties(), name)
            //? }
        );
    }

    static {
        // Register one plain block item per plush; like the gacha machine, their items are vanilla models
        for (String plushId : BuiltInPlushes.PLUSH_IDS) {
            PLUSH_ITEMS.put(plushId, ITEMS.register(
                "plush_" + plushId,
                //? if >=1.21.2 {
                /*() -> new BlockItem(ModBlocks.PLUSH_BLOCKS.get(plushId).get(), itemProperties("plush_" + plushId))
                *///? } else {
                () -> new BlockItem(ModBlocks.PLUSH_BLOCKS.get(plushId).get(), new Item.Properties())
                //? }
            ));
        }

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
