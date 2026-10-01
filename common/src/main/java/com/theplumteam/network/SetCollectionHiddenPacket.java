package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server packet that hides a collection from every player's collection list,
 * or shows it again. Requires operator permissions.
 */
public class SetCollectionHiddenPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetCollectionHiddenPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "set_collection_hidden");

    private final String collectionId;
    private final boolean hidden;

    public SetCollectionHiddenPacket(String collectionId, boolean hidden) {
        this.collectionId = collectionId;
        this.hidden = hidden;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(collectionId);
        buffer.writeBoolean(hidden);
        return buffer;
    }

    public static SetCollectionHiddenPacket decode(FriendlyByteBuf buffer) {
        String collectionId = buffer.readUtf();
        boolean hidden = buffer.readBoolean();
        return new SetCollectionHiddenPacket(collectionId, hidden);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SetCollectionHiddenPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                // Check permissions (Level 2 = OP/Cheats)
                if (!ServerLevels.hasCommandLevel(player, 2)) {
                    LOGGER.warn("Player {} tried to change collection visibility without permission",
                            player.getName().getString());
                    return;
                }

                // Only a collection the list can show may be hidden; any ID may be shown again
                if (packet.hidden && (!CollectionRegistry.hasCollection(packet.collectionId)
                        || "default".equals(packet.collectionId))) {
                    LOGGER.warn("Player {} tried to hide unknown collection {}",
                            player.getName().getString(), packet.collectionId);
                    return;
                }

                ServerConfig config = ServerConfig.getInstance();
                List<String> hiddenCollections = new ArrayList<>(config.getHiddenCollections());
                // Nothing to save or sync when the collection is already in the requested state
                if (hiddenCollections.contains(packet.collectionId) == packet.hidden) {
                    return;
                }
                if (packet.hidden) {
                    hiddenCollections.add(packet.collectionId);
                } else {
                    hiddenCollections.remove(packet.collectionId);
                }

                // Save the new list, then sync it to every player
                if (config.setHiddenCollections(hiddenCollections)) {
                    BlockPopsMod.logDebug("Player {} set collection {} hidden: {}",
                            player.getName().getString(), packet.collectionId, packet.hidden);
                    SyncHiddenCollectionsPacket.sendToAllPlayers(ServerLevels.serverOf(player));
                }
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
