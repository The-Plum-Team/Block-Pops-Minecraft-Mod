package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.server.WorldPlayerRoster;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Client-to-server packet that enables or disables a player in the World Players collection.
 * Requires operator permissions.
 */
public class SetWorldPlayerEnabledPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetWorldPlayerEnabledPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "set_world_player_enabled");

    private final UUID playerUUID;
    private final boolean enabled;

    public SetWorldPlayerEnabledPacket(UUID playerUUID, boolean enabled) {
        this.playerUUID = playerUUID;
        this.enabled = enabled;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(playerUUID);
        buffer.writeBoolean(enabled);
        return buffer;
    }

    public static SetWorldPlayerEnabledPacket decode(FriendlyByteBuf buffer) {
        return new SetWorldPlayerEnabledPacket(buffer.readUUID(), buffer.readBoolean());
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SetWorldPlayerEnabledPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check permissions (Level 2 = OP/Cheats)
                if (!ServerLevels.hasCommandLevel(player, 2)) {
                    LOGGER.warn("Player {} tried to change the World Players roster without permission", player.getName().getString());
                    return;
                }

                // Only players of this world's collection can be toggled, and a repeated
                // request (the registered figure already has that state) changes nothing
                MinecraftServer server = ServerLevels.serverOf(player);
                if (server == null || CollectionRegistry.getFigure(
                        PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID, packet.playerUUID.toString())
                        .filter(figure -> figure.isEnabled() != packet.enabled).isEmpty()) {
                    return;
                }

                if (WorldPlayerRoster.get(server).setEnabled(packet.playerUUID, packet.enabled)) {
                    BlockPopsMod.logDebug("Player {} set World Players figure {} enabled: {}",
                            player.getName().getString(), packet.playerUUID, packet.enabled);

                    // Regenerate the World Players collection so every client drops or regains the figure
                    PlayerCollectionHelper.regenerateAndSyncPlayerCollection(server);
                } else {
                    player.sendSystemMessage(Component.literal("\u00A7cCould not save the World Players roster. Check the server log."));
                }
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
