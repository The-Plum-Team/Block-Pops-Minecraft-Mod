package com.theplumteam.figure.fabric;

import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.data.PlayerDiscovery;
import com.theplumteam.data.fabric.StateSaverAndLoader;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.TagReads;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=26 {
/*import net.minecraft.server.players.UserNameToIdResolver;
*///? } else {
import net.minecraft.server.players.GameProfileCache;
//? }

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Fabric implementation of PlayerCollectionHelper.
 * Uses cross-platform PlayerDataManager for player data access.
 */
public class PlayerCollectionHelperImpl {

    private static final String COLLECTION_ID = "world_players";
    private static final String COLLECTION_NAME = "World Players";

    /**
     * Generate the World Players collection based on server players.
     * Uses cross-platform PlayerDataManager to access player discovery data.
     */
    public static FigureCollection generate(MinecraftServer server) {
        try {
            // Get the playerdata directory from the world save
            Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            File playerdataDir = worldPath.resolve("playerdata").toFile();

            List<FigureDefinition> playerFigures = new ArrayList<>();
            Set<UUID> processedPlayers = new HashSet<>();
            //? if >=26 {
            /*// 26.1 replaced the profile cache with a name/id resolver on the services.
            UserNameToIdResolver profileCache = server.services().nameToIdCache();
            *///? } else {
            GameProfileCache profileCache = server.getProfileCache();
            //? }

            // Use default model and animation paths
            ResourceLocation defaultModel = GeoAssets.model("blockpops", "figure/box_figure_default");
            ResourceLocation defaultAnimation = GeoAssets.animation("blockpops", "figure/box_figure_default");

            // First, process existing .dat files (if directory exists)
            if (playerdataDir.exists() && playerdataDir.isDirectory()) {
                File[] playerFiles = playerdataDir.listFiles((dir, name) -> name.endsWith(".dat"));

                if (playerFiles != null && playerFiles.length > 0) {
                    for (File playerFile : playerFiles) {
                        try {
                            // Extract UUID from filename (remove .dat extension)
                            String filename = playerFile.getName();
                            String uuidString = filename.substring(0, filename.length() - 4);
                            UUID playerUUID = UUID.fromString(uuidString);

                            // Try to get the player's name from the profile cache
                            String playerName = "Unknown Player";
                            if (profileCache != null) {
                                //? if >=26 {
                                /*Optional<net.minecraft.server.players.NameAndId> profile =
                                        profileCache.get(playerUUID);
                                if (profile.isPresent()) {
                                    playerName = profile.get().name();
                                *///? } else {
                                Optional<GameProfile> profile = profileCache.get(playerUUID);
                                if (profile.isPresent()) {
                                    playerName = profile.get().getName();
                                //? }
                                } else {
                                    // Fallback: use a shortened UUID if name not found
                                    playerName = "Player " + uuidString.substring(0, 8);
                                }
                            }

                            // Load player's favorite color - check if they're online first
                            PopBlockColor defaultColor = ServerConfig.getInstance().getDefaultPlayerColor();
                            PopBlockColor favoriteColor = defaultColor;

                            // Try to get the color from the online player's in-memory data first
                            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerUUID);
                            if (onlinePlayer != null) {
                                // Player is online, read from their in-memory data
                                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(onlinePlayer);
                                if (discovery.hasChosenFavoriteColor() && discovery.getFavoriteColor() != null) {
                                    favoriteColor = discovery.getFavoriteColor();
                                }
                                BlockPopsMod.LOGGER.debug("Loaded favorite color from online player {}: {}", playerName, favoriteColor.getSerializedName());
                            } else {
                                // Player is offline, read from the world saved data (Fabric keeps player data
                                // in StateSaverAndLoader, not in the player's .dat file)
                                try {
                                    CompoundTag playerData = StateSaverAndLoader.findPlayerState(server, playerUUID);
                                    if (playerData != null) {
                                        PlayerDiscovery savedDiscovery = new PlayerDiscovery();
                                        savedDiscovery.deserializeNBT(TagReads.compound(playerData, PlayerDataManager.DATA_KEY));
                                        if (savedDiscovery.hasChosenFavoriteColor() && savedDiscovery.getFavoriteColor() != null) {
                                            favoriteColor = savedDiscovery.getFavoriteColor();
                                        }
                                    }
                                    BlockPopsMod.LOGGER.debug("Loaded favorite color from saved data for offline player {}: {}", playerName, favoriteColor.getSerializedName());
                                } catch (Exception e) {
                                    BlockPopsMod.LOGGER.warn("Failed to load favorite color for player {}, defaulting to ORIGINAL: {}", playerUUID, e.getMessage());
                                }
                            }

                            // Create a player figure definition WITH the color
                            FigureDefinition playerFigure = new FigureDefinition(
                                uuidString,  // Use UUID as the figure ID
                                playerName,
                                defaultModel,
                                defaultAnimation,
                                playerUUID,
                                favoriteColor  // Pass the color
                            );

                            playerFigures.add(playerFigure);
                            processedPlayers.add(playerUUID);
                            BlockPopsMod.LOGGER.debug("Added player figure from .dat file: {} ({})", playerName, playerUUID);

                        } catch (IllegalArgumentException e) {
                            BlockPopsMod.LOGGER.warn("Failed to parse player UUID from file: {}", playerFile.getName());
                        }
                    }
                }
            }

            // Second, add any online players who don't have .dat files yet (e.g., first-time joiners)
            List<ServerPlayer> onlinePlayers = server.getPlayerList().getPlayers();
            for (ServerPlayer onlinePlayer : onlinePlayers) {
                UUID playerUUID = onlinePlayer.getUUID();

                // Skip if we already processed this player from a .dat file
                if (processedPlayers.contains(playerUUID)) {
                    continue;
                }

                String playerName = onlinePlayer.getName().getString();
                String uuidString = playerUUID.toString();

                // Get favorite color from online player's data
                PopBlockColor defaultColorOnline = ServerConfig.getInstance().getDefaultPlayerColor();
                PopBlockColor favoriteColor = defaultColorOnline;
                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(onlinePlayer);
                if (discovery.hasChosenFavoriteColor() && discovery.getFavoriteColor() != null) {
                    favoriteColor = discovery.getFavoriteColor();
                }

                // Create a player figure definition
                FigureDefinition playerFigure = new FigureDefinition(
                    uuidString,  // Use UUID as the figure ID
                    playerName,
                    defaultModel,
                    defaultAnimation,
                    playerUUID,
                    favoriteColor  // Pass the color
                );

                playerFigures.add(playerFigure);
                processedPlayers.add(playerUUID);
                BlockPopsMod.LOGGER.debug("Added online player figure (no .dat file yet): {} ({})", playerName, playerUUID);
            }

            BlockPopsMod.logDebug("Generated World Players collection with {} figures", playerFigures.size());

            // Use the default/original box texture
            ResourceLocation boxTexture = ResourceLocations.of("blockpops", "textures/block/box/default.png");

            // Create logo configuration for World Players collection
            ResourceLocation logoTexture = ResourceLocations.of("blockpops", "textures/block/box/logo/logo_worldplayers.png");
            FigureCollection.LogoConfig logoConfig = new FigureCollection.LogoConfig(
                logoTexture,
                -0.915f,  // positionX
                -0.165f,  // positionY
                -0.001f,  // positionZ
                4.004f,   // scaleX (Width)
                4.503f,   // scaleY (Height)
                1.0f      // scaleZ
            );

            return new FigureCollection(
                COLLECTION_ID,
                COLLECTION_NAME,
                "Minecraft",  // Author for player collection
                null,  // No URL for player collection
                boxTexture,
                logoConfig,  // Logo configuration
                playerFigures,
                new int[]{152, 48, 167}  // Purple background color
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
        ResourceLocation boxTexture = ResourceLocations.of("blockpops", "textures/block/box/default.png");

        // Create logo configuration for World Players collection
        ResourceLocation logoTexture = ResourceLocations.of("blockpops", "textures/block/box/logo/logo_worldplayers.png");
        FigureCollection.LogoConfig logoConfig = new FigureCollection.LogoConfig(
            logoTexture,
            -0.915f,  // positionX
            -0.165f,  // positionY
            -0.001f,  // positionZ
            4.004f,   // scaleX (Width)
            4.503f,   // scaleY (Height)
            1.0f      // scaleZ
        );

        return new FigureCollection(
            COLLECTION_ID,
            COLLECTION_NAME,
            "Minecraft",  // Author for player collection
            null,  // No URL for player collection
            boxTexture,
            logoConfig,  // Logo configuration
            new ArrayList<>(),
            new int[]{152, 48, 167}  // Purple background color
        );
    }

    /**
     * Regenerate the World Players collection and sync it to all players.
     * Uses cross-platform PlayerDataManager to access player discovery data.
     */
    public static void regenerateAndSyncPlayerCollection(MinecraftServer server) {
        FigureCollection updatedCollection = generate(server);
        CollectionRegistry.registerDynamicCollection(updatedCollection);
        BlockPopsMod.logDebug("Regenerated World Players collection");

        // Broadcast update to all players
        List<FigureCollection> dynamicCollections = new ArrayList<>();
        dynamicCollections.add(updatedCollection);
        SyncDynamicCollectionsPacket.sendToAllPlayers(server, dynamicCollections);
    }
}
