package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.figure.BuiltInCollections;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK);

    // Map of collection ID to box block - one box block per collection
    public static final Map<String, RegistrySupplier<Block>> BOX_BLOCKS = new HashMap<>();

    public static final RegistrySupplier<Block> CLAW_MACHINE_BLOCK = BLOCKS.register(
        "claw_machine_block",
        () -> new ClawMachineBlock(
            BlockBehaviour.Properties.of()
                .strength(1.5F, 6.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
        )
    );

    static {
        // Register one box block for each built-in collection
        for (String collectionId : BuiltInCollections.COLLECTION_IDS) {
            BOX_BLOCKS.put(collectionId, BLOCKS.register(
                "box_block_" + collectionId,
                () -> new BoxBlock(
                    BlockBehaviour.Properties.of()
                        .mapColor(MapColor.COLOR_ORANGE) // Default color, actual texture from collection
                        .strength(1.5F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .noOcclusion(),
                    collectionId
                )
            ));
        }
    }

    public static void register() {
        BLOCKS.register();
    }
}
