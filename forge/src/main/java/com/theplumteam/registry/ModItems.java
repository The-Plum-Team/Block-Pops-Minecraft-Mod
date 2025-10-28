package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
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

    public static final Map<PopBlockColor, RegistrySupplier<Item>> BOX_BLOCK_ITEMS = new HashMap<>();

    public static final RegistrySupplier<Item> CLAW_MACHINE_BLOCK_ITEM = ITEMS.register(
        "claw_machine_block",
        () -> new GeoBlockItem(ModBlocks.CLAW_MACHINE_BLOCK.get(), new Item.Properties())
    );

    static {
        for (PopBlockColor color : PopBlockColor.values()) {
            BOX_BLOCK_ITEMS.put(color, ITEMS.register(
                "box_block_" + color.getSerializedName(),
                () -> new GeoBlockItem(ModBlocks.BOX_BLOCKS.get(color).get(), new Item.Properties())
            ));
        }
    }

    public static void register() {
        ITEMS.register();
    }
}
