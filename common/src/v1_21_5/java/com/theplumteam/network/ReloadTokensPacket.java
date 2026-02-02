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
 * Client-to-server packet that reloads a player's tokens.
 * Can reload regular tokens (sets to 3), guaranteed token, or both.
 */
public class ReloadTokensPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadTokensPacket.class);
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "reload_tokens");

    private final boolean reloadRegular;
    private final boolean reloadGuaranteed;

    public ReloadTokensPacket(boolean reloadRegular, boolean reloadGuaranteed) {
        this.reloadRegular = reloadRegular;
        this.reloadGuaranteed = reloadGuaranteed;
    }

    public RegistryFriendlyByteBuf encode() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buffer.writeBoolean(reloadRegular);
        buffer.writeBoolean(reloadGuaranteed);
        return buffer;
    }

    public static ReloadTokensPacket decode(FriendlyByteBuf buffer) {
        boolean reloadRegular = buffer.readBoolean();
        boolean reloadGuaranteed = buffer.readBoolean();
        return new ReloadTokensPacket(reloadRegular, reloadGuaranteed);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        ReloadTokensPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check if player has permission (level 2, same as /blockpops getbox)
                if (!player.hasPermissions(2)) {
                    LOGGER.warn("Player {} tried to reload tokens without permission", player.getName().getString());
                    return;
                }

                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);

                if (packet.reloadRegular) {
                    discovery.setRegularTokens(ServerConfig.getInstance().getMaxRegularTokens());
                    discovery.setNextRegularTokenTime(0);
                    BlockPopsMod.logDebug("Reloaded regular tokens for player {}", player.getName().getString());
                }

                if (packet.reloadGuaranteed) {
                    discovery.setUsedTodaySpecialToken(false);
                    BlockPopsMod.logDebug("Reloaded guaranteed token for player {}", player.getName().getString());
                }

                PlayerDataManager.markDirty(player, discovery);

                // Sync token data back to client using cross-platform networking
                long gameTime = player.serverLevel().getGameTime();
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
        });
    }

    public void sendToServer() {
        NetworkManager.sendToServer(ID, encode());
    }
}
