package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.server.ServerTickHandler;
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
 * Client-to-server packet for admins to update token settings.
 * Validates permissions, clamps values, updates ServerConfig, and broadcasts to all players.
 */
public class UpdateTokenSettingsPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateTokenSettingsPacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "update_token_settings");

    private final int regularTokenCooldownHours;
    private final int maxRegularTokens;
    private final int guaranteedTokenResetHour;

    public UpdateTokenSettingsPacket(int regularTokenCooldownHours, int maxRegularTokens,
                                     int guaranteedTokenResetHour) {
        this.regularTokenCooldownHours = regularTokenCooldownHours;
        this.maxRegularTokens = maxRegularTokens;
        this.guaranteedTokenResetHour = guaranteedTokenResetHour;
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeInt(regularTokenCooldownHours);
        buffer.writeInt(maxRegularTokens);
        buffer.writeInt(guaranteedTokenResetHour);
        return buffer;
    }

    public static UpdateTokenSettingsPacket decode(FriendlyByteBuf buffer) {
        int regularTokenCooldownHours = buffer.readInt();
        int maxRegularTokens = buffer.readInt();
        int guaranteedTokenResetHour = buffer.readInt();
        return new UpdateTokenSettingsPacket(regularTokenCooldownHours, maxRegularTokens,
                guaranteedTokenResetHour);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UpdateTokenSettingsPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check permissions (Level 2 = OP/Admin)
                if (!player.hasPermissions(2)) {
                    LOGGER.warn("Player {} tried to update token settings without permission",
                            player.getName().getString());
                    return;
                }

                ServerConfig config = ServerConfig.getInstance();

                // Update all settings with clamping, then save once
                config.regularTokenCooldownHours = Math.max(1, Math.min(168, packet.regularTokenCooldownHours));
                config.maxRegularTokens = Math.max(1, Math.min(99, packet.maxRegularTokens));
                config.guaranteedTokenResetHour = Math.max(0, Math.min(23, packet.guaranteedTokenResetHour));
                config.save();

                BlockPopsMod.logDebug("Player {} updated token settings: cooldown={}h, maxTokens={}, resetHour={}",
                        player.getName().getString(),
                        config.getRegularTokenCooldownHours(),
                        config.getMaxRegularTokens(),
                        config.getGuaranteedTokenResetHour());

                // Broadcast updated config to all players
                SyncServerConfigPacket.broadcastToAll(player.getServer());

                // Re-sync token data for all players (cooldown calculations may have changed)
                for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
                    IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(p);
                    long gameTime = p.serverLevel().getGameTime();
                    long nextRegularTime = discovery.getNextRegularTokenTime();
                    long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
                    long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

                    SyncTokenDataPacket.sendToPlayer(
                            p,
                            discovery.getRegularTokens(),
                            ticksUntilNext,
                            !discovery.hasUsedTodaySpecialToken(),
                            millisUntilReset
                    );
                }
            }
        });
    }

    public void sendToServer() {
        NetworkManager.sendToServer(ID, encode());
    }
}
