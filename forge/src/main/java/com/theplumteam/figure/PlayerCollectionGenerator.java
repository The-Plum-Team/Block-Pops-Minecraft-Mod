package com.theplumteam.figure;

import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates a dynamic figure collection based on players who have joined the world.
 * Scans the world's playerdata folder and creates figures for each player.
 */
public class PlayerCollectionGenerator {

    private static final String COLLECTION_ID = "world_players";
    private static final String COLLECTION_NAME = "World Players";

    /**
     * Generates a FigureCollection containing all players who have joined the world.
     *
     * @param server The Minecraft server instance
     * @return A FigureCollection with player figures, or null if generation fails
     */
    public static FigureCollection generate(MinecraftServer server) {
        try {
            // Get the playerdata directory from the world save
            Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            File playerdataDir = worldPath.resolve("playerdata").toFile();

            if (!playerdataDir.exists() || !playerdataDir.isDirectory()) {
                BlockPopsMod.LOGGER.warn("Playerdata directory not found, World Players collection will be empty");
                return createEmptyCollection();
            }

            // Get all .dat files (each represents a player)
            File[] playerFiles = playerdataDir.listFiles((dir, name) -> name.endsWith(".dat"));

            if (playerFiles == null || playerFiles.length == 0) {
                BlockPopsMod.LOGGER.info("No player data files found, World Players collection will be empty");
                return createEmptyCollection();
            }

            List<FigureDefinition> playerFigures = new ArrayList<>();
            GameProfileCache profileCache = server.getProfileCache();

            // Use default model and animation paths
            ResourceLocation defaultModel = new ResourceLocation("blockpops", "geo/figure/box_figure_default.geo.json");
            ResourceLocation defaultAnimation = new ResourceLocation("blockpops", "animations/figure/box_figure_default.animation.json");

            for (File playerFile : playerFiles) {
                try {
                    // Extract UUID from filename (remove .dat extension)
                    String filename = playerFile.getName();
                    String uuidString = filename.substring(0, filename.length() - 4);
                    UUID playerUUID = UUID.fromString(uuidString);

                    // Try to get the player's name from the profile cache
                    String playerName = "Unknown Player";
                    if (profileCache != null) {
                        Optional<GameProfile> profile = profileCache.get(playerUUID);
                        if (profile.isPresent()) {
                            playerName = profile.get().getName();
                        } else {
                            // Fallback: use a shortened UUID if name not found
                            playerName = "Player " + uuidString.substring(0, 8);
                        }
                    }

                    // Create a player figure definition
                    FigureDefinition playerFigure = new FigureDefinition(
                        uuidString,  // Use UUID as the figure ID
                        playerName,
                        defaultModel,
                        defaultAnimation,
                        playerUUID
                    );

                    playerFigures.add(playerFigure);
                    BlockPopsMod.LOGGER.debug("Added player figure: {} ({})", playerName, playerUUID);

                } catch (IllegalArgumentException e) {
                    BlockPopsMod.LOGGER.warn("Failed to parse player UUID from file: {}", playerFile.getName());
                }
            }

            BlockPopsMod.LOGGER.info("Generated World Players collection with {} figures", playerFigures.size());

            // Use the default/original box texture
            ResourceLocation boxTexture = new ResourceLocation("blockpops", "textures/block/box/default.png");

            return new FigureCollection(
                COLLECTION_ID,
                COLLECTION_NAME,
                boxTexture,
                null,  // No logo texture for now
                "square",
                playerFigures
            );

        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to generate World Players collection", e);
            return createEmptyCollection();
        }
    }

    /**
     * Creates an empty World Players collection as a fallback
     */
    private static FigureCollection createEmptyCollection() {
        ResourceLocation boxTexture = new ResourceLocation("blockpops", "textures/block/box/default.png");
        return new FigureCollection(
            COLLECTION_ID,
            COLLECTION_NAME,
            boxTexture,
            null,
            "square",
            new ArrayList<>()
        );
    }

    /**
     * Gets the collection ID for the World Players collection
     */
    public static String getCollectionId() {
        return COLLECTION_ID;
    }
}
