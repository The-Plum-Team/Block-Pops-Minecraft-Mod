package com.theplumteam.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.command.ModCommands;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import com.theplumteam.network.SyncDiscoveryDataPacket;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import com.theplumteam.data.IPlayerDiscovery;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

@Mod(BlockPopsMod.MOD_ID)
public final class BlockPopsModForge {

    public BlockPopsModForge(IEventBus modEventBus) {
        // Note: In NeoForge 1.21+, Architectury registers the event bus automatically

        // Register NeoForge-specific content
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModCreativeTabs.register();

        // Register client mod bus events
        BlockPopsModForgeClient.initModBusEvents(modEventBus);

        // Register server lifecycle events
        registerServerEvents();

        // Register commands
        NeoForge.EVENT_BUS.register(this);

        // Run our common setup.
        BlockPopsMod.init();
    }

    /**
     * Register commands when the server starts
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // Register cross-platform commands from common
        ModCommands.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
        BlockPopsMod.logDebug("Registered BlockPops commands");
    }

    private void registerServerEvents() {
        // Load static collections and generate World Players collection when server starts
        LifecycleEvent.SERVER_STARTING.register(server -> {
            // Load static collections from JSON files on the server
            BlockPopsMod.logDebug("Loading static collections on server...");
            CollectionRegistry.loadCollections(server.getResourceManager());

            // Generate dynamic World Players collection
            BlockPopsMod.logDebug("Generating World Players collection...");
            FigureCollection playerCollection = PlayerCollectionGenerator.generate(server);
            CollectionRegistry.registerDynamicCollection(playerCollection);
        });

        // Add new players to the collection when they join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player.getServer() != null) {
                // FALLBACK: If collections weren't loaded during SERVER_STARTING,
                // load them now. This check ensures we only load once.
                if (!CollectionRegistry.isInitialized() || CollectionRegistry.getAllCollections().size() <= 1) {
                    BlockPopsMod.logDebug("Collections not loaded yet, loading now from PLAYER_JOIN...");
                    CollectionRegistry.loadCollections(player.getServer().getResourceManager());
                }

                // 1. Re-generate World Players collection to include the new player
                FigureCollection updatedPlayerCollection = PlayerCollectionGenerator.generate(player.getServer());
                CollectionRegistry.registerDynamicCollection(updatedPlayerCollection);
                BlockPopsMod.LOGGER.debug("Updated World Players collection after player join: {}", player.getName().getString());

                // 2. Sync ALL collections (static + dynamic) to the JOINING player
                // This ensures they see Adventure Time, FNAF, etc. in the menu
                if (player instanceof ServerPlayer serverPlayer) {
                    List<FigureCollection> allCollections = new ArrayList<>(CollectionRegistry.getAllCollections());
                    SyncDynamicCollectionsPacket.sendToPlayer(serverPlayer, allCollections);
                    BlockPopsMod.logDebug("Synced {} collections to joining player {}", allCollections.size(), player.getName().getString());
                }

                // 3. Sync ONLY the updated World Players collection to ALL OTHER players
                // This ensures existing players see the new player's figure without resending static data
                List<FigureCollection> dynamicUpdate = new ArrayList<>();
                dynamicUpdate.add(updatedPlayerCollection);

                for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
                    if (p != player) { // Skip the joining player (they got it in step 2)
                        SyncDynamicCollectionsPacket.sendToPlayer(p, dynamicUpdate);
                    }
                }

                // Sync discovery data and token data to the SPECIFIC client when they join
                if (player instanceof ServerPlayer serverPlayer) {
                    // Use SavedData-based player data manager
                    IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(serverPlayer);
                    var discoveredSet = discovery.getDiscoveredSet();
                    var figureSkins = discovery.getAllFigureSkins();
                    var figureQuickSkins = discovery.getAllFigureQuickSkins();

                    // Sync discovered figures, their skins, and Quick Skins using cross-platform networking
                    SyncDiscoveryDataPacket.sendToPlayer(serverPlayer, discoveredSet, figureSkins, figureQuickSkins);
                    BlockPopsMod.logDebug("Synced {} discovered figures, {} skins, and {} quick skins to {}",
                            discoveredSet.size(),
                            figureSkins.size(),
                            figureQuickSkins.size(),
                            serverPlayer.getName().getString());

                    // Sync token data using cross-platform networking
                    long gameTime = serverPlayer.level().getGameTime();
                    long nextRegularTime = discovery.getNextRegularTokenTime();
                    long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                    // Calculate millis until next special reset
                    long millisUntilReset = com.theplumteam.server.ServerTickHandler.calculateMillisUntilNextReset();

                    SyncTokenDataPacket.sendToPlayer(
                            serverPlayer,
                            discovery.getRegularTokens(),
                            ticksUntilNext,
                            !discovery.hasUsedTodaySpecialToken(),
                            millisUntilReset
                    );
                    BlockPopsMod.logDebug("Synced token data to {}: {} regular tokens, special: {}",
                            serverPlayer.getName().getString(),
                            discovery.getRegularTokens(),
                            !discovery.hasUsedTodaySpecialToken() ? "available" : "used");

                    // Sync server config to client
                    com.theplumteam.network.SyncServerConfigPacket.sendToPlayer(serverPlayer);

                    // Check if favorite color needs to be chosen
                    if (!discovery.hasChosenFavoriteColor() && com.theplumteam.server.config.ServerConfig.getInstance().isShowColorSelectionOnJoin()) {
                        BlockPopsMod.logDebug("Player {} has not chosen a favorite color. Sending packet to open selection screen.",
                                serverPlayer.getName().getString());
                        // Use cross-platform Architectury networking
                        OpenFavoriteColorScreenPacket.sendToPlayer(serverPlayer);
                    }
                }
            }
        });
    }

}
