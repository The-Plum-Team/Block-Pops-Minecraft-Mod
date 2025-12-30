package com.theplumteam.data.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Forge implementation of PlayerDataManager platform methods.
 */
@SuppressWarnings("unused")
public class PlayerDataManagerImpl {

    /**
     * Get the persistent data CompoundTag for a player.
     * On Forge, this uses the player's getPersistentData() method.
     * @param player The player
     * @return The persistent data CompoundTag
     */
    public static CompoundTag getPersistentData(Player player) {
        return player.getPersistentData();
    }
}
