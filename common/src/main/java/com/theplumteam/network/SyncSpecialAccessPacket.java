package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.SpecialAccess;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet that syncs the plush collections whose specials the player receives.
 * Sent when the player opens a gacha machine, and whenever the supporter service answers.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class SyncSpecialAccessPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_special_access");

    private final List<String> collectionIds;

    public SyncSpecialAccessPacket(List<String> collectionIds) {
        this.collectionIds = collectionIds;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(collectionIds.size());
        for (String collectionId : collectionIds) {
            buffer.writeUtf(collectionId);
        }
        return buffer;
    }

    public static SyncSpecialAccessPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        List<String> collectionIds = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            collectionIds.add(buffer.readUtf());
        }
        return new SyncSpecialAccessPacket(collectionIds);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncSpecialAccessPacket packet = decode(buf);

        context.queue(() -> {
            BlockPopsMod.logDebug("Received special access to {} collections from server", packet.collectionIds.size());
            // Use fully qualified name to avoid importing a class that reads the client connection
            com.theplumteam.client.ClientSpecialAccess.set(packet.collectionIds);
        });
    }

    /**
     * Send a player the collections whose specials they receive
     */
    public static void sendToPlayer(ServerPlayer player) {
        //? if >=1.21 {
        /*// A client on an older build never registered this payload, and NeoForge refuses to send it
        if (!NetworkManager.canPlayerReceive(player, ID)) {
            return;
        }
        *///? }
        SyncSpecialAccessPacket packet = new SyncSpecialAccessPacket(SpecialAccess.collectionsFor(player));
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
    }
}
