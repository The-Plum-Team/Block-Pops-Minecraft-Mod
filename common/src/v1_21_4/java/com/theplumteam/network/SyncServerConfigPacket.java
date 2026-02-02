package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.config.ClientServerConfig;
import com.theplumteam.server.config.ServerConfig;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that syncs server configuration values.
 * Sent on player join and whenever an admin changes token settings.
 */
public class SyncServerConfigPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncServerConfigPacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "sync_server_config");

    private final int regularTokenCooldownHours;
    private final int maxRegularTokens;
    private final int guaranteedTokenCooldownHours;
    private final int guaranteedTokenResetHour;

    public SyncServerConfigPacket(int regularTokenCooldownHours, int maxRegularTokens,
                                  int guaranteedTokenCooldownHours, int guaranteedTokenResetHour) {
        this.regularTokenCooldownHours = regularTokenCooldownHours;
        this.maxRegularTokens = maxRegularTokens;
        this.guaranteedTokenCooldownHours = guaranteedTokenCooldownHours;
        this.guaranteedTokenResetHour = guaranteedTokenResetHour;
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeInt(regularTokenCooldownHours);
        buffer.writeInt(maxRegularTokens);
        buffer.writeInt(guaranteedTokenCooldownHours);
        buffer.writeInt(guaranteedTokenResetHour);
        return buffer;
    }

    public static SyncServerConfigPacket decode(FriendlyByteBuf buffer) {
        int regularTokenCooldownHours = buffer.readInt();
        int maxRegularTokens = buffer.readInt();
        int guaranteedTokenCooldownHours = buffer.readInt();
        int guaranteedTokenResetHour = buffer.readInt();
        return new SyncServerConfigPacket(regularTokenCooldownHours, maxRegularTokens,
                guaranteedTokenCooldownHours, guaranteedTokenResetHour);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncServerConfigPacket packet = decode(buf);

        context.queue(() -> {
            ClientServerConfig.update(
                    packet.regularTokenCooldownHours,
                    packet.maxRegularTokens,
                    packet.guaranteedTokenCooldownHours,
                    packet.guaranteedTokenResetHour
            );
            LOGGER.debug("Received server config sync: cooldown={}h, maxTokens={}, guaranteedCooldown={}h, resetHour={}",
                    packet.regularTokenCooldownHours, packet.maxRegularTokens,
                    packet.guaranteedTokenCooldownHours, packet.guaranteedTokenResetHour);
        });
    }

    /**
     * Send current server config to a specific player.
     */
    public static void sendToPlayer(ServerPlayer player) {
        ServerConfig config = ServerConfig.getInstance();
        SyncServerConfigPacket packet = new SyncServerConfigPacket(
                config.getRegularTokenCooldownHours(),
                config.getMaxRegularTokens(),
                config.getGuaranteedTokenCooldownHours(),
                config.getGuaranteedTokenResetHour()
        );
        NetworkManager.sendToPlayer(player, ID, packet.encode());
    }

    /**
     * Broadcast current server config to all online players.
     */
    public static void broadcastToAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(player);
        }
    }
}
