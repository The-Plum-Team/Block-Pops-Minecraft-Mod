package com.theplumteam.data.neoforge;

import com.theplumteam.BlockPopsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import com.mojang.serialization.Codec;

import java.util.HashMap;
import java.util.UUID;

/**
 * NeoForge SavedData for saving player data.
 * Data is stored per-world and persists across server restarts.
 */
public class StateSaverAndLoader extends SavedData {

    // Store player data keyed by UUID
    private final HashMap<UUID, CompoundTag> players = new HashMap<>();

    // In 1.21.5, save() signature may have changed
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, playerData) -> {
            playersTag.put(uuid.toString(), playerData.copy());
        });
        tag.put("players", playersTag);
        return tag;
    }

    public static StateSaverAndLoader createFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        StateSaverAndLoader state = new StateSaverAndLoader();
        CompoundTag playersTag = tag.getCompoundOrEmpty("players");
        playersTag.keySet().forEach(key -> {
            try {
                UUID uuid = UUID.fromString(key);
                state.players.put(uuid, playersTag.getCompoundOrEmpty(key).copy());
            } catch (IllegalArgumentException e) {
                BlockPopsMod.LOGGER.warn("Invalid UUID in saved data: {}", key);
            }
        });
        return state;
    }

    /**
     * Get the server state from the overworld's data storage.
     */
    // Simple Codec that delegates to our NBT methods
    private static final Codec<StateSaverAndLoader> CODEC = CompoundTag.CODEC.xmap(
        tag -> StateSaverAndLoader.createFromTag(tag, null), // decode
        state -> {
            CompoundTag tag = new CompoundTag();
            return state.save(tag, null);
        } // encode
    );

    // SavedDataType for 1.21.5+
    private static final SavedDataType<StateSaverAndLoader> TYPE =
        new SavedDataType<StateSaverAndLoader>(
            BlockPopsMod.MOD_ID + "_player_data", // name
            StateSaverAndLoader::new, // supplier when no data exists
            CODEC, // codec for serialization
            null // No data fixer
        );

    public static StateSaverAndLoader getServerState(MinecraftServer server) {
        var persistentStateManager = server.overworld().getDataStorage();
        // In 1.21.5+, computeIfAbsent only takes the SavedDataType (name is in the type)
        StateSaverAndLoader state = persistentStateManager.computeIfAbsent(TYPE);
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
