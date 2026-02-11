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

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UpdateTokenSettingsPacket.ID,
            UpdateTokenSettingsPacket::handleServer
        );

        // Register S2C packets - use different approach for server vs client on Fabric
        // Server: register payload type only, Client: register receiver with handler
        if (Platform.getEnvironment() == Env.SERVER) {
            // On server, just register the payload types so packets can be sent
            NetworkManager.registerS2CPayloadType(SyncTokenDataPacket.ID, null);
            NetworkManager.registerS2CPayloadType(SyncDiscoveryDataPacket.ID, null);
            NetworkManager.registerS2CPayloadType(UnlockFigurePacket.ID, null);
            NetworkManager.registerS2CPayloadType(SyncDynamicCollectionsPacket.ID, null);
            NetworkManager.registerS2CPayloadType(OpenFavoriteColorScreenPacket.ID, null);
            NetworkManager.registerS2CPayloadType(SyncServerConfigPacket.ID, null);
        } else {
            // On client, register receivers to handle incoming packets
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

            NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                SyncServerConfigPacket.ID,
                SyncServerConfigPacket::handleClient
            );
        }

        BlockPopsMod.logDebug("BlockPops networking initialized");
    }

    /**
     * Initialize client-side networking
     * @deprecated S2C packets are now registered in init() with environment check
     */
    @Deprecated
    public static void initClient() {
        BlockPopsMod.logDebug("BlockPops client networking initialization (no-op, packets registered in init())");
    }
}
