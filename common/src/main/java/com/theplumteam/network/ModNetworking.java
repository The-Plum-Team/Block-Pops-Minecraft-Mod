package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.resources.ResourceLocation;

/**
 * Central networking registry for BlockPops
 * Uses Architectury's NetworkManager for cross-platform compatibility
 */
public class ModNetworking {

    // Client to Server packets (C2S)
    public static final ResourceLocation FIGURE_POSITION =
        ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "figure_position");

    public static final ResourceLocation CLAW_MACHINE_COLLECTION =
        ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "claw_machine_collection");

    public static final ResourceLocation SET_FAVORITE_COLOR =
        ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "set_favorite_color");

    /**
     * Initializes networking (registers all packet receivers)
     * Called from BlockPopsMod.init() on both client and server
     */
    public static void init() {
        BlockPopsMod.LOGGER.info("Initializing BlockPops networking...");

        // Register server-side packet receivers (C2S)
        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            FIGURE_POSITION,
            FigurePositionPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            CLAW_MACHINE_COLLECTION,
            ClawMachineCollectionPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            SET_FAVORITE_COLOR,
            SetFavoriteColorPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            DropBoxPacket.ID,
            DropBoxPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            ReloadTokensPacket.ID,
            ReloadTokensPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UnlockCollectionPacket.ID,
            UnlockCollectionPacket::handleServer
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UpdateGuaranteedResetHourPacket.ID,
            UpdateGuaranteedResetHourPacket::handleServer
        );

        // Register S2C packets on both sides (required for Architectury 13.x / NeoForge 1.21.1)
        // Handler only does work on client side
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncTokenDataPacket.ID,
            (buf, context) -> {
                if (Platform.getEnvironment() == Env.CLIENT) {
                    SyncTokenDataPacket.handleClient(buf, context);
                }
            }
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncDiscoveryDataPacket.ID,
            (buf, context) -> {
                if (Platform.getEnvironment() == Env.CLIENT) {
                    SyncDiscoveryDataPacket.handleClient(buf, context);
                }
            }
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            UnlockFigurePacket.ID,
            (buf, context) -> {
                if (Platform.getEnvironment() == Env.CLIENT) {
                    UnlockFigurePacket.handleClient(buf, context);
                }
            }
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncDynamicCollectionsPacket.ID,
            (buf, context) -> {
                if (Platform.getEnvironment() == Env.CLIENT) {
                    SyncDynamicCollectionsPacket.handleClient(buf, context);
                }
            }
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            OpenFavoriteColorScreenPacket.ID,
            (buf, context) -> {
                if (Platform.getEnvironment() == Env.CLIENT) {
                    OpenFavoriteColorScreenPacket.handleClient(buf, context);
                }
            }
        );

        BlockPopsMod.LOGGER.info("BlockPops networking initialized");
    }

    /**
     * Initialize client-side networking
     * @deprecated S2C packets are now registered in init() with environment check
     */
    @Deprecated
    public static void initClient() {
        BlockPopsMod.LOGGER.info("BlockPops client networking initialization (no-op, packets registered in init())");
    }
}
