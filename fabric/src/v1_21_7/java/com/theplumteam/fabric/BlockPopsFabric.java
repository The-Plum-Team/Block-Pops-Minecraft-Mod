package com.theplumteam.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.command.ModCommands;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.server.ServerCollectionLoader;
import com.theplumteam.server.config.WorldConfig;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import com.theplumteam.network.SyncDiscoveryDataPacket;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric entry point for BlockPops
 * This class is only loaded on Fabric
 */
public class BlockPopsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        BlockPopsMod.logDebug("BlockPops loading on Fabric platform");

        // Register all content (blocks, items, block entities, creative tabs)
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModCreativeTabs.register();

        // Initialize common code
        BlockPopsMod.init();

        // Register commands using Architectury event
        CommandRegistrationEvent.EVENT.register(ModCommands::register);

        // Register server lifecycle events
        registerServerEvents();

        // Register figure pose cycling event (shift+right-click to cycle poses)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Only handle main hand to prevent double-firing
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            // Only handle if player is sneaking (shift+right-click)
            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }

            // Only process on server side
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }

            BlockEntity blockEntity = world.getBlockEntity(hitResult.getBlockPos());

            // Handle FigureBlockEntity (extracted figures)
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                if (figureBlockEntity.hasFigure()) {
                    figureBlockEntity.cyclePose();
                    player.displayClientMessage(
                        Component.literal("Pose changed to: " + figureBlockEntity.getPoseIndex()), true);
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.PASS;
            }

            // Handle BoxBlockEntity (figures inside boxes) - only allow pose change if figure is extracted
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                if (boxBlockEntity.hasFigure() && boxBlockEntity.isFigureExtracted()) {
                    boxBlockEntity.cyclePose();
                    player.displayClientMessage(
                        Component.literal("Pose changed to: " + boxBlockEntity.getPoseIndex()), true);
                    return InteractionResult.SUCCESS;
                }
            }

            return InteractionResult.PASS;
        });

        BlockPopsMod.logDebug("BlockPops Fabric initialization complete");
    }

    private void registerServerEvents() {
        // Load static collections and generate World Players collection when server starts
        LifecycleEvent.SERVER_STARTING.register(server -> {
            // Load static collections from JSON files on the server
            BlockPopsMod.logDebug("Loading static collections on server...");
            CollectionRegistry.loadCollections(server.getResourceManager());

            // Generate dynamic World Players collection
            BlockPopsMod.logDebug("Generating World Players collection...");
            FigureCollection playerCollection = PlayerCollectionHelper.generate(server);
            CollectionRegistry.registerDynamicCollection(playerCollection);

            // Load enabled remote collections on the server
            WorldConfig worldConfig = WorldConfig.get(server);
            java.util.Set<String> enabledRemote = new java.util.HashSet<>(worldConfig.getEnabledRemoteCollections());
            if (!enabledRemote.isEmpty()) {
                ServerCollectionLoader.loadCollections(enabledRemote);
            }
        });

        // Add new players to the collection when they join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player.getServer() != null) {
                // FALLBACK: If collections weren't loaded during SERVER_STARTING (e.g., with Kilt),
                // load them now. This check ensures we only load once.
                if (!CollectionRegistry.isInitialized() || CollectionRegistry.getAllCollections().size() <= 1) {
                    BlockPopsMod.logDebug("Collections not loaded yet, loading now from PLAYER_JOIN...");
                    CollectionRegistry.loadCollections(player.getServer().getResourceManager());
                }

                // 1. Re-generate World Players collection to include the new player
                FigureCollection updatedPlayerCollection = PlayerCollectionHelper.generate(player.getServer());
                CollectionRegistry.registerDynamicCollection(updatedPlayerCollection);
                BlockPopsMod.LOGGER.debug("Updated World Players collection after player join: {}", player.getName().getString());

                // 2. Sync ALL collections (static + dynamic) to the JOINING player
                // This ensures they see Adventure Time, FNAF, etc. in the menu
                if (player instanceof ServerPlayer serverPlayer) {
                    // Exclude remote collections - client downloads and registers them via RemoteAssetManager
                    com.theplumteam.server.config.WorldConfig wc = com.theplumteam.server.config.WorldConfig.get(player.getServer());
                    java.util.Set<String> remoteIds = new java.util.HashSet<>(wc.getEnabledRemoteCollections());
                    List<FigureCollection> allCollections = CollectionRegistry.getAllCollections().stream()
                            .filter(c -> !remoteIds.contains(c.getId()))
                            .collect(java.util.stream.Collectors.toList());
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
                if (player instanceof ServerPlayer) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;
                    IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(serverPlayer);

                    // Sync discovered figures, their skins, and Quick Skins
                    SyncDiscoveryDataPacket.sendToPlayer(
                            serverPlayer,
                            discovery.getDiscoveredSet(),
                            discovery.getAllFigureSkins(),
                            discovery.getAllFigureQuickSkins()
                    );
                    BlockPopsMod.logDebug("Synced {} discovered figures, {} skins, and {} quick skins to {}",
                            discovery.getDiscoveredSet().size(),
                            discovery.getAllFigureSkins().size(),
                            discovery.getAllFigureQuickSkins().size(),
                            serverPlayer.getName().getString());

                    // Sync token data
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
                        OpenFavoriteColorScreenPacket.sendToPlayer(serverPlayer);
                    }
                }
            }
        });
    }

}
