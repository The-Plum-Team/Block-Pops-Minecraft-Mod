package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.FigureBlock;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK);

    // Single box block - collection/color determined by NBT, not registration
    // This matches the architecture used by figure blocks for better maintainability
    public static final RegistrySupplier<Block> BOX_BLOCK = BLOCKS.register(
        "box_block",
        () -> new BoxBlock(
            BlockBehaviour.Properties.of()
                .strength(0.5F, 1.0F)
                .noOcclusion()
        )
    );

    public static final RegistrySupplier<Block> CLAW_MACHINE_BLOCK = BLOCKS.register(
        "claw_machine_block",
        () -> new ClawMachineBlock(
            BlockBehaviour.Properties.of()
                .strength(1.5F, 6.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
        )
    );

    public static final RegistrySupplier<Block> FIGURE_BLOCK = BLOCKS.register(
        "figure_block",
        () -> new FigureBlock(
            BlockBehaviour.Properties.of()
                .strength(0.5F, 1.0F)
                .noOcclusion()
        )
    );

    public static void register() {
        BLOCKS.register();
    }
}
