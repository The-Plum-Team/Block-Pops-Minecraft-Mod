package com.theplumteam.network;

import com.theplumteam.client.discovery.ClientDiscoveryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server-to-client packet that syncs the player's complete set of discovered figures.
 * Sent when a player logs in to ensure their collection is up-to-date.
 */
public class SyncDiscoveryDataPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncDiscoveryDataPacket.class);

    private final Set<String> discoveredFigures;
    private final Map<String, String> figureSkins;

    public SyncDiscoveryDataPacket(Set<String> discoveredFigures, Map<String, String> figureSkins) {
        this.discoveredFigures = new HashSet<>(discoveredFigures);
        this.figureSkins = new HashMap<>(figureSkins);
    }

    public static void encode(SyncDiscoveryDataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.discoveredFigures.size());
        for (String figureId : packet.discoveredFigures) {
            buffer.writeUtf(figureId);
        }

        // Encode figure skins map
        buffer.writeInt(packet.figureSkins.size());
        for (Map.Entry<String, String> entry : packet.figureSkins.entrySet()) {
            buffer.writeUtf(entry.getKey());
            buffer.writeUtf(entry.getValue());
        }
    }

    public static SyncDiscoveryDataPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Set<String> discoveredFigures = new HashSet<>();
        for (int i = 0; i < size; i++) {
            discoveredFigures.add(buffer.readUtf());
        }

        // Decode figure skins map
        int skinsSize = buffer.readInt();
        Map<String, String> figureSkins = new HashMap<>();
        for (int i = 0; i < skinsSize; i++) {
            String figureId = buffer.readUtf();
            String skinUrl = buffer.readUtf();
            figureSkins.put(figureId, skinUrl);
        }

        return new SyncDiscoveryDataPacket(discoveredFigures, figureSkins);
    }

    public static void handle(SyncDiscoveryDataPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
            LOGGER.info("Received discovery data sync: {} figures discovered, {} skins",
                    packet.discoveredFigures.size(), packet.figureSkins.size());
            ClientDiscoveryManager.setData(packet.discoveredFigures, packet.figureSkins);
        });
        context.setPacketHandled(true);
    }

    public Set<String> getDiscoveredFigures() {
        return discoveredFigures;
    }
}
