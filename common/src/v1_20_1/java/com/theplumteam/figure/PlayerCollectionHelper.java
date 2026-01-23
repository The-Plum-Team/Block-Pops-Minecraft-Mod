package com.theplumteam.figure;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.MinecraftServer;

/**
 * Platform-abstracted helper for player collection operations.
 * The actual implementation uses platform-specific player data access.
 */
public class PlayerCollectionHelper {

    /**
     * The collection ID for the World Players collection.
     */
    public static final String WORLD_PLAYERS_COLLECTION_ID = "world_players";

    /**
     * Generate the World Players collection based on server players.
     * This is platform-specific because it needs to access player discovery data
     * which is stored differently on Forge (capabilities) vs Fabric.
     *
     * @param server The Minecraft server instance
     * @return The generated FigureCollection for world players
     */
    @ExpectPlatform
    public static FigureCollection generate(MinecraftServer server) {
        throw new AssertionError("Not implemented");
    }

    /**
     * Regenerate the World Players collection and sync it to all players.
     * This is platform-specific because it needs to access player discovery data
     * which is stored differently on Forge (capabilities) vs Fabric.
     *
     * @param server The Minecraft server instance
     */
    @ExpectPlatform
    public static void regenerateAndSyncPlayerCollection(MinecraftServer server) {
        throw new AssertionError("Not implemented");
    }
}
