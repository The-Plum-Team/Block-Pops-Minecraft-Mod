package com.theplumteam.figure;

import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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

            List<FigureDefinition> playerFigures = new ArrayList<>();
            Set<UUID> processedPlayers = new HashSet<>(); // Track which players we've already added
            GameProfileCache profileCache = server.getProfileCache();

            // Use default model and animation paths
            ResourceLocation defaultModel = new ResourceLocation("blockpops", "geo/figure/box_figure_default.geo.json");
            ResourceLocation defaultAnimation = new ResourceLocation("blockpops", "animations/figure/box_figure_default.animation.json");

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
                                Optional<GameProfile> profile = profileCache.get(playerUUID);
                                if (profile.isPresent()) {
                                    playerName = profile.get().getName();
                                } else {
                                    // Fallback: use a shortened UUID if name not found
                                    playerName = "Player " + uuidString.substring(0, 8);
                                }
                            }

                            // Load player's favorite color - check if they're online first
                            PopBlockColor defaultColor = ServerConfig.getInstance().getDefaultPlayerColor();
                            PopBlockColor favoriteColor = defaultColor;

                            // Try to get the color from the online player's in-memory capability first
                            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerUUID);
                            if (onlinePlayer != null) {
                                // Player is online, read from their in-memory capability
                                AtomicReference<PopBlockColor> colorRef = new AtomicReference<>(defaultColor);
                                onlinePlayer.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                                    PopBlockColor color = discovery.getFavoriteColor();
                                    if (color != null) {
                                        colorRef.set(color);
                                    }
                                });
                                favoriteColor = colorRef.get();
                                BlockPopsMod.LOGGER.debug("Loaded favorite color from online player {}: {}", playerName, favoriteColor.getSerializedName());
                            } else {
                                // Player is offline, read from disk
                                try {
                                    File playerDataFile = new File(playerdataDir, uuidString + ".dat");
                                    if (playerDataFile.exists()) {
                                        CompoundTag playerData = NbtIo.readCompressed(playerDataFile);
                                        if (playerData != null) {
                                            CompoundTag capabilities = playerData.getCompound("ForgeCaps");
                                            if (capabilities.contains("blockpops:player_discovery")) {
                                                CompoundTag discoveryTag = capabilities.getCompound("blockpops:player_discovery");
                                                if (discoveryTag.contains("FavoriteColor", Tag.TAG_STRING)) {
                                                    try {
                                                        favoriteColor = PopBlockColor.valueOf(discoveryTag.getString("FavoriteColor").toUpperCase());
                                                    } catch (IllegalArgumentException e) {
                                                        BlockPopsMod.LOGGER.warn("Invalid favorite color found for player {}, defaulting to ORIGINAL", playerUUID);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    BlockPopsMod.LOGGER.debug("Loaded favorite color from disk for offline player {}: {}", playerName, favoriteColor.getSerializedName());
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

                // Get favorite color from online player's capability
                PopBlockColor defaultColorOnline = ServerConfig.getInstance().getDefaultPlayerColor();
                PopBlockColor favoriteColor = defaultColorOnline;
                AtomicReference<PopBlockColor> colorRef = new AtomicReference<>(defaultColorOnline);
                onlinePlayer.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    PopBlockColor color = discovery.getFavoriteColor();
                    if (color != null) {
                        colorRef.set(color);
                    }
                });
                favoriteColor = colorRef.get();

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
            ResourceLocation boxTexture = new ResourceLocation("blockpops", "textures/block/box/default.png");

            // Create logo configuration for World Players collection
            ResourceLocation logoTexture = new ResourceLocation("blockpops", "textures/block/box/logo/logo_worldplayers.png");
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
        ResourceLocation boxTexture = new ResourceLocation("blockpops", "textures/block/box/default.png");

        // Create logo configuration for World Players collection
        ResourceLocation logoTexture = new ResourceLocation("blockpops", "textures/block/box/logo/logo_worldplayers.png");
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
     * Gets the collection ID for the World Players collection
     */
    public static String getCollectionId() {
        return COLLECTION_ID;
    }
}
