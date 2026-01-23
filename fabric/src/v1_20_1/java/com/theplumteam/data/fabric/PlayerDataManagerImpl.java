package com.theplumteam.data.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric implementation of PlayerDataManager platform methods.
 * Uses Fabric's built-in PersistentState (SavedData) for data persistence.
 */
@SuppressWarnings("unused")
public class PlayerDataManagerImpl {

    /**
     * Get the persistent data CompoundTag for a player.
     * On Fabric, this uses SavedData (PersistentState) for persistence.
     * @param player The player
     * @return The persistent data CompoundTag
     */
    public static CompoundTag getPersistentData(Player player) {
        return StateSaverAndLoader.getPlayerState(player);
    }
}
