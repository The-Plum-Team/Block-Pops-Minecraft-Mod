package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.config.ServerConfig;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server packet for admins to update hidden collections.
 * Validates permissions, updates ServerConfig, and broadcasts to all players.
 */
public class UpdateHiddenCollectionsPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateHiddenCollectionsPacket.class);
    public static final ResourceLocation ID = new ResourceLocation(BlockPopsMod.MOD_ID, "update_hidden_collections");

    private final List<String> hiddenCollections;

    public UpdateHiddenCollectionsPacket(List<String> hiddenCollections) {
        this.hiddenCollections = hiddenCollections != null ? hiddenCollections : new ArrayList<>();
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(hiddenCollections.size());
        for (String id : hiddenCollections) {
            buffer.writeUtf(id);
        }
        return buffer;
    }

    public static UpdateHiddenCollectionsPacket decode(FriendlyByteBuf buffer) {
        int count = buffer.readInt();
        List<String> hiddenCollections = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            hiddenCollections.add(buffer.readUtf());
        }
        return new UpdateHiddenCollectionsPacket(hiddenCollections);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UpdateHiddenCollectionsPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check permissions (Level 2 = OP/Admin)
                if (!player.hasPermissions(2)) {
                    LOGGER.warn("Player {} tried to update hidden collections without permission",
                            player.getName().getString());
                    return;
                }

                ServerConfig config = ServerConfig.getInstance();
                config.setHiddenCollections(packet.hiddenCollections);

                BlockPopsMod.logDebug("Player {} updated hidden collections: {}",
                        player.getName().getString(), packet.hiddenCollections);

                // Broadcast updated config to all players
                SyncServerConfigPacket.broadcastToAll(player.getServer());
            }
        });
    }

    public void sendToServer() {
        NetworkManager.sendToServer(ID, encode());
    }
}
