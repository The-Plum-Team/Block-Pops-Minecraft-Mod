package com.theplumteam.data.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.TagReads;
//? if >=1.21 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.util.datafix.DataFixTypes;
*///? }
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.UUID;

/**
 * Fabric's built-in PersistentState for saving player data.
 * Data is stored per-world and persists across server restarts.
 */
public class StateSaverAndLoader extends SavedData {

    // Store player data keyed by UUID
    private final HashMap<UUID, CompoundTag> players = new HashMap<>();

    @Override
    //? if >=1.21 {
    /*public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    *///? } else {
    public CompoundTag save(CompoundTag tag) {
    //? }
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, playerData) -> {
            playersTag.put(uuid.toString(), playerData.copy());
        });
        tag.put("players", playersTag);
        return tag;
    }

    //? if >=1.21 {
    /*public static StateSaverAndLoader createFromTag(CompoundTag tag, HolderLookup.Provider registries) {
    *///? } else {
    public static StateSaverAndLoader createFromTag(CompoundTag tag) {
    //? }
        StateSaverAndLoader state = new StateSaverAndLoader();
        CompoundTag playersTag = TagReads.compound(tag, "players");
        playersTag.getAllKeys().forEach(key -> {
            try {
                UUID uuid = UUID.fromString(key);
                state.players.put(uuid, TagReads.compound(playersTag, key).copy());
            } catch (IllegalArgumentException e) {
                BlockPopsMod.LOGGER.warn("Invalid UUID in saved data: {}", key);
            }
        });
        return state;
    }

    /**
     * Get the server state from the overworld's data storage.
     */
    public static StateSaverAndLoader getServerState(MinecraftServer server) {
        var persistentStateManager = server.overworld().getDataStorage();
        //? if >=1.21 {
        /*StateSaverAndLoader state = persistentStateManager.computeIfAbsent(
                new SavedData.Factory<>(
                        StateSaverAndLoader::new,
                        StateSaverAndLoader::createFromTag,
                        // A null type makes vanilla's reader throw and silently discard saved data.
                        DataFixTypes.SAVED_DATA_COMMAND_STORAGE
                ),
                BlockPopsMod.MOD_ID + "_player_data"
        );
        *///? } else {
        StateSaverAndLoader state = persistentStateManager.computeIfAbsent(
                StateSaverAndLoader::createFromTag,
                StateSaverAndLoader::new,
                BlockPopsMod.MOD_ID + "_player_data"
        );
        //? }
        state.setDirty(); // Mark for saving
        return state;
    }

    /**
     * Get the persistent data CompoundTag for a specific player.
     */
    public static CompoundTag getPlayerState(Player player) {
        if (player.level().isClientSide()) {
            // Client-side: return empty tag (data is server-side only)
            return new CompoundTag();
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return new CompoundTag();
        }

        StateSaverAndLoader serverState = getServerState(server);
        return serverState.players.computeIfAbsent(player.getUUID(), uuid -> new CompoundTag());
    }
}
