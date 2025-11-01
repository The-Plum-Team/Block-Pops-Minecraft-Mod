package com.theplumteam.network;

import com.theplumteam.client.token.ClientTokenManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Server-to-client packet that syncs the player's token status and cooldowns.
 * Sent periodically to keep the client UI updated with accurate token information.
 */
public class SyncTokenDataPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncTokenDataPacket.class);

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

    public static void encode(SyncTokenDataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.regularTokens);
        buffer.writeLong(packet.ticksUntilNextRegular);
        buffer.writeBoolean(packet.hasSpecialToken);
        buffer.writeLong(packet.millisUntilNextSpecialReset);
    }

    public static SyncTokenDataPacket decode(FriendlyByteBuf buffer) {
        int regularTokens = buffer.readInt();
        long ticksUntilNextRegular = buffer.readLong();
        boolean hasSpecialToken = buffer.readBoolean();
        long millisUntilNextSpecialReset = buffer.readLong();
        return new SyncTokenDataPacket(regularTokens, ticksUntilNextRegular,
                hasSpecialToken, millisUntilNextSpecialReset);
    }

    public static void handle(SyncTokenDataPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
            LOGGER.debug("Received token data sync: {} regular tokens, special token: {}",
                    packet.regularTokens, packet.hasSpecialToken ? "available" : "used");
            ClientTokenManager.update(packet);
        });
        context.setPacketHandled(true);
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
