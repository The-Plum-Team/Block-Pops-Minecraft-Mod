package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.platform.PlatformHelper;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.blockentity.GachaMachineBlockEntity;
import com.theplumteam.blockentity.PlushBlockEntity;
import com.theplumteam.plush.BuiltInPlushes;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.LinkedHashMap;
import java.util.Map;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<BoxBlockEntity>> BOX_BLOCK =
        BLOCK_ENTITIES.register("box_block", () ->
            //? if >=1.21.2 {
            /*PlatformHelper.createBlockEntityType(BoxBlockEntity::new, ModBlocks.BOX_BLOCK.get())
            *///? } else {
            BlockEntityType.Builder.of(
                BoxBlockEntity::new,
                ModBlocks.BOX_BLOCK.get()
            ).build(null)
            //? }
        );

    public static final RegistrySupplier<BlockEntityType<ClawMachineBlockEntity>> CLAW_MACHINE_BLOCK =
        BLOCK_ENTITIES.register("claw_machine_block", () ->
            //? if >=1.21.2 {
            /*PlatformHelper.createBlockEntityType(ClawMachineBlockEntity::new, ModBlocks.CLAW_MACHINE_BLOCK.get())
            *///? } else {
            BlockEntityType.Builder.of(
                ClawMachineBlockEntity::new,
                ModBlocks.CLAW_MACHINE_BLOCK.get()
            ).build(null)
            //? }
        );

    public static final RegistrySupplier<BlockEntityType<GachaMachineBlockEntity>> GACHA_MACHINE_BLOCK =
        BLOCK_ENTITIES.register("gacha_machine_block", () ->
            //? if >=1.21.2 {
            /*PlatformHelper.createBlockEntityType(GachaMachineBlockEntity::new, ModBlocks.GACHA_MACHINE_BLOCK.get())
            *///? } else {
            BlockEntityType.Builder.of(
                GachaMachineBlockEntity::new,
                ModBlocks.GACHA_MACHINE_BLOCK.get()
            ).build(null)
            //? }
        );

    public static final RegistrySupplier<BlockEntityType<FigureBlockEntity>> FIGURE_BLOCK =
        BLOCK_ENTITIES.register("figure_block", () ->
            //? if >=1.21.2 {
            /*PlatformHelper.createBlockEntityType(FigureBlockEntity::new, ModBlocks.FIGURE_BLOCK.get())
            *///? } else {
            BlockEntityType.Builder.of(
                FigureBlockEntity::new,
                ModBlocks.FIGURE_BLOCK.get()
            ).build(null)
            //? }
        );

    // Each plush has its own type, so its renderer can own a model with fixed resources.
    public static final Map<String, RegistrySupplier<BlockEntityType<PlushBlockEntity>>> PLUSH_BLOCKS = new LinkedHashMap<>();

    static {
        for (String plushId : BuiltInPlushes.PLUSH_IDS) {
            PLUSH_BLOCKS.put(plushId, BLOCK_ENTITIES.register("plush_" + plushId, () ->
                //? if >=1.21.2 {
                /*PlatformHelper.<PlushBlockEntity>createBlockEntityType(
                    (pos, state) -> new PlushBlockEntity(PLUSH_BLOCKS.get(plushId).get(), pos, state),
                    ModBlocks.PLUSH_BLOCKS.get(plushId).get())
                *///? } else {
                BlockEntityType.Builder.<PlushBlockEntity>of(
                    (pos, state) -> new PlushBlockEntity(PLUSH_BLOCKS.get(plushId).get(), pos, state),
                    ModBlocks.PLUSH_BLOCKS.get(plushId).get()
                ).build(null)
                //? }
            ));
        }
    }

    public static void register() {
        BLOCK_ENTITIES.register();
    }
}
