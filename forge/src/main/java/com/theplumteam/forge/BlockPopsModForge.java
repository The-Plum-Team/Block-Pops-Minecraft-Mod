package com.theplumteam.forge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.command.ModCommands;
import com.theplumteam.data.IPlayerDiscovery;
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
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.platform.forge.EventBuses;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Mod(BlockPopsMod.MOD_ID)
public final class BlockPopsModForge {

    public BlockPopsModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(BlockPopsMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Register Forge-specific content
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModCreativeTabs.register();

        // Register server lifecycle events
        registerServerEvents();

        // Register commands
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);

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
        BlockPopsMod.LOGGER.info("Registered BlockPops commands");
    }

    private void registerServerEvents() {
        // Load static collections and generate World Players collection when server starts
        LifecycleEvent.SERVER_STARTING.register(server -> {
            // Load static collections from JSON files on the server
            BlockPopsMod.LOGGER.info("Loading static collections on server...");
            CollectionRegistry.loadCollections(server.getResourceManager());

            // Generate dynamic World Players collection
            BlockPopsMod.LOGGER.info("Generating World Players collection...");
            FigureCollection playerCollection = PlayerCollectionGenerator.generate(server);
            CollectionRegistry.registerDynamicCollection(playerCollection);
        });

        // Add new players to the collection when they join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player.getServer() != null) {
                // FALLBACK: If collections weren't loaded during SERVER_STARTING,
                // load them now. This check ensures we only load once.
                if (!CollectionRegistry.isInitialized() || CollectionRegistry.getAllCollections().size() <= 1) {
                    BlockPopsMod.LOGGER.info("Collections not loaded yet, loading now from PLAYER_JOIN...");
                    CollectionRegistry.loadCollections(player.getServer().getResourceManager());
                }

                // 1. Re-generate World Players collection to include the new player
                FigureCollection updatedPlayerCollection = PlayerCollectionGenerator.generate(player.getServer());
                CollectionRegistry.registerDynamicCollection(updatedPlayerCollection);
                BlockPopsMod.LOGGER.debug("Updated World Players collection after player join: {}", player.getName().getString());

                // 2. Sync ALL collections (static + dynamic) to the JOINING player
                // This ensures they see Adventure Time, FNAF, etc. in the menu
                if (player instanceof ServerPlayer) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    java.util.List<FigureCollection> allCollections = new java.util.ArrayList<>(CollectionRegistry.getAllCollections());
                    SyncDynamicCollectionsPacket.sendToPlayer(serverPlayer, allCollections);
                    BlockPopsMod.LOGGER.info("Synced {} collections to joining player {}", allCollections.size(), player.getName().getString());
                }

                // 3. Sync ONLY the updated World Players collection to ALL OTHER players
                // This ensures existing players see the new player's figure without resending static data
                java.util.List<FigureCollection> dynamicUpdate = new java.util.ArrayList<>();
                dynamicUpdate.add(updatedPlayerCollection);

                for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
                    if (p != player) { // Skip the joining player (they got it in step 2)
                        SyncDynamicCollectionsPacket.sendToPlayer(p, dynamicUpdate);
                    }
                }

                // Sync discovery data and token data to the SPECIFIC client when they join
                if (player instanceof ServerPlayer) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;

                    // Use cross-platform PlayerDataManager to get discovery data (reads from NBT, not Capability)
                    IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(serverPlayer);

                    // Sync discovered figures, their skins, and Quick Skins using cross-platform networking
                    SyncDiscoveryDataPacket.sendToPlayer(
                            serverPlayer,
                            discovery.getDiscoveredSet(),
                            discovery.getAllFigureSkins(),
                            discovery.getAllFigureQuickSkins()
                    );
                    BlockPopsMod.LOGGER.info("Synced {} discovered figures, {} skins, and {} quick skins to {}",
                            discovery.getDiscoveredSet().size(),
                            discovery.getAllFigureSkins().size(),
                            discovery.getAllFigureQuickSkins().size(),
                            serverPlayer.getName().getString());

                    // Sync token data using cross-platform networking
                    long gameTime = serverPlayer.serverLevel().getGameTime();
                    long nextRegularTime = discovery.getNextRegularTokenTime();
                    long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                    // Calculate millis until next special reset
                    long millisUntilReset = calculateMillisUntilNextReset();

                    SyncTokenDataPacket.sendToPlayer(
                            serverPlayer,
                            discovery.getRegularTokens(),
                            ticksUntilNext,
                            !discovery.hasUsedTodaySpecialToken(),
                            millisUntilReset
                    );
                    BlockPopsMod.LOGGER.info("Synced token data to {}: {} regular tokens, special: {}",
                            serverPlayer.getName().getString(),
                            discovery.getRegularTokens(),
                            !discovery.hasUsedTodaySpecialToken() ? "available" : "used");

                    // Check if favorite color needs to be chosen
                    if (!discovery.hasChosenFavoriteColor()) {
                        BlockPopsMod.LOGGER.info("Player {} has not chosen a favorite color. Sending packet to open selection screen.",
                                serverPlayer.getName().getString());
                        // Use cross-platform Architectury networking
                        OpenFavoriteColorScreenPacket.sendToPlayer(serverPlayer);
                    }
                }
            }
        });
    }

    /**
     * Calculate milliseconds until the next daily reset at 18:00 UTC (6 PM).
     */
    private static long calculateMillisUntilNextReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        ZonedDateTime nextReset = now.withHour(18).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= 18) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}