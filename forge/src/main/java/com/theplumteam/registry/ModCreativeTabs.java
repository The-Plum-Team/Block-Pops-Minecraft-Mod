package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.BuiltInCollections;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> BLOCKPOPS_TAB = CREATIVE_TABS.register(
        "blockpops_tab",
        () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.blockpops.blockpops_tab"))
            .icon(() -> new ItemStack(ModItems.BOX_BLOCK_ITEMS.get("default").get()))
            .displayItems((parameters, output) -> {
                // Add claw machine
                output.accept(ModItems.CLAW_MACHINE_BLOCK_ITEM.get());

                // Add all collection box blocks to the creative tab
                for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
                    output.accept(ModItems.BOX_BLOCK_ITEMS.get(collectionId).get());
                }
            })
            .build()
    );

    public static void register() {
        CREATIVE_TABS.register();
    }
}

