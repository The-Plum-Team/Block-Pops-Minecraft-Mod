package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Central networking registry for BlockPops
 * Uses Architectury's NetworkManager for cross-platform compatibility
 */
public class ModNetworking {

    // Client to Server packets (C2S)
    public static final ResourceLocation FIGURE_POSITION =
        new ResourceLocation(BlockPopsMod.MOD_ID, "figure_position");

    public static final ResourceLocation CLAW_MACHINE_COLLECTION =
        new ResourceLocation(BlockPopsMod.MOD_ID, "claw_machine_collection");

    public static final ResourceLocation SET_FAVORITE_COLOR =
        new ResourceLocation(BlockPopsMod.MOD_ID, "set_favorite_color");

    /**
     * Initializes networking (registers server-side receivers)
     * Called from BlockPopsMod.init() on both client and server
     */
    public static void init() {
        BlockPopsMod.logDebug("Initializing BlockPops networking...");

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

        BlockPopsMod.logDebug("BlockPops networking initialized");
    }

    /**
     * Initialize client-side networking (registers client-side receivers for S2C packets)
     * Must be called from client initialization only
     */
    public static void initClient() {
        BlockPopsMod.logDebug("Initializing BlockPops client networking...");

        // Register client-side packet receivers (S2C)
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncTokenDataPacket.ID,
            SyncTokenDataPacket::handleClient
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncDiscoveryDataPacket.ID,
            SyncDiscoveryDataPacket::handleClient
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            UnlockFigurePacket.ID,
            UnlockFigurePacket::handleClient
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncDynamicCollectionsPacket.ID,
            SyncDynamicCollectionsPacket::handleClient
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            OpenFavoriteColorScreenPacket.ID,
            OpenFavoriteColorScreenPacket::handleClient
        );

        BlockPopsMod.logDebug("BlockPops client networking initialized");
    }
}
