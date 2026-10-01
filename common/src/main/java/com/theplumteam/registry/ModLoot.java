package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.loot.WorldPlayersBoxLootEntry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
//? if >=26 {
/*import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
*///? } else {
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
//? }

public class ModLoot {
    //? if >=26 {
    /*// 26.1 registers the entry codec itself; LootPoolEntryType no longer exists.
    public static final DeferredRegister<MapCodec<? extends LootPoolEntryContainer>> LOOT_POOL_ENTRY_TYPES =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.LOOT_POOL_ENTRY_TYPE);

    public static final RegistrySupplier<MapCodec<? extends LootPoolEntryContainer>> WORLD_PLAYERS_BOX =
        LOOT_POOL_ENTRY_TYPES.register("world_players_box", () -> WorldPlayersBoxLootEntry.CODEC);
    *///? } else {
    public static final DeferredRegister<LootPoolEntryType> LOOT_POOL_ENTRY_TYPES =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.LOOT_POOL_ENTRY_TYPE);

    public static final RegistrySupplier<LootPoolEntryType> WORLD_PLAYERS_BOX =
        LOOT_POOL_ENTRY_TYPES.register("world_players_box", () -> new LootPoolEntryType(
            //? if >=1.21 {
            /*WorldPlayersBoxLootEntry.CODEC
            *///? } else {
            new WorldPlayersBoxLootEntry.Serializer()
            //? }
        ));
    //? }

    public static void register() {
        LOOT_POOL_ENTRY_TYPES.register();
    }
}
