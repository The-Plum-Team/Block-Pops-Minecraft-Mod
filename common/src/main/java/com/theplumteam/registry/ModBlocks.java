package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.FigureBlock;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
//? if >=1.21.2 {
/*import net.minecraft.resources.ResourceKey;
import com.theplumteam.util.ResourceLocations;
*///? }
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {
    //? if >=1.21.2 {
    /*// 1.21.2 made BlockBehaviour.Properties require its registry id up front.
    private static ResourceKey<Block> blockKey(String name) {
        return ResourceKey.create(Registries.BLOCK, ResourceLocations.of(BlockPopsMod.MOD_ID, name));
    }

    *///? }
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK);

    // Single box block - collection/color determined by NBT, not registration
    // This matches the architecture used by figure blocks for better maintainability
    public static final RegistrySupplier<Block> BOX_BLOCK = BLOCKS.register(
        "box_block",
        () -> new BoxBlock(
            BlockBehaviour.Properties.of()
            //? if >=1.21.2 {
            /*.setId(blockKey("box_block"))
            *///? }
                .strength(0.5F, 1.0F)
                .noOcclusion()
        )
    );

    public static final RegistrySupplier<Block> CLAW_MACHINE_BLOCK = BLOCKS.register(
        "claw_machine_block",
        () -> new ClawMachineBlock(
            BlockBehaviour.Properties.of()
            //? if >=1.21.2 {
            /*.setId(blockKey("claw_machine_block"))
            *///? }
                .strength(1.5F, 6.0F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
        )
    );

    public static final RegistrySupplier<Block> FIGURE_BLOCK = BLOCKS.register(
        "figure_block",
        () -> new FigureBlock(
            BlockBehaviour.Properties.of()
            //? if >=1.21.2 {
            /*.setId(blockKey("figure_block"))
            *///? }
                .strength(0.5F, 1.0F)
                .noOcclusion()
        )
    );

    public static void register() {
        BLOCKS.register();
    }
}
