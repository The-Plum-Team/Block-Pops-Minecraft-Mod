package com.theplumteam.network;

import com.theplumteam.client.discovery.ClientDiscoveryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server-to-client packet that syncs the player's complete set of discovered figures.
 * Sent when a player logs in to ensure their collection is up-to-date.
 */
public class SyncDiscoveryDataPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncDiscoveryDataPacket.class);

    private final Set<String> discoveredFigures;

    public SyncDiscoveryDataPacket(Set<String> discoveredFigures) {
        this.discoveredFigures = new HashSet<>(discoveredFigures);
    }

    public static void encode(SyncDiscoveryDataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.discoveredFigures.size());
        for (String figureId : packet.discoveredFigures) {
            buffer.writeUtf(figureId);
        }
    }

    public static SyncDiscoveryDataPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Set<String> discoveredFigures = new HashSet<>();
        for (int i = 0; i < size; i++) {
            discoveredFigures.add(buffer.readUtf());
        }
        return new SyncDiscoveryDataPacket(discoveredFigures);
    }

    public static void handle(SyncDiscoveryDataPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
            LOGGER.info("Received discovery data sync: {} figures discovered", packet.discoveredFigures.size());
            ClientDiscoveryManager.setData(packet.discoveredFigures);
        });
        context.setPacketHandled(true);
    }

    public Set<String> getDiscoveredFigures() {
        return discoveredFigures;
    }
}
