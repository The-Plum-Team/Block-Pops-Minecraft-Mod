package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client answer to a {@link CustomCollectionRequestPacket}: what happened, and
 * the collection's name when there is one.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class CustomCollectionStatusPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "custom_collection_status");

    private final CustomCollections.Status status;

    public CustomCollectionStatusPacket(CustomCollections.Status status) {
        this.status = status;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeEnum(status.result());
        buffer.writeUtf(status.name(), 64);
        return buffer;
    }

    public static CustomCollectionStatusPacket decode(FriendlyByteBuf buffer) {
        CustomCollections.Result result = buffer.readEnum(CustomCollections.Result.class);
        return new CustomCollectionStatusPacket(new CustomCollections.Status(result, buffer.readUtf(64)));
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        CustomCollectionStatusPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.remote.ClientCustomCollections.showStatus(packet.status));
    }

    public static void send(ServerPlayer player, CustomCollections.Status status) {
        if (NetworkManager.canPlayerReceive(player, ID)) {
            PacketNetworking.sendToPlayer(player, ID, new CustomCollectionStatusPacket(status).encode());
        }
    }
}
