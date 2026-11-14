package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.ClientCapabilities;
import com.theplumteam.server.PlayerCollections;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet a client sends once after joining to say it loads player collections,
 * and by which protocol. The server sends player collections only to a client that said so; a
 * server from before them drops the packet.
 */
public class PlayerCollectionsHelloPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "player_collections_hello");
    private static final int MAX_PROTOCOL = 1000;

    private final int protocol;

    public PlayerCollectionsHelloPacket(int protocol) {
        if (protocol < 1 || protocol > MAX_PROTOCOL) {
            throw new IllegalArgumentException("Player collections protocol out of range: " + protocol);
        }
        this.protocol = protocol;
    }

    public int protocol() {
        return protocol;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(protocol);
        return buffer;
    }

    public static PlayerCollectionsHelloPacket decode(FriendlyByteBuf buffer) {
        return new PlayerCollectionsHelloPacket(buffer.readVarInt());
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        PlayerCollectionsHelloPacket packet = decode(buf);
        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                ClientCapabilities.hello(player, packet.protocol);
                PlayerCollections.sendTo(player);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
