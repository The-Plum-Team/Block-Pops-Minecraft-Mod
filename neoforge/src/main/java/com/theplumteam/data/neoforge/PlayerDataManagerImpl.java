package com.theplumteam.data.neoforge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge implementation of PlayerDataManager platform methods.
 * Uses SavedData for data persistence.
 */
@SuppressWarnings("unused")
public class PlayerDataManagerImpl {

    /**
     * Get the persistent data CompoundTag for a player.
     * On NeoForge, this uses SavedData for persistence.
     * @param player The player
     * @return The persistent data CompoundTag
     */
    public static CompoundTag getPersistentData(Player player) {
        return StateSaverAndLoader.getPlayerState(player);
    }
}
