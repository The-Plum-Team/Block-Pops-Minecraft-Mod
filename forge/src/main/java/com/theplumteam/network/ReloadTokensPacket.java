package com.theplumteam.network;

import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Client-to-server packet that reloads a player's tokens.
 * Can reload regular tokens (sets to 3), guaranteed token, or both.
 */
public class ReloadTokensPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadTokensPacket.class);

    private final boolean reloadRegular;
    private final boolean reloadGuaranteed;

    public ReloadTokensPacket(boolean reloadRegular, boolean reloadGuaranteed) {
        this.reloadRegular = reloadRegular;
        this.reloadGuaranteed = reloadGuaranteed;
    }

    public static void encode(ReloadTokensPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.reloadRegular);
        buffer.writeBoolean(packet.reloadGuaranteed);
    }

    public static ReloadTokensPacket decode(FriendlyByteBuf buffer) {
        boolean reloadRegular = buffer.readBoolean();
        boolean reloadGuaranteed = buffer.readBoolean();
        return new ReloadTokensPacket(reloadRegular, reloadGuaranteed);
    }

    public static void handle(ReloadTokensPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                // Check if player has permission (level 2, same as /blockpops getbox)
                if (!player.hasPermissions(2)) {
                    LOGGER.warn("Player {} tried to reload tokens without permission", player.getName().getString());
                    return;
                }

                player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    if (packet.reloadRegular) {
                        discovery.setRegularTokens(3);
                        discovery.setNextRegularTokenTime(0);
                        LOGGER.info("Reloaded regular tokens for player {}", player.getName().getString());
                    }

                    if (packet.reloadGuaranteed) {
                        discovery.setUsedTodaySpecialToken(false);
                        LOGGER.info("Reloaded guaranteed token for player {}", player.getName().getString());
                    }

                    // Sync token data back to client
                    long gameTime = player.serverLevel().getGameTime();
                    long nextRegularTime = discovery.getNextRegularTokenTime();
                    long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                    // Calculate millis until next special reset
                    long millisUntilReset = calculateMillisUntilNextReset();

                    SyncTokenDataPacket tokenPacket = new SyncTokenDataPacket(
                            discovery.getRegularTokens(),
                            ticksUntilNext,
                            !discovery.hasUsedTodaySpecialToken(),
                            millisUntilReset
                    );
                    BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), tokenPacket);
                });
            } else {
                LOGGER.warn("Player is null in ReloadTokensPacket handler!");
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * Calculate milliseconds until the next daily reset at the configured hour.
     * This is duplicated from BlockPopsModForge for encapsulation.
     */
    private static long calculateMillisUntilNextReset() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
        int resetHour = com.theplumteam.server.config.ServerConfig.getInstance().getGuaranteedTokenResetHour();
        java.time.ZonedDateTime nextReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= resetHour) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}
