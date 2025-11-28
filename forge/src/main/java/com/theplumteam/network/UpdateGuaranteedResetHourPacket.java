// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\network\UpdateGuaranteedResetHourPacket.java ==========
package com.theplumteam.network;

import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

public class UpdateGuaranteedResetHourPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateGuaranteedResetHourPacket.class);

    private final int resetHour;

    public UpdateGuaranteedResetHourPacket(int resetHour) {
        this.resetHour = resetHour;
    }

    public static void encode(UpdateGuaranteedResetHourPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.resetHour);
    }

    public static UpdateGuaranteedResetHourPacket decode(FriendlyByteBuf buffer) {
        return new UpdateGuaranteedResetHourPacket(buffer.readInt());
    }

    public static void handle(UpdateGuaranteedResetHourPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                // Check permissions (Level 2 = OP/Cheats)
                if (player.hasPermissions(2)) {
                    // Update server configuration
                    ServerConfig.getInstance().setGuaranteedTokenResetHour(packet.resetHour);
                    LOGGER.info("Player {} updated guaranteed token reset hour to {} UTC",
                            player.getName().getString(), packet.resetHour);

                    // Sync updated token data to client (recalculates time based on new hour)
                    syncTokenData(player);
                } else {
                    LOGGER.warn("Player {} tried to update reset hour without permission", player.getName().getString());
                }
            }
        });
        context.setPacketHandled(true);
    }

    private static void syncTokenData(ServerPlayer player) {
        player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
            long gameTime = player.serverLevel().getGameTime();
            long nextRegularTime = discovery.getNextRegularTokenTime();
            long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
            long millisUntilReset = calculateMillisUntilNextReset();

            SyncTokenDataPacket tokenPacket = new SyncTokenDataPacket(
                    discovery.getRegularTokens(),
                    ticksUntilNext,
                    !discovery.hasUsedTodaySpecialToken(),
                    millisUntilReset
            );
            BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), tokenPacket);
        });
    }

    private static long calculateMillisUntilNextReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        int resetHour = ServerConfig.getInstance().getGuaranteedTokenResetHour();
        ZonedDateTime nextReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= resetHour) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}