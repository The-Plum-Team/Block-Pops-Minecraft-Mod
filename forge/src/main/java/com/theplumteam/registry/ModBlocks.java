package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.PopBlockColor;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.HashMap;
import java.util.Map;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK);

    public static final Map<PopBlockColor, RegistrySupplier<Block>> BOX_BLOCKS = new HashMap<>();

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
        for (PopBlockColor color : PopBlockColor.values()) {
            BOX_BLOCKS.put(color, BLOCKS.register(
                "box_block_" + color.getSerializedName(),
                () -> new BoxBlock(
                    BlockBehaviour.Properties.of()
                        .mapColor(color.getMapColor())
                        .strength(1.5F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .noOcclusion(),
                    color
                )
            ));
        }
    }

    public static void register() {
        BLOCKS.register();
    }
}
