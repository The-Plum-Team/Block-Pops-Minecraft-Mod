package com.theplumteam.data;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.TagReads;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.WeakHashMap;

/**
 * Cross-platform manager for player discovery data.
 * Uses platform-specific persistent data storage.
 */
public class PlayerDataManager {
    public static final String DATA_KEY = BlockPopsMod.MOD_ID + "_discovery";

    // Cache to avoid repeated deserialization
    private static final WeakHashMap<Player, PlayerDiscovery> cache = new WeakHashMap<>();

    /**
     * Get the player discovery data for a player.
     * This loads from NBT if not cached.
     * @param player The player to get data for
     * @return The player's discovery data
     */
    public static IPlayerDiscovery getDiscovery(Player player) {
        // Check cache first
        PlayerDiscovery cached = cache.get(player);
        if (cached != null) {
            return cached;
        }

        // Load from persistent data using platform-specific implementation
        PlayerDiscovery discovery = new PlayerDiscovery();
        CompoundTag persistentData = getPersistentData(player);

        if (TagReads.hasCompound(persistentData, DATA_KEY)) {
            discovery.deserializeNBT(TagReads.compound(persistentData, DATA_KEY));
        }

        cache.put(player, discovery);
        return discovery;
    }

    /**
     * Save the player discovery data to NBT.
     * Call this after modifying the discovery data to persist changes.
     * @param player The player to save data for
     */
    public static void saveDiscovery(Player player) {
        PlayerDiscovery discovery = cache.get(player);
        if (discovery != null) {
            getPersistentData(player).put(DATA_KEY, discovery.serializeNBT());
        }
    }

    /**
     * Mark the player's discovery data as dirty and save it.
     * Use this after making changes to the discovery data.
     * @param player The player whose data was modified
     * @param discovery The discovery data that was modified
     */
    public static void markDirty(Player player, IPlayerDiscovery discovery) {
        if (discovery instanceof PlayerDiscovery pd) {
            cache.put(player, pd);
            getPersistentData(player).put(DATA_KEY, pd.serializeNBT());
        }
    }

    /**
     * Copy discovery data from one player to another.
     * Used for handling player respawns and dimension changes.
     * @param from The original player
     * @param to The new player instance
     */
    public static void copyData(Player from, Player to) {
        CompoundTag fromData = getPersistentData(from);
        if (TagReads.hasCompound(fromData, DATA_KEY)) {
            getPersistentData(to).put(DATA_KEY, TagReads.compound(fromData, DATA_KEY).copy());
            // Invalidate cache for new player so it loads fresh
            cache.remove(to);
        }
    }

    /**
     * Clear the cache for a player.
     * Call this when a player disconnects.
     * @param player The player to clear from cache
     */
    public static void clearCache(Player player) {
        cache.remove(player);
    }

    /**
     * Get the persistent data CompoundTag for a player.
     * Platform-specific implementation.
     * @param player The player
     * @return The persistent data CompoundTag
     */
    @ExpectPlatform
    public static CompoundTag getPersistentData(Player player) {
        throw new AssertionError("Not implemented");
    }
}
