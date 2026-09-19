package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.token.ClientTokenManager;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that syncs the player's token status and cooldowns.
 * Sent periodically to keep the client UI updated with accurate token information.
 */
public class SyncTokenDataPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncTokenDataPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_token_data");

    private final int regularTokens;
    private final long ticksUntilNextRegular;
    private final boolean hasSpecialToken;
    private final long millisUntilNextSpecialReset;

    public SyncTokenDataPacket(int regularTokens, long ticksUntilNextRegular,
                               boolean hasSpecialToken, long millisUntilNextSpecialReset) {
        this.regularTokens = regularTokens;
        this.ticksUntilNextRegular = ticksUntilNextRegular;
        this.hasSpecialToken = hasSpecialToken;
        this.millisUntilNextSpecialReset = millisUntilNextSpecialReset;
    }

    /**
     * Encode this packet to a buffer for network transmission.
     */
    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(regularTokens);
        buffer.writeLong(ticksUntilNextRegular);
        buffer.writeBoolean(hasSpecialToken);
        buffer.writeLong(millisUntilNextSpecialReset);
        return buffer;
    }

    /**
     * Decode a packet from a buffer.
     */
    public static SyncTokenDataPacket decode(FriendlyByteBuf buffer) {
        int regularTokens = buffer.readInt();
        long ticksUntilNextRegular = buffer.readLong();
        boolean hasSpecialToken = buffer.readBoolean();
        long millisUntilNextSpecialReset = buffer.readLong();
        return new SyncTokenDataPacket(regularTokens, ticksUntilNextRegular,
                hasSpecialToken, millisUntilNextSpecialReset);
    }

    /**
     * Handle this packet on the client side.
     */
    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncTokenDataPacket packet = decode(buf);

        context.queue(() -> {
            LOGGER.debug("Received token data sync: {} regular tokens, special token: {}",
                    packet.regularTokens, packet.hasSpecialToken ? "available" : "used");

            // Update client token manager
            ClientTokenManager.update(packet);
        });
    }

    /**
     * Send this packet to a specific player.
     */
    public static void sendToPlayer(ServerPlayer player, int regularTokens, long ticksUntilNextRegular,
                                    boolean hasSpecialToken, long millisUntilNextSpecialReset) {
        SyncTokenDataPacket packet = new SyncTokenDataPacket(regularTokens, ticksUntilNextRegular,
                hasSpecialToken, millisUntilNextSpecialReset);
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
    }

    public int getRegularTokens() {
        return regularTokens;
    }

    public long getTicksUntilNextRegular() {
        return ticksUntilNextRegular;
    }

    public boolean hasSpecialToken() {
        return hasSpecialToken;
    }

    public long getMillisUntilNextSpecialReset() {
        return millisUntilNextSpecialReset;
    }
}
