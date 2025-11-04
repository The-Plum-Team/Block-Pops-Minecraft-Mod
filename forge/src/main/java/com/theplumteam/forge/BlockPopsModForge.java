package com.theplumteam.forge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.command.ChangeFavoriteColorCommand;
import com.theplumteam.command.GetBoxCommand;
import com.theplumteam.command.GetFavoriteColorCommand;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.FigurePositionPacket;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import com.theplumteam.network.SetFavoriteColorPacket;
import com.theplumteam.network.SyncDiscoveryDataPacket;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.network.UnlockFigurePacket;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.platform.forge.EventBuses;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@Mod(BlockPopsMod.MOD_ID)
public final class BlockPopsModForge {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel NETWORK_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlockPopsMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public BlockPopsModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(BlockPopsMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Register Forge-specific content
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModCreativeTabs.register();

        // Register network packets
        registerNetworkPackets();

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
        GetBoxCommand.register(event.getDispatcher());
        ChangeFavoriteColorCommand.register(event.getDispatcher());
        GetFavoriteColorCommand.register(event.getDispatcher());
        BlockPopsMod.LOGGER.info("Registered /blockpops commands: getbox, changefavoritecolor, getfavoritecolor");
    }

    private void registerServerEvents() {
        // Generate World Players collection when server starts
        LifecycleEvent.SERVER_STARTING.register(server -> {
            BlockPopsMod.LOGGER.info("Generating World Players collection...");
            FigureCollection playerCollection = PlayerCollectionGenerator.generate(server);
            CollectionRegistry.registerDynamicCollection(playerCollection);
        });

        // Add new players to the collection when they join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Re-generate and update the collection to include the new player
            // This is safe because it happens on the server thread
            if (player.getServer() != null) {
                FigureCollection updatedCollection = PlayerCollectionGenerator.generate(player.getServer());
                CollectionRegistry.registerDynamicCollection(updatedCollection);
                BlockPopsMod.LOGGER.debug("Updated World Players collection after player join: {}", player.getName().getString());

                // Sync dynamic collections, discovery data, and token data to the client when they join
                if (player instanceof ServerPlayer) {
                    ServerPlayer serverPlayer = (ServerPlayer) player;

                    // Sync dynamic collections (like World Players)
                    java.util.List<FigureCollection> dynamicCollections = new java.util.ArrayList<>();
                    CollectionRegistry.getAllCollections().forEach(collection -> {
                        if ("world_players".equals(collection.getId())) {
                            dynamicCollections.add(collection);
                        }
                    });
                    if (!dynamicCollections.isEmpty()) {
                        SyncDynamicCollectionsPacket collectionsPacket = new SyncDynamicCollectionsPacket(dynamicCollections);
                        NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), collectionsPacket);
                        BlockPopsMod.LOGGER.info("Synced {} dynamic collections to {}",
                                dynamicCollections.size(), serverPlayer.getName().getString());
                    }

                    serverPlayer.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                        // Sync discovered figures and their skins
                        SyncDiscoveryDataPacket discoveryPacket = new SyncDiscoveryDataPacket(
                                discovery.getDiscoveredSet(),
                                discovery.getAllFigureSkins()
                        );
                        NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), discoveryPacket);
                        BlockPopsMod.LOGGER.info("Synced {} discovered figures and {} skins to {}",
                                discovery.getDiscoveredSet().size(),
                                discovery.getAllFigureSkins().size(),
                                serverPlayer.getName().getString());

                        // Sync token data
                        long gameTime = serverPlayer.serverLevel().getGameTime();
                        long nextRegularTime = discovery.getNextRegularTokenTime();
                        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                        // Calculate millis until next special reset
                        long millisUntilReset = calculateMillisUntilNextReset();

                        SyncTokenDataPacket tokenPacket = new SyncTokenDataPacket(
                                discovery.getRegularTokens(),
                                ticksUntilNext,
                                !discovery.hasUsedTodaySpecialToken(),
                                millisUntilReset
                        );
                        NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), tokenPacket);
                        BlockPopsMod.LOGGER.info("Synced token data to {}: {} regular tokens, special: {}",
                                serverPlayer.getName().getString(),
                                discovery.getRegularTokens(),
                                !discovery.hasUsedTodaySpecialToken() ? "available" : "used");

                        // Check if favorite color needs to be chosen
                        if (!discovery.hasChosenFavoriteColor()) {
                            BlockPopsMod.LOGGER.info("Player {} has not chosen a favorite color. Sending packet to open selection screen.",
                                    serverPlayer.getName().getString());
                            NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                                    new OpenFavoriteColorScreenPacket());
                        }
                    });
                }
            }
        });
    }

    private void registerNetworkPackets() {
        int packetId = 0;
        NETWORK_CHANNEL.registerMessage(packetId++,
                FigurePositionPacket.class,
                FigurePositionPacket::encode,
                FigurePositionPacket::decode,
                FigurePositionPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                ClawMachineCollectionPacket.class,
                ClawMachineCollectionPacket::encode,
                ClawMachineCollectionPacket::decode,
                ClawMachineCollectionPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                DropBoxPacket.class,
                DropBoxPacket::encode,
                DropBoxPacket::decode,
                DropBoxPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                SyncDiscoveryDataPacket.class,
                SyncDiscoveryDataPacket::encode,
                SyncDiscoveryDataPacket::decode,
                SyncDiscoveryDataPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                UnlockFigurePacket.class,
                UnlockFigurePacket::encode,
                UnlockFigurePacket::decode,
                UnlockFigurePacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                SyncTokenDataPacket.class,
                SyncTokenDataPacket::encode,
                SyncTokenDataPacket::decode,
                SyncTokenDataPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                OpenFavoriteColorScreenPacket.class,
                OpenFavoriteColorScreenPacket::encode,
                OpenFavoriteColorScreenPacket::decode,
                OpenFavoriteColorScreenPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                SetFavoriteColorPacket.class,
                SetFavoriteColorPacket::encode,
                SetFavoriteColorPacket::decode,
                SetFavoriteColorPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                SyncDynamicCollectionsPacket.class,
                SyncDynamicCollectionsPacket::encode,
                SyncDynamicCollectionsPacket::decode,
                SyncDynamicCollectionsPacket::handle
        );
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
