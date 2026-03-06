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

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet that syncs server configuration values.
 * Sent on player join and whenever an admin changes token settings.
 */
public class SyncServerConfigPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncServerConfigPacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "sync_server_config");

    private final int regularTokenCooldownHours;
    private final int maxRegularTokens;
    private final int guaranteedTokenResetHour;
    private final List<String> hiddenCollections;

    public SyncServerConfigPacket(int regularTokenCooldownHours, int maxRegularTokens,
                                  int guaranteedTokenResetHour, List<String> hiddenCollections) {
        this.regularTokenCooldownHours = regularTokenCooldownHours;
        this.maxRegularTokens = maxRegularTokens;
        this.guaranteedTokenResetHour = guaranteedTokenResetHour;
        this.hiddenCollections = hiddenCollections != null ? hiddenCollections : new ArrayList<>();
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeInt(regularTokenCooldownHours);
        buffer.writeInt(maxRegularTokens);
        buffer.writeInt(guaranteedTokenResetHour);
        buffer.writeInt(hiddenCollections.size());
        for (String id : hiddenCollections) {
            buffer.writeUtf(id);
        }
        return buffer;
    }

    public static SyncServerConfigPacket decode(FriendlyByteBuf buffer) {
        int regularTokenCooldownHours = buffer.readInt();
        int maxRegularTokens = buffer.readInt();
        int guaranteedTokenResetHour = buffer.readInt();
        int hiddenCount = buffer.readInt();
        List<String> hiddenCollections = new ArrayList<>(hiddenCount);
        for (int i = 0; i < hiddenCount; i++) {
            hiddenCollections.add(buffer.readUtf());
        }
        return new SyncServerConfigPacket(regularTokenCooldownHours, maxRegularTokens,
                guaranteedTokenResetHour, hiddenCollections);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncServerConfigPacket packet = decode(buf);

        context.queue(() -> {
            ClientServerConfig.update(
                    packet.regularTokenCooldownHours,
                    packet.maxRegularTokens,
                    packet.guaranteedTokenResetHour
            );
            ClientServerConfig.updateHiddenCollections(packet.hiddenCollections);
            LOGGER.debug("Received server config sync: cooldown={}h, maxTokens={}, resetHour={}, hidden={}",
                    packet.regularTokenCooldownHours, packet.maxRegularTokens,
                    packet.guaranteedTokenResetHour, packet.hiddenCollections);
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
                config.getGuaranteedTokenResetHour(),
                config.getHiddenCollections()
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
