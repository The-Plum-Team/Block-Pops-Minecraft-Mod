package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Central networking registry for BlockPops
 * Uses Architectury's NetworkManager for cross-platform compatibility
 */
public class ModNetworking {

    // Client to Server packets (C2S)
    public static final ResourceLocation FIGURE_POSITION =
        ResourceLocations.of(BlockPopsMod.MOD_ID, "figure_position");

    public static final ResourceLocation CLAW_MACHINE_COLLECTION =
        ResourceLocations.of(BlockPopsMod.MOD_ID, "claw_machine_collection");

    public static final ResourceLocation SET_FAVORITE_COLOR =
        ResourceLocations.of(BlockPopsMod.MOD_ID, "set_favorite_color");

    /**
     * Initializes networking (registers server-side receivers)
     * Called from BlockPopsMod.init() on both client and server
     */
    public static void init() {
        BlockPopsMod.logDebug("Initializing BlockPops networking...");

        // Register server-side packet receivers (C2S)
        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            FIGURE_POSITION,
            FigurePositionPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            CLAW_MACHINE_COLLECTION,
            ClawMachineCollectionPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            SET_FAVORITE_COLOR,
            SetFavoriteColorPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            DropBoxPacket.ID,
            DropBoxPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            ReloadTokensPacket.ID,
            ReloadTokensPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            UnlockCollectionPacket.ID,
            UnlockCollectionPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            AdminProgressionPacket.ID,
            AdminProgressionPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            UpdateGuaranteedResetHourPacket.ID,
            UpdateGuaranteedResetHourPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            RequestServerSettingsPacket.ID,
            RequestServerSettingsPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            SetCollectionHiddenPacket.ID,
            SetCollectionHiddenPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            SetWorldPlayerEnabledPacket.ID,
            SetWorldPlayerEnabledPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            CustomCollectionRequestPacket.ID,
            CustomCollectionRequestPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            RequestSupportCheckoutPacket.ID,
            RequestSupportCheckoutPacket::handleServer
        );

        // World collections: uploads and edits from operators, file requests from every player
        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldUploadBeginPacket.ID,
            WorldUploadBeginPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldUploadChunkPacket.ID,
            WorldUploadChunkPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldUploadCommitPacket.ID,
            WorldUploadCommitPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldCollectionEditPacket.ID,
            WorldCollectionEditPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldAssetRequestPacket.ID,
            WorldAssetRequestPacket::handleServer
        );

        PacketNetworking.registerReceiver(
            NetworkManager.c2s(),
            WorldAssetAckPacket.ID,
            WorldAssetAckPacket::handleServer
        );

        //? if >=1.21 {
        /*PacketNetworking.registerServerS2CPayloads(
                SyncTokenDataPacket.ID, SyncDiscoveryDataPacket.ID, UnlockFigurePacket.ID,
                SyncDynamicCollectionsPacket.ID, OpenFavoriteColorScreenPacket.ID,
                SyncServerSettingsPacket.ID, SyncHiddenCollectionsPacket.ID,
                AdminProgressionSyncPacket.ID, SyncSpecialAccessPacket.ID,
                SyncCustomCollectionsPacket.ID, CustomCollectionStatusPacket.ID, DrawResultPacket.ID,
                SyncWorldCollectionsPacket.ID, WorldAssetChunkPacket.ID, WorldCollectionStatusPacket.ID,
                OpenSupportCheckoutPacket.ID);
        *///? }

        BlockPopsMod.logDebug("BlockPops networking initialized");
    }

    /**
     * Initialize client-side networking (registers client-side receivers for S2C packets)
     * Must be called from client initialization only
     */
    public static void initClient() {
        BlockPopsMod.logDebug("Initializing BlockPops client networking...");

        // Register client-side packet receivers (S2C)
        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncTokenDataPacket.ID,
            SyncTokenDataPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncDiscoveryDataPacket.ID,
            SyncDiscoveryDataPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            UnlockFigurePacket.ID,
            UnlockFigurePacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncDynamicCollectionsPacket.ID,
            SyncDynamicCollectionsPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            OpenFavoriteColorScreenPacket.ID,
            OpenFavoriteColorScreenPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            OpenSupportCheckoutPacket.ID,
            OpenSupportCheckoutPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncServerSettingsPacket.ID,
            SyncServerSettingsPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncHiddenCollectionsPacket.ID,
            SyncHiddenCollectionsPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            AdminProgressionSyncPacket.ID,
            AdminProgressionSyncPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncSpecialAccessPacket.ID,
            SyncSpecialAccessPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncCustomCollectionsPacket.ID,
            SyncCustomCollectionsPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            CustomCollectionStatusPacket.ID,
            CustomCollectionStatusPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            DrawResultPacket.ID,
            DrawResultPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            SyncWorldCollectionsPacket.ID,
            SyncWorldCollectionsPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            WorldAssetChunkPacket.ID,
            WorldAssetChunkPacket::handleClient
        );

        PacketNetworking.registerReceiver(
            NetworkManager.s2c(),
            WorldCollectionStatusPacket.ID,
            WorldCollectionStatusPacket::handleClient
        );

        // Leaving a server forgets its Custom and World collections and their downloaded assets
        dev.architectury.event.events.client.ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            com.theplumteam.client.remote.ClientCustomCollections.forget();
            com.theplumteam.client.worldcontent.ClientWorldCollections.forget();
            com.theplumteam.client.worldcontent.ClientWorldUploads.reset();
        });
        // A proxy can move the client to another server without a disconnect; that server sends its own
        dev.architectury.event.events.client.ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            com.theplumteam.client.worldcontent.ClientWorldCollections.forget();
            com.theplumteam.client.worldcontent.ClientWorldUploads.reset();
        });
        // Uploads send their chunks and the file picker takes its dialog's answer on the client tick
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(minecraft -> {
            com.theplumteam.client.worldcontent.FilePicker.tick();
            com.theplumteam.client.worldcontent.ClientWorldUploads.tick();
        });
        // The creator's client command, in a development environment only
        com.theplumteam.client.CreatorCommand.register();

        BlockPopsMod.logDebug("BlockPops client networking initialized");
    }
}
