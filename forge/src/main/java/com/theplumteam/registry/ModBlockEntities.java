package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<BoxBlockEntity>> BOX_BLOCK =
        BLOCK_ENTITIES.register("box_block", () -> {
            // Extract blocks array inside the lambda, after blocks are registered
            // Include both default color variants AND themed collection blocks
            Block[] defaultBlocks = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                .map(RegistrySupplier::get)
                .toArray(Block[]::new);

            Block[] collectionBlocks = ModBlocks.BOX_BLOCKS.values().stream()
                .map(RegistrySupplier::get)
                .toArray(Block[]::new);

            // Combine both arrays
            Block[] allBlocks = new Block[defaultBlocks.length + collectionBlocks.length];
            System.arraycopy(defaultBlocks, 0, allBlocks, 0, defaultBlocks.length);
            System.arraycopy(collectionBlocks, 0, allBlocks, defaultBlocks.length, collectionBlocks.length);

            return BlockEntityType.Builder.of(
                BoxBlockEntity::new,
                allBlocks
            ).build(null);
        });

    public static final RegistrySupplier<BlockEntityType<ClawMachineBlockEntity>> CLAW_MACHINE_BLOCK =
        BLOCK_ENTITIES.register("claw_machine_block", () ->
            BlockEntityType.Builder.of(
                ClawMachineBlockEntity::new,
                ModBlocks.CLAW_MACHINE_BLOCK.get()
            ).build(null)
        );

    public static void register() {
        BLOCK_ENTITIES.register();
    }
}
