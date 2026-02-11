package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.server.ServerTickHandler;
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
 * Client-to-server packet that updates the guaranteed token reset hour.
 * Requires operator permissions.
 */
public class UpdateGuaranteedResetHourPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateGuaranteedResetHourPacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "update_guaranteed_reset_hour");

    private final int resetHour;

    public UpdateGuaranteedResetHourPacket(int resetHour) {
        this.resetHour = resetHour;
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeInt(resetHour);
        return buffer;
    }

    public static UpdateGuaranteedResetHourPacket decode(FriendlyByteBuf buffer) {
        return new UpdateGuaranteedResetHourPacket(buffer.readInt());
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UpdateGuaranteedResetHourPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check permissions (Level 2 = OP/Cheats)
                if (player.hasPermissions(2)) {
                    // Update server configuration
                    ServerConfig.getInstance().setGuaranteedTokenResetHour(packet.resetHour);
                    BlockPopsMod.logDebug("Player {} updated guaranteed token reset hour to {} UTC",
                            player.getName().getString(), packet.resetHour);

                    // Sync updated token data to client (recalculates time based on new hour)
                    syncTokenData(player);
                } else {
                    LOGGER.warn("Player {} tried to update reset hour without permission", player.getName().getString());
                }
            }
        });
    }

    private static void syncTokenData(ServerPlayer player) {
        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);

        long gameTime = player.level().getGameTime();
        long nextRegularTime = discovery.getNextRegularTokenTime();
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
        long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

        SyncTokenDataPacket.sendToPlayer(
                player,
                discovery.getRegularTokens(),
                ticksUntilNext,
                !discovery.hasUsedTodaySpecialToken(),
                millisUntilReset
        );
    }

    public void sendToServer() {
        NetworkManager.sendToServer(ID, encode());
    }
}
