package com.theplumteam.data.fabric;

import com.theplumteam.BlockPopsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

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
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, playerData) -> {
            playersTag.put(uuid.toString(), playerData.copy());
        });
        tag.put("players", playersTag);
        return tag;
    }

    public static StateSaverAndLoader createFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        StateSaverAndLoader state = new StateSaverAndLoader();
        CompoundTag playersTag = tag.getCompound("players");
        playersTag.getAllKeys().forEach(key -> {
            try {
                UUID uuid = UUID.fromString(key);
                state.players.put(uuid, playersTag.getCompound(key).copy());
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
        StateSaverAndLoader state = persistentStateManager.computeIfAbsent(
                new SavedData.Factory<>(
                        StateSaverAndLoader::new,
                        StateSaverAndLoader::createFromTag,
                        null // DataFixTypes - null for no data fixing
                ),
                BlockPopsMod.MOD_ID + "_player_data"
        );
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
