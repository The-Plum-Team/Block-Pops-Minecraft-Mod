package com.theplumteam.data.neoforge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge implementation of PlayerDataManager platform methods.
 */
@SuppressWarnings("unused")
public class PlayerDataManagerImpl {

    /**
     * Get the persistent data CompoundTag for a player.
     * On NeoForge this uses the player's getPersistentData(), stored as NeoForgeData.
     * @param player The player
     * @return The persistent data CompoundTag
     */
    public static CompoundTag getPersistentData(Player player) {
        return player.getPersistentData();
    }
}
