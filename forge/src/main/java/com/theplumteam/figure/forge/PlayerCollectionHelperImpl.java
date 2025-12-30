package com.theplumteam.figure.forge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge implementation of PlayerCollectionHelper.
 */
public class PlayerCollectionHelperImpl {

    /**
     * Generate the World Players collection based on server players.
     * Uses Forge capabilities to access player discovery data.
     */
    public static FigureCollection generate(MinecraftServer server) {
        return PlayerCollectionGenerator.generate(server);
    }

    /**
     * Regenerate the World Players collection and sync it to all players.
     * Uses Forge capabilities to access player discovery data.
     */
    public static void regenerateAndSyncPlayerCollection(MinecraftServer server) {
        FigureCollection updatedCollection = PlayerCollectionGenerator.generate(server);
        CollectionRegistry.registerDynamicCollection(updatedCollection);
        BlockPopsMod.LOGGER.info("Regenerated World Players collection");

        // Broadcast update to all players
        List<FigureCollection> dynamicCollections = new ArrayList<>();
        dynamicCollections.add(updatedCollection);
        SyncDynamicCollectionsPacket.sendToAllPlayers(server, dynamicCollections);
    }
}
